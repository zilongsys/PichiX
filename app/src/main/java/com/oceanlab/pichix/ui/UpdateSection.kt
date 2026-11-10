package com.oceanlab.pichix.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.oceanlab.pichix.BuildConfig
import com.oceanlab.pichix.R
import com.oceanlab.pichix.update.ApkHistory
import com.oceanlab.pichix.update.ApkInstaller
import com.oceanlab.pichix.update.ApkStorage
import com.oceanlab.pichix.update.AppUpdater
import com.oceanlab.pichix.update.UpdatePrefs
import java.io.File
import java.io.IOException
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sección «Actualización» de Config: buscar release en GitHub, descargar, instalar y gestionar el
 * historial local de APKs. La lógica de red/índice vive en [AppUpdater] / [ApkHistory].
 */
class UpdateSection(private val fragment: Fragment) {

    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var pendingExport: ApkHistory.Entry? = null

    // Debe registrarse antes de que el fragment pase a STARTED: se crea en el constructor del fragment.
    private val exportLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.CreateDocument(ApkInstaller.APK_MIME),
    ) { uri -> uri?.let { pendingExport?.let { e -> exportTo(e, it) } }; pendingExport = null }

    private val ctx get() = fragment.requireContext().applicationContext
    private val prefs get() = UpdatePrefs(ctx)
    private val history get() = ApkStorage.history(ctx)

    private fun alive() = fragment.isAdded && fragment.view != null
    private fun ui(block: () -> Unit) = main.post { if (alive()) block() }
    private fun str(id: Int, vararg a: Any) = fragment.getString(id, *a)

    fun bind(view: View) {
        root = view
        val etToken = view.findViewById<TextInputEditText>(R.id.etUpdateToken)
        val etKeep = view.findViewById<TextInputEditText>(R.id.etUpdateKeepLatest)
        view.findViewById<TextView>(R.id.tvUpdateInstalled).text =
            str(R.string.update_installed_version, BuildConfig.VERSION_NAME)
        etToken.setText(prefs.token)
        prefs.keepLatest.takeIf { it > 0 }?.let { etKeep.setText(it.toString()) }
        etToken.addTextChangedListener(simpleWatcher { prefs.token = it })
        etKeep.addTextChangedListener(simpleWatcher { prefs.keepLatest = it.toIntOrNull() ?: 0 })
        view.findViewById<MaterialButton>(R.id.btnUpdateCheck).setOnClickListener { check() }
        view.findViewById<MaterialButton>(R.id.btnUpdateHistory).setOnClickListener { showHistory() }
    }

    private fun simpleWatcher(onText: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onText(s?.toString().orEmpty())
    }

    private fun setStatus(text: String?) {
        root?.findViewById<TextView>(R.id.tvUpdateStatus)?.let {
            it.text = text.orEmpty()
            it.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }

    // ---------- A. Comprobar ----------

    private fun check() {
        val token = prefs.token
        if (token.isBlank()) {
            setStatus(str(R.string.update_err_no_token))
            return
        }
        val btn = root?.findViewById<MaterialButton>(R.id.btnUpdateCheck)
        btn?.isEnabled = false
        setStatus(str(R.string.update_checking))
        Thread {
            val result = AppUpdater.checkForUpdate(token, BuildConfig.VERSION_NAME)
            ui {
                btn?.isEnabled = true
                when (result) {
                    is AppUpdater.CheckResult.Failure -> setStatus(failureText(result.kind, result.httpCode))
                    is AppUpdater.CheckResult.UpToDate -> setStatus(str(R.string.update_up_to_date, result.version))
                    is AppUpdater.CheckResult.Available -> {
                        setStatus(null)
                        showAvailable(result.release)
                    }
                }
            }
        }.start()
    }

    private fun failureText(kind: AppUpdater.Kind, code: Int): String = when (kind) {
        AppUpdater.Kind.NO_TOKEN -> str(R.string.update_err_no_token)
        AppUpdater.Kind.UNAUTHORIZED -> str(R.string.update_err_unauthorized, code)
        AppUpdater.Kind.NOT_FOUND -> str(R.string.update_err_not_found)
        AppUpdater.Kind.NO_NETWORK -> str(R.string.update_err_no_network)
        AppUpdater.Kind.NO_APK -> str(R.string.update_err_no_apk)
        AppUpdater.Kind.HTTP_ERROR -> str(R.string.update_err_http, code)
        AppUpdater.Kind.SHA_MISMATCH -> str(R.string.update_err_sha)
        AppUpdater.Kind.SIZE_MISMATCH -> str(R.string.update_err_size)
        AppUpdater.Kind.BAD_RESPONSE -> str(R.string.update_err_bad_response)
    }

    private fun showAvailable(release: AppUpdater.ReleaseInfo) {
        val existing = history.find(release.version)
        val notes = release.notes.trim().ifEmpty { str(R.string.update_no_notes) }
        val size = if (release.apkSize > 0) Formatter.formatShortFileSize(ctx, release.apkSize) else "?"
        var msg = str(R.string.update_available_msg, size, notes)
        if (existing != null) msg += "\n\n" + str(R.string.update_already_downloaded)
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(str(R.string.update_available_title, release.version))
            .setMessage(msg)
            .setPositiveButton(
                if (existing != null) R.string.update_btn_install else R.string.update_btn_download_install,
            ) { _, _ ->
                if (existing != null) install(history.fileOf(existing)) else download(release)
            }
            .setNegativeButton(R.string.update_cancel, null)
            .show()
    }

    // ---------- B. Descarga ----------

    private fun download(release: AppUpdater.ReleaseInfo) {
        val token = prefs.token
        val dir = ApkStorage.historyDir(ctx)
        val dest = File(dir, AppUpdater.apkFileName(release.version))
        val cancelled = AtomicBoolean(false)

        val bar = LinearProgressIndicator(fragment.requireContext()).apply {
            isIndeterminate = true
            max = 100
        }
        val pct = TextView(fragment.requireContext()).apply {
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 13f
        }
        val box = LinearLayout(fragment.requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(20)
            setPadding(p, dp(8), p, 0)
            addView(bar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(pct)
        }
        val dialog = MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(str(R.string.update_downloading, release.version))
            .setView(box)
            .setCancelable(false)
            .setNegativeButton(R.string.update_cancel) { _, _ -> cancelled.set(true) }
            .show()

        Thread {
            try {
                val sha = AppUpdater.downloadApk(
                    release, token, dest,
                    onProgress = { p ->
                        main.post {
                            if (p >= 0) {
                                bar.isIndeterminate = false
                                bar.progress = p
                                pct.text = "$p %"
                            }
                        }
                    },
                    isCancelled = { cancelled.get() },
                )
                val info = ApkInstaller.inspect(ctx, dest)
                if (info == null || info.first != ctx.packageName) {
                    dest.delete()
                    ui { dialog.dismiss(); setStatus(str(R.string.update_err_invalid_apk)) }
                    return@Thread
                }
                history.add(
                    ApkHistory.Entry(
                        version = release.version,
                        versionCode = info.second,
                        downloadedAt = System.currentTimeMillis(),
                        size = dest.length(),
                        sha256 = sha,
                        notes = release.notes,
                        file = dest.name,
                    ),
                )
                ui { dialog.dismiss(); install(dest) }
            } catch (e: AppUpdater.UpdateException) {
                ui { dialog.dismiss(); setStatus(failureText(e.kind, e.httpCode)) }
            } catch (e: IOException) {
                if (cancelled.get()) ui { dialog.dismiss() }
                else ui { dialog.dismiss(); setStatus(str(R.string.update_err_no_network)) }
            }
        }.start()
    }

    // ---------- D. Instalación ----------

    private fun install(file: File) {
        val c = fragment.requireContext()
        if (!ApkInstaller.canInstall(c)) {
            Toast.makeText(c, R.string.update_allow_unknown_sources, Toast.LENGTH_LONG).show()
            try { fragment.startActivity(ApkInstaller.unknownSourcesIntent(c)) } catch (e: ActivityNotFoundException) { }
            return
        }
        try {
            fragment.startActivity(ApkInstaller.installIntent(c, file))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(c, R.string.update_install_failed, Toast.LENGTH_LONG).show()
        }
    }

    // ---------- C. Historial ----------

    private fun showHistory() {
        val c = fragment.requireContext()
        val entries = history.entries()
        val list = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        list.addView(TextView(c).apply {
            text = str(R.string.update_history_warning)
            textSize = 12f
            setTextColor(ContextCompat.getColor(c, R.color.coral_600))
            setPadding(0, 0, 0, dp(10))
        })
        if (entries.isEmpty()) {
            list.addView(TextView(c).apply {
                text = str(R.string.update_history_empty)
                setTextColor(ContextCompat.getColor(c, R.color.text_secondary))
            })
        }
        var dialogRef: androidx.appcompat.app.AlertDialog? = null
        entries.forEach { e ->
            val installed = AppUpdater.compareVersions(e.version, BuildConfig.VERSION_NAME) == 0
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                isClickable = true
                val tv = android.util.TypedValue()
                if (c.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
                    setBackgroundResource(tv.resourceId)
                }
            }
            row.addView(TextView(c).apply {
                text = "v${e.version}" + if (installed) "  ·  ${str(R.string.update_history_installed_tag)}" else ""
                textSize = 15f
                setTextColor(ContextCompat.getColor(c, R.color.text_primary))
            })
            row.addView(TextView(c).apply {
                val date = DateFormat.getMediumDateFormat(c).format(Date(e.downloadedAt)) + " " +
                    DateFormat.getTimeFormat(c).format(Date(e.downloadedAt))
                text = str(R.string.update_history_row_sub, date, Formatter.formatShortFileSize(c, e.size))
                textSize = 12f
                setTextColor(ContextCompat.getColor(c, R.color.text_secondary))
            })
            row.setOnClickListener {
                dialogRef?.dismiss()
                showActions(e)
            }
            list.addView(row)
        }
        dialogRef = MaterialAlertDialogBuilder(c)
            .setTitle(R.string.update_history_title)
            .setView(ScrollView(c).apply { addView(list) })
            .setPositiveButton(R.string.update_close, null)
            .setNeutralButton(R.string.update_btn_prune) { _, _ -> confirmPrune() }
            .show()
    }

    private fun showActions(e: ApkHistory.Entry) {
        val labels = arrayOf(
            str(R.string.update_action_install),
            str(R.string.update_action_share),
            str(R.string.update_action_export),
            str(R.string.update_action_delete),
        )
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle("v${e.version}")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> installFromHistory(e)
                    1 -> share(e)
                    2 -> { pendingExport = e; exportLauncher.launch(history.fileOf(e).name) }
                    3 -> confirmDelete(e)
                }
            }
            .setNegativeButton(R.string.update_close) { _, _ -> showHistory() }
            .show()
    }

    private fun installFromHistory(e: ApkHistory.Entry) {
        val older = if (e.versionCode > 0) e.versionCode < BuildConfig.VERSION_CODE
        else AppUpdater.compareVersions(e.version, BuildConfig.VERSION_NAME) < 0
        val file = history.fileOf(e)
        if (!older) { install(file); return }
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.update_downgrade_title)
            .setMessage(str(R.string.update_downgrade_msg, e.version, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.update_downgrade_try) { _, _ -> install(file) }
            .setNegativeButton(R.string.update_cancel, null)
            .show()
    }

    private fun share(e: ApkHistory.Entry) {
        val c = fragment.requireContext()
        val send = ApkInstaller.shareIntent(c, history.fileOf(e))
        fragment.startActivity(Intent.createChooser(send, str(R.string.update_share_title)))
    }

    private fun exportTo(e: ApkHistory.Entry, uri: Uri) {
        val src = history.fileOf(e)
        val resolver = ctx.contentResolver
        Thread {
            val ok = try {
                resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } } != null
            } catch (ex: IOException) { false }
            ui {
                Toast.makeText(
                    fragment.requireContext(),
                    if (ok) R.string.update_exported else R.string.update_export_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }.start()
    }

    private fun confirmDelete(e: ApkHistory.Entry) {
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.update_delete_confirm_title)
            .setMessage(str(R.string.update_delete_confirm_msg, e.version))
            .setPositiveButton(R.string.update_action_delete) { _, _ ->
                history.remove(e)
                Toast.makeText(fragment.requireContext(), R.string.update_deleted, Toast.LENGTH_SHORT).show()
                showHistory()
            }
            .setNegativeButton(R.string.update_cancel) { _, _ -> showHistory() }
            .show()
    }

    private fun confirmPrune() {
        val c = fragment.requireContext()
        val keep = prefs.keepLatest
        if (keep <= 0) {
            Toast.makeText(c, R.string.update_prune_set_n, Toast.LENGTH_LONG).show()
            showHistory()
            return
        }
        val victims = history.entries().drop(keep)
            .count { AppUpdater.compareVersions(it.version, BuildConfig.VERSION_NAME) != 0 }
        if (victims == 0) {
            Toast.makeText(c, R.string.update_prune_nothing, Toast.LENGTH_SHORT).show()
            showHistory()
            return
        }
        MaterialAlertDialogBuilder(c)
            .setTitle(R.string.update_btn_prune)
            .setMessage(str(R.string.update_prune_confirm, victims, keep))
            .setPositiveButton(R.string.update_btn_prune) { _, _ ->
                val removed = history.pruneKeepLatest(keep, BuildConfig.VERSION_NAME).size
                Toast.makeText(c, str(R.string.update_pruned, removed), Toast.LENGTH_SHORT).show()
                showHistory()
            }
            .setNegativeButton(R.string.update_cancel) { _, _ -> showHistory() }
            .show()
    }

    private fun dp(v: Int) = (v * fragment.resources.displayMetrics.density).toInt()
}
