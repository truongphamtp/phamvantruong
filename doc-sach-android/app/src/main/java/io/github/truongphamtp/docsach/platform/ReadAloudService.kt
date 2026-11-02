package io.github.truongphamtp.docsach.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.github.truongphamtp.docsach.DocSachApp
import io.github.truongphamtp.docsach.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Dịch vụ chạy nền khi đọc to: giữ cho giọng đọc tiếp tục khi ra ngoài ứng dụng, hiện thông báo kiểu
 * trình phát nhạc (bìa sách, tạm dừng/tiếp, câu trước/sau, dừng) — trên HyperOS 3 thông báo này hiện
 * ở "đảo" quanh cụm camera (Hyper Island). Nút tai nghe/Bluetooth cũng điều khiển được.
 */
class ReadAloudService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var center: ReadAloudCenter
    private lateinit var media: MediaSession
    private var foreground = false
    private var focusRequest: AudioFocusRequest? = null
    private var pausedByFocus = false
    private var pauseTimeout: Job? = null
    private var coverFor: File? = null
    private var cover: Bitmap? = null
    private var islandAllowed: Boolean? = null

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Rút tai nghe / ngắt Bluetooth: tạm dừng để không đọc ra loa ngoài.
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) center.pause()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        center = (application as DocSachApp).readAloud
        createChannel()
        media = MediaSession(this, "DocSach").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = center.resume()
                override fun onPause() = center.pause()
                override fun onStop() = center.stop()
                override fun onSkipToNext() = center.skip(1)
                override fun onSkipToPrevious() = center.skip(-1)
            })
            isActive = true
        }
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch { center.state.collect { render(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> center.toggle()
            ACTION_NEXT -> center.skip(1)
            ACTION_PREVIOUS -> center.skip(-1)
            ACTION_STOP -> center.stop()
        }
        // Dịch vụ chạy nền phải hiện thông báo ngay khi khởi động.
        val ui = center.state.value
        if (ui == null) finish() else if (!foreground) goForeground(build(ui))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        runCatching { unregisterReceiver(noisy) }
        abandonFocus()
        media.isActive = false
        media.release()
        super.onDestroy()
    }

    private fun render(ui: ReadAloudUi?) {
        if (ui == null) {
            finish()
            return
        }
        if (ui.paused) {
            // Tạm dừng lâu thì tắt hẳn để không giữ thông báo mãi.
            if (pauseTimeout == null) {
                pauseTimeout = scope.launch {
                    delay(PAUSE_TIMEOUT_MS)
                    center.stop()
                }
            }
        } else {
            pauseTimeout?.cancel()
            pauseTimeout = null
            pausedByFocus = false
            requestFocus()
        }
        updateSession(ui)
        val notification = build(ui)
        if (!foreground) goForeground(notification) else notificationManager().notify(NOTIFICATION_ID, notification)
    }

    private fun goForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foreground = true
    }

    private fun finish() {
        pauseTimeout?.cancel()
        abandonFocus()
        // Đã xin chạy nền thì phải hiện thông báo trước khi tắt (luật của Android), dù chỉ thoáng qua.
        if (!foreground) goForeground(build(null))
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        running = false
        stopSelf()
    }

    // ------------------------------------------------------------------ phiên phát (MediaSession)

    private fun updateSession(ui: ReadAloudUi) {
        val art = coverBitmap(ui.coverFile)
        media.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, ui.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, ui.chapter.ifBlank { "Đọc Sách" })
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Đọc to · Đọc Sách")
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, ui.sentence.orEmpty())
                .apply { if (art != null) putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art) }
                .build(),
        )
        media.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(if (ui.paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
        media.setSessionActivity(openApp(ui.bookId))
    }

    // ------------------------------------------------------------------ thông báo

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Đọc to", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Điều khiển đọc to khi ứng dụng chạy nền"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun build(ui: ReadAloudUi?): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_read_aloud)
            .setContentTitle(ui?.title ?: "Đọc to")
            .setContentText(ui?.sentence?.take(120) ?: ui?.chapter ?: "")
            .setSubText(if (ui?.paused == true) "Tạm dừng" else "Đang đọc to")
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(ui?.paused != true)
        if (ui == null) return builder.build()
        coverBitmap(ui.coverFile)?.let { builder.setLargeIcon(it) }
        builder.setContentIntent(openApp(ui.bookId))
        builder.setDeleteIntent(action(ACTION_STOP, 4))
        builder.addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_previous), "Câu trước", action(ACTION_PREVIOUS, 1)).build())
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, if (ui.paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause),
                if (ui.paused) "Đọc tiếp" else "Tạm dừng",
                action(ACTION_TOGGLE, 2),
            ).build(),
        )
        builder.addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_next), "Câu sau", action(ACTION_NEXT, 3)).build())
        builder.addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Dừng", action(ACTION_STOP, 4)).build())
        builder.setStyle(Notification.MediaStyle().setMediaSession(media.sessionToken).setShowActionsInCompactView(0, 1, 2))
        xiaomiIsland(ui)?.let { builder.addExtras(it) }
        return builder.build()
    }

    /**
     * Thông tin riêng cho "đảo" của HyperOS 3 (thông báo tiêu điểm của Xiaomi): chỉ thêm khi máy hỗ trợ
     * và Xiaomi cho phép ứng dụng này hiện trên đảo; nếu không, đảo vẫn hiện như một trình phát nhạc.
     */
    private fun xiaomiIsland(ui: ReadAloudUi): Bundle? {
        if (islandAllowed == null) islandAllowed = XiaomiIsland.allowed(this)
        if (islandAllowed != true) return null
        val title = ui.title.take(40)
        val chapter = ui.chapter.take(40)
        val param = JSONObject().put(
            "param_v2",
            JSONObject()
                .put("protocol", 1)
                .put("business", "read_aloud")
                .put("enableFloat", false)
                .put("updatable", true)
                .put("ticker", "Đọc to: $title")
                .put("aodTitle", title)
                .put(
                    "param_island",
                    JSONObject()
                        .put("islandProperty", 1)
                        .put(
                            "bigIslandArea",
                            JSONObject().put(
                                "imageTextInfoLeft",
                                JSONObject()
                                    .put("type", 1)
                                    .put(
                                        "textInfo",
                                        JSONObject()
                                            .put("frontTitle", if (ui.paused) "Tạm dừng" else "Đang đọc")
                                            .put("title", title)
                                            .put("content", chapter)
                                            .put("useHighLight", !ui.paused),
                                    ),
                            ),
                        )
                        .put("smallIslandArea", JSONObject().put("picInfo", JSONObject().put("type", 1).put("pic", ISLAND_PIC))),
                )
                .put("baseInfo", JSONObject().put("title", title).put("content", (ui.sentence ?: chapter).take(60)).put("type", 2)),
        )
        val text = param.toString()
        if (text.toByteArray().size > 3000) return null
        return Bundle().apply {
            putString("miui.focus.param", text)
            putBundle("miui.focus.pics", Bundle().apply { putParcelable(ISLAND_PIC, Icon.createWithResource(this@ReadAloudService, R.drawable.ic_stat_read_aloud)) })
        }
    }

    private fun openApp(bookId: String): PendingIntent =
        PendingIntent.getActivity(this, 10, center.openIntent(bookId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun action(name: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, ReadAloudService::class.java).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun coverBitmap(file: File?): Bitmap? {
        if (file == coverFor) return cover
        coverFor = file
        cover = file?.let {
            runCatching {
                val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(it.absolutePath, options)
            }.getOrNull()
        }
        return cover
    }

    private fun notificationManager(): NotificationManager = getSystemService(NotificationManager::class.java)

    // ------------------------------------------------------------------ âm thanh

    private fun requestFocus() {
        if (focusRequest != null) return
        val manager = getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(SPEECH_ATTRIBUTES)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        // Ứng dụng khác phát nhạc/video: dừng đọc, không tự đọc lại.
                        center.pause()
                        abandonFocus()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        // Có cuộc gọi, chỉ đường...: tạm dừng, xong thì đọc tiếp.
                        if (center.state.value?.paused == false) {
                            pausedByFocus = true
                            center.pause()
                        }
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus) {
                        pausedByFocus = false
                        center.resume()
                    }
                }
            }
            .build()
        if (manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusRequest = request
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request)
        focusRequest = null
    }

    companion object {
        private const val CHANNEL_ID = "doc_to"
        private const val NOTIFICATION_ID = 7
        private const val ISLAND_PIC = "miui.focus.pic_doc_sach"
        private const val PAUSE_TIMEOUT_MS = 15 * 60_000L
        private const val ACTION_TOGGLE = "io.github.truongphamtp.docsach.DOC_TO_TAM_DUNG"
        private const val ACTION_NEXT = "io.github.truongphamtp.docsach.DOC_TO_CAU_SAU"
        private const val ACTION_PREVIOUS = "io.github.truongphamtp.docsach.DOC_TO_CAU_TRUOC"
        private const val ACTION_STOP = "io.github.truongphamtp.docsach.DOC_TO_DUNG"

        /** Giọng đọc phát như nội dung media (âm lượng media, hiện như trình phát). */
        val SPEECH_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        @Volatile
        private var running = false

        /** Bật dịch vụ (gọi từ màn hình đọc khi người dùng bấm Đọc to). */
        fun start(context: Context) {
            if (running) return
            running = true
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ReadAloudService::class.java)) }
                .onFailure { running = false }
        }
    }
}

/** Nhận biết "đảo" (thông báo tiêu điểm) của Xiaomi HyperOS 3 và quyền hiện trên đảo. */
internal object XiaomiIsland {
    fun allowed(context: Context): Boolean {
        val protocol = runCatching { Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0) }.getOrDefault(0)
        if (protocol < 3) return false
        return runCatching {
            val extras = Bundle().apply { putString("package", context.packageName) }
            context.contentResolver.call(Uri.parse("content://miui.statusbar.notification.public"), "canShowFocus", null, extras)
                ?.getBoolean("canShowFocus", false) ?: false
        }.getOrDefault(false)
    }
}
