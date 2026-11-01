package io.github.truongphamtp.docsach.sync

import io.github.truongphamtp.docsach.core.BookRecord
import io.github.truongphamtp.docsach.core.LibraryState
import io.github.truongphamtp.docsach.core.PositionOffer
import io.github.truongphamtp.docsach.core.SyncMerge
import io.github.truongphamtp.docsach.data.LibraryJson
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/** Một tệp trên ổ đám mây. */
data class RemoteItem(val name: String, val size: Long, val eTag: String?, val downloadUrl: String?)

/** Lỗi từ ổ đám mây kèm mã HTTP (412: tệp đã bị máy khác sửa, 409: đã có tệp, 507: hết dung lượng...). */
class DriveException(val code: Int, val error: String, message: String) : IOException(message) {
    /** Lỗi không nên bỏ qua để làm tiếp các tệp khác (hết quyền, hết dung lượng). */
    val fatal: Boolean get() = code == 401 || code == 403 || code == 507
}

/** Các thao tác cần với ổ đám mây (OneDrive qua Microsoft Graph; bản giả lập khi kiểm thử). */
interface RemoteDrive {
    /** Thông tin một tệp (null nếu chưa có). */
    suspend fun item(path: String): RemoteItem?

    /** Các tệp trong một thư mục (rỗng nếu thư mục chưa có). */
    suspend fun children(folder: String): List<RemoteItem>

    suspend fun downloadText(url: String): String

    suspend fun downloadTo(url: String, target: File, onProgress: (Long, Long) -> Unit)

    /**
     * Ghi một tệp nhỏ. [ifMatch]: chỉ ghi nếu tệp vẫn là phiên bản đó (không thì lỗi 412);
     * [mustBeNew]: chỉ tạo mới, nếu đã có tệp thì lỗi 409.
     */
    suspend fun putSmall(path: String, bytes: ByteArray, contentType: String, ifMatch: String?, mustBeNew: Boolean): RemoteItem

    suspend fun uploadFile(path: String, file: File, onProgress: (Long, Long) -> Unit)

    suspend fun delete(path: String)
}

/** Thư viện trên máy, nhìn từ phía đồng bộ. */
interface LocalLibrary {
    suspend fun snapshot(): LibraryState

    /** Gộp [merged] vào thư viện (giữ nguyên sách đang mở [keepLocal]); trả về các cuốn bị xoá khỏi máy. */
    suspend fun apply(merged: LibraryState, keepLocal: Set<String>, now: Long): List<BookRecord>

    fun fileOf(book: BookRecord): File

    fun newTempFile(): File

    /** Nhận tệp vừa tải về: kiểm tra dấu vân tay, đưa vào thư mục sách; null nếu tệp không khớp. */
    suspend fun adopt(downloaded: File, remote: BookRecord): BookRecord?

    suspend fun deleteFiles(book: BookRecord)
}

/**
 * Lõi đồng bộ (không phụ thuộc Android, kiểm thử được với ổ đám mây giả lập).
 *
 * Trên ổ đám mây:
 * - thu-vien.json: danh sách sách, vị trí đọc, dấu trang, ghi chú, sách đã xoá;
 * - sach/<dấu vân tay>.<đuôi>: tệp sách để máy khác tải về.
 *
 * Mỗi lượt: đọc bản trên mây → gộp với máy này → tải lên tệp sách còn thiếu → ghi thư viện (chỉ khi
 * bản trên mây chưa bị máy khác đổi; đã đổi thì đọc lại và gộp tiếp) → áp vào máy → tải về sách mới.
 */
class SyncEngine(private val drive: RemoteDrive, private val local: LocalLibrary) {

    class Options(
        /** Đồng bộ cả tệp sách (không chỉ danh sách, vị trí đọc, ghi chú). */
        val syncFiles: Boolean = true,
        /** Được phép chuyển tệp sách lúc này (vd. đang dùng Wi‑Fi). */
        val transferFiles: Boolean = true,
        /** Sách đang mở trên màn hình đọc (dấu vân tay). */
        val openBooks: () -> Set<String> = { emptySet() },
        /** Gợi ý vị trí đã trả lời (dấu vân tay -> thời điểm của vị trí đó). */
        val answeredOffers: Map<String, Long> = emptyMap(),
    )

    data class Result(
        val uploaded: Int,
        val downloaded: Int,
        val removed: List<BookRecord>,
        val waitingUploads: Int,
        val waitingDownloads: Int,
        val failed: List<String>,
        val offers: Map<String, PositionOffer>,
        /** Có ghi thư viện lên mây trong lượt này không. */
        val wroteLibrary: Boolean,
    )

    @Volatile
    private var cachedETag: String? = null

    @Volatile
    private var cachedText: String? = null

    fun forgetCache() {
        cachedETag = null
        cachedText = null
    }

    suspend fun sync(options: Options, clock: () -> Long = System::currentTimeMillis, progress: (String) -> Unit = {}): Result {
        var attempt = 0
        while (true) {
            attempt++
            syncOnce(options, clock(), progress)?.let { return it }
            if (attempt >= MAX_ATTEMPTS) throw IOException("Thư viện trên OneDrive đang được máy khác cập nhật. Sẽ thử lại sau.")
        }
    }

