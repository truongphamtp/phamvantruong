package io.github.truongphamtp.docsach.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import io.github.truongphamtp.docsach.core.BookFormat
import io.github.truongphamtp.docsach.core.BookRecord
import io.github.truongphamtp.docsach.format.EpubArchive
import io.github.truongphamtp.docsach.format.EpubParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToInt

/** Sao chép sách vào bộ nhớ riêng của ứng dụng, đọc tên/tác giả/ảnh bìa và thêm vào thư viện. */
class BookImporter(
    private val context: Context,
    private val library: LibraryStore,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    val booksDir: File = File(context.filesDir, "books").apply { mkdirs() }
    val coversDir: File = File(context.filesDir, "covers").apply { mkdirs() }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    sealed interface Result {
        data class Added(val book: BookRecord) : Result
        data class Duplicate(val book: BookRecord) : Result
        data class Failed(val reason: String) : Result
    }

    fun clearMessage() {
        _message.value = null
    }

    fun importAll(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            _busy.value = true
            val results = uris.map { importUri(it) }
            _busy.value = false
            _message.value = summarize(results)
        }
    }

    /** Mở sách từ ứng dụng khác (Quản lý tệp, Zalo, Drive...): thêm vào thư viện rồi mở luôn. */
    fun importAndOpen(uri: Uri, onReady: (String) -> Unit) {
        scope.launch {
            _busy.value = true
            val result = importUri(uri)
            _busy.value = false
            when (result) {
                is Result.Added -> onReady(result.book.id)
                is Result.Duplicate -> onReady(result.book.id)
                is Result.Failed -> _message.value = result.reason
            }
        }
    }

    fun delete(book: BookRecord) {
        library.remove(book.id)
        scope.launch(Dispatchers.IO) {
            File(booksDir, book.fileName).delete()
            book.coverFileName?.let { File(coversDir, it).delete() }
        }
        _message.value = "Đã xoá “${book.title}” khỏi thư viện."
    }

    /** Lần đầu mở ứng dụng: thêm sẵn cuốn "Hướng dẫn sử dụng". */
    fun addGuideIfNeeded() {
        if (settings.guideAdded) return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.assets.open(GUIDE_ASSET).use { importStream(it, "Hướng dẫn sử dụng Đọc Sách.epub") }
                }.getOrElse { Result.Failed(it.message ?: "") }
            }
            if (result !is Result.Failed) settings.guideAdded = true
        }
    }

    private suspend fun importUri(uri: Uri): Result = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.Failed("Không mở được tệp “$name”.")
            input.use { importStream(it, name) }
        } catch (e: SecurityException) {
            Result.Failed("Ứng dụng không có quyền đọc tệp “$name”.")
        } catch (e: Exception) {
            Log.e(TAG, "Nhập sách lỗi", e)
            Result.Failed("Không thêm được “$name”: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun importStream(input: InputStream, displayName: String): Result {
        val tmp = File(booksDir, "nhap-${UUID.randomUUID()}.tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-1")
            var size = 0L
            tmp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    size += n
                }
            }
            if (size == 0L) return Result.Failed("Tệp “$displayName” trống.")
            val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
            library.books.value.firstOrNull { it.fingerprint == fingerprint }?.let { return Result.Duplicate(it) }

            val format = detectFormat(tmp, displayName) ?: return Result.Failed(unsupportedMessage(tmp, displayName))
            val id = UUID.randomUUID().toString()
            val file = File(booksDir, "$id.${format.name.lowercase()}")
            if (!tmp.renameTo(file)) tmp.copyTo(file, overwrite = true)

            var title = displayName.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { "Sách không tên" }
            var author = ""
            var cover: String? = null
            when (format) {
                BookFormat.EPUB -> runCatching {
                    EpubArchive(file).use { archive ->
                        val meta = EpubParser.readMetadata(archive)
                        if (meta.title.isNotBlank()) title = meta.title
                        author = meta.author
                        cover = meta.coverPath?.let { archive.read(it) }?.let { saveCover(id, it) }
                    }
                }.onFailure { Log.w(TAG, "Không đọc được thông tin EPUB", it) }
                BookFormat.PDF -> cover = runCatching { renderPdfCover(id, file) }.getOrNull()
                BookFormat.TXT -> Unit
            }
            val record = BookRecord(
                id = id,
                title = title,
                author = author,
                format = format,
                fileName = file.name,
                coverFileName = cover,
                sizeBytes = size,
                fingerprint = fingerprint,
                addedAt = System.currentTimeMillis(),
            )
            library.add(record)
            return Result.Added(record)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun detectFormat(file: File, name: String): BookFormat? {
        val head = ByteArray(8)
        val read = file.inputStream().use { it.read(head) }
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            read >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() ->
                if (EpubParser.isEpub(file)) BookFormat.EPUB else null
            read >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "%PDF" -> BookFormat.PDF
            ext == "txt" || ext == "text" -> BookFormat.TXT
            (ext.isEmpty() || ext == "md") && looksLikeText(file) -> BookFormat.TXT
            else -> null
        }
    }

    private fun looksLikeText(file: File): Boolean {
        val sample = ByteArray(4096)
        val n = file.inputStream().use { it.read(sample) }
        return n > 0 && (0 until n).none { sample[it] == 0.toByte() }
    }

    private fun unsupportedMessage(file: File, name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        val isMobi = runCatching {
            val buf = ByteArray(68)
            file.inputStream().use { it.read(buf) }
            String(buf, 60, 8, Charsets.ISO_8859_1) == "BOOKMOBI"
        }.getOrDefault(false)
        return when {
            isMobi || ext in setOf("mobi", "azw", "azw3", "kfx") ->
                "“$name” là định dạng riêng của Kindle (MOBI/AZW). Hãy dùng bản EPUB của sách này."
            ext in setOf("docx", "doc", "odt") ->
                "“$name” là tệp văn bản Word. Hãy xuất sang EPUB (như khi gửi sang Kindle) rồi mở lại."
            else -> "Chưa hỗ trợ định dạng của “$name”. Ứng dụng đọc được EPUB, PDF và TXT."
        }
    }

    private fun saveCover(id: String, bytes: ByteArray): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= COVER_WIDTH) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return writeCover(id, bitmap)
    }

    private fun renderPdfCover(id: String, file: File): String? =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount == 0) {
                    null
                } else {
                    renderer.openPage(0).use { page ->
                        val height = (COVER_WIDTH * page.height / page.width.toFloat()).roundToInt().coerceIn(1, COVER_WIDTH * 3)
                        val bitmap = Bitmap.createBitmap(COVER_WIDTH, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        writeCover(id, bitmap)
                    }
                }
            }
        }

    private fun writeCover(id: String, bitmap: Bitmap): String {
        val out = File(coversDir, "$id.jpg")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bitmap.recycle()
        return out.name
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index)?.let { return it }
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "Sách"
    }

    private fun summarize(results: List<Result>): String {
        val added = results.filterIsInstance<Result.Added>()
        val duplicates = results.filterIsInstance<Result.Duplicate>()
        val failed = results.filterIsInstance<Result.Failed>()
        return buildList {
            when {
                added.size == 1 -> add("Đã thêm “${added[0].book.title}”.")
                added.size > 1 -> add("Đã thêm ${added.size} cuốn sách.")
            }
            if (duplicates.isNotEmpty()) add("${duplicates.size} cuốn đã có sẵn trong thư viện.")
            if (failed.size == 1) add(failed[0].reason) else if (failed.size > 1) add("${failed.size} tệp không thêm được.")
        }.joinToString(" ")
    }

    private companion object {
        const val TAG = "BookImporter"
        const val GUIDE_ASSET = "huong-dan-su-dung.epub"
        const val COVER_WIDTH = 480
    }
}
