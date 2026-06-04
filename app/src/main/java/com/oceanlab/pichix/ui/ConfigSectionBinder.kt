package com.oceanlab.pichix.ui

import android.util.TypedValue
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.oceanlab.pichix.R

object ConfigSectionBinder {

    fun bind(
        header: TextView,
        content: View,
        scrollHost: View? = null,
        startExpanded: Boolean = true,
    ) {
        var open = startExpanded
        val baseTitle = header.text.toString()
            .removeSuffix(" ▼")
            .removeSuffix(" ▶")
            .trim()
        header.text = baseTitle
        header.isClickable = true
        header.isFocusable = true
        header.applySelectableForeground()
        header.setCompoundDrawablesWithIntrinsicBounds(0, 0, chevronFor(open), 0)
        header.compoundDrawablePadding = (header.resources.displayMetrics.density * 6).toInt()
        content.visibility = if (open) View.VISIBLE else View.GONE

        header.setOnClickListener {
            val toggle = {
                open = !open
                content.visibility = if (open) View.VISIBLE else View.GONE
                header.setCompoundDrawablesWithIntrinsicBounds(0, 0, chevronFor(open), 0)
            }
            when (scrollHost) {
                is ScrollView -> scrollHost.runRetainingScrollAndFocus { toggle() }
                is NestedScrollView -> scrollHost.runRetainingScrollAndFocus { toggle() }
                else -> toggle()
            }
        }
    }

    private fun View.applySelectableForeground() {
        val tv = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            foreground = ContextCompat.getDrawable(context, tv.resourceId)
        }
    }

    private fun chevronFor(expanded: Boolean): Int =
        if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
}
