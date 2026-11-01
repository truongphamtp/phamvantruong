package io.github.truongphamtp.docsach

import io.github.truongphamtp.docsach.core.ReadingPosition
import io.github.truongphamtp.docsach.format.BlockKind
import io.github.truongphamtp.docsach.format.BookSearch
import io.github.truongphamtp.docsach.format.EpubArchive
import io.github.truongphamtp.docsach.format.EpubParser
import io.github.truongphamtp.docsach.format.ImageBlock
import io.github.truongphamtp.docsach.format.SpeechText
import io.github.truongphamtp.docsach.format.TextBlock
import io.github.truongphamtp.docsach.format.TextFold
import io.github.truongphamtp.docsach.format.TxtParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FormatTest {

    private fun makeEpub(files: Map<String, String>, extra: Map<String, ByteArray> = emptyMap()): File {
        val file = File.createTempFile("test", ".epub")
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            for ((name, text) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
            }
            for ((name, bytes) in extra) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
            }
        }
        return file
    }

    private val container = """<?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>"""

    private val opf = """<?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>Truyện Thử Nghiệm</dc:title>
            <dc:creator>Nguyễn Văn A</dc:creator>
          </metadata>
          <manifest>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="c1" href="text/ch%201.xhtml" media-type="application/xhtml+xml"/>
            <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
            <item id="img" href="images/cover.png" media-type="image/png" properties="cover-image"/>
          </manifest>
          <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
        </package>"""

    private val nav = """<?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title/></head>
        <body><nav epub:type="toc"><ol>
          <li><a href="text/ch%201.xhtml">Chương một</a></li>
          <li><a href="text/ch2.xhtml">Chương hai</a>
            <ol><li><a href="text/ch2.xhtml#muc2">Mục 2.1</a></li></ol>
          </li>
        </ol></nav></body></html>"""

    private val ch1 = """<?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE html>
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title/><style>p{}</style></head>
        <body>
          <h1>Chương 1</h1>
          <p>Xin   chào <b>thế giới</b>&nbsp;và <i>bạn  đọc</i>.</p>
          <p class="center">* * *</p>
          <img src="../images/cover.png" alt="bìa"/>
          <ul><li>Một</li><li><p>Hai</p></li></ul>
          <p>Dòng một<br/>dòng hai</p>
          <p>   </p>
        </body></html>"""

    private val ch2 = """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>x</title></head><body>
        <h2>Chương 2</h2>
        <p>Đoạn đầu tiên.</p>
        <p id="muc2">Mục hai bắt đầu ở đây.</p>
        <blockquote><p>Trích dẫn</p></blockquote>
        </body></html>"""

    @Test
    fun parsesEpubStructure() {
        val file = makeEpub(
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf,
                "OEBPS/nav.xhtml" to nav,
                "OEBPS/text/ch 1.xhtml" to ch1,
                "OEBPS/text/ch2.xhtml" to ch2,
            ),
            mapOf("OEBPS/images/cover.png" to byteArrayOf(1, 2, 3)),
        )
        EpubArchive(file).use { archive ->
            val meta = EpubParser.readMetadata(archive)
            assertEquals("Truyện Thử Nghiệm", meta.title)
            assertEquals("Nguyễn Văn A", meta.author)
            assertEquals("OEBPS/images/cover.png", meta.coverPath)

            val book = EpubParser.parse(archive, "fallback")
            assertEquals(2, book.chapters.size)
            assertEquals("Chương một", book.chapters[0].title)
            assertEquals(3, book.toc.size)

            val blocks = book.chapters[0].blocks
            val first = blocks[0] as TextBlock
            assertEquals(BlockKind.HEADING1, first.kind)
            val para = blocks[1] as TextBlock
            assertEquals("Xin chào thế giới và bạn đọc.", para.text)
            assertEquals(2, para.styles.size)
            assertTrue(para.styles[0].bold)
            assertEquals("thế giới", para.text.substring(para.styles[0].start, para.styles[0].end))
            assertEquals("bạn đọc", para.text.substring(para.styles[1].start, para.styles[1].end))
            assertEquals(BlockKind.CENTER, (blocks[2] as TextBlock).kind)
            assertEquals("OEBPS/images/cover.png", (blocks[3] as ImageBlock).path)
            assertEquals("• Một", (blocks[4] as TextBlock).text)
            assertEquals("• Hai", (blocks[5] as TextBlock).text)
            assertEquals(BlockKind.LIST_ITEM, (blocks[5] as TextBlock).kind)
            assertEquals("Dòng một\ndòng hai", (blocks[6] as TextBlock).text)
            assertEquals(7, blocks.size)

            // Liên kết có #muc2 phải trỏ đúng vị trí đoạn "Mục hai..."
            val sub = book.toc[2]
            assertEquals(1, sub.chapter)
            val chapter2 = book.chapters[1]
            val target = chapter2.blocks[chapter2.blockIndexAt(sub.offset)] as TextBlock
            assertTrue(target.text.startsWith("Mục hai"))
            assertEquals(BlockKind.QUOTE, (chapter2.blocks.last() as TextBlock).kind)
        }
    }

    @Test
    fun positionsAndProgress() {
        val book = TxtParser.parse("Chương 1\nabc\ndef\nChương 2\nghi\n", "T")
        assertEquals(2, book.chapters.size)
        val total = book.totalLength
        assertEquals(0f, book.progress(ReadingPosition(0, 0)), 0.0001f)
        val pos = book.positionAt(book.chapterStart(1) + 3)
        assertEquals(ReadingPosition(1, 3), pos)
        assertEquals(total, book.globalOffset(ReadingPosition(1, 10_000)))
    }

    @Test
    fun txtChapterDetection() {
        assertTrue(TxtParser.isHeading("Chương 12: Gặp lại"))
        assertTrue(TxtParser.isHeading("CHƯƠNG I"))
        assertTrue(TxtParser.isHeading("Hồi thứ hai"))
        assertTrue(TxtParser.isHeading("Chapter 3 - The End"))
        assertTrue(TxtParser.isHeading("Lời mở đầu"))
        assertFalse(TxtParser.isHeading("Phần lớn mọi người đều thích đọc sách."))
        assertFalse(TxtParser.isHeading("Chương trình hôm nay rất hay"))
        assertFalse(TxtParser.isHeading("Tập trung vào việc học"))

        val text = "Tên truyện\nTác giả\n\nChương 1\nChương 2\nChương 3\nChương 1\nNội dung một.\n\nChương 2\nNội dung hai.\nChương 3\nNội dung ba."
        val book = TxtParser.parse(text, "Truyện")
        val titles = book.chapters.map { it.title }
        assertEquals(listOf("Truyện", "Mục lục", "Chương 1", "Chương 2", "Chương 3"), titles)
    }

    @Test
    fun txtWithoutChaptersIsSplitIntoParts() {
        val para = "Đây là một đoạn văn khá dài dùng để kiểm tra việc chia phần. ".repeat(20)
        val text = (1..40).joinToString("\n") { para }
        val book = TxtParser.parse(text, "Không chương")
        assertTrue(book.chapters.size > 1)
        assertEquals("Phần 1", book.chapters[0].title)
    }

    @Test
    fun decodesLegacyEncodings() {
        assertEquals("Tiếng Việt", TxtParser.decode("Tiếng Việt".toByteArray(Charsets.UTF_8)))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Xin chào".toByteArray()
        assertEquals("Xin chào", TxtParser.decode(bom))
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Đọc".toByteArray(Charsets.UTF_16LE)
        assertEquals("Đọc", TxtParser.decode(utf16))
    }

    @Test
    fun foldsVietnameseDiacritics() {
        assertEquals("tieng viet dang yeu", TextFold.foldString("Tiếng Việt đáng yêu"))
        assertTrue(TextFold.hasDiacritics("tìm"))
        assertFalse(TextFold.hasDiacritics("tim kiem"))
        // Chuỗi ở dạng tổ hợp (NFD) cũng phải tìm được.
        val decomposed = java.text.Normalizer.normalize("Hà Nội", java.text.Normalizer.Form.NFD)
        assertEquals("ha noi", TextFold.foldString(decomposed))
    }

    @Test
    fun searchIsAccentInsensitiveWhenQueryHasNoAccents() {
        val book = TxtParser.parse("Chương 1\nHôm nay trời đẹp. Tôi đi học.\nChương 2\nTRỜI mưa to.", "S")
        val loose = BookSearch.search(book, "troi")
        assertEquals(2, loose.size)
        val hit = loose[0]
        val block = book.chapters[hit.chapter].blocks.first { it.start <= hit.start && hit.start < it.end } as TextBlock
        assertEquals("trời", block.text.substring(hit.start - block.start, hit.end - block.start))
        assertEquals("trời", hit.snippet.substring(hit.matchStart, hit.matchEnd))

        val strict = BookSearch.search(book, "trời")
        assertEquals(2, strict.size)
        assertEquals(0, BookSearch.search(book, "trợi").size)
    }

    @Test
    fun splitsSentences() {
        val text = "Xin chào! Hôm nay là thứ hai. \"Đúng vậy.\" Rồi sao…\nDòng mới"
        val parts = SpeechText.sentences(text).map { text.substring(it.first, it.last + 1) }
        assertEquals(listOf("Xin chào!", "Hôm nay là thứ hai.", "\"Đúng vậy.\"", "Rồi sao…", "Dòng mới"), parts)
        val long = "a, ".repeat(300)
        assertTrue(SpeechText.sentences(long, maxLength = 100).all { it.last - it.first < 100 })
    }
}
