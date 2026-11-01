package io.github.truongphamtp.docsach

import io.github.truongphamtp.docsach.core.ReadingPosition
import io.github.truongphamtp.docsach.format.BlockKind
import io.github.truongphamtp.docsach.format.EpubArchive
import io.github.truongphamtp.docsach.format.EpubParser
import io.github.truongphamtp.docsach.format.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Cuốn "Hướng dẫn sử dụng" đi kèm ứng dụng phải đọc được đầy đủ. */
class GuideBookTest {
    private fun guideFile(): File {
        val candidates = listOf("src/main/assets/huong-dan-su-dung.epub", "app/src/main/assets/huong-dan-su-dung.epub")
        return candidates.map(::File).first { it.exists() }
    }

    @Test
    fun guideBookParses() {
        EpubArchive(guideFile()).use { archive ->
            val book = EpubParser.parse(archive, "x")
            assertEquals("Hướng dẫn sử dụng Đọc Sách", book.title)
            assertEquals(10, book.chapters.size)
            assertEquals(9, book.toc.size)
            assertEquals("Chào mừng", book.toc.first().title)
            // Lần đầu mở sẽ nhảy tới "Chào mừng" (bỏ qua trang bìa), giống Kindle.
            assertEquals(ReadingPosition(1, 0), book.startPosition)

            val intro = book.chapters[1].blocks.filterIsInstance<TextBlock>()
            val first = intro.first { it.kind == BlockKind.PARAGRAPH }
            assertTrue(first.text.startsWith("Đọc Sách là ứng dụng"))
            assertTrue(first.styles.first().bold)
            assertTrue(intro.any { it.indent })

            val display = book.chapters[5].blocks.filterIsInstance<TextBlock>()
            assertTrue(display.any { it.kind == BlockKind.RIGHT })
            val last = book.chapters.last().blocks
            assertTrue(last.last().pageBreakBefore)
        }
    }
}
