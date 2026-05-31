package com.oceanlab.pichix.ui

import android.content.Context
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText

object ChipInputHelper {

    fun addChip(
        context: Context,
        group: ChipGroup,
        text: String,
        onChanged: () -> Unit,
        refocusField: TextInputEditText? = null,
    ) {
        for (i in 0 until group.childCount) {
            if ((group.getChildAt(i) as? Chip)?.text.toString() == text) return
        }
        val chip = Chip(context).apply {
            this.text = text
            isCloseIconVisible = true
            isFocusable = false
            isFocusableInTouchMode = false
            setOnCloseIconClickListener {
                group.removeView(this)
                onChanged()
                refocusField?.post {
                    if (refocusField.isEnabled && refocusField.isShown) refocusField.requestFocus()
                }
            }
        }
        group.addView(chip)
    }

    fun collectChips(group: ChipGroup): List<String> =
        (0 until group.childCount).mapNotNull { (group.getChildAt(it) as? Chip)?.text?.toString() }
}
