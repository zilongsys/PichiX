package com.oceanlab.pichix.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

object MonitorPackages {

    private const val TAG = "MonitorPackages"
    private const val PLACEHOLDER_UNCONFIGURED = "__pichix_unconfigured__"

    const val ACTION_RELOAD = "com.oceanlab.pichix.RELOAD_MONITOR_PACKAGES"

    fun resolve(context: Context): Array<String> {
        val csv = AppSettings(context).monitorPackagesCsv
        val list = csv.split(',', '\n', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        return if (list.isEmpty()) emptyArray() else list.distinct().toTypedArray()
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
