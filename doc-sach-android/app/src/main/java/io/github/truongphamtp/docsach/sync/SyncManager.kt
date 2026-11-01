package io.github.truongphamtp.docsach.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import io.github.truongphamtp.docsach.core.BookRecord
import io.github.truongphamtp.docsach.core.LibraryState
import io.github.truongphamtp.docsach.core.PositionOffer
import io.github.truongphamtp.docsach.core.SyncUiState
import io.github.truongphamtp.docsach.data.BookImporter
import io.github.truongphamtp.docsach.data.LibraryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Đồng bộ thư viện qua OneDrive của chính người dùng: đăng nhập, lịch đồng bộ, trạng thái hiển thị.
 * Phần gộp/ghi dữ liệu nằm trong [SyncEngine].
 */
class SyncManager(
    private val context: Context,
    private val library: LibraryStore,
    private val importer: BookImporter,
    builtInClientId: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("dong_bo", Context.MODE_PRIVATE)
    private val auth = OneDriveAuth(TokenVault(context.getSharedPreferences("dong_bo_token", Context.MODE_PRIVATE)), prefs, builtInClientId)

    private val localLibrary = object : LocalLibrary {
        override suspend fun snapshot(): LibraryState = withContext(Dispatchers.Main) { library.snapshot() }

        override suspend fun apply(merged: LibraryState, keepLocal: Set<String>, now: Long): List<BookRecord> =
            withContext(Dispatchers.Main) { library.applySync(merged, keepLocal, now) }

        override fun fileOf(book: BookRecord): File = importer.fileOf(book)

        override fun newTempFile(): File = File(context.cacheDir, "tai-ve-${UUID.randomUUID()}.tmp")

        override suspend fun adopt(downloaded: File, remote: BookRecord): BookRecord? =
            withContext(Dispatchers.IO) { importer.adoptDownloaded(downloaded, remote) }

        override suspend fun deleteFiles(book: BookRecord) = withContext(Dispatchers.IO) { importer.deleteFiles(book) }
    }

    private val engine = SyncEngine(GraphClient(auth), localLibrary)

    @Volatile
    private var signedIn = prefs.getBoolean(KEY_SIGNED_IN, false)

    private val _state = MutableStateFlow(
        SyncUiState(
            configured = auth.clientId.isNotBlank(),
            clientId = auth.clientId,
            signedIn = signedIn,
            accountName = auth.accountName,
            accountEmail = auth.accountEmail,
            lastSyncAt = prefs.getLong(KEY_LAST_SYNC, 0L),
            syncFiles = prefs.getBoolean(KEY_SYNC_FILES, true),
            wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, true),
        ),
    )
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    private val _signInRequest = MutableStateFlow<OneDriveAuth.Request?>(null)

    /** Trang đăng nhập Microsoft cần hiện (null = không đăng nhập). */
    val signInRequest: StateFlow<OneDriveAuth.Request?> = _signInRequest.asStateFlow()

    private val _offers = MutableStateFlow<Map<String, PositionOffer>>(emptyMap())

    /** Sách đang mở mà máy khác đã đọc tới vị trí mới hơn (theo dấu vân tay). */
    val offers: StateFlow<Map<String, PositionOffer>> = _offers.asStateFlow()
    private val answeredOffers = ConcurrentHashMap<String, Long>()

    private val openBooks = ConcurrentHashMap<String, Int>()

    private class Trigger(val delayMs: Long, val forceFiles: Boolean = false, val manual: Boolean = false)

    private val triggers = Channel<Trigger>(Channel.UNLIMITED)

    fun start() {
        scope.launch { importer.removeOrphanFiles() }
        scope.launch { runLoop() }
        scope.launch {
            library.changes.collect { change ->
                requestSync(if (change == LibraryStore.Change.CONTENT) CONTENT_DELAY_MS else POSITION_DELAY_MS)
            }
        }
        requestSync(0L)
    }

    // ------------------------------------------------------------------ yêu cầu đồng bộ

    /** Xin đồng bộ sau [delayMs] (gộp với các yêu cầu khác). Không làm gì nếu chưa đăng nhập. */
    fun requestSync(delayMs: Long = 0L) {
        if (signedIn) triggers.trySend(Trigger(delayMs))
    }

    fun syncNow() {
        if (signedIn) triggers.trySend(Trigger(0L, manual = true))
    }

    /** Tải ngay các sách đang chờ, kể cả khi đang dùng dữ liệu di động. */
    fun downloadPendingNow() {
        if (signedIn) triggers.trySend(Trigger(0L, forceFiles = true, manual = true))
    }

    fun bookOpened(fingerprint: String) {
        openBooks.merge(fingerprint, 1, Int::plus)
        requestSync(0L)
    }

    fun bookClosed(fingerprint: String) {
        openBooks.computeIfPresent(fingerprint) { _, count -> if (count <= 1) null else count - 1 }
        _offers.update { it - fingerprint }
        requestSync(1_500L)
    }

    /** Người dùng đã trả lời gợi ý "chuyển tới vị trí đọc trên máy khác". */
    fun answerOffer(fingerprint: String) {
        _offers.value[fingerprint]?.let { answeredOffers[fingerprint] = it.at }
        _offers.update { it - fingerprint }
    }

    private suspend fun runLoop() {
        while (true) {
            var trigger = triggers.receive()
            var deadline = SystemClock.elapsedRealtime() + trigger.delayMs
            // Chờ đủ thời gian, gộp các yêu cầu đến trong lúc chờ (yêu cầu gấp hơn thì làm sớm hơn).
            while (true) {
                val wait = deadline - SystemClock.elapsedRealtime()
                if (wait <= 0) break
                val next = withTimeoutOrNull(wait) { triggers.receive() } ?: break
                deadline = minOf(deadline, SystemClock.elapsedRealtime() + next.delayMs)
                trigger = Trigger(0L, trigger.forceFiles || next.forceFiles, trigger.manual || next.manual)
            }
            while (true) {
                val more = triggers.tryReceive().getOrNull() ?: break
                trigger = Trigger(0L, trigger.forceFiles || more.forceFiles, trigger.manual || more.manual)
            }
            runSync(trigger.forceFiles, trigger.manual)
        }
    }

    private suspend fun runSync(forceFiles: Boolean, manual: Boolean) {
        if (!signedIn) return
        if (!isOnline()) {
            if (manual) _state.update { it.copy(lastError = "Chưa có kết nối mạng.") }
            return
        }
        _state.update { it.copy(syncing = true, progress = "Đang kiểm tra OneDrive…", lastError = null) }
        try {
            val options = SyncEngine.Options(
                syncFiles = prefs.getBoolean(KEY_SYNC_FILES, true),
                transferFiles = forceFiles || !prefs.getBoolean(KEY_WIFI_ONLY, true) || isUnmetered(),
                openBooks = { openBooks.keys.toSet() },
                answeredOffers = HashMap(answeredOffers),
            )
            val result = engine.sync(options, progress = { text -> _state.update { it.copy(progress = text) } })
            _offers.value = result.offers
            report(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthExpiredException) {
            setSignedIn(false)
            _state.update { it.copy(signedIn = false, lastError = e.message) }
        } catch (e: Exception) {
            Log.w(TAG, "Đồng bộ lỗi", e)
            _state.update { it.copy(lastError = friendly(e)) }
        } finally {
            _state.update { it.copy(syncing = false, progress = null) }
        }
    }

    private fun report(result: SyncEngine.Result) {
        val now = System.currentTimeMillis()
        val parts = buildList {
            if (result.uploaded > 0) add("đã tải lên ${result.uploaded} cuốn")
            if (result.downloaded > 0) add("đã tải về ${result.downloaded} cuốn")
            if (result.removed.isNotEmpty()) add("đã xoá ${result.removed.size} cuốn theo máy khác")
            if (result.waitingUploads > 0) add("${result.waitingUploads} cuốn chờ Wi‑Fi để tải lên")
            if (result.waitingDownloads > 0) add("${result.waitingDownloads} cuốn chờ Wi‑Fi để tải về")
        }
        if (result.downloaded > 0 || result.removed.isNotEmpty()) {
            importer.postMessage(
                buildList {
                    if (result.downloaded > 0) add("Đã tải về ${result.downloaded} cuốn từ OneDrive.")
                    if (result.removed.isNotEmpty()) add("Đã xoá ${result.removed.size} cuốn (đã xoá ở máy khác).")
                }.joinToString(" "),
            )
        }
        prefs.edit().putLong(KEY_LAST_SYNC, now).apply()
        _state.update {
            it.copy(
                lastSyncAt = now,
                summary = if (parts.isEmpty()) "Mọi thứ đã khớp." else parts.joinToString(", ").replaceFirstChar { c -> c.uppercase() } + ".",
                pendingDownloads = result.waitingDownloads,
                lastError = if (result.failed.isEmpty()) {
                    null
                } else {
                    "Chưa chuyển được ${result.failed.size} tệp: ${result.failed.take(3).joinToString(", ")}. Sẽ thử lại lần sau."
                },
            )
        }
    }

    // ------------------------------------------------------------------ đăng nhập

    fun beginSignIn() {
        if (auth.clientId.isBlank()) {
            _state.update { it.copy(lastError = "Hãy nhập mã ứng dụng (Client ID) trước.") }
            return
        }
        _signInRequest.value = auth.newRequest()
        _state.update { it.copy(signingIn = true, lastError = null) }
    }

    fun cancelSignIn() {
        _signInRequest.value = null
        _state.update { it.copy(signingIn = false) }
    }

    fun isRedirect(url: String): Boolean = auth.isRedirect(url)

    /** Trang đăng nhập đã chuyển về [redirectUrl]: hoàn tất đăng nhập rồi đồng bộ lần đầu. */
    fun finishSignIn(redirectUrl: String) {
        val request = _signInRequest.value ?: return
        _signInRequest.value = null
        scope.launch {
            _state.update { it.copy(signingIn = true, progress = "Đang hoàn tất đăng nhập…", lastError = null) }
            try {
                auth.complete(redirectUrl, request)
                engine.forgetCache()
                setSignedIn(true)
                _state.update { it.copy(signedIn = true, accountName = auth.accountName, accountEmail = auth.accountEmail) }
                triggers.trySend(Trigger(0L, manual = true))
            } catch (e: CancellationException) {
                throw e
            } catch (e: SignInException) {
                _state.update { it.copy(lastError = e.message) }
            } catch (e: Exception) {
                Log.w(TAG, "Đăng nhập lỗi", e)
                _state.update { it.copy(lastError = "Không kết nối được máy chủ Microsoft. ${friendly(e)}") }
            } finally {
                _state.update { it.copy(signingIn = false, progress = null) }
            }
        }
    }

    fun signOut() {
        auth.signOut()
        setSignedIn(false)
        engine.forgetCache()
        _offers.value = emptyMap()
        runCatching { CookieManager.getInstance().removeAllCookies(null) }
        prefs.edit().remove(KEY_LAST_SYNC).apply()
        _state.update {
            it.copy(
                signedIn = false,
                accountName = "",
                accountEmail = "",
                lastSyncAt = 0L,
                summary = null,
                lastError = null,
                pendingDownloads = 0,
            )
        }
    }

    fun setClientId(value: String) {
        val clean = value.trim()
        if (signedIn && clean != auth.clientId) signOut()
        auth.setClientId(clean)
        _state.update { it.copy(configured = auth.clientId.isNotBlank(), clientId = auth.clientId, lastError = null) }
    }

    fun setSyncFiles(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SYNC_FILES, enabled).apply()
        _state.update { it.copy(syncFiles = enabled) }
        if (enabled) requestSync(0L)
    }

    fun setWifiOnly(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY, enabled).apply()
        _state.update { it.copy(wifiOnly = enabled) }
        if (!enabled) requestSync(0L)
    }

    private fun setSignedIn(value: Boolean) {
        signedIn = value
        prefs.edit().putBoolean(KEY_SIGNED_IN, value).apply()
    }

    // ------------------------------------------------------------------ tiện ích

    private fun capabilities(): NetworkCapabilities? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return manager.getNetworkCapabilities(manager.activeNetwork)
    }

    private fun isOnline(): Boolean = capabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    private fun isUnmetered(): Boolean = capabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

    private fun friendly(e: Exception): String = when (e) {
        is DriveException -> e.message.orEmpty()
        is UnknownHostException -> "Không kết nối được OneDrive (mất mạng?). Sẽ thử lại sau."
        is SocketTimeoutException -> "Mạng chậm, OneDrive không phản hồi kịp. Sẽ thử lại sau."
        is IOException -> e.message?.takeIf { it.isNotBlank() } ?: "Lỗi mạng khi đồng bộ. Sẽ thử lại sau."
        else -> "Đồng bộ chưa được: ${e.message ?: e.javaClass.simpleName}"
    }

    private companion object {
        const val TAG = "SyncManager"

        /** Ghi chú, dấu trang, thêm/xoá sách: đồng bộ sau ít giây. */
        const val CONTENT_DELAY_MS = 12_000L

        /** Lật trang: gom lại, tối đa vài phút một lần (đóng sách/thoát ứng dụng thì đồng bộ ngay). */
        const val POSITION_DELAY_MS = 4 * 60_000L

        const val KEY_SIGNED_IN = "signed_in"
        const val KEY_LAST_SYNC = "last_sync_at"
        const val KEY_SYNC_FILES = "sync_files"
        const val KEY_WIFI_ONLY = "wifi_only"
    }
}
