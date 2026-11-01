package io.github.truongphamtp.docsach.sync

import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Đăng nhập tài khoản Microsoft (OneDrive) theo chuẩn OAuth 2.0 "authorization code + PKCE"
 * cho ứng dụng trên máy: không cần mật khẩu bí mật, ứng dụng không bao giờ thấy mật khẩu của bạn.
 *
 * Quyền xin: chỉ thư mục riêng của ứng dụng trong OneDrive (Files.ReadWrite.AppFolder),
 * đọc tên tài khoản (User.Read) và giữ đăng nhập (offline_access).
 */
class OneDriveAuth(
    private val vault: TokenVault,
    private val prefs: SharedPreferences,
    private val builtInClientId: String,
) : TokenProvider {
    /** Một lần đăng nhập đang chờ: địa chỉ trang đăng nhập và các mã để kiểm tra kết quả trả về. */
    data class Request(val url: String, val state: String, val verifier: String)

    private val refreshLock = Mutex()

    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var accessExpiresAt = 0L

    val clientId: String
        get() = prefs.getString(KEY_CLIENT_ID, null)?.trim()?.takeIf { it.isNotEmpty() } ?: builtInClientId.trim()

    /** Mã ứng dụng do người dùng nhập (rỗng = dùng mã có sẵn khi đóng gói, nếu có). */
    fun setClientId(value: String) {
        prefs.edit().putString(KEY_CLIENT_ID, value.trim()).apply()
    }

    fun hasRefreshToken(): Boolean = vault.get(REFRESH_TOKEN) != null

    val accountName: String get() = prefs.getString(KEY_NAME, "").orEmpty()
    val accountEmail: String get() = prefs.getString(KEY_EMAIL, "").orEmpty()

    fun newRequest(): Request {
        val verifier = randomUrlSafe(48)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val state = randomUrlSafe(18)
        val url = Uri.parse("$AUTHORITY/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_mode", "query")
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("prompt", "select_account")
            .build()
            .toString()
        return Request(url, state, verifier)
    }

    /** Trang đăng nhập đã chuyển về địa chỉ trả kết quả chưa. */
    fun isRedirect(url: String): Boolean = url.startsWith(REDIRECT_URI, ignoreCase = true)

    /** Đổi mã nhận được sau khi đăng nhập lấy token, rồi đọc tên tài khoản. */
    suspend fun complete(redirectUrl: String, request: Request) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(redirectUrl)
        uri.getQueryParameter("error")?.let { error ->
            throw SignInException(explain(error, uri.getQueryParameter("error_description").orEmpty()))
        }
        if (uri.getQueryParameter("state") != request.state) {
            throw SignInException("Phiên đăng nhập không khớp. Hãy thử đăng nhập lại.")
        }
        val code = uri.getQueryParameter("code") ?: throw SignInException("Microsoft không trả về mã đăng nhập. Hãy thử lại.")
        val tokens = try {
            tokenRequest(
                mapOf(
                    "client_id" to clientId,
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to REDIRECT_URI,
                    "code_verifier" to request.verifier,
                    "scope" to SCOPES,
                ),
            )
        } catch (e: AuthExpiredException) {
            throw SignInException(e.message.orEmpty())
        }
        store(tokens)
        loadProfile()
    }

    /** Token để gọi OneDrive; tự làm mới khi sắp hết hạn. */
    override suspend fun accessToken(forceRefresh: Boolean): String = refreshLock.withLock {
        val cached = accessToken
        if (!forceRefresh && cached != null && System.currentTimeMillis() < accessExpiresAt - 120_000) return@withLock cached
        val refresh = vault.get(REFRESH_TOKEN) ?: throw AuthExpiredException("Hãy đăng nhập lại OneDrive (phiên đăng nhập trên máy này không còn).")
        val tokens = withContext(Dispatchers.IO) {
            tokenRequest(
                mapOf(
                    "client_id" to clientId,
                    "grant_type" to "refresh_token",
                    "refresh_token" to refresh,
                    "scope" to SCOPES,
                ),
            )
        }
        store(tokens)
        tokens.getString("access_token")
    }

    fun signOut() {
        accessToken = null
        accessExpiresAt = 0L
        vault.clear()
        prefs.edit().remove(KEY_NAME).remove(KEY_EMAIL).apply()
    }

    private fun store(tokens: JSONObject) {
        accessToken = tokens.getString("access_token")
        accessExpiresAt = System.currentTimeMillis() + tokens.optLong("expires_in", 3600L) * 1000L
        tokens.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { vault.put(REFRESH_TOKEN, it) }
    }

    private suspend fun loadProfile() {
        val token = accessToken(forceRefresh = false)
        val connection = Http.open("https://graph.microsoft.com/v1.0/me?\$select=displayName,mail,userPrincipalName", "GET")
        try {
            connection.setRequestProperty("Authorization", "Bearer $token")
            val code = connection.responseCode
            if (code !in 200..299) return
            val me = JSONObject(Http.readBody(connection, code))
            val email = me.optString("mail").takeIf { it.isNotBlank() && it != "null" } ?: me.optString("userPrincipalName")
            prefs.edit()
                .putString(KEY_NAME, me.optString("displayName").takeIf { it != "null" }.orEmpty())
                .putString(KEY_EMAIL, email.takeIf { it != "null" }.orEmpty())
                .apply()
        } catch (e: Exception) {
            // Không đọc được tên tài khoản thì vẫn đồng bộ bình thường.
        } finally {
            connection.disconnect()
        }
    }

    private fun tokenRequest(form: Map<String, String>): JSONObject {
        val body = form.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }.toByteArray(Charsets.UTF_8)
        val connection = Http.open("$AUTHORITY/token", "POST")
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val text = Http.readBody(connection, code)
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (code in 200..299 && json.has("access_token")) return json
            val error = json.optString("error")
            val description = json.optString("error_description")
            if (code in 400..499 && error.isNotEmpty()) {
                if (error == "invalid_grant" || error == "interaction_required" || error == "consent_required") {
                    vault.put(REFRESH_TOKEN, null)
                    accessToken = null
                    throw AuthExpiredException("Phiên đăng nhập OneDrive đã hết hạn. Hãy đăng nhập lại.")
                }
                throw AuthExpiredException(explain(error, description))
            }
            throw IOException("Máy chủ đăng nhập Microsoft trả lỗi $code. Sẽ thử lại sau.")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** Địa chỉ trả kết quả dành cho ứng dụng trên máy — đánh dấu sẵn có trong trang đăng ký của Microsoft. */
        const val REDIRECT_URI = "https://login.microsoftonline.com/common/oauth2/nativeclient"
        const val SCOPES = "offline_access User.Read Files.ReadWrite.AppFolder"
        private const val AUTHORITY = "https://login.microsoftonline.com/common/oauth2/v2.0"

        private const val REFRESH_TOKEN = "refresh_token"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_NAME = "account_name"
        private const val KEY_EMAIL = "account_email"

        private val random = SecureRandom()

        private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

        private fun randomUrlSafe(bytes: Int): String = base64Url(ByteArray(bytes).also { random.nextBytes(it) })

        private fun base64Url(data: ByteArray): String =
            Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        /** Diễn giải các lỗi thường gặp khi cấu hình ứng dụng trên trang của Microsoft. */
        fun explain(error: String, description: String): String {
            val d = description.lowercase()
            return when {
                error == "access_denied" -> "Bạn đã huỷ đăng nhập hoặc chưa đồng ý cấp quyền."
                "aadsts700016" in d || "was not found in the directory" in d ->
                    "Không tìm thấy ứng dụng có mã này. Hãy kiểm tra lại mã ứng dụng (Application/Client ID)."
                "aadsts50011" in d || "redirect" in d && "match" in d ->
                    "Địa chỉ chuyển hướng chưa được thêm. Trong trang đăng ký ứng dụng, mục Authentication → " +
                        "Mobile and desktop applications, hãy đánh dấu $REDIRECT_URI."
                "aadsts7000218" in d || "client_assertion" in d || "client_secret" in d ->
                    "Ứng dụng đang được đăng ký dạng Web. Hãy thêm nền tảng \"Mobile and desktop applications\" " +
                        "và bật \"Allow public client flows\"."
                "not enabled for consumers" in d || "aadsts50020" in d || "aadsts500200" in d ->
                    "Ứng dụng chưa cho phép tài khoản Microsoft cá nhân. Ở mục Authentication → Supported account types, " +
                        "chọn \"Accounts in any organizational directory and personal Microsoft accounts\"."
                error == "unauthorized_client" ->
                    "Ứng dụng chưa được phép đăng nhập kiểu này. Kiểm tra mục Authentication trong trang đăng ký ứng dụng."
                else -> "Đăng nhập không thành công ($error). ${description.substringBefore("\r").substringBefore("\n").take(180)}"
            }
        }
    }
}
