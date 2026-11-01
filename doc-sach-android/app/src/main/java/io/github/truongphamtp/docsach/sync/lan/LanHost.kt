package io.github.truongphamtp.docsach.sync.lan

import io.github.truongphamtp.docsach.core.BookRecord
import io.github.truongphamtp.docsach.core.LibraryState
import io.github.truongphamtp.docsach.core.PositionOffer
import io.github.truongphamtp.docsach.core.SyncMerge
import io.github.truongphamtp.docsach.data.LibraryJson
import io.github.truongphamtp.docsach.sync.LocalLibrary
import io.github.truongphamtp.docsach.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** Một máy đã ghép đôi. */
data class LanPeer(val id: String, val name: String)

/** Nơi lưu các máy đã ghép đôi và khoá chung của từng máy. */
interface LanPeerStore {
    fun keyOf(peerId: String): ByteArray?

    fun nameOf(peerId: String): String?

    fun save(peer: LanPeer, key: ByteArray)
}

/** Những việc máy chủ báo lại cho ứng dụng (cập nhật giao diện, thông báo...). */
interface LanHostEvents {
    fun onPaired(peer: LanPeer) {}

    fun onPairingFailed(attemptsLeft: Int) {}

    fun onApplied(peer: LanPeer, applied: LanHost.Applied) {}

    fun onNudge(peer: LanPeer) {}
}

/**
 * Phần "máy chủ" của đồng bộ qua Wi‑Fi: cho máy đã ghép đôi xem thư viện của máy này như một ổ đĩa
 * (thu-vien.json + thư mục sach/), để máy kia chạy [SyncEngine] với nó. Khi máy kia ghi thư viện đã gộp,
 * máy này áp ngay vào thư viện của mình và nhận các tệp sách vừa được gửi sang.
 */
