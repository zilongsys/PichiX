package com.oceanlab.pichix.ui

import android.content.Intent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible

object CategoryUiHelper {

    const val ACTION_CATEGORY_UI_CHANGED = "com.oceanlab.pichix.CATEGORY_UI_CHANGED"

    fun categoryUiChangedIntent() = Intent(ACTION_CATEGORY_UI_CHANGED)

    fun applySidebarCategoryUi(
        sidebar: LinearLayout,
        containers: Array<LinearLayout?>,
        icons: Array<ImageView?>,
        labels: Array<TextView?>,
        helpContainer: LinearLayout?,
        helpIcon: ImageView?,
        helpLabel: TextView?,
        showNames: Boolean
    ) {
        val density = sidebar.resources.displayMetrics.density
        fun Int.dp() = (this * density).toInt()

        sidebar.layoutParams = sidebar.layoutParams.apply {
            width = if (showNames) 62.dp() else 56.dp()
        }

        val iconSize = if (showNames) 36.dp() else 46.dp()
        val rowHeight = if (showNames) 58.dp() else 52.dp()
        val pad = if (showNames) 7.dp() else 8.dp()

        fun styleNavItem(container: LinearLayout?, icon: ImageView?, label: TextView?) {
            container?.layoutParams = container?.layoutParams?.apply {
                height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }
            container?.minimumHeight = rowHeight
            label?.isVisible = showNames
            icon?.let {
                it.layoutParams = it.layoutParams.apply {
                    width = iconSize
                    height = iconSize
                }
                it.setPadding(pad, pad, pad, pad)
            }
        }

        for (i in containers.indices) {
            styleNavItem(containers[i], icons[i], labels[i])
        }
        styleNavItem(helpContainer, helpIcon, helpLabel)
    }
}
