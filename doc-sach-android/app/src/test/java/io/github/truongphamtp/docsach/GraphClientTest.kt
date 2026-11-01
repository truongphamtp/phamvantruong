package io.github.truongphamtp.docsach

import io.github.truongphamtp.docsach.sync.AuthExpiredException
import io.github.truongphamtp.docsach.sync.DriveException
import io.github.truongphamtp.docsach.sync.GraphClient
import io.github.truongphamtp.docsach.sync.TokenProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Máy chủ HTTP/1.1 tối giản, đủ cho HttpURLConnection (mỗi yêu cầu một kết nối).
 * Không dùng com.sun.net.httpserver vì kiểm thử Android không biên dịch được với gói đó.
 */
private class MiniServer(private val handler: (Request) -> Response?) : Closeable {
    class Request(val method: String, val path: String, val query: String, private val headers: Map<String, String>, val body: ByteArray) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    /** Phản hồi; [handler] trả về null nghĩa là cắt kết nối mà không trả lời (giả lập mất phản hồi). */
    class Response(val code: Int, val body: ByteArray? = null, val headers: Map<String, String> = emptyMap())

    val failures: MutableList<Throwable> = Collections.synchronizedList(ArrayList())
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort

    init {
        thread(isDaemon = true, name = "may-chu-gia-lap") {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                try {
                    client.use { serve(it) }
                } catch (e: Throwable) {
                    failures += e
                }
            }
        }
    }

    private fun serve(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val target = parts[1]
        val response = handler(Request(parts[0], target.substringBefore('?'), target.substringAfter('?', ""), headers, body)) ?: return
        val bytes = response.body ?: ByteArray(0)
        val head = buildString {
            append("HTTP/1.1 ${response.code} OK\r\n")
            response.headers.forEach { (k, v) -> append("$k: $v\r\n") }
            if (response.code != 204) append("Content-Type: application/json\r\n")
            append("Content-Length: ${if (response.code == 204) 0 else bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = client.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        if (response.code != 204) out.write(bytes)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.size() == 0) null else line.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) line.write(b)
        }
        return line.toString("ISO-8859-1")
    }

    override fun close() {
        socket.close()
    }
}

