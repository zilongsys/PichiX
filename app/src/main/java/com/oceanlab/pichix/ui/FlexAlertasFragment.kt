package com.oceanlab.pichix.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.FlexAlertRule
import com.oceanlab.pichix.data.FlexAlertRulesStore
import com.oceanlab.pichix.data.MonitorPackages
import com.oceanlab.pichix.util.AlertManager
import com.oceanlab.pichix.util.MatchTextParser
import com.oceanlab.pichix.util.PermissionStatusHelper
import com.oceanlab.pichix.util.SoundPickerHelper
import com.oceanlab.pichix.util.SoundUriLabel

class FlexAlertasFragment : Fragment() {

    private lateinit var settings: AppSettings
    private lateinit var audioFilePicker: SoundPickerHelper
    private lateinit var scrollView: ScrollView
    private lateinit var listContainer: LinearLayout
    private lateinit var tvAccessStatus: TextView
    private lateinit var switchAlertService: SwitchMaterial
    private lateinit var seekAlertVolume: SeekBar
    private lateinit var tvAlertVolumePct: TextView
    private lateinit var swForceVolume: SwitchMaterial
    private lateinit var swVibrate: SwitchMaterial
    private lateinit var tvSoundDraft: TextView
    private lateinit var etName: TextInputEditText
    private lateinit var etMatch: TextInputEditText
    private lateinit var etRepeat: TextInputEditText
    private lateinit var draftSourceTypeToggle: MaterialButtonToggleGroup
    private lateinit var tvDraftSourceHint: TextView
    private lateinit var draftCallOnMatchRow: LinearLayout
    private lateinit var swDraftCallOnMatch: SwitchMaterial

    private var draftSoundUri: String = ""
    private var draftMatchMode: String = FlexAlertRule.MATCH_ANY
    private var draftTextMatchMode: String = AppSettings.TEXT_MATCH_CONTAINS
    private var draftIgnoreCase: Boolean = true
    private var draftCallOnMatch: Boolean = false
    private var draftTextMatchToggle: MaterialButtonToggleGroup? = null
    private var swDraftIgnoreCase: SwitchMaterial? = null
    private var matchModeToggle: MaterialButtonToggleGroup? = null
    private var pickingRuleId: String? = null
    private var onSoundPicked: ((String) -> Unit)? = null
    private var ringtoneFocusRestore: View? = null
    private var ringtoneScrollY: Int = 0
    private val expandedRuleCardIds = mutableSetOf<String>()

    private companion object {
        const val SOURCE_TOGGLE_WIRED = "alert_source_toggle_wired"
    }

    private data class IconAction(
        val iconRes: Int,
        val contentDescription: String,
        val tintRes: Int = R.color.text_primary,
        val action: () -> Unit,
    )

    private val ringtonePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(requireContext())
        audioFilePicker = SoundPickerHelper(this) { uri ->
            val consumer = onSoundPicked
            onSoundPicked = null
            consumer?.invoke(uri)
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

        val headerGeneral = collapsibleSectionHeader(getString(R.string.alert_section_general))
        root.addView(headerGeneral)
        val sectionGeneral = sectionContent()
        root.addView(card().apply { addView(sectionGeneral) })
        buildGeneralSection(sectionGeneral)

        val headerNewRule = collapsibleSectionHeader(getString(R.string.alert_section_new_rule))
        root.addView(headerNewRule)
        val sectionNewRule = sectionContent()
        root.addView(card().apply { addView(sectionNewRule) })
        buildNewRuleSection(sectionNewRule)

        val headerRules = collapsibleSectionHeader(getString(R.string.alert_section_rules))
        root.addView(headerRules)
        val sectionRules = sectionContent()
        listContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        sectionRules.addView(listContainer)
        root.addView(sectionRules)

        ConfigSectionBinder.bind(headerGeneral, sectionGeneral.parent as View, scrollView, "alert_general", true)
        ConfigSectionBinder.bind(headerNewRule, sectionNewRule.parent as View, scrollView, "alert_new_rule", false)
        ConfigSectionBinder.bind(headerRules, sectionRules, scrollView, "alert_rules", true)

        return scrollView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        scrollView.setupFormFocus()
        switchAlertService.isChecked = settings.flexAlertsEnabled
        switchAlertService.setOnCheckedChangeRetainingFocus(scrollView) { checked ->
            settings.flexAlertsEnabled = checked
            if (!checked) AlertManager.stopGlobal()
            refreshAccessStatus()
        }
        bindVolumeControls()
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        if (!::settings.isInitialized) return
        switchAlertService.isChecked = settings.flexAlertsEnabled
        seekAlertVolume.progress = settings.alertVolume
        tvAlertVolumePct.text = "${settings.alertVolume}%"
        swForceVolume.isChecked = settings.alertForceVolumeEnabled
        swVibrate.isChecked = settings.vibrateOnAlert
        updateCallOnMatchVisibility()
        refreshAccessStatus()
    }

