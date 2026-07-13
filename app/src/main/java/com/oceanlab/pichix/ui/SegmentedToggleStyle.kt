package com.oceanlab.pichix.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import androidx.core.content.ContextCompat
import com.oceanlab.pichix.R

/** Estilo de botones segmentados coherente con tema claro/oscuro. */
object SegmentedToggleStyle {

    fun createSegmentButton(context: Context, text: CharSequence): MaterialButton =
        MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            textSize = 10f
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            insetTop = 0
            insetBottom = 0
        }

    fun apply(button: MaterialButton, selected: Boolean) {
        val ctx = button.context
        val activeBg = ContextCompat.getColor(ctx, R.color.tab_active_bg)
        val activeText = ContextCompat.getColor(ctx, R.color.button_primary_text)
        val inactiveBg = ContextCompat.getColor(ctx, R.color.bg_card)
        val inactiveText = ContextCompat.getColor(ctx, R.color.text_primary)
        val activeStroke = ContextCompat.getColor(ctx, R.color.accent_teal_dark)
        val inactiveStroke = ContextCompat.getColor(ctx, R.color.border_subtle)
        button.backgroundTintList = ColorStateList.valueOf(if (selected) activeBg else inactiveBg)
        button.setTextColor(if (selected) activeText else inactiveText)
        button.strokeColor = ColorStateList.valueOf(if (selected) activeStroke else inactiveStroke)
    }

    fun applyGroup(group: MaterialButtonToggleGroup, checkedId: Int) {
        for (i in 0 until group.childCount) {
            val btn = group.getChildAt(i) as? MaterialButton ?: continue
            apply(btn, btn.id == checkedId)
        }
    }

    /**
     * Aplica colores al cambiar selección. [onChecked] recibe el id del botón activo.
     */
    fun wireGroup(
        group: MaterialButtonToggleGroup,
        focusRoot: View? = null,
        onChecked: ((Int) -> Unit)? = null,
    ) {
        group.addOnButtonCheckedListener { g, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            applyGroup(g, checkedId)
            val run: () -> Unit = {
                onChecked?.invoke(checkedId)
            }
            if (focusRoot != null) {
                focusRoot.formFocusHost().runRetainingFocus(run)
            } else {
                run()
            }
        }
        val initialId = when {
            group.checkedButtonId != View.NO_ID -> group.checkedButtonId
            group.childCount > 0 -> (group.getChildAt(0) as? MaterialButton)?.id ?: View.NO_ID
            else -> View.NO_ID
        }
        if (initialId != View.NO_ID) {
            applyGroup(group, initialId)
        }
    }
}
