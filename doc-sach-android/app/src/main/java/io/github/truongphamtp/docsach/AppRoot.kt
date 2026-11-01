package io.github.truongphamtp.docsach

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.truongphamtp.docsach.core.BookFormat
import io.github.truongphamtp.docsach.core.BookRecord
import io.github.truongphamtp.docsach.core.FoldInfo
import io.github.truongphamtp.docsach.core.Note
import io.github.truongphamtp.docsach.core.PositionOffer
import io.github.truongphamtp.docsach.core.ReadingPosition
import io.github.truongphamtp.docsach.format.BookContent
import io.github.truongphamtp.docsach.format.EpubArchive
import io.github.truongphamtp.docsach.format.EpubParser
import io.github.truongphamtp.docsach.format.TxtParser
import io.github.truongphamtp.docsach.library.LanActions
import io.github.truongphamtp.docsach.library.LibraryScreen
import io.github.truongphamtp.docsach.library.NotesScreen
import io.github.truongphamtp.docsach.library.SyncActions
import io.github.truongphamtp.docsach.library.SyncOfferBanner
import io.github.truongphamtp.docsach.platform.AndroidPdfSource
import io.github.truongphamtp.docsach.platform.AndroidSpeechEngine
import io.github.truongphamtp.docsach.platform.EpubImageProvider
import io.github.truongphamtp.docsach.reader.BookImageProvider
import io.github.truongphamtp.docsach.reader.PdfReaderController
import io.github.truongphamtp.docsach.reader.PdfReaderScreen
import io.github.truongphamtp.docsach.reader.ReaderController
import io.github.truongphamtp.docsach.reader.ReaderScreen
import io.github.truongphamtp.docsach.sync.MicrosoftSignInScreen
import io.github.truongphamtp.docsach.ui.LiquidBackground
import io.github.truongphamtp.docsach.ui.LocalHyper
import io.github.truongphamtp.docsach.ui.PillButton
import io.github.truongphamtp.docsach.ui.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Điều hướng: Thư viện <-> Màn hình đọc. */
@Composable
fun AppRoot(
    app: DocSachApp,
    openRequests: StateFlow<String?>,
    onOpenRequestHandled: () -> Unit,
    fold: FoldInfo?,
) {
    var openBookId by rememberSaveable { mutableStateOf<String?>(null) }
    var jumpNoteId by rememberSaveable { mutableStateOf<String?>(null) }
    var showNotes by rememberSaveable { mutableStateOf(false) }
    val request by openRequests.collectAsState()
    LaunchedEffect(request) {
        request?.let {
            openBookId = it
            jumpNoteId = null
            onOpenRequestHandled()
        }
    }
    val id = openBookId
    val route: Route = when {
        id != null -> Route.Reader(id, jumpNoteId)
        showNotes -> Route.Notes
        else -> Route.Library
    }
    val signIn by app.sync.signInRequest.collectAsState()
    Box(Modifier.fillMaxSize()) {
        // Chuyển màn hình mượt: mở sách thì "phóng ra" từ thư viện, đóng sách thì thu về.
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                val forward = targetState is Route.Reader || (targetState is Route.Notes && initialState is Route.Library)
                if (forward) {
                    (fadeIn(tween(260, delayMillis = 30)) + scaleIn(spring(dampingRatio = 0.86f, stiffness = 380f), initialScale = 0.92f)) togetherWith
                        (fadeOut(tween(180)) + scaleOut(tween(220), targetScale = 1.03f))
                } else {
                    (fadeIn(tween(240)) + scaleIn(spring(dampingRatio = 0.86f, stiffness = 380f), initialScale = 1.04f)) togetherWith
                        (fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 0.92f))
                }
            },
            label = "chuyển màn hình",
        ) { target ->
            when (target) {
                is Route.Reader -> {
                    BackHandler { openBookId = null }
                    ReaderRoute(app, target.bookId, target.noteId, fold) { openBookId = null }
                }
                Route.Notes -> {
                    BackHandler { showNotes = false }
                    NotesRoute(
                        app = app,
                        onOpenNote = { book, note ->
                            jumpNoteId = note.id
                            openBookId = book.id
                        },
                        onBack = { showNotes = false },
                    )
                }
                Route.Library -> LibraryRoute(app, onOpenNotes = { showNotes = true }) {
                    jumpNoteId = null
                    openBookId = it.id
                }
            }
        }
        // Trang đăng nhập Microsoft trượt lên phủ toàn màn hình.
        AnimatedVisibility(
            visible = signIn != null,
            enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 380f)) { it / 3 } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(220)) { it / 3 } + fadeOut(tween(180)),
        ) {
            val shownRequest = remember { mutableStateOf(signIn) }
            signIn?.let { shownRequest.value = it }
            shownRequest.value?.let { current ->
                MicrosoftSignInScreen(
                    url = current.url,
                    isRedirect = app.sync::isRedirect,
                    onRedirect = app.sync::finishSignIn,
                    onCancel = app.sync::cancelSignIn,
                )
            }
        }
    }
}

