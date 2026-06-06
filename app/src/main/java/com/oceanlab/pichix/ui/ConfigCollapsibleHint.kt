package com.oceanlab.pichix.ui

import android.view.View
import android.widget.TextView
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.AppSettings

object ConfigCollapsibleHint {

    fun bind(
        toggleHeader: View,
        toggleLabel: TextView,
        content: View,
        settings: AppSettings,
        hintKey: String,
        defaultVisible: Boolean = false,
    ) {
        fun applyVisible(visible: Boolean) {
            content.visibility = if (visible) View.VISIBLE else View.GONE
            toggleLabel.text = toggleLabel.context.getString(
                if (visible) R.string.config_phase_note_hide else R.string.config_phase_note_show,
            )
        }

        applyVisible(settings.isConfigHintVisible(hintKey, defaultVisible))
        toggleHeader.setOnClickListener {
            val visible = content.visibility != View.VISIBLE
            settings.setConfigHintVisible(hintKey, visible)
            applyVisible(visible)
        }
    }
}
