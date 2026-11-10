package com.oceanlab.pichix.update

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Lógica de actualización desde GitHub Releases. Sin dependencias de Android (testeable en JVM).
 * El token solo viaja a api.github.com; nunca a los hosts a los que redirige la descarga.
 */
object AppUpdater {

    const val REPO = "zilongsys/PichiX"
    private const val API_HOST = "api.github.com"
    private const val USER_AGENT = "PichiX-Updater"
    private const val MAX_REDIRECTS = 5
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_SHA_FILE_BYTES = 4096

    data class ReleaseInfo(
        val tag: String,
        val version: String,
        val notes: String,
        val apkName: String,
        val apkApiUrl: String,
        val apkSize: Long,
        /** URL de API del asset `.sha256`, si el release lo incluye. */
        val shaAssetApiUrl: String?,
        /** SHA-256 hallado en las notas del release, si lo hay. */
        val shaFromNotes: String?,
    )

    sealed class CheckResult {
        data class Available(val release: ReleaseInfo) : CheckResult()
        data class UpToDate(val version: String) : CheckResult()
        data class Failure(val kind: Kind, val httpCode: Int = 0) : CheckResult()
    }

    enum class Kind { NO_TOKEN, UNAUTHORIZED, NOT_FOUND, NO_NETWORK, NO_APK, HTTP_ERROR, BAD_RESPONSE, SHA_MISMATCH, SIZE_MISMATCH }

    class UpdateException(val kind: Kind, val httpCode: Int = 0) : Exception(kind.name)

    // ---------- Puro ----------

    /** Quita prefijo `v`/`V` y espacios. */
    fun normalizeVersion(raw: String): String = raw.trim().removePrefix("v").removePrefix("V").trim()

