package io.github.truongphamtp.docsach

import io.github.truongphamtp.docsach.core.BookFormat
import io.github.truongphamtp.docsach.core.BookRecord
import io.github.truongphamtp.docsach.core.HighlightColor
import io.github.truongphamtp.docsach.core.LibraryState
import io.github.truongphamtp.docsach.core.Note
import io.github.truongphamtp.docsach.core.ReadingPosition
import io.github.truongphamtp.docsach.core.SyncMerge
import io.github.truongphamtp.docsach.data.LibraryJson
import io.github.truongphamtp.docsach.sync.DriveException
import io.github.truongphamtp.docsach.sync.LocalLibrary
import io.github.truongphamtp.docsach.sync.RemoteDrive
import io.github.truongphamtp.docsach.sync.RemoteItem
import io.github.truongphamtp.docsach.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/** Đồng bộ giữa hai "máy" qua một OneDrive giả lập trong bộ nhớ (có eTag như thật). */
class SyncEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val t0 = 1_800_000_000_000L
    private val drive = FakeDrive()

    private class FakeDrive : RemoteDrive {
        val files = LinkedHashMap<String, ByteArray>()
        private val etags = HashMap<String, String>()
        private var version = 0
        var beforeLibraryPut: (suspend () -> Unit)? = null
        var libraryWrites = 0
        var uploads = 0

        fun text(path: String) = files[path]?.toString(Charsets.UTF_8)
        fun library() = LibraryJson.stateFromText(text(SyncEngine.LIBRARY_FILE)!!)

        fun overwrite(path: String, bytes: ByteArray) {
            files[path] = bytes
            etags[path] = "\"v${++version}\""
        }

        override suspend fun item(path: String): RemoteItem? =
            files[path]?.let { RemoteItem(path.substringAfterLast('/'), it.size.toLong(), etags[path], "mem://$path") }

        override suspend fun children(folder: String): List<RemoteItem> =
            files.keys.filter { it.startsWith("$folder/") }.map { RemoteItem(it.substringAfterLast('/'), files[it]!!.size.toLong(), etags[it], null) }

        override suspend fun downloadText(url: String): String =
            (files[url.removePrefix("mem://")] ?: throw DriveException(404, "itemNotFound", "không có")).toString(Charsets.UTF_8)

        override suspend fun downloadTo(url: String, target: File, onProgress: (Long, Long) -> Unit) {
            val data = files[url.removePrefix("mem://")] ?: throw DriveException(404, "itemNotFound", "không có")
            target.writeBytes(data)
            onProgress(data.size.toLong(), data.size.toLong())
        }

        override suspend fun putSmall(path: String, bytes: ByteArray, contentType: String, ifMatch: String?, mustBeNew: Boolean): RemoteItem {
            if (path == SyncEngine.LIBRARY_FILE) {
                beforeLibraryPut?.let { hook ->
                    beforeLibraryPut = null
                    hook()
                }
            }
            if (mustBeNew && path in files) throw DriveException(409, "nameAlreadyExists", "đã có")
            if (ifMatch != null && etags[path] != ifMatch) throw DriveException(412, "preconditionFailed", "đã đổi")
            overwrite(path, bytes)
            if (path == SyncEngine.LIBRARY_FILE) libraryWrites++
            return item(path)!!
        }

        override suspend fun uploadFile(path: String, file: File, onProgress: (Long, Long) -> Unit) {
            overwrite(path, file.readBytes())
            uploads++
        }

        override suspend fun delete(path: String) {
            files.remove(path)
            etags.remove(path)
        }
    }

    /** Một máy: thư viện trong bộ nhớ + thư mục tệp sách, áp đồng bộ đúng như [SyncMerge.applyToLocal]. */
    private inner class Device(name: String) : LocalLibrary {
        var state = LibraryState(emptyList())
        val dir: File = tmp.newFolder(name)
        val engine = SyncEngine(drive, this)
        private var counter = 0

        override suspend fun snapshot() = state

        override suspend fun apply(merged: LibraryState, keepLocal: Set<String>, now: Long): List<BookRecord> {
            val (next, removed) = SyncMerge.applyToLocal(state, merged, keepLocal, now)
            state = next
            return removed
        }

        override fun fileOf(book: BookRecord) = File(dir, book.fileName)

        override fun newTempFile(): File = File.createTempFile("tai-ve", ".tmp", dir)

        override suspend fun adopt(downloaded: File, remote: BookRecord): BookRecord? {
            if (sha1(downloaded.readBytes()) != remote.fingerprint) return null
            val id = "${dir.name}-${++counter}"
            val file = File(dir, "$id.epub")
            downloaded.copyTo(file)
            return remote.copy(id = id, fileName = file.name, coverFileName = null)
        }

        override suspend fun deleteFiles(book: BookRecord) {
            File(dir, book.fileName).delete()
        }

        fun addBook(title: String, content: String, at: Long): BookRecord {
            val bytes = content.toByteArray()
            val id = "${dir.name}-${++counter}"
            File(dir, "$id.epub").writeBytes(bytes)
            val record = BookRecord(
                id = id, title = title, author = "", format = BookFormat.EPUB, fileName = "$id.epub", coverFileName = "$id.jpg",
                sizeBytes = bytes.size.toLong(), fingerprint = sha1(bytes), addedAt = at,
            )
            state = state.copy(books = state.books + record, deletedBooks = state.deletedBooks - record.fingerprint)
            return record
        }

        fun book(fingerprint: String) = state.books.firstOrNull { it.fingerprint == fingerprint }

        fun edit(fingerprint: String, transform: (BookRecord) -> BookRecord) {
            state = state.copy(books = state.books.map { if (it.fingerprint == fingerprint) transform(it) else it })
        }

        fun read(fingerprint: String, chapter: Int, at: Long) = edit(fingerprint) {
            it.copy(position = ReadingPosition(chapter, 0), progress = chapter / 10f, positionAt = at, lastOpenedAt = at)
        }

        fun addNote(fingerprint: String, id: String, text: String, at: Long) = edit(fingerprint) {
            it.copy(notes = it.notes + Note(id, 0, 0, 4, "trích", text, HighlightColor.YELLOW, "Chương 1", at))
        }

        fun remove(fingerprint: String, at: Long) {
            val book = book(fingerprint)!!
            File(dir, book.fileName).delete()
            state = state.copy(books = state.books - book, deletedBooks = state.deletedBooks + (fingerprint to at))
        }

        fun sync(at: Long, open: Set<String> = emptySet(), transfer: Boolean = true) = runBlocking {
            engine.sync(SyncEngine.Options(transferFiles = transfer, openBooks = { open }), clock = { at })
        }
    }

    private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Hai máy đã có chung một cuốn sách. */
    private fun twoDevicesWithBook(): Triple<Device, Device, String> {
        val a = Device("may-a")
        val b = Device("may-b")
        val fp = a.addBook("Truyện Kiều", "nội dung sách ".repeat(50), t0).fingerprint
        a.sync(t0 + 1_000)
        b.sync(t0 + 2_000)
        return Triple(a, b, fp)
    }

    @Test
    fun firstDeviceUploadsAndSecondDeviceDownloads() {
        val a = Device("may-a")
        val fp = a.addBook("Truyện Kiều", "nội dung sách ".repeat(50), t0).fingerprint
        a.read(fp, 3, t0 + 100)
        a.addNote(fp, "n1", "hay", t0 + 200)
        val first = a.sync(t0 + 1_000)
        assertEquals(1, first.uploaded)
        assertNotNull(drive.files["sach/$fp.epub"])
        // Trên mây không chứa thông tin riêng của từng máy.
        val shared = drive.library().books.single()
        assertEquals(fp, shared.id)
        assertEquals("", shared.fileName)
        assertNull(shared.coverFileName)

        val b = Device("may-b")
        val second = b.sync(t0 + 2_000)
        assertEquals(1, second.downloaded)
        val copy = b.book(fp)!!
        assertEquals(3, copy.position.chapter)
        assertEquals(listOf("n1"), copy.notes.map { it.id })
        assertNotEquals(a.book(fp)!!.id, copy.id)
        assertEquals(File(a.dir, a.book(fp)!!.fileName).readText(), File(b.dir, copy.fileName).readText())
    }

    @Test
    fun changesFlowBothWaysAndUnchangedLibraryIsNotRewritten() {
        val (a, b, fp) = twoDevicesWithBook()
        b.addNote(fp, "n2", "ghi chú trên máy B", t0 + 3_000)
        b.read(fp, 5, t0 + 3_100)
        b.sync(t0 + 4_000)
        a.sync(t0 + 5_000)
        assertEquals(listOf("n2"), a.book(fp)!!.notes.map { it.id })
        assertEquals(5, a.book(fp)!!.position.chapter)

        val writes = drive.libraryWrites
        val again = a.sync(t0 + 6_000)
        assertFalse(again.wroteLibrary)
        assertEquals(writes, drive.libraryWrites)
        assertEquals(1, drive.uploads)
    }

    @Test
    fun concurrentWriteIsRetriedAndBothChangesSurvive() {
        val (a, b, fp) = twoDevicesWithBook()
        a.addNote(fp, "na", "của A", t0 + 3_000)
        b.addNote(fp, "nb", "của B", t0 + 3_100)
        // Đúng lúc A sắp ghi thì B ghi trước → A gặp 412, đọc lại và gộp tiếp.
        drive.beforeLibraryPut = { b.engine.sync(SyncEngine.Options(), clock = { t0 + 3_500 }) }
        a.sync(t0 + 4_000)
        assertEquals(setOf("na", "nb"), drive.library().books.single().notes.map { it.id }.toSet())
        assertEquals(setOf("na", "nb"), a.book(fp)!!.notes.map { it.id }.toSet())
        b.sync(t0 + 5_000)
        assertEquals(setOf("na", "nb"), b.book(fp)!!.notes.map { it.id }.toSet())
    }

    @Test
    fun deletedNoteAndBookPropagateAndRemoteFileIsRemoved() {
        val (a, b, fp) = twoDevicesWithBook()
        a.addNote(fp, "n1", "sẽ xoá", t0 + 3_000)
        a.sync(t0 + 3_500)
        b.sync(t0 + 3_600)
        assertEquals(1, b.book(fp)!!.notes.size)
        // Xoá ghi chú trên B (ghi lại thời điểm xoá như LibraryStore.updateNotes).
        b.edit(fp) { it.copy(notes = emptyList(), deletedItems = it.deletedItems + ("n1" to t0 + 3_700)) }
        b.sync(t0 + 3_800)
        a.sync(t0 + 3_900)
        assertTrue(a.book(fp)!!.notes.isEmpty())

        a.remove(fp, t0 + 4_000)
        a.sync(t0 + 4_100)
        assertNull(drive.files["sach/$fp.epub"])
        val result = b.sync(t0 + 5_000)
        assertEquals(listOf(fp), result.removed.map { it.fingerprint })
        assertNull(b.book(fp))
        assertFalse(File(b.dir, result.removed.single().fileName).exists())
    }

    @Test
    fun openBookIsLeftAloneAndNewerPositionIsOffered() {
        val (a, b, fp) = twoDevicesWithBook()
        b.read(fp, 8, t0 + 3_000)
        b.addNote(fp, "nb", "B ghi khi A đang đọc", t0 + 3_000)
        b.sync(t0 + 3_500)
        // A đang mở sách ở chương 2 (mở lúc sau nhưng chưa lật trang).
        a.read(fp, 2, t0 + 500)
        a.edit(fp) { it.copy(lastOpenedAt = t0 + 4_000) }
        val result = a.sync(t0 + 4_100, open = setOf(fp))
        assertEquals(2, a.book(fp)!!.position.chapter)
        assertTrue(a.book(fp)!!.notes.isEmpty())
        assertEquals(8, result.offers[fp]!!.position.chapter)
        // Đóng sách rồi đồng bộ: ghi chú của B về tới A.
        a.sync(t0 + 5_000)
        assertEquals(listOf("nb"), a.book(fp)!!.notes.map { it.id })
    }

    @Test
    fun wifiOnlyDefersFilesButNotTheLibrary() {
        val a = Device("may-a")
        val b = Device("may-b")
        val fp = a.addBook("Sách mới", "abc ".repeat(100), t0).fingerprint
        val noWifi = a.sync(t0 + 1_000, transfer = false)
        assertEquals(1, noWifi.waitingUploads)
        assertNull(drive.files["sach/$fp.epub"])
        assertEquals(listOf(fp), drive.library().books.map { it.fingerprint })

        b.sync(t0 + 1_500, transfer = false)
        assertNull(b.book(fp))
        a.sync(t0 + 2_000)
        val waiting = b.sync(t0 + 2_500, transfer = false)
        assertEquals(1, waiting.waitingDownloads)
        assertNull(b.book(fp))
        assertEquals(1, b.sync(t0 + 3_000).downloaded)
        assertNotNull(b.book(fp))
    }

    @Test
    fun brokenRemoteLibraryIsRepairedAndBadDownloadIsRejected() {
        val a = Device("may-a")
        val fp = a.addBook("Sách", "nội dung ".repeat(30), t0).fingerprint
        drive.overwrite(SyncEngine.LIBRARY_FILE, "không phải JSON".toByteArray())
        a.sync(t0 + 1_000)
        assertEquals(listOf(fp), drive.library().books.map { it.fingerprint })

        // Tệp trên mây bị hỏng (không khớp dấu vân tay) thì không nhận.
        drive.overwrite("sach/$fp.epub", "hỏng".toByteArray())
        val b = Device("may-b")
        val result = b.sync(t0 + 2_000)
        assertEquals(listOf("Sách"), result.failed)
        assertNull(b.book(fp))
        assertEquals(0, b.dir.listFiles()!!.count { it.name.endsWith(".epub") })
    }
}
