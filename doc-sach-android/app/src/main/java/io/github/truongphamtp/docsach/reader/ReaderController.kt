package io.github.truongphamtp.docsach.reader

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.truongphamtp.docsach.core.Bookmark
import io.github.truongphamtp.docsach.core.HighlightColor
import io.github.truongphamtp.docsach.core.Note
import io.github.truongphamtp.docsach.core.ReadingPosition
import io.github.truongphamtp.docsach.format.BookContent
import io.github.truongphamtp.docsach.format.BookSearch
import io.github.truongphamtp.docsach.format.ChapterPages
import io.github.truongphamtp.docsach.format.Paginator
import io.github.truongphamtp.docsach.format.SearchHit
import io.github.truongphamtp.docsach.format.SpeechText
import io.github.truongphamtp.docsach.format.TextBlock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs

/** Thông số dàn trang hiện tại; đổi bất kỳ giá trị nào sẽ dàn trang lại. */
data class LayoutRequest(
    val density: Density,
    val fontFamilyResolver: FontFamily.Resolver,
    val typography: ReaderTypography,
    val pageWidth: Int,
    val pageHeight: Int,
    val pagesPerView: Int,
)

/** Đoạn chữ được tô sáng (kết quả tìm kiếm hoặc câu đang đọc to). */
data class TextHighlight(val chapter: Int, val start: Int, val end: Int)

data class SpeechItem(val id: String, val text: String)

interface SpeechCallback {
    fun onStart(id: String)
    fun onDone(id: String)
    fun onError(message: String)
}

/** Bộ đọc thành tiếng (bản Android dùng TextToSpeech). */
interface SpeechEngine {
    var callback: SpeechCallback?
    fun speak(items: List<SpeechItem>, rate: Float, flush: Boolean)
    fun stop()
    fun release()
}

/**
 * Trạng thái và thao tác của màn hình đọc EPUB/TXT: dàn trang theo chương, lật trang,
 * đánh dấu trang, tìm kiếm và đọc to.
 */
