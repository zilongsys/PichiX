package com.oceanlab.pichix.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.oceanlab.pichix.data.AppSettings

object ThemeHelper {

    fun applyFromSettings(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            if (AppSettings(context).useDarkTheme) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        )
    }

    fun setDarkTheme(context: Context, dark: Boolean) {
        AppSettings(context).useDarkTheme = dark
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}
