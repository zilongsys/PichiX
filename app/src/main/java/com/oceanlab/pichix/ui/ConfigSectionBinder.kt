package com.oceanlab.pichix.ui

import android.util.TypedValue
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.AppSettings

object ConfigSectionBinder {

    fun bind(
        header: TextView,
        content: View,
        scrollHost: View? = null,
        sectionKey: String,
        startExpanded: Boolean = true,
    ) {
        val settings = AppSettings(header.context)
        var open = settings.isConfigSectionExpanded(sectionKey, startExpanded)
        val baseTitle = header.text.toString()
            .removeSuffix(" ▼")
            .removeSuffix(" ▶")
            .trim()
        header.text = baseTitle
        header.isClickable = true
        header.preventCollapsibleHeaderFocusSteal()
        header.applySelectableForeground()
        applyExpandedState(header, content, open)

        header.setOnClickListener {
            val focused = header.rootView.findFocus()
            val toggle = {
                open = !open
                applyExpandedState(header, content, open)
                settings.setConfigSectionExpanded(sectionKey, open)
            }
            when (scrollHost) {
                is ScrollView -> scrollHost.runRetainingScrollForSectionToggle(header, toggle)
                is NestedScrollView -> scrollHost.runRetainingScrollForSectionToggle(header, toggle)
                else -> toggle()
            }
            header.post { header.rootView.restoreFocus(focused) }
        }
    }

    private fun applyExpandedState(header: TextView, content: View, open: Boolean) {
        content.visibility = if (open) View.VISIBLE else View.GONE
        header.setCompoundDrawablesWithIntrinsicBounds(0, 0, chevronFor(open), 0)
        header.compoundDrawablePadding = (header.resources.displayMetrics.density * 6).toInt()
    }

    private fun View.applySelectableForeground() {
        val tv = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            foreground = ContextCompat.getDrawable(context, tv.resourceId)
        }
    }

    private fun chevronFor(expanded: Boolean): Int =
        if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down

    /** Expande la sección si está colapsada (p. ej. banner de permisos). */
    fun ensureExpanded(
        header: TextView,
        content: View,
        scrollHost: View?,
        sectionKey: String,
    ) {
        if (content.visibility == View.VISIBLE) return
        val settings = AppSettings(header.context)
        settings.setConfigSectionExpanded(sectionKey, true)
        applyExpandedState(header, content, true)
    }
}
