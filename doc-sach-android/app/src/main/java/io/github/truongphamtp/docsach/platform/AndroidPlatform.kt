package io.github.truongphamtp.docsach.platform

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.window.layout.FoldingFeature
import io.github.truongphamtp.docsach.core.FoldInfo
import io.github.truongphamtp.docsach.core.HardwareKey
import io.github.truongphamtp.docsach.reader.SpeechCallback
import io.github.truongphamtp.docsach.reader.SpeechEngine
import io.github.truongphamtp.docsach.reader.SpeechItem
import io.github.truongphamtp.docsach.ui.ReaderPlatform
import java.util.Locale

/** Chuyển thông tin bản lề của Jetpack WindowManager sang dạng dùng chung. */
fun FoldingFeature.toFoldInfo(): FoldInfo = FoldInfo(
    left = bounds.left,
    top = bounds.top,
    right = bounds.right,
    bottom = bounds.bottom,
    vertical = orientation == FoldingFeature.Orientation.VERTICAL,
    halfOpened = state == FoldingFeature.State.HALF_OPENED,
    separating = isSeparating,
)

fun hardwareKeyFor(keyCode: Int): HardwareKey? = when (keyCode) {
    KeyEvent.KEYCODE_VOLUME_DOWN -> HardwareKey.VOLUME_DOWN
    KeyEvent.KEYCODE_VOLUME_UP -> HardwareKey.VOLUME_UP
    KeyEvent.KEYCODE_PAGE_DOWN -> HardwareKey.PAGE_DOWN
    KeyEvent.KEYCODE_PAGE_UP -> HardwareKey.PAGE_UP
    else -> null
}

class AndroidReaderPlatform(private val activity: Activity) : ReaderPlatform {
    var keyHandler: ((HardwareKey) -> Boolean)? = null
        private set

    private val insetsController: WindowInsetsControllerCompat
        get() = WindowCompat.getInsetsController(activity.window, activity.window.decorView)

    override fun setImmersive(enabled: Boolean) {
        val controller = insetsController
        if (enabled) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun setLightSystemBars(light: Boolean?) {
        val night = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val value = light ?: !night
        val controller = insetsController
        controller.isAppearanceLightStatusBars = value
        controller.isAppearanceLightNavigationBars = value
    }

    override fun setKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun setBrightness(value: Float) {
        val attributes = activity.window.attributes
        attributes.screenBrightness = if (value < 0f) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            value.coerceIn(0.01f, 1f)
        }
        activity.window.attributes = attributes
    }

    override fun setKeyHandler(handler: ((HardwareKey) -> Boolean)?) {
        keyHandler = handler
    }

    override fun openSpeechSettings() {
        val candidates = listOf(Intent("com.android.settings.TTS_SETTINGS"), Intent(Settings.ACTION_SETTINGS))
        for (intent in candidates) {
            try {
                activity.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // thử cách khác
            } catch (_: SecurityException) {
                // thử cách khác
            }
        }
    }
}

/** Đọc to bằng bộ chuyển văn bản thành giọng nói của Android, giọng tiếng Việt. */
class AndroidSpeechEngine(context: Context) : SpeechEngine {
    override var callback: SpeechCallback? = null

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failure: String? = null
    private var pending: (() -> Unit)? = null

    private fun ensureEngine() {
        if (tts != null) return
        tts = TextToSpeech(appContext) { status -> main.post { onInit(status) } }
    }

    private fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            failure = NO_ENGINE
        } else {
            val result = engine.setLanguage(Locale.forLanguageTag("vi-VN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                failure = NO_VIETNAMESE
            }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    val id = utteranceId ?: return
                    main.post { callback?.onStart(id) }
                }

                override fun onDone(utteranceId: String?) {
                    val id = utteranceId ?: return
                    main.post { callback?.onDone(id) }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    main.post { callback?.onError(SPEAK_ERROR) }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    main.post { callback?.onError(SPEAK_ERROR) }
                }
            })
        }
        ready = true
        val action = pending
        pending = null
        action?.invoke()
    }

    override fun speak(items: List<SpeechItem>, rate: Float, flush: Boolean) {
        ensureEngine()
        val action: () -> Unit = {
            val error = failure
            val engine = tts
            if (error != null || engine == null) {
                callback?.onError(error ?: NO_ENGINE)
            } else {
                engine.setSpeechRate(rate)
                items.forEachIndexed { index, item ->
                    val mode = if (flush && index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    engine.speak(item.text, mode, null, item.id)
                }
            }
        }
        if (ready) action() else pending = action
    }

    override fun stop() {
        pending = null
        if (ready) tts?.stop()
    }

    override fun release() {
        pending = null
        callback = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private companion object {
        const val NO_ENGINE =
            "Máy chưa có bộ chuyển văn bản thành giọng nói. Hãy cài “Dịch vụ lời nói của Google” " +
                "(Speech Services by Google) từ CH Play rồi thử lại."
        const val NO_VIETNAMESE =
            "Máy chưa có giọng đọc tiếng Việt. Vào Cài đặt → Cài đặt bổ sung → Ngôn ngữ và nhập liệu → " +
                "Chuyển văn bản thành giọng nói, chọn “Dịch vụ lời nói của Google”, rồi tải giọng Tiếng Việt."
        const val SPEAK_ERROR = "Bộ đọc giọng nói gặp lỗi khi đọc. Hãy thử lại."
    }
}