    private fun buildGeneralSection(section: LinearLayout) {
        val serviceRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        serviceRow.addView(rowLabel("Servicio de alertas").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        switchAlertService = SwitchMaterial(requireContext()).apply { styleWideSwitch(primary = true) }
        serviceRow.addView(switchAlertService)
        section.addView(serviceRow)
        section.addView(smallText(getString(R.string.alert_service_hint)))

        section.addView(rowLabel(getString(R.string.alert_volume_label)))
        section.addView(smallText(getString(R.string.alert_volume_hint)))
        val volumeRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        seekAlertVolume = SeekBar(requireContext()).apply {
            max = 100
            progress = settings.alertVolume
            styleSeekBar(this)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvAlertVolumePct = TextView(requireContext()).apply {
            text = "${settings.alertVolume}%"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(10) }
        }
        volumeRow.addView(seekAlertVolume)
        volumeRow.addView(tvAlertVolumePct)
        section.addView(volumeRow)

        swForceVolume = labeledSwitchRow(
            section,
            getString(R.string.alert_force_volume_label),
            getString(R.string.alert_force_volume_hint),
            settings.alertForceVolumeEnabled,
        ) { checked -> settings.alertForceVolumeEnabled = checked }

        swVibrate = labeledSwitchRow(
            section,
            getString(R.string.alert_vibrate_label),
            hint = null,
            settings.vibrateOnAlert,
        ) { checked -> settings.vibrateOnAlert = checked }

        tvAccessStatus = infoBox("")
        section.addView(tvAccessStatus)

        val permRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        permRow.addView(
            iconActionButton(
                iconRes = R.drawable.ic_alert_notifications,
                contentDescription = getString(R.string.alert_access_notification),
                tintRes = R.color.accent_teal,
            ) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    marginEnd = dp(4)
                }
            },
        )
        permRow.addView(
            iconActionButton(
                iconRes = android.R.drawable.ic_menu_preferences,
                contentDescription = getString(R.string.alert_open_accessibility),
                tintRes = R.color.accent_teal,
            ) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    marginStart = dp(4)
                }
            },
        )
        section.addView(permRow)
    }

    private fun buildNewRuleSection(section: LinearLayout) {
        section.addView(rowLabel("Nombre"))
        etName = styledInput("ej: Oferta reservada")
        section.addView(etName)

        section.addView(subSectionLabel(getString(R.string.alert_rule_source_label)))
        section.addView(smallText(getString(R.string.alert_rule_source_hint)))
        draftSourceTypeToggle = createSourceTypeToggleGroup()
        section.addView(draftSourceTypeToggle)
        tvDraftSourceHint = smallText(sourceTypeHintFor(FlexAlertRule.SOURCE_BOTH))
        section.addView(tvDraftSourceHint)
        wireSourceTypeToggle(draftSourceTypeToggle, FlexAlertRule.SOURCE_BOTH) { source ->
            tvDraftSourceHint.text = sourceTypeHintFor(source)
        }

        section.addView(cardDivider())
        section.addView(subSectionLabel(getString(R.string.alert_match_section_label)))
        section.addView(rowLabel(getString(R.string.alert_rule_texts_label)))
        etMatch = styledInput("Uno por línea o separados por coma").apply {
            maxLines = 4
            setSingleLine(false)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        section.addView(etMatch)
        section.addView(smallText("Una alerta puede vigilar varios textos. Elige si basta con uno (cualquiera) o deben aparecer todos."))

        matchModeToggle = MaterialButtonToggleGroup(requireContext()).apply {
            isSingleSelection = true
            isSelectionRequired = true
            addView(SegmentedToggleStyle.createSegmentButton(context, "Cualquiera").apply {
                tag = FlexAlertRule.MATCH_ANY
            })
            addView(SegmentedToggleStyle.createSegmentButton(context, "Todas").apply {
                tag = FlexAlertRule.MATCH_ALL
            })
            check(getChildAt(0).id)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        SegmentedToggleStyle.wireGroup(matchModeToggle!!, scrollView) { checkedId ->
            draftMatchMode = tagForButtonId(matchModeToggle!!, checkedId) ?: FlexAlertRule.MATCH_ANY
        }
        section.addView(matchModeToggle)

        section.addView(smallText("Cómo comparar cada texto con la notificación:"))
        draftTextMatchToggle = MaterialButtonToggleGroup(requireContext()).apply {
            isSingleSelection = true
            isSelectionRequired = true
            val btnContains = SegmentedToggleStyle.createSegmentButton(context, "Contiene").apply {
                id = View.generateViewId()
                tag = AppSettings.TEXT_MATCH_CONTAINS
            }
            val btnExact = SegmentedToggleStyle.createSegmentButton(context, "Exacto").apply {
                id = View.generateViewId()
                tag = AppSettings.TEXT_MATCH_EXACT
            }
            addView(btnContains)
            addView(btnExact)
            check(btnContains.id)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }
        }
        SegmentedToggleStyle.wireGroup(draftTextMatchToggle!!, scrollView) { checkedId ->
            draftTextMatchMode = tagForButtonId(draftTextMatchToggle!!, checkedId)
                ?: AppSettings.TEXT_MATCH_CONTAINS
        }
        section.addView(draftTextMatchToggle)

        val ignoreRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        ignoreRow.addView(TextView(requireContext()).apply {
            text = getString(R.string.config_ignore_case_label)
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        swDraftIgnoreCase = SwitchMaterial(requireContext()).apply {
            isChecked = draftIgnoreCase
            setOnCheckedChangeListener { _, checked -> draftIgnoreCase = checked }
        }
        ignoreRow.addView(swDraftIgnoreCase)
        section.addView(ignoreRow)

        draftCallOnMatchRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        val callRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        callRow.addView(TextView(requireContext()).apply {
            text = getString(R.string.alert_rule_call_on_match)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        swDraftCallOnMatch = SwitchMaterial(requireContext()).apply {
            isChecked = draftCallOnMatch
            setOnCheckedChangeListener { _, checked -> draftCallOnMatch = checked }
        }
        callRow.addView(swDraftCallOnMatch)
        draftCallOnMatchRow.addView(callRow)
        draftCallOnMatchRow.addView(smallText(getString(R.string.alert_rule_call_on_match_hint)))
        section.addView(draftCallOnMatchRow)
        updateCallOnMatchVisibility()

        section.addView(rowLabel("Sonido"))
        tvSoundDraft = smallText(soundName(draftSoundUri))
        section.addView(tvSoundDraft)
        section.addView(rowLabel("Veces que suena"))
        etRepeat = styledInput("2").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(FlexAlertRule.DEFAULT_REPEAT_COUNT.toString())
        }
        section.addView(etRepeat)
        section.addView(iconActionBar(
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
        section.addView(
            iconActionButton(
                iconRes = android.R.drawable.ic_input_add,
                contentDescription = "Agregar regla",
                tintRes = R.color.accent_teal,
            ) { addRule() },
        )
    }

    private fun bindVolumeControls() {
        seekAlertVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvAlertVolumePct.text = "$progress%"
                if (fromUser) settings.alertVolume = progress
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun updateCallOnMatchVisibility() {
        if (!::draftCallOnMatchRow.isInitialized) return
        val visible = settings.callOnBlockEnabled
        draftCallOnMatchRow.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            draftCallOnMatch = false
            if (::swDraftCallOnMatch.isInitialized) swDraftCallOnMatch.isChecked = false
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
        if (!::tvAccessStatus.isInitialized) return
        val configured = MonitorPackages.resolve(requireContext())
        val notificationAccess = PermissionStatusHelper.isNotificationListenerEnabled(requireContext())
        val accessibilityAccess = PermissionStatusHelper.isAccessibilityServiceEnabled(requireContext())
        tvAccessStatus.text = styledAccessStatus(
            settings.flexAlertsEnabled,
            notificationAccess,
            accessibilityAccess,
            configured.toList(),
        )
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
                textMatchMode = draftTextMatchMode,
                ignoreCase = draftIgnoreCase,
                soundUri = draftSoundUri,
                repeatCount = draftRepeatCount(),
                callOnMatch = draftCallOnMatch && settings.callOnBlockEnabled,
                sourceType = readDraftSourceType(draftSourceTypeToggle),
            ),
        )
        FlexAlertRulesStore.save(settings, rules)
        scrollView.runRetainingScrollAndFocus {
            etName.setText("")
            etMatch.setText("")
            etRepeat.setText(FlexAlertRule.DEFAULT_REPEAT_COUNT.toString())
            draftSoundUri = ""
            draftCallOnMatch = false
            swDraftCallOnMatch.isChecked = false
            tvSoundDraft.text = soundName("")
            resetSourceTypeToggle(draftSourceTypeToggle, FlexAlertRule.SOURCE_BOTH)
            tvDraftSourceHint.text = sourceTypeHintFor(FlexAlertRule.SOURCE_BOTH)
            renderRules()
            etMatch.requestFocus()
        }
        Toast.makeText(context, "✓ Regla agregada", Toast.LENGTH_SHORT).show()
    }

    private fun renderRules() {
        listContainer.removeAllViews()
        val rules = FlexAlertRulesStore.load(settings)
        if (rules.isEmpty()) {
            listContainer.addView(infoBox("No hay reglas configuradas. Agrega una regla con parte del texto de la notificación."))
            return
        }
        rules.forEach { rule ->
            listContainer.addView(ruleCard(rule))
        }
    }

    private fun ruleCard(rule: FlexAlertRule): View {
        val expanded = rule.id in expandedRuleCardIds
        return card().apply {
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            val titleArea = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                applyHeaderSelectableForeground(this)
                isClickable = true
                isFocusable = true
            }
            val chevron = ImageView(context).apply {
                setImageResource(if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down)
                imageTintList = ContextCompat.getColorStateList(context, R.color.text_hint)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(6) }
            }
            titleArea.addView(chevron)
            titleArea.addView(TextView(context).apply {
                text = rule.displayName()
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            })
            val toggleExpand = {
                scrollView.runRetainingScrollAndFocus {
                    if (rule.id in expandedRuleCardIds) expandedRuleCardIds.remove(rule.id)
                    else expandedRuleCardIds.add(rule.id)
                    renderRules()
                }
            }
            titleArea.setOnClickListener { toggleExpand() }
            header.addView(titleArea)
            header.addView(SwitchMaterial(context).apply {
                isChecked = rule.enabled
                styleWideSwitch()
                setOnCheckedChangeRetainingFocus(scrollView) { checked ->
                    saveRule(rule.id) { it.copy(enabled = checked) }
                }
            })
            addView(header)

            val body = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (expanded) View.VISIBLE else View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) }
            }
            addView(body)

            body.addView(smallText("Detecta: ${rule.matchSummary()}"))
            body.addView(smallText("Origen: ${rule.sourceTypeLabel()}"))
            body.addView(smallText("Sonido: ${soundName(rule.soundUri)}"))
            body.addView(smallText("Repite: ${rule.repeatCount} veces"))
            if (rule.callOnMatch && settings.callOnBlockEnabled) {
                body.addView(smallText("Llamar al coincidir: activo"))
            }

            body.addView(cardDivider())
            body.addView(subSectionLabel(getString(R.string.alert_rule_source_label)))
            val cardSourceToggle = createSourceTypeToggleGroup()
            body.addView(cardSourceToggle)
            val cardSourceHint = smallText(sourceTypeHintFor(rule.sourceType))
            body.addView(cardSourceHint)
            wireSourceTypeToggle(cardSourceToggle, rule.sourceType) { source ->
                cardSourceHint.text = sourceTypeHintFor(source)
                saveRule(rule.id) { it.copy(sourceType = source) }
            }

            if (settings.callOnBlockEnabled) {
                body.addView(cardDivider())
                val callRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(4) }
                }
                callRow.addView(TextView(context).apply {
                    text = getString(R.string.alert_rule_call_on_match)
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                callRow.addView(SwitchMaterial(context).apply {
                    isChecked = rule.callOnMatch
                    styleWideSwitch()
                    setOnCheckedChangeRetainingFocus(scrollView) { checked ->
                        saveRule(rule.id) { it.copy(callOnMatch = checked) }
                    }
                })
                body.addView(callRow)
                body.addView(smallText(getString(R.string.alert_rule_call_on_match_hint)))
            }

            body.addView(iconActionBar(
                IconAction(R.drawable.ic_alert_edit, "Editar regla", R.color.accent_teal) {
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
                IconAction(android.R.drawable.ic_menu_delete, "Eliminar regla", R.color.coral_600) {
                    deleteRule(rule.id)
                },
            ))
        }
    }

    private fun showEditRuleDialog(rule: FlexAlertRule) {
        val etEditName = styledInput("Nombre (opcional)").apply { setText(rule.name) }
        val etEditMatch = styledInput("Textos a detectar").apply {
            setText(MatchTextParser.formatForEdit(rule.effectiveMatchTexts()))
            maxLines = 4
            setSingleLine(false)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        var editMatchMode = rule.matchMode
        var editTextMatchMode = rule.textMatchMode
        var editIgnoreCase = rule.ignoreCase
        var editSourceType = rule.sourceType
        var editCallOnMatch = rule.callOnMatch
        val etEditRepeat = styledInput("Veces que suena").apply {
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
            addView(rowLabel("Nombre"))
            addView(etEditName)
            addView(subSectionLabel(getString(R.string.alert_rule_source_label)))
            val editSourceToggle = createSourceTypeToggleGroup()
            addView(editSourceToggle)
            val tvEditSourceHint = smallText(sourceTypeHintFor(editSourceType))
            addView(tvEditSourceHint)
            wireSourceTypeToggle(editSourceToggle, editSourceType) { source ->
                editSourceType = source
                tvEditSourceHint.text = sourceTypeHintFor(source)
            }
            addView(cardDivider())
            addView(rowLabel(getString(R.string.alert_rule_texts_label)))
            addView(etEditMatch)
            val editModeGroup = MaterialButtonToggleGroup(context).apply {
                isSingleSelection = true
                isSelectionRequired = true
                val btnAny = SegmentedToggleStyle.createSegmentButton(context, "Cualquiera").apply {
                    id = View.generateViewId()
                    tag = FlexAlertRule.MATCH_ANY
                }
                val btnAll = SegmentedToggleStyle.createSegmentButton(context, "Todas").apply {
                    id = View.generateViewId()
                    tag = FlexAlertRule.MATCH_ALL
                }
                addView(btnAny)
                addView(btnAll)
                check(if (editMatchMode == FlexAlertRule.MATCH_ALL) btnAll.id else btnAny.id)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
            }
            SegmentedToggleStyle.wireGroup(editModeGroup) { checkedId ->
                editMatchMode = tagForButtonId(editModeGroup, checkedId) ?: FlexAlertRule.MATCH_ANY
            }
            addView(editModeGroup)
            val editTextModeGroup = MaterialButtonToggleGroup(context).apply {
                isSingleSelection = true
                isSelectionRequired = true
                val btnContains = SegmentedToggleStyle.createSegmentButton(context, "Contiene").apply {
                    id = View.generateViewId()
                    tag = AppSettings.TEXT_MATCH_CONTAINS
                }
                val btnExact = SegmentedToggleStyle.createSegmentButton(context, "Exacto").apply {
                    id = View.generateViewId()
                    tag = AppSettings.TEXT_MATCH_EXACT
                }
                addView(btnContains)
                addView(btnExact)
                check(if (editTextMatchMode == AppSettings.TEXT_MATCH_EXACT) btnExact.id else btnContains.id)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(6) }
            }
            SegmentedToggleStyle.wireGroup(editTextModeGroup) { checkedId ->
                editTextMatchMode = tagForButtonId(editTextModeGroup, checkedId)
                    ?: AppSettings.TEXT_MATCH_CONTAINS
            }
            addView(editTextModeGroup)
            val editIgnoreRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            editIgnoreRow.addView(TextView(context).apply {
                text = getString(R.string.config_ignore_case_label)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val swEditIgnore = SwitchMaterial(context).apply {
                isChecked = editIgnoreCase
                setOnCheckedChangeListener { _, checked -> editIgnoreCase = checked }
            }
            editIgnoreRow.addView(swEditIgnore)
            addView(editIgnoreRow)

            var editCallRow: LinearLayout? = null
            if (settings.callOnBlockEnabled) {
                editCallRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                editCallRow.addView(TextView(context).apply {
                    text = getString(R.string.alert_rule_call_on_match)
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                editCallRow.addView(SwitchMaterial(context).apply {
                    isChecked = editCallOnMatch
                    setOnCheckedChangeListener { _, checked -> editCallOnMatch = checked }
                })
                addView(editCallRow)
                addView(smallText(getString(R.string.alert_rule_call_on_match_hint)))
            }

            addView(rowLabel("Sonido"))
            addView(tvEditSound)
            addView(rowLabel("Veces que suena"))
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
            .setTitle("Editar regla")
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
                            textMatchMode = editTextMatchMode,
                            ignoreCase = editIgnoreCase,
                            soundUri = editSoundUri,
                            repeatCount = repeat,
                            sourceType = editSourceType,
                            callOnMatch = editCallOnMatch && settings.callOnBlockEnabled,
                        )
                    }
                    renderRules()
                }
                dialog.dismiss()
                Toast.makeText(context, "✓ Regla actualizada", Toast.LENGTH_SHORT).show()
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
        expandedRuleCardIds.remove(id)
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

    private fun draftRepeatCount(): Int =
        etRepeat.text?.toString()?.toIntOrNull()
            ?.coerceIn(1, 20)
            ?: FlexAlertRule.DEFAULT_REPEAT_COUNT

    private fun restoreAfterRingtonePicker() {
        if (!::scrollView.isInitialized) return
        val target = ringtoneFocusRestore
        ringtoneFocusRestore = null
        scrollView.post {
            scrollView.scrollTo(0, ringtoneScrollY)
            scrollView.restoreFocus(target)
        }
    }

    private fun styledAccessStatus(
        serviceEnabled: Boolean,
        notificationAccess: Boolean,
        accessibilityAccess: Boolean,
        configuredPackages: List<String>,
    ): CharSequence {
        val builder = SpannableStringBuilder("Servicio de alertas: ")
        appendColoredStatus(builder, serviceEnabled)
        builder.append("\nAcceso a notificaciones: ")
        appendColoredStatus(builder, notificationAccess)
        builder.append("\nAcceso a accesibilidad: ")
        appendColoredStatus(builder, accessibilityAccess)
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
                ),
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

    private fun createSourceTypeToggleGroup(): MaterialButtonToggleGroup {
        val ctx = requireContext()
        return MaterialButtonToggleGroup(ctx).apply {
            isSingleSelection = true
            isSelectionRequired = true
            addView(SegmentedToggleStyle.createSegmentButton(ctx, getString(R.string.alert_rule_source_notification)).apply {
                id = View.generateViewId()
                tag = FlexAlertRule.SOURCE_NOTIFICATION
            })
            addView(SegmentedToggleStyle.createSegmentButton(ctx, getString(R.string.alert_rule_source_in_app)).apply {
                id = View.generateViewId()
                tag = FlexAlertRule.SOURCE_IN_APP
            })
            addView(SegmentedToggleStyle.createSegmentButton(ctx, getString(R.string.alert_rule_source_both)).apply {
                id = View.generateViewId()
                tag = FlexAlertRule.SOURCE_BOTH
            })
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }
        }
    }

    private fun wireSourceTypeToggle(
        group: MaterialButtonToggleGroup,
        initialSource: String,
        onChanged: ((String) -> Unit)? = null,
    ) {
        resetSourceTypeToggle(group, initialSource)
        if (group.tag == SOURCE_TOGGLE_WIRED) return
        group.tag = SOURCE_TOGGLE_WIRED
        SegmentedToggleStyle.wireGroup(group, scrollView) { checkedId ->
            val source = tagForButtonId(group, checkedId) ?: FlexAlertRule.SOURCE_BOTH
            onChanged?.invoke(source)
        }
    }

    private fun resetSourceTypeToggle(group: MaterialButtonToggleGroup, source: String) {
        val initialId = buttonIdForTag(group, source)
            ?: (group.getChildAt(2) as MaterialButton).id
        group.check(initialId)
        SegmentedToggleStyle.applyGroup(group, initialId)
    }

    private fun readDraftSourceType(group: MaterialButtonToggleGroup): String =
        tagForButtonId(group, group.checkedButtonId) ?: FlexAlertRule.SOURCE_BOTH

    private fun sourceTypeHintFor(source: String): String = when (source) {
        FlexAlertRule.SOURCE_NOTIFICATION -> getString(R.string.alert_rule_source_notification_hint)
        FlexAlertRule.SOURCE_IN_APP -> getString(R.string.alert_rule_source_in_app_hint)
        else -> getString(R.string.alert_rule_source_both_hint)
    }

    private fun buttonIdForTag(group: MaterialButtonToggleGroup, tag: String): Int? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) as? MaterialButton ?: continue
            if (child.tag == tag) return child.id
        }
        return null
    }

    private fun tagForButtonId(group: MaterialButtonToggleGroup, buttonId: Int): String? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) as? MaterialButton ?: continue
            if (child.id == buttonId) return child.tag as? String
        }
        return null
    }

    private fun collapsibleSectionHeader(title: CharSequence): TextView =
        TextView(requireContext()).apply {
            text = title
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.text_hint))
            isAllCaps = true
            letterSpacing = 0.08f
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            setPadding(0, dp(12), 0, dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(16)
                bottomMargin = dp(8)
            }
            preventCollapsibleHeaderFocusSteal()
        }

    private fun sectionContent(): LinearLayout =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

    private fun rowLabel(text: CharSequence): TextView =
        TextView(requireContext()).apply {
            this.text = text
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }
        }

    private fun subSectionLabel(text: CharSequence): TextView =
        TextView(requireContext()).apply {
            this.text = text
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.accent_teal_dark))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
        }

    private fun applyHeaderSelectableForeground(view: View) {
        val tv = TypedValue()
        if (view.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            view.foreground = ContextCompat.getDrawable(view.context, tv.resourceId)
        }
    }

    private fun cardDivider(): View =
        View(requireContext()).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.border_subtle))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1),
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        }

    private fun styleSeekBar(seekBar: SeekBar) {
        val ctx = requireContext()
        val accent = ContextCompat.getColor(ctx, R.color.accent_teal)
        val accentDark = ContextCompat.getColor(ctx, R.color.accent_teal_dark)
        seekBar.progressTintList = ColorStateList.valueOf(accent)
        seekBar.thumbTintList = ColorStateList.valueOf(accentDark)
    }

    private fun labeledSwitchRow(
        parent: LinearLayout,
        label: CharSequence,
        hint: CharSequence?,
        initial: Boolean,
        onChange: (Boolean) -> Unit,
    ): SwitchMaterial {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = if (hint != null) dp(2) else dp(10) }
        }
        row.addView(TextView(requireContext()).apply {
            text = label
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sw = SwitchMaterial(requireContext()).apply {
            isChecked = initial
            styleWideSwitch()
            setOnCheckedChangeRetainingFocus(scrollView, onChange)
        }
        row.addView(sw)
        parent.addView(row)
        if (hint != null) parent.addView(smallText(hint))
        return sw
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

    private fun smallText(text: CharSequence): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 12f
        isFocusable = false
        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8) }
    }

    private fun infoBox(text: CharSequence): TextView = smallText(text).apply {
        setBackgroundResource(R.drawable.info_bg)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun styledInput(hintText: String): TextInputEditText =
        TextInputEditText(requireContext()).apply {
            hint = hintText
            textSize = 13f
            maxLines = 1
            setSingleLine(true)
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundResource(R.drawable.input_bg)
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_hint)))
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
