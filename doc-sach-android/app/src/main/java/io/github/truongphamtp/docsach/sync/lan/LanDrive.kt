package io.github.truongphamtp.docsach.sync.lan

import io.github.truongphamtp.docsach.sync.DriveException
import io.github.truongphamtp.docsach.sync.RemoteDrive
import io.github.truongphamtp.docsach.sync.RemoteItem
import io.github.truongphamtp.docsach.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

/** Thông tin một máy đọc sách trong mạng (trả lời /v1/hello). */
data class LanHello(val id: String, val name: String, val pairing: Boolean)

/** Gửi yêu cầu HTTP tới máy khác trong mạng. */
internal object LanHttpClient {
    fun open(url: String, method: String, connectTimeout: Int = 4_000): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            this.connectTimeout = connectTimeout
            readTimeout = 60_000
            useCaches = false
            instanceFollowRedirects = false
        }

    fun read(connection: HttpURLConnection, code: Int): ByteArray =
        runCatching { (if (code in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes() } }
            .getOrNull() ?: ByteArray(0)

    /** Hỏi máy ở [baseUrl] là máy nào (null nếu không phải ứng dụng Đọc Sách hoặc không trả lời). */
    suspend fun hello(baseUrl: String): LanHello? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = open("$baseUrl/v1/hello", "GET", connectTimeout = 2_500)
            try {
                val code = connection.responseCode
                if (code != 200) return@runCatching null
                val json = JSONObject(String(read(connection, code), Charsets.UTF_8))
                if (json.optString("app") != LanHost.APP) return@runCatching null
                LanHello(json.getString("id"), json.optString("name"), json.optBoolean("pairing"))
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }
}

/** Ghép đôi với một máy đang hiện mã 6 số. */
object LanPairing {
    data class Result(val peerId: String, val peerName: String, val key: ByteArray)

    class WrongCodeException : Exception("Mã ghép đôi không đúng.")

    class NotPairingException : Exception("Máy kia chưa bật ghép đôi (bấm \"Ghép đôi máy mới\" trên máy đó).")

    suspend fun pair(baseUrl: String, myId: String, myName: String, code: String, now: () -> Long = System::currentTimeMillis): Result =
        withContext(Dispatchers.IO) {
            val keyPair = LanCrypto.newKeyPair()
            val myNonce = LanCrypto.randomBytes(16)
            val start = JSONObject()
                .put("id", myId)
                .put("name", myName)
                .put("pub", LanCrypto.b64(LanCrypto.publicKey(keyPair)))
                .put("nonce", LanCrypto.b64(myNonce))
                .toString()
                .toByteArray(Charsets.UTF_8)
            val first = LanHttpClient.open("$baseUrl/v1/pair/start", "POST")
            val reply = try {
                first.doOutput = true
                first.setRequestProperty("Content-Type", "application/json")
                first.setFixedLengthStreamingMode(start.size)
                first.outputStream.use { it.write(start) }
                val code1 = first.responseCode
                if (code1 == 403) throw NotPairingException()
                if (code1 != 200) throw IOException("Máy kia trả lỗi $code1 khi ghép đôi.")
                JSONObject(String(LanHttpClient.read(first, code1), Charsets.UTF_8))
            } finally {
                first.disconnect()
            }
            val hostId = reply.getString("id")
            val hostName = reply.optString("name")
            val shared = LanCrypto.sharedSecret(keyPair, LanCrypto.unb64(reply.getString("pub")))
            val key = LanCrypto.pairingKey(shared, myNonce + LanCrypto.unb64(reply.getString("nonce")), code.trim(), hostId = hostId, joinerId = myId)

            val target = "/v1/pair/confirm"
            val body = LanCrypto.encrypt(key, JSONObject().put("ok", true).toString().toByteArray(Charsets.UTF_8), "req|POST $target")
            val second = LanHttpClient.open("$baseUrl$target", "POST")
            try {
                LanAuth.headers(key, myId, "POST", target, LanCrypto.sha256Hex(body), now()).forEach { (k, v) -> second.setRequestProperty(k, v) }
                second.doOutput = true
                second.setFixedLengthStreamingMode(body.size)
                second.outputStream.use { it.write(body) }
                val code2 = second.responseCode
                if (code2 == 401) throw WrongCodeException()
                if (code2 != 200) throw IOException("Máy kia trả lỗi $code2 khi ghép đôi.")
                val ok = JSONObject(String(LanCrypto.decrypt(key, LanHttpClient.read(second, code2), "resp|POST $target"), Charsets.UTF_8))
                if (!ok.optBoolean("ok")) throw IOException("Ghép đôi không thành công.")
            } finally {
                second.disconnect()
            }
            Result(hostId, hostName, key)
        }
}

/**
 * Thư viện của máy kia (đã ghép đôi), nhìn như một ổ đĩa để [SyncEngine] đồng bộ:
 * đọc/ghi thư viện (mã hoá), tải lên/tải về tệp sách.
 */
