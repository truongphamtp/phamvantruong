package io.github.truongphamtp.docsach.format

import io.github.truongphamtp.docsach.core.ReadingPosition

/** Loại khối nội dung sau khi chuyển EPUB/TXT về dạng đơn giản để tự dàn trang. */
enum class BlockKind {
    PARAGRAPH,
    HEADING1,
    HEADING2,
    HEADING3,
    QUOTE,
    CENTER,
    RIGHT,
    PREFORMATTED,
    LIST_ITEM;

    val isHeading: Boolean get() = this == HEADING1 || this == HEADING2 || this == HEADING3
}

data class InlineStyle(val start: Int, val end: Int, val bold: Boolean, val italic: Boolean)

/**
 * Mỗi khối chiếm một đoạn [start, end) trong "không gian ký tự" của chương.
 * Khối chữ dài bằng số ký tự + 1 (ký tự ngăn cách), khối ảnh dài 1.
 */
sealed class Block {
    abstract val start: Int
    abstract val length: Int

    /** Bắt đầu trang mới trước khối này (ngắt trang trong file gốc). */
    abstract val pageBreakBefore: Boolean
    val end: Int get() = start + length

    /** Bản sao với vị trí bắt đầu mới (dùng khi tách một tệp lớn thành nhiều chương). */
    abstract fun movedTo(newStart: Int): Block
}

class TextBlock(
    override val start: Int,
    val text: String,
    val kind: BlockKind,
    val styles: List<InlineStyle> = emptyList(),
    /** Thụt đầu dòng (text-indent trong CSS). */
    val indent: Boolean = false,
    override val pageBreakBefore: Boolean = false,
) : Block() {
    override val length: Int get() = text.length + 1
    override fun movedTo(newStart: Int) = TextBlock(newStart, text, kind, styles, indent, pageBreakBefore)
}

class ImageBlock(
    override val start: Int,
    /** Đường dẫn ảnh bên trong file EPUB. */
    val path: String,
    val alt: String = "",
    override val pageBreakBefore: Boolean = false,
) : Block() {
    override val length: Int get() = 1
    override fun movedTo(newStart: Int) = ImageBlock(newStart, path, alt, pageBreakBefore)
}

class Chapter(
    val title: String,
    val blocks: List<Block>,
    val anchors: Map<String, Int> = emptyMap(),
    val path: String = "",
) {
    val length: Int = blocks.lastOrNull()?.end ?: 0

    /** Chỉ số khối chứa vị trí [offset]. */
    fun blockIndexAt(offset: Int): Int {
        if (blocks.isEmpty()) return 0
        var lo = 0
        var hi = blocks.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (blocks[mid].start <= offset) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** Lấy một đoạn chữ ngắn bắt đầu từ [offset] (dùng cho dấu trang). */
    fun textFrom(offset: Int, maxChars: Int): String {
        val sb = StringBuilder()
        var i = blockIndexAt(offset)
        while (i < blocks.size && sb.length < maxChars) {
            val block = blocks[i]
            if (block is TextBlock) {
                val from = (offset - block.start).coerceIn(0, block.text.length)
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(block.text, from, block.text.length)
            }
            i++
        }
        val text = sb.toString().replace('\n', ' ').trim()
        return if (text.length <= maxChars) text else text.substring(0, maxChars).trimEnd() + "…"
    }
}

class TocEntry(val title: String, val chapter: Int, val offset: Int, val depth: Int)

class BookContent(
    val title: String,
    val author: String,
    val chapters: List<Chapter>,
    toc: List<TocEntry>,
    /** Vị trí "bắt đầu đọc" do file khai báo (guide/landmarks, giống Kindle). */
    val startPosition: ReadingPosition? = null,
) {
    val toc: List<TocEntry> = toc.ifEmpty {
        chapters.mapIndexedNotNull { index, chapter ->
            if (chapter.title.isNotBlank()) TocEntry(chapter.title, index, 0, 0) else null
        }
    }

    private val starts: IntArray = IntArray(chapters.size + 1).also { arr ->
        for (i in chapters.indices) arr[i + 1] = arr[i] + chapters[i].length
    }

    val totalLength: Int get() = starts[chapters.size]

    fun chapterStart(index: Int): Int = starts[index.coerceIn(0, chapters.size)]

    fun globalOffset(position: ReadingPosition): Int {
        if (chapters.isEmpty()) return 0
        val ch = position.chapter.coerceIn(0, chapters.size - 1)
        return starts[ch] + position.offset.coerceIn(0, chapters[ch].length)
    }

    fun positionAt(global: Int): ReadingPosition {
        if (chapters.isEmpty()) return ReadingPosition()
        val g = global.coerceIn(0, totalLength)
        var lo = 0
        var hi = chapters.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= g) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ReadingPosition(ans, (g - starts[ans]).coerceAtMost(chapters[ans].length))
    }

    fun progress(position: ReadingPosition): Float =
        if (totalLength == 0) 0f else globalOffset(position).toFloat() / totalLength

    fun chapterTitle(index: Int): String {
        val chapter = chapters.getOrNull(index) ?: return ""
        if (chapter.title.isNotBlank()) return chapter.title
        val fromToc = toc.lastOrNull { it.chapter <= index }?.title
        return fromToc ?: "Phần ${index + 1}"
    }
}
