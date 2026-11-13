package com.oceanlab.pichix.update

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * Historial local de APKs descargados: carpeta [dir] con `PichiX-vX.Y.Z.apk` + `index.json`.
 * Sin dependencias de Android. Nunca borra nada por sí solo: solo [remove] y [pruneKeepLatest] (a petición).
 */
class ApkHistory(private val dir: File) {

    data class Entry(
        val version: String,
        val versionCode: Int,      // 0 = desconocido
        val downloadedAt: Long,
        val size: Long,
        val sha256: String,
        val notes: String,
        val file: String,
    )

    private val indexFile get() = File(dir, INDEX_NAME)

    /** Entradas con su fichero presente, la versión más alta primero. */
    fun entries(): List<Entry> {
        dir.mkdirs()
        val loaded = decode(runCatching { indexFile.readText(Charsets.UTF_8) }.getOrDefault(""))
        val present = loaded.filter { File(dir, it.file).isFile }
        val known = present.map { it.file }.toSet()
        // APKs huérfanos (índice perdido/corrupto o copiados a mano): se reincorporan
        val orphans = dir.listFiles { f -> f.isFile && ORPHAN_REGEX.matches(f.name) && f.name !in known }
            .orEmpty().map { f ->
                Entry(
                    version = ORPHAN_REGEX.find(f.name)!!.groupValues[1],
                    versionCode = 0,
                    downloadedAt = f.lastModified(),
                    size = f.length(),
                    sha256 = "", // desconocido: el fichero no lo descargó esta app
                    notes = "",
                    file = f.name,
                )
            }
        val all = present + orphans
        if (all.size != loaded.size || orphans.isNotEmpty()) save(all)
        return all.sortedWith { a, b -> AppUpdater.compareVersions(b.version, a.version) }
    }

    fun find(version: String): Entry? {
        val v = AppUpdater.normalizeVersion(version)
        return entries().firstOrNull { AppUpdater.compareVersions(it.version, v) == 0 }
    }

    fun fileOf(entry: Entry): File = File(dir, entry.file)

    /** Registra (o reemplaza) la entrada de una versión cuyo APK ya está en [dir]. */
    fun add(entry: Entry) {
        val others = entries().filterNot { AppUpdater.compareVersions(it.version, entry.version) == 0 }
        save(others + entry)
    }

    fun remove(entry: Entry): Boolean {
        val f = File(dir, entry.file)
        val ok = !f.exists() || f.delete()
        save(entries().filterNot { it.file == entry.file })
        return ok
    }

    /**
     * Conserva las [keep] más recientes (y siempre [protectVersion], p. ej. la instalada).
     * Devuelve las entradas eliminadas. keep <= 0 no borra nada.
     */
    fun pruneKeepLatest(keep: Int, protectVersion: String? = null): List<Entry> {
        if (keep <= 0) return emptyList()
        val victims = entries().drop(keep).filter {
            protectVersion == null || AppUpdater.compareVersions(it.version, protectVersion) != 0
        }
        victims.forEach { remove(it) }
        return victims
    }

    private fun save(list: List<Entry>) {
        dir.mkdirs()
        val tmp = File(dir, "$INDEX_NAME.tmp")
        tmp.writeText(encode(list), Charsets.UTF_8)
        indexFile.delete()
        tmp.renameTo(indexFile)
    }

    companion object {
        const val INDEX_NAME = "index.json"
        private val ORPHAN_REGEX = Regex("^PichiX-v(\\d+(?:\\.\\d+)*)\\.apk$")

        fun encode(list: List<Entry>): String {
            val arr = JSONArray()
            list.forEach { e ->
                arr.put(
                    JSONObject()
                        .put("version", e.version)
                        .put("versionCode", e.versionCode)
                        .put("downloadedAt", e.downloadedAt)
                        .put("size", e.size)
                        .put("sha256", e.sha256)
                        .put("notes", e.notes)
                        .put("file", e.file),
                )
            }
            return JSONObject().put("entries", arr).toString(2)
        }

        /** Índice corrupto o vacío → lista vacía (los APK se reincorporan por nombre de fichero). */
        fun decode(json: String): List<Entry> {
            if (json.isBlank()) return emptyList()
            return try {
                val arr = JSONObject(json).optJSONArray("entries") ?: return emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val file = o.optString("file", "")
                    val version = o.optString("version", "")
                    if (file.isEmpty() || version.isEmpty() || file.contains('/') || file.contains('\\')) {
                        return@mapNotNull null
                    }
                    Entry(
                        version = version,
                        versionCode = o.optInt("versionCode", 0),
                        downloadedAt = o.optLong("downloadedAt", 0L),
                        size = o.optLong("size", 0L),
                        sha256 = o.optString("sha256", ""),
                        notes = o.optString("notes", ""),
                        file = file,
                    )
                }
            } catch (e: JSONException) {
                emptyList()
            }
        }
    }
}