/** Kiểm tra cách gọi Microsoft Graph với một máy chủ giả lập chạy trên máy. */
class GraphClientTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MiniServer
    private lateinit var base: String
    private val files = Collections.synchronizedMap(LinkedHashMap<String, ByteArray>())
    private val etags = HashMap<String, Int>()
    private val sessions = HashMap<String, ByteArrayOutputStream>()
    private val sessionTargets = HashMap<String, String>()
    private val authHeadersOnUpload = Collections.synchronizedList(ArrayList<String?>())

    @Volatile
    private var throttleNextChildren = false

    @Volatile
    private var dropFirstChunkResponse = false
    private var refreshes = 0

    private val tokens = object : TokenProvider {
        private var token = "cu"

        override suspend fun accessToken(forceRefresh: Boolean): String {
            if (forceRefresh) {
                refreshes++
                token = "tot"
            }
            return token
        }
    }

    private lateinit var client: GraphClient

    @Before
    fun startServer() {
        server = MiniServer(::handle)
        base = "http://127.0.0.1:${server.port}"
        client = GraphClient(tokens, "$base/v1.0/me/drive/special/approot")
    }

    @After
    fun stopServer() {
        server.close()
        server.failures.firstOrNull()?.let { throw AssertionError("Máy chủ giả lập gặp lỗi", it) }
    }

    private fun json(code: Int, body: String, headers: Map<String, String> = emptyMap()) =
        MiniServer.Response(code, body.toByteArray(), headers)

    private fun handle(request: MiniServer.Request): MiniServer.Response? {
        val path = URLDecoder.decode(request.path, "UTF-8")
        return when {
            path.startsWith("/download/") -> files[path.removePrefix("/download/")]?.let { MiniServer.Response(200, it) } ?: json(404, "{}")
            path.startsWith("/upload/") -> upload(request, path.removePrefix("/upload/"))
            request.header("Authorization") != "Bearer tot" -> json(401, """{"error":{"code":"InvalidAuthenticationToken"}}""")
            else -> graph(request, path.substringAfter("/v1.0/me/drive/special/approot:/"))
        }
    }

    private fun graph(request: MiniServer.Request, rest: String): MiniServer.Response {
        val method = request.method
        return when {
            rest.endsWith(":/children") -> {
                if (throttleNextChildren) {
                    throttleNextChildren = false
                    return json(429, "{}", mapOf("Retry-After" to "1"))
                }
                val folder = rest.removeSuffix(":/children")
                val names = files.keys.filter { it.startsWith("$folder/") }
                if (names.isEmpty()) return json(404, "{}")
                // Mỗi trang 2 tệp, có đường dẫn trang sau như Graph.
                val skip = Regex("skip=(\\d+)").find(request.query)?.groupValues?.get(1)?.toInt() ?: 0
                val page = names.drop(skip).take(2)
                val body = JSONObject().put("value", JSONArray(page.map { itemJson(it) }))
                if (skip + 2 < names.size) body.put("@odata.nextLink", "$base/v1.0/me/drive/special/approot:/$folder:/children?skip=${skip + 2}")
                json(200, body.toString())
            }
            rest.endsWith(":/content") && method == "PUT" -> {
                val name = rest.removeSuffix(":/content")
                val ifMatch = request.header("If-Match")
                if ("conflictBehavior=fail" in request.query && name in files) return json(409, """{"error":{"code":"nameAlreadyExists"}}""")
                if (ifMatch != null && ifMatch != etag(name)) return json(412, """{"error":{"code":"preconditionFailed","message":"đã đổi"}}""")
                files[name] = request.body
                etags[name] = (etags[name] ?: 0) + 1
                json(201, itemJson(name).toString())
            }
            rest.endsWith(":/createUploadSession") && method == "POST" -> {
                val id = "phien${sessions.size + 1}"
                sessions[id] = ByteArrayOutputStream()
                sessionTargets[id] = rest.removeSuffix(":/createUploadSession")
                json(200, JSONObject().put("uploadUrl", "$base/upload/$id").toString())
            }
            method == "DELETE" -> {
                files.remove(rest)
                MiniServer.Response(204)
            }
            method == "GET" -> {
                if (rest !in files) return json(404, """{"error":{"code":"itemNotFound"}}""")
                json(200, itemJson(rest).toString())
            }
            else -> json(400, "{}")
        }
    }

    private fun upload(request: MiniServer.Request, id: String): MiniServer.Response? {
        val buffer = sessions[id] ?: return json(404, "{}")
        if (request.method == "GET") {
            return json(200, JSONObject().put("nextExpectedRanges", JSONArray(listOf("${buffer.size()}-"))).toString())
        }
        authHeadersOnUpload += request.header("Authorization")
        val range = request.header("Content-Range")!! // bytes a-b/total
        val (start, end) = range.removePrefix("bytes ").substringBefore('/').split('-').map { it.toLong() }
        val total = range.substringAfter('/').toLong()
        if (start.toInt() != buffer.size()) return json(416, "{}")
        assertEquals(end - start + 1, request.body.size.toLong())
        buffer.write(request.body)
        if (dropFirstChunkResponse) {
            // OneDrive đã nhận nhưng phản hồi bị mất giữa đường.
            dropFirstChunkResponse = false
            return null
        }
        return if (buffer.size().toLong() == total) {
            val name = sessionTargets[id]!!
            files[name] = buffer.toByteArray()
            json(201, itemJson(name).toString())
        } else {
            json(202, JSONObject().put("nextExpectedRanges", JSONArray(listOf("${buffer.size()}-"))).toString())
        }
    }

    private fun etag(name: String) = "\"{$name},${etags[name] ?: 0}\""

    private fun itemJson(name: String) = JSONObject()
        .put("name", name.substringAfterLast('/'))
        .put("size", files[name]!!.size)
        .put("eTag", etag(name))
        .put("@microsoft.graph.downloadUrl", "$base/download/$name")

    @Test
    fun readWriteWithETagsAndTokenRefresh() = runBlocking {
        assertNull(client.item("thu-vien.json"))
        assertEquals(1, refreshes) // token cũ ("cu") bị từ chối → làm mới một lần rồi gọi lại.

        val first = client.putSmall("thu-vien.json", "{\"a\":1}".toByteArray(), "application/json", ifMatch = null, mustBeNew = true)
        try {
            client.putSmall("thu-vien.json", "{}".toByteArray(), "application/json", ifMatch = null, mustBeNew = true)
            fail("phải báo đã có tệp")
        } catch (e: DriveException) {
            assertEquals(409, e.code)
        }
        val second = client.putSmall("thu-vien.json", "{\"a\":2}".toByteArray(), "application/json", ifMatch = first.eTag, mustBeNew = false)
        try {
            client.putSmall("thu-vien.json", "{\"a\":3}".toByteArray(), "application/json", ifMatch = first.eTag, mustBeNew = false)
            fail("phải báo tệp đã bị đổi")
        } catch (e: DriveException) {
            assertEquals(412, e.code)
        }
        val item = client.item("thu-vien.json")!!
        assertEquals(second.eTag, item.eTag)
        assertEquals("{\"a\":2}", client.downloadText(item.downloadUrl!!))
    }

    @Test
    fun childrenAreListedAcrossPagesEvenWhenThrottled() = runBlocking {
        assertTrue(client.children("sach").isEmpty())
        for (i in 1..5) client.putSmall("sach/s$i.epub", ByteArray(10 * i), "application/octet-stream", null, false)
        throttleNextChildren = true
        val listed = client.children("sach")
        assertEquals((1..5).map { "s$it.epub" }, listed.map { it.name })
        assertEquals(30L, listed[2].size)
        client.delete("sach/s1.epub")
        assertNull(client.item("sach/s1.epub"))
    }

    @Test
    fun largeFileIsUploadedInChunksAndResumesAfterLostResponse() = runBlocking {
        val data = Random(7).nextBytes(9 * 1024 * 1024 + 123)
        val file = tmp.newFile("lon.pdf").apply { writeBytes(data) }
        dropFirstChunkResponse = true
        val progress = ArrayList<Long>()
        client.uploadFile("sach/lon.pdf", file) { done, _ -> progress += done }
        assertArrayEquals(data, files["sach/lon.pdf"])
        // Không gửi token tới địa chỉ phiên tải lên.
        assertTrue(authHeadersOnUpload.all { it == null })
        assertEquals(data.size.toLong(), progress.last())

        val target = tmp.newFile("tai-ve.bin")
        client.downloadTo(client.item("sach/lon.pdf")!!.downloadUrl!!, target) { _, _ -> }
        assertArrayEquals(data, target.readBytes())
    }

    @Test
    fun rejectedTokenAfterRefreshMeansSignInAgain() = runBlocking {
        val broken = GraphClient(
            object : TokenProvider {
                override suspend fun accessToken(forceRefresh: Boolean) = "sai"
            },
            "$base/v1.0/me/drive/special/approot",
        )
        try {
            broken.item("thu-vien.json")
            fail("phải yêu cầu đăng nhập lại")
        } catch (e: AuthExpiredException) {
            assertTrue(e.message!!.contains("đăng nhập"))
        }
    }
}
