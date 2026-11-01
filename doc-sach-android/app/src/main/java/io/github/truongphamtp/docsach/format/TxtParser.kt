package io.github.truongphamtp.docsach.format

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

/** Đọc file .txt: tự nhận bảng mã và tự tách chương ("Chương 1", "Hồi thứ hai", "Chapter 3"...). */
object TxtParser {
    private const val MAX_PART_CHARS = 12_000

    private val numberWords = listOf(
        "[0-9]+", "[ivxlcdm]+", "một", "hai", "ba", "bốn", "tư", "năm", "sáu", "bảy", "tám", "chín",
        "mười", "mươi", "trăm", "nhất", "cuối", "one", "two", "three", "four", "five", "six", "seven",
        "eight", "nine", "ten",
    ).joinToString("|")

    private val headingRegex = Regex(
        "^(?:chương|chuong|hồi|hoi|phần|phan|quyển|quyen|tập|tap|tiết|chapter|part|book|volume)" +
            "\\s*(?:thứ\\s+)?(?:$numberWords)(?=$|[\\s:.,;\\-–—)\\]])",
        RegexOption.IGNORE_CASE,
    )

    private val specialHeadingRegex = Regex(
        "^(?:lời mở đầu|lời nói đầu|lời giới thiệu|lời tựa|mở đầu|phần kết|kết thúc|vĩ thanh|phụ lục|" +
            "prologue|epilogue|preface|introduction)(?=$|[\\s:.,;\\-–—])",
        RegexOption.IGNORE_CASE,
    )

    fun isHeading(line: String): Boolean {
        val l = line.trim()
        if (l.isEmpty() || l.length > 80) return false
        return headingRegex.containsMatchIn(l) || specialHeadingRegex.containsMatchIn(l)
    }

    /** Giải mã nội dung theo BOM, UTF-8, rồi tới bảng mã Windows tiếng Việt. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            // Không phải UTF-8 hợp lệ: thử bảng mã cũ.
        }
        for (name in listOf("windows-1258", "windows-1252")) {
            val charset = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            return String(bytes, charset)
        }
        return String(bytes, Charsets.ISO_8859_1)
    }

    fun parse(rawText: String, title: String): BookContent {
        val text = Normalizer.normalize(rawText.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC)
        val lines = text.split('\n')
        val headingIndexes = lines.indices.filter { isHeading(lines[it]) }

        val chapters = ArrayList<Chapter>()
        if (headingIndexes.size >= 2) {
            val first = headingIndexes.first()
            val preface = lines.subList(0, first)
            if (preface.any { it.isNotBlank() }) chapters += buildChapter(title, null, preface)
            var orphanHeadings = ArrayList<String>()
            for ((k, start) in headingIndexes.withIndex()) {
                val end = if (k + 1 < headingIndexes.size) headingIndexes[k + 1] else lines.size
                val heading = lines[start].trim()
                val body = lines.subList(start + 1, end)
                if (body.none { it.isNotBlank() }) {
                    // Dòng tiêu đề đứng liền nhau (thường là mục lục đầu sách): gộp lại, không tách chương rỗng.
                    orphanHeadings += heading
                    continue
                }
                var extraHeadings: List<String> = emptyList()
                if (orphanHeadings.size in 1..2) {
                    // Ví dụ "Phần 1" ngay trước "Chương 1": giữ làm tiêu đề lớn của chương sau.
                    extraHeadings = orphanHeadings
                } else if (orphanHeadings.isNotEmpty()) {
                    chapters += buildChapter("Mục lục", null, orphanHeadings)
                }
                orphanHeadings = ArrayList()
                chapters += buildChapter(heading, heading, body, extraHeadings)
            }
            if (orphanHeadings.isNotEmpty()) chapters += buildChapter("Mục lục", null, orphanHeadings)
        } else {
            val parts = ArrayList<List<String>>()
            var current = ArrayList<String>()
            var size = 0
            for (line in lines) {
                if (line.isBlank()) continue
                current += line
                size += line.length
                if (size >= MAX_PART_CHARS) {
                    parts += current
                    current = ArrayList()
                    size = 0
                }
            }
            if (current.isNotEmpty() || parts.isEmpty()) parts += current
            for ((i, part) in parts.withIndex()) {
                val partTitle = if (parts.size == 1) title else "Phần ${i + 1}"
                chapters += buildChapter(partTitle, null, part)
            }
        }
        if (chapters.isEmpty() || chapters.all { it.blocks.isEmpty() }) {
            chapters.clear()
            chapters += Chapter(title, listOf(TextBlock(0, "(Tệp văn bản trống)", BlockKind.CENTER)))
        }
        val toc = chapters.mapIndexed { i, c -> TocEntry(c.title, i, 0, 0) }
        return BookContent(title, "", chapters, toc)
    }

    private fun buildChapter(
        title: String,
        heading: String?,
        lines: List<String>,
        extraHeadings: List<String> = emptyList(),
    ): Chapter {
        val blocks = ArrayList<Block>()
        var offset = 0
        for (extra in extraHeadings) {
            blocks += TextBlock(offset, extra, BlockKind.HEADING1)
            offset += extra.length + 1
        }
        if (heading != null) {
            blocks += TextBlock(offset, heading, BlockKind.HEADING2)
            offset += heading.length + 1
        }
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            blocks += TextBlock(offset, line, BlockKind.PARAGRAPH)
            offset += line.length + 1
        }
        return Chapter(title, blocks)
    }
}
