package com.oceanlab.pichix.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

object MonitorPackages {

    private const val TAG = "MonitorPackages"
    private const val PLACEHOLDER_UNCONFIGURED = "__pichix_unconfigured__"

    const val ACTION_RELOAD = "com.oceanlab.pichix.RELOAD_MONITOR_PACKAGES"

    @Volatile private var cachedCsv: String? = null
    @Volatile private var cachedList: Array<String> = emptyArray()

    /** Se llama en cada evento de accesibilidad: solo reparsea si cambió el CSV de los ajustes. */
    fun resolve(context: Context): Array<String> {
        val csv = AppSettings(context).monitorPackagesCsv
        if (csv == cachedCsv) return cachedList
        val list = csv.split(',', '\n', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        val result = if (list.isEmpty()) emptyArray() else list.distinct().toTypedArray()
        cachedList = result
        cachedCsv = csv
        return result
    }

    fun isConfigured(context: Context): Boolean = resolve(context).isNotEmpty()

    fun primaryTarget(context: Context): String? = resolve(context).firstOrNull()

    fun packageNamesForService(context: Context): Array<String> {
        val pkgs = resolve(context)
        return if (pkgs.isEmpty()) arrayOf(PLACEHOLDER_UNCONFIGURED) else pkgs
    }

    fun notifyReload(context: Context) {
        LocalBroadcastManager.getInstance(context)
            .sendBroadcast(Intent(ACTION_RELOAD))
    }

    fun statusLine(context: Context): String {
        val pkgs = resolve(context)
        return when {
            pkgs.isEmpty() -> "Sin paquetes configurados"
            pkgs.size == 1 -> "Vigilando: ${pkgs[0]}"
            else -> "Vigilando ${pkgs.size} apps (principal: ${pkgs[0]})"
        }
    }
}
