package com.oceanlab.pichix.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.BotEventLog

/** Marca una llamada al tomar bloque o al coincidir una notificación configurada. */
object CallOnBlockHelper {

    private const val TAG = "CallOnBlock"
    private const val DEDUP_MS = 45_000L

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastCallAtMs = 0L

    fun normalizePhone(raw: String): String =
        raw.filter { it.isDigit() || it == '+' }.trim()

    fun hasCallPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    fun maybeCall(context: Context, settings: AppSettings, reason: String): Boolean {
        if (!settings.callOnBlockEnabled) return false
        val phone = normalizePhone(settings.callOnBlockPhoneNumber)
        if (phone.length < 7) return false
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastCallAtMs < DEDUP_MS) return false
            lastCallAtMs = now
        }
        val appContext = context.applicationContext
        val delayMs = settings.callOnBlockDelayMs
        if (delayMs <= 0L) {
            return placeCall(appContext, phone, reason)
        }
        handler.postDelayed({ placeCall(appContext, phone, reason) }, delayMs)
        return true
    }

    private fun placeCall(context: Context, phone: String, reason: String): Boolean {
        val uri = Uri.parse("tel:$phone")
        return try {
            if (hasCallPermission(context)) {
                context.startActivity(
                    Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                BotEventLog.log(context, BotEventLog.CAT_BOT, "Llamada iniciada ($reason)")
                true
            } else {
                context.startActivity(
                    Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                BotEventLog.log(
                    context,
                    BotEventLog.CAT_BOT,
                    "Marcador abierto — concede permiso de llamada ($reason)",
                )
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo llamar a $phone", e)
            BotEventLog.log(context, BotEventLog.CAT_BOT, "Error al llamar: ${e.message ?: "desconocido"}")
            false
        }
    }
}
