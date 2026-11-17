package com.pitgpt.galaxy.data.media

import com.pitgpt.galaxy.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import org.xmlpull.v1.XmlPullParser

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
    fun manifestWiresBothBackupRuleResourcesAndKeepsBackupEnabled() {
        val application = readApplicationManifest()
        val androidNamespace = "http://schemas.android.com/apk/res/android"

        assertEquals(
            "Backup must remain enabled so the explicit exclusion policy is applied",
            "true",
            application.getAttributeNS(androidNamespace, "allowBackup")
        )
        assertEquals(
            "Android 11 and earlier must use the explicit full-backup exclusions",
            "@xml/backup_rules",
            application.getAttributeNS(androidNamespace, "fullBackupContent")
        )
        assertEquals(
            "Android 12 and later must use explicit cloud-backup and device-transfer exclusions",
            "@xml/data_extraction_rules",
            application.getAttributeNS(androidNamespace, "dataExtractionRules")
        )
    }

    private fun readApplicationManifest(): Element {
        val manifest = sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        ).firstOrNull(File::isFile)
            ?: error("The app source AndroidManifest.xml could not be located from the Gradle test directory.")

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val document = factory.newDocumentBuilder().parse(manifest)
        return document.getElementsByTagName("application").item(0) as? Element
            ?: error("The app source manifest does not contain an application element.")
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
        val deviceOnlyExclusions = setOf(
            "sharedpref" to "galaxy_secure_auth.xml",
            "sharedpref" to "galaxy_provider_vault.xml",
            "sharedpref" to "galaxy_database_key.xml",
            "database" to ".",
            "file" to "${EncryptedMediaStore.MEDIA_DIRECTORY}/"
        )
    }
}
