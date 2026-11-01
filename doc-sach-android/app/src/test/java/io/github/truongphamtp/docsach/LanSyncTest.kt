package io.github.truongphamtp.docsach

import io.github.truongphamtp.docsach.core.BookFormat
import io.github.truongphamtp.docsach.core.BookRecord
import io.github.truongphamtp.docsach.core.HighlightColor
import io.github.truongphamtp.docsach.core.LibraryState
import io.github.truongphamtp.docsach.core.Note
import io.github.truongphamtp.docsach.core.ReadingPosition
import io.github.truongphamtp.docsach.core.SyncMerge
import io.github.truongphamtp.docsach.sync.DriveException
import io.github.truongphamtp.docsach.sync.LocalLibrary
import io.github.truongphamtp.docsach.sync.RemoteDrive
import io.github.truongphamtp.docsach.sync.SyncEngine
import io.github.truongphamtp.docsach.sync.lan.LanDrive
import io.github.truongphamtp.docsach.sync.lan.LanHost
import io.github.truongphamtp.docsach.sync.lan.LanHostEvents
import io.github.truongphamtp.docsach.sync.lan.LanHttpClient
import io.github.truongphamtp.docsach.sync.lan.LanHttpServer
import io.github.truongphamtp.docsach.sync.lan.LanPairing
import io.github.truongphamtp.docsach.sync.lan.LanPeer
import io.github.truongphamtp.docsach.sync.lan.LanPeerStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Hai "máy" đồng bộ trực tiếp qua mạng (máy chủ HTTP thật chạy trên máy tính). */
class LanSyncTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val t0 = 1_800_000_000_000L
    private val servers = ArrayList<LanHttpServer>()

    @After
    fun stop() {
        servers.forEach { it.close() }
    }

    private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Một máy: thư viện trong bộ nhớ, thư mục tệp sách, danh sách máy đã ghép đôi. */
    private inner class Device(val name: String) : LocalLibrary, LanPeerStore {
        val id = UUID.randomUUID().toString()
        @Volatile var state = LibraryState(emptyList())
        val dir: File = tmp.newFolder(name)
        val keys = HashMap<String, ByteArray>()
        val names = HashMap<String, String>()
        var open: Set<String> = emptySet()
        val applied = ArrayList<LanHost.Applied>()
        private var counter = 0

        // --- LocalLibrary
        override suspend fun snapshot() = state

        override suspend fun apply(merged: LibraryState, keepLocal: Set<String>, now: Long): List<BookRecord> = synchronized(this) {
            val (next, removed) = SyncMerge.applyToLocal(state, merged, keepLocal, now)
            state = next
            removed
        }

        override fun fileOf(book: BookRecord) = File(dir, book.fileName)

        override fun newTempFile(): File = File.createTempFile("nhan", ".tmp", dir)

        override suspend fun adopt(downloaded: File, remote: BookRecord): BookRecord? {
            if (sha1(downloaded.readBytes()) != remote.fingerprint) return null
            val newId = "$name-${++counter}"
            val file = File(dir, "$newId.epub")
            downloaded.copyTo(file)
            return remote.copy(id = newId, fileName = file.name, coverFileName = null)
        }

        override suspend fun deleteFiles(book: BookRecord) {
            File(dir, book.fileName).delete()
        }

        // --- LanPeerStore
        override fun keyOf(peerId: String) = synchronized(keys) { keys[peerId] }

        override fun nameOf(peerId: String) = synchronized(keys) { names[peerId] }

        override fun save(peer: LanPeer, key: ByteArray) {
            synchronized(keys) {
                keys[peer.id] = key
                names[peer.id] = peer.name
            }
        }

        // --- tiện ích
        fun addBook(title: String, content: String, at: Long): String {
            val bytes = content.toByteArray()
            val bookId = "$name-${++counter}"
            File(dir, "$bookId.epub").writeBytes(bytes)
            val record = BookRecord(bookId, title, "", BookFormat.EPUB, "$bookId.epub", null, bytes.size.toLong(), sha1(bytes), at)
            state = state.copy(books = state.books + record)
            return record.fingerprint
        }

        fun book(fp: String) = state.books.firstOrNull { it.fingerprint == fp }

        fun edit(fp: String, transform: (BookRecord) -> BookRecord) {
            state = state.copy(books = state.books.map { if (it.fingerprint == fp) transform(it) else it })
        }

        fun addNote(fp: String, noteId: String, at: Long) = edit(fp) {
            it.copy(notes = it.notes + Note(noteId, 0, 0, 4, "trích", "", HighlightColor.BLUE, "Chương 1", at))
        }

        fun read(fp: String, chapter: Int, at: Long) = edit(fp) {
            it.copy(position = ReadingPosition(chapter, 0), progress = chapter / 10f, positionAt = at, lastOpenedAt = at)
        }

        /** Bật máy chủ của máy này. */
        fun serve(): Pair<LanHost, String> {
            val host = LanHost(
                myId = id,
                myName = { name },
                peers = this,
                local = this,
                stashDir = File(dir, ".nhan"),
                openBooks = { open },
                answeredOffers = { emptyMap() },
                events = object : LanHostEvents {
                    override fun onApplied(peer: LanPeer, applied: LanHost.Applied) {
                        synchronized(this@Device.applied) { this@Device.applied += applied }
                    }
                },
            )
            val server = LanHttpServer(host::handle)
            servers += server
            return host to "http://127.0.0.1:${server.port}"
        }

        fun syncWith(drive: RemoteDrive, at: Long) = runBlocking {
            SyncEngine(drive, this@Device).sync(SyncEngine.Options(openBooks = { open }), clock = { at })
        }
    }

    /** A làm máy chủ, B ghép đôi với A bằng mã đang hiện trên A. */
    private fun pairedDevices(): Triple<Device, Device, LanDrive> {
        val a = Device("may-a")
        val b = Device("may-b")
        val (host, url) = a.serve()
        val code = host.startPairing()
        val result = runBlocking { LanPairing.pair(url, b.id, b.name, code) }
        b.save(LanPeer(result.peerId, result.peerName), result.key)
        assertEquals(a.id, result.peerId)
        assertArrayEquals(result.key, a.keyOf(b.id))
        assertFalse(host.isPairing)
        return Triple(a, b, LanDrive(url, b.id, result.key))
    }

    @Test
    fun pairedDevicesExchangeBooksNotesAndPositionsBothWays() {
        val (a, b, drive) = pairedDevices()
        val x = a.addBook("Truyện Kiều", "nội dung X ".repeat(200), t0)
        a.addNote(x, "na", t0 + 10)
        val y = b.addBook("Giáo án", "nội dung Y ".repeat(300), t0)
        b.read(y, 4, t0 + 20)

        val result = b.syncWith(drive, t0 + 1_000)
        assertEquals(1, result.uploaded)
        assertEquals(1, result.downloaded)
        // Máy B có cả hai cuốn, ghi chú của A đi theo sách.
        assertEquals(setOf(x, y), b.state.books.map { it.fingerprint }.toSet())
        assertEquals(listOf("na"), b.book(x)!!.notes.map { it.id })
        assertEquals(File(a.dir, a.book(x)!!.fileName).readText(), File(b.dir, b.book(x)!!.fileName).readText())
        // Máy A nhận được cuốn của B và vị trí B đang đọc.
        assertEquals(setOf(x, y), a.state.books.map { it.fingerprint }.toSet())
        assertEquals(4, a.book(y)!!.position.chapter)
        assertEquals(File(b.dir, b.book(y)!!.fileName).readText(), File(a.dir, a.book(y)!!.fileName).readText())
        assertEquals(1, a.applied.single().received)

        // Ghi chú mới trên A, đọc tiếp trên A: lần đồng bộ sau B nhận được.
        a.addNote(y, "nb", t0 + 2_000)
        a.read(y, 6, t0 + 2_100)
        b.syncWith(drive, t0 + 3_000)
        assertEquals(listOf("nb"), b.book(y)!!.notes.map { it.id })
        assertEquals(6, b.book(y)!!.position.chapter)
    }

    @Test
    fun deletingABookOnOneDeviceRemovesItOnTheOther() {
        val (a, b, drive) = pairedDevices()
        val x = a.addBook("Sách cũ", "abc ".repeat(100), t0)
        b.syncWith(drive, t0 + 1_000)
        assertNotNull(b.book(x))
        val fileOnA = File(a.dir, a.book(x)!!.fileName)

        b.state = b.state.copy(books = b.state.books.filter { it.fingerprint != x }, deletedBooks = mapOf(x to t0 + 2_000))
        b.syncWith(drive, t0 + 3_000)
        assertNull(a.book(x))
        assertFalse(fileOnA.exists())
        assertEquals(listOf(x), a.applied.last().removed.map { it.fingerprint })
    }

    @Test
    fun changeOnTheHostDuringSyncIsRetriedNotLost() {
        val (a, b, drive) = pairedDevices()
        val x = a.addBook("Sách", "nội dung ".repeat(50), t0)
        b.syncWith(drive, t0 + 1_000)
        b.addNote(x, "cua-b", t0 + 1_500)
        var changed = false
        // Đúng lúc B sắp ghi, người dùng thêm ghi chú trên A → A báo "đã đổi", B đọc lại và gộp tiếp.
        val racing = object : RemoteDrive by drive {
            override suspend fun putSmall(path: String, bytes: ByteArray, contentType: String, ifMatch: String?, mustBeNew: Boolean) =
                run {
                    if (!changed) {
                        changed = true
                        a.addNote(x, "cua-a", t0 + 1_600)
                    }
                    drive.putSmall(path, bytes, contentType, ifMatch, mustBeNew)
                }
        }
        b.syncWith(racing, t0 + 2_000)
        assertEquals(setOf("cua-a", "cua-b"), a.book(x)!!.notes.map { it.id }.toSet())
        assertEquals(setOf("cua-a", "cua-b"), b.book(x)!!.notes.map { it.id }.toSet())
    }

    @Test
    fun openBookOnTheHostIsLeftAloneAndGetsAnOffer() {
        val (a, b, drive) = pairedDevices()
        val x = a.addBook("Sách", "nội dung ".repeat(50), t0)
        a.read(x, 1, t0 + 100)
        b.syncWith(drive, t0 + 1_000)
        b.read(x, 9, t0 + 2_000)
        a.open = setOf(x)
        b.syncWith(drive, t0 + 3_000)
        assertEquals(1, a.book(x)!!.position.chapter)
        assertEquals(9, a.applied.last().offers[x]!!.position.chapter)
    }

    @Test
    fun wrongCodeIsRejectedAndPairingClosesAfterFiveTries() {
        val a = Device("may-a")
        val b = Device("may-b")
        val (host, url) = a.serve()
        val code = host.startPairing()
        val wrong = if (code == "111111") "222222" else "111111"
        repeat(5) {
            try {
                runBlocking { LanPairing.pair(url, b.id, b.name, wrong) }
                fail("mã sai mà vẫn ghép đôi được")
            } catch (e: LanPairing.WrongCodeException) {
                // đúng như mong đợi
            }
        }
        assertNull(a.keyOf(b.id))
        assertFalse(host.isPairing)
        try {
            runBlocking { LanPairing.pair(url, b.id, b.name, code) }
            fail("đã hết lượt thử mà vẫn ghép đôi được")
        } catch (e: LanPairing.NotPairingException) {
            // đúng như mong đợi
        }
    }

    @Test
    fun strangersCannotReadTheLibrary() {
        val (a, _, _) = pairedDevices()
        a.addBook("Sách riêng", "bí mật ".repeat(20), t0)
        val url = "http://127.0.0.1:${servers.last().port}"
        val hello = runBlocking { LanHttpClient.hello(url) }!!
        assertEquals(a.id, hello.id)
        val stranger = LanDrive(url, UUID.randomUUID().toString(), ByteArray(32) { 7 })
        try {
            runBlocking { stranger.item(SyncEngine.LIBRARY_FILE) }
            fail("máy lạ đọc được thư viện")
        } catch (e: DriveException) {
            assertEquals(401, e.code)
        }
        assertTrue(LanHost.validBookName("a".repeat(40) + ".epub"))
        assertFalse(LanHost.validBookName("../../thu-vien.json"))
    }
}
