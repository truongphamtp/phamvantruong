package io.github.truongphamtp.docsach.data

import io.github.truongphamtp.docsach.core.BookFormat
import io.github.truongphamtp.docsach.core.BookRecord
import io.github.truongphamtp.docsach.core.Bookmark
import io.github.truongphamtp.docsach.core.HighlightColor
import io.github.truongphamtp.docsach.core.LibraryState
import io.github.truongphamtp.docsach.core.Note
import io.github.truongphamtp.docsach.core.ReadingPosition
import org.json.JSONArray
import org.json.JSONObject

/** Đọc/ghi thư viện dạng JSON (dùng cho tệp trên máy và tệp trên OneDrive). */
object LibraryJson {
    fun stateToJson(state: LibraryState): JSONObject = JSONObject().apply {
        put("version", 2)
        put("books", JSONArray().apply { state.books.forEach { put(bookToJson(it)) } })
        put("deletedBooks", JSONObject().apply { state.deletedBooks.forEach { (k, v) -> put(k, v) } })
    }

    /** Hỗ trợ cả định dạng cũ (mảng sách) lẫn định dạng mới (đối tượng có "books"). */
    fun stateFromText(text: String): LibraryState {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return LibraryState(emptyList())
        if (trimmed.startsWith("[")) return LibraryState(booksFrom(JSONArray(trimmed)))
        val root = JSONObject(trimmed)
        val deleted = root.optJSONObject("deletedBooks")
        return LibraryState(
            books = root.optJSONArray("books")?.let(::booksFrom).orEmpty(),
            deletedBooks = timesFrom(deleted),
        )
    }

    private fun booksFrom(array: JSONArray): List<BookRecord> =
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { bookFromJson(it) } }

    private fun timesFrom(obj: JSONObject?): Map<String, Long> {
        if (obj == null) return emptyMap()
        val out = HashMap<String, Long>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = obj.optLong(key)
        }
        return out
    }

    fun bookToJson(book: BookRecord): JSONObject = JSONObject().apply {
        put("id", book.id)
        put("title", book.title)
        put("author", book.author)
        put("format", book.format.name)
        put("fileName", book.fileName)
        book.coverFileName?.let { put("coverFileName", it) }
        put("sizeBytes", book.sizeBytes)
        put("fingerprint", book.fingerprint)
        put("addedAt", book.addedAt)
        put("lastOpenedAt", book.lastOpenedAt)
        put("progress", book.progress.toDouble())
        put("chapter", book.position.chapter)
        put("offset", book.position.offset)
        put("positionAt", book.positionAt)
        put("bookmarks", JSONArray().apply { book.bookmarks.forEach { put(bookmarkToJson(it)) } })
        put("notes", JSONArray().apply { book.notes.forEach { put(noteToJson(it)) } })
        put("deletedItems", JSONObject().apply { book.deletedItems.forEach { (k, v) -> put(k, v) } })
    }

    private fun bookmarkToJson(mark: Bookmark): JSONObject = JSONObject().apply {
        put("id", mark.id)
        put("chapter", mark.position.chapter)
        put("offset", mark.position.offset)
        put("label", mark.label)
        put("snippet", mark.snippet)
        put("createdAt", mark.createdAt)
    }

    private fun noteToJson(note: Note): JSONObject = JSONObject().apply {
        put("id", note.id)
        put("chapter", note.chapter)
        put("start", note.start)
        put("end", note.end)
        put("quote", note.quote)
        put("text", note.text)
        put("color", note.color.name)
        put("label", note.label)
        put("createdAt", note.createdAt)
        put("updatedAt", note.updatedAt)
    }

    private fun noteFromJson(m: JSONObject): Note? {
        val id = m.optString("id")
        if (id.isEmpty()) return null
        return Note(
            id = id,
            chapter = m.optInt("chapter"),
            start = m.optInt("start"),
            end = m.optInt("end"),
            quote = m.optString("quote"),
            text = m.optString("text"),
            color = HighlightColor.entries.firstOrNull { it.name == m.optString("color") } ?: HighlightColor.YELLOW,
            label = m.optString("label"),
            createdAt = m.optLong("createdAt"),
            updatedAt = m.optLong("updatedAt"),
        )
    }

    fun bookFromJson(o: JSONObject): BookRecord? {
        val format = BookFormat.entries.firstOrNull { it.name == o.optString("format") } ?: return null
        val id = o.optString("id")
        if (id.isEmpty()) return null
        val marks = o.optJSONArray("bookmarks")
        val notes = o.optJSONArray("notes")
        return BookRecord(
            id = id,
            title = o.optString("title"),
            author = o.optString("author"),
            format = format,
            fileName = o.optString("fileName"),
            coverFileName = if (o.has("coverFileName")) o.optString("coverFileName") else null,
            sizeBytes = o.optLong("sizeBytes"),
            fingerprint = o.optString("fingerprint"),
            addedAt = o.optLong("addedAt"),
            lastOpenedAt = o.optLong("lastOpenedAt"),
            progress = o.optDouble("progress", 0.0).toFloat(),
            position = ReadingPosition(o.optInt("chapter"), o.optInt("offset")),
            positionAt = o.optLong("positionAt"),
            bookmarks = if (marks == null) emptyList() else (0 until marks.length()).mapNotNull { i ->
                marks.optJSONObject(i)?.let { m ->
                    Bookmark(
                        id = m.optString("id"),
                        position = ReadingPosition(m.optInt("chapter"), m.optInt("offset")),
                        label = m.optString("label"),
                        snippet = m.optString("snippet"),
                        createdAt = m.optLong("createdAt"),
                    )
                }
            },
            notes = if (notes == null) emptyList() else (0 until notes.length()).mapNotNull { i ->
                notes.optJSONObject(i)?.let { noteFromJson(it) }
            },
            deletedItems = timesFrom(o.optJSONObject("deletedItems")),
        )
    }
}
