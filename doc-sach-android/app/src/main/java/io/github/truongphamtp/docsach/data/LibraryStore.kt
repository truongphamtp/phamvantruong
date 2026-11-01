package io.github.truongphamtp.docsach.data

import android.util.Log
import io.github.truongphamtp.docsach.core.BookRecord
import io.github.truongphamtp.docsach.core.Bookmark
import io.github.truongphamtp.docsach.core.LibraryState
import io.github.truongphamtp.docsach.core.Note
import io.github.truongphamtp.docsach.core.ReadingPosition
import io.github.truongphamtp.docsach.core.SyncMerge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Danh sách sách trong thư viện, lưu thành tệp JSON trong bộ nhớ riêng của ứng dụng. */
class LibraryStore(private val file: File, scope: CoroutineScope) {
    /** Loại thay đổi do người dùng làm (để quyết định đồng bộ sớm hay muộn). */
    enum class Change { POSITION, CONTENT }

    private val _state = MutableStateFlow(load())
    private val _books = MutableStateFlow(_state.value.books)
    val books: StateFlow<List<BookRecord>> = _books.asStateFlow()

    private val _changes = MutableSharedFlow<Change>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Các thay đổi trên máy này (không gồm thay đổi do đồng bộ mang về). */
    val changes: SharedFlow<Change> = _changes.asSharedFlow()

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

    /** Ảnh chụp toàn bộ thư viện (dùng khi đồng bộ). */
    fun snapshot(): LibraryState = _state.value

    fun add(record: BookRecord) = changeState(Change.CONTENT) { state ->
        state.copy(
            books = state.books.filter { it.id != record.id } + record,
            // Thêm lại sách từng xoá: bỏ dấu "đã xoá".
            deletedBooks = state.deletedBooks - record.fingerprint,
        )
    }

    fun remove(id: String) = changeState(Change.CONTENT) { state ->
        val book = state.books.firstOrNull { it.id == id }
        state.copy(
            books = state.books.filter { it.id != id },
            deletedBooks = if (book == null) state.deletedBooks else state.deletedBooks + (book.fingerprint to System.currentTimeMillis()),
        )
    }

    fun updateBook(id: String, change: Change = Change.CONTENT, transform: (BookRecord) -> BookRecord) = changeState(change) { state ->
        state.copy(books = state.books.map { if (it.id == id) transform(it) else it })
    }

    fun markOpened(id: String) = updateBook(id, Change.POSITION) { it.copy(lastOpenedAt = System.currentTimeMillis()) }

    fun updatePosition(id: String, position: ReadingPosition, progress: Float) = updateBook(id, Change.POSITION) {
        val now = System.currentTimeMillis()
        it.copy(
            position = position,
            progress = progress.coerceIn(0f, 1f),
            lastOpenedAt = now,
            // Chỉ tính là "đã đọc tới đây" khi vị trí thật sự đổi (lật trang, nhảy chương...).
            positionAt = if (position != it.position) now else it.positionAt,
        )
    }

    /** Lưu dấu trang; dấu trang bị bỏ được ghi lại để đồng bộ việc xoá. */
    fun updateBookmarks(id: String, bookmarks: List<Bookmark>) = updateBook(id) { book ->
        val now = System.currentTimeMillis()
        val keep = bookmarks.map { it.id }.toSet()
        val removed = book.bookmarks.filter { it.id !in keep }.associate { it.id to now }
        book.copy(bookmarks = bookmarks, deletedItems = book.deletedItems + removed)
    }

    /** Lưu ghi chú; ghi chú bị sửa được đánh dấu thời gian, bị xoá thì ghi lại để đồng bộ. */
    fun updateNotes(id: String, notes: List<Note>) = updateBook(id) { book ->
        val now = System.currentTimeMillis()
        val old = book.notes.associateBy { it.id }
        val stamped = notes.map { note ->
            val before = old[note.id]
            if (before != null && before.copy(updatedAt = 0L) != note.copy(updatedAt = 0L)) note.copy(updatedAt = now) else note
        }
        val keep = notes.map { it.id }.toSet()
        val removed = book.notes.filter { it.id !in keep }.associate { it.id to now }
        book.copy(notes = stamped, deletedItems = book.deletedItems + removed)
    }

    /**
     * Áp kết quả đồng bộ (xem [SyncMerge.applyToLocal]); không tính là thay đổi của người dùng.
     *
     * @return những cuốn bị xoá khỏi máy này (để xoá tệp).
     */
    fun applySync(merged: LibraryState, keepLocal: Set<String>, now: Long): List<BookRecord> {
        var removed: List<BookRecord> = emptyList()
        _state.update { current ->
            val (next, gone) = SyncMerge.applyToLocal(current, merged, keepLocal, now)
            removed = gone
            next
        }
        _books.value = _state.value.books
        saveRequests.tryEmit(Unit)
        return removed
    }

    private fun changeState(change: Change, transform: (LibraryState) -> LibraryState) {
        _state.update(transform)
        _books.value = _state.value.books
        saveRequests.tryEmit(Unit)
        _changes.tryEmit(change)
    }

    /** Ghi ngay xuống tệp (ghi ra tệp tạm rồi đổi tên để không hỏng dữ liệu khi tắt máy đột ngột). */
    fun saveNow() {
        val json = LibraryJson.stateToJson(_state.value).toString()
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

    private fun load(): LibraryState {
        if (!file.exists()) return LibraryState(emptyList())
        return try {
            LibraryJson.stateFromText(file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Tệp thư viện bị lỗi, bắt đầu lại", e)
            file.copyTo(File(file.parentFile, file.name + ".loi"), overwrite = true)
            LibraryState(emptyList())
        }
    }

    private companion object {
        const val TAG = "LibraryStore"
    }
}
