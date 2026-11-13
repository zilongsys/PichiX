package com.oceanlab.pichix.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.oceanlab.pichix.R

/**
 * Una sección de Ajustes a pantalla completa (o todas, con buscador, si no se pasa sección).
 * Reutiliza [FlexConfigFragment] en «modo sección»: mismas vistas, mismo guardado.
 */
class ConfigSectionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyFromSettings(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_section)
        val section = intent.getStringExtra(EXTRA_SECTION)
        findViewById<TextView>(R.id.tvSectionTitle).text =
            intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.nav_settings)
        findViewById<View>(R.id.btnSectionBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.sectionContainer, FlexConfigFragment.newInstance(section))
                .commit()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val f = supportFragmentManager.findFragmentById(R.id.sectionContainer) as? FlexConfigFragment
                if (f == null || !f.hasUnsavedChanges) {
                    finish()
                    return
                }
                MaterialAlertDialogBuilder(this@ConfigSectionActivity)
                    .setTitle(R.string.config_unsaved_title)
                    .setMessage(R.string.config_unsaved_msg)
                    .setPositiveButton(R.string.config_btn_save) { _, _ -> if (f.requestSave()) finish() }
                    .setNegativeButton(R.string.config_unsaved_discard) { _, _ -> finish() }
                    .setNeutralButton(R.string.update_cancel, null)
                    .show()
            }
        })
    }

    companion object {
        const val EXTRA_SECTION = "section"
        const val EXTRA_TITLE = "title"

        fun open(context: Context, section: String?, title: String) {
            context.startActivity(
                Intent(context, ConfigSectionActivity::class.java)
                    .putExtra(EXTRA_SECTION, section)
                    .putExtra(EXTRA_TITLE, title),
            )
        }
    }
}
