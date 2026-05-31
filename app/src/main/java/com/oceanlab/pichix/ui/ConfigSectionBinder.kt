package com.oceanlab.pichix.ui

import android.view.View
import android.widget.TextView

object ConfigSectionBinder {
    fun bind(header: TextView, content: View, startExpanded: Boolean = true) {
        var open = startExpanded
        content.visibility = if (open) View.VISIBLE else View.GONE
        val baseTitle = header.text.toString().removeSuffix(" ▼").removeSuffix(" ▶")
        header.text = "$baseTitle ${if (open) "▼" else "▶"}"
        header.setOnClickListener {
            open = !open
            content.visibility = if (open) View.VISIBLE else View.GONE
            header.text = "$baseTitle ${if (open) "▼" else "▶"}"
        }
    }
}