private sealed interface Route {
    data object Library : Route
    data object Notes : Route
    data class Reader(val bookId: String, val noteId: String?) : Route
}

@Composable
private fun NotesRoute(app: DocSachApp, onOpenNote: (BookRecord, Note) -> Unit, onBack: () -> Unit) {
    val books by app.library.books.collectAsState()
    val readerSettings by app.settings.reader.collectAsState()
    NotesScreen(
        books = books,
        coverLoader = app.covers,
        onOpenNote = onOpenNote,
        onDeleteNote = { book, note -> app.library.updateNotes(book.id, book.notes.filter { it.id != note.id }) },
        onBack = onBack,
        toolbarSide = readerSettings.toolbarSide,
    )
}

@Composable
private fun LibraryRoute(app: DocSachApp, onOpenNotes: () -> Unit, onOpen: (BookRecord) -> Unit) {
    val books by app.library.books.collectAsState()
    val prefs by app.settings.library.collectAsState()
    val readerSettings by app.settings.reader.collectAsState()
    val busy by app.importer.busy.collectAsState()
    val message by app.importer.message.collectAsState()
    val sync by app.sync.state.collectAsState()
    val lan by app.lan.state.collectAsState()
    val lanActions = remember {
        LanActions(
            onStartPairing = app.lan::startPairing,
            onStopPairing = app.lan::stopPairing,
            onPair = app.lan::pairWith,
            onPairAddress = app.lan::pairWithAddress,
            onSyncNow = app.lan::syncNow,
            onUnpair = app.lan::unpair,
        )
    }
    val syncActions = remember {
        SyncActions(
            onSaveClientId = app.sync::setClientId,
            onSignIn = app.sync::beginSignIn,
            onSignOut = app.sync::signOut,
            onSyncNow = app.sync::syncNow,
            onSyncFilesChange = app.sync::setSyncFiles,
            onWifiOnlyChange = app.sync::setWifiOnly,
            onDownloadPending = app.sync::downloadPendingNow,
        )
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        app.importer.importAll(uris)
    }
    LibraryScreen(
        books = books,
        prefs = prefs,
        onPrefsChange = app.settings::updateLibrary,
        coverLoader = app.covers,
        busy = busy,
        message = message,
        onMessageShown = app.importer::clearMessage,
        onOpen = onOpen,
        onAddBooks = { picker.launch(arrayOf("*/*")) },
        onDelete = { app.importer.delete(it) },
        toolbarSide = readerSettings.toolbarSide,
        onOpenNotes = onOpenNotes,
        sync = sync,
        syncActions = syncActions,
        lan = lan,
        lanActions = lanActions,
    )
}

private sealed interface Session {
    class Text(val content: BookContent, val archive: EpubArchive?, val images: BookImageProvider?) : Session
    class Pdf(val source: AndroidPdfSource) : Session
    class Failed(val message: String) : Session
}

private fun openSession(app: DocSachApp, record: BookRecord): Session {
    val file = File(app.importer.booksDir, record.fileName)
    if (!file.exists()) return Session.Failed("Không tìm thấy tệp của cuốn sách này. Có thể tệp đã bị xoá.")
    return try {
        when (record.format) {
            BookFormat.EPUB -> {
                val archive = EpubArchive(file)
                try {
                    Session.Text(EpubParser.parse(archive, record.title), archive, EpubImageProvider(archive))
                } catch (e: Throwable) {
                    archive.close()
                    throw e
                }
            }
            BookFormat.TXT -> Session.Text(TxtParser.parse(TxtParser.decode(file.readBytes()), record.title), null, null)
            BookFormat.PDF -> Session.Pdf(AndroidPdfSource(file))
        }
    } catch (e: SecurityException) {
        Session.Failed("Tệp PDF này được đặt mật khẩu nên chưa mở được.")
    } catch (e: OutOfMemoryError) {
        Session.Failed("Tệp quá lớn, máy không đủ bộ nhớ để mở.")
    } catch (e: Exception) {
        Session.Failed("Không mở được sách: ${e.message ?: e.javaClass.simpleName}")
    }
}

private fun closeSession(session: Session?) {
    when (session) {
        is Session.Text -> runCatching { session.archive?.close() }
        is Session.Pdf -> runCatching { session.source.close() }
        else -> Unit
    }
}

