package io.github.truongphamtp.docsach

import io.github.truongphamtp.docsach.core.ReadingPosition
import io.github.truongphamtp.docsach.format.BlockKind
import io.github.truongphamtp.docsach.format.EpubArchive
import io.github.truongphamtp.docsach.format.EpubParser
import io.github.truongphamtp.docsach.format.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * File EPUB tự làm để gửi sang Kindle (Google Docs, Word, LibreOffice, Calibre...) thường định dạng
 * bằng lớp CSS thay cho thẻ <b>/<i>. Các kiểm thử này mô phỏng những file như vậy.
 */
class KindleEpubTest {

    private fun epub(files: Map<String, String>): File {
        val file = File.createTempFile("kindle", ".epub")
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            for ((name, text) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
            }
        }
        return file
    }

    private val container = """<?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>"""

    /** Kiểu Google Docs: một tệp XHTML duy nhất, CSS lớp c1, c2..., ngắt trang bằng <hr> ẩn. */
    @Test
    fun googleDocsStyleExport() {
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Giáo án Ngữ văn</dc:title></metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="doc" href="GoogleDoc/Giao_an.xhtml" media-type="application/xhtml+xml"/>
                <item id="css" href="GoogleDoc/style.css" media-type="text/css"/>
              </manifest>
              <spine><itemref idref="doc"/></spine>
            </package>"""
        val css = """
            /* Google Docs */
            .c1{font-weight:400;font-style:normal;color:#000000}
            .c2{font-weight:700}
            .c3{font-style:italic}
            .c4{text-align:center}
            .c5{text-align:right}
            .c6{text-indent:36pt;margin:0}
            .hidden{display:none}
            p.note{font-style:italic}
            @media amzn-kf8 { .k8{font-weight:bold} }
            @media print { .c1{font-weight:bold} }
            div.chapter p { font-weight: bold }
        """
        val doc = """<?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>x</title>
              <link href="style.css" rel="stylesheet" type="text/css"/>
              <style>.c7{font-weight:bold;font-style:italic}</style>
            </head><body>
              <p class="c4 title" id="h.title"><span class="c1">Giáo án Ngữ văn 10</span></p>
              <p class="subtitle"><span class="c1">Học kỳ I</span></p>
              <p class="c6"><span class="c1">Đoạn </span><span class="c2">đậm</span><span class="c1"> và </span><span class="c3">nghiêng</span><span class="c7">cả hai</span></p>
              <p class="c4"><span class="c1">Căn giữa</span></p>
              <p class="c5"><span class="c1">Hà Nội, ngày 1 tháng 11 năm 2026</span></p>
              <p class="note">Ghi chú</p>
              <p class="hidden">Không hiện</p>
              <p><span class="k8">Kindle</span></p>
              <div class="chapter"><p>Không áp dụng bộ chọn lồng nhau</p></div>
              <hr style="page-break-before:always;display:none;"/>
              <h1 id="h.bai1"><span class="c1">Bài 1</span></h1>
              <p><span class="c1">Nội dung bài 1</span></p>
              <p style="page-break-before: always">Trang mới</p>
              <mbp:pagebreak xmlns:mbp="https://kindlegen.amazon.com/"/>
              <p>Sau ngắt trang Kindle</p>
              <ol start="3"><li>Ba</li><li>Bốn</li></ol>
            </body></html>"""
        val nav = """<?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
            <nav epub:type="toc"><ol>
              <li><a href="GoogleDoc/Giao_an.xhtml#h.title">Giáo án</a></li>
              <li><a href="GoogleDoc/Giao_an.xhtml#h.bai1">Bài 1</a></li>
            </ol></nav>
            <nav epub:type="landmarks"><ol>
              <li><a epub:type="bodymatter" href="GoogleDoc/Giao_an.xhtml#h.bai1">Bắt đầu</a></li>
            </ol></nav></body></html>"""
        val file = epub(
            mapOf(
                "META-INF/container.xml" to container,
                "content.opf" to opf,
                "nav.xhtml" to nav,
                "GoogleDoc/Giao_an.xhtml" to doc,
                "GoogleDoc/style.css" to css,
            ),
        )
        EpubArchive(file).use { archive ->
            val book = EpubParser.parse(archive, "x")
            val blocks = book.chapters.flatMap { it.blocks }.filterIsInstance<TextBlock>()
            fun block(text: String) = blocks.first { it.text.startsWith(text) }

            assertEquals(BlockKind.HEADING1, block("Giáo án Ngữ văn 10").kind)
            assertEquals(BlockKind.HEADING3, block("Học kỳ I").kind)

            val mixed = block("Đoạn")
            assertEquals("Đoạn đậm và nghiêngcả hai", mixed.text)
            assertTrue(mixed.indent)
            fun styleOf(word: String) = mixed.styles.first { mixed.text.substring(it.start, it.end) == word }
            assertTrue(styleOf("đậm").bold)
            assertFalse(styleOf("đậm").italic)
            assertTrue(styleOf("nghiêng").italic)
            assertTrue(styleOf("cả hai").bold && styleOf("cả hai").italic)
            assertEquals(3, mixed.styles.size)

            assertEquals(BlockKind.CENTER, block("Căn giữa").kind)
            assertEquals(BlockKind.RIGHT, block("Hà Nội").kind)
            assertTrue(block("Ghi chú").styles.single().italic)
            assertTrue(blocks.none { it.text.contains("Không hiện") })
            assertTrue(block("Kindle").styles.single().bold)
            assertTrue(block("Không áp dụng").styles.isEmpty())

            assertTrue(block("Bài 1").pageBreakBefore)
            assertTrue(block("Trang mới").pageBreakBefore)
            assertTrue(block("Sau ngắt trang Kindle").pageBreakBefore)
            assertFalse(block("Nội dung bài 1").pageBreakBefore)
            assertEquals("3. Ba", block("3.").text)
            assertEquals("4. Bốn", block("4.").text)

            // Điểm bắt đầu đọc (landmarks bodymatter) trỏ đúng "Bài 1".
            val start = book.startPosition
            assertNotNull(start)
            val chapter = book.chapters[start!!.chapter]
            val startBlock = chapter.blocks[chapter.blockIndexAt(start.offset)] as TextBlock
            assertEquals("Bài 1", startBlock.text)
            assertEquals("Bài 1", book.toc[1].title)
            assertEquals(start, ReadingPosition(book.toc[1].chapter, book.toc[1].offset))
        }
    }

    /** Kiểu Calibre/Word EPUB 2: NCX, <guide>, lớp calibreN, tệp rất dài được tách chương. */
    @Test
    fun calibreStyleExportWithGuideAndLongFile() {
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                <dc:title>Tuyển tập</dc:title><dc:creator opf:role="aut">Tác Giả</dc:creator>
                <meta name="cover" content="cover"/>
              </metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="cover" href="images/bia.jpg" media-type="image/jpeg"/>
                <item id="title" href="titlepage.xhtml" media-type="application/xhtml+xml"/>
                <item id="text" href="text/index_split_000.html" media-type="application/xhtml+xml"/>
                <item id="css" href="stylesheet.css" media-type="text/css"/>
              </manifest>
              <spine toc="ncx"><itemref idref="title"/><itemref idref="text"/></spine>
              <guide>
                <reference type="cover" href="titlepage.xhtml" title="Bìa"/>
                <reference type="text" href="text/index_split_000.html#start" title="Bắt đầu"/>
              </guide>
            </package>"""
        val css = ".calibre1{display:block;font-weight:bold;text-align:center}.calibre2{font-style:italic}"
        val longParagraph = "Câu văn dài để làm tệp lớn. ".repeat(40)
        val chapters = (1..30).joinToString("\n") { i ->
            """<h2 class="calibre1" id="c$i">Chương $i</h2>""" +
                (1..3).joinToString("") { "<p class=\"calibre2\">$longParagraph</p>" }
        }
        val text = """<html xmlns="http://www.w3.org/1999/xhtml"><head>
              <link rel="stylesheet" type="text/css" href="../stylesheet.css"/></head>
            <body><p id="start">Lời mở đầu</p>$chapters</body></html>"""
        val ncx = """<?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><navMap>
              <navPoint id="n1"><navLabel><text>Chương 1</text></navLabel><content src="text/index_split_000.html#c1"/></navPoint>
              <navPoint id="n2"><navLabel><text>Chương 25</text></navLabel><content src="text/index_split_000.html#c25"/></navPoint>
            </navMap></ncx>"""
        val file = epub(
            mapOf(
                "META-INF/container.xml" to container,
                "content.opf" to opf,
                "toc.ncx" to ncx,
                "titlepage.xhtml" to """<html><body><div><svg xmlns:xlink="http://www.w3.org/1999/xlink"><image xlink:href="images/bia.jpg"/></svg></div></body></html>""",
                "text/index_split_000.html" to text,
                "stylesheet.css" to css,
            ),
        )
        // Ảnh bìa không có trong file nên trang bìa rỗng sẽ bị bỏ qua, không làm lệch mục lục.
        EpubArchive(file).use { archive ->
            assertEquals("images/bia.jpg", EpubParser.readMetadata(archive).coverPath)
            val book = EpubParser.parse(archive, "x")
            assertEquals("Tác Giả", book.author)
            assertTrue("tệp dài phải được tách thành nhiều chương", book.chapters.size > 5)
            val heading = book.chapters.flatMap { it.blocks }.filterIsInstance<TextBlock>().first { it.text == "Chương 7" }
            assertEquals(BlockKind.HEADING2, heading.kind)
            val para = book.chapters[1].blocks.filterIsInstance<TextBlock>().first { it.kind == BlockKind.PARAGRAPH }
            assertTrue(para.styles.single().italic)

            val entry = book.toc[1]
            assertEquals("Chương 25", entry.title)
            val target = book.chapters[entry.chapter].let { it.blocks[it.blockIndexAt(entry.offset)] as TextBlock }
            assertEquals("Chương 25", target.text)
            assertEquals(ReadingPosition(0, 0), book.startPosition ?: ReadingPosition(0, 0))
        }
    }
}
