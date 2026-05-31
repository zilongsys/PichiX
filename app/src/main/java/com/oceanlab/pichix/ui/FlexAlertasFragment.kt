package com.oceanlab.pichix.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.MonitorPackages
import com.oceanlab.pichix.data.FlexAlertRule
import com.oceanlab.pichix.data.FlexAlertRulesStore
import com.oceanlab.pichix.service.FlexNotificationListenerService
import com.oceanlab.pichix.util.AlertManager
import com.oceanlab.pichix.util.MatchTextParser
import com.oceanlab.pichix.util.SoundPickerHelper
import com.oceanlab.pichix.util.SoundUriLabel
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

class FlexAlertasFragment : Fragment() {

    private lateinit var settings: AppSettings
    private lateinit var listContainer: LinearLayout
    private lateinit var tvAccessStatus: TextView
    private lateinit var tvSoundDraft: TextView
    private lateinit var etName: TextInputEditText
    private lateinit var etMatch: TextInputEditText
    private lateinit var etRepeat: TextInputEditText
    private lateinit var switchAlertService: SwitchMaterial
    private lateinit var scrollView: ScrollView
    private var draftSoundUri: String = ""
    private var draftMatchMode: String = FlexAlertRule.MATCH_ANY
    private var pickingRuleId: String? = null
    private var onSoundPicked: ((String) -> Unit)? = null
    private var ringtoneFocusRestore: View? = null
    private lateinit var audioFilePicker: SoundPickerHelper
    private var matchModeToggle: MaterialButtonToggleGroup? = null

    private data class IconAction(
        val iconRes: Int,
        val contentDescription: String,
        val tintRes: Int = R.color.text_primary,
        val action: () -> Unit,
    )

