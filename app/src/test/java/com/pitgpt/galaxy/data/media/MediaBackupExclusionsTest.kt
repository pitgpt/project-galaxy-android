package com.pitgpt.galaxy.data.media

import android.content.pm.ApplicationInfo
import com.pitgpt.galaxy.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaBackupExclusionsTest {
    @Test
    fun allDeviceBoundStateIsExcludedFromFullBackup() {
        val exclusions = readExclusions(R.xml.backup_rules)["root"].orEmpty()

        assertTrue(
            "Device-bound credentials, database and encrypted media must be excluded from full backup",
            exclusions.containsAll(deviceOnlyExclusions)
        )
    }

    @Test
    fun allDeviceBoundStateIsExcludedFromCloudBackupAndDeviceTransfer() {
        val exclusions = readExclusions(R.xml.data_extraction_rules)

        listOf("cloud-backup", "device-transfer").forEach { scope ->
            assertTrue(
                "Device-bound credentials, database and encrypted media must be excluded from $scope",
                exclusions[scope].orEmpty().containsAll(deviceOnlyExclusions)
            )
        }
    }

    @Test
    fun applicationManifestKeepsBackupEnabledForItsExplicitExclusionRules() {
        val applicationInfo = RuntimeEnvironment.getApplication().applicationInfo

        assertTrue(
            "The device-only backup policy expects backup to remain enabled with explicit exclusions",
            applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0
        )
    }

    @Test
    fun manifestReferencesBothBackupRuleResources() {
        val attributes = readManifestApplicationAttributes()

        assertEquals(
            "Legacy full-backup exclusions must be attached to the application",
            "@xml/backup_rules",
            attributes["fullBackupContent"]
        )
        assertEquals(
            "Android 12+ extraction exclusions must be attached to the application",
            "@xml/data_extraction_rules",
            attributes["dataExtractionRules"]
        )
    }

    private fun readExclusions(resourceId: Int): Map<String, Set<Pair<String, String>>> {
        val parser = RuntimeEnvironment.getApplication().resources.getXml(resourceId)
        val exclusions = mutableMapOf<String, MutableSet<Pair<String, String>>>()
        var scope = "root"
        val scopedElements = setOf("cloud-backup", "device-transfer")

        try {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name in scopedElements) {
                            scope = parser.name
                        } else if (parser.name == "exclude") {
                            val domain = parser.getAttributeValue(null, "domain")
                            val path = parser.getAttributeValue(null, "path")
                            if (domain != null && path != null) {
                                exclusions.getOrPut(scope) { mutableSetOf() }.add(domain to path)
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        if (parser.name in scopedElements) scope = "root"
                    }
                }
                parser.next()
            }
        } finally {
            parser.close()
        }

        return exclusions.mapValues { it.value.toSet() }
    }

    private fun readManifestApplicationAttributes(): Map<String, String?> {
        val manifestFile = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        ).firstOrNull(File::isFile) ?: throw AssertionError(
            "Could not locate the app module's src/main/AndroidManifest.xml from ${System.getProperty("user.dir")}"
        )
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        }

        return manifestFile.inputStream().use { input ->
            parser.setInput(input, Charsets.UTF_8.name())
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "application") {
                    return@use listOf("fullBackupContent", "dataExtractionRules").associateWith { name ->
                        parser.getAttributeValue(ANDROID_NAMESPACE, name)
                    }
                }
                parser.next()
            }
            throw AssertionError("The application element is missing from the Android manifest")
        }
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

        val deviceOnlyExclusions = setOf(
            "sharedpref" to "galaxy_secure_auth.xml",
            "sharedpref" to "galaxy_provider_vault.xml",
            "sharedpref" to "galaxy_database_key.xml",
            "database" to ".",
            "file" to "${EncryptedMediaStore.MEDIA_DIRECTORY}/"
        )
    }
}
