package io.github.truongphamtp.docsach

import android.app.Application
import io.github.truongphamtp.docsach.data.BookImporter
import io.github.truongphamtp.docsach.data.LibraryStore
import io.github.truongphamtp.docsach.data.SettingsStore
import io.github.truongphamtp.docsach.platform.AndroidCoverLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class DocSachApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var library: LibraryStore
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var importer: BookImporter
        private set
    lateinit var covers: AndroidCoverLoader
        private set

    override fun onCreate() {
        super.onCreate()
        library = LibraryStore(File(filesDir, "library.json"), appScope)
        settings = SettingsStore(getSharedPreferences("doc_sach", MODE_PRIVATE))
        importer = BookImporter(this, library, settings, appScope)
        covers = AndroidCoverLoader(importer.coversDir)
        importer.addGuideIfNeeded()
    }
}
