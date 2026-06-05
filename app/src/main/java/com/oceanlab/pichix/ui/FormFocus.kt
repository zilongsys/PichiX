package com.oceanlab.pichix.ui

import android.graphics.Typeface
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.oceanlab.pichix.R

/** ScrollView raÃ­z + contenido interno: no robar foco al relayout. */
fun View.setupFormFocus() {
    preventScrollFocusSteal()
    if (this is ViewGroup && this is android.widget.ScrollView && childCount > 0) {
        getChildAt(0).preventScrollFocusSteal()
    }
}

/** ScrollView o NestedScrollView ancestro (formularios Config/Tarifas). */
fun View.findHostScrollView(): View? {
    var current: View? = this
    while (current != null) {
        if (current is ScrollView || current is NestedScrollView) return current
        current = current.parent as? View
    }
    return null
}

/** Vista raÃ­z del Ã¡rbol (activity / fragment) para restaurar foco tras cambios de UI. */
fun View.focusRoot(): View {
    var current: View = this
    var parent = current.parent as? View
    while (parent != null) {
        current = parent
        parent = current.parent as? View
    }
    return current
}

/** Ejecuta [block] y devuelve el foco al control que lo tenÃ­a si sigue usable. */
fun View.runRetainingFocus(block: () -> Unit) {
    val focused = findFocus()
    block()
    restoreFocus(focused)
}

fun View.restoreFocus(target: View?) {
    target?.post {
        if (target.isShown && target.isEnabled &&
            (target.isFocusable || target.isFocusableInTouchMode)
        ) {
            target.requestFocus()
        }
    }
}

private fun ViewGroup.runRetainingScrollAndFocusInternal(block: () -> Unit) {
    val scrollY = scrollY
    val focused = findFocus()
    val anchor = focused?.takeIf {
        it.isDescendantOf(this) &&
            (it.isFocusable || it.isFocusableInTouchMode)
    }
    val anchorLoc = IntArray(2)
    val hostLoc = IntArray(2)
    var anchorOffsetInViewport = 0
    if (anchor != null) {
        anchor.getLocationOnScreen(anchorLoc)
        getLocationOnScreen(hostLoc)
        anchorOffsetInViewport = anchorLoc[1] - hostLoc[1]
    }
    val shouldRestoreFocus = anchor != null &&
        anchor.isShown &&
        (anchor.isFocusable || anchor.isFocusableInTouchMode)
    block()
    post {
        val child = getChildAt(0)
        val maxScroll = ((child?.height ?: 0) - height).coerceAtLeast(0)
        val newScrollY = if (anchor != null && anchor.isShown) {
            anchor.getLocationOnScreen(anchorLoc)
            getLocationOnScreen(hostLoc)
            val delta = (anchorLoc[1] - hostLoc[1]) - anchorOffsetInViewport
            (scrollY + delta).coerceIn(0, maxScroll)
        } else {
            scrollY.coerceIn(0, maxScroll)
        }
        scrollTo(0, newScrollY)
        if (shouldRestoreFocus) {
            anchor!!.requestFocus()
        }
    }
}

/** Tras relayout en un ScrollView: conserva scroll y solo restaura foco en campos/switches estables. */
fun ScrollView.runRetainingScrollAndFocus(block: () -> Unit) =
    runRetainingScrollAndFocusInternal(block)

/** Igual que [ScrollView.runRetainingScrollAndFocus] para el editor de reglas (bottom sheet). */
fun NestedScrollView.runRetainingScrollAndFocus(block: () -> Unit) =
    runRetainingScrollAndFocusInternal(block)

inline fun SwitchMaterial.setOnCheckedChangeRetainingFocus(
    focusRoot: View,
    crossinline listener: (Boolean) -> Unit,
) {
    val scrollHost = focusRoot.findHostScrollView()
    setOnCheckedChangeListener { switch, checked ->
        val run: () -> Unit = { listener(checked) }
        when (scrollHost) {
            is ScrollView -> scrollHost.runRetainingScrollAndFocus(run)
            is NestedScrollView -> scrollHost.runRetainingScrollAndFocus(run)
            else -> focusRoot.runRetainingFocus(run)
        }
        switch.post {
            if (switch.isShown && switch.isEnabled) {
                switch.requestFocus()
            }
        }
    }
}

