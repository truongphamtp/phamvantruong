package io.github.truongphamtp.docsach.format

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeFilter
import org.jsoup.select.NodeTraversor
import java.text.Normalizer

/** Tên thẻ không kèm tiền tố namespace, viết thường. */
internal fun Element.localName(): String = normalName().substringAfter(':')

private val SKIP_TAGS = setOf(
    "head", "script", "style", "noscript", "template", "title", "button", "input", "select",
    "textarea", "audio", "video", "iframe", "object", "embed", "rt", "rp", "math",
)

private val BLOCK_TAGS = setOf(
    "address", "article", "aside", "body", "caption", "center", "dd", "details", "dialog", "div",
    "dl", "dt", "fieldset", "figcaption", "figure", "footer", "form", "header", "hgroup", "main",
    "nav", "p", "section", "summary", "table", "tbody", "thead", "tfoot", "tr",
    "h1", "h2", "h3", "h4", "h5", "h6", "blockquote",
)

private val BOLD_TAGS = setOf("b", "strong")
private val ITALIC_TAGS = setOf("i", "em", "cite", "dfn", "var")

/** Gom nội dung HTML thành danh sách khối chữ/ảnh. */
internal class ChapterBuilder(private val resolveImage: (String) -> String?) {
    private class BlockContext(val kind: BlockKind, val indent: Boolean)

    val blocks = ArrayList<Block>()
    val anchors = HashMap<String, Int>()
    var firstHeading: String? = null
        private set

    private var offset = 0
    private val sb = StringBuilder()
    private val styles = ArrayList<InlineStyle>()
    private var boldDepth = 0
    private var italicDepth = 0
    private var preDepth = 0
    private val contexts = ArrayList<BlockContext>()
    private val listStack = ArrayList<IntArray>()
    private var pendingPrefix: String? = null
    private var pendingBreak = false

    fun markAnchor(id: String) {
        if (id.isNotEmpty() && id !in anchors) anchors[id] = offset + sb.length
    }

    fun text(raw: String) {
        if (raw.isEmpty()) return
        val value = if (Normalizer.isNormalized(raw, Normalizer.Form.NFC)) raw
        else Normalizer.normalize(raw, Normalizer.Form.NFC)
        val prefix = pendingPrefix
        if (prefix != null && value.any { !it.isWhitespace() }) {
            sb.append(prefix)
            pendingPrefix = null
        }
        val begin = sb.length
        if (preDepth > 0) {
            sb.append(value.replace("\r\n", "\n").replace('\r', '\n'))
        } else {
            for (ch in value) {
                if (ch == ' ' || ch == '\n' || ch == '\t' || ch == '\r' || ch == '\u000C') {
                    if (sb.isEmpty()) continue
                    val last = sb[sb.length - 1]
                    if (last == ' ' || last == '\n') continue
                    sb.append(' ')
                } else {
                    sb.append(ch)
                }
            }
        }
        addStyle(begin, sb.length)
    }

    private fun addStyle(begin: Int, end: Int) {
        if (end <= begin) return
        val bold = boldDepth > 0
        val italic = italicDepth > 0
        if (!bold && !italic) return
        val last = styles.lastOrNull()
        if (last != null && last.end == begin && last.bold == bold && last.italic == italic) {
            styles[styles.size - 1] = last.copy(end = end)
        } else {
            styles += InlineStyle(begin, end, bold, italic)
        }
    }

    fun lineBreak() {
        if (sb.isEmpty()) return
        if (sb[sb.length - 1] == ' ') sb.setLength(sb.length - 1)
        if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') sb.append('\n')
    }

    fun cellSeparator() {
        if (sb.isNotEmpty() && sb[sb.length - 1] != ' ' && sb[sb.length - 1] != '\n') sb.append("  ")
    }

    /** Ngắt trang: khối kế tiếp sẽ bắt đầu ở trang mới. */
    fun pageBreak() {
        flush()
        if (blocks.isNotEmpty()) pendingBreak = true
    }

    fun beginBlock(kind: BlockKind, indent: Boolean = false) {
        flush()
        contexts += BlockContext(kind, indent)
    }

    fun endBlock() {
        flush()
        if (contexts.isNotEmpty()) contexts.removeAt(contexts.size - 1)
    }

    fun beginList(ordered: Boolean, start: Int) {
        flush()
        listStack += intArrayOf(if (ordered) 1 else 0, start - 1)
    }

