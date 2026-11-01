package io.github.truongphamtp.docsach.sync.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import io.github.truongphamtp.docsach.core.LanPeerUi
import io.github.truongphamtp.docsach.core.LanUiState
import io.github.truongphamtp.docsach.core.NearbyDeviceUi
import io.github.truongphamtp.docsach.core.PositionOffer
import io.github.truongphamtp.docsach.data.BookImporter
import io.github.truongphamtp.docsach.data.LibraryStore
import io.github.truongphamtp.docsach.sync.AndroidLocalLibrary
import io.github.truongphamtp.docsach.sync.DriveException
import io.github.truongphamtp.docsach.sync.SyncEngine
import io.github.truongphamtp.docsach.sync.TokenVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Đồng bộ trực tiếp giữa các máy trong cùng mạng Wi‑Fi — không cần tài khoản hay đám mây.
 *
 * Khi ứng dụng đang mở, máy này chạy một máy chủ nhỏ ([LanHost]) và tự tìm các máy đã ghép đôi
 * (qua mDNS/NSD). Thấy nhau là đồng bộ; sau đó cứ có thay đổi (ghi chú, lật trang, thêm/xoá sách)
 * là đồng bộ lại sau ít giây.
 */
class LanSyncManager(
    private val context: Context,
    private val library: LibraryStore,
    private val importer: BookImporter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("dong_bo_wifi", Context.MODE_PRIVATE)
    private val vault = TokenVault(context.getSharedPreferences("dong_bo_wifi_khoa", Context.MODE_PRIVATE))
    private val localLibrary = AndroidLocalLibrary(context, library, importer)

    val myId: String = prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_ID, it).apply() }

    private class PeerRecord(val id: String, val name: String, var lastSyncAt: Long, var baseUrl: String?)

    private val records = ConcurrentHashMap<String, PeerRecord>()
    private val keys = ConcurrentHashMap<String, ByteArray>()

    /** Máy đã ghép đôi đang thấy trong mạng: id -> địa chỉ. */
    private val online = ConcurrentHashMap<String, String>()

    /** Máy (chưa ghép đôi) nhìn thấy trong mạng: id -> (địa chỉ, tên, đang ghép đôi). */
    private class Seen(val baseUrl: String, val name: String, val pairing: Boolean)

    private val seen = ConcurrentHashMap<String, Seen>()

    private val openBooks = ConcurrentHashMap<String, Int>()
    private val answeredOffers = ConcurrentHashMap<String, Long>()
    private val engines = ConcurrentHashMap<String, Pair<String, SyncEngine>>()
    private val syncLock = Mutex()

    private val _state = MutableStateFlow(LanUiState(deviceName = deviceName()))
    val state: StateFlow<LanUiState> = _state.asStateFlow()

    private val _offers = MutableStateFlow<Map<String, PositionOffer>>(emptyMap())
    val offers: StateFlow<Map<String, PositionOffer>> = _offers.asStateFlow()

    private val peerStore = object : LanPeerStore {
        override fun keyOf(peerId: String): ByteArray? = keys[peerId]

        override fun nameOf(peerId: String): String? = records[peerId]?.name

        override fun save(peer: LanPeer, key: ByteArray) {
            keys[peer.id] = key
            vault.put(KEY_PREFIX + peer.id, LanCrypto.b64(key))
            records[peer.id] = records[peer.id]?.let { PeerRecord(peer.id, peer.name, it.lastSyncAt, it.baseUrl) }
                ?: PeerRecord(peer.id, peer.name, 0L, null)
            savePeers()
        }
    }

    private val host = LanHost(
        myId = myId,
        myName = { deviceName() },
        peers = peerStore,
        local = localLibrary,
        stashDir = File(context.cacheDir, "nhan-sach"),
        openBooks = { openBooks.keys.toSet() },
        answeredOffers = { HashMap(answeredOffers) },
        events = object : LanHostEvents {
            override fun onPaired(peer: LanPeer) {
                _state.update { it.copy(pairingCode = null, lastError = null) }
                importer.postMessage("Đã ghép đôi với ${peer.name}.")
                publishPeers()
                requestSync(800L)
            }

            override fun onPairingFailed(attemptsLeft: Int) {
                _state.update {
                    it.copy(
                        lastError = if (attemptsLeft > 0) "Máy kia nhập sai mã ghép đôi (còn $attemptsLeft lần)." else "Nhập sai mã quá nhiều lần. Hãy bấm ghép đôi lại.",
                        pairingCode = if (attemptsLeft > 0) it.pairingCode else null,
                    )
                }
            }

            override fun onApplied(peer: LanPeer, applied: LanHost.Applied) {
                records[peer.id]?.lastSyncAt = System.currentTimeMillis()
                savePeers()
                if (applied.offers.isNotEmpty()) _offers.update { it + applied.offers }
                val parts = buildList {
                    if (applied.received > 0) add("Đã nhận ${applied.received} cuốn từ ${peer.name}.")
                    if (applied.removed.isNotEmpty()) add("Đã xoá ${applied.removed.size} cuốn theo ${peer.name}.")
                }
                if (parts.isNotEmpty()) importer.postMessage(parts.joinToString(" "))
                _state.update { it.copy(lastSyncAt = System.currentTimeMillis()) }
                publishPeers()
            }

            override fun onNudge(peer: LanPeer) {
                requestSync(500L)
            }
        },
    )

    private val nsd: NsdManager? = context.getSystemService(NsdManager::class.java)
    private var server: LanHttpServer? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
    @Volatile
    private var foreground = false
    private var stopJob: Job? = null
    private var pollJob: Job? = null

    /** Bật/tắt mạng luôn chạy lần lượt (ứng dụng có thể ẩn/hiện liên tục). */
    private val lifecycleLock = Mutex()

    private class Trigger(val delayMs: Long, val manual: Boolean)

    private val triggers = Channel<Trigger>(Channel.UNLIMITED)

    fun start() {
        loadPeers()
        publishPeers()
        scope.launch { resolveLoop() }
        scope.launch { syncLoop() }
        scope.launch {
            library.changes.collect { change ->
                requestSync(if (change == LibraryStore.Change.CONTENT) CONTENT_DELAY_MS else POSITION_DELAY_MS)
            }
        }
    }

    // ------------------------------------------------------------------ vòng đời ứng dụng

    /** Ứng dụng hiện lên: bật máy chủ, tìm máy khác. */
    fun onForeground() {
        foreground = true
        scope.launch {
            lifecycleLock.withLock {
                stopJob?.cancel()
                if (foreground && server == null) startNetwork()
            }
        }
    }

    /** Ứng dụng ẩn đi: đẩy thay đổi sang máy khác ngay, rồi tắt sau ít giây. */
    fun onBackground() {
        foreground = false
        requestSync(0L)
        scope.launch {
            lifecycleLock.withLock {
                stopJob?.cancel()
                stopJob = scope.launch {
                    delay(STOP_DELAY_MS)
                    lifecycleLock.withLock { if (!foreground) stopNetwork() }
                }
            }
        }
    }

    private fun startNetwork() {
        if (server == null) {
            server = runCatching { LanHttpServer(host::handle, PREFERRED_PORT) }.onFailure { Log.w(TAG, "Không mở được máy chủ", it) }.getOrNull()
        }
        val port = server?.port ?: return
        host.cleanStash()
        runCatching {
            multicastLock = context.applicationContext.getSystemService(WifiManager::class.java)
                ?.createMulticastLock("doc-sach")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
        register(port)
        discover()
        _state.update { it.copy(active = true, address = localAddress()?.let { a -> "$a:$port" }, deviceName = deviceName()) }
        // Mạng chặn mDNS? Thử thẳng địa chỉ lần trước của các máy đã ghép đôi.
        scope.launch {
            records.values.mapNotNull { it.baseUrl }.distinct().forEach { probe(it) }
        }
        pollJob?.cancel()
        pollJob = scope.launch { pollLoop() }
    }

    private fun stopNetwork() {
        pollJob?.cancel()
        runCatching { discovery?.let { nsd?.stopServiceDiscovery(it) } }
        runCatching { registration?.let { nsd?.unregisterService(it) } }
        discovery = null
        registration = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        server?.close()
        server = null
        online.clear()
        seen.clear()
        host.stopPairing()
        _state.update { it.copy(active = false, pairingCode = null, nearby = emptyList()) }
        publishPeers()
    }

    // ------------------------------------------------------------------ tìm máy trong mạng

    private fun register(port: Int) {
        val manager = nsd ?: return
        if (registration != null) return
        val info = NsdServiceInfo().apply {
            serviceName = "DocSach-${myId.take(8)}"
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Không công bố được dịch vụ: $errorCode")
                registration = null
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { registration = null }
    }

    private fun discover() {
        val manager = nsd ?: return
        if (discovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Không tìm được máy khác: $errorCode")
                discovery = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName != "DocSach-${myId.take(8)}") resolveQueue.trySend(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                // Không chắc máy nào: lần đồng bộ tới sẽ biết (lỗi kết nối thì đánh dấu ngoại tuyến).
                scope.launch { recheckOnline() }
            }
        }
        discovery = listener
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { discovery = null }
    }

    private suspend fun resolveLoop() {
        for (info in resolveQueue) {
            val resolved = withTimeoutOrNull(8_000) { resolve(info) } ?: continue
            // Ưu tiên địa chỉ IPv4 (dễ kết nối hơn địa chỉ IPv6 nội bộ).
            @Suppress("DEPRECATION")
            val address = if (Build.VERSION.SDK_INT >= 34) {
                resolved.hostAddresses.firstOrNull { it is Inet4Address } ?: resolved.hostAddresses.firstOrNull() ?: resolved.host
            } else {
                resolved.host
            } ?: continue
            probe(baseUrl(address, resolved.port))
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { cont ->
        val manager = nsd
        if (manager == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        runCatching {
            manager.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (cont.isActive) cont.resume(serviceInfo)
                    }

                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        }.onFailure { if (cont.isActive) cont.resume(null) }
    }

    /** Hỏi một địa chỉ xem là máy nào; cập nhật danh sách máy đã ghép đôi / máy ở gần. */
    private suspend fun probe(url: String) {
        val hello = LanHttpClient.hello(url) ?: return
        if (hello.id == myId) return
        if (keys.containsKey(hello.id)) {
            val record = records[hello.id]
            val wasOnline = online.containsKey(hello.id)
            online[hello.id] = url
            if (record != null && record.baseUrl != url) {
                record.baseUrl = url
                savePeers()
            }
            seen.remove(hello.id)
            publishPeers()
            // Mới thấy nhau: máy có mã nhỏ hơn bắt đầu đồng bộ (tránh hai máy cùng lúc).
            if (!wasOnline) requestSync(if (myId < hello.id) 300L else 4_000L)
        } else {
            seen[hello.id] = Seen(url, hello.name, hello.pairing)
            publishNearby()
        }
    }

    private suspend fun recheckOnline() {
        for ((id, url) in online.entries.toList()) {
            if (LanHttpClient.hello(url)?.id != id) online.remove(id)
        }
        publishPeers()
    }

    /** Trong lúc mở ứng dụng: thỉnh thoảng hỏi lại các máy (đang ghép đôi thì hỏi dày hơn). */
    private suspend fun pollLoop() {
        var round = 0
        while (scope.isActive) {
            val pairing = host.isPairing || _state.value.pairingCode != null
            delay(if (pairing) 2_000L else 30_000L)
            round++
            if (_state.value.pairingCode != null && !host.isPairing) {
                _state.update { it.copy(pairingCode = null) }
            }
            if (pairing) {
                seen.values.map { it.baseUrl }.distinct().forEach { probe(it) }
            }
            if (!pairing || round % 15 == 0) {
                records.values.filter { !online.containsKey(it.id) }.mapNotNull { it.baseUrl }.distinct().forEach { probe(it) }
                recheckOnline()
            }
        }
    }

    // ------------------------------------------------------------------ ghép đôi

    fun startPairing() {
        val code = host.startPairing()
        _state.update { it.copy(pairingCode = code, lastError = null) }
        if (server == null) onForeground()
        scope.launch { seen.values.map { it.baseUrl }.distinct().forEach { probe(it) } }
    }

    fun stopPairing() {
        host.stopPairing()
        _state.update { it.copy(pairingCode = null, pairingBusy = false) }
    }

    /** Nhập mã đang hiện trên máy [deviceId] (tìm thấy trong mạng). */
    fun pairWith(deviceId: String, code: String) {
        val url = seen[deviceId]?.baseUrl ?: online[deviceId]
        if (url == null) {
            _state.update { it.copy(lastError = "Không còn thấy máy đó trong mạng. Hãy thử lại.") }
            return
        }
        pair(url, code)
    }

    /** Nhập tay địa chỉ máy kia (khi mạng chặn việc tự tìm), dạng 192.168.1.5:47123. */
    fun pairWithAddress(address: String, code: String) {
        val clean = address.trim().removePrefix("http://").trimEnd('/')
        if (clean.isEmpty()) return
        pair(if (':' in clean) "http://$clean" else "http://$clean:$PREFERRED_PORT", code)
    }

    private fun pair(url: String, code: String) {
        scope.launch {
            _state.update { it.copy(pairingBusy = true, lastError = null) }
            try {
                val result = LanPairing.pair(url, myId, deviceName(), code.filter { it.isDigit() })
                peerStore.save(LanPeer(result.peerId, result.peerName.ifBlank { "Máy khác" }), result.key)
                records[result.peerId]?.baseUrl = url
                savePeers()
                online[result.peerId] = url
                seen.remove(result.peerId)
                host.stopPairing()
                _state.update { it.copy(pairingCode = null) }
                importer.postMessage("Đã ghép đôi với ${result.peerName.ifBlank { "máy kia" }}.")
                publishPeers()
                publishNearby()
                requestSync(300L)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LanPairing.WrongCodeException) {
                _state.update { it.copy(lastError = "Mã ghép đôi không đúng. Hãy xem lại mã trên máy kia.") }
            } catch (e: LanPairing.NotPairingException) {
                _state.update { it.copy(lastError = e.message) }
            } catch (e: Exception) {
                Log.w(TAG, "Ghép đôi lỗi", e)
                _state.update { it.copy(lastError = "Không kết nối được máy kia. Hai máy cần dùng chung một mạng Wi‑Fi.") }
            } finally {
                _state.update { it.copy(pairingBusy = false) }
            }
        }
    }

    fun unpair(peerId: String) {
        keys.remove(peerId)
        records.remove(peerId)
        online.remove(peerId)
        engines.remove(peerId)
        vault.put(KEY_PREFIX + peerId, null)
        savePeers()
        publishPeers()
    }

    // ------------------------------------------------------------------ đồng bộ

    fun requestSync(delayMs: Long = 0L) {
        if (keys.isNotEmpty()) triggers.trySend(Trigger(delayMs, manual = false))
    }

    fun syncNow() {
        triggers.trySend(Trigger(0L, manual = true))
    }

    fun bookOpened(fingerprint: String) {
        openBooks.merge(fingerprint, 1, Int::plus)
        requestSync(0L)
    }

    fun bookClosed(fingerprint: String) {
        openBooks.computeIfPresent(fingerprint) { _, count -> if (count <= 1) null else count - 1 }
        _offers.update { it - fingerprint }
        requestSync(500L)
    }

    fun answerOffer(fingerprint: String) {
        _offers.value[fingerprint]?.let { answeredOffers[fingerprint] = it.at }
        _offers.update { it - fingerprint }
    }

    private suspend fun syncLoop() {
        while (true) {
            var trigger = triggers.receive()
            var deadline = SystemClock.elapsedRealtime() + trigger.delayMs
            while (true) {
                val wait = deadline - SystemClock.elapsedRealtime()
                if (wait <= 0) break
                val next = withTimeoutOrNull(wait) { triggers.receive() } ?: break
                deadline = minOf(deadline, SystemClock.elapsedRealtime() + next.delayMs)
                trigger = Trigger(0L, trigger.manual || next.manual)
            }
            while (true) {
                val more = triggers.tryReceive().getOrNull() ?: break
                trigger = Trigger(0L, trigger.manual || more.manual)
            }
            syncAll(trigger.manual)
        }
    }

    private suspend fun syncAll(manual: Boolean) = syncLock.withLock {
        val targets = online.entries.filter { keys.containsKey(it.key) }.map { it.key to it.value }
        if (targets.isEmpty()) {
            if (manual) {
                _state.update {
                    it.copy(
                        lastError = if (keys.isEmpty()) {
                            "Chưa ghép đôi máy nào."
                        } else {
                            "Chưa thấy máy đã ghép đôi trong mạng. Hãy mở ứng dụng trên máy kia (cùng Wi‑Fi)."
                        },
                    )
                }
            }
            return@withLock
        }
        for ((peerId, url) in targets) syncWith(peerId, url, manual)
    }

    private suspend fun syncWith(peerId: String, url: String, manual: Boolean) {
        val key = keys[peerId] ?: return
        val name = records[peerId]?.name ?: "máy kia"
        val engine = engines[peerId]?.takeIf { it.first == url }?.second
            ?: SyncEngine(LanDrive(url, myId, key), localLibrary).also { engines[peerId] = url to it }
        _state.update { it.copy(syncing = true, progress = "Đang đồng bộ với $name…", lastError = if (manual) null else it.lastError) }
        markSyncing(peerId, true)
        try {
            val result = engine.sync(
                SyncEngine.Options(
                    syncFiles = true,
                    transferFiles = true,
                    openBooks = { openBooks.keys.toSet() },
                    answeredOffers = HashMap(answeredOffers),
                ),
                progress = { text -> _state.update { it.copy(progress = text) } },
            )
            val now = System.currentTimeMillis()
            records[peerId]?.lastSyncAt = now
            savePeers()
            _offers.update { current -> current.filterKeys { it !in openBooks.keys || it in result.offers } + result.offers }
            val parts = buildList {
                if (result.uploaded > 0) add("đã gửi ${result.uploaded} cuốn")
                if (result.downloaded > 0) add("đã nhận ${result.downloaded} cuốn")
                if (result.removed.isNotEmpty()) add("đã xoá ${result.removed.size} cuốn")
            }
            if (result.downloaded > 0 || result.removed.isNotEmpty()) {
                importer.postMessage(
                    buildList {
                        if (result.downloaded > 0) add("Đã nhận ${result.downloaded} cuốn từ $name.")
                        if (result.removed.isNotEmpty()) add("Đã xoá ${result.removed.size} cuốn (đã xoá trên $name).")
                    }.joinToString(" "),
                )
            }
            _state.update {
                it.copy(
                    lastSyncAt = now,
                    summary = if (parts.isEmpty()) "Đã khớp với $name." else "Với $name: " + parts.joinToString(", ") + ".",
                    lastError = if (result.failed.isEmpty()) null else "Chưa chuyển được ${result.failed.size} tệp. Sẽ thử lại.",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveException) {
            if (e.code == 401) {
                _state.update { it.copy(lastError = "$name không còn nhận ra máy này. Hãy bỏ ghép đôi rồi ghép đôi lại.") }
            } else {
                _state.update { it.copy(lastError = e.message) }
            }
        } catch (e: IOException) {
            // Máy kia vừa đóng ứng dụng hoặc rời mạng.
            online.remove(peerId)
            if (manual) _state.update { it.copy(lastError = "Mất kết nối với $name.") }
        } catch (e: Exception) {
            Log.w(TAG, "Đồng bộ Wi‑Fi lỗi", e)
            _state.update { it.copy(lastError = "Đồng bộ chưa được: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
            markSyncing(peerId, false)
            _state.update { it.copy(syncing = false, progress = null) }
            publishPeers()
        }
    }

    private fun markSyncing(peerId: String, syncing: Boolean) {
        _state.update { state -> state.copy(peers = state.peers.map { if (it.id == peerId) it.copy(syncing = syncing) else it }) }
    }

    // ------------------------------------------------------------------ lưu trữ & hiển thị

    private fun loadPeers() {
        val text = prefs.getString(KEY_PEERS, null) ?: return
        runCatching {
            val array = JSONArray(text)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val id = o.getString("id")
                val key = vault.get(KEY_PREFIX + id)?.let { LanCrypto.unb64(it) } ?: continue
                keys[id] = key
                records[id] = PeerRecord(id, o.optString("name"), o.optLong("lastSyncAt"), o.optString("url").takeIf { it.isNotEmpty() })
            }
        }
    }

    private fun savePeers() {
        val array = JSONArray()
        records.values.forEach { record ->
            array.put(
                JSONObject()
                    .put("id", record.id)
                    .put("name", record.name)
                    .put("lastSyncAt", record.lastSyncAt)
                    .put("url", record.baseUrl.orEmpty()),
            )
        }
        prefs.edit().putString(KEY_PEERS, array.toString()).apply()
    }

    private fun publishPeers() {
        _state.update { state ->
            val syncing = state.peers.filter { it.syncing }.map { it.id }.toSet()
            state.copy(
                peers = records.values
                    .sortedBy { it.name.lowercase() }
                    .map { LanPeerUi(it.id, it.name, online.containsKey(it.id), it.lastSyncAt, it.id in syncing) },
            )
        }
    }

    private fun publishNearby() {
        _state.update { state ->
            state.copy(nearby = seen.entries.filter { it.value.pairing && !keys.containsKey(it.key) }.map { NearbyDeviceUi(it.key, it.value.name) })
        }
    }

    private fun deviceName(): String {
        val resolver = context.contentResolver
        return runCatching { Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { Settings.Secure.getString(resolver, "bluetooth_name") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim()
    }

    private fun localAddress(): String? = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        manager.getLinkProperties(manager.activeNetwork)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    private fun baseUrl(address: InetAddress, port: Int): String {
        val text = address.hostAddress ?: address.toString().trimStart('/')
        return if (':' in text) "http://[${text.replace("%", "%25")}]:$port" else "http://$text:$port"
    }

    private companion object {
        const val TAG = "LanSync"
        const val SERVICE_TYPE = "_docsach._tcp"
        const val PREFERRED_PORT = 47_123
        const val KEY_ID = "device_id"
        const val KEY_PEERS = "peers"
        const val KEY_PREFIX = "key_"
        const val CONTENT_DELAY_MS = 3_000L
        const val POSITION_DELAY_MS = 20_000L
        const val STOP_DELAY_MS = 15_000L
    }
}