class LanDrive(
    private val baseUrl: String,
    private val myId: String,
    private val key: ByteArray,
    private val now: () -> Long = System::currentTimeMillis,
) : RemoteDrive {

    private class Reply(val code: Int, val body: ByteArray)

    private suspend fun call(method: String, target: String, body: ByteArray? = null): Reply = withContext(Dispatchers.IO) {
        val payload = body ?: ByteArray(0)
        val connection = LanHttpClient.open("$baseUrl$target", method)
        try {
            LanAuth.headers(key, myId, method, target, LanCrypto.sha256Hex(payload), now()).forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            Reply(code, LanHttpClient.read(connection, code))
        } finally {
            connection.disconnect()
        }
    }

    private fun fail(reply: Reply): Nothing {
        val message = String(reply.body, Charsets.UTF_8).take(200)
        throw when (reply.code) {
            401 -> DriveException(401, "unauthorized", "Máy kia không nhận ra máy này. Hãy bỏ ghép đôi rồi ghép đôi lại.")
            412 -> DriveException(412, "changed", "Máy kia vừa thay đổi thư viện.")
            else -> DriveException(reply.code, "error", "Máy kia báo lỗi ${reply.code}: $message")
        }
    }

    private fun json(method: String, target: String, reply: Reply): JSONObject =
        JSONObject(String(LanCrypto.decrypt(key, reply.body, "resp|$method $target"), Charsets.UTF_8))

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    override suspend fun item(path: String): RemoteItem? {
        val target = "/v1/item?path=${enc(path)}"
        val reply = call("GET", target)
        if (reply.code == 404) return null
        if (reply.code != 200) fail(reply)
        val body = json("GET", target, reply)
        return RemoteItem(
            name = body.optString("name"),
            size = body.optLong("size"),
            eTag = body.optString("eTag").takeIf { it.isNotEmpty() },
            downloadUrl = CONTENT + path,
        )
    }

    override suspend fun children(folder: String): List<RemoteItem> {
        val target = "/v1/children?folder=${enc(folder)}"
        val reply = call("GET", target)
        if (reply.code != 200) fail(reply)
        val values = json("GET", target, reply).optJSONArray("value") ?: return emptyList()
        return (0 until values.length()).mapNotNull { i ->
            values.optJSONObject(i)?.let { RemoteItem(it.optString("name"), it.optLong("size"), null, null) }
        }
    }

    override suspend fun downloadText(url: String): String {
        val target = "/v1/content?path=${enc(url.removePrefix(CONTENT))}"
        val reply = call("GET", target)
        if (reply.code != 200) fail(reply)
        return String(LanCrypto.decrypt(key, reply.body, "resp|GET $target"), Charsets.UTF_8)
    }

    override suspend fun downloadTo(url: String, target: File, onProgress: (Long, Long) -> Unit): Unit = withContext(Dispatchers.IO) {
        val path = "/v1/content?path=${enc(url.removePrefix(CONTENT))}"
        val connection = LanHttpClient.open("$baseUrl$path", "GET")
        try {
            LanAuth.headers(key, myId, "GET", path, LanCrypto.sha256Hex(ByteArray(0)), now()).forEach { (k, v) -> connection.setRequestProperty(k, v) }
            val code = connection.responseCode
            if (code != 200) fail(Reply(code, LanHttpClient.read(connection, code)))
            val total = connection.contentLengthLong
            var done = 0L
            var reported = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (done - reported > 512 * 1024) {
                            reported = done
                            onProgress(done, total)
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("Nhận tệp bị ngắt giữa chừng.")
            onProgress(done, total)
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun putSmall(path: String, bytes: ByteArray, contentType: String, ifMatch: String?, mustBeNew: Boolean): RemoteItem {
        require(path == SyncEngine.LIBRARY_FILE) { "Chỉ ghi được thư viện" }
        val target = "/v1/content?path=${enc(path)}"
        val payload = JSONObject().put("ifMatch", ifMatch.orEmpty()).put("data", LanCrypto.b64(bytes)).toString().toByteArray(Charsets.UTF_8)
        val reply = call("PUT", target, LanCrypto.encrypt(key, payload, "req|PUT $target"))
        if (reply.code != 200) fail(reply)
        json("PUT", target, reply)
        // Máy kia có thể giữ lại một phần của nó (sách đang mở) nên lần sau đọc lại cho chắc.
        return RemoteItem(path, bytes.size.toLong(), eTag = null, downloadUrl = CONTENT + path)
    }

    override suspend fun uploadFile(path: String, file: File, onProgress: (Long, Long) -> Unit): Unit = withContext(Dispatchers.IO) {
        val target = "/v1/content?path=${enc(path)}"
        val connection = LanHttpClient.open("$baseUrl$target", "PUT")
        try {
            LanAuth.headers(key, myId, "PUT", target, LanAuth.UNSIGNED_BODY, now()).forEach { (k, v) -> connection.setRequestProperty(k, v) }
            val total = file.length()
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(total)
            var sent = 0L
            var reported = 0L
            connection.outputStream.use { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        sent += n
                        if (sent - reported > 512 * 1024) {
                            reported = sent
                            onProgress(sent, total)
                        }
                    }
                }
            }
            val code = connection.responseCode
            if (code != 200) fail(Reply(code, LanHttpClient.read(connection, code)))
            onProgress(total, total)
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun delete(path: String) {
        val reply = call("DELETE", "/v1/content?path=${enc(path)}")
        if (reply.code != 204 && reply.code != 200 && reply.code != 404) fail(reply)
    }

    /** Báo máy kia "máy này vừa có thay đổi" để nó đồng bộ ngay. */
    suspend fun nudge() {
        val reply = call("POST", "/v1/nudge", ByteArray(0))
        if (reply.code != 204 && reply.code != 200) fail(reply)
    }

    private companion object {
        const val CONTENT = "content:"
    }
}
