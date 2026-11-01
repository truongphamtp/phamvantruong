package io.github.truongphamtp.docsach.format

/** Tách văn bản thành các câu ngắn để đọc to và tô sáng từng câu. */
object SpeechText {
    private const val SENTENCE_END = ".!?…;"
    private const val CLOSERS = "\"'”’)]»"

    /** Trả về danh sách khoảng [start, end) trong [text], bắt đầu từ vị trí [from]. */
    fun sentences(text: String, from: Int = 0, maxLength: Int = 320): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = skipSpaces(text, from.coerceIn(0, text.length))
        var i = start
        while (i < text.length) {
            val c = text[i]
            var endHere = false
            if (c == '\n') {
                endHere = true
            } else if (c in SENTENCE_END) {
                var j = i + 1
                while (j < text.length && (text[j] in SENTENCE_END || text[j] in CLOSERS)) j++
                if (j >= text.length || text[j].isWhitespace()) {
                    i = j - 1
                    endHere = true
                }
            }
            if (!endHere && i - start + 1 >= maxLength) {
                // Câu quá dài: cắt ở dấu phẩy hoặc khoảng trắng gần nhất.
                val cut = lastBreak(text, start, i)
                if (cut > start) {
                    i = cut
                    endHere = true
                }
            }
            if (endHere) {
                val end = trimEnd(text, start, i + 1)
                if (end > start) out += start until end
                start = skipSpaces(text, i + 1)
                i = start
                continue
            }
            i++
        }
        val end = trimEnd(text, start, text.length)
        if (end > start) out += start until end
        return out
    }

    private fun lastBreak(text: String, start: Int, at: Int): Int {
        for (k in at downTo start + 1) if (text[k] == ',') return k
        for (k in at downTo start + 1) if (text[k] == ' ') return k
        return -1
    }

    private fun skipSpaces(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun trimEnd(text: String, start: Int, end: Int): Int {
        var e = end
        while (e > start && text[e - 1].isWhitespace()) e--
        return e
    }
}
