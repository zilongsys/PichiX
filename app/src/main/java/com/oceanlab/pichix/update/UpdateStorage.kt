package com.oceanlab.pichix.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Preferencias propias de la actualización. El token vive SOLO aquí (`app_update`), separado de
 * `pichix_settings`; el fichero está excluido de los respaldos (backup_rules / data_extraction_rules)
 * y nunca se escribe en logs ni en exports JSON.
 */
class UpdatePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var token: String
        get() = prefs.getString(K_TOKEN, "") ?: ""
        set(v) { prefs.edit().putString(K_TOKEN, v.trim()).apply() }

    /** Cuántas versiones conservar al pulsar «Borrar antiguas». 0 = todas (no borrar). */
    var keepLatest: Int
        get() = prefs.getInt(K_KEEP, 0)
        set(v) { prefs.edit().putInt(K_KEEP, v.coerceIn(0, 999)).apply() }

    companion object {
        const val NAME = "app_update"
        private const val K_TOKEN = "github_token"
        private const val K_KEEP = "keep_latest"
    }
}

/** Carpeta `apk_history/` en `getExternalFilesDir` (visible desde el gestor de archivos); si no hay, almacenamiento interno. */
object ApkStorage {
    fun historyDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "apk_history").also { it.mkdirs() }
    }

    fun history(context: Context): ApkHistory = ApkHistory(historyDir(context))
}

object ApkInstaller {
    const val APK_MIME = "application/vnd.android.package-archive"

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(context: Context, file: File): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, file), APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    fun shareIntent(context: Context, file: File): Intent {
        val uri = uriFor(context, file)
        return Intent(Intent.ACTION_SEND)
            .setType(APK_MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** (packageName, versionCode) del APK, o null si no se puede leer. */
    fun inspect(context: Context, file: File): Pair<String, Int>? {
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: return null
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt() else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
        return info.packageName to code
    }
}
