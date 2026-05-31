package com.oceanlab.pichix.ui

import com.google.android.material.button.MaterialButtonToggleGroup
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.AppSettings

object TextMatchUiHelper {
    fun setMatchMode(
        group: MaterialButtonToggleGroup,
        mode: String,
        containsButtonId: Int,
        exactButtonId: Int,
    ) {
        group.check(
            if (mode == AppSettings.TEXT_MATCH_EXACT) exactButtonId else containsButtonId,
        )
    }

    fun readMatchMode(
        group: MaterialButtonToggleGroup,
        exactButtonId: Int,
    ): String =
        if (group.checkedButtonId == exactButtonId) {
            AppSettings.TEXT_MATCH_EXACT
        } else {
            AppSettings.TEXT_MATCH_CONTAINS
        }
}
