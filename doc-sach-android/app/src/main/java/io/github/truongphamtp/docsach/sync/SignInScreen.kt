package io.github.truongphamtp.docsach.sync

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.truongphamtp.docsach.ui.AccentTile
import io.github.truongphamtp.docsach.ui.GlassIconButton
import io.github.truongphamtp.docsach.ui.LiquidBackground
import io.github.truongphamtp.docsach.ui.LocalHyper
import io.github.truongphamtp.docsach.ui.ThinProgress
import io.github.truongphamtp.docsach.ui.glass
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Trang đăng nhập Microsoft mở ngay trong ứng dụng. Khi Microsoft chuyển về địa chỉ trả kết quả,
 * ứng dụng lấy mã đăng nhập (không bao giờ nhìn thấy mật khẩu) và đóng trang.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MicrosoftSignInScreen(
    url: String,
    isRedirect: (String) -> Boolean,
    onRedirect: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val p = LocalHyper.current
    var progress by remember { mutableIntStateOf(0) }
    // Giữ tham chiếu tới WebView để nút Quay lại đi lùi trong trang đăng nhập (không phải trạng thái giao diện).
    val webView = remember { arrayOfNulls<WebView>(1) }
    val delivered = remember(url) { AtomicBoolean(false) }
    val currentRedirect by rememberUpdatedState(onRedirect)
    val currentIsRedirect by rememberUpdatedState(isRedirect)

    fun deliver(target: String) {
        if (delivered.compareAndSet(false, true)) currentRedirect(target)
    }

    BackHandler {
        val view = webView[0]
        if (view != null && view.canGoBack()) view.goBack() else onCancel()
    }

    Box(
        Modifier
            .fillMaxSize()
            // Chặn chạm xuyên xuống thư viện phía dưới.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        LiquidBackground(Modifier.fillMaxSize())
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(28.dp), strong = true, elevation = 10.dp)
                    .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccentTile(Icons.Rounded.Cloud, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Đăng nhập OneDrive", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Trang chính thức của Microsoft · ứng dụng chỉ dùng thư mục riêng của nó",
                        color = p.textSecondary,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                GlassIconButton(Icons.Rounded.Close, null, onCancel, size = 40.dp)
            }
            // Thanh tiến độ tải trang: hiện mờ dần khi đang tải, ẩn khi xong.
            val loadingAlpha by animateFloatAsState(if (progress in 1..99) 1f else 0f, tween(220), label = "tiến độ")
            ThinProgress(
                progress / 100f,
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 6.dp)
                    .graphicsLayer { alpha = loadingAlpha },
            )
            Box(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White),
            ) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.setSupportMultipleWindows(false)
                            CookieManager.getInstance().setAcceptCookie(true)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val target = request.url.toString()
                                    if (currentIsRedirect(target)) {
                                        deliver(target)
                                        return true
                                    }
                                    return false
                                }

                                override fun onPageStarted(view: WebView, address: String?, favicon: Bitmap?) {
                                    if (address != null && currentIsRedirect(address)) {
                                        view.stopLoading()
                                        deliver(address)
                                    }
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    progress = newProgress
                                }
                            }
                            loadUrl(url)
                            webView[0] = this
                        }
                    },
                    onRelease = { view ->
                        webView[0] = null
                        view.stopLoading()
                        view.destroy()
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
