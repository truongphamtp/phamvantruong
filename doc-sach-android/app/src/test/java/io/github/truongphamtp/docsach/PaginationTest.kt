package io.github.truongphamtp.docsach

import io.github.truongphamtp.docsach.format.BlockKind
import io.github.truongphamtp.docsach.format.Chapter
import io.github.truongphamtp.docsach.format.ImageBlock
import io.github.truongphamtp.docsach.format.ImagePiece
import io.github.truongphamtp.docsach.format.MeasuredText
import io.github.truongphamtp.docsach.format.PageStyle
import io.github.truongphamtp.docsach.format.Paginator
import io.github.truongphamtp.docsach.format.PieceSize
import io.github.truongphamtp.docsach.format.TextBlock
import io.github.truongphamtp.docsach.format.TextPiece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bộ đo giả: mỗi dòng chứa tối đa [charsPerLine] ký tự và cao 10px. */
private class FakeText(private val length: Int, private val charsPerLine: Int) : MeasuredText {
    override val lineCount: Int = maxOf(1, (length + charsPerLine - 1) / charsPerLine)
    override fun lineTop(line: Int) = line * 10f
    override fun lineBottom(line: Int) = (line + 1) * 10f
    override fun lineStart(line: Int) = minOf(length, line * charsPerLine)
    override fun lineEnd(line: Int) = minOf(length, (line + 1) * charsPerLine)
}

private class FakeStyle(private val charsPerLine: Int = 10) : PageStyle<FakeText> {
    override val bodyLineHeight = 10f
    override fun measure(block: TextBlock, width: Int) = FakeText(block.text.length, charsPerLine)
    override fun inset(kind: BlockKind) = 0f
    override fun spaceBefore(kind: BlockKind) = if (kind.isHeading) 10f else 0f
    override fun spaceAfter(kind: BlockKind) = 0f
    override fun imageSize(block: ImageBlock, maxWidth: Float, maxHeight: Float) = PieceSize(50f, minOf(35f, maxHeight))
}

class PaginationTest {
    private fun chapterOf(vararg texts: String, kinds: List<BlockKind> = emptyList()): Chapter {
        var offset = 0
        val blocks = texts.mapIndexed { i, t ->
            TextBlock(offset, t, kinds.getOrElse(i) { BlockKind.PARAGRAPH }).also { offset += t.length + 1 }
        }
        return Chapter("c", blocks)
    }

    /** Ghép lại toàn bộ chữ từ các trang để chắc chắn không mất/lặp chữ. */
    private fun reconstruct(chapter: Chapter, height: Int): String {
        val pages = Paginator.paginate(0, chapter, FakeStyle(), 100, height).pages
        val sb = StringBuilder()
        var lastEnd = 0
        for (page in pages) {
            assertTrue("trang phải bắt đầu ở vị trí tăng dần", page.startOffset >= lastEnd || page.index == 0)
            for (piece in page.pieces) {
                if (piece is TextPiece<*>) {
                    val local = piece.startOffset - piece.block.start
                    val localEnd = piece.endOffset - piece.block.start
                    sb.append(piece.block.text, local, localEnd)
                    if (piece.lastLine == piece.text.lineCount - 1) sb.append('|')
                    assertTrue("không vượt chiều cao trang", piece.y + piece.height <= height + 0.5f)
                }
            }
            lastEnd = page.endOffset
        }
        return sb.toString()
    }

    @Test
    fun keepsAllTextAcrossPages() {
        val texts = (1..30).map { i -> "x".repeat(5 + (i * 7) % 43) }
        val chapter = chapterOf(*texts.toTypedArray())
        val expected = texts.joinToString("|", postfix = "|")
        for (height in listOf(25, 40, 55, 100, 333)) {
            assertEquals("chiều cao $height", expected, reconstruct(chapter, height))
        }
    }

    @Test
    fun pageOffsetsAreFindable() {
        val texts = (1..20).map { "y".repeat(35) }
        val chapter = chapterOf(*texts.toTypedArray())
        val result = Paginator.paginate(0, chapter, FakeStyle(), 100, 50)
        for (page in result.pages) {
            assertEquals(page.index, result.pageForOffset(page.startOffset))
            if (page.endOffset > page.startOffset) {
                assertEquals(page.index, result.pageForOffset(page.endOffset - 1))
            }
        }
        assertEquals(result.pages.size - 1, result.pageForOffset(Int.MAX_VALUE))
    }

    @Test
    fun avoidsOrphanAndWidowLines() {
        // Trang cao 5 dòng. Đoạn 1 chiếm 4 dòng -> đoạn 2 (3 dòng) không được để 1 dòng ở cuối trang.
        val chapter = chapterOf("a".repeat(40), "b".repeat(30))
        val pages = Paginator.paginate(0, chapter, FakeStyle(), 100, 50).pages
        assertEquals(2, pages.size)
        assertEquals(1, pages[0].pieces.size)
        val second = pages[1].pieces.single() as TextPiece<*>
        assertEquals(0, second.firstLine)

        // Đoạn 5 dòng trên trang cao 4 dòng: không để đúng 1 dòng sang trang sau.
        val widow = chapterOf("c".repeat(50))
        val wp = Paginator.paginate(0, widow, FakeStyle(), 100, 40).pages
        assertEquals(2, wp.size)
        assertEquals(2, (wp[1].pieces.single() as TextPiece<*>).let { it.lastLine - it.firstLine + 1 })
    }

    @Test
    fun headingStaysWithFollowingText() {
        val chapter = chapterOf("a".repeat(30), "Tiêu đề", "b".repeat(30), kinds = listOf(BlockKind.PARAGRAPH, BlockKind.HEADING2))
        val pages = Paginator.paginate(0, chapter, FakeStyle(), 100, 50).pages
        // Tiêu đề không nằm cuối trang 1 mà sang trang 2 cùng đoạn văn sau nó.
        assertEquals(1, pages[0].pieces.size)
        assertEquals(1, pages[1].pieces.first().blockIndex)
    }

    @Test
    fun imagesMoveToNextPageWhenTooTall() {
        var offset = 0
        val first = TextBlock(offset, "a".repeat(30), BlockKind.PARAGRAPH).also { offset = it.end }
        val image = ImageBlock(offset, "img.png").also { offset = it.end }
        val chapter = Chapter("c", listOf(first, image))
        val pages = Paginator.paginate(0, chapter, FakeStyle(), 100, 50).pages
        assertEquals(2, pages.size)
        val piece = pages[1].pieces.single() as ImagePiece
        assertEquals(25f, piece.x, 0.01f)
        assertEquals(image.start, pages[1].startOffset)
    }

    @Test
    fun tinyPageStillProgresses() {
        val chapter = chapterOf("z".repeat(100))
        val pages = Paginator.paginate(0, chapter, FakeStyle(), 100, 5).pages
        assertEquals(10, pages.size)
    }
}
