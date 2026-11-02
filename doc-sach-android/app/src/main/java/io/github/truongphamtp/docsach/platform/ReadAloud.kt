package io.github.truongphamtp.docsach.platform

import android.content.Context
import android.content.Intent
import io.github.truongphamtp.docsach.MainActivity
import io.github.truongphamtp.docsach.core.BookRecord
import io.github.truongphamtp.docsach.core.ReadingPosition
import io.github.truongphamtp.docsach.data.LibraryStore
import io.github.truongphamtp.docsach.format.BookContent
import io.github.truongphamtp.docsach.format.EpubArchive
import io.github.truongphamtp.docsach.reader.BookImageProvider
import io.github.truongphamtp.docsach.reader.ReaderController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Một cuốn EPUB/TXT đang mở: nội dung, bộ điều khiển trang và giọng đọc.
 * Sống tách khỏi màn hình đọc để khi rời màn hình (về thư viện, ra ngoài ứng dụng) mà đang đọc to
 * thì vẫn đọc tiếp được.
 */
class ReadingSession internal constructor(
    val bookId: String,
    val title: String,
    val coverFile: File?,
    val content: BookContent,
    private val archive: EpubArchive?,
    val controller: ReaderController,
    private val engine: AndroidSpeechEngine,
    private val scope: CoroutineScope,
) {
    /** Số màn hình đang hiển thị phiên này. */
    internal var users = 0

    internal fun close() {
        controller.onSpeechChanged = null
        controller.stopSpeech()
        engine.release()
        scope.cancel()
        runCatching { archive?.close() }
    }
}

/** Thông tin hiển thị khi đang đọc to (thông báo, đảo camera, thanh nhỏ ở thư viện). */
data class ReadAloudUi(
    val bookId: String,
    val title: String,
    val chapter: String,
    val sentence: String?,
    val paused: Boolean,
    val coverFile: File?,
)

/**
 * Quản lý các phiên đọc và việc đọc to chạy nền: khi bắt đầu đọc to thì bật dịch vụ nền
 * ([ReadAloudService]) có thông báo kiểu trình phát nhạc; đọc xong/dừng thì tắt.
 * Mọi hàm chạy trên luồng chính.
 */
class ReadAloudCenter(
    private val context: Context,
    private val library: LibraryStore,
    private val coversDir: File,
) {
    private val sessions = HashMap<String, ReadingSession>()
    private var active: ReadingSession? = null

    private val _state = MutableStateFlow<ReadAloudUi?>(null)
    val state: StateFlow<ReadAloudUi?> = _state.asStateFlow()

    /** Phiên đang mở của cuốn [bookId] (vd. đang đọc to chạy nền), nếu có. */
    fun acquire(bookId: String): ReadingSession? = sessions[bookId]?.also { it.users++ }

    /** Tạo phiên mới cho cuốn sách vừa mở. */
    fun create(record: BookRecord, content: BookContent, archive: EpubArchive?, images: BookImageProvider?): ReadingSession {
        sessions.remove(record.id)?.close()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val latest = library.get(record.id) ?: record
        // Mở lần đầu: nhảy tới điểm "bắt đầu đọc" do file khai báo, giống Kindle.
        val firstOpen = latest.lastOpenedAt == 0L && latest.position == ReadingPosition()
        val start = if (firstOpen) content.startPosition ?: latest.position else latest.position
        val bookId = record.id
        val controller = ReaderController(
            book = content,
            initialPosition = start,
            initialBookmarks = latest.bookmarks,
            scope = scope,
            images = images,
            onPositionChanged = { position, progress -> library.updatePosition(bookId, position, progress) },
            onBookmarksChanged = { library.updateBookmarks(bookId, it) },
            initialNotes = latest.notes,
            onNotesChanged = { library.updateNotes(bookId, it) },
        )
        val engine = AndroidSpeechEngine(context)
        controller.attachSpeech(engine)
        val cover = latest.coverFileName?.let { File(coversDir, it) }?.takeIf { it.isFile }
        val session = ReadingSession(bookId, latest.title, cover, content, archive, controller, engine, scope)
        session.users = 1
        controller.onSpeechChanged = { onSpeechChanged(session) }
        sessions[bookId] = session
        return session
    }

    /** Màn hình đọc đóng lại: nếu không còn đọc to thì giải phóng sách. */
    fun release(session: ReadingSession) {
        session.users--
        if (session.users <= 0 && !session.controller.speaking) close(session)
    }

    private fun close(session: ReadingSession) {
        if (sessions[session.bookId] === session) sessions.remove(session.bookId)
        if (active === session) {
            active = null
            _state.value = null
        }
        session.close()
    }

    private fun onSpeechChanged(session: ReadingSession) {
        val controller = session.controller
        if (controller.speaking) {
            // Chỉ đọc to một cuốn mỗi lúc.
            active?.takeIf { it !== session }?.controller?.stopSpeech()
            active = session
            _state.value = ReadAloudUi(
                bookId = session.bookId,
                title = session.title,
                chapter = controller.speechChapterTitle,
                sentence = controller.speechSentence,
                paused = controller.speechPaused,
                coverFile = session.coverFile,
            )
            ReadAloudService.start(context)
        } else if (active === session) {
            active = null
            _state.value = null
            if (session.users <= 0) close(session)
        }
    }

    fun pause() {
        active?.controller?.pauseSpeech()
    }

    fun resume() {
        active?.controller?.resumeSpeech()
    }

    fun toggle() {
        val controller = active?.controller ?: return
        if (controller.speechPaused) controller.resumeSpeech() else controller.pauseSpeech()
    }

    fun skip(direction: Int) {
        active?.controller?.skipSpeech(direction)
    }

    fun stop() {
        active?.controller?.stopSpeech()
    }

    /** Ý định mở lại ứng dụng ở đúng cuốn đang đọc to (khi chạm vào thông báo hay đảo camera). */
    fun openIntent(bookId: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_BOOK)
            .putExtra(EXTRA_BOOK_ID, bookId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    companion object {
        const val ACTION_OPEN_BOOK = "io.github.truongphamtp.docsach.MO_SACH"
        const val EXTRA_BOOK_ID = "book_id"
    }
}