    /** Một lượt đồng bộ; null nếu máy khác vừa ghi thư viện (cần đọc lại và gộp tiếp). */
    private suspend fun syncOnce(options: Options, now: Long, progress: (String) -> Unit): Result? {
        // 1. Đọc thư viện trên mây và gộp với máy này.
        progress("Đang đọc thư viện trên OneDrive…")
        val meta = drive.item(LIBRARY_FILE)
        val cached = cachedText
        val remoteText: String? = when {
            meta == null -> null
            meta.eTag != null && meta.eTag == cachedETag && cached != null -> cached
            else -> drive.downloadText(meta.downloadUrl ?: throw IOException("OneDrive không trả về địa chỉ tải thư viện."))
        }
        // Tệp hỏng thì coi như trống: bản gộp từ máy này sẽ ghi đè lên.
        val remote = remoteText?.let { runCatching { LibraryJson.stateFromText(it) }.getOrNull() } ?: LibraryState(emptyList())
        val mine = local.snapshot()
        val merged = SyncMerge.merge(mine, remote, now)
        val localByKey = mine.books.associateBy { it.fingerprint }

        // 2. Tải lên tệp sách mà mây chưa có.
        val remoteFiles = if (options.syncFiles) drive.children(BOOKS_FOLDER).associateBy { it.name } else emptyMap()
        val failed = ArrayList<String>()
        val toUpload = if (!options.syncFiles) {
            emptyList()
        } else {
            merged.books.mapNotNull { book ->
                localByKey[book.fingerprint]?.takeIf { SyncMerge.remoteFileName(it) !in remoteFiles && local.fileOf(it).exists() }
            }
        }
        var uploaded = 0
        if (options.transferFiles) {
            for ((index, book) in toUpload.withIndex()) {
                val label = "Đang tải lên ${index + 1}/${toUpload.size}: ${book.title}"
                progress(label)
                try {
                    drive.uploadFile("$BOOKS_FOLDER/${SyncMerge.remoteFileName(book)}", local.fileOf(book)) { done, total ->
                        progress("$label · ${percent(done, total)}")
                    }
                    uploaded++
                } catch (e: DriveException) {
                    if (e.fatal) throw e
                    failed += book.title
                }
            }
        }

        // 3. Ghi thư viện đã gộp lên mây (bỏ qua nếu không có gì mới).
        val shared = SyncMerge.portable(merged)
        val write = meta == null || shared != SyncMerge.portable(remote)
        if (write) {
            progress("Đang lưu thư viện lên OneDrive…")
            val text = LibraryJson.stateToJson(shared).toString()
            try {
                val item = drive.putSmall(LIBRARY_FILE, text.toByteArray(Charsets.UTF_8), "application/json", ifMatch = meta?.eTag, mustBeNew = meta == null)
                cachedETag = item.eTag
                cachedText = text
            } catch (e: DriveException) {
                if (e.code == 412 || e.code == 409) {
                    forgetCache()
                    return null
                }
                throw e
            }
        } else {
            cachedETag = meta?.eTag
            cachedText = remoteText
        }

        // 4. Áp vào máy này (sách chưa có tệp trên máy được thêm khi tải về xong).
        val forLocal = LibraryState(merged.books.filter { it.fingerprint in localByKey }, merged.deletedBooks)
        val removed = local.apply(forLocal, options.openBooks(), now)
        removed.forEach { local.deleteFiles(it) }

        // 5. Xoá trên mây tệp của sách đã xoá.
        for (item in remoteFiles.values) {
            if (item.name.substringBefore('.') !in merged.deletedBooks) continue
            try {
                drive.delete("$BOOKS_FOLDER/${item.name}")
            } catch (e: DriveException) {
                if (e.fatal) throw e
            }
        }

        // 6. Tải về sách mới từ máy khác — cuốn nào xong là hiện ngay trong thư viện.
        val toDownload = merged.books.filter { it.fingerprint !in localByKey && SyncMerge.remoteFileName(it) in remoteFiles }
        var downloaded = 0
        if (options.transferFiles) {
            for ((index, book) in toDownload.withIndex()) {
                val label = "Đang tải về ${index + 1}/${toDownload.size}: ${book.title}"
                progress(label)
                val record = try {
                    download(book, label, progress)
                } catch (e: DriveException) {
                    if (e.fatal) throw e
                    null
                }
                if (record == null) {
                    failed += book.title
                } else {
                    local.apply(LibraryState(listOf(record)), options.openBooks(), now)
                    downloaded++
                }
            }
        }

        // 7. Sách đang mở mà máy khác đã đọc xa hơn: gợi ý chuyển tới.
        val offers = SyncMerge.offers(merged, local.snapshot(), options.openBooks(), options.answeredOffers)

        return Result(
            uploaded = uploaded,
            downloaded = downloaded,
            removed = removed,
            waitingUploads = if (options.transferFiles) 0 else toUpload.size,
            waitingDownloads = if (options.transferFiles) 0 else toDownload.size,
            failed = failed,
            offers = offers,
            wroteLibrary = write,
        )
    }

    private suspend fun download(book: BookRecord, label: String, progress: (String) -> Unit): BookRecord? {
        val item = drive.item("$BOOKS_FOLDER/${SyncMerge.remoteFileName(book)}") ?: return null
        val url = item.downloadUrl ?: return null
        val tmp = local.newTempFile()
        try {
            drive.downloadTo(url, tmp) { done, total -> progress("$label · ${percent(done, total)}") }
            return local.adopt(tmp, book)
        } finally {
            tmp.delete()
        }
    }

    private fun percent(done: Long, total: Long): String =
        if (total <= 0) "${done / (1024 * 1024)} MB" else "${(done * 100f / total).roundToInt()}%"

    companion object {
        const val LIBRARY_FILE = "thu-vien.json"
        const val BOOKS_FOLDER = "sach"
        private const val MAX_ATTEMPTS = 4
    }
}
