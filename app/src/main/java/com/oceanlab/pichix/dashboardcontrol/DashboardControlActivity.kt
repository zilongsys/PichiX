package com.oceanlab.pichix.dashboardcontrol

// Pantalla "Conexión con PC": IP, puerto, ruta, token, activar/desactivar, estado en vivo
// y cuántos registros esperan para enviarse a la PC.

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.oceanlab.pichix.R
import com.oceanlab.pichix.ui.ThemeHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DashboardControlActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val dayFmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var pending: TextView
    private lateinit var host: TextInputEditText
    private lateinit var port: TextInputEditText
    private lateinit var path: TextInputEditText
    private lateinit var token: TextInputEditText
    private lateinit var urlPreview: TextView
    private lateinit var enabled: SwitchMaterial

    private val previewWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { updatePreview() }
    }

    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyFromSettings(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dashboardcontrol_activity)
        status = findViewById(R.id.dcStatus)
        detail = findViewById(R.id.dcDetail)
        pending = findViewById(R.id.dcPending)
        host = findViewById(R.id.dcHost)
        port = findViewById(R.id.dcPort)
        path = findViewById(R.id.dcPath)
        token = findViewById(R.id.dcToken)
        urlPreview = findViewById(R.id.dcUrlPreview)
        enabled = findViewById(R.id.dcEnabled)

        fillFromSaved()
        host.addTextChangedListener(previewWatcher)
        port.addTextChangedListener(previewWatcher)
        path.addTextChangedListener(previewWatcher)
        updatePreview()

        findViewById<android.view.View>(R.id.dcBack).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.dcSave).setOnClickListener { save() }
        findViewById<android.view.View>(R.id.dcRetry).setOnClickListener {
            DashboardLink.reconnectNow()
            Toast.makeText(this, "Reintentando…", Toast.LENGTH_SHORT).show()
        }
        findViewById<android.view.View>(R.id.dcClear).setOnClickListener { confirmClear() }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun fillFromSaved() {
        val ep = DashboardLink.currentEndpoint(this)
        host.setText(ep.host)
        port.setText(ep.port.toString())
        path.setText(ep.path.ifBlank { DashboardLink.DEFAULT_PATH })
        token.setText(DashboardLink.currentToken(this))
        enabled.isChecked = DashboardLink.isEnabled(this)
    }

    private fun readPort(): Int =
        port.text?.toString()?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: DashboardLink.DEFAULT_PORT

    private fun readPath(): String {
        val raw = path.text?.toString()?.trim().orEmpty()
        return raw.ifBlank { DashboardLink.DEFAULT_PATH }
    }

    private fun updatePreview() {
        val h = host.text?.toString()?.trim().orEmpty()
        val built = if (h.isEmpty()) {
            "ws://<IP>:${readPort()}${normalizePathPreview(readPath())}"
        } else {
            DashboardLink.buildUrl(h, readPort(), readPath())
        }
        urlPreview.text = "URL: $built"
    }

    private fun normalizePathPreview(raw: String): String {
        var p = raw.trim().ifEmpty { DashboardLink.DEFAULT_PATH }
        if (!p.startsWith("/")) p = "/$p"
        return p
    }

    private fun save() {
        val h = host.text?.toString()?.trim().orEmpty()
        val t = token.text?.toString()?.trim().orEmpty()
        val p = readPort()
        val pathPart = readPath()
        if (enabled.isChecked && (h.isEmpty() || t.isEmpty())) {
            Toast.makeText(this, "Escribe la IP de la PC y el token", Toast.LENGTH_LONG).show()
            return
        }
        val url = if (h.isEmpty()) "" else DashboardLink.buildUrl(h, p, pathPart)
        DashboardLink.configure(this, url, t, enabled.isChecked)
        fillFromSaved()
        updatePreview()
        Toast.makeText(this, if (enabled.isChecked) "Guardado · conectando" else "Guardado · desactivado", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun confirmClear() {
        val n = DashboardLink.info().pending
        if (n <= 0) {
            Toast.makeText(this, "No hay nada pendiente", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Borrar pendientes?")
            .setMessage("Se borrarán ${fmtCount(n)} registros que aún no llegaron a la PC. No se puede deshacer.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Borrar") { _, _ -> DashboardLink.clearPending { render() } }
            .show()
    }

    private fun render() {
        val i = DashboardLink.info()
        status.text = i.status.label
        status.setTextColor(
            when (i.status) {
                LinkStatus.CONECTADO -> Color.rgb(22, 163, 74)
                LinkStatus.CONECTANDO -> Color.rgb(217, 119, 6)
                LinkStatus.TOKEN_INVALIDO, LinkStatus.SIN_CONEXION -> Color.rgb(220, 38, 38)
                else -> status.currentHintTextColor
            },
        )
        val now = System.currentTimeMillis()
        detail.text = when (i.status) {
            LinkStatus.CONECTADO -> "Conectado desde las ${timeFmt.format(Date(i.lastConnectedAt))}"
            LinkStatus.SIN_CONEXION, LinkStatus.TOKEN_INVALIDO -> buildString {
                if (i.nextRetryAt > now) append("Reintento en ${(i.nextRetryAt - now + 999) / 1000} s")
                if (i.lastError.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(i.lastError)
                }
                if (i.lastConnectedAt > 0) append("\nÚltima conexión: ${dayFmt.format(Date(i.lastConnectedAt))}")
            }
            LinkStatus.APAGADO -> "No se guarda nada para la PC mientras esté apagado."
            else -> ""
        }
        pending.text = when {
            i.pending > 0 && i.oldestPendingTs > 0 ->
                "${fmtCount(i.pending)} registros esperando para enviarse (desde ${dayFmt.format(Date(i.oldestPendingTs))})"
            i.pending > 0 -> "${fmtCount(i.pending)} registros esperando para enviarse"
            i.status == LinkStatus.APAGADO -> ""
            else -> "Nada pendiente: todo está en la PC"
        }
    }

    private fun fmtCount(n: Long): String = String.format(Locale.getDefault(), "%,d", n)
}
