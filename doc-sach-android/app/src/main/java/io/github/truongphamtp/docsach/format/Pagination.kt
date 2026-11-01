package io.github.truongphamtp.docsach.format

/** Kết quả đo một đoạn chữ đã ngắt dòng (độc lập với thư viện vẽ). */
interface MeasuredText {
    val lineCount: Int
    fun lineTop(line: Int): Float
    fun lineBottom(line: Int): Float
    fun lineStart(line: Int): Int
    fun lineEnd(line: Int): Int
}

class PieceSize(val width: Float, val height: Float)

/** Cách đo chữ và khoảng cách (đơn vị pixel) dùng khi dàn trang. */
interface PageStyle<M : MeasuredText> {
    val bodyLineHeight: Float
    fun measure(block: TextBlock, width: Int): M
    fun inset(kind: BlockKind): Float
    fun spaceBefore(kind: BlockKind): Float
    fun spaceAfter(kind: BlockKind): Float
    fun imageSize(block: ImageBlock, maxWidth: Float, maxHeight: Float): PieceSize
}

sealed class PagePiece {
    abstract val blockIndex: Int
    abstract val x: Float
    abstract val y: Float
}

class TextPiece<M : MeasuredText>(
    override val blockIndex: Int,
    val block: TextBlock,
    val text: M,
    val firstLine: Int,
    val lastLine: Int,
    override val x: Float,
    override val y: Float,
) : PagePiece() {
    val top: Float get() = text.lineTop(firstLine)
    val height: Float get() = text.lineBottom(lastLine) - top
    val startOffset: Int get() = block.start + text.lineStart(firstLine)
    val endOffset: Int get() = block.start + text.lineEnd(lastLine)
}

class ImagePiece(
    override val blockIndex: Int,
    val block: ImageBlock,
    override val x: Float,
    override val y: Float,
    val width: Float,
    val height: Float,
) : PagePiece()

class PageModel(val index: Int, val pieces: List<PagePiece>, val startOffset: Int, val endOffset: Int)

class ChapterPages(val chapter: Int, val pages: List<PageModel>) {
    /** Trang chứa vị trí ký tự [offset]. */
    fun pageForOffset(offset: Int): Int {
        var lo = 0
        var hi = pages.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (pages[mid].startOffset <= offset) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}

/** Dàn trang một chương: chia các khối chữ theo dòng sao cho vừa khung trang. */
object Paginator {
    fun <M : MeasuredText> paginate(
        chapterIndex: Int,
        chapter: Chapter,
        style: PageStyle<M>,
        width: Int,
        height: Int,
    ): ChapterPages {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1).toFloat()
        val pages = ArrayList<PageModel>()
        var pieces = ArrayList<PagePiece>()
        var y = 0f
        var pageStart = -1
        var pageEnd = 0

        fun flush() {
            if (pieces.isEmpty()) return
            pages += PageModel(pages.size, pieces, pageStart.coerceAtLeast(0), pageEnd)
            pieces = ArrayList()
            y = 0f
            pageStart = -1
        }

        fun begin(offset: Int) {
            if (pageStart < 0) pageStart = offset
        }

        for ((bi, block) in chapter.blocks.withIndex()) {
            if (block.pageBreakBefore) flush()
            when (block) {
                is TextBlock -> {
                    val inset = style.inset(block.kind)
                    val measured = style.measure(block, (w - inset).toInt().coerceAtLeast(1))
                    val lineCount = measured.lineCount
                    if (lineCount == 0) continue
                    val before = style.spaceBefore(block.kind)

                    if (block.kind.isHeading && pieces.isNotEmpty()) {
                        // Không để tiêu đề nằm trơ trọi cuối trang.
                        val need = before + measured.lineBottom(lineCount - 1) - measured.lineTop(0) +
                            style.bodyLineHeight * 2
                        if (y + need > h && need <= h) flush()
                    }

                    var line = 0
                    while (line < lineCount) {
                        val gap = if (line == 0 && pieces.isNotEmpty()) before else 0f
                        val top = measured.lineTop(line)
                        val available = h - y - gap
                        var last = line - 1
                        while (last + 1 < lineCount && measured.lineBottom(last + 1) - top <= available + 0.5f) last++
                        if (last < line) {
                            if (pieces.isEmpty()) {
                                last = line
                            } else {
                                flush()
                                continue
                            }
                        }
                        if (lineCount - 1 - last == 1 && last > line) {
                            // Dòng goá: tránh để đúng một dòng cuối của đoạn sang trang sau.
                            last -= 1
                        }
                        if (pieces.isNotEmpty() && line == 0 && last == 0 && lineCount > 1) {
                            // Dòng mồ côi: chỉ vừa dòng đầu của đoạn ở cuối trang -> chuyển cả đoạn sang trang sau.
                            flush()
                            continue
                        }
                        y += gap
                        begin(block.start + measured.lineStart(line))
                        pieces += TextPiece(bi, block, measured, line, last, inset, y)
                        y += measured.lineBottom(last) - top
                        line = last + 1
                        pageEnd = if (line >= lineCount) block.end else block.start + measured.lineEnd(last)
                        if (line < lineCount) flush()
                    }
                    y += style.spaceAfter(block.kind)
                }

                is ImageBlock -> {
                    val size = style.imageSize(block, w.toFloat(), h)
                    val gap = if (pieces.isNotEmpty()) style.spaceAfter(BlockKind.PARAGRAPH) else 0f
                    if (pieces.isNotEmpty() && y + gap + size.height > h) flush()
                    if (pieces.isNotEmpty()) y += gap
                    begin(block.start)
                    pieces += ImagePiece(bi, block, (w - size.width) / 2f, y, size.width, size.height)
                    y += size.height + style.spaceAfter(BlockKind.PARAGRAPH)
                    pageEnd = block.end
                }
            }
        }
        flush()
        if (pages.isEmpty()) pages += PageModel(0, emptyList(), 0, chapter.length)
        return ChapterPages(chapterIndex, pages)
    }
}
