package io.github.truongphamtp.docsach.format

import org.jsoup.nodes.Element

internal enum class CssAlign { START, CENTER, END, JUSTIFY }

/**
 * Phần nhỏ của CSS mà trình đọc hiểu được — đúng những thuộc tính các công cụ xuất EPUB
 * cho Kindle (Google Docs, Word, LibreOffice, Calibre, Sigil...) hay dùng.
 */
internal data class CssStyle(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val align: CssAlign? = null,
    val hidden: Boolean? = null,
    val breakBefore: Boolean? = null,
    val breakAfter: Boolean? = null,
    val indent: Boolean? = null,
) {
    fun mergedWith(other: CssStyle): CssStyle = CssStyle(
        bold = other.bold ?: bold,
        italic = other.italic ?: italic,
        align = other.align ?: align,
        hidden = other.hidden ?: hidden,
        breakBefore = other.breakBefore ?: breakBefore,
        breakAfter = other.breakAfter ?: breakAfter,
        indent = other.indent ?: indent,
    )

    companion object {
        val EMPTY = CssStyle()

        private val numberRegex = Regex("^(-?[0-9]*\\.?[0-9]+)")

        fun parseDeclarations(text: String): CssStyle {
            var style = EMPTY
            for (decl in text.split(';')) {
                val colon = decl.indexOf(':')
                if (colon <= 0) continue
                val name = decl.substring(0, colon).trim().lowercase()
                val value = decl.substring(colon + 1).replace("!important", "").trim().lowercase()
                style = when (name) {
                    "font-weight" -> style.copy(
                        bold = when {
                            value == "bold" || value == "bolder" -> true
                            value.toIntOrNull()?.let { it >= 600 } == true -> true
                            value == "normal" || value == "lighter" || value.toIntOrNull() != null -> false
                            else -> style.bold
                        },
                    )
                    "font-style" -> style.copy(
                        italic = when (value) {
                            "italic", "oblique" -> true
                            "normal" -> false
                            else -> style.italic
                        },
                    )
                    "font" -> style.copy(
                        bold = if (Regex("\\b(bold|[6-9]00)\\b").containsMatchIn(value)) true else style.bold,
                        italic = if ("italic" in value || "oblique" in value) true else style.italic,
                    )
                    "text-align" -> style.copy(
                        align = when (value) {
                            "center" -> CssAlign.CENTER
                            "right", "end" -> CssAlign.END
                            "justify" -> CssAlign.JUSTIFY
                            "left", "start" -> CssAlign.START
                            else -> style.align
                        },
                    )
                    "display" -> style.copy(hidden = value == "none")
                    "visibility" -> if (value == "hidden") style.copy(hidden = true) else style
                    "page-break-before", "break-before" -> style.copy(breakBefore = value in BREAK_VALUES)
                    "page-break-after", "break-after" -> style.copy(breakAfter = value in BREAK_VALUES)
                    "text-indent" -> {
                        val number = numberRegex.find(value)?.groupValues?.get(1)?.toFloatOrNull()
                        if (number == null) style else style.copy(indent = number > 0f)
                    }
                    else -> style
                }
            }
            return style
        }

        private val BREAK_VALUES = setOf("always", "page", "left", "right", "recto", "verso")
    }
}

/** Bảng kiểu đơn giản: chỉ áp dụng bộ chọn dạng `thẻ`, `.lớp`, `thẻ.lớp` (không xét cha con). */
internal class StyleSheet {
    private class Rule(val tag: String?, val classes: List<String>, val style: CssStyle, val order: Int) {
        val specificity: Int = classes.size * 10 + if (tag != null) 1 else 0
    }

    private val byClass = HashMap<String, MutableList<Rule>>()
    private val byTag = HashMap<String, MutableList<Rule>>()
    private var order = 0

    val isEmpty: Boolean get() = byClass.isEmpty() && byTag.isEmpty()

    fun addCss(css: String) {
        val text = css.replace(COMMENT, " ")
        var i = 0
        while (i < text.length) {
            val open = text.indexOf('{', i)
            if (open < 0) break
            val selector = text.substring(i, open).trim()
            var depth = 1
            var j = open + 1
            while (j < text.length && depth > 0) {
                when (text[j]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                j++
            }
            val body = text.substring(open + 1, (j - 1).coerceAtLeast(open + 1))
            if (selector.startsWith("@")) {
                // Kindle đọc các quy tắc trong @media amzn-kf8; bỏ qua @font-face, @page, @media khác.
                val media = selector.lowercase()
                if (media.startsWith("@media") && ("amzn-kf8" in media || "screen" in media || "all" in media)) {
                    addCss(body)
                }
            } else {
                val style = CssStyle.parseDeclarations(body)
                if (style != CssStyle.EMPTY) {
                    for (sel in selector.split(',')) register(sel.trim(), style)
                }
            }
            i = j
        }
    }

    private fun register(selector: String, style: CssStyle) {
        if (selector.isEmpty()) return
        if (selector.any { it.isWhitespace() || it in ">+~:[#*()" }) return
        val parts = selector.split('.')
        val tag = parts[0].lowercase().substringAfter('|').ifEmpty { null }
        val classes = parts.drop(1).filter { it.isNotEmpty() }
        val rule = Rule(tag, classes, style, order++)
        if (classes.isNotEmpty()) {
            byClass.getOrPut(classes[0]) { ArrayList() } += rule
        } else if (tag != null) {
            byTag.getOrPut(tag) { ArrayList() } += rule
        }
    }

    /** Kiểu cuối cùng của phần tử: thẻ < lớp < thuộc tính style viết trực tiếp. */
    fun styleFor(element: Element, localName: String): CssStyle {
        val classNames = element.classNames()
        val inline = element.attr("style")
        if (isEmpty && inline.isEmpty()) return CssStyle.EMPTY
        val matched = ArrayList<Rule>()
        byTag[localName]?.let(matched::addAll)
        for (cls in classNames) {
            byClass[cls]?.forEach { rule ->
                val tagOk = rule.tag == null || rule.tag == localName
                if (tagOk && classNames.containsAll(rule.classes)) matched += rule
            }
        }
        var style = CssStyle.EMPTY
        for (rule in matched.sortedWith(compareBy<Rule>({ it.specificity }, { it.order }))) {
            style = style.mergedWith(rule.style)
        }
        if (inline.isNotEmpty()) style = style.mergedWith(CssStyle.parseDeclarations(inline))
        return style
    }

    private companion object {
        val COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    }
}