    private val ringtonePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode != Activity.RESULT_OK) {
            onSoundPicked = null
            pickingRuleId = null
            restoreAfterRingtonePicker()
            return@registerForActivityResult
        }
        val uri: Uri? = res.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        val picked = uri?.toString() ?: ""
        val ruleId = pickingRuleId
        val consumer = onSoundPicked
        val savedFocus = ringtoneFocusRestore
        val savedScroll = ringtoneScrollY
        onSoundPicked = null
        pickingRuleId = null
        ringtoneFocusRestore = null
        scrollView.post {
            when {
                consumer != null -> consumer(picked)
                ruleId == null -> {
                    draftSoundUri = picked
                    tvSoundDraft.text = soundName(picked)
                }
                else -> {
                    saveRule(ruleId) { it.copy(soundUri = picked) }
                    renderRules()
                }
            }
            scrollView.scrollTo(0, savedScroll)
            scrollView.restoreFocus(savedFocus)
        }
    }

    private var ringtoneScrollY: Int = 0

    private fun restoreAfterRingtonePicker() {
        if (!::scrollView.isInitialized) return
        val target = ringtoneFocusRestore
        ringtoneFocusRestore = null
        scrollView.post {
            scrollView.scrollTo(0, ringtoneScrollY)
            scrollView.restoreFocus(target)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        scrollView = ScrollView(requireContext()).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.bg_page))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isFillViewport = true
            isScrollbarFadingEnabled = false
            preventScrollFocusSteal()
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            preventScrollFocusSteal()
        }
        scrollView.addView(root)

        root.addView(sectionTitle("MONITOREO NOTIFICACIONES"))
        root.addView(card().apply {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(context).apply {
                text = "Servicio de alertas"
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            switchAlertService = SwitchMaterial(context).apply { styleWideSwitch(primary = true) }
            row.addView(switchAlertService)
            addView(row)
            addView(smallText("Control independiente del bot. Si está activo y tienes acceso a notificaciones, las alertas suenan aunque el bot esté apagado."))
        })
        tvAccessStatus = infoBox("")
        root.addView(tvAccessStatus)
        root.addView(
            iconActionButton(
                iconRes = R.drawable.ic_alert_notifications,
                contentDescription = "Abrir acceso a notificaciones",
                tintRes = R.color.accent_teal,
            ) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
        )

        root.addView(sectionTitle("NUEVA ACCIÓN"))
        root.addView(card().apply {
            addView(label("Nombre"))
            etName = input("ej: Oferta reservada")
            addView(etName)
            addView(label("Textos a detectar"))
            etMatch = input("Uno por línea o separados por coma").apply {
                maxLines = 4
                setSingleLine(false)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            addView(etMatch)
            addView(smallText("Una alerta puede vigilar varios textos. Elige si basta con uno (cualquiera) o deben aparecer todos."))
            matchModeToggle = MaterialButtonToggleGroup(context).apply {
                isSingleSelection = true
                isSelectionRequired = true
                addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    id = View.generateViewId()
                    text = "Cualquiera"
                    tag = FlexAlertRule.MATCH_ANY
                })
                addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    id = View.generateViewId()
                    text = "Todas"
                    tag = FlexAlertRule.MATCH_ALL
                })
                check((getChildAt(0) as MaterialButton).id)
                addOnButtonCheckedListener { _, checkedId, isChecked ->
                    if (!isChecked) return@addOnButtonCheckedListener
                    draftMatchMode = when (checkedId) {
                        (getChildAt(1) as MaterialButton).id -> FlexAlertRule.MATCH_ALL
                        else -> FlexAlertRule.MATCH_ANY
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(10) }
            }
            addView(matchModeToggle)
            addView(label("Sonido"))
            tvSoundDraft = smallText(soundName(draftSoundUri))
            addView(tvSoundDraft)
            addView(label("Veces que suena"))
            etRepeat = input("2").apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(FlexAlertRule.DEFAULT_REPEAT_COUNT.toString())
            }
            addView(etRepeat)
            addView(iconActionBar(
                IconAction(R.drawable.ic_btn_music, "Elegir sonido") {
                    launchRingtonePicker(draftSoundUri)
                },
                IconAction(android.R.drawable.ic_menu_upload, "Audio del dispositivo") {
                    launchAudioPicker { picked ->
                        draftSoundUri = picked
                        tvSoundDraft.text = soundName(picked)
                    }
                },
                IconAction(android.R.drawable.ic_menu_preferences, "Sonido del sistema") {
                    draftSoundUri = ""
                    tvSoundDraft.text = soundName("")
                },
                IconAction(R.drawable.ic_btn_play, "Probar sonido", R.color.green_400) {
                    AlertManager(requireContext()).playFlexNotificationAlert(draftSoundUri, draftRepeatCount())
                },
                IconAction(R.drawable.ic_btn_stop, "Detener sonido", R.color.coral_600) {
                    AlertManager.stopGlobal()
                },
            ))
            addView(
                iconActionButton(
                    iconRes = android.R.drawable.ic_input_add,
                    contentDescription = "Agregar acción",
                    tintRes = R.color.accent_teal,
                ) { addRule() },
            )
        })

        root.addView(sectionTitle("ACCIONES CONFIGURADAS"))
        listContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        return scrollView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        settings = AppSettings(requireContext())
        audioFilePicker = SoundPickerHelper(this) { uri ->
            val consumer = onSoundPicked
            onSoundPicked = null
            consumer?.invoke(uri)
        }
        scrollView.setupFormFocus()
        switchAlertService.isChecked = settings.flexAlertsEnabled
        switchAlertService.setOnCheckedChangeRetainingFocus(scrollView) { checked ->
            settings.flexAlertsEnabled = checked
            if (!checked) AlertManager.stopGlobal()
            refreshAccessStatus()
        }
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        if (::settings.isInitialized) {
            switchAlertService.isChecked = settings.flexAlertsEnabled
            refreshAccessStatus()
        }
    }

    private fun refreshAll() {
        refreshAccessStatus()
        refreshRulesList()
    }

    private fun refreshRulesList() {
        if (!::scrollView.isInitialized) return
        scrollView.runRetainingScrollAndFocus { renderRules() }
    }

    private fun refreshAccessStatus() {
        val configured = MonitorPackages.resolve(requireContext())
        val access = isNotificationAccessEnabled()
        tvAccessStatus.text = styledAccessStatus(settings.flexAlertsEnabled, access, configured.toList())
    }

    private fun addRule() {
        val texts = FlexAlertRule.parseMatchInput(etMatch.text?.toString().orEmpty())
        if (texts.isEmpty()) {
            Toast.makeText(context, "Escribe al menos un texto a detectar", Toast.LENGTH_SHORT).show()
            etMatch.requestFocus()
            return
        }
        val rules = FlexAlertRulesStore.load(settings).toMutableList()
        rules.add(
            FlexAlertRule(
                name = etName.text?.toString()?.trim().orEmpty(),
                matchTexts = texts,
                matchMode = draftMatchMode,
                soundUri = draftSoundUri,
                repeatCount = draftRepeatCount(),
            )
        )
        FlexAlertRulesStore.save(settings, rules)
        scrollView.runRetainingScrollAndFocus {
            etName.setText("")
            etMatch.setText("")
            etRepeat.setText(FlexAlertRule.DEFAULT_REPEAT_COUNT.toString())
            draftSoundUri = ""
            tvSoundDraft.text = soundName("")
            renderRules()
            etMatch.requestFocus()
        }
        Toast.makeText(context, "✓ Acción agregada", Toast.LENGTH_SHORT).show()
    }

    private fun renderRules() {
        listContainer.removeAllViews()
        val rules = FlexAlertRulesStore.load(settings)
        if (rules.isEmpty()) {
            listContainer.addView(infoBox("No hay acciones configuradas. Agrega una regla con parte del texto de la notificación."))
            return
        }
        rules.forEach { rule ->
            listContainer.addView(ruleCard(rule))
        }
    }

    private fun ruleCard(rule: FlexAlertRule): View =
        card().apply {
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(TextView(context).apply {
                text = rule.displayName()
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            header.addView(SwitchMaterial(context).apply {
                isChecked = rule.enabled
                styleWideSwitch()
                setOnCheckedChangeRetainingFocus(scrollView) { checked ->
                    saveRule(rule.id) { it.copy(enabled = checked) }
                }
            })
            addView(header)
            addView(smallText("Detecta: ${rule.matchSummary()}"))
            addView(smallText("Sonido: ${soundName(rule.soundUri)}"))
            addView(smallText("Repite: ${rule.repeatCount} veces"))
            addView(iconActionBar(
                IconAction(R.drawable.ic_alert_edit, "Editar acción", R.color.accent_teal) {
                    showEditRuleDialog(rule)
                },
                IconAction(R.drawable.ic_btn_music, "Elegir sonido") {
                    launchRingtonePicker(rule.soundUri, ruleId = rule.id)
                },
                IconAction(android.R.drawable.ic_menu_upload, "Audio del dispositivo") {
                    launchAudioPicker { picked ->
                        saveRule(rule.id) { it.copy(soundUri = picked) }
                        refreshRulesList()
                    }
                },
                IconAction(android.R.drawable.ic_menu_preferences, "Sonido del sistema") {
                    saveRule(rule.id) { it.copy(soundUri = "") }
                    refreshRulesList()
                },
                IconAction(R.drawable.ic_btn_play, "Probar sonido", R.color.green_400) {
                    AlertManager(requireContext()).playFlexNotificationAlert(rule.soundUri, rule.repeatCount)
                },
                IconAction(R.drawable.ic_btn_stop, "Detener sonido", R.color.coral_600) {
                    AlertManager.stopGlobal()
                },
                IconAction(R.drawable.ic_alert_minus, "Menos repeticiones") {
                    saveRule(rule.id) { it.copy(repeatCount = (it.repeatCount - 1).coerceIn(1, 20)) }
                    refreshRulesList()
                },
                IconAction(android.R.drawable.ic_input_add, "Más repeticiones", R.color.accent_teal) {
                    saveRule(rule.id) { it.copy(repeatCount = (it.repeatCount + 1).coerceIn(1, 20)) }
                    refreshRulesList()
                },
                IconAction(android.R.drawable.ic_menu_delete, "Eliminar acción", R.color.coral_600) {
                    deleteRule(rule.id)
                },
            ))
        }

    private fun showEditRuleDialog(rule: FlexAlertRule) {
        val etEditName = input("Nombre (opcional)").apply { setText(rule.name) }
        val etEditMatch = input("Textos a detectar").apply {
            setText(MatchTextParser.formatForEdit(rule.effectiveMatchTexts()))
            maxLines = 4
            setSingleLine(false)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        var editMatchMode = rule.matchMode
        val etEditRepeat = input("Veces que suena").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(rule.repeatCount.toString())
        }
        var editSoundUri = rule.soundUri
        val tvEditSound = smallText(soundName(editSoundUri))

        val dialogView = card().apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = 0 }
            addView(label("Nombre"))
            addView(etEditName)
            addView(label("Textos a detectar"))
            addView(etEditMatch)
            val editModeGroup = MaterialButtonToggleGroup(context).apply {
                isSingleSelection = true
                isSelectionRequired = true
                val btnAny = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    id = View.generateViewId()
                    text = "Cualquiera"
                }
                val btnAll = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    id = View.generateViewId()
                    text = "Todas"
                }
                addView(btnAny)
                addView(btnAll)
                check(if (editMatchMode == FlexAlertRule.MATCH_ALL) btnAll.id else btnAny.id)
                addOnButtonCheckedListener { _, checkedId, isChecked ->
                    if (!isChecked) return@addOnButtonCheckedListener
                    editMatchMode = if (checkedId == btnAll.id) FlexAlertRule.MATCH_ALL else FlexAlertRule.MATCH_ANY
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
            }
            addView(editModeGroup)
            addView(label("Sonido"))
            addView(tvEditSound)
            addView(label("Veces que suena"))
            addView(etEditRepeat)
            addView(iconActionBar(
                IconAction(R.drawable.ic_btn_music, "Elegir sonido") {
                    ringtoneFocusRestore = etEditMatch
                    launchRingtonePicker(editSoundUri) { picked ->
                        editSoundUri = picked
                        tvEditSound.text = soundName(picked)
                    }
                },
                IconAction(android.R.drawable.ic_menu_upload, "Audio del dispositivo") {
                    launchAudioPicker { picked ->
                        editSoundUri = picked
                        tvEditSound.text = soundName(picked)
                    }
                },
                IconAction(android.R.drawable.ic_menu_preferences, "Sonido del sistema") {
                    editSoundUri = ""
                    tvEditSound.text = soundName("")
                },
                IconAction(R.drawable.ic_btn_play, "Probar sonido", R.color.green_400) {
                    AlertManager(requireContext()).playFlexNotificationAlert(
                        editSoundUri,
                        etEditRepeat.text?.toString()?.toIntOrNull()?.coerceIn(1, 20)
                            ?: rule.repeatCount,
                    )
                },
            ))
        }

        val dialog = AlertDialog.Builder(requireContext(), R.style.SparkAlertDialogTheme)
            .setTitle("Editar acción")
            .setView(dialogView)
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val texts = FlexAlertRule.parseMatchInput(etEditMatch.text?.toString().orEmpty())
                if (texts.isEmpty()) {
                    Toast.makeText(context, "Escribe al menos un texto a detectar", Toast.LENGTH_SHORT).show()
                    etEditMatch.requestFocus()
                    return@setOnClickListener
                }
                val repeat = etEditRepeat.text?.toString()?.toIntOrNull()?.coerceIn(1, 20)
                    ?: FlexAlertRule.DEFAULT_REPEAT_COUNT
                scrollView.runRetainingScrollAndFocus {
                    saveRule(rule.id) {
                        it.copy(
                            name = etEditName.text?.toString()?.trim().orEmpty(),
                            matchTexts = texts,
                            matchMode = editMatchMode,
                            soundUri = editSoundUri,
                            repeatCount = repeat,
                        )
                    }
                    renderRules()
                }
                dialog.dismiss()
                Toast.makeText(context, "✓ Acción actualizada", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
        etEditMatch.requestFocus()
    }

    private fun saveRule(id: String, change: (FlexAlertRule) -> FlexAlertRule) {
        val rules = FlexAlertRulesStore.load(settings).map { rule ->
            if (rule.id == id) change(rule) else rule
        }
        FlexAlertRulesStore.save(settings, rules)
    }

    private fun deleteRule(id: String) {
        FlexAlertRulesStore.save(settings, FlexAlertRulesStore.load(settings).filterNot { it.id == id })
        refreshRulesList()
    }

    private fun launchRingtonePicker(
        currentUri: String,
        ruleId: String? = null,
        onPicked: ((String) -> Unit)? = null,
    ) {
        pickingRuleId = ruleId
        onSoundPicked = onPicked
        ringtoneScrollY = if (::scrollView.isInitialized) scrollView.scrollY else 0
        ringtoneFocusRestore = if (::scrollView.isInitialized) scrollView.findFocus() else null
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_TYPE,
                RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_RINGTONE,
            )
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Sonido de alerta Flex")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            if (currentUri.isNotBlank()) {
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(currentUri))
            }
        }
        ringtonePicker.launch(intent)
    }

    private fun launchAudioPicker(onPicked: (String) -> Unit) {
        onSoundPicked = onPicked
        audioFilePicker.pickAudioFromDevice()
    }

    private fun soundName(uriStr: String): String =
        SoundUriLabel.label(requireContext(), uriStr)

    private fun isNotificationAccessEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            requireContext().contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        val expected = ComponentName(requireContext(), FlexNotificationListenerService::class.java)
            .flattenToString()
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun draftRepeatCount(): Int =
        etRepeat.text?.toString()?.toIntOrNull()
            ?.coerceIn(1, 20)
            ?: FlexAlertRule.DEFAULT_REPEAT_COUNT

    private fun styledAccessStatus(
        serviceEnabled: Boolean,
        access: Boolean,
        configuredPackages: List<String>,
    ): CharSequence {
        val builder = SpannableStringBuilder("Servicio de alertas: ")
        appendColoredStatus(builder, serviceEnabled)
        builder.append("\nAcceso a notificaciones: ")
        appendColoredStatus(builder, access)
        builder.append("\n")
        if (configuredPackages.isEmpty()) {
            builder.append("Sin app Flex configurado en Config. Las alertas no se disparan hasta configurar la app vigilada.")
        } else {
            builder.append("Vigilando notificaciones de: ")
            val packagesStart = builder.length
            builder.append(configuredPackages.joinToString())
            builder.setSpan(
                StyleSpan(Typeface.BOLD),
                packagesStart,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return builder
    }

    private fun appendColoredStatus(builder: SpannableStringBuilder, enabled: Boolean) {
        val status = if (enabled) "ACTIVADO" else "DESACTIVADO"
        val statusStart = builder.length
        builder.append(status)
        builder.setSpan(
            ForegroundColorSpan(
                ContextCompat.getColor(
                    requireContext(),
                    if (enabled) R.color.green_400 else R.color.red_400,
                )
            ),
            statusStart,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(
            StyleSpan(Typeface.BOLD),
            statusStart,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    private fun card(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        setBackgroundResource(R.drawable.card_bg)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8) }
    }

    private fun sectionTitle(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 11f
        isFocusable = false
        setTypeface(null, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(context, R.color.text_hint))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(12)
            bottomMargin = dp(6)
        }
    }

    private fun label(text: String): TextView = smallText(text).apply {
        setTypeface(null, Typeface.BOLD)
    }

    private fun smallText(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 12f
        isFocusable = false
        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8) }
    }

    private fun infoBox(text: String): TextView = smallText(text).apply {
        setBackgroundResource(R.drawable.info_bg)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun input(hintText: String): TextInputEditText = TextInputEditText(requireContext()).apply {
        hint = hintText
        textSize = 13f
        maxLines = 1
        setSingleLine(true)
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundResource(R.drawable.input_bg)
        setPadding(dp(12), 0, dp(12), 0)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(42),
        ).apply { bottomMargin = dp(10) }
    }

    private fun iconActionButton(
        iconRes: Int,
        contentDescription: String,
        tintRes: Int = R.color.text_primary,
        onClick: () -> Unit,
    ): ImageButton =
        createIconActionView(
            IconAction(iconRes, contentDescription, tintRes, onClick),
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { bottomMargin = dp(8) }
        }

    private fun iconActionBar(vararg actions: IconAction): GridLayout {
        val columnCount = 4
        val rowCount = (actions.size + columnCount - 1) / columnCount
        return GridLayout(requireContext()).apply {
            this.columnCount = columnCount
            this.rowCount = rowCount
            alignmentMode = GridLayout.ALIGN_BOUNDS
            setColumnOrderPreserved(false)
            useDefaultMargins = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            actions.forEachIndexed { index, action ->
                val col = index % columnCount
                val row = index / columnCount
                addView(
                    createIconActionView(action),
                    GridLayout.LayoutParams().apply {
                        width = 0
                        height = dp(48)
                        columnSpec = GridLayout.spec(col, 1f)
                        rowSpec = GridLayout.spec(row)
                        setMargins(dp(3), dp(3), dp(3), dp(3))
                    },
                )
            }
        }
    }

    private fun createIconActionView(action: IconAction): ImageButton =
        ImageButton(requireContext()).apply {
            setImageResource(action.iconRes)
            imageTintList = ContextCompat.getColorStateList(context, action.tintRes)
            this.contentDescription = action.contentDescription
            background = ContextCompat.getDrawable(context, R.drawable.bg_alert_icon_button)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            adjustViewBounds = true
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = true
            isFocusable = false
            isFocusableInTouchMode = false
            setOnClickListener {
                if (::scrollView.isInitialized) {
                    scrollView.runRetainingScrollAndFocus(action.action)
                } else {
                    action.action()
                }
            }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
