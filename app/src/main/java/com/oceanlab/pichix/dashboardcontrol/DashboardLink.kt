package com.oceanlab.pichix.dashboardcontrol

// ============================================================================
// DashboardLink — conexión del teléfono con el Centro de control de la PC.
// Protocolo: docs/dashboardcontrol/PROTOCOLO.md (v2, con bandeja de salida).
//
// Reglas de velocidad (el bot nunca espera a la PC):
//  1. log()/event() solo comparan el nivel y encolan en memoria (sin JSON, sin disco,
//     sin red). Coste: unos pocos microsegundos, desde cualquier hilo.
//  2. Hilo "dash-writer" (prioridad mínima): pasa lo encolado a la bandeja en disco
//     (DashboardOutbox) en lotes cada 250 ms.
//  3. Hilo "dash-link": conecta, manda lotes numerados de la bandeja, estado cada 3 s.
//     Hilo "dash-read": recibe confirmaciones y comandos. Hilo "dash-cmd": ejecuta
//     los comandos de uno en uno.
//
// Sin conexión (minutos o días) todo se acumula en la bandeja (hasta 7 días) y al
// reconectar se envía en orden. La PC confirma lo que guardó (up_ack) y solo
// entonces se borra del teléfono: un corte a mitad no pierde ni duplica nada.
//
// Sin dependencias nuevas: WebSocket propio (DashboardSocket), SQLite de Android,
// org.json y hilos normales.
// ============================================================================

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Lo que cada app implementa (ver PichixDashboard). Todos los métodos se llaman desde
 * hilos de fondo y de uno en uno. Para tocar el motor o vistas usar [DashboardLink.onMain].
 */
interface DashboardBridge {
    /** Nombre en la PC: "PichiX", "MakiX", "YoniX". */
    val systemName: String
    val appVersion: String

    /** Ajustes que la PC puede ver y cambiar (null = ninguno). */
    val settings: DashboardSettings?

    /** Comandos propios además de pausar/reanudar: [{"name","label","confirm"?}]. */
    fun extraCommands(): JSONArray = JSONArray()

    /** Pausar el bot, igual que el botón de la app. */
    fun pause(): CommandResult
    fun resume(): CommandResult

    /** Estado para la tarjeta de la PC: status (running|paused|idle|error), statusText, metrics. Barato. */
    fun state(): JSONObject

    /** Comandos propios. null = no existe. */
    fun runCommand(name: String, args: JSONObject): CommandResult? = null
}

data class CommandResult(val ok: Boolean, val error: String? = null, val data: JSONObject? = null)

enum class LinkStatus(val label: String) {
    APAGADO("Apagado"),
    SIN_CONFIGURAR("Falta la dirección de la PC"),
    CONECTANDO("Conectando…"),
    CONECTADO("Conectado"),
    TOKEN_INVALIDO("Token inválido"),
    SIN_CONEXION("Sin conexión con la PC"),
}

/** Foto del enlace para la pantalla "Conexión con PC". */
data class LinkInfo(
    val status: LinkStatus,
    val pending: Long,
    val oldestPendingTs: Long,
    val lastConnectedAt: Long,
    val nextRetryAt: Long,
    val lastError: String,
)

object DashboardLink {
    const val PREFS = "dashboardcontrol"
    private const val K_URL = "url"
    private const val K_TOKEN = "token"
    private const val K_ENABLED = "enabled"
    private const val K_LEVEL = "min_level"

    private const val QUEUE_CAP = 20_000
    private const val WRITER_MS = 250L
    private const val PUMP_MS = 200L
    private const val STATE_EVERY_MS = 3_000L
    private const val PING_EVERY_MS = 15_000L
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 45_000
    private const val UP_MAX_ROWS = 500
    private const val UP_MAX_CHARS = 512 * 1024
    private const val UP_WINDOW = 4
    private const val ACK_TIMEOUT_MS = 30_000L
    private const val PRUNE_EVERY_MS = 60_000L
    private const val MAX_MSG_CHARS = 4_000

