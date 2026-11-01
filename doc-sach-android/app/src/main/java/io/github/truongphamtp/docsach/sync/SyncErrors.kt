package io.github.truongphamtp.docsach.sync

import java.net.HttpURLConnection
import java.net.URL

/** Phiên đăng nhập hết hạn hoặc bị thu hồi — cần đăng nhập lại. */
class AuthExpiredException(message: String) : Exception(message)

/** Lỗi trong lúc đăng nhập (người dùng huỷ, cấu hình ứng dụng sai...). */
class SignInException(message: String) : Exception(message)

/** Nơi lấy token để gọi OneDrive. */
interface TokenProvider {
    suspend fun accessToken(forceRefresh: Boolean): String
}

/** Mở kết nối HTTP với thời gian chờ hợp lý (dùng chung cho đăng nhập và OneDrive). */
internal object Http {
    fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 60_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
        }

    fun readBody(connection: HttpURLConnection, code: Int): String =
        runCatching {
            (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                .orEmpty()
        }.getOrDefault("")
}
