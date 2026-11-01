package io.github.truongphamtp.docsach.format

import java.text.Normalizer

/** Bỏ dấu tiếng Việt để tìm kiếm: "Tiếng Việt" -> "tieng viet". */
object TextFold {
    private const val TABLE_SIZE = 0x2000
    private const val REMOVED = '\u0000'

    private val table: CharArray by lazy { CharArray(TABLE_SIZE) { foldUncached(it.toChar()) } }

    private fun foldUncached(c: Char): Char {
        if (c == 'đ' || c == 'Đ') return 'd'
        if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) return REMOVED
        val decomposed = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
        for (ch in decomposed) {
            if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) return ch.lowercaseChar()
        }
        return REMOVED
    }

    /** Trả về ký tự đã bỏ dấu và viết thường, hoặc '\u0000' nếu là dấu thanh rời. */
    fun fold(c: Char): Char = if (c.code < TABLE_SIZE) table[c.code] else c.lowercaseChar()

    fun foldString(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val f = fold(c)
            if (f != REMOVED) sb.append(f)
        }
        return sb.toString()
    }

    fun hasDiacritics(s: String): Boolean = s.any { c ->
        val f = fold(c)
        f != c.lowercaseChar()
    }

    /** Chuỗi đã bỏ dấu kèm bảng ánh xạ vị trí về chuỗi gốc. */
    class Mapped(val text: String, val map: IntArray)

    fun foldWithMap(s: String, removeDiacritics: Boolean): Mapped {
        val sb = StringBuilder(s.length)
        val map = IntArray(s.length)
        var n = 0
        for ((i, c) in s.withIndex()) {
            val f = if (removeDiacritics) fold(c) else c.lowercaseChar()
            if (f == REMOVED) continue
            sb.append(f)
            map[n++] = i
        }
        return Mapped(sb.toString(), map.copyOf(n))
    }
}

data class SearchHit(
    val chapter: Int,
    val start: Int,
    val end: Int,
    val snippet: String,
    val matchStart: Int,
    val matchEnd: Int,
)

object BookSearch {
    /**
     * Tìm [query] trong toàn bộ sách. Nếu người dùng gõ không dấu thì tìm cả chữ có dấu
     * ("tieng" khớp "tiếng"); nếu gõ có dấu thì chỉ khớp đúng dấu.
     */
    fun search(
        book: BookContent,
        query: String,
        limit: Int = 300,
        isCancelled: () -> Boolean = { false },
    ): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val insensitive = !TextFold.hasDiacritics(q)
        val needle = if (insensitive) TextFold.foldString(q) else q.map { it.lowercaseChar() }.joinToString("")
        if (needle.isEmpty()) return emptyList()
        val hits = ArrayList<SearchHit>()
        for ((ci, chapter) in book.chapters.withIndex()) {
            if (isCancelled()) return hits
            for (block in chapter.blocks) {
                if (block !is TextBlock) continue
                val mapped = TextFold.foldWithMap(block.text, insensitive)
                var idx = mapped.text.indexOf(needle)
                while (idx >= 0) {
                    val s = mapped.map[idx]
                    val e = mapped.map[idx + needle.length - 1] + 1
                    hits += makeHit(ci, block, s, e)
                    if (hits.size >= limit) return hits
                    idx = mapped.text.indexOf(needle, idx + needle.length)
                }
            }
        }
        return hits
    }

    private fun makeHit(chapter: Int, block: TextBlock, s: Int, e: Int): SearchHit {
        val text = block.text
        var from = (s - 40).coerceAtLeast(0)
        var to = (e + 70).coerceAtMost(text.length)
        // Tránh cắt giữa từ.
        if (from > 0) text.indexOf(' ', from).takeIf { it in from until s }?.let { from = it + 1 }
        if (to < text.length) text.lastIndexOf(' ', to).takeIf { it > e }?.let { to = it }
        val prefix = if (from > 0) "…" else ""
        val suffix = if (to < text.length) "…" else ""
        val snippet = prefix + text.substring(from, to).replace('\n', ' ') + suffix
        val ms = prefix.length + (s - from)
        val me = ms + (e - s)
        return SearchHit(chapter, block.start + s, block.start + e, snippet, ms, me)
    }
}
