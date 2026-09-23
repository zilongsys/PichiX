package com.oceanlab.pichix.dashboardcontrol

// ============================================================================
// DashboardSocket — cliente WebSocket mínimo (RFC 6455) sobre un Socket normal.
//
// Por qué no OkHttp: la app no lo tiene y así no hay que tocar build.gradle.
// Además un Socket directo no pasa por la política de "cleartext" de Android,
// así que tampoco hace falta network_security_config para ws:// en la red local.
//
// Solo lo que el Centro de control necesita: texto, ping/pong, close. Sin TLS.
// Escrituras sincronizadas (varios hilos pueden enviar); una sola lectura a la vez.
// ============================================================================

import android.util.Base64
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom

internal class DashboardSocket private constructor(
    private val socket: Socket,
    private val input: BufferedInputStream,
    private val output: OutputStream,
) : Closeable {

    private val writeLock = Any()
    private val random = SecureRandom()

    /** Código de cierre que mandó la PC (4001 = token inválido…). -1 si se cortó sin aviso. */
    @Volatile var closeCode: Int = -1
        private set

    companion object {
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val MAX_MESSAGE = 4 * 1024 * 1024

        /** Conecta y hace el handshake. Lanza IOException si algo falla. [readTimeoutMs] vale para toda la conexión. */
        fun connect(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): DashboardSocket {
            val uri = URI(url)
            if (uri.scheme != "ws") throw IOException("solo ws:// (red local)")
            val host = uri.host ?: throw IOException("dirección sin host")
            val port = if (uri.port > 0) uri.port else 80
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.soTimeout = connectTimeoutMs
                val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
                val output = socket.getOutputStream()
                val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
                val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
                val req = "GET $path HTTP/1.1\r\n" +
                    "Host: $host:$port\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n"
                output.write(req.toByteArray(Charsets.US_ASCII))
                output.flush()
                val status = readLine(input)
                val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input)
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                if (code != 101) throw IOException("la PC respondió HTTP $code")
                val expected = Base64.encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)),
                    Base64.NO_WRAP,
                )
                if (headers["sec-websocket-accept"] != expected) throw IOException("handshake inválido")
                socket.soTimeout = readTimeoutMs
                return DashboardSocket(socket, input, output)
            } catch (t: Throwable) {
                try { socket.close() } catch (_: Throwable) { }
                throw if (t is IOException) t else IOException(t.message ?: t.javaClass.simpleName, t)
            }
        }

        private fun readLine(input: BufferedInputStream): String {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException("conexión cerrada en el handshake")
                if (b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
                if (sb.length > 8192) throw IOException("cabecera demasiado larga")
            }
            return sb.toString()
        }
    }

    fun sendText(text: String) = sendFrame(0x1, text.toByteArray(Charsets.UTF_8))

    fun sendPing() = sendFrame(0x9, ByteArray(0))

    /**
     * Espera el siguiente mensaje de texto. Responde pings solo.
     * Devuelve null si la PC cerró la conexión ([closeCode] dice por qué).
     * Lanza IOException si la red se cae o pasa el timeout de lectura.
     */
    fun readText(): String? {
        val msg = ByteArrayOutputStream()
        var inMessage = false
        while (true) {
            val b1 = readByte()
            val b2 = readByte()
            val fin = (b1 and 0x80) != 0
            val op = b1 and 0x0F
            val masked = (b2 and 0x80) != 0
            var len = (b2 and 0x7F).toLong()
            if (len == 126L) len = ((readByte() shl 8) or readByte()).toLong()
            else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or readByte().toLong() } }
            if (len > MAX_MESSAGE) throw IOException("mensaje demasiado grande")
            val mask = if (masked) ByteArray(4).also { readFully(it) } else null
            val data = ByteArray(len.toInt()).also { readFully(it) }
            if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (op) {
                0x8 -> {
                    closeCode = if (data.size >= 2) ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF) else 1005
                    try { sendFrame(0x8, data.copyOf(minOf(2, data.size))) } catch (_: IOException) { }
                    return null
                }
                0x9 -> { sendFrame(0xA, data); continue }
                0xA -> continue
                0x1, 0x2 -> { msg.reset(); msg.write(data); inMessage = true }
                0x0 -> { if (!inMessage) throw IOException("fragmento suelto"); msg.write(data) }
                else -> throw IOException("opcode $op no soportado")
            }
            if (msg.size() > MAX_MESSAGE) throw IOException("mensaje demasiado grande")
            if (fin && inMessage) return msg.toString("UTF-8")
        }
    }

    fun closeWith(code: Int) {
        try {
            sendFrame(0x8, byteArrayOf((code shr 8).toByte(), code.toByte()))
        } catch (_: Throwable) { }
        try { socket.close() } catch (_: Throwable) { }
    }

    override fun close() = closeWith(1000)

    private fun sendFrame(op: Int, payload: ByteArray) {
        val n = payload.size
        val header = ByteArrayOutputStream(14)
        header.write(0x80 or op)
        when {
            n < 126 -> header.write(0x80 or n)
            n < 65536 -> { header.write(0x80 or 126); header.write(n shr 8); header.write(n and 0xFF) }
            else -> { header.write(0x80 or 127); for (i in 7 downTo 0) header.write(((n.toLong() shr (8 * i)) and 0xFF).toInt()) }
        }
        val mask = ByteArray(4)
        synchronized(writeLock) {
            random.nextBytes(mask)
            header.write(mask)
            val masked = ByteArray(n)
            for (i in 0 until n) masked[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            output.write(header.toByteArray())
            output.write(masked)
            output.flush()
        }
    }

    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw EOFException("la PC cerró la conexión")
        return b
    }

    private fun readFully(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) throw EOFException("la PC cerró la conexión")
            off += r
        }
    }
}
