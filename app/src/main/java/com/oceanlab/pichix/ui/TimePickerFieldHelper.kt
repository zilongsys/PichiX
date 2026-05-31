package com.oceanlab.pichix.ui

import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat

object TimePickerFieldHelper {

    fun attachClockPicker(
        fragment: Fragment,
        field: TextInputEditText,
        layout: TextInputLayout? = null,
        title: String = "Seleccionar hora",
        onChanged: () -> Unit = {},
    ) {
        field.isClickable = true
        field.isFocusableInTouchMode = true
        val openPicker = {
            val (h, m) = parseHHmm(field.text?.toString()) ?: Pair(8, 0)
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(h)
                .setMinute(m)
                .setTitleText(title)
                .build()
            picker.addOnPositiveButtonClickListener {
                field.setText(formatHHmm(picker.hour, picker.minute))
                onChanged()
            }
            val fm = dialogFragmentManager(fragment)
            if (!fm.isStateSaved) {
                picker.show(fm, "time_${field.id}")
            }
        }
        field.setOnClickListener { openPicker() }
        layout?.setEndIconOnClickListener { openPicker() }
    }

    fun formatHHmm(hour: Int, minute: Int): String =
        "%02d:%02d".format(hour.coerceIn(0, 23), minute.coerceIn(0, 59))

    fun parseHHmm(text: String?): Pair<Int, Int>? {
        val parts = text?.trim()?.split(":") ?: return null
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h to m
    }

    fun parseToMinutesOfDay(text: String?): Int? {
        val (h, m) = parseHHmm(text) ?: return null
        return h * 60 + m
    }

    fun minutesOfDayToHHmm(minutes: Int): String {
        val h = (minutes / 60) % 24
        val m = minutes % 60
        return formatHHmm(h, m)
    }

    /** Duración en minutos desde HH:mm (ej. 02:30 → 150 min de anticipación). */
    fun parseDurationMinutes(text: String?): Int? {
        val (h, m) = parseHHmm(text) ?: return null
        return h * 60 + m
    }

    private fun dialogFragmentManager(fragment: Fragment): FragmentManager =
        when (fragment) {
            is BottomSheetDialogFragment -> fragment.childFragmentManager
            is DialogFragment -> fragment.childFragmentManager
            else -> fragment.parentFragmentManager
        }

    fun durationMinutesToHHmm(totalMinutes: Int): String {
        val h = (totalMinutes / 60).coerceAtLeast(0)
        val m = (totalMinutes % 60).coerceAtLeast(0)
        return formatHHmm(h % 24, m)
    }
}
