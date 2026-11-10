package com.oceanlab.pichix.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ApkHistoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun entry(dir: File, version: String, code: Int = 0, notes: String = ""): ApkHistory.Entry {
        val name = AppUpdater.apkFileName(version)
        File(dir, name).writeText("apk-$version")
        return ApkHistory.Entry(version, code, 1_700_000_000_000L, 10L, "f".repeat(64), notes, name)
    }

    @Test
    fun encodeDecode_roundTrip() {
        val e = ApkHistory.Entry("0.2.44", 147, 123L, 456L, "ab".repeat(32), "notas\nlínea 2", "PichiX-v0.2.44.apk")
        val back = ApkHistory.decode(ApkHistory.encode(listOf(e)))
        assertEquals(listOf(e), back)
    }

    @Test
    fun decode_corruptOrEmpty_isEmpty() {
        assertTrue(ApkHistory.decode("").isEmpty())
        assertTrue(ApkHistory.decode("{no json").isEmpty())
        assertTrue(ApkHistory.decode("""{"entries":"x"}""").isEmpty())
    }

    @Test
    fun decode_rejectsPathTraversalFileNames() {
        val json = """{"entries":[{"version":"1.0","file":"../evil.apk"},{"version":"1.1","file":"ok.apk"}]}"""
        assertEquals(listOf("ok.apk"), ApkHistory.decode(json).map { it.file })
    }

    @Test
    fun add_sortsByNumericVersionDescending_andReplacesSameVersion() {
        val dir = tmp.newFolder("h")
        val h = ApkHistory(dir)
        h.add(entry(dir, "0.2.9"))
        h.add(entry(dir, "0.2.100"))
        h.add(entry(dir, "0.2.44", notes = "primera"))
        h.add(entry(dir, "0.2.44", notes = "segunda"))
        val list = h.entries()
        assertEquals(listOf("0.2.100", "0.2.44", "0.2.9"), list.map { it.version })
        assertEquals("segunda", h.find("v0.2.44")!!.notes)
    }

    @Test
    fun entries_dropsMissingFiles_andAdoptsOrphans() {
        val dir = tmp.newFolder("h2")
        val h = ApkHistory(dir)
        val a = entry(dir, "0.2.1")
        h.add(a)
        File(dir, a.file).delete()
        File(dir, "PichiX-v0.2.7.apk").writeText("huérfano")
        val list = h.entries()
        assertEquals(listOf("0.2.7"), list.map { it.version })
        assertNull(h.find("0.2.1"))
        assertNotNull(h.find("0.2.7"))
    }

    @Test
    fun remove_deletesFileAndEntry() {
        val dir = tmp.newFolder("h3")
        val h = ApkHistory(dir)
        val e = entry(dir, "0.2.5")
        h.add(e)
        assertTrue(h.remove(e))
        assertFalse(File(dir, e.file).exists())
        assertTrue(h.entries().isEmpty())
    }

    @Test
    fun pruneKeepLatest_keepsNewestAndProtectedVersion() {
        val dir = tmp.newFolder("h4")
        val h = ApkHistory(dir)
        listOf("0.2.1", "0.2.2", "0.2.3", "0.2.4").forEach { h.add(entry(dir, it)) }
        val removed = h.pruneKeepLatest(keep = 2, protectVersion = "0.2.1")
        assertEquals(listOf("0.2.2"), removed.map { it.version })
        assertEquals(listOf("0.2.4", "0.2.3", "0.2.1"), h.entries().map { it.version })
    }

    @Test
    fun pruneKeepLatest_zeroKeepsEverything() {
        val dir = tmp.newFolder("h5")
        val h = ApkHistory(dir)
        listOf("0.2.1", "0.2.2").forEach { h.add(entry(dir, it)) }
        assertTrue(h.pruneKeepLatest(0).isEmpty())
        assertEquals(2, h.entries().size)
    }
}
