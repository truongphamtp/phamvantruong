package io.github.truongphamtp.docsach

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
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
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Collections
import kotlin.random.Random

/** Kiểm tra cách gọi Microsoft Graph với một máy chủ giả lập chạy trên máy. */
class GraphClientTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val files = Collections.synchronizedMap(LinkedHashMap<String, ByteArray>())
    private val etags = HashMap<String, Int>()
    private val sessions = HashMap<String, ByteArrayOutputStream>()
    private val sessionTargets = HashMap<String, String>()
    private val authHeadersOnUpload = ArrayList<String?>()
    private var throttleNextChildren = false
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
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> runCatching { handle(exchange) }.onFailure { exchange.close() } }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
        client = GraphClient(tokens, "$base/v1.0/me/drive/special/approot")
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun handle(exchange: HttpExchange) {
        val path = URLDecoder.decode(exchange.requestURI.rawPath, "UTF-8")
        val query = exchange.requestURI.rawQuery.orEmpty()
        val body = exchange.requestBody.readBytes()
        when {
            path.startsWith("/download/") -> {
                val data = files[path.removePrefix("/download/")] ?: return send(exchange, 404, "{}")
                send(exchange, 200, data)
            }
            path.startsWith("/upload/") -> upload(exchange, path.removePrefix("/upload/"), body)
            exchange.requestHeaders.getFirst("Authorization") != "Bearer tot" -> send(exchange, 401, """{"error":{"code":"InvalidAuthenticationToken"}}""")
            else -> graph(exchange, path.substringAfter("/v1.0/me/drive/special/approot:/"), query, body)
        }
    }

    private fun graph(exchange: HttpExchange, rest: String, query: String, body: ByteArray) {
        val method = exchange.requestMethod
        when {
            rest.endsWith(":/children") -> {
                if (throttleNextChildren) {
                    throttleNextChildren = false
                    exchange.responseHeaders.add("Retry-After", "1")
                    return send(exchange, 429, "{}")
                }
                val folder = rest.removeSuffix(":/children")
                val names = files.keys.filter { it.startsWith("$folder/") }
                if (names.isEmpty()) return send(exchange, 404, "{}")
                // Mỗi trang 2 tệp, có đường dẫn trang sau như Graph.
                val skip = Regex("skip=(\\d+)").find(query)?.groupValues?.get(1)?.toInt() ?: 0
                val page = names.drop(skip).take(2)
                val json = JSONObject().put("value", JSONArray(page.map { itemJson(it) }))
                if (skip + 2 < names.size) json.put("@odata.nextLink", "$base/v1.0/me/drive/special/approot:/$folder:/children?skip=${skip + 2}")
                send(exchange, 200, json.toString())
            }
            rest.endsWith(":/content") && method == "PUT" -> {
                val name = rest.removeSuffix(":/content")
                val ifMatch = exchange.requestHeaders.getFirst("If-Match")
                if ("conflictBehavior=fail" in query && name in files) return send(exchange, 409, """{"error":{"code":"nameAlreadyExists"}}""")
                if (ifMatch != null && ifMatch != etag(name)) return send(exchange, 412, """{"error":{"code":"preconditionFailed","message":"đã đổi"}}""")
                files[name] = body
                etags[name] = (etags[name] ?: 0) + 1
                send(exchange, 201, itemJson(name).toString())
            }
            rest.endsWith(":/createUploadSession") && method == "POST" -> {
                val id = "phien${sessions.size + 1}"
                sessions[id] = ByteArrayOutputStream()
                sessionTargets[id] = rest.removeSuffix(":/createUploadSession")
                send(exchange, 200, JSONObject().put("uploadUrl", "$base/upload/$id").toString())
            }
            method == "DELETE" -> {
                files.remove(rest)
                send(exchange, 204, null as ByteArray?)
            }
            method == "GET" -> {
                if (rest !in files) return send(exchange, 404, """{"error":{"code":"itemNotFound"}}""")
                send(exchange, 200, itemJson(rest).toString())
            }
            else -> send(exchange, 400, "{}")
        }
    }

    private fun upload(exchange: HttpExchange, id: String, body: ByteArray) {
        val buffer = sessions[id] ?: return send(exchange, 404, "{}")
        if (exchange.requestMethod == "GET") {
            return send(exchange, 200, JSONObject().put("nextExpectedRanges", JSONArray(listOf("${buffer.size()}-"))).toString())
        }
        authHeadersOnUpload += exchange.requestHeaders.getFirst("Authorization")
        val range = exchange.requestHeaders.getFirst("Content-Range")!! // bytes a-b/total
        val (start, end) = range.removePrefix("bytes ").substringBefore('/').split('-').map { it.toLong() }
        val total = range.substringAfter('/').toLong()
        if (start.toInt() != buffer.size()) return send(exchange, 416, "{}")
        assertEquals(end - start + 1, body.size.toLong())
        buffer.write(body)
        if (dropFirstChunkResponse) {
            // OneDrive đã nhận nhưng phản hồi bị mất giữa đường.
            dropFirstChunkResponse = false
            exchange.close()
            return
        }
        if (buffer.size().toLong() == total) {
            val name = sessionTargets[id]!!
            files[name] = buffer.toByteArray()
            send(exchange, 201, itemJson(name).toString())
        } else {
            send(exchange, 202, JSONObject().put("nextExpectedRanges", JSONArray(listOf("${buffer.size()}-"))).toString())
        }
    }

    private fun etag(name: String) = "\"{${name}},${etags[name] ?: 0}\""

    private fun itemJson(name: String) = JSONObject()
        .put("name", name.substringAfterLast('/'))
        .put("size", files[name]!!.size)
        .put("eTag", etag(name))
        .put("@microsoft.graph.downloadUrl", "$base/download/$name")

    private fun send(exchange: HttpExchange, code: Int, body: String?) = send(exchange, code, body?.toByteArray())

    private fun send(exchange: HttpExchange, code: Int, body: ByteArray?) {
        if (body == null || code == 204) {
            exchange.sendResponseHeaders(code, -1)
        } else {
            exchange.sendResponseHeaders(code, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        exchange.close()
    }

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
