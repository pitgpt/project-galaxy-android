package com.pitgpt.galaxy.data.media

import com.pitgpt.galaxy.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
class MediaBackupExclusionsTest {
    @Test
    fun encryptedMediaDirectoryIsExcludedFromFullBackup() {
        val exclusions = readExclusions(R.xml.backup_rules)

        assertTrue(
            "Encrypted media must be excluded from Android full backup",
            exclusions["root"]?.contains("file" to EncryptedMediaStore.MEDIA_DIRECTORY + "/") == true
        )
    }

    @Test
    fun encryptedMediaDirectoryIsExcludedFromCloudBackupAndDeviceTransfer() {
        val exclusions = readExclusions(R.xml.data_extraction_rules)
        val mediaExclusion = "file" to EncryptedMediaStore.MEDIA_DIRECTORY + "/"

        assertTrue(
            "Encrypted media must be excluded from cloud backup",
            exclusions["cloud-backup"]?.contains(mediaExclusion) == true
        )
        assertTrue(
            "Encrypted media must be excluded from device-to-device transfer",
            exclusions["device-transfer"]?.contains(mediaExclusion) == true
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
}
