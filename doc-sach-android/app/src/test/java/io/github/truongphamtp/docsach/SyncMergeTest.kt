package io.github.truongphamtp.docsach

import io.github.truongphamtp.docsach.core.BookFormat
import io.github.truongphamtp.docsach.core.BookRecord
import io.github.truongphamtp.docsach.core.Bookmark
import io.github.truongphamtp.docsach.core.HighlightColor
import io.github.truongphamtp.docsach.core.LibraryState
import io.github.truongphamtp.docsach.core.Note
import io.github.truongphamtp.docsach.core.ReadingPosition
import io.github.truongphamtp.docsach.core.SyncMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {
    private val now = 1_800_000_000_000L

    /** Mốc thời gian gần hiện tại (giây trước [now]). */
    private fun t(secondsBefore: Long) = now - secondsBefore * 1000

    private fun book(fp: String, id: String = "id-$fp", opened: Long = 0L, added: Long = 100L, chapter: Int = 0, notes: List<Note> = emptyList(), marks: List<Bookmark> = emptyList(), deleted: Map<String, Long> = emptyMap()) =
        BookRecord(
            id = id, title = "Sách $fp", author = "", format = BookFormat.EPUB, fileName = "$id.epub", coverFileName = null,
            sizeBytes = 10, fingerprint = fp, addedAt = added, lastOpenedAt = opened, progress = chapter / 10f,
            position = ReadingPosition(chapter, 0), bookmarks = marks, notes = notes, deletedItems = deleted,
        )

    private fun note(id: String, created: Long, updated: Long = 0L, text: String = "") =
        Note(id, 0, 0, 5, "trích", text, HighlightColor.YELLOW, "Chương 1", created, updated)

    @Test
    fun newerReadingPositionWinsAndLocalFieldsAreKept() {
        val local = book("a", id = "may-1", opened = 1_000, chapter = 2)
        val remote = book("a", id = "may-2", opened = 2_000, chapter = 7)
        val merged = SyncMerge.merge(LibraryState(listOf(local)), LibraryState(listOf(remote)), now).books.single()
        assertEquals(7, merged.position.chapter)
        assertEquals("may-1", merged.id)
        assertEquals("may-1.epub", merged.fileName)
        assertEquals(2_000, merged.lastOpenedAt)
    }

    @Test
    fun notesAreUnitedAndNewestEditWins() {
        val local = book("a", notes = listOf(note("n1", 10, text = "cũ"), note("n2", 20)))
        val remote = book("a", notes = listOf(note("n1", 10, updated = 50, text = "mới"), note("n3", 30)))
        val merged = SyncMerge.merge(LibraryState(listOf(local)), LibraryState(listOf(remote)), now).books.single()
        assertEquals(setOf("n1", "n2", "n3"), merged.notes.map { it.id }.toSet())
        assertEquals("mới", merged.notes.first { it.id == "n1" }.text)
    }

    @Test
    fun deletedNotesAndBookmarksStayDeleted() {
        val mark = Bookmark("b1", ReadingPosition(0, 0), "C1", "", t(900))
        val local = book("a", notes = emptyList(), marks = emptyList(), deleted = mapOf("n1" to t(500), "b1" to t(500)))
        val remote = book("a", notes = listOf(note("n1", t(900))), marks = listOf(mark))
        val merged = SyncMerge.merge(LibraryState(listOf(local)), LibraryState(listOf(remote)), now).books.single()
        assertTrue(merged.notes.isEmpty())
        assertTrue(merged.bookmarks.isEmpty())
        // Ghi chú được sửa ở máy khác SAU khi bị xoá thì vẫn giữ lại.
        val remoteEdited = book("a", notes = listOf(note("n1", t(900), updated = t(100))))
        val kept = SyncMerge.merge(LibraryState(listOf(local)), LibraryState(listOf(remoteEdited)), now).books.single()
        assertEquals(listOf("n1"), kept.notes.map { it.id })
    }

    @Test
    fun deletedBooksPropagateUnlessReadAgainLater() {
        val remote = LibraryState(listOf(book("a", opened = t(900)), book("b", opened = t(900))))
        val local = LibraryState(listOf(book("b", opened = t(100))), deletedBooks = mapOf("a" to t(500), "b" to t(500)))
        val merged = SyncMerge.merge(local, remote, now)
        assertEquals(listOf("b"), merged.books.map { it.fingerprint })
        assertTrue("a" in merged.deletedBooks)
        assertFalse("b" in merged.deletedBooks)
    }

    @Test
    fun openingWithoutTurningPagesDoesNotOverrideNewerPosition() {
        // Máy tính bảng đọc tới chương 7; điện thoại mở sách sau đó nhưng chưa lật trang (vẫn ở chương 2 cũ).
        val tablet = book("a", opened = t(600), chapter = 7).copy(positionAt = t(600))
        val phone = book("a", opened = t(60), chapter = 2).copy(positionAt = t(3000))
        val merged = SyncMerge.merge(LibraryState(listOf(phone)), LibraryState(listOf(tablet)), now).books.single()
        assertEquals(7, merged.position.chapter)
        assertEquals(t(600), merged.positionAt)
        assertEquals(t(60), merged.lastOpenedAt)
        // Lật trang trên điện thoại sau đó thì điện thoại thắng.
        val turned = phone.copy(position = ReadingPosition(3, 0), positionAt = t(30))
        val again = SyncMerge.merge(LibraryState(listOf(turned)), LibraryState(listOf(tablet)), now).books.single()
        assertEquals(3, again.position.chapter)
    }

    @Test
    fun portableFormIgnoresDeviceFieldsAndOrder() {
        val a = book("a", id = "may-1", notes = listOf(note("n2", 2), note("n1", 1)))
        val b = book("a", id = "may-2", notes = listOf(note("n1", 1), note("n2", 2))).copy(coverFileName = "bia.jpg")
        assertEquals(SyncMerge.portable(LibraryState(listOf(a))), SyncMerge.portable(LibraryState(listOf(b))))
        assertEquals("a.epub", SyncMerge.remoteFileName(a))
    }

    @Test
    fun mergingAgainIsStable() {
        val local = LibraryState(
            listOf(book("a", opened = t(100), notes = listOf(note("n1", t(400)))), book("b", opened = t(50))),
            deletedBooks = mapOf("c" to t(300)),
        )
        val remote = LibraryState(
            listOf(book("a", opened = t(200), chapter = 4, notes = listOf(note("n2", t(500)))), book("c", opened = t(900))),
        )
        val once = SyncMerge.merge(local, remote, now)
        val twice = SyncMerge.merge(once, remote, now)
        val reverse = SyncMerge.merge(remote, local, now)
        assertEquals(SyncMerge.portable(once), SyncMerge.portable(twice))
        assertEquals(SyncMerge.portable(once), SyncMerge.portable(reverse))
        assertEquals(listOf("a", "b"), SyncMerge.portable(once).books.map { it.fingerprint })
    }

    @Test
    fun remoteOnlyBooksAreAddedAndOldTombstonesExpire() {
        val local = LibraryState(emptyList(), deletedBooks = mapOf("old" to now - SyncMerge.TOMBSTONE_TTL_MS - 1))
        val remote = LibraryState(listOf(book("x")))
        val merged = SyncMerge.merge(local, remote, now)
        assertEquals(listOf("x"), merged.books.map { it.fingerprint })
        assertTrue(merged.deletedBooks.isEmpty())
    }
}
