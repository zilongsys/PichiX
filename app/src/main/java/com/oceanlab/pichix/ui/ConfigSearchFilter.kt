package com.oceanlab.pichix.ui

import android.text.Normalizer
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.oceanlab.pichix.data.AppSettings

/**
 * Filtra secciones y bloques de Config según un texto de búsqueda
 * (etiquetas, hints, botones). Con query vacío restaura todo.
 */
object ConfigSearchFilter {

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

            val headerHit = normalize(section.header.text.toString()).contains(query)
            var blockHits = 0
            val content = section.content
            if (content is ViewGroup) {
                for (i in 0 until content.childCount) {
                    val block = content.getChildAt(i)
                    val hit = headerHit || collectText(block).contains(query)
                    block.visibility = if (hit) View.VISIBLE else View.GONE
                    if (hit) blockHits++
                }
            }

            val show = headerHit || blockHits > 0
            section.header.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                anyVisible = true
                ConfigSectionBinder.showExpandedVisual(
                    section.header,
                    section.content,
                    expanded = true,
                )
                if (headerHit && content is ViewGroup) {
                    for (i in 0 until content.childCount) {
                        content.getChildAt(i).visibility = View.VISIBLE
                    }
                }
            } else {
                section.content.visibility = View.GONE
            }
        }

        emptyLabel?.visibility = if (!anyVisible && query.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun restoreSection(section: Section, settings: AppSettings) {
        section.header.visibility = View.VISIBLE
        val open = settings.isConfigSectionExpanded(section.key, section.startExpanded)
        val content = section.content
        if (content is ViewGroup) {
            for (i in 0 until content.childCount) {
                content.getChildAt(i).visibility = View.VISIBLE
            }
        }
        ConfigSectionBinder.setExpanded(section.header, content, section.key, open)
    }

    private fun collectText(root: View): String {
        val sb = StringBuilder()
        fun walk(v: View) {
            when (v) {
                is MaterialButton -> sb.append(' ').append(v.text)
                is TextView -> sb.append(' ').append(v.text)
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return normalize(sb.toString())
    }

    fun normalize(raw: String): String {
        val n = Normalizer.normalize(raw.trim(), Normalizer.Form.NFD)
        return n.replace("\\p{Mn}+".toRegex(), "").lowercase()
    }
}