@Stable
class ReaderController(
    val book: BookContent,
    initialPosition: ReadingPosition,
    initialBookmarks: List<Bookmark>,
    private val scope: CoroutineScope,
    val images: BookImageProvider? = null,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val onPositionChanged: (ReadingPosition, Float) -> Unit = { _, _ -> },
    private val onBookmarksChanged: (List<Bookmark>) -> Unit = {},
    initialNotes: List<Note> = emptyList(),
    private val onNotesChanged: (List<Note>) -> Unit = {},
) {
    private val chapterCount = book.chapters.size

    var chapter by mutableIntStateOf(initialPosition.chapter.coerceIn(0, maxOf(0, chapterCount - 1)))
        private set

    /** Vị trí ký tự "neo" của trang đang xem; giữ nguyên khi đổi cỡ chữ để không lạc chỗ đọc. */
    var anchor by mutableIntStateOf(initialPosition.offset.coerceAtLeast(0))
        private set

    /** Tăng mỗi lần người đọc chuyển trang (dùng cho hiệu ứng lật trang). */
    var navSerial by mutableIntStateOf(0)
        private set

    /** 1 = trang sau, -1 = trang trước, 0 = nhảy tới vị trí khác. */
    var navDirection by mutableIntStateOf(0)
        private set

    var pagesPerView by mutableIntStateOf(1)
        private set

    private val cache = mutableStateMapOf<Int, ChapterPages>()
    private var stale by mutableStateOf<ChapterPages?>(null)
    private var request: LayoutRequest? = null
    private var generation = 0
    private val jobs = HashMap<Int, Job>()

    var bookmarks by mutableStateOf(sortBookmarks(initialBookmarks))
        private set

    var searchHighlight by mutableStateOf<TextHighlight?>(null)
        private set

    /** Các đoạn đánh dấu/ghi chú của cuốn sách. */
    var notes by mutableStateOf(sortNotes(initialNotes))
        private set

    /** Đoạn đang được chọn (nhấn giữ rồi kéo). */
    var selection by mutableStateOf<TextHighlight?>(null)
        private set

    /** Ghi chú đang được chạm vào để sửa/xoá. */
    var activeNote by mutableStateOf<Note?>(null)
        private set

    /** Đang kéo để chọn chữ (ẩn thanh thao tác cho tới khi nhấc tay). */
    var selecting by mutableStateOf(false)
        private set

    private var selectionAnchor: IntRange? = null
    var speechHighlight by mutableStateOf<TextHighlight?>(null)
        private set

    var searchQuery by mutableStateOf("")
        private set
    var searchResults by mutableStateOf<List<SearchHit>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    private var searchJob: Job? = null

    var speaking by mutableStateOf(false)
        private set
    var speechPaused by mutableStateOf(false)
        private set
    var speechError by mutableStateOf<String?>(null)
    var speechRate: Float = 1f

    private var engine: SpeechEngine? = null
    private val speechSpans = HashMap<String, TextHighlight>()

    /** Các câu đã xếp hàng để đọc (theo thứ tự), dùng cho "câu trước/câu sau". */
    private val speechQueue = ArrayList<Pair<String, TextHighlight>>()
    private val speechTexts = HashMap<String, String>()
    private var speechCurrentId: String? = null

    /** Câu đang được đọc (để hiện trên thông báo, màn hình khoá). */
    var speechSentence by mutableStateOf<String?>(null)
        private set

    /** Gọi mỗi khi trạng thái đọc to đổi: bắt đầu, tạm dừng, tiếp tục, dừng, sang câu mới. */
    var onSpeechChanged: (() -> Unit)? = null

    /** Tên chương đang đọc to (hoặc đang xem). */
    val speechChapterTitle: String
        get() = book.chapters.getOrNull(speechHighlight?.chapter ?: chapter)?.title.orEmpty()
    private var speechLastId: String? = null
    private var speechChapter = 0
    private var speechSerial = 0

    // ---------------------------------------------------------------- dàn trang

    fun updateLayout(newRequest: LayoutRequest) {
        if (newRequest == request) return
        request = newRequest
        generation++
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        cache[chapter]?.let { stale = it }
        cache.clear()
        pagesPerView = newRequest.pagesPerView
        ensure(chapter)
        prefetch()
    }

    private fun ensure(index: Int) {
        val req = request ?: return
        if (index !in 0 until chapterCount || cache.containsKey(index) || jobs.containsKey(index)) return
        val gen = generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val pages = withContext(worker) { paginate(req, index) }
            if (gen == generation) {
                cache[index] = pages
                jobs.remove(index)
                if (index == chapter) {
                    stale = null
                    normalizeAnchor()
                }
                trimCache()
            }
        }
        jobs[index] = job
        job.start()
    }

    private fun paginate(req: LayoutRequest, index: Int): ChapterPages {
        val measurer = TextMeasurer(req.fontFamilyResolver, req.density, LayoutDirection.Ltr, 0)
        val style = ComposePageStyle(measurer, req.density, req.typography, images)
        return Paginator.paginate(index, book.chapters[index], style, req.pageWidth, req.pageHeight)
    }

    private fun prefetch() {
        ensure(chapter + 1)
        ensure(chapter - 1)
    }

    private fun trimCache() {
        val keep = (chapter - 2)..(chapter + 2)
        cache.keys.filter { it !in keep }.forEach { cache.remove(it) }
    }

    fun pagesFor(index: Int): ChapterPages? = cache[index] ?: stale?.takeIf { it.chapter == index }

    val currentPages: ChapterPages? get() = pagesFor(chapter)

    /** Đã dàn trang xong chương hiện tại với thông số mới nhất. */
    val isReady: Boolean get() = cache.containsKey(chapter)

    val pageIndex: Int get() = currentPages?.pageForOffset(anchor) ?: 0

    val viewIndex: Int get() = pageIndex / pagesPerView

    val viewCount: Int
        get() = currentPages?.let { (it.pages.size + pagesPerView - 1) / pagesPerView } ?: 1

    /** Khoảng ký tự [đầu, cuối) đang hiển thị trên màn hình. */
    fun visibleRange(): Pair<Int, Int>? {
        val pages = cache[chapter]?.pages ?: return null
        val first = (viewIndex * pagesPerView).coerceAtMost(pages.size - 1)
        val last = minOf(first + pagesPerView - 1, pages.size - 1)
        return pages[first].startOffset to pages[last].endOffset
    }

    val progress: Float
        get() = book.progress(ReadingPosition(chapter, anchor.coerceAtMost(book.chapters.getOrNull(chapter)?.length ?: 0)))

    val globalOffset: Int get() = book.globalOffset(ReadingPosition(chapter, anchor))

    // ---------------------------------------------------------------- lật trang

    fun nextPage(): Boolean {
        val pages = cache[chapter]?.pages ?: return false
        val nextFirst = (viewIndex + 1) * pagesPerView
        when {
            nextFirst < pages.size -> moveTo(chapter, pages[nextFirst].startOffset, 1)
            chapter + 1 < chapterCount -> moveTo(chapter + 1, 0, 1)
            else -> return false
        }
        return true
    }

    fun previousPage(): Boolean {
        val pages = cache[chapter]?.pages ?: return false
        val view = viewIndex
        when {
            view > 0 -> moveTo(chapter, pages[(view - 1) * pagesPerView].startOffset, -1)
            chapter > 0 -> moveTo(chapter - 1, END, -1)
            else -> return false
        }
        return true
    }

    /** Trang (hoặc cặp trang) liền trước/liền sau, để vẽ sẵn khi kéo lật trang. */
    fun neighbor(direction: Int): PageTarget? {
        val pages = cache[chapter]?.pages ?: return null
        val view = viewIndex
        return if (direction > 0) {
            when {
                (view + 1) * pagesPerView < pages.size -> PageTarget(chapter, view + 1, 0, 0)
                chapter + 1 < chapterCount -> PageTarget(chapter + 1, 0, 0, 0)
                else -> null
            }
        } else {
            when {
                view > 0 -> PageTarget(chapter, view - 1, 0, 0)
                chapter > 0 -> {
                    val previous = cache[chapter - 1]
                    PageTarget(chapter - 1, if (previous != null) (previous.pages.size - 1) / pagesPerView else 0, 0, 0)
                }
                else -> null
            }
        }
    }

    fun goTo(position: ReadingPosition) {
        if (chapterCount == 0) return
        val target = ReadingPosition(position.chapter.coerceIn(0, chapterCount - 1), position.offset.coerceAtLeast(0))
        moveTo(target.chapter, target.offset, 0)
    }

    fun goToFraction(fraction: Float) {
        goTo(book.positionAt((fraction.coerceIn(0f, 1f) * book.totalLength).toInt()))
    }

    private fun moveTo(newChapter: Int, offset: Int, direction: Int) {
        clearSelection()
        chapter = newChapter
        anchor = offset
        navDirection = direction
        navSerial++
        ensure(newChapter)
        prefetch()
        normalizeAnchor()
        report()
    }

    private fun normalizeAnchor() {
        if (anchor < END) return
        val pages = cache[chapter]?.pages ?: return
        val lastView = (pages.size - 1) / pagesPerView
        anchor = pages[lastView * pagesPerView].startOffset
        report()
    }

    private fun report() {
        if (anchor >= END) return
        onPositionChanged(ReadingPosition(chapter, anchor), progress)
    }

    // ---------------------------------------------------------------- dấu trang

    fun bookmarksInView(): List<Bookmark> {
        val (start, end) = visibleRange() ?: return emptyList()
        return bookmarks.filter { it.position.chapter == chapter && it.position.offset >= start && it.position.offset < maxOf(end, start + 1) }
    }

    val isBookmarked: Boolean get() = bookmarksInView().isNotEmpty()

    fun toggleBookmark() {
        val inView = bookmarksInView()
        bookmarks = if (inView.isNotEmpty()) {
            val ids = inView.map { it.id }.toSet()
            bookmarks.filter { it.id !in ids }
        } else {
            val start = visibleRange()?.first ?: anchor.coerceAtMost(book.chapters[chapter].length)
            val mark = Bookmark(
                id = UUID.randomUUID().toString(),
                position = ReadingPosition(chapter, start),
                label = book.chapterTitle(chapter),
                snippet = book.chapters[chapter].textFrom(start, 110),
                createdAt = System.currentTimeMillis(),
            )
            sortBookmarks(bookmarks + mark)
        }
        onBookmarksChanged(bookmarks)
    }

    fun removeBookmark(bookmark: Bookmark) {
        bookmarks = bookmarks.filter { it.id != bookmark.id }
        onBookmarksChanged(bookmarks)
    }

    private fun sortBookmarks(list: List<Bookmark>) = list.sortedBy { book.globalOffset(it.position) }

    // ---------------------------------------------------------------- đánh dấu & ghi chú

    /** Bắt đầu chọn chữ ở từ [word] (vị trí ký tự trong chương hiện tại). */
    fun startSelection(word: IntRange) {
        activeNote = null
        selectionAnchor = word
        selecting = true
        selection = TextHighlight(chapter, word.first, word.last + 1)
    }

    /** Kéo rộng vùng chọn tới từ [word]. */
    fun extendSelection(word: IntRange) {
        val anchor = selectionAnchor ?: return
        selection = TextHighlight(chapter, minOf(anchor.first, word.first), maxOf(anchor.last, word.last) + 1)
    }

    fun finishSelection() {
        selecting = false
    }

    fun clearSelection() {
        selection = null
        selectionAnchor = null
        activeNote = null
        selecting = false
    }

    /** Chữ của một đoạn trong chương. */
    fun textOf(range: TextHighlight): String {
        val chapterContent = book.chapters.getOrNull(range.chapter) ?: return ""
        val sb = StringBuilder()
        for (block in chapterContent.blocks) {
            if (block !is TextBlock || block.end <= range.start || block.start >= range.end) continue
            val from = (range.start - block.start).coerceIn(0, block.text.length)
            val to = (range.end - block.start).coerceIn(from, block.text.length)
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(block.text, from, to)
        }
        return sb.toString().trim()
    }

    val selectedText: String get() = selection?.let(::textOf).orEmpty()

    /** Lưu vùng đang chọn thành đoạn đánh dấu (kèm ghi chú nếu có). */
    fun saveSelection(color: HighlightColor, text: String = ""): Note? {
        val range = selection ?: return null
        val note = Note(
            id = UUID.randomUUID().toString(),
            chapter = range.chapter,
            start = range.start,
            end = range.end,
            quote = textOf(range).take(2000),
            text = text.trim(),
            color = color,
            label = book.chapterTitle(range.chapter),
            createdAt = System.currentTimeMillis(),
        )
        notes = sortNotes(notes + note)
        onNotesChanged(notes)
        clearSelection()
        return note
    }

    /** Chạm vào một đoạn đã đánh dấu: chọn nó để sửa màu, ghi chú hoặc xoá. */
    fun selectNote(note: Note) {
        selectionAnchor = null
        selecting = false
        activeNote = note
        selection = TextHighlight(note.chapter, note.start, note.end)
    }

    fun updateNote(note: Note) {
        notes = sortNotes(notes.map { if (it.id == note.id) note else it })
        onNotesChanged(notes)
        if (activeNote?.id == note.id) activeNote = note
    }

    fun removeNote(note: Note) {
        notes = notes.filter { it.id != note.id }
        onNotesChanged(notes)
        if (activeNote?.id == note.id) clearSelection()
    }

    /** Ghi chú chứa vị trí [offset] của chương đang đọc. */
    fun noteAt(offset: Int): Note? =
        notes.lastOrNull { it.chapter == chapter && offset >= it.start && offset < it.end }

    /** Mở tới đúng đoạn có ghi chú và tô sáng đoạn đó. */
    fun goToNote(note: Note) {
        goTo(ReadingPosition(note.chapter, note.start))
        searchHighlight = TextHighlight(note.chapter, note.start, note.end)
    }

    private fun sortNotes(list: List<Note>) = list.sortedWith(compareBy<Note>({ it.chapter }, { it.start }))

    // ---------------------------------------------------------------- tìm kiếm

    fun search(query: String) {
        searchQuery = query
        searchJob?.cancel()
        if (query.isBlank()) {
            searchResults = emptyList()
            searching = false
            return
        }
        searching = true
        searchJob = scope.launch {
            delay(250)
            val results = withContext(worker) {
                BookSearch.search(book, query, limit = 300) { !isActive }
            }
            searchResults = results
            searching = false
        }
    }

    fun openSearchHit(hit: SearchHit) {
        goTo(ReadingPosition(hit.chapter, hit.start))
        searchHighlight = TextHighlight(hit.chapter, hit.start, hit.end)
    }

    fun clearSearchHighlight() {
        searchHighlight = null
    }

    // ---------------------------------------------------------------- đọc to

    fun attachSpeech(speechEngine: SpeechEngine?) {
        engine = speechEngine
        speechEngine?.callback = object : SpeechCallback {
            override fun onStart(id: String) {
                val span = speechSpans[id] ?: return
                speechHighlight = span
                speechCurrentId = id
                speechSentence = speechTexts[id]
                followSpeech(span)
                onSpeechChanged?.invoke()
            }

            override fun onDone(id: String) {
                speechSpans.remove(id)
                if (id == speechLastId && speaking && !speechPaused) {
                    val next = speechChapter + 1
                    if (next < chapterCount) queueSpeech(next, 0, flush = false) else stopSpeech()
                }
            }

            override fun onError(message: String) {
                speechError = message
                stopSpeech()
            }
        }
    }

    fun startSpeech() {
        if (engine == null) {
            speechError = "Thiết bị này chưa hỗ trợ đọc to."
            return
        }
        speaking = true
        speechPaused = false
        val from = visibleRange()?.first ?: anchor
        queueSpeech(chapter, from, flush = true)
        onSpeechChanged?.invoke()
    }

    fun pauseSpeech() {
        if (!speaking) return
        speechPaused = true
        engine?.stop()
        onSpeechChanged?.invoke()
    }

    fun resumeSpeech() {
        if (!speaking) return
        speechPaused = false
        val last = speechHighlight
        if (last != null) queueSpeech(last.chapter, last.start, flush = true) else startSpeech()
        onSpeechChanged?.invoke()
    }

    fun stopSpeech() {
        val wasSpeaking = speaking
        speaking = false
        speechPaused = false
        speechHighlight = null
        speechSentence = null
        speechCurrentId = null
        speechSpans.clear()
        speechQueue.clear()
        speechTexts.clear()
        engine?.stop()
        if (wasSpeaking) onSpeechChanged?.invoke()
    }

    /** Khi đang đọc to: sang câu sau (1) hoặc quay lại câu trước (-1); qua được ranh giới chương. */
    fun skipSpeech(direction: Int) {
        if (!speaking) return
        val current = speechHighlight ?: speechQueue.firstOrNull()?.second ?: return
        val starts = sentenceStarts(current.chapter)
        val target: Pair<Int, Int>? = if (direction > 0) {
            starts.firstOrNull { it > current.start }?.let { current.chapter to it }
                ?: (current.chapter + 1).takeIf { it < chapterCount }?.let { it to 0 }
        } else {
            starts.lastOrNull { it < current.start }?.let { current.chapter to it }
                ?: (current.chapter - 1).takeIf { it >= 0 }?.let { previous -> previous to (sentenceStarts(previous).lastOrNull() ?: 0) }
                ?: (current.chapter to current.start)
        }
        if (target == null) {
            // Câu cuối của sách: "câu sau" là hết.
            stopSpeech()
            return
        }
        speechPaused = false
        queueSpeech(target.first, target.second, flush = true)
        onSpeechChanged?.invoke()
    }

    /** Vị trí bắt đầu của từng câu trong chương [index]. */
    private fun sentenceStarts(index: Int): List<Int> {
        val out = ArrayList<Int>()
        for (block in book.chapters[index].blocks) {
            if (block !is TextBlock) continue
            for (range in SpeechText.sentences(block.text, 0)) out += block.start + range.first
        }
        return out
    }

    private fun queueSpeech(fromChapter: Int, fromOffset: Int, flush: Boolean) {
        val speech = engine ?: return
        speechSpans.clear()
        speechQueue.clear()
        speechTexts.clear()
        var c = fromChapter
        var offset = fromOffset
        while (c < chapterCount) {
            val items = buildSpeechItems(c, offset)
            if (items.isNotEmpty()) {
                speechChapter = c
                speechLastId = items.last().id
                speech.speak(items, speechRate, flush)
                return
            }
            c++
            offset = 0
        }
        stopSpeech()
    }

    private fun buildSpeechItems(index: Int, from: Int): List<SpeechItem> {
        val items = ArrayList<SpeechItem>()
        for (block in book.chapters[index].blocks) {
            if (block !is TextBlock || block.end <= from) continue
            val local = (from - block.start).coerceAtLeast(0)
            for (range in SpeechText.sentences(block.text, local)) {
                val id = "s${speechSerial++}"
                val span = TextHighlight(index, block.start + range.first, block.start + range.last + 1)
                val text = block.text.substring(range.first, range.last + 1)
                speechSpans[id] = span
                speechQueue += id to span
                speechTexts[id] = text
                items += SpeechItem(id, text)
            }
        }
        return items
    }

    private fun followSpeech(span: TextHighlight) {
        val range = visibleRange()
        val visible = span.chapter == chapter && range != null && span.start >= range.first && span.start < range.second
        if (visible) return
        val forward = span.chapter > chapter || (span.chapter == chapter && span.start >= (range?.second ?: anchor))
        chapter = span.chapter
        anchor = span.start
        navDirection = if (forward) 1 else 0
        navSerial++
        ensure(chapter)
        prefetch()
        report()
    }

    // ---------------------------------------------------------------- thông tin hiển thị

    /** Kiểu Kindle: "vị trí" ≈ 150 ký tự. */
    val location: Int get() = globalOffset / LOCATION_CHARS + 1
    val locationCount: Int get() = book.totalLength / LOCATION_CHARS + 1

    fun minutesLeftInChapter(): Int {
        val length = book.chapters.getOrNull(chapter)?.length ?: return 0
        return ((length - anchor.coerceAtMost(length)) / CHARS_PER_MINUTE.toFloat()).let { kotlin.math.ceil(it).toInt() }
    }

    fun minutesLeftInBook(): Int =
        kotlin.math.ceil((book.totalLength - globalOffset).coerceAtLeast(0) / CHARS_PER_MINUTE.toFloat()).toInt()

    /** Mục lục đang đọc (mục cuối cùng nằm trước vị trí hiện tại). */
    fun currentTocIndex(): Int {
        val here = globalOffset
        var result = -1
        for ((i, entry) in book.toc.withIndex()) {
            if (book.globalOffset(ReadingPosition(entry.chapter, entry.offset)) <= here) result = i
        }
        return result
    }

    fun isNear(position: ReadingPosition): Boolean =
        abs(book.globalOffset(position) - globalOffset) < LOCATION_CHARS

    companion object {
        const val END = Int.MAX_VALUE / 4
        const val LOCATION_CHARS = 150
        const val CHARS_PER_MINUTE = 1100
    }
}