    /** Compara numéricamente por componentes: 0.2.100 > 0.2.99; los faltantes cuentan como 0. */
    fun compareVersions(a: String, b: String): Int {
        val pa = components(a)
        val pb = components(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private fun components(v: String): List<Int> =
        normalizeVersion(v).split('.').map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }

    /** Primer asset `.apk`; null si no hay. Lanza JSONException si el JSON es inválido. */
    fun parseRelease(json: String): ReleaseInfo? {
        val root = JSONObject(json)
        val tag = root.optString("tag_name", "")
        val assets = root.optJSONArray("assets") ?: return null
        var apk: JSONObject? = null
        var sha: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "")
            if (apk == null && name.endsWith(".apk", ignoreCase = true)) apk = a
            if (sha == null && name.endsWith(".sha256", ignoreCase = true)) sha = a
        }
        val apkAsset = apk ?: return null
        val apkUrl = apkAsset.optString("url", "")
        if (apkUrl.isEmpty()) return null
        val notes = root.optString("body", "")
        return ReleaseInfo(
            tag = tag,
            version = normalizeVersion(tag),
            notes = notes,
            apkName = apkAsset.optString("name", ""),
            apkApiUrl = apkUrl,
            apkSize = apkAsset.optLong("size", -1L),
            shaAssetApiUrl = sha?.optString("url", "")?.takeIf { it.isNotEmpty() },
            shaFromNotes = extractSha256(notes),
        )
    }

    private val SHA_REGEX = Regex("(?<![0-9a-fA-F])[0-9a-fA-F]{64}(?![0-9a-fA-F])")

    /** Primer hash de 64 hex en el texto (contenido de un .sha256 o notas), en minúsculas. */
    fun extractSha256(text: String?): String? =
        text?.let { SHA_REGEX.find(it)?.value?.lowercase() }

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun apkFileName(version: String): String = "PichiX-v${normalizeVersion(version)}.apk"

    // ---------- Red ----------

    /** Consulta el último release y lo compara con [installedVersion]. Bloqueante: llamar en hilo de fondo. */
    fun checkForUpdate(token: String, installedVersion: String, repo: String = REPO): CheckResult {
        if (token.isBlank()) return CheckResult.Failure(Kind.NO_TOKEN)
        val conn = try {
            open("https://$API_HOST/repos/$repo/releases/latest", token.trim(), "application/vnd.github+json")
        } catch (e: IOException) {
            return CheckResult.Failure(Kind.NO_NETWORK)
        }
        try {
            val code = conn.responseCode
            when {
                code == 401 || code == 403 -> return CheckResult.Failure(Kind.UNAUTHORIZED, code)
                code == 404 -> return CheckResult.Failure(Kind.NOT_FOUND, code)
                code != 200 -> return CheckResult.Failure(Kind.HTTP_ERROR, code)
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val release = try {
                parseRelease(body)
            } catch (e: JSONException) {
                return CheckResult.Failure(Kind.BAD_RESPONSE)
            } ?: return CheckResult.Failure(Kind.NO_APK)
            return if (compareVersions(release.version, installedVersion) > 0) {
                CheckResult.Available(release)
            } else {
                CheckResult.UpToDate(installedVersion)
            }
        } catch (e: IOException) {
            return CheckResult.Failure(Kind.NO_NETWORK)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Descarga el APK a `<dest>.part`, valida tamaño (y SHA-256 si hay) y solo entonces renombra a [dest].
     * Devuelve el SHA-256 del fichero. Lanza [UpdateException] / IOException. Bloqueante.
     * @param onProgress porcentaje 0..100 (o -1 si el tamaño es desconocido)
     * @param isCancelled si devuelve true se borra el `.part` y se lanza IOException
     */
    fun downloadApk(
        release: ReleaseInfo,
        token: String,
        dest: File,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean = { false },
    ): String {
        val expectedSha = resolveExpectedSha(release, token)
        val part = File(dest.parentFile, dest.name + ".part")
        part.delete()
        var conn: HttpURLConnection? = null
        try {
            conn = openFollowing(release.apkApiUrl, token.trim(), "application/octet-stream")
            val total = if (release.apkSize > 0) release.apkSize else conn.contentLengthLong
            var done = 0L
            var lastPct = -2
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw IOException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
            if (release.apkSize > 0 && part.length() != release.apkSize) {
                throw UpdateException(Kind.SIZE_MISMATCH)
            }
            val actual = sha256Hex(part)
            if (expectedSha != null && !expectedSha.equals(actual, ignoreCase = true)) {
                throw UpdateException(Kind.SHA_MISMATCH)
            }
            dest.delete()
            if (!part.renameTo(dest)) throw IOException("rename failed")
            return actual
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            conn?.disconnect()
        }
    }

    private fun resolveExpectedSha(release: ReleaseInfo, token: String): String? {
        release.shaAssetApiUrl?.let { url ->
            try {
                val conn = openFollowing(url, token.trim(), "application/octet-stream")
                try {
                    val bytes = conn.inputStream.use { it.readUpTo(MAX_SHA_FILE_BYTES) }
                    extractSha256(String(bytes, Charsets.UTF_8))?.let { return it }
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                // .sha256 ilegible: se intenta con las notas
            } catch (e: UpdateException) {
            }
        }
        return release.shaFromNotes
    }

    private fun InputStream.readUpTo(max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * GET a [url] (https) con el token solo en el primer salto; los `Location` se siguen a mano,
     * solo https, máx. [MAX_REDIRECTS], sin reenviar Authorization. Devuelve la conexión con HTTP 200.
     */
    private fun openFollowing(url: String, token: String, accept: String): HttpURLConnection {
        var current = url
        var useToken = true
        repeat(MAX_REDIRECTS + 1) {
            val conn = open(current, if (useToken) token else null, accept)
            val code = conn.responseCode
            when (code) {
                200 -> return conn
                301, 302, 303, 307, 308 -> {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrBlank()) throw UpdateException(Kind.BAD_RESPONSE, code)
                    val next = URL(URL(current), loc).toString()
                    if (!next.startsWith("https://", ignoreCase = true)) throw UpdateException(Kind.BAD_RESPONSE, code)
                    current = next
                    useToken = false
                }
                401, 403 -> { conn.disconnect(); throw UpdateException(Kind.UNAUTHORIZED, code) }
                404 -> { conn.disconnect(); throw UpdateException(Kind.NOT_FOUND, code) }
                else -> { conn.disconnect(); throw UpdateException(Kind.HTTP_ERROR, code) }
            }
        }
        throw UpdateException(Kind.BAD_RESPONSE)
    }

    private fun open(url: String, token: String?, accept: String): HttpURLConnection {
        if (!url.startsWith("https://", ignoreCase = true)) throw UpdateException(Kind.BAD_RESPONSE)
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("User-Agent", USER_AGENT)
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        return conn
    }
}
