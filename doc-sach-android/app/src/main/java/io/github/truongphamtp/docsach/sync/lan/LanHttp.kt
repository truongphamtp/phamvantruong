package io.github.truongphamtp.docsach.sync.lan

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Một yêu cầu HTTP nhận được từ máy khác trong mạng. */
class LanRequest(
    val method: String,
    /** Đường dẫn kèm chuỗi truy vấn, đúng như máy kia gửi (dùng để kiểm chữ ký). */
    val target: String,
    val path: String,
    val query: Map<String, String>,
    private val headers: Map<String, String>,
    val contentLength: Long,
    val body: InputStream,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    /** Đọc hết thân yêu cầu (chỉ dùng cho dữ liệu nhỏ). */
    fun readBody(limit: Int = 32 * 1024 * 1024): ByteArray {
        if (contentLength > limit) throw IOException("Dữ liệu quá lớn")
        return body.readBytes()
    }
}

/** Phản hồi: dữ liệu trong bộ nhớ hoặc một tệp (gửi theo luồng). */
sealed class LanResponse(val code: Int) {
    class Bytes(code: Int, val body: ByteArray, val contentType: String = "application/octet-stream") : LanResponse(code)
    class FileBody(val file: File, val contentType: String = "application/octet-stream") : LanResponse(200)
    class Empty(code: Int) : LanResponse(code)

    companion object {
        fun text(code: Int, message: String) = Bytes(code, message.toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8")
    }
}

/**
 * Máy chủ HTTP/1.1 rất nhỏ chạy trong ứng dụng (mỗi yêu cầu một kết nối), đủ cho việc trao đổi
 * giữa hai máy đọc sách trong cùng mạng Wi‑Fi. Không phụ thuộc Android nên kiểm thử được trên máy tính.
 */
class LanHttpServer(
    private val handler: (LanRequest) -> LanResponse,
    preferredPort: Int = 0,
) : Closeable {
    private val socket: ServerSocket = openSocket(preferredPort)
    private val workers: ExecutorService = Executors.newFixedThreadPool(4) { r -> Thread(r, "dong-bo-wifi").apply { isDaemon = true } }

    val port: Int get() = socket.localPort

    init {
        thread(isDaemon = true, name = "dong-bo-wifi-cho") {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                try {
                    workers.execute { client.use { serve(it) } }
                } catch (e: Exception) {
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = 60_000
            val input = BufferedInputStream(client.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0 && headers.size < 64) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val length = headers["content-length"]?.toLongOrNull() ?: 0L
            val target = parts[1]
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { pair ->
                URLDecoder.decode(pair.substringBefore('='), "UTF-8") to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            }
            val body = LimitedInputStream(input, length)
            val response = try {
                handler(LanRequest(parts[0].uppercase(), target, path, query, headers, length, body))
            } catch (e: Exception) {
                LanResponse.text(500, e.message ?: e.javaClass.simpleName)
            }
            write(client, response)
        } catch (e: SocketException) {
            // Máy kia ngắt kết nối giữa chừng.
        } catch (e: IOException) {
            // Mạng chập chờn: máy kia sẽ thử lại.
        }
    }

    private fun write(client: Socket, response: LanResponse) {
        val out = client.getOutputStream()
        val (length, type) = when (response) {
            is LanResponse.Bytes -> response.body.size.toLong() to response.contentType
            is LanResponse.FileBody -> response.file.length() to response.contentType
            is LanResponse.Empty -> 0L to null
        }
        val head = buildString {
            append("HTTP/1.1 ${response.code} ${reason(response.code)}\r\n")
            if (type != null) append("Content-Type: $type\r\n")
            append("Content-Length: $length\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        when (response) {
            is LanResponse.Bytes -> out.write(response.body)
            is LanResponse.FileBody -> response.file.inputStream().use { it.copyTo(out, 64 * 1024) }
            is LanResponse.Empty -> Unit
        }
        out.flush()
    }

    override fun close() {
        runCatching { socket.close() }
        workers.shutdownNow()
    }

    private class LimitedInputStream(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = super.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()
    }

    private companion object {
        fun openSocket(preferred: Int): ServerSocket {
            if (preferred > 0) {
                runCatching {
                    return ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(preferred), 16)
                    }
                }
            }
            return ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(0), 16)
            }
        }

        fun readLine(input: InputStream): String? {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (line.size() == 0) null else line.toString("ISO-8859-1")
                if (b == '\n'.code) break
                if (b != '\r'.code) line.write(b)
                if (line.size() > 16 * 1024) throw IOException("Dòng quá dài")
            }
            return line.toString("ISO-8859-1")
        }

        fun reason(code: Int): String = when (code) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            412 -> "Precondition Failed"
            else -> "Status"
        }
    }
}
