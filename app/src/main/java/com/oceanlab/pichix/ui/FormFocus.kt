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
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.oceanlab.pichix.R

/** Conserva posición de scroll al relayout; [anchor] compensa cambios de altura del contenido. */
fun View.runRetainingHostScroll(anchor: View = this, block: () -> Unit) {
    when (val scrollHost = findHostScrollView()) {
        is ScrollView -> scrollHost.runRetainingScrollForSectionToggle(anchor, block)
        is NestedScrollView -> scrollHost.runRetainingScrollForSectionToggle(anchor, block)
        else -> block()
    }
}

/** ScrollView raíz + contenido interno: no robar foco al relayout. */
fun View.setupFormFocus() {
    preventScrollFocusSteal()
    if (this is ViewGroup) {
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (this is ScrollView && childCount > 0) {
            getChildAt(0).preventScrollFocusSteal()
        }
    }
}

/** Cabeceras de hint/sección: clicables pero sin robar foco del campo activo. */
fun View.preventCollapsibleHeaderFocusSteal() {
    isFocusable = false
    isFocusableInTouchMode = false
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

/** Mejor ancla para conservar scroll/foco: scroll del campo o [fallback]. */
fun View.formFocusHost(fallback: View = this): View =
    findHostScrollView() ?: fallback.findHostScrollView() ?: fallback.focusRoot()

/** Marca un toggle sin disparar IllegalStateException si el grupo aún no está listo. */
fun MaterialButtonToggleGroup.safeCheck(buttonId: Int) {
    if (findViewById<View>(buttonId) == null) return
    try {
        check(buttonId)
    } catch (_: IllegalStateException) {
        post {
            try {
                check(buttonId)
            } catch (_: IllegalStateException) {
            }
        }
    }
}

/** Vista raíz del árbol (activity / fragment) para restaurar foco tras cambios de UI. */
fun View.focusRoot(): View {
    var current: View = this
    var parent = current.parent as? View
    while (parent != null) {
        current = parent
        parent = current.parent as? View
    }
    return current
}

/** Ejecuta [block] y devuelve el foco al control que lo tenía si sigue usable. */
fun View.runRetainingFocus(block: () -> Unit) {
    val focused = findFocus()
    block()
    restoreFocus(focused)
}

fun View.restoreFocus(target: View?) {
    target?.post {
        if (target.isShown && target.isEnabled &&
            (target.isFocusable || target.isFocusableInTouchMode) &&
            target.requestFocus()
        ) {
            return@post
        }
        restoreFocusNearby(target)
    }
}

private fun restoreFocusNearby(from: View?) {
    var origin: View? = from
    repeat(12) {
        val node = origin ?: return
        for (direction in intArrayOf(View.FOCUS_DOWN, View.FOCUS_UP, View.FOCUS_FORWARD)) {
            val candidate = node.focusSearch(direction)
            if (candidate != null && candidate !== node &&
                candidate.isShown && candidate.isEnabled &&
                (candidate.isFocusable || candidate.isFocusableInTouchMode) &&
                candidate.requestFocus()
            ) {
                return
            }
        }
        origin = node.parent as? View
    }
}

/** Offset vertical de [view] respecto al contenido directo del scroll. */
fun contentOffsetTop(view: View, scrollContent: View): Int {
    var top = 0
    var current: View? = view
    while (current != null && current !== scrollContent) {
        top += current.top
        current = current.parent as? View
    }
    return top
}

private fun View.shouldRetainFormFocus(): Boolean = when (this) {
    is TextInputEditText,
    is SwitchMaterial,
    is MaterialButton,
    is Spinner,
    is MaterialButtonToggleGroup -> true
    is TextView -> false
    else -> isFocusable || isFocusableInTouchMode
}

private fun ViewGroup.scrollContentChild(): View? = getChildAt(0)

private fun ViewGroup.computeRetainedScrollY(
    scrollYBefore: Int,
    anchor: View?,
    anchorTopBefore: Int?,
): Int {
    val scrollContent = scrollContentChild() ?: return scrollYBefore
    val maxScroll = (scrollContent.height - height).coerceAtLeast(0)
    if (anchor != null && anchor.isShown && anchorTopBefore != null) {
        val anchorTopAfter = contentOffsetTop(anchor, scrollContent)
        return (scrollYBefore + (anchorTopAfter - anchorTopBefore)).coerceIn(0, maxScroll)
    }
    return scrollYBefore.coerceIn(0, maxScroll)
}

private fun ViewGroup.runRetainingScrollAndFocusInternal(block: () -> Unit) {
    val scrollContent = scrollContentChild()
    val scrollYBefore = scrollY
    val focused = findFocus()?.takeIf {
        it.shouldRetainFormFocus() && it.isDescendantOf(this)
    }
    val anchorTopBefore = focused?.let { f ->
        scrollContent?.let { contentOffsetTop(f, it) }
    }
    block()
    post {
        val anchor = focused?.takeIf { it.isShown && it.isEnabled } ?: focused
        val targetScrollY = computeRetainedScrollY(scrollYBefore, anchor, anchorTopBefore)
        scrollTo(0, targetScrollY)
        restoreFocus(focused)
        post {
            scrollTo(0, computeRetainedScrollY(scrollYBefore, anchor, anchorTopBefore))
        }
    }
}

/** Tras relayout en un ScrollView: conserva scroll y solo restaura foco en campos/switches estables. */
fun ScrollView.runRetainingScrollAndFocus(block: () -> Unit) =
    runRetainingScrollAndFocusInternal(block)

/** Igual que [ScrollView.runRetainingScrollAndFocus] para el editor de reglas (bottom sheet). */
fun NestedScrollView.runRetainingScrollAndFocus(block: () -> Unit) =
    runRetainingScrollAndFocusInternal(block)

/** Conserva scroll al colapsar/expandir una sección (ancla el encabezado, sin robar foco). */
fun ScrollView.runRetainingScrollForSectionToggle(anchor: View, block: () -> Unit) {
    val scrollContent = getChildAt(0) ?: run { block(); return }
    val scrollYBefore = scrollY
    val anchorTopBefore = contentOffsetTop(anchor, scrollContent)
    block()
    post {
        scrollTo(0, computeRetainedScrollY(scrollYBefore, anchor, anchorTopBefore))
    }
}

fun NestedScrollView.runRetainingScrollForSectionToggle(anchor: View, block: () -> Unit) {
    val scrollContent = getChildAt(0) ?: run { block(); return }
    val scrollYBefore = scrollY
    val anchorTopBefore = contentOffsetTop(anchor, scrollContent)
    block()
    post {
        scrollTo(0, computeRetainedScrollY(scrollYBefore, anchor, anchorTopBefore))
    }
}

inline fun SwitchMaterial.setOnCheckedChangeRetainingFocus(
    focusRoot: View,
    crossinline listener: (Boolean) -> Unit,
) {
    setOnCheckedChangeListener { _, checked ->
        val host = formFocusHost(focusRoot)
        val run: () -> Unit = { listener(checked) }
        when (host) {
            is ScrollView -> host.runRetainingScrollAndFocus(run)
            is NestedScrollView -> host.runRetainingScrollAndFocus(run)
            else -> host.runRetainingFocus(run)
        }
    }
}

/** Toggle Material: conserva scroll y foco al cambiar modo (Contiene/Exacto, Basic/Smart). */
inline fun MaterialButtonToggleGroup.addOnButtonCheckedRetainingFocus(
    focusRoot: View,
    crossinline listener: () -> Unit,
) {
    addOnButtonCheckedListener { _, _, isChecked ->
        if (!isChecked) return@addOnButtonCheckedListener
        val host = formFocusHost(focusRoot)
        val run = { listener() }
        when (host) {
            is ScrollView -> host.runRetainingScrollAndFocus(run)
            is NestedScrollView -> host.runRetainingScrollAndFocus(run)
            else -> host.runRetainingFocus(run)
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
            formFocusHost(focusRoot).runRetainingFocus { listener(pos) }
        }
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }
}

/** Evita que un ScrollView robe el foco de los campos de texto al relayout. */
fun View.preventScrollFocusSteal() {
    isFocusable = false
    isFocusableInTouchMode = false
}

/** True si [ancestor] contiene esta vista (recorriendo padres hasta la raíz). */
fun View.isDescendantOf(ancestor: View): Boolean {
    var p = parent as? View
    while (p != null) {
        if (p === ancestor) return true
        p = p.parent as? View
    }
    return false
}

/** Botones rectangulares 2×2 de Historial: misma altura y alineación. */
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

/** El ChipGroup no debe quedarse con el foco al añadir chips (solo los hijos editables). */
fun ChipGroup.preventChipGroupFocusSteal() {
    isFocusable = false
    isFocusableInTouchMode = false
    descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
}

fun TextInputEditText.onUserTextChanged(
    onDirty: () -> Unit,
    onChange: (() -> Unit)? = null
) {
    addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            val host = formFocusHost()
            val action: () -> Unit = {
                onDirty()
                onChange?.invoke()
            }
            when (host) {
                is ScrollView -> host.runRetainingScrollAndFocus(action)
                is NestedScrollView -> host.runRetainingScrollAndFocus(action)
                else -> host.runRetainingFocus(action)
            }
        }
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    })
}

private val MIN_MAX_HIGHLIGHT = Regex("mín|máx", RegexOption.IGNORE_CASE)
private val MAX_MILES_VALUE = Regex("""(\d+\.\d+)\s*mi""")

/** Hint de millas máx.: resalta mín/máx y pone en negrita las millas calculadas. */
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
    onItemSelectedListener = null
    setSelection(position, false)
    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
            formFocusHost().runRetainingFocus { onSelected(pos) }
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}
