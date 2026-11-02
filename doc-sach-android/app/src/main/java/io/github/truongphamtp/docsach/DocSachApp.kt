package io.github.truongphamtp.docsach

import android.app.Application
import io.github.truongphamtp.docsach.data.BookImporter
import io.github.truongphamtp.docsach.data.LibraryStore
import io.github.truongphamtp.docsach.data.SettingsStore
import io.github.truongphamtp.docsach.platform.AndroidCoverLoader
import io.github.truongphamtp.docsach.platform.ReadAloudCenter
import io.github.truongphamtp.docsach.sync.SyncManager
import io.github.truongphamtp.docsach.sync.lan.LanSyncManager
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
    lateinit var sync: SyncManager
        private set
    lateinit var lan: LanSyncManager
        private set
    lateinit var readAloud: ReadAloudCenter
        private set

    override fun onCreate() {
        super.onCreate()
        library = LibraryStore(File(filesDir, "library.json"), appScope)
        settings = SettingsStore(getSharedPreferences("doc_sach", MODE_PRIVATE))
        importer = BookImporter(this, library, settings, appScope)
        covers = AndroidCoverLoader(importer.coversDir)
        // Đọc to chạy nền (thông báo kiểu trình phát nhạc, "đảo" HyperOS).
        readAloud = ReadAloudCenter(this, library, importer.coversDir)
        importer.addGuideIfNeeded()
        // Đồng bộ qua OneDrive của chính bạn (chỉ chạy khi đã đăng nhập).
        sync = SyncManager(this, library, importer, BuildConfig.ONEDRIVE_CLIENT_ID)
        sync.start()
        // Đồng bộ trực tiếp qua Wi‑Fi giữa các máy đã ghép đôi (không cần tài khoản).
        lan = LanSyncManager(this, library, importer)
        lan.start()
    }
}
