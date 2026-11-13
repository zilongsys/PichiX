package com.oceanlab.pichix.ui

import android.content.Intent

object CategoryUiHelper {

    /** Se emite al cambiar «Mostrar nombres en la barra inferior»; MainActivity reaplica las etiquetas. */
    const val ACTION_CATEGORY_UI_CHANGED = "com.oceanlab.pichix.CATEGORY_UI_CHANGED"

    fun categoryUiChangedIntent() = Intent(ACTION_CATEGORY_UI_CHANGED)
}
