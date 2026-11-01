package io.github.truongphamtp.docsach.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

/**
 * Gọi Microsoft Graph để đọc/ghi trong thư mục riêng của ứng dụng trên OneDrive
 * ("Ứng dụng/<tên ứng dụng>" — ứng dụng không xem được các tệp khác của bạn).
 */
class GraphClient(
    private val tokens: TokenProvider,
    /** Gốc thư mục ứng dụng (đổi được để kiểm thử với máy chủ giả lập). */
    private val root: String = APP_ROOT,
) : RemoteDrive {

    override suspend fun item(path: String): RemoteItem? =
        request("GET", pathUrl(path)) { connection, code ->
            if (code == 404) null else parseItem(JSONObject(Http.readBody(connection, code)))
        }

    override suspend fun children(folder: String): List<RemoteItem> {
        val out = ArrayList<RemoteItem>()
        var next: String? = "${pathUrl(folder)}:/children?\$top=200&\$select=name,size,eTag"
        while (next != null) {
            val page = request("GET", next) { connection, code ->
                if (code == 404) null else JSONObject(Http.readBody(connection, code))
            } ?: break
            val values = page.optJSONArray("value")
            if (values != null) for (i in 0 until values.length()) values.optJSONObject(i)?.let { out += parseItem(it) }
            next = page.optString("@odata.nextLink").takeIf { it.isNotEmpty() }
        }
        return out
    }

    /** Tải nội dung chữ từ địa chỉ tải về (đã kèm quyền, không cần token). */
    override suspend fun downloadText(url: String): String = withContext(Dispatchers.IO) {
        val connection = Http.open(url, "GET")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw DriveException(code, "download", "Không tải được dữ liệu từ OneDrive ($code).")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Tải tệp về [target], báo tiến độ (đã tải, tổng số byte). */
    override suspend fun downloadTo(url: String, target: File, onProgress: (Long, Long) -> Unit): Unit = withContext(Dispatchers.IO) {
        val connection = Http.open(url, "GET")
        connection.setRequestProperty("Accept", "*/*")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw DriveException(code, "download", "Không tải được tệp từ OneDrive ($code).")
            val total = connection.contentLengthLong
            var done = 0L
            var lastReport = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (done - lastReport > 256 * 1024) {
                            lastReport = done
                            onProgress(done, total)
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("Tải tệp bị ngắt giữa chừng.")
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun putSmall(path: String, bytes: ByteArray, contentType: String, ifMatch: String?, mustBeNew: Boolean): RemoteItem {
        val behavior = if (mustBeNew) "fail" else "replace"
        val url = "${pathUrl(path)}:/content?@microsoft.graph.conflictBehavior=$behavior"
        return request(
            method = "PUT",
            url = url,
            prepare = { connection ->
                connection.setRequestProperty("Content-Type", contentType)
                if (ifMatch != null) connection.setRequestProperty("If-Match", ifMatch)
            },
            body = bytes,
        ) { connection, code ->
            if (code == 404) throw DriveException(404, "itemNotFound", "Không tìm thấy thư mục của ứng dụng trên OneDrive.")
            parseItem(JSONObject(Http.readBody(connection, code)))
        }!!
    }

    /** Tải một tệp sách lên (tệp lớn chia thành nhiều phần 5 MB, mạng chập chờn vẫn đi tiếp được). */
    override suspend fun uploadFile(path: String, file: File, onProgress: (Long, Long) -> Unit) {
        val total = file.length()
        if (total <= SMALL_LIMIT) {
            putSmall(path, file.readBytes(), "application/octet-stream", ifMatch = null, mustBeNew = false)
            onProgress(total, total)
            return
        }
        val session = request(
            method = "POST",
            url = "${pathUrl(path)}:/createUploadSession",
            prepare = { it.setRequestProperty("Content-Type", "application/json") },
            body = """{"item":{"@microsoft.graph.conflictBehavior":"replace"}}""".toByteArray(),
        ) { connection, code -> JSONObject(Http.readBody(connection, code)).getString("uploadUrl") }!!
        try {
            RandomAccessFile(file, "r").use { input ->
                var start = 0L
                val buffer = ByteArray(CHUNK.toInt())
                while (start < total) {
                    coroutineContext.ensureActive()
                    val length = minOf(CHUNK, total - start).toInt()
                    input.seek(start)
                    input.readFully(buffer, 0, length)
                    start = putChunk(session, buffer, length, start, total)
                    onProgress(start, total)
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { runCatching { Http.open(session, "DELETE").apply { responseCode }.disconnect() } }
            throw e
        }
    }

    /** Gửi một phần của tệp; trả về vị trí cần gửi tiếp theo. */
    private suspend fun putChunk(uploadUrl: String, buffer: ByteArray, length: Int, start: Long, total: Long): Long {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return withContext(Dispatchers.IO) {
                    // Địa chỉ phiên tải lên đã kèm quyền: không gửi token (OneDrive sẽ từ chối nếu gửi).
                    val connection = Http.open(uploadUrl, "PUT")
                    try {
                        connection.doOutput = true
                        connection.setFixedLengthStreamingMode(length)
                        connection.setRequestProperty("Content-Range", "bytes $start-${start + length - 1}/$total")
                        connection.outputStream.use { it.write(buffer, 0, length) }
                        val code = connection.responseCode
                        val text = Http.readBody(connection, code)
                        when (code) {
                            200, 201 -> total
                            202 -> nextExpected(text) ?: (start + length)
                            else -> throw DriveException(code, errorCode(text), describe(code, text))
                        }
                    } finally {
                        connection.disconnect()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val fatal = e is DriveException && e.code in 400..499 && e.code != 408 && e.code != 416 && e.code != 429
                if (attempt >= 4 || fatal) throw e
                delay(1500L * attempt)
                // Có thể OneDrive đã nhận phần này nhưng mất phản hồi: hỏi lại vị trí cần gửi tiếp.
                val resume = sessionNext(uploadUrl)
                if (resume != null && resume != start) return resume
            }
        }
    }

    private suspend fun sessionNext(uploadUrl: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = Http.open(uploadUrl, "GET")
            try {
                val code = connection.responseCode
                if (code in 200..299) nextExpected(Http.readBody(connection, code)) else null
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    override suspend fun delete(path: String) {
        request("DELETE", pathUrl(path)) { _, _ -> Unit }
    }

    /**
     * Gửi một yêu cầu tới Graph kèm token. Tự làm mới token khi hết hạn (401) và chờ theo
     * Retry-After khi OneDrive báo bận (429/503). 404 được trả cho [handle] để tự xử lý.
     */
    private suspend fun <T> request(
        method: String,
        url: String,
        prepare: (HttpURLConnection) -> Unit = {},
        body: ByteArray? = null,
        handle: (HttpURLConnection, Int) -> T,
    ): T? {
        var refreshed = false
        var busyRetries = 0
        while (true) {
            val token = tokens.accessToken(forceRefresh = false)
            val outcome = withContext(Dispatchers.IO) {
                val connection = Http.open(url, method)
                try {
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    prepare(connection)
                    if (body != null) {
                        connection.doOutput = true
                        connection.setFixedLengthStreamingMode(body.size)
                        connection.outputStream.use { it.write(body) }
                    }
                    val code = connection.responseCode
                    when {
                        code in 200..299 || code == 404 -> Outcome.Done(handle(connection, code))
                        code == 401 -> Outcome.Unauthorized
                        code == 429 || code == 503 || code == 504 -> Outcome.Busy(connection.getHeaderField("Retry-After")?.toLongOrNull())
                        else -> {
                            val text = Http.readBody(connection, code)
                            throw DriveException(code, errorCode(text), describe(code, text))
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }
            when (outcome) {
                is Outcome.Done<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    return outcome.value as T?
                }
                Outcome.Unauthorized -> {
                    if (refreshed) throw AuthExpiredException("OneDrive từ chối phiên đăng nhập. Hãy đăng nhập lại.")
                    refreshed = true
                    tokens.accessToken(forceRefresh = true)
                }
                is Outcome.Busy -> {
                    busyRetries++
                    if (busyRetries > 4) throw DriveException(429, "busy", "OneDrive đang bận. Sẽ thử lại sau.")
                    delay(((outcome.retryAfterSeconds ?: (2L shl busyRetries)).coerceIn(1, 60)) * 1000)
                }
            }
        }
    }

    private sealed interface Outcome {
        class Done<T>(val value: T) : Outcome
        data object Unauthorized : Outcome
        class Busy(val retryAfterSeconds: Long?) : Outcome
    }

    private fun parseItem(json: JSONObject) = RemoteItem(
        name = json.optString("name"),
        size = json.optLong("size"),
        eTag = json.optString("eTag").takeIf { it.isNotEmpty() },
        downloadUrl = json.optString("@microsoft.graph.downloadUrl").takeIf { it.isNotEmpty() },
    )

    private fun nextExpected(text: String): Long? = runCatching {
        JSONObject(text).optJSONArray("nextExpectedRanges")?.optString(0)?.substringBefore('-')?.toLong()
    }.getOrNull()

    private fun errorCode(text: String): String =
        runCatching { JSONObject(text).optJSONObject("error")?.optString("code") }.getOrNull().orEmpty()

    private fun describe(code: Int, text: String): String {
        val message = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
        return when (code) {
            403 -> "OneDrive không cho phép truy cập thư mục của ứng dụng (403). Tài khoản công việc/trường học có thể bị " +
                "quản trị viên chặn; hãy thử tài khoản Microsoft cá nhân."
            507 -> "OneDrive của bạn đã hết dung lượng."
            else -> "OneDrive báo lỗi $code${if (message.isNotEmpty()) ": $message" else ""}"
        }
    }

    private fun pathUrl(path: String): String =
        "$root:/" + path.trim('/').split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    companion object {
        const val APP_ROOT = "https://graph.microsoft.com/v1.0/me/drive/special/approot"
        private const val SMALL_LIMIT = 4L * 1024 * 1024

        /** 5 MB — là bội số của 320 KB như OneDrive yêu cầu. */
        private const val CHUNK = 5L * 1024 * 1024
    }
}
