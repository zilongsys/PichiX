package com.oceanlab.pichix.util

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri

object SoundUriLabel {
    fun label(context: Context, uriStr: String, emptyLabel: String = "Sonido del sistema"): String {
        if (uriStr.isBlank()) return emptyLabel
        return try {
            RingtoneManager.getRingtone(context, Uri.parse(uriStr))?.getTitle(context)
                ?: "Audio personalizado"
        } catch (_: Exception) {
            "Audio personalizado"
        }
    }
}