class LanHost(
    private val myId: String,
    private val myName: () -> String,
    private val peers: LanPeerStore,
    private val local: LocalLibrary,
    private val stashDir: File,
    private val openBooks: () -> Set<String>,
    private val answeredOffers: () -> Map<String, Long>,
    private val events: LanHostEvents,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Kết quả khi máy kia gửi thư viện đã gộp sang. */
    data class Applied(val received: Int, val removed: List<BookRecord>, val offers: Map<String, PositionOffer>)

    private class Pending(val name: String, val key: ByteArray, val expires: Long)

    private val libraryLock = Mutex()
    private val seenNonces = object : LinkedHashMap<String, Long>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 4096
    }
    private val pending = HashMap<String, Pending>()
    private var pairingUntil = 0L
    private var failures = 0

    @Volatile
    var pairingCode: String? = null
        private set

    val isPairing: Boolean get() = pairingCode != null && clock() < pairingUntil

    /** Bật chế độ ghép đôi: trả về mã 6 số để hiện lên màn hình. */
    @Synchronized
    fun startPairing(durationMs: Long = 5 * 60_000L): String {
        val code = LanCrypto.newCode()
        pairingCode = code
        pairingUntil = clock() + durationMs
        failures = 0
        pending.clear()
        return code
    }

    @Synchronized
    fun stopPairing() {
        pairingCode = null
        pending.clear()
    }

    fun handle(request: LanRequest): LanResponse = try {
        when {
            request.path == "/v1/hello" && request.method == "GET" -> hello()
            request.path == "/v1/pair/start" && request.method == "POST" -> pairStart(request)
            request.path == "/v1/pair/confirm" && request.method == "POST" -> pairConfirm(request)
            request.path.startsWith("/v1/") -> authenticated(request)
            else -> LanResponse.text(404, "Không có")
        }
    } catch (e: Exception) {
        LanResponse.text(400, e.message ?: "Yêu cầu không hợp lệ")
    }

    // ------------------------------------------------------------------ chào hỏi, ghép đôi

    private fun hello(): LanResponse = json(
        JSONObject()
            .put("app", APP)
            .put("v", 1)
            .put("id", myId)
            .put("name", myName())
            .put("pairing", isPairing),
    )

    private fun pairStart(request: LanRequest): LanResponse {
        val code = synchronized(this) {
            if (!isPairing) return LanResponse.text(403, "Máy này chưa bật ghép đôi.")
            pairingCode!!
        }
        val body = JSONObject(String(request.readBody(64 * 1024), Charsets.UTF_8))
        val peerId = body.getString("id")
        if (!validId(peerId) || peerId == myId) return LanResponse.text(400, "Mã máy không hợp lệ")
        val peerNonce = LanCrypto.unb64(body.getString("nonce"))
        val keyPair = LanCrypto.newKeyPair()
        val myNonce = LanCrypto.randomBytes(16)
        val shared = LanCrypto.sharedSecret(keyPair, LanCrypto.unb64(body.getString("pub")))
        val key = LanCrypto.pairingKey(shared, peerNonce + myNonce, code, hostId = myId, joinerId = peerId)
        synchronized(this) {
            pending[peerId] = Pending(body.optString("name").take(80), key, clock() + 2 * 60_000L)
        }
        return json(
            JSONObject()
                .put("app", APP)
                .put("id", myId)
                .put("name", myName())
                .put("pub", LanCrypto.b64(LanCrypto.publicKey(keyPair)))
                .put("nonce", LanCrypto.b64(myNonce)),
        )
    }

    private fun pairConfirm(request: LanRequest): LanResponse {
        val peerId = request.header(LanAuth.ID) ?: return unauthorized()
        val candidate = synchronized(this) { pending[peerId] } ?: return unauthorized()
        if (candidate.expires < clock()) return unauthorized()
        val body = request.readBody(64 * 1024)
        if (!verify(request, peerId, candidate.key, body)) {
            val left = synchronized(this) {
                failures++
                pending.remove(peerId)
                if (failures >= MAX_PAIRING_FAILURES) stopPairing()
                MAX_PAIRING_FAILURES - failures
            }
            events.onPairingFailed(left.coerceAtLeast(0))
            return LanResponse.text(401, "Mã ghép đôi không đúng.")
        }
        LanCrypto.decrypt(candidate.key, body, requestAad(request))
        val peer = LanPeer(peerId, candidate.name.ifBlank { "Máy khác" })
        peers.save(peer, candidate.key)
        stopPairing()
        events.onPaired(peer)
        return encrypted(candidate.key, request, JSONObject().put("ok", true).put("id", myId).put("name", myName()))
    }

    // ------------------------------------------------------------------ ổ đĩa ảo

    private fun authenticated(request: LanRequest): LanResponse {
        val peerId = request.header(LanAuth.ID) ?: return unauthorized()
        val key = peers.keyOf(peerId) ?: return unauthorized()
        val peer = LanPeer(peerId, peers.nameOf(peerId).orEmpty())
        val path = request.query["path"].orEmpty()
        val upload = request.method == "PUT" && request.path == "/v1/content" && path.startsWith("$FOLDER/")
        val body = if (upload) null else request.readBody()
        if (!verify(request, peerId, key, body)) return unauthorized()
        return when {
            request.method == "GET" && request.path == "/v1/item" -> item(key, request, path)
            request.method == "GET" && request.path == "/v1/children" -> children(key, request, request.query["folder"].orEmpty())
            request.method == "GET" && request.path == "/v1/content" -> content(key, request, path)
            request.method == "PUT" && request.path == "/v1/content" && path == SyncEngine.LIBRARY_FILE -> putLibrary(peer, key, request, body!!)
            upload -> stash(key, request, path.removePrefix("$FOLDER/"))
            request.method == "DELETE" && request.path == "/v1/content" -> {
                if (path.startsWith("$FOLDER/") && validBookName(path.removePrefix("$FOLDER/"))) File(stashDir, path.removePrefix("$FOLDER/")).delete()
                LanResponse.Empty(204)
            }
            request.method == "POST" && request.path == "/v1/nudge" -> {
                events.onNudge(peer)
                LanResponse.Empty(204)
            }
            else -> LanResponse.text(404, "Không có")
        }
    }

    private fun libraryText(state: LibraryState): String = LibraryJson.stateToJson(SyncMerge.portable(state)).toString()

    private fun etag(text: String): String = LanCrypto.sha256Hex(text.toByteArray(Charsets.UTF_8)).take(32)

    private fun item(key: ByteArray, request: LanRequest, path: String): LanResponse {
        if (path == SyncEngine.LIBRARY_FILE) {
            val text = libraryText(runBlocking { local.snapshot() })
            return encrypted(key, request, JSONObject().put("name", path).put("size", text.length).put("eTag", etag(text)))
        }
        val name = path.removePrefix("$FOLDER/")
        if (!path.startsWith("$FOLDER/") || !validBookName(name)) return LanResponse.text(404, "Không có")
        val file = bookFile(name) ?: return LanResponse.text(404, "Không có")
        return encrypted(key, request, JSONObject().put("name", name).put("size", file.length()))
    }

    private fun children(key: ByteArray, request: LanRequest, folder: String): LanResponse {
        val values = JSONArray()
        if (folder == FOLDER) {
            val names = HashSet<String>()
            runBlocking { local.snapshot() }.books.forEach { book ->
                val file = local.fileOf(book)
                val name = SyncMerge.remoteFileName(book)
                if (file.isFile && names.add(name)) values.put(JSONObject().put("name", name).put("size", file.length()))
            }
            stashDir.listFiles()?.forEach { file ->
                if (validBookName(file.name) && names.add(file.name)) values.put(JSONObject().put("name", file.name).put("size", file.length()))
            }
        }
        return encrypted(key, request, JSONObject().put("value", values))
    }

    private fun content(key: ByteArray, request: LanRequest, path: String): LanResponse {
        if (path == SyncEngine.LIBRARY_FILE) {
            val text = libraryText(runBlocking { local.snapshot() })
            return LanResponse.Bytes(200, LanCrypto.encrypt(key, text.toByteArray(Charsets.UTF_8), responseAad(request)))
        }
        val name = path.removePrefix("$FOLDER/")
        if (!path.startsWith("$FOLDER/") || !validBookName(name)) return LanResponse.text(404, "Không có")
        val file = bookFile(name) ?: return LanResponse.text(404, "Không có")
        return LanResponse.FileBody(file)
    }

    private fun bookFile(name: String): File? {
        val book = runBlocking { local.snapshot() }.books.firstOrNull { SyncMerge.remoteFileName(it) == name }
        book?.let { local.fileOf(it).takeIf { file -> file.isFile } }?.let { return it }
        return File(stashDir, name).takeIf { it.isFile }
    }

    /** Máy kia gửi thư viện đã gộp: áp vào máy này (nếu thư viện ở đây chưa đổi kể từ lúc máy kia đọc). */
    private fun putLibrary(peer: LanPeer, key: ByteArray, request: LanRequest, body: ByteArray): LanResponse {
        val payload = JSONObject(String(LanCrypto.decrypt(key, body, requestAad(request)), Charsets.UTF_8))
        val ifMatch = payload.optString("ifMatch").takeIf { it.isNotEmpty() }
        val text = String(LanCrypto.unb64(payload.getString("data")), Charsets.UTF_8)
        val merged = LibraryJson.stateFromText(text)
        return runBlocking {
            libraryLock.withLock {
                val current = local.snapshot()
                if (ifMatch != null && ifMatch != etag(libraryText(current))) {
                    return@withLock LanResponse.text(412, "Thư viện trên máy này vừa thay đổi.")
                }
                val now = clock()
                val mine = current.books.associateBy { it.fingerprint }
                val open = openBooks()
                val removed = local.apply(LibraryState(merged.books.filter { it.fingerprint in mine }, merged.deletedBooks), open, now)
                removed.forEach { local.deleteFiles(it) }
                var received = 0
                for (book in merged.books) {
                    if (book.fingerprint in mine) continue
                    val stashed = File(stashDir, SyncMerge.remoteFileName(book))
                    if (!stashed.isFile) continue
                    val record = local.adopt(stashed, book)
                    stashed.delete()
                    if (record != null) {
                        local.apply(LibraryState(listOf(record)), open, now)
                        received++
                    }
                }
                val after = local.snapshot()
                val applied = Applied(received, removed, SyncMerge.offers(merged, after, open, answeredOffers()))
                events.onApplied(peer, applied)
                encrypted(key, request, JSONObject().put("ok", true).put("eTag", etag(libraryText(after))))
            }
        }
    }

    private fun stash(key: ByteArray, request: LanRequest, name: String): LanResponse {
        if (!validBookName(name)) return LanResponse.text(400, "Tên tệp không hợp lệ")
        stashDir.mkdirs()
        val part = File(stashDir, "$name.part")
        part.outputStream().use { request.body.copyTo(it, 64 * 1024) }
        if (part.length() != request.contentLength) {
            part.delete()
            return LanResponse.text(400, "Tệp nhận được không đủ")
        }
        val target = File(stashDir, name)
        target.delete()
        if (!part.renameTo(target)) {
            part.delete()
            return LanResponse.text(500, "Không lưu được tệp")
        }
        return encrypted(key, request, JSONObject().put("ok", true))
    }

    /** Dọn tệp sách nhận dở (máy kia ngắt giữa chừng) cũ hơn một ngày. */
    fun cleanStash() {
        val cutoff = clock() - 24 * 60 * 60_000L
        stashDir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    // ------------------------------------------------------------------ tiện ích

    private fun verify(request: LanRequest, peerId: String, key: ByteArray, body: ByteArray?): Boolean {
        val time = request.header(LanAuth.TIME)?.toLongOrNull() ?: return false
        if (abs(clock() - time) > LanAuth.MAX_SKEW_MS) return false
        val nonce = request.header(LanAuth.NONCE) ?: return false
        val bodyHash = request.header(LanAuth.BODY) ?: return false
        val signature = request.header(LanAuth.SIG) ?: return false
        if (body != null && bodyHash != LanCrypto.sha256Hex(body)) return false
        if (body == null && bodyHash != LanAuth.UNSIGNED_BODY) return false
        val expected = LanAuth.signature(key, request.method, request.target, time, nonce, bodyHash)
        if (!LanCrypto.sameBytes(expected.toByteArray(), signature.toByteArray())) return false
        synchronized(seenNonces) {
            val id = "$peerId|$nonce"
            if (seenNonces.containsKey(id)) return false
            seenNonces[id] = time
        }
        return true
    }

    private fun encrypted(key: ByteArray, request: LanRequest, body: JSONObject): LanResponse =
        LanResponse.Bytes(200, LanCrypto.encrypt(key, body.toString().toByteArray(Charsets.UTF_8), responseAad(request)))

    private fun json(body: JSONObject): LanResponse = LanResponse.Bytes(200, body.toString().toByteArray(Charsets.UTF_8), "application/json")

    private fun unauthorized(): LanResponse = LanResponse.text(401, "Máy này không nhận ra máy gửi. Hãy ghép đôi lại.")

    companion object {
        const val APP = "docsach"
        const val FOLDER = SyncEngine.BOOKS_FOLDER
        private const val MAX_PAIRING_FAILURES = 5
        private val BOOK_NAME = Regex("^[0-9a-f]{40}\\.(epub|pdf|txt)$")
        private val ID = Regex("^[A-Za-z0-9-]{8,64}$")

        fun validBookName(name: String): Boolean = BOOK_NAME.matches(name)

        fun validId(id: String): Boolean = ID.matches(id)

        internal fun requestAad(request: LanRequest) = "req|${request.method} ${request.target}"

        internal fun responseAad(request: LanRequest) = "resp|${request.method} ${request.target}"
    }
}
