package com.oceanlab.pichix.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun compareVersions_numericPerComponent() {
        assertTrue(AppUpdater.compareVersions("9.2.100", "9.2.99") > 0)
        assertTrue(AppUpdater.compareVersions("0.2.9", "0.2.10") < 0)
        assertEquals(0, AppUpdater.compareVersions("0.2.44", "0.2.44"))
    }

    @Test
    fun compareVersions_prefixAndMissingComponents() {
        assertEquals(0, AppUpdater.compareVersions("v0.2.44", "0.2.44"))
        assertEquals(0, AppUpdater.compareVersions("V1.0", "1.0.0"))
        assertTrue(AppUpdater.compareVersions("v1.0.1", "1.0") > 0)
        assertTrue(AppUpdater.compareVersions("1", "0.9.99") > 0)
    }

    @Test
    fun compareVersions_ignoresNonNumericSuffix() {
        assertEquals(0, AppUpdater.compareVersions("0.2.44-beta", "0.2.44"))
        assertTrue(AppUpdater.compareVersions("0.3", "0.2.44") > 0)
    }

    private fun releaseJson(assets: String, body: String = "Notas de la versión") =
        """{"tag_name":"v0.2.44","body":${org.json.JSONObject.quote(body)},"assets":[$assets]}"""

    @Test
    fun parseRelease_picksFirstApk() {
        val json = releaseJson(
            """
            {"name":"notes.txt","url":"https://api.github.com/a/1","size":5},
            {"name":"PichiX-v0.2.44.apk","url":"https://api.github.com/a/2","size":1234},
            {"name":"otro.apk","url":"https://api.github.com/a/3","size":99},
            {"name":"PichiX-v0.2.44.apk.sha256","url":"https://api.github.com/a/4","size":80}
            """,
        )
        val r = AppUpdater.parseRelease(json)
        assertNotNull(r)
        r!!
        assertEquals("v0.2.44", r.tag)
        assertEquals("0.2.44", r.version)
        assertEquals("PichiX-v0.2.44.apk", r.apkName)
        assertEquals("https://api.github.com/a/2", r.apkApiUrl)
        assertEquals(1234L, r.apkSize)
        assertEquals("https://api.github.com/a/4", r.shaAssetApiUrl)
        assertEquals("Notas de la versión", r.notes)
    }

    @Test
    fun parseRelease_nullWithoutApk() {
        assertNull(AppUpdater.parseRelease(releaseJson("""{"name":"x.zip","url":"https://api.github.com/a/1","size":1}""")))
        assertNull(AppUpdater.parseRelease("""{"tag_name":"v1.0.0"}"""))
        assertNull(AppUpdater.parseRelease(releaseJson("")))
    }

    @Test
    fun parseRelease_shaFromNotes() {
        val sha = "a".repeat(32) + "B".repeat(32)
        val json = releaseJson(
            """{"name":"p.apk","url":"https://api.github.com/a/2","size":10}""",
            body = "SHA-256: $sha",
        )
        assertEquals(sha.lowercase(), AppUpdater.parseRelease(json)!!.shaFromNotes)
    }

    @Test
    fun extractSha256_ignoresWrongLengths() {
        assertNull(AppUpdater.extractSha256("abc123"))
        assertNull(AppUpdater.extractSha256("a".repeat(65)))
        val h = "0123456789abcdef".repeat(4)
        assertEquals(h, AppUpdater.extractSha256("$h  PichiX-v1.apk"))
    }

    @Test
    fun apkFileName_normalizesPrefix() {
        assertEquals("PichiX-v0.2.44.apk", AppUpdater.apkFileName("v0.2.44"))
        assertEquals("PichiX-v0.2.44.apk", AppUpdater.apkFileName("0.2.44"))
    }
}
