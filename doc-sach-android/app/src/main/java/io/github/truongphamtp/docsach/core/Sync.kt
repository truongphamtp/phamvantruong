package io.github.truongphamtp.docsach.core

/** Toàn bộ thư viện ở một thời điểm (trên máy hoặc trên OneDrive). */
data class LibraryState(
    val books: List<BookRecord>,
    /** Sách đã xoá: dấu vân tay tệp -> thời điểm xoá. */
    val deletedBooks: Map<String, Long> = emptyMap(),
)

/** Trạng thái đồng bộ hiển thị trên giao diện. */
data class SyncUiState(
    /** Đã có mã ứng dụng (Client ID) đăng ký với Microsoft chưa. */
    val configured: Boolean = false,
    val clientId: String = "",
    val signedIn: Boolean = false,
    val signingIn: Boolean = false,
    val accountName: String = "",
    val accountEmail: String = "",
    val syncing: Boolean = false,
    /** Việc đang làm, ví dụ "Đang tải lên 2/5: Tên sách". */
    val progress: String? = null,
    val lastSyncAt: Long = 0L,
    /** Kết quả lần đồng bộ gần nhất, ví dụ "Đã tải lên 2 cuốn". */
    val summary: String? = null,
    val lastError: String? = null,
    val syncFiles: Boolean = true,
    val wifiOnly: Boolean = true,
    /** Số cuốn có trên OneDrive nhưng chưa tải về máy này (vd. đang dùng dữ liệu di động). */
    val pendingDownloads: Int = 0,
)

/** Máy khác đã đọc tới vị trí mới hơn trong cuốn sách đang mở. */
data class PositionOffer(val fingerprint: String, val position: ReadingPosition, val progress: Float, val at: Long)

/**
 * Gộp thư viện trên máy với bản trên OneDrive.
 * - Sách nhận diện bằng dấu vân tay tệp (cùng một tệp trên hai máy có cùng dấu vân tay).
 * - Vị trí đọc: lấy bên lật trang gần đây hơn (mở sách mà chưa lật trang thì không tính).
 * - Dấu trang, ghi chú: hợp lại; ghi chú sửa ở hai nơi thì lấy bản sửa sau cùng.
 * - Xoá: ghi lại thời điểm xoá để máy khác cũng xoá theo (trừ khi bên kia đọc/sửa sau thời điểm đó).
 */
object SyncMerge {
    /** Giữ thông tin "đã xoá" trong 180 ngày. */
    const val TOMBSTONE_TTL_MS = 180L * 24 * 60 * 60 * 1000

    fun merge(local: LibraryState, remote: LibraryState, now: Long): LibraryState {
        val deleted = HashMap(mergeTimes(local.deletedBooks, remote.deletedBooks, now))
        val localByKey = LinkedHashMap<String, BookRecord>()
        local.books.forEach { localByKey[it.fingerprint] = it }
        val remoteByKey = LinkedHashMap<String, BookRecord>()
        remote.books.forEach { remoteByKey[it.fingerprint] = it }

        val result = ArrayList<BookRecord>()
        val keys = LinkedHashSet<String>().apply {
            addAll(localByKey.keys)
            addAll(remoteByKey.keys)
        }
        for (key in keys) {
            val l = localByKey[key]
            val r = remoteByKey[key]
            val lastActivity = maxOf(activity(l), activity(r))
            val removedAt = deleted[key]
            if (removedAt != null) {
                if (removedAt >= lastActivity) continue
                deleted.remove(key) // Được thêm/đọc lại sau khi xoá.
            }
            result += when {
                l == null -> r!!
                r == null -> l
                else -> mergeBook(l, r, now)
            }
        }
        return LibraryState(result, deleted)
    }

    private fun activity(book: BookRecord?): Long =
        if (book == null) {
            0L
        } else {
            maxOf(book.addedAt, book.lastOpenedAt, book.positionAt, book.notes.maxOfOrNull { it.modifiedAt } ?: 0L)
        }

    /** Thời điểm vị trí đọc được đặt (dữ liệu cũ chưa có [BookRecord.positionAt] thì dùng lần mở gần nhất). */
    fun positionTime(book: BookRecord): Long = if (book.positionAt > 0L) book.positionAt else book.lastOpenedAt

    /** Gộp hai bản của cùng một cuốn sách; [local] giữ các thông tin riêng của máy (id, tên tệp, ảnh bìa). */
    fun mergeBook(local: BookRecord, remote: BookRecord, now: Long): BookRecord {
        val deletedItems = mergeTimes(local.deletedItems, remote.deletedItems, now)
        val newerPosition = if (positionTime(remote) > positionTime(local)) remote else local

        val bookmarks = LinkedHashMap<String, Bookmark>()
        (local.bookmarks + remote.bookmarks).forEach { mark ->
            val removedAt = deletedItems[mark.id]
            if (removedAt == null || mark.createdAt > removedAt) bookmarks.putIfAbsent(mark.id, mark)
        }
        val notes = LinkedHashMap<String, Note>()
        (local.notes + remote.notes).forEach { note ->
            val removedAt = deletedItems[note.id]
            if (removedAt != null && note.modifiedAt <= removedAt) return@forEach
            val existing = notes[note.id]
            if (existing == null || note.modifiedAt > existing.modifiedAt) notes[note.id] = note
        }
        return local.copy(
            title = local.title.ifBlank { remote.title },
            author = local.author.ifBlank { remote.author },
            addedAt = minOf(local.addedAt, remote.addedAt),
            lastOpenedAt = maxOf(local.lastOpenedAt, remote.lastOpenedAt),
            progress = newerPosition.progress,
            position = newerPosition.position,
            positionAt = positionTime(newerPosition),
            bookmarks = bookmarks.values.sortedWith(compareBy<Bookmark>({ it.createdAt }, { it.id })),
            notes = notes.values.sortedWith(compareBy<Note>({ it.chapter }, { it.start }, { it.id })),
            deletedItems = deletedItems,
        )
    }

