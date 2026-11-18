package com.pitgpt.galaxy.data.media

import android.content.pm.ApplicationInfo
import com.pitgpt.galaxy.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import org.xmlpull.v1.XmlPullParser
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

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
    fun applicationManifestReferencesBothBackupRuleResources() {
        val application = readSourceApplicationManifest()

        assertEquals(
            "Legacy full-backup rules must be wired through android:fullBackupContent",
            "@xml/backup_rules",
            application.getAttributeNS(ANDROID_NAMESPACE, "fullBackupContent")
        )
        assertEquals(
            "Cloud backup and device-transfer rules must be wired through android:dataExtractionRules",
            "@xml/data_extraction_rules",
            application.getAttributeNS(ANDROID_NAMESPACE, "dataExtractionRules")
        )
    }

    private fun readSourceApplicationManifest(): Element {
        val manifestFile = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        ).firstOrNull(File::isFile)
            ?: throw AssertionError("Could not locate app/src/main/AndroidManifest.xml from the unit-test working directory.")

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val document = manifestFile.inputStream().use { input ->
            factory.newDocumentBuilder().parse(input)
        }

        return document.getElementsByTagName("application").item(0) as? Element
            ?: throw AssertionError("The Android manifest does not contain an application element.")
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