/** Toggle Material: conserva scroll y foco al cambiar modo (Contiene/Exacto, Basic/Smart). */
inline fun com.google.android.material.button.MaterialButtonToggleGroup
    .addOnButtonCheckedRetainingFocus(
    focusRoot: View,
    crossinline listener: () -> Unit,
) {
    val scrollHost = focusRoot.findHostScrollView()
    addOnButtonCheckedListener { _, _, isChecked ->
        if (!isChecked) return@addOnButtonCheckedListener
        val run = { listener() }
        when (scrollHost) {
            is ScrollView -> scrollHost.runRetainingScrollAndFocus(run)
            is NestedScrollView -> scrollHost.runRetainingScrollAndFocus(run)
            else -> focusRoot.runRetainingFocus(run)
        }
    }
}

fun SwitchMaterial.styleWideSwitch(primary: Boolean = false) {
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    minWidth = dp(if (primary) 84 else 72)
    minimumWidth = dp(if (primary) 84 else 72)
    scaleX = if (primary) 1.18f else 1.10f
    scaleY = if (primary) 1.08f else 1.04f
    layoutParams = (layoutParams ?: ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )).apply {
        width = dp(if (primary) 92 else 78)
        height = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

inline fun Spinner.setOnItemSelectedRetainingFocus(
    focusRoot: View,
    crossinline listener: (Int) -> Unit,
) {
    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
            focusRoot.runRetainingFocus { listener(pos) }
        }
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }
}

/** Evita que un ScrollView robe el foco de los campos de texto al relayout. */
fun View.preventScrollFocusSteal() {
    isFocusable = false
    isFocusableInTouchMode = false
}

/** True si [ancestor] contiene esta vista (recorriendo padres hasta la raÃ­z). */
fun View.isDescendantOf(ancestor: View): Boolean {
    var p = parent as? View
    while (p != null) {
        if (p === ancestor) return true
        p = p.parent as? View
    }
    return false
}

/** Botones rectangulares 2Ã—2 de Historial: misma altura y alineaciÃ³n. */
fun MaterialButton.centerHistorialActionContent() {
    gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.CENTER_HORIZONTAL
    textAlignment = View.TEXT_ALIGNMENT_CENTER
    iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
    includeFontPadding = false
    val d = context.resources.displayMetrics.density
    iconPadding = (6 * d).toInt()
    val h = (10 * d).toInt()
    val v = (6 * d).toInt()
    setPadding(h, v, h, v)
}

/** El ChipGroup no debe quedarse con el foco al aÃ±adir chips (solo los hijos editables). */
fun ChipGroup.preventChipGroupFocusSteal() {
    isFocusable = false
    isFocusableInTouchMode = false
    descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
}

fun TextInputEditText.onUserTextChanged(
    onDirty: () -> Unit,
    onChange: (() -> Unit)? = null
) {
    val scrollHost = findHostScrollView()
    val root = focusRoot()
    addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            val action: () -> Unit = {
                onDirty()
                onChange?.invoke()
            }
            when (scrollHost) {
                is ScrollView -> scrollHost.runRetainingScrollAndFocus(action)
                is NestedScrollView -> scrollHost.runRetainingScrollAndFocus(action)
                else -> root.runRetainingFocus(action)
            }
        }
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    })
}

private val MIN_MAX_HIGHLIGHT = Regex("mÃ­n|mÃ¡x", RegexOption.IGNORE_CASE)
private val MAX_MILES_VALUE = Regex("""(\d+\.\d+)\s*mi""")

/** Hint de millas mÃ¡x.: resalta mÃ­n/mÃ¡x y pone en negrita las millas calculadas. */
fun TextView.setTarifaMaxHint(text: String) {
    val spannable = SpannableString(text)
    val accent = ContextCompat.getColor(context, R.color.accent_teal)
    MIN_MAX_HIGHLIGHT.findAll(text).forEach { match ->
        val start = match.range.first
        val end = match.range.last + 1
        spannable.setSpan(ForegroundColorSpan(accent), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    MAX_MILES_VALUE.findAll(text).forEach { match ->
        val num = match.groupValues[1]
        val start = text.indexOf(num, match.range.first)
        if (start >= 0) {
            val end = start + num.length
            spannable.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    this.text = spannable
}

inline fun Spinner.setSelectionSilently(position: Int, crossinline onSelected: (Int) -> Unit) {
    val focusRoot = focusRoot()
    onItemSelectedListener = null
    setSelection(position, false)
    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
            focusRoot.runRetainingFocus { onSelected(pos) }
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}

