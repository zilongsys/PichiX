package com.oceanlab.pichix.dashboardcontrol

// ============================================================================
// DashboardSettings — ajustes de la app que la PC puede ver y cambiar.
//
// Cada app declara una lista de SettingSpec que leen/escriben con SUS propias
// propiedades de AppSettings (mismo formato y mismas validaciones que la UI).
//
// set_settings desde la PC:
//  - Si la versión que tenía la PC no es la actual → se rechaza ("conflicto").
//  - Se validan TODOS los valores antes de tocar nada; si uno falla, no se aplica ninguno.
//  - Se escriben todos juntos en el hilo principal, se llama afterApply() UNA vez
//    (una sola recarga del motor) y la versión sube UNA vez.
//
// Si el usuario cambia algo desde la propia app, un listener de SharedPreferences
// lo detecta, sube la versión y avisa a la PC (así la PC nunca pisa un cambio local).
// ============================================================================

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

data class SettingsSnapshot(val values: JSONObject, val version: Long) {
    fun toJson(): JSONObject = JSONObject().put("settings", values).put("settingsVersion", version)
}

sealed class ApplyResult {
    data class Ok(val snapshot: SettingsSnapshot) : ApplyResult()
    data class Rejected(val reason: String, val snapshot: SettingsSnapshot) : ApplyResult()
}

/**
 * Un ajuste. [key] es la clave REAL de SharedPreferences (así se detectan los cambios
 * hechos desde la app). Crear con los constructores de [Companion]: bool, int, long, float, choice, text.
 */
class SettingSpec private constructor(
    val key: String,
    val label: String,
    val type: String,
    val group: String,
    private val min: Double?,
    private val max: Double?,
    private val step: Double?,
    private val unit: String?,
    private val options: List<String>?,
    private val help: String?,
    private val read: () -> Any,
    private val write: (Any) -> Unit,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key); put("label", label); put("type", type); put("group", group)
        min?.let { put("min", it) }
        max?.let { put("max", it) }
        step?.let { put("step", it) }
        unit?.let { put("unit", it) }
        help?.let { put("help", it) }
        options?.let { put("options", JSONArray(it)) }
    }

    fun get(): Any = read()

    internal fun set(v: Any) = write(v)

    /** Convierte y valida lo que manda la PC. null = inválido. */
    internal fun parse(raw: Any?): Any? = when (type) {
        "bool" -> when (raw) {
            is Boolean -> raw
            is String -> raw.toBooleanStrictOrNull()
            else -> null
        }
        "int" -> number(raw)?.takeIf { it % 1.0 == 0.0 && inRange(it) }?.toLong()
        "float" -> number(raw)?.takeIf { inRange(it) }
        "enum" -> (raw as? String)?.takeIf { options?.contains(it) == true }
        else -> (raw as? String)?.takeIf { it.length <= 2_000 }
    }

    private fun number(raw: Any?): Double? = when (raw) {
        is Number -> raw.toDouble()
        is String -> raw.trim().replace(',', '.').toDoubleOrNull()
        else -> null
    }?.takeIf { !it.isNaN() && !it.isInfinite() }

    private fun inRange(v: Double) = (min == null || v >= min) && (max == null || v <= max)

    companion object {
        fun bool(key: String, label: String, group: String, help: String? = null,
                 get: () -> Boolean, set: (Boolean) -> Unit) =
            SettingSpec(key, label, "bool", group, null, null, null, null, null, help, get) { set(it as Boolean) }

        fun int(key: String, label: String, group: String, min: Int, max: Int, unit: String? = null, help: String? = null,
                get: () -> Int, set: (Int) -> Unit) =
            SettingSpec(key, label, "int", group, min.toDouble(), max.toDouble(), 1.0, unit, null, help, get) { set((it as Long).toInt()) }

        fun long(key: String, label: String, group: String, min: Long, max: Long, unit: String? = null, help: String? = null,
                 get: () -> Long, set: (Long) -> Unit) =
            SettingSpec(key, label, "int", group, min.toDouble(), max.toDouble(), 1.0, unit, null, help, get) { set(it as Long) }

        fun float(key: String, label: String, group: String, min: Double, max: Double, step: Double, unit: String? = null,
                  help: String? = null, get: () -> Double, set: (Double) -> Unit) =
            SettingSpec(key, label, "float", group, min, max, step, unit, null, help,
                { Math.round(get() * 1e6) / 1e6 }) { set(it as Double) }

        fun choice(key: String, label: String, group: String, options: List<String>, help: String? = null,
                   get: () -> String, set: (String) -> Unit) =
            SettingSpec(key, label, "enum", group, null, null, null, null, options, help, get) { set(it as String) }

        fun text(key: String, label: String, group: String, help: String? = null,
                 get: () -> String, set: (String) -> Unit) =
            SettingSpec(key, label, "string", group, null, null, null, null, null, help, get) { set(it as String) }
    }
}

/**
 * @param prefsName archivo de SharedPreferences de la app (para detectar cambios hechos en el teléfono).
 * @param afterApply se llama UNA vez en el hilo principal tras escribir todo (recargar el motor).
 */
class DashboardSettings(
    context: Context,
    prefsName: String,
    private val specs: List<SettingSpec>,
    private val afterApply: () -> Unit,
) {
    private val byKey = specs.associateBy { it.key }
    private val meta = context.applicationContext.getSharedPreferences(DashboardLink.PREFS, Context.MODE_PRIVATE)
    private val lock = Any()
    private val changedOnPhone = AtomicBoolean(false)
    @Volatile private var applying = false

    // Referencia fuerte: SharedPreferences guarda los listeners de forma débil.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!applying && key != null && byKey.containsKey(key)) {
            changedOnPhone.set(true)
            DashboardLink.notifySettingsChanged()
        }
    }

    init {
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(listener)
    }

    fun schema(): JSONArray = JSONArray().apply { specs.forEach { put(it.toJson()) } }

    fun snapshot(): SettingsSnapshot {
        val values = JSONObject()
        for (s in specs) {
            try { values.put(s.key, s.get()) } catch (_: Throwable) { }
        }
        return SettingsSnapshot(values, currentVersion())
    }

    fun applyFromPc(values: JSONObject, baseVersion: Long): ApplyResult {
        synchronized(lock) { return applyLocked(values, baseVersion) }
    }

    private fun applyLocked(values: JSONObject, baseVersion: Long): ApplyResult {
        val current = currentVersion()
        if (baseVersion != current) return ApplyResult.Rejected("conflicto: el teléfono está en v$current", snapshot())
        val parsed = ArrayList<Pair<SettingSpec, Any>>()
        for (k in values.keys()) {
            val spec = byKey[k] ?: return ApplyResult.Rejected("ajuste desconocido: $k", snapshot())
            val v = spec.parse(values.opt(k)) ?: return ApplyResult.Rejected("valor inválido para «${spec.label}»", snapshot())
            parsed.add(spec to v)
        }
        if (parsed.isEmpty()) return ApplyResult.Ok(snapshot())
        DashboardLink.onMain {
            applying = true
            try {
                parsed.forEach { (spec, v) -> spec.set(v) }
                afterApply()
            } finally {
                applying = false
            }
        }
        bump()
        return ApplyResult.Ok(snapshot())
    }

    private fun currentVersion(): Long {
        if (changedOnPhone.getAndSet(false)) bump()
        return meta.getLong(K_VERSION, 1L)
    }

    private fun bump() {
        meta.edit().putLong(K_VERSION, meta.getLong(K_VERSION, 1L) + 1).apply()
    }

    private companion object {
        const val K_VERSION = "settings_version"
    }
}
