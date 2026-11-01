package io.github.truongphamtp.docsach.sync

import android.content.Context
import io.github.truongphamtp.docsach.core.BookRecord
import io.github.truongphamtp.docsach.core.LibraryState
import io.github.truongphamtp.docsach.data.BookImporter
import io.github.truongphamtp.docsach.data.LibraryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Thư viện trên máy, nhìn từ phía đồng bộ (OneDrive hoặc Wi‑Fi). */
class AndroidLocalLibrary(
    private val context: Context,
    private val library: LibraryStore,
    private val importer: BookImporter,
) : LocalLibrary {
    override suspend fun snapshot(): LibraryState = withContext(Dispatchers.Main) { library.snapshot() }

    override suspend fun apply(merged: LibraryState, keepLocal: Set<String>, now: Long): List<BookRecord> =
        withContext(Dispatchers.Main) { library.applySync(merged, keepLocal, now) }

    override fun fileOf(book: BookRecord): File = importer.fileOf(book)

    override fun newTempFile(): File = File(context.cacheDir, "tai-ve-${UUID.randomUUID()}.tmp")

    override suspend fun adopt(downloaded: File, remote: BookRecord): BookRecord? =
        withContext(Dispatchers.IO) { importer.adoptDownloaded(downloaded, remote) }

    override suspend fun deleteFiles(book: BookRecord) = withContext(Dispatchers.IO) { importer.deleteFiles(book) }
}
