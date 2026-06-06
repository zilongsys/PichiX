package com.oceanlab.pichix.ui

import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.AppSettings

object ConfigCollapsibleHint {

    fun bind(
        toggleHeader: View,
        toggleLabel: TextView,
        content: View,
        settings: AppSettings,
        hintKey: String,
        scrollHost: View? = null,
        defaultVisible: Boolean = false,
    ) {
        toggleHeader.preventCollapsibleHeaderFocusSteal()

        fun applyVisible(visible: Boolean) {
            content.visibility = if (visible) View.VISIBLE else View.GONE
            toggleLabel.text = toggleLabel.context.getString(
                if (visible) R.string.config_phase_note_hide else R.string.config_phase_note_show,
            )
        }

        applyVisible(settings.isConfigHintVisible(hintKey, defaultVisible))
        toggleHeader.setOnClickListener {
            val toggle = {
                val visible = content.visibility != View.VISIBLE
                settings.setConfigHintVisible(hintKey, visible)
                applyVisible(visible)
            }
            when (scrollHost) {
                is ScrollView -> scrollHost.runRetainingScrollAndFocus(toggle)
                is NestedScrollView -> scrollHost.runRetainingScrollAndFocus(toggle)
                else -> toggle()
            }
        }
    }
}