@Composable
private fun ReaderRoute(app: DocSachApp, bookId: String, jumpNoteId: String?, fold: FoldInfo?, onClose: () -> Unit) {
    val record = remember(bookId) { app.library.get(bookId) }
    if (record == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val settings by app.settings.reader.collectAsState()
    val session by produceState<Session?>(null, bookId) {
        val opened = withContext(Dispatchers.IO) { openSession(app, record) }
        value = opened
        awaitDispose { closeSession(opened) }
    }
    LaunchedEffect(bookId) { app.library.markOpened(bookId) }
    // Báo cho phần đồng bộ: sách đang mở (không ghi đè ghi chú đang sửa) và đồng bộ ngay khi đóng.
    DisposableEffect(record.fingerprint) {
        app.sync.bookOpened(record.fingerprint)
        app.lan.bookOpened(record.fingerprint)
        onDispose {
            app.sync.bookClosed(record.fingerprint)
            app.lan.bookClosed(record.fingerprint)
        }
    }
    val cloudOffers by app.sync.offers.collectAsState()
    val lanOffers by app.lan.offers.collectAsState()
    val offer = lanOffers[record.fingerprint] ?: cloudOffers[record.fingerprint]
    val answer: (PositionOffer) -> Unit = {
        app.sync.answerOffer(it.fingerprint)
        app.lan.answerOffer(it.fingerprint)
    }

    when (val s = session) {
        null -> CenterMessage(record.title, loading = true)
        is Session.Failed -> CenterMessage(s.message, loading = false, onClose = onClose)
        is Session.Text -> {
            val scope = rememberCoroutineScope()
            val controller = remember(s) {
                // Mở lần đầu: nhảy tới điểm "bắt đầu đọc" do file khai báo, giống Kindle.
                val firstOpen = record.lastOpenedAt == 0L && record.position == ReadingPosition()
                val start = if (firstOpen) s.content.startPosition ?: record.position else record.position
                ReaderController(
                    book = s.content,
                    initialPosition = start,
                    initialBookmarks = record.bookmarks,
                    scope = scope,
                    images = s.images,
                    onPositionChanged = { position, progress -> app.library.updatePosition(bookId, position, progress) },
                    onBookmarksChanged = { app.library.updateBookmarks(bookId, it) },
                    initialNotes = record.notes,
                    onNotesChanged = { app.library.updateNotes(bookId, it) },
                )
            }
            // Mở từ sổ ghi chú: nhảy tới đúng đoạn có ghi chú và tô sáng.
            LaunchedEffect(controller, jumpNoteId) {
                record.notes.firstOrNull { it.id == jumpNoteId }?.let { controller.goToNote(it) }
            }
            val context = LocalContext.current
            DisposableEffect(controller) {
                val engine = AndroidSpeechEngine(context)
                controller.attachSpeech(engine)
                onDispose {
                    controller.stopSpeech()
                    engine.release()
                }
            }
            Box(Modifier.fillMaxSize()) {
                ReaderScreen(controller, settings, app.settings::updateReader, onClose, fold)
                OfferBanner(
                    offer = offer,
                    onGo = {
                        controller.goTo(it.position)
                        answer(it)
                    },
                    onStay = answer,
                )
            }
        }
        is Session.Pdf -> {
            val controller = remember(s) {
                val jump = record.notes.firstOrNull { it.id == jumpNoteId }
                PdfReaderController(
                    source = s.source,
                    title = record.title,
                    initialPage = jump?.chapter ?: record.position.chapter,
                    initialBookmarks = record.bookmarks,
                    onPositionChanged = { position, progress -> app.library.updatePosition(bookId, position, progress) },
                    onBookmarksChanged = { app.library.updateBookmarks(bookId, it) },
                    initialNotes = record.notes,
                    onNotesChanged = { app.library.updateNotes(bookId, it) },
                )
            }
            Box(Modifier.fillMaxSize()) {
                PdfReaderScreen(controller, settings, app.settings::updateReader, onClose, fold)
                OfferBanner(
                    offer = offer,
                    onGo = {
                        controller.goTo(it.position.chapter)
                        answer(it)
                    },
                    onStay = answer,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.OfferBanner(offer: PositionOffer?, onGo: (PositionOffer) -> Unit, onStay: (PositionOffer) -> Unit) {
    SyncOfferBanner(
        offer = offer,
        onGo = onGo,
        onStay = onStay,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(start = 16.dp, end = 16.dp, top = 72.dp),
    )
}

@Composable
private fun CenterMessage(text: String, loading: Boolean, onClose: (() -> Unit)? = null) {
    val p = LocalHyper.current
    Box(Modifier.fillMaxSize()) {
        LiquidBackground(Modifier.fillMaxSize())
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(24.dp)
                .widthIn(max = 420.dp)
                .glass(RoundedCornerShape(32.dp), strong = true, elevation = 10.dp)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (loading) {
                CircularProgressIndicator(color = p.accent)
                Spacer(Modifier.height(16.dp))
                Text("Đang mở “$text”…", color = p.text, textAlign = TextAlign.Center)
            } else {
                Text(text, color = p.text, fontSize = 16.sp, textAlign = TextAlign.Center)
                if (onClose != null) {
                    Spacer(Modifier.height(18.dp))
                    PillButton("Về thư viện", onClick = onClose)
                }
            }
        }
    }
}
