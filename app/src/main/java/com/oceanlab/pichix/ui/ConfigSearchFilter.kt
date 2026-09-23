package com.oceanlab.pichix.ui

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputLayout
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.AppSettings
import java.text.Normalizer

/**
 * Filtra opciones de Config una a una según el texto de búsqueda.
 * Con query vacío restaura la visibilidad previa (hints colapsados, layouts de modo, etc.).
 */
object ConfigSearchFilter {

    private val SAVED_VIS = R.id.tag_config_search_saved_visibility

    data class Section(
        val key: String,
        val header: TextView,
        val content: View,
        val startExpanded: Boolean = true,
    )

    fun apply(queryRaw: String, sections: List<Section>, emptyLabel: TextView?) {
        val query = normalize(queryRaw)
        var anyVisible = false

        for (section in sections) {
            val settings = AppSettings(section.header.context)
            if (query.isEmpty()) {
                restoreSection(section, settings)
                anyVisible = true
                continue
            }

            val hits = filterSectionContent(section.content, query)
            if (hits > 0) {
                anyVisible = true
                setVisibleSaved(section.header, View.VISIBLE)
                ConfigSectionBinder.showExpandedVisual(
                    section.header,
                    section.content,
                    expanded = true,
                )
            } else {
                setVisibleSaved(section.header, View.GONE)
                setVisibleSaved(section.content, View.GONE)
            }
        }

        emptyLabel?.visibility = if (!anyVisible && query.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun filterSectionContent(content: View, query: String): Int {
        if (content !is ViewGroup) {
            val hit = matches(content, query)
            setVisibleSaved(content, if (hit) View.VISIBLE else View.GONE)
            return if (hit) 1 else 0
        }

        var totalHits = 0
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            totalHits += when {
                isCardLike(child) && child is ViewGroup -> filterCard(child, query)
                else -> {
                    val hit = matches(child, query)
                    setVisibleSaved(child, if (hit) View.VISIBLE else View.GONE)
                    if (hit) 1 else 0
                }
            }
        }
        setVisibleSaved(content, if (totalHits > 0) View.VISIBLE else View.GONE)
        return totalHits
    }

    private fun filterCard(card: ViewGroup, query: String): Int {
        val clusters = buildClusters(card)
        var hits = 0
        for (cluster in clusters) {
            val hit = cluster.any { matches(it, query) }
            for (v in cluster) {
                when {
                    isDivider(v) -> setVisibleSaved(v, View.GONE)
                    !hit -> setVisibleSaved(v, View.GONE)
                    isHintChrome(v) -> {
                        // No forzar hints colapsados a visibles; solo recordar estado.
                        if (v.getTag(SAVED_VIS) == null) {
                            v.setTag(SAVED_VIS, v.visibility)
                        }
                    }
                    else -> setVisibleSaved(v, View.VISIBLE)
                }
            }
            if (hit) hits++
        }
        setVisibleSaved(card, if (hits > 0) View.VISIBLE else View.GONE)
        return hits
    }

    private fun buildClusters(card: ViewGroup): List<List<View>> {
        val clusters = mutableListOf<MutableList<View>>()
        var current: MutableList<View>? = null

        for (i in 0 until card.childCount) {
            val child = card.getChildAt(i)
            if (isDivider(child)) {
                current?.add(child)
                continue
            }
            val cur = current
            if (cur == null) {
                current = mutableListOf(child).also { clusters.add(it) }
            } else if (!isClusterStart(child) || shouldAttachToCurrent(cur, child)) {
                cur.add(child)
            } else {
                current = mutableListOf(child).also { clusters.add(it) }
            }
        }
        return clusters
    }

    /** Etiquetas sueltas se unen al control siguiente (toggle, input, fila…). */
    private fun shouldAttachToCurrent(current: List<View>, next: View): Boolean {
        if (isHintChrome(next)) return true
        val onlyLabels = current.all { isDivider(it) || it is TextView }
        if (!onlyLabels) return false
        return next is MaterialButtonToggleGroup ||
            next is TextInputLayout ||
            next is MaterialButton ||
            next is RecyclerView ||
            next is LinearLayout
    }

    private fun isClusterStart(v: View): Boolean {
        if (isHintChrome(v)) return false
        return when (v) {
            is MaterialButtonToggleGroup,
            is MaterialButton,
            is TextInputLayout,
            is RecyclerView,
            -> true
            is TextView -> true
            is LinearLayout -> true
            is ViewGroup -> true
            else -> true
        }
    }

    private fun isHintChrome(v: View): Boolean {
        val name = resourceEntryName(v)
        if (name.contains("hint", ignoreCase = true)) return true
        if (v is TextView && isLikelyHintText(v)) return true
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val c = v.getChildAt(i)
                if (c is TextView && resourceEntryName(c).contains("HintToggle", ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
    }

    private fun isLikelyHintText(tv: TextView): Boolean {
        val name = resourceEntryName(tv)
        if (name.contains("hint", ignoreCase = true)) return true
        // Subsección (11sp, all caps) no es hint.
        if (tv.isAllCaps) return false
        val sp = tv.textSize / tv.resources.displayMetrics.scaledDensity
        return sp <= 12.5f
    }

    private fun isDivider(v: View): Boolean =
        v !is ViewGroup && v !is TextView && v !is MaterialButton &&
            v.javaClass == View::class.java

    private fun isCardLike(v: View): Boolean =
        v is LinearLayout && v.orientation == LinearLayout.VERTICAL

    private fun matches(root: View, query: String): Boolean =
        collectSearchableText(root).contains(query)

    private fun collectSearchableText(root: View): String {
        val sb = StringBuilder()
        fun walk(v: View) {
            val name = resourceEntryName(v)
            if (name.contains("HintToggle", ignoreCase = true)) return

            when (v) {
                is TextInputLayout -> {
                    v.hint?.let { sb.append(' ').append(it) }
                    v.helperText?.let { sb.append(' ').append(it) }
                    v.placeholderText?.let { sb.append(' ').append(it) }
                }
                is MaterialButton -> {
                    if (!v.text.isNullOrBlank()) sb.append(' ').append(v.text)
                    v.contentDescription?.let { sb.append(' ').append(it) }
                }
                is TextView -> {
                    if (!v.text.isNullOrBlank()) sb.append(' ').append(v.text)
                    v.contentDescription?.let { sb.append(' ').append(it) }
                    v.hint?.let { sb.append(' ').append(it) }
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return normalize(sb.toString())
    }

    private fun setVisibleSaved(v: View, visibility: Int) {
        if (v.getTag(SAVED_VIS) == null) {
            v.setTag(SAVED_VIS, v.visibility)
        }
        v.visibility = visibility
    }

    private fun restoreSection(section: Section, settings: AppSettings) {
        restoreSavedVisibility(section.header)
        restoreSavedVisibilityDeep(section.content)
        val open = settings.isConfigSectionExpanded(section.key, section.startExpanded)
        ConfigSectionBinder.setExpanded(section.header, section.content, section.key, open)
    }

    private fun restoreSavedVisibilityDeep(root: View) {
        restoreSavedVisibility(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                restoreSavedVisibilityDeep(root.getChildAt(i))
            }
        }
    }

    private fun restoreSavedVisibility(v: View) {
        val saved = v.getTag(SAVED_VIS) as? Int
        if (saved != null) {
            v.visibility = saved
            v.setTag(SAVED_VIS, null)
        }
    }

    private fun resourceEntryName(v: View): String {
        if (v.id == View.NO_ID) return ""
        return try {
            v.resources.getResourceEntryName(v.id)
        } catch (_: Exception) {
            ""
        }
    }

    fun normalize(raw: String): String {
        val n = Normalizer.normalize(raw.trim(), Normalizer.Form.NFD)
        return n.replace("\\p{Mn}+".toRegex(), "").lowercase()
    }
}
