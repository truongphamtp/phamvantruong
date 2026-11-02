package io.github.truongphamtp.docsach

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import io.github.truongphamtp.docsach.platform.AndroidReaderPlatform
import io.github.truongphamtp.docsach.platform.ReadAloudCenter
import io.github.truongphamtp.docsach.platform.hardwareKeyFor
import io.github.truongphamtp.docsach.platform.toFoldInfo
import io.github.truongphamtp.docsach.ui.AppTheme
import io.github.truongphamtp.docsach.ui.LocalBackHandler
import io.github.truongphamtp.docsach.ui.LocalReaderPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var platform: AndroidReaderPlatform
    private val openRequests = MutableStateFlow<String?>(null)
    private val consumedKeys = HashSet<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as DocSachApp
        platform = AndroidReaderPlatform(this)
        if (savedInstanceState == null) handleIntent(intent)
        addOnNewIntentListener { handleIntent(it) }

        // Theo dõi bản lề màn hình gập (Xiaomi Mix Fold 2...) để đặt rãnh giữa hai trang đúng chỗ gập.
        val windowLayout = WindowInfoTracker.getOrCreate(this).windowLayoutInfo(this)
        setContent {
            val layoutInfo by windowLayout.collectAsState(initial = null)
            val fold = layoutInfo?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull()?.toFoldInfo()
            AppTheme {
                CompositionLocalProvider(
                    LocalReaderPlatform provides platform,
                    LocalBackHandler provides { enabled, onBack -> BackHandler(enabled, onBack) },
                ) {
                    AppRoot(
                        app = app,
                        openRequests = openRequests,
                        onOpenRequestHandled = { openRequests.value = null },
                        fold = fold,
                    )
                }
            }
        }
    }

    /** Mở sách được chia sẻ/mở từ ứng dụng khác (Quản lý tệp, Zalo, Google Drive...). */
    private fun handleIntent(intent: Intent?) {
        // Chạm vào thông báo đọc to / "đảo" camera: mở lại đúng cuốn đang đọc.
        if (intent?.action == ReadAloudCenter.ACTION_OPEN_BOOK) {
            intent.getStringExtra(ReadAloudCenter.EXTRA_BOOK_ID)?.let { openRequests.value = it }
            return
        }
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        (application as DocSachApp).importer.importAndOpen(uri) { id -> openRequests.value = id }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val key = hardwareKeyFor(keyCode)
        if (key != null && platform.keyHandler?.invoke(key) == true) {
            consumedKeys += keyCode
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (consumedKeys.remove(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        // Quay lại ứng dụng: lấy thay đổi từ máy khác, bật đồng bộ qua Wi‑Fi.
        val app = application as DocSachApp
        app.sync.requestSync(0L)
        app.lan.onForeground()
    }

    override fun onStop() {
        super.onStop()
        val app = application as DocSachApp
        app.appScope.launch(Dispatchers.IO) { app.library.saveNow() }
        // Rời ứng dụng: đẩy vị trí đọc, ghi chú sang OneDrive/máy kia ngay.
        app.sync.requestSync(0L)
        app.lan.onBackground()
    }
}