    private class Item(
        val ts: Long, val kind: Int, val lv: Char, val tag: String, val msg: String,
        val type: String = "", val status: String = "", val tone: String = "",
        val data: Map<String, Any?>? = null, val notify: Boolean = false,
    )

    private val queue = ArrayBlockingQueue<Item>(QUEUE_CAP)
    private val dropped = AtomicLong(0)
    @Volatile private var recording = false
    @Volatile private var minLevel = 1
    @Volatile private var bridge: DashboardBridge? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var box: DashboardOutbox? = null
    private val started = AtomicBoolean(false)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // Señales: datos nuevos (writer → link) y cambio de configuración (pantalla → link).
    private val dataSignal = Object()
    private val configSignal = Object()
    private val generation = AtomicInteger(0)
    private val settingsDirty = AtomicBoolean(false)
    @Volatile private var sock: DashboardSocket? = null
    private val cmdExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "dash-cmd").apply { isDaemon = true } }

    // Estado de sincronización (protegido por syncLock).
    private val syncLock = Any()
    private var cursor = -1L                       // último q enviado en esta conexión (-1 = aún sin welcome)
    private var acked = 0L                         // último q que la PC confirmó guardado
    private val inflight = ArrayDeque<LongArray>() // [último q del lote, hora de envío]

    @Volatile private var status = LinkStatus.APAGADO
    @Volatile private var lastError = ""
    @Volatile private var lastConnectedAt = 0L
    @Volatile private var nextRetryAt = 0L
    @Volatile private var pendingCache = 0L
    @Volatile private var oldestCache = 0L

    // ------------------------------------------------------------------ API
    /** Llamar una vez en Application.onCreate. No toca disco ni red en este hilo. */
    fun start(context: Context, bridge: DashboardBridge) {
        appContext = context.applicationContext
        this.bridge = bridge
        reloadConfig()
        if (!started.compareAndSet(false, true)) return
        box = DashboardOutbox(context.applicationContext)
        thread(name = "dash-writer", isDaemon = true, priority = Thread.MIN_PRIORITY) { writerLoop() }
        thread(name = "dash-link", isDaemon = true) { linkLoop() }
    }

    /** Log hacia la PC. Cualquier hilo. Nunca bloquea. */
    fun log(lv: Char, tag: String, msg: String) {
        if (!recording || rank(lv) < minLevel) return
        enqueue(Item(System.currentTimeMillis(), DashboardOutbox.KIND_LOG, lv, tag, msg))
    }

    /** Para no construir textos caros que igual se van a filtrar: if (DashboardLink.wants('D')) … */
    fun wants(lv: Char): Boolean = recording && rank(lv) >= minLevel

    /** true si el enlace está activado (se graba para la PC, haya conexión o no). */
    val active: Boolean get() = recording

    /**
     * Evento de negocio (oferta vista/aceptada/rechazada…). tone: ok | bad | warn | info | muted.
     * data: valores simples (String, Number, Boolean). notify=true → aviso de escritorio en la PC.
     */
    fun event(
        type: String, status: String, tone: String, title: String,
        data: Map<String, Any?> = emptyMap(), notify: Boolean = false,
    ) {
        if (!recording) return
        enqueue(Item(System.currentTimeMillis(), DashboardOutbox.KIND_EVENT, 'I', "", title, type, status, tone, data, notify))
    }

    /** Los ajustes cambiaron desde la app del teléfono: se avisa a la PC. */
    fun notifySettingsChanged() {
        settingsDirty.set(true)
        signal(dataSignal)
    }

    /** Guardar desde la pantalla "Conexión con PC" y reconectar. */
    fun configure(context: Context, url: String, token: String, enabled: Boolean) {
        appContext = context.applicationContext
        prefs().edit()
            .putString(K_URL, normalizeUrl(url))
            .putString(K_TOKEN, token.trim().uppercase())
            .putBoolean(K_ENABLED, enabled)
            .apply()
        reloadConfig()
        reconnectNow()
    }

    /** Corta la conexión actual (si hay) y reintenta ya. */
    fun reconnectNow() {
        generation.incrementAndGet()
        nextRetryAt = 0L
        sock?.let { s -> thread(name = "dash-close", isDaemon = true) { s.closeWith(1000) } }
        signal(configSignal)
        signal(dataSignal)
    }

    /** Borra lo que el teléfono tiene sin enviar (botón en la pantalla, con confirmación). */
    fun clearPending(done: () -> Unit) {
        thread(name = "dash-clear", isDaemon = true) {
            try {
                box?.clear()
                refreshPendingCache()
            } catch (_: Throwable) { }
            mainHandler.post(done)
        }
    }

    fun info(): LinkInfo = LinkInfo(status, pendingCache, oldestCache, lastConnectedAt, nextRetryAt, lastError)

    fun currentUrl(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_URL, "") ?: ""
    fun currentToken(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_TOKEN, "") ?: ""
    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_ENABLED, false)

    /** Ejecuta en el hilo principal y espera el resultado (para comandos que tocan el motor). */
    fun <T> onMain(timeoutMs: Long = 5_000, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        mainHandler.post {
            result = runCatching(block)
            latch.countDown()
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw TimeoutException("el hilo principal no respondió")
        return result!!.getOrThrow()
    }

    // ------------------------------------------------------------ internos
    private fun rank(c: Char): Int = when (c) {
        'D', 'V' -> 0
        'W' -> 2
        'E', 'A' -> 3
        else -> 1
    }

    private fun enqueue(item: Item) {
        if (!queue.offer(item)) {
            queue.poll()
            dropped.incrementAndGet()
            queue.offer(item)
        }
    }

    private fun prefs() = appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun reloadConfig() {
        val p = prefs()
        recording = p.getBoolean(K_ENABLED, false) && !p.getString(K_URL, "").isNullOrBlank()
        minLevel = p.getInt(K_LEVEL, 1)
    }

    private fun normalizeUrl(raw: String): String {
        var u = raw.trim()
        if (u.isEmpty()) return u
        if (!u.startsWith("ws://")) u = "ws://" + u.substringAfter("://")
        val hostPart = u.removePrefix("ws://").substringBefore('/')
        if (!hostPart.contains(':')) u = u.replaceFirst(hostPart, "$hostPart:8765")
        if (!u.endsWith("/agent")) u = u.trimEnd('/').substringBefore("/agent") + "/agent"
        return u
    }

    private fun signal(lock: Object) {
        synchronized(lock) { lock.notifyAll() }
    }

    private fun waitOn(lock: Object, ms: Long) {
        if (ms <= 0) return
        synchronized(lock) { lock.wait(ms) }
    }

    // ---------- hilo dash-writer: memoria → disco ----------
    private fun writerLoop() {
        val batch = ArrayList<Item>(2048)
        val rows = ArrayList<Triple<Int, Long, String>>(2048)
        var lastPrune = 0L
        while (true) {
            try {
                val first = queue.poll(WRITER_MS, TimeUnit.MILLISECONDS)
                if (first != null) {
                    batch.add(first)
                    queue.drainTo(batch, 4_000)
                }
                val now = System.currentTimeMillis()
                val lost = dropped.getAndSet(0)
                if (lost > 0) rows.add(Triple(DashboardOutbox.KIND_LOG, now, logJson(now, 'W', "dashboard", "se descartaron $lost líneas (ráfaga demasiado grande)")))
                for (it in batch) rows.add(Triple(it.kind, it.ts, toJson(it)))
                val b = box ?: continue
                if (rows.isNotEmpty()) {
                    b.insert(rows)
                    signal(dataSignal)
                }
                batch.clear()
                rows.clear()
                if (now - lastPrune > PRUNE_EVERY_MS) {
                    lastPrune = now
                    val n = b.prune(now)
                    if (n > 0) b.insert(listOf(Triple(DashboardOutbox.KIND_LOG, now, logJson(now, 'W', "dashboard", "bandeja llena: se descartaron $n registros viejos sin enviar"))))
                    if (sock == null) refreshPendingCache()
                }
            } catch (t: Throwable) {
                batch.clear()
                rows.clear()
                try { Thread.sleep(1_000) } catch (_: InterruptedException) { }
            }
        }
    }

    private fun logJson(ts: Long, lv: Char, tag: String, msg: String): String =
        JSONObject().put("ts", ts).put("lv", lv.toString()).put("tag", tag.take(40)).put("m", msg.take(MAX_MSG_CHARS)).toString()

    private fun toJson(it: Item): String {
        if (it.kind == DashboardOutbox.KIND_LOG) return logJson(it.ts, it.lv, it.tag, it.msg)
        val data = JSONObject()
        it.data?.forEach { (k, v) ->
            data.put(k, when (v) {
                null -> JSONObject.NULL
                is Number, is Boolean, is String -> v
                else -> v.toString()
            })
        }
        return JSONObject().put("ts", it.ts).put("type", it.type).put("status", it.status).put("tone", it.tone)
            .put("title", it.msg.take(200)).put("data", data).put("notify", it.notify).toString()
    }

    // ---------- hilo dash-link: conexión + envío ----------
    private fun linkLoop() {
        var backoff = 1_000L
        while (true) {
            try {
                val p = prefs()
                val url = p.getString(K_URL, "") ?: ""
                val token = p.getString(K_TOKEN, "") ?: ""
                if (!p.getBoolean(K_ENABLED, false)) {
                    status = LinkStatus.APAGADO
                    refreshPendingCache()
                    waitOn(configSignal, 10_000)
                    continue
                }
                if (url.isBlank()) {
                    status = LinkStatus.SIN_CONFIGURAR
                    waitOn(configSignal, 10_000)
                    continue
                }
                status = LinkStatus.CONECTANDO
                val gen = generation.get()
                var closeCode = -1
                try {
                    val s = DashboardSocket.connect("$url?token=" + URLEncoder.encode(token, "UTF-8"), CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
                    synchronized(syncLock) {
                        cursor = -1
                        inflight.clear()
                    }
                    sock = s
                    val alive = AtomicBoolean(true)
                    s.sendText(hello().toString())
                    status = LinkStatus.CONECTADO
                    lastConnectedAt = System.currentTimeMillis()
                    lastError = ""
                    backoff = 1_000L
                    thread(name = "dash-read", isDaemon = true) { readLoop(s, alive) }
                    try {
                        pumpLoop(s, gen, alive)
                    } finally {
                        closeCode = s.closeCode
                        s.closeWith(1000)
                    }
                } catch (e: IOException) {
                    lastError = e.message ?: "error de red"
                }
                sock = null
                synchronized(syncLock) {
                    cursor = -1
                    inflight.clear()
                }
                refreshPendingCache()
                if (!prefs().getBoolean(K_ENABLED, false)) continue
                if (generation.get() != gen) continue // reconexión pedida: sin espera
                status = if (closeCode == 4001) LinkStatus.TOKEN_INVALIDO else LinkStatus.SIN_CONEXION
                if (closeCode == 4001) lastError = "la PC rechazó el token"
                val wait = if (closeCode == 4001) 30_000L else (backoff * Random.nextDouble(0.8, 1.2)).toLong()
                nextRetryAt = System.currentTimeMillis() + wait
                waitOn(configSignal, wait)
                nextRetryAt = 0L
                backoff = (backoff * 2).coerceAtMost(30_000L)
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                try { Thread.sleep(5_000) } catch (_: InterruptedException) { }
            }
        }
    }

    private fun pumpLoop(s: DashboardSocket, gen: Int, alive: AtomicBoolean) {
        val b = box ?: return
        var lastState = 0L
        var lastPing = System.currentTimeMillis()
        while (alive.get() && generation.get() == gen) {
            val now = System.currentTimeMillis()
            synchronized(syncLock) {
                if (cursor >= 0 && inflight.isNotEmpty() && now - inflight.first()[1] > ACK_TIMEOUT_MS) {
                    cursor = acked // sin confirmación: se reenvía desde lo guardado; la PC ignora repetidos
                    inflight.clear()
                }
            }
            // Lotes numerados de la bandeja (lo atrasado y lo nuevo, en orden).
            while (true) {
                val from = synchronized(syncLock) { if (cursor < 0 || inflight.size >= UP_WINDOW) -1L else cursor }
                if (from < 0) break
                val rows = b.after(from, UP_MAX_ROWS, UP_MAX_CHARS)
                if (rows.isEmpty()) break
                val last = rows.last().id
                val msg = StringBuilder(rows.sumOf { it.body.length } + rows.size * 24 + 64)
                msg.append("{\"t\":\"up\",\"pending\":").append(b.countAfter(last)).append(",\"items\":[")
                rows.forEachIndexed { i, r ->
                    if (i > 0) msg.append(',')
                    // body es un objeto JSON: se le agrega el número y el tipo sin re-parsear.
                    msg.append("{\"q\":").append(r.id)
                        .append(if (r.kind == DashboardOutbox.KIND_EVENT) ",\"k\":\"e\"," else ",\"k\":\"l\",")
                        .append(r.body, 1, r.body.length)
                }
                msg.append("]}")
                synchronized(syncLock) {
                    cursor = last
                    inflight.addLast(longArrayOf(last, now))
                }
                s.sendText(msg.toString())
            }
            if (settingsDirty.getAndSet(false)) {
                bridge?.settings?.let { st ->
                    val snap = st.snapshot()
                    s.sendText(JSONObject().put("t", "settings").put("settings", snap.values).put("settingsVersion", snap.version).toString())
                }
            }
            if (now - lastState >= STATE_EVERY_MS) {
                lastState = now
                refreshPendingCache()
                s.sendText(JSONObject().put("t", "state").put("state", fullState()).toString())
            }
            if (now - lastPing >= PING_EVERY_MS) {
                lastPing = now
                s.sendPing()
            }
            waitOn(dataSignal, PUMP_MS)
        }
    }

    // ---------- hilo dash-read: confirmaciones y comandos ----------
    private fun readLoop(s: DashboardSocket, alive: AtomicBoolean) {
        try {
            while (true) {
                val text = s.readText() ?: break
                val m = try { JSONObject(text) } catch (_: Exception) { continue }
                when (m.optString("t")) {
                    "welcome" -> {
                        // La PC dice hasta dónde tiene guardado esta bandeja: se borra eso y se sigue desde ahí.
                        val ack = m.optLong("ack", 0L)
                        synchronized(syncLock) {
                            acked = ack
                            cursor = ack
                            inflight.clear()
                        }
                        box?.deleteUpTo(ack)
                        signal(dataSignal)
                    }
                    "up_ack" -> {
                        val q = m.optLong("q", 0L)
                        var delete = false
                        synchronized(syncLock) {
                            if (q > acked) {
                                acked = q
                                delete = true
                            }
                            while (inflight.isNotEmpty() && inflight.first()[0] <= q) inflight.removeFirst()
                        }
                        if (delete) box?.deleteUpTo(q)
                        signal(dataSignal)
                    }
                    "cmd" -> cmdExecutor.execute { runCommand(m) }
                }
            }
        } catch (_: Throwable) {
        } finally {
            alive.set(false)
            signal(dataSignal)
        }
    }

    private fun runCommand(m: JSONObject) {
        val id = m.optString("id")
        val res = try {
            execute(m.optString("name"), m.optJSONObject("args") ?: JSONObject())
        } catch (t: Throwable) {
            CommandResult(false, t.message ?: t.javaClass.simpleName)
        }
        val ack = JSONObject().put("t", "ack").put("id", id).put("ok", res.ok)
        res.error?.let { ack.put("error", it) }
        res.data?.let { ack.put("data", it) }
        try { sock?.sendText(ack.toString()) } catch (_: IOException) { }
    }

    private fun execute(name: String, args: JSONObject): CommandResult {
        val b = bridge ?: return CommandResult(false, "app sin bridge")
        return when (name) {
            "pause" -> b.pause().withState()
            "resume" -> b.resume().withState()
            "get_settings" -> {
                val st = b.settings ?: return CommandResult(false, "esta app no expone ajustes")
                CommandResult(true, null, st.snapshot().toJson())
            }
            "set_settings" -> {
                val st = b.settings ?: return CommandResult(false, "esta app no expone ajustes")
                when (val r = st.applyFromPc(args.optJSONObject("values") ?: JSONObject(), args.optLong("baseVersion", -1))) {
                    is ApplyResult.Ok -> CommandResult(true, null, r.snapshot.toJson())
                    is ApplyResult.Rejected -> CommandResult(false, r.reason, r.snapshot.toJson())
                }
            }
            "set_log_level" -> {
                val c = args.optString("level", "I").firstOrNull()?.uppercaseChar()
                if (c == null || c !in "DIWE") CommandResult(false, "nivel inválido") else {
                    minLevel = rank(c)
                    prefs().edit().putInt(K_LEVEL, minLevel).apply()
                    CommandResult(true)
                }
            }
            "ping" -> CommandResult(true)
            else -> b.runCommand(name, args) ?: CommandResult(false, "comando desconocido: $name")
        }
    }

    private fun CommandResult.withState(): CommandResult =
        if (data != null) this else copy(data = JSONObject().put("state", fullState()))

    private fun hello(): JSONObject {
        val b = bridge!!
        val commands = JSONArray()
            .put(JSONObject().put("name", "pause").put("label", "Pausar"))
            .put(JSONObject().put("name", "resume").put("label", "Reanudar"))
        val extra = b.extraCommands()
        for (i in 0 until extra.length()) commands.put(extra.get(i))
        val schema = JSONObject().put("commands", commands).put("settings", b.settings?.schema() ?: JSONArray())
        val snap = b.settings?.snapshot()
        refreshPendingCache()
        return JSONObject()
            .put("t", "hello")
            .put("proto", 2)
            .put("system", b.systemName)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("deviceId", deviceId())
            .put("appVersion", b.appVersion)
            .put("time", System.currentTimeMillis())
            .put("outboxId", box!!.outboxId)
            .put("sync", JSONObject().put("pending", pendingCache).put("oldestTs", oldestCache))
            .put("schema", schema)
            .put("settings", snap?.values ?: JSONObject())
            .put("settingsVersion", snap?.version ?: 0L)
            .put("state", fullState())
    }

    private fun refreshPendingCache() {
        val b = box ?: return
        try {
            val from = synchronized(syncLock) { acked }
            pendingCache = b.countAfter(from)
            oldestCache = if (pendingCache > 0) b.oldestTsAfter(from) else 0L
        } catch (_: Throwable) { }
    }

    private fun deviceId(): String =
        Settings.Secure.getString(appContext!!.contentResolver, Settings.Secure.ANDROID_ID) ?: Build.MODEL

    /** Estado de la app + batería/temperatura (intent "sticky": sin receiver) + pendientes de sincronizar. */
    private fun fullState(): JSONObject {
        val st = try { bridge?.state() ?: JSONObject() } catch (t: Throwable) {
            JSONObject().put("status", "error").put("statusText", t.message ?: "error leyendo estado")
        }
        try {
            val i: Intent? = appContext?.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (i != null) {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && !st.has("battery")) st.put("battery", level * 100 / scale)
                if (!st.has("charging")) st.put("charging", i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
                val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                if (t != Int.MIN_VALUE && !st.has("temp")) st.put("temp", t / 10.0)
            }
        } catch (_: Throwable) { }
        st.put("sync", JSONObject().put("pending", pendingCache).put("oldestTs", oldestCache))
        return st
    }
}
