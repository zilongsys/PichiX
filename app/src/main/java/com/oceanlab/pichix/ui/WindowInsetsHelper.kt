package com.oceanlab.pichix.ui

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.oceanlab.pichix.R

fun Activity.setupPichiXWindowInsets() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val root = findViewById<View>(R.id.mainRoot) ?: return
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        findViewById<View>(R.id.viewHeader)?.updatePadding(top = bars.top)
        findViewById<View>(R.id.mainBody)?.updatePadding(bottom = bars.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(root)
}
