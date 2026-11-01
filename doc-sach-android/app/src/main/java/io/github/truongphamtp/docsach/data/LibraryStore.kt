package io.github.truongphamtp.docsach.data

import android.util.Log
import io.github.truongphamtp.docsach.core.BookFormat
import io.github.truongphamtp.docsach.core.BookRecord
import io.github.truongphamtp.docsach.core.Bookmark
import io.github.truongphamtp.docsach.core.HighlightColor
import io.github.truongphamtp.docsach.core.Note
import io.github.truongphamtp.docsach.core.ReadingPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Danh sách sách trong thư viện, lưu thành tệp JSON trong bộ nhớ riêng của ứng dụng. */
class LibraryStore(private val file: File, scope: CoroutineScope) {
    private val _books = MutableStateFlow(load())
    val books: StateFlow<List<BookRecord>> = _books.asStateFlow()

    private val saveRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val lock = Any()

    init {
        scope.launch {
            saveRequests.collectLatest {
                delay(400)
                withContext(Dispatchers.IO) { saveNow() }
            }
        }
    }

    fun get(id: String): BookRecord? = _books.value.firstOrNull { it.id == id }

    fun add(record: BookRecord) = change { list -> list.filter { it.id != record.id } + record }

    fun remove(id: String) = change { list -> list.filter { it.id != id } }

    fun updateBook(id: String, transform: (BookRecord) -> BookRecord) =
        change { list -> list.map { if (it.id == id) transform(it) else it } }

    fun markOpened(id: String) = updateBook(id) { it.copy(lastOpenedAt = System.currentTimeMillis()) }

    fun updatePosition(id: String, position: ReadingPosition, progress: Float) = updateBook(id) {
        it.copy(position = position, progress = progress.coerceIn(0f, 1f), lastOpenedAt = System.currentTimeMillis())
    }

    fun updateBookmarks(id: String, bookmarks: List<Bookmark>) = updateBook(id) { it.copy(bookmarks = bookmarks) }

    fun updateNotes(id: String, notes: List<Note>) = updateBook(id) { it.copy(notes = notes) }

    private fun change(transform: (List<BookRecord>) -> List<BookRecord>) {
        _books.update(transform)
        saveRequests.tryEmit(Unit)
    }

    /** Ghi ngay xuống tệp (ghi ra tệp tạm rồi đổi tên để không hỏng dữ liệu khi tắt máy đột ngột). */
    fun saveNow() {
        val json = JSONArray().apply { _books.value.forEach { put(it.toJson()) } }.toString()
        synchronized(lock) {
            try {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Không lưu được thư viện", e)
            }
        }
    }

    private fun load(): List<BookRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::bookFromJson) }
        } catch (e: Exception) {
            Log.e(TAG, "Tệp thư viện bị lỗi, bắt đầu lại", e)
            file.copyTo(File(file.parentFile, file.name + ".loi"), overwrite = true)
            emptyList()
        }
    }

    private companion object {
        const val TAG = "LibraryStore"

        fun BookRecord.toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("title", title)
            put("author", author)
            put("format", format.name)
            put("fileName", fileName)
            coverFileName?.let { put("coverFileName", it) }
            put("sizeBytes", sizeBytes)
            put("fingerprint", fingerprint)
            put("addedAt", addedAt)
            put("lastOpenedAt", lastOpenedAt)
            put("progress", progress.toDouble())
            put("chapter", position.chapter)
            put("offset", position.offset)
            put("bookmarks", JSONArray().apply { bookmarks.forEach { put(it.toJson()) } })
            put("notes", JSONArray().apply { notes.forEach { put(it.toJson()) } })
        }

        fun Note.toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("chapter", chapter)
            put("start", start)
            put("end", end)
            put("quote", quote)
            put("text", text)
            put("color", color.name)
            put("label", label)
            put("createdAt", createdAt)
        }

        fun noteFromJson(m: JSONObject): Note? {
            val id = m.optString("id").ifEmpty { return null }
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
            )
        }

        fun Bookmark.toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("chapter", position.chapter)
            put("offset", position.offset)
            put("label", label)
            put("snippet", snippet)
            put("createdAt", createdAt)
        }

        fun bookFromJson(o: JSONObject): BookRecord? {
            val format = BookFormat.entries.firstOrNull { it.name == o.optString("format") } ?: return null
            val id = o.optString("id").ifEmpty { return null }
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
            )
        }
    }
}
