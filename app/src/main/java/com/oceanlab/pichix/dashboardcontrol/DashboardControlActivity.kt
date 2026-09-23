package com.oceanlab.pichix.dashboardcontrol

// Pantalla "Conexión con PC": dirección, token, activar/desactivar, estado en vivo
// y cuántos registros esperan para enviarse a la PC.

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private lateinit var url: TextInputEditText
    private lateinit var token: TextInputEditText
    private lateinit var enabled: SwitchMaterial

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
        url = findViewById(R.id.dcUrl)
        token = findViewById(R.id.dcToken)
        enabled = findViewById(R.id.dcEnabled)

        url.setText(DashboardLink.currentUrl(this))
        token.setText(DashboardLink.currentToken(this))
        enabled.isChecked = DashboardLink.isEnabled(this)

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

    private fun save() {
        val u = url.text?.toString()?.trim().orEmpty()
        val t = token.text?.toString()?.trim().orEmpty()
        if (enabled.isChecked && (u.isEmpty() || t.isEmpty())) {
            Toast.makeText(this, "Escribe la dirección de la PC y el token", Toast.LENGTH_LONG).show()
            return
        }
        DashboardLink.configure(this, u, t, enabled.isChecked)
        url.setText(DashboardLink.currentUrl(this))
        token.setText(DashboardLink.currentToken(this))
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
