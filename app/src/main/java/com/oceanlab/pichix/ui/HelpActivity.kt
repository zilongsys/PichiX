package com.oceanlab.pichix.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.oceanlab.pichix.R

class HelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyFromSettings(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_pichix_stub)
        findViewById<TextView>(R.id.stubSectionLabel)?.text = "AYUDA"
        findViewById<TextView>(R.id.stubTitle)?.text = "PichiX"
        findViewById<TextView>(R.id.stubMessage)?.text =
            "Asistente para Amazon Flex.\n\n" +
                "1. Configura el paquete de Flex y activa accesibilidad.\n" +
                "2. Ajusta tarifas mínimas (criterios del grabber).\n" +
                "3. Activa el switch del header.\n\n" +
                "La automatización se implementará por fases a partir de tus macros FLEX."
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.help_title)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