    fun endList() {
        flush()
        if (listStack.isNotEmpty()) listStack.removeAt(listStack.size - 1)
    }

    fun beginListItem(value: Int?) {
        beginBlock(BlockKind.LIST_ITEM)
        val list = listStack.lastOrNull()
        pendingPrefix = if (list != null && list[0] == 1) {
            list[1] = value ?: (list[1] + 1)
            "${list[1]}. "
        } else {
            "• "
        }
    }

    fun endListItem() {
        endBlock()
        pendingPrefix = null
    }

    fun bold(delta: Int) {
        boldDepth = (boldDepth + delta).coerceAtLeast(0)
    }

    fun italic(delta: Int) {
        italicDepth = (italicDepth + delta).coerceAtLeast(0)
    }

    fun pre(delta: Int) {
        preDepth = (preDepth + delta).coerceAtLeast(0)
    }

    fun image(src: String, alt: String) {
        if (src.isBlank()) return
        val path = resolveImage(src) ?: return
        flush()
        blocks += ImageBlock(offset, path, alt, pageBreakBefore = takeBreak())
        offset += 1
    }

    fun separator() {
        flush()
        val text = "* * *"
        blocks += TextBlock(offset, text, BlockKind.CENTER, pageBreakBefore = takeBreak())
        offset += text.length + 1
    }

    private fun takeBreak(): Boolean {
        val value = pendingBreak
        pendingBreak = false
        return value
    }

    fun flush() {
        var end = sb.length
        while (end > 0 && sb[end - 1].isWhitespace()) end--
        if (end == 0 || sb.isBlank()) {
            reset()
            return
        }
        val text = sb.substring(0, end)
        val kind = effectiveKind()
        val clamped = styles.mapNotNull { s ->
            val e = minOf(s.end, end)
            if (e > s.start) s.copy(end = e) else null
        }
        val indent = contexts.lastOrNull()?.indent == true && kind == BlockKind.PARAGRAPH
        blocks += TextBlock(offset, text, kind, clamped, indent, takeBreak())
        offset += text.length + 1
        if (firstHeading == null && kind.isHeading) firstHeading = text.replace('\n', ' ').trim()
        reset()
    }

    private fun reset() {
        sb.setLength(0)
        styles.clear()
    }

    private fun effectiveKind(): BlockKind {
        for (i in contexts.indices.reversed()) {
            val kind = contexts[i].kind
            if (kind != BlockKind.PARAGRAPH) return kind
        }
        return BlockKind.PARAGRAPH
    }
}

private fun legacyCentered(el: Element): Boolean {
    if (el.attr("align").equals("center", ignoreCase = true)) return true
    val cls = el.className().lowercase()
    return "center" in cls || "centre" in cls
}

private fun blockKindFor(el: Element, name: String, css: CssStyle): BlockKind {
    when (name) {
        "h1" -> return BlockKind.HEADING1
        "h2" -> return BlockKind.HEADING2
        "h3", "h4", "h5", "h6" -> return BlockKind.HEADING3
        "blockquote" -> return BlockKind.QUOTE
        "pre" -> return BlockKind.PREFORMATTED
        "center" -> return BlockKind.CENTER
    }
    val classes = el.classNames()
    // Kiểu "Tiêu đề"/"Phụ đề" của Google Docs khi xuất EPUB.
    if ("title" in classes) return BlockKind.HEADING1
    if ("subtitle" in classes) return BlockKind.HEADING3
    return when (css.align) {
        CssAlign.CENTER -> BlockKind.CENTER
        CssAlign.END -> BlockKind.RIGHT
        CssAlign.START, CssAlign.JUSTIFY -> BlockKind.PARAGRAPH
        null -> if (legacyCentered(el)) BlockKind.CENTER else BlockKind.PARAGRAPH
    }
}

/** Những việc đã làm khi mở thẻ, để hoàn tác đúng khi đóng thẻ. */
private class Frame(
    val block: Boolean = false,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val list: Boolean = false,
    val listItem: Boolean = false,
    val pre: Boolean = false,
    val breakAfter: Boolean = false,
)

private val EMPTY_FRAME = Frame()

