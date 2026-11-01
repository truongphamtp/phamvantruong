package io.github.truongphamtp.docsach.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import io.github.truongphamtp.docsach.core.BookRecord
import io.github.truongphamtp.docsach.format.EpubArchive
import io.github.truongphamtp.docsach.library.CoverLoader
import io.github.truongphamtp.docsach.reader.BookImageProvider
import io.github.truongphamtp.docsach.reader.PdfPageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Đọc ảnh minh hoạ nằm trong file EPUB. */
class EpubImageProvider(private val archive: EpubArchive) : BookImageProvider {
    private val sizes = ConcurrentHashMap<String, IntSize>()

    override fun imageSize(path: String): IntSize? {
        sizes[path]?.let { return it }
        val bytes = archive.read(path) ?: return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return IntSize(options.outWidth, options.outHeight).also { sizes[path] = it }
    }

    override suspend fun loadImage(path: String, maxWidth: Int, maxHeight: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val bytes = archive.read(path) ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth && bounds.outHeight / (sample * 2) >= maxHeight) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }
}

/** Nạp ảnh bìa đã lưu, có bộ nhớ đệm. */
class AndroidCoverLoader(private val dir: File) : CoverLoader {
    private val cache = LruCache<String, ImageBitmap>(80)

    override suspend fun load(book: BookRecord): ImageBitmap? {
        val name = book.coverFileName ?: return null
        cache.get(name)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(File(dir, name).path)?.asImageBitmap()
        } ?: return null
        cache.put(name, bitmap)
        return bitmap
    }
}

/** Hiển thị PDF bằng PdfRenderer có sẵn trong Android. */
class AndroidPdfSource(file: File) : PdfPageSource, Closeable {
    private val descriptor: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = try {
        PdfRenderer(descriptor)
    } catch (e: Exception) {
        descriptor.close()
        throw e
    }
    private val lock = Any()
    private var closed = false

    override val pageCount: Int = renderer.pageCount

    override suspend fun pageSize(index: Int): IntSize = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (closed) return@withContext IntSize(1, 1)
            renderer.openPage(index).use { IntSize(it.width, it.height) }
        }
    }

    override suspend fun render(index: Int, widthPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (closed) return@withContext null
            renderer.openPage(index).use { page ->
                val width = widthPx.coerceIn(1, 4096)
                val height = (width.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceIn(1, 8192)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.asImageBitmap()
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            renderer.close()
            descriptor.close()
        }
    }
}
