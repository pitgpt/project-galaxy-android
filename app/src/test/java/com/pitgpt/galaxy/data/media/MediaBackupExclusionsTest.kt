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
    fun manifestActivatesBothBackupRuleResources() {
        val applicationInfo: ApplicationInfo = RuntimeEnvironment.getApplication().applicationInfo

        assertTrue("Backup policy must remain explicitly configured", applicationInfo.allowBackup)
        assertEquals(
            "Legacy full-backup rules must be attached to the application",
            R.xml.backup_rules,
            applicationInfo.fullBackupContent
        )
        assertEquals(
            "Android 12+ extraction rules must be attached to the application",
            R.xml.data_extraction_rules,
            applicationInfo.dataExtractionRules
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