/** Chuyển phần thân (body) của một tài liệu XHTML thành các khối nội dung. */
internal fun collectBlocks(
    root: Element,
    sheet: StyleSheet = StyleSheet(),
    resolveImage: (String) -> String?,
): ChapterBuilder {
    val b = ChapterBuilder(resolveImage)
    val frames = ArrayList<Frame>()
    NodeTraversor.filter(object : NodeFilter {
        override fun head(node: Node, depth: Int): NodeFilter.FilterResult {
            if (node is TextNode) {
                b.text(node.wholeText)
                return NodeFilter.FilterResult.CONTINUE
            }
            if (node !is Element) return NodeFilter.FilterResult.CONTINUE
            val name = node.localName()
            if (name in SKIP_TAGS) return NodeFilter.FilterResult.SKIP_ENTIRELY
            val css = sheet.styleFor(node, name)
            if (css.hidden == true) {
                // Google Docs ghi ngắt trang bằng <hr style="page-break-before:always;display:none">.
                if (css.breakBefore == true || css.breakAfter == true) b.pageBreak()
                return NodeFilter.FilterResult.SKIP_ENTIRELY
            }
            if (css.breakBefore == true || name == "pagebreak") b.pageBreak()

            val frame: Frame = when (name) {
                "br" -> {
                    b.lineBreak()
                    EMPTY_FRAME
                }
                "hr" -> {
                    if (css.breakBefore != true && css.breakAfter != true) b.separator()
                    Frame(breakAfter = css.breakAfter == true)
                }
                "img" -> {
                    b.image(node.attr("src"), node.attr("alt"))
                    EMPTY_FRAME
                }
                "image" -> {
                    b.image(node.attr("xlink:href").ifEmpty { node.attr("href") }, "")
                    EMPTY_FRAME
                }
                "svg" -> {
                    for (img in node.getAllElements()) {
                        if (img.localName() == "image") {
                            b.image(img.attr("xlink:href").ifEmpty { img.attr("href") }, "")
                        }
                    }
                    return NodeFilter.FilterResult.SKIP_ENTIRELY
                }
                "ul", "ol" -> {
                    val start = node.attr("start").trim().toIntOrNull() ?: 1
                    b.beginList(ordered = name == "ol", start = start)
                    Frame(list = true, breakAfter = css.breakAfter == true)
                }
                "li" -> {
                    b.beginListItem(node.attr("value").trim().toIntOrNull())
                    Frame(listItem = true, bold = applyBold(b, css), italic = applyItalic(b, css))
                }
                "pre" -> {
                    b.beginBlock(BlockKind.PREFORMATTED)
                    b.pre(+1)
                    Frame(block = true, pre = true)
                }
                "td", "th" -> {
                    b.cellSeparator()
                    Frame(bold = applyBold(b, css, name == "th"), italic = applyItalic(b, css))
                }
                else -> {
                    val isBlock = name in BLOCK_TAGS
                    if (isBlock) b.beginBlock(blockKindFor(node, name, css), indent = css.indent == true)
                    Frame(
                        block = isBlock,
                        bold = applyBold(b, css, name in BOLD_TAGS),
                        italic = applyItalic(b, css, name in ITALIC_TAGS),
                        breakAfter = css.breakAfter == true,
                    )
                }
            }
            // Ghi mốc sau khi đã mở khối, để liên kết "#id" trỏ đúng đầu đoạn.
            if (node.id().isNotEmpty()) b.markAnchor(node.id())
            if (name == "a") node.attr("name").takeIf { it.isNotEmpty() }?.let(b::markAnchor)
            frames += frame
            return NodeFilter.FilterResult.CONTINUE
        }

        override fun tail(node: Node, depth: Int): NodeFilter.FilterResult {
            if (node !is Element) return NodeFilter.FilterResult.CONTINUE
            val frame = if (frames.isNotEmpty()) frames.removeAt(frames.size - 1) else return NodeFilter.FilterResult.CONTINUE
            if (frame.bold) b.bold(-1)
            if (frame.italic) b.italic(-1)
            if (frame.pre) b.pre(-1)
            when {
                frame.listItem -> b.endListItem()
                frame.list -> b.endList()
                frame.block -> b.endBlock()
            }
            if (frame.breakAfter) b.pageBreak()
            return NodeFilter.FilterResult.CONTINUE
        }
    }, root)
    b.flush()
    return b
}

private fun applyBold(b: ChapterBuilder, css: CssStyle, byTag: Boolean = false): Boolean {
    val bold = css.bold ?: byTag
    if (bold) b.bold(+1)
    return bold
}

private fun applyItalic(b: ChapterBuilder, css: CssStyle, byTag: Boolean = false): Boolean {
    val italic = css.italic ?: byTag
    if (italic) b.italic(+1)
    return italic
}
