package com.oceanlab.pichix.util

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment

/**
 * Elige sonido del sistema (ringtone) o un archivo de audio del dispositivo (carpetas / almacenamiento).
 */
class SoundPickerHelper(
    fragment: Fragment,
    private val onPicked: (uriString: String) -> Unit,
) {
    private val audioFilePicker = fragment.registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            fragment.requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: Exception) {
            // Algunos proveedores no permiten persistencia; MediaPlayer puede seguir funcionando en la sesión.
        }
        onPicked(uri.toString())
    }

    private val ringtonePicker = fragment.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        onPicked(uri?.toString().orEmpty())
    }

    fun pickAudioFromDevice() {
        audioFilePicker.launch(arrayOf("audio/*"))
    }

    fun pickRingtone(currentUri: String = "") {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_TYPE,
                RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_RINGTONE,
            )
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Elegir sonido")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            if (currentUri.isNotBlank()) {
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(currentUri))
            }
        }
        ringtonePicker.launch(intent)
    }
}