    /** Hợp hai bảng "thời điểm", lấy giá trị lớn hơn và bỏ mục quá hạn. */
    fun mergeTimes(a: Map<String, Long>, b: Map<String, Long>, now: Long): Map<String, Long> {
        val out = HashMap<String, Long>()
        for ((k, v) in a) out[k] = maxOf(v, out[k] ?: 0L)
        for ((k, v) in b) out[k] = maxOf(v, out[k] ?: 0L)
        return out.filterValues { now - it < TOMBSTONE_TTL_MS }
    }

    /**
     * Dạng "dùng chung" để lưu lên OneDrive: bỏ thông tin riêng của từng máy (id, tên tệp, ảnh bìa)
     * và sắp xếp cố định, nhờ vậy hai bản giống nhau về nội dung thì so sánh bằng nhau.
     */
    fun portable(state: LibraryState): LibraryState = LibraryState(
        books = state.books
            .map { book ->
                book.copy(
                    id = book.fingerprint,
                    fileName = "",
                    coverFileName = null,
                    bookmarks = book.bookmarks.sortedBy { it.id },
                    notes = book.notes.sortedBy { it.id },
                )
            }
            .sortedBy { it.fingerprint },
        deletedBooks = state.deletedBooks,
    )

    /**
     * Áp bản gộp [merged] vào thư viện trên máy [current]. Gộp thêm một lần với dữ liệu hiện tại để
     * những gì người dùng vừa làm trong lúc đồng bộ không bị mất. Sách đang mở ([keepLocal], theo dấu
     * vân tay) giữ nguyên để không giành ghi chú với màn hình đọc; lần đồng bộ sau sẽ gộp tiếp.
     *
     * @return thư viện mới và các cuốn bị xoá khỏi máy.
     */
    fun applyToLocal(current: LibraryState, merged: LibraryState, keepLocal: Set<String>, now: Long): Pair<LibraryState, List<BookRecord>> {
        val combined = merge(current, merged, now)
        val currentByKey = current.books.associateBy { it.fingerprint }
        val books = combined.books.map { book -> if (book.fingerprint in keepLocal) currentByKey[book.fingerprint] ?: book else book }
            .toMutableList()
        current.books.forEach { book ->
            if (book.fingerprint in keepLocal && books.none { it.fingerprint == book.fingerprint }) books += book
        }
        val kept = books.mapTo(HashSet()) { it.fingerprint }
        return LibraryState(books, combined.deletedBooks) to current.books.filter { it.fingerprint !in kept }
    }

    /**
     * Sách đang mở trên máy này ([open]) mà bản gộp có vị trí đọc mới hơn (máy khác đã đọc xa hơn):
     * trả về gợi ý "đọc tiếp từ máy khác". Bỏ qua gợi ý người dùng đã trả lời ([answered]).
     */
    fun offers(merged: LibraryState, current: LibraryState, open: Set<String>, answered: Map<String, Long>): Map<String, PositionOffer> {
        val out = HashMap<String, PositionOffer>()
        for (fingerprint in open) {
            val here = current.books.firstOrNull { it.fingerprint == fingerprint } ?: continue
            val best = merged.books.firstOrNull { it.fingerprint == fingerprint } ?: continue
            val at = positionTime(best)
            if (best.position != here.position && at > positionTime(here) && at > (answered[fingerprint] ?: 0L)) {
                out[fingerprint] = PositionOffer(fingerprint, best.position, best.progress, at)
            }
        }
        return out
    }

    /** Tên tệp sách trên OneDrive (trong thư mục "sach"). */
    fun remoteFileName(book: BookRecord): String = "${book.fingerprint}.${book.format.name.lowercase()}"
}

/** Một máy đã ghép đôi, hiển thị trong bảng Đồng bộ. */
data class LanPeerUi(
    val id: String,
    val name: String,
    /** Đang thấy máy này trong cùng mạng Wi‑Fi (ứng dụng đang mở trên máy đó). */
    val online: Boolean = false,
    val lastSyncAt: Long = 0L,
    val syncing: Boolean = false,
)

/** Máy ở gần đang bật "Ghép đôi máy mới". */
data class NearbyDeviceUi(val id: String, val name: String)

/** Trạng thái đồng bộ qua Wi‑Fi hiển thị trên giao diện. */
data class LanUiState(
    val deviceName: String = "",
    /** Máy này đang sẵn sàng đồng bộ (ứng dụng mở, có mạng). */
    val active: Boolean = false,
    /** Địa chỉ của máy này trong mạng, ví dụ "192.168.1.5:47123" (dùng khi phải nhập tay). */
    val address: String? = null,
    val peers: List<LanPeerUi> = emptyList(),
    /** Mã 6 số khi máy này đang bật ghép đôi. */
    val pairingCode: String? = null,
    val nearby: List<NearbyDeviceUi> = emptyList(),
    val pairingBusy: Boolean = false,
    val syncing: Boolean = false,
    val progress: String? = null,
    val summary: String? = null,
    val lastError: String? = null,
    val lastSyncAt: Long = 0L,
)
