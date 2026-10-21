package com.oceanlab.pichix.ui

import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.checkbox.MaterialCheckBox
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.PichixFullBackup
import org.json.JSONObject

object PichixBackupImportDialog {

    fun show(
        fragment: Fragment,
        backupText: String,
        root: JSONObject,
        onSuccess: () -> Unit,
    ) {
        val ctx = fragment.requireContext()
        val sections = PichixFullBackup.listImportSections(root)
        if (sections.isEmpty()) {
            Toast.makeText(ctx, R.string.config_backup_file_invalid, Toast.LENGTH_LONG).show()
            return
        }
        val dialogView = fragment.layoutInflater.inflate(R.layout.dialog_import_backup, null)
        val legacy = PichixFullBackup.isLegacyTariffOnly(root)
        val version = root.optString("appVersionName", root.optString("appVersion", ""))
            .ifBlank { if (legacy) "tarifas" else "?" }
        dialogView.findViewById<TextView>(R.id.tvImportBackupSummary).text = buildString {
            append(ctx.getString(R.string.config_backup_import_origin, version))
            append('\n')
            if (legacy) append(ctx.getString(R.string.config_backup_import_legacy))
            if (root.optString("format") == "pichix_config_backup") {
                append(ctx.getString(R.string.config_backup_import_config_only))
            }
        }
        val replaceLogsCb = dialogView.findViewById<MaterialCheckBox>(R.id.cbImportReplaceLogs)
        val container = dialogView.findViewById<LinearLayout>(R.id.layoutImportChecks)
        val checkboxes = mutableListOf<MaterialCheckBox>()
        sections.forEach { section ->
            val cb = MaterialCheckBox(ctx).apply {
                text = section.label
                isChecked = section.selected
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            }
            checkboxes.add(cb)
            container.addView(cb)
        }
        AlertDialog.Builder(ctx, R.style.SparkAlertDialogTheme)
            .setTitle(R.string.config_backup_import_title)
            .setView(dialogView)
            .setPositiveButton(R.string.config_backup_import_confirm) { _, _ ->
                sections.forEachIndexed { i, section ->
                    section.selected = checkboxes[i].isChecked
                }
                val options = PichixFullBackup.optionsFromSections(
                    sections,
                    replaceLogs = replaceLogsCb.isChecked,
                )
                if (!options.restoreSettings && !options.restoreTariffRules &&
                    !options.restoreAlertRules && !options.restoreOfferLog &&
                    !options.restoreAppliedSuggestions && !options.restoreDashboard &&
                    !options.restoreFileLogs && !options.restoreRefreshGuard
                ) {
                    Toast.makeText(ctx, R.string.config_backup_import_nothing_selected, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val result = PichixFullBackup.importAll(ctx, backupText, options)
                Toast.makeText(ctx, result.message, Toast.LENGTH_LONG).show()
                if (result.success) {
                    onSuccess()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
