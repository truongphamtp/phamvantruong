package io.github.truongphamtp.docsach.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.truongphamtp.docsach.core.Bookmark
import io.github.truongphamtp.docsach.core.FoldInfo
import io.github.truongphamtp.docsach.core.HardwareKey
import io.github.truongphamtp.docsach.core.HighlightColor
import io.github.truongphamtp.docsach.core.Note
import io.github.truongphamtp.docsach.core.ReaderSettings
import io.github.truongphamtp.docsach.core.ReaderTheme
import io.github.truongphamtp.docsach.core.ReadingPosition
import io.github.truongphamtp.docsach.core.ToolbarSide
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import io.github.truongphamtp.docsach.library.NoteCard
import io.github.truongphamtp.docsach.ui.AccentTile
import io.github.truongphamtp.docsach.ui.FloatingRail
import io.github.truongphamtp.docsach.ui.GlassGroup
import io.github.truongphamtp.docsach.ui.GlassIconButton
import io.github.truongphamtp.docsach.ui.GlassSearchField
import io.github.truongphamtp.docsach.ui.HyperSlider
import io.github.truongphamtp.docsach.ui.LocalHazeState
import io.github.truongphamtp.docsach.ui.LocalHyper
import io.github.truongphamtp.docsach.ui.PillButton
import io.github.truongphamtp.docsach.ui.SectionLabel
import io.github.truongphamtp.docsach.ui.glass
import io.github.truongphamtp.docsach.ui.LocalBackHandler
import io.github.truongphamtp.docsach.ui.LocalReaderPlatform
import io.github.truongphamtp.docsach.ui.PinnedRail
import io.github.truongphamtp.docsach.ui.RailAction
import io.github.truongphamtp.docsach.ui.ReaderChromeTheme
import io.github.truongphamtp.docsach.ui.SidePanel
import io.github.truongphamtp.docsach.ui.ZoomState
import io.github.truongphamtp.docsach.ui.pageGestures
import io.github.truongphamtp.docsach.ui.readerColors
import java.util.UUID
import kotlin.math.roundToInt

/** Nguồn trang PDF (bản Android dùng PdfRenderer). */
interface PdfPageSource {
    val pageCount: Int

    /** Kích thước trang theo điểm (1/72 inch). */
    suspend fun pageSize(index: Int): IntSize

    suspend fun render(index: Int, widthPx: Int): ImageBitmap?
}

@Stable
class PdfReaderController(
    val source: PdfPageSource,
    val title: String,
    initialPage: Int,
    initialBookmarks: List<Bookmark>,
    private val onPositionChanged: (ReadingPosition, Float) -> Unit = { _, _ -> },
    private val onBookmarksChanged: (List<Bookmark>) -> Unit = {},
    initialNotes: List<Note> = emptyList(),
    private val onNotesChanged: (List<Note>) -> Unit = {},
) {
    val pageCount: Int = source.pageCount

    /** Ghi chú theo trang (PDF không chọn được chữ). */
    var notes by mutableStateOf(initialNotes.sortedBy { it.chapter })
        private set

    fun notesOn(page: Int): List<Note> = notes.filter { it.chapter == page }

    fun addPageNote(page: Int, text: String, color: HighlightColor = HighlightColor.YELLOW) {
        val note = Note(
            id = UUID.randomUUID().toString(),
            chapter = page,
            start = 0,
            end = 0,
            quote = "",
            text = text.trim(),
            color = color,
            label = "Trang ${page + 1}",
            createdAt = System.currentTimeMillis(),
        )
        notes = (notes + note).sortedBy { it.chapter }
        onNotesChanged(notes)
    }

    fun updateNote(note: Note) {
        notes = notes.map { if (it.id == note.id) note else it }
        onNotesChanged(notes)
    }

    fun removeNote(note: Note) {
        notes = notes.filter { it.id != note.id }
        onNotesChanged(notes)
    }

    var page by mutableIntStateOf(initialPage.coerceIn(0, maxOf(0, pageCount - 1)))
        private set
    var pagesPerView by mutableIntStateOf(1)
    var navSerial by mutableIntStateOf(0)
        private set
    var navDirection by mutableIntStateOf(0)
        private set
    var bookmarks by mutableStateOf(initialBookmarks.sortedBy { it.position.chapter })
        private set

    private val bitmaps = object : LinkedHashMap<Long, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ImageBitmap>?): Boolean = size > 10
    }
    private val sizes = HashMap<Int, IntSize>()

    val viewIndex: Int get() = page / pagesPerView
    val firstPageInView: Int get() = viewIndex * pagesPerView
    val progress: Float get() = if (pageCount <= 1) 1f else page / (pageCount - 1f)

    fun next(): Boolean {
        val nextFirst = (viewIndex + 1) * pagesPerView
        if (nextFirst >= pageCount) return false
        move(nextFirst, 1)
        return true
    }

    fun previous(): Boolean {
        if (viewIndex == 0) return false
        move((viewIndex - 1) * pagesPerView, -1)
        return true
    }

    fun goTo(index: Int) = move(index.coerceIn(0, maxOf(0, pageCount - 1)), 0)

    fun goToFraction(fraction: Float) = goTo((fraction.coerceIn(0f, 1f) * (pageCount - 1)).roundToInt())

    private fun move(index: Int, direction: Int) {
        page = index
        navDirection = direction
        navSerial++
        onPositionChanged(ReadingPosition(index, 0), progress)
    }

    private fun pagesInView(): IntRange = firstPageInView until minOf(firstPageInView + pagesPerView, pageCount)

    val isBookmarked: Boolean get() = bookmarks.any { it.position.chapter in pagesInView() }

    fun toggleBookmark() {
        val range = pagesInView()
        bookmarks = if (isBookmarked) {
            bookmarks.filter { it.position.chapter !in range }
        } else {
            (bookmarks + Bookmark(UUID.randomUUID().toString(), ReadingPosition(firstPageInView, 0), "Trang ${firstPageInView + 1}", "", System.currentTimeMillis()))
                .sortedBy { it.position.chapter }
        }
        onBookmarksChanged(bookmarks)
    }

    fun removeBookmark(bookmark: Bookmark) {
        bookmarks = bookmarks.filter { it.id != bookmark.id }
        onBookmarksChanged(bookmarks)
    }

    suspend fun pageSize(index: Int): IntSize = sizes[index] ?: source.pageSize(index).also { sizes[index] = it }

    suspend fun bitmap(index: Int, widthPx: Int): ImageBitmap? {
        val key = (index.toLong() shl 32) or widthPx.toLong()
        bitmaps[key]?.let { return it }
        val rendered = source.render(index, widthPx) ?: return null
        bitmaps[key] = rendered
        return rendered
    }
}

private enum class PdfPanel { PAGES, DISPLAY }

private val InvertColors = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

@Composable
fun PdfReaderScreen(
    controller: PdfReaderController,
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    onBack: () -> Unit,
    fold: FoldInfo?,
    modifier: Modifier = Modifier,
    initialMenuVisible: Boolean = false,
) {
    val colors = remember(settings.theme) { readerColors(settings.theme) }
    var menuVisible by rememberSaveable { mutableStateOf(initialMenuVisible) }
    var panel by remember { mutableStateOf<PdfPanel?>(null) }
    var lastPanel by remember { mutableStateOf(PdfPanel.PAGES) }
    val platform = LocalReaderPlatform.current
    val railOnRight = settings.toolbarSide == ToolbarSide.RIGHT
    val zoom = remember { ZoomState() }
    /** Trang đang viết ghi chú (nhấn giữ trên trang). */
    var notePage by remember { mutableStateOf<Int?>(null) }

    val chromeVisible = menuVisible || panel != null
    LaunchedEffect(chromeVisible) { platform.setImmersive(!chromeVisible) }
    LaunchedEffect(colors.dark) { platform.setLightSystemBars(!colors.dark) }
    DisposableEffect(Unit) {
        onDispose {
            platform.setImmersive(false)
            platform.setLightSystemBars(null)
        }
    }
    DisposableEffect(settings.keepScreenOn) {
        platform.setKeepScreenOn(settings.keepScreenOn)
        onDispose { platform.setKeepScreenOn(false) }
    }
    DisposableEffect(settings.brightness) {
        platform.setBrightness(settings.brightness)
        onDispose { platform.setBrightness(-1f) }
    }
    val latestSettings by rememberUpdatedState(settings)
    DisposableEffect(controller) {
        platform.setKeyHandler { key ->
            when (key) {
                HardwareKey.PAGE_DOWN -> controller.next()
                HardwareKey.PAGE_UP -> controller.previous()
                HardwareKey.VOLUME_DOWN -> if (latestSettings.volumeKeys) { controller.next(); true } else false
                HardwareKey.VOLUME_UP -> if (latestSettings.volumeKeys) { controller.previous(); true } else false
            }
        }
        onDispose { platform.setKeyHandler(null) }
    }
    LaunchedEffect(controller.navSerial) { zoom.reset() }
    LocalBackHandler.current(panel != null || menuVisible) {
        if (panel != null) panel = null else menuVisible = false
    }

    fun openPanel(p: PdfPanel) {
        lastPanel = p
        panel = p
    }

    val actions = listOf(
        RailAction(Icons.AutoMirrored.Rounded.ArrowBackIos, "Thư viện", onClick = onBack),
        RailAction(Icons.Rounded.GridView, "Trang", selected = panel == PdfPanel.PAGES) { openPanel(PdfPanel.PAGES) },
        RailAction(Icons.Rounded.TextFields, "Hiển thị", selected = panel == PdfPanel.DISPLAY) { openPanel(PdfPanel.DISPLAY) },
        RailAction(
            if (controller.isBookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
            "Dấu trang",
            selected = controller.isBookmarked,
        ) { controller.toggleBookmark() },
    )

    val hazeState = remember { HazeState() }
    ReaderChromeTheme(colors) {
        CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(modifier.fillMaxSize().background(colors.background)) {
            Row(Modifier.fillMaxSize()) {
                if (settings.pinToolbar && !railOnRight) PinnedRail(actions)
                var origin by remember { mutableStateOf(Offset.Zero) }
                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .haze(hazeState)
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .onGloballyPositioned { origin = it.positionInWindow() },
                ) {
                    val density = LocalDensity.current
                    val spread = shouldUseSpread(settings.pageLayout, maxWidth.value, maxHeight.value) && controller.pageCount > 1
                    val perView = if (spread) 2 else 1
                    SideEffect { controller.pagesPerView = perView }
                    val foldX = fold?.takeIf { it.vertical }?.let { it.centerX - origin.x }
                    val areaWidth = constraints.maxWidth
                    val center = foldX?.takeIf { it > areaWidth * 0.35f && it < areaWidth * 0.65f } ?: (areaWidth / 2f)
                    val gutter = with(density) { 10.dp.toPx() }
                    val target = Triple(controller.viewIndex, controller.navSerial, controller.navDirection)
                    AnimatedContent(
                        targetState = target,
                        transitionSpec = {
                            val dir = targetState.third
                            if (!settings.pageAnimation || dir == 0 || targetState.second == initialState.second) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                slideInHorizontally(tween(220)) { it * dir } togetherWith slideOutHorizontally(tween(220)) { -it * dir }
                            }
                        },
                        contentKey = { it.first to it.second },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 28.dp, bottom = 34.dp)
                            .pageGestures(
                                onTap = { pos ->
                                    if (menuVisible) {
                                        menuVisible = false
                                    } else {
                                        val fx = pos.x / areaWidth
                                        when {
                                            fx < 0.3f -> controller.previous()
                                            fx > 0.7f -> controller.next()
                                            else -> menuVisible = true
                                        }
                                    }
                                },
                                onSwipe = { dir -> if (dir > 0) controller.next() else controller.previous() },
                                zoom = zoom,
                                onLongPress = { pos ->
                                    val second = controller.pagesPerView == 2 && pos.x > center &&
                                        controller.firstPageInView + 1 < controller.pageCount
                                    notePage = controller.firstPageInView + if (second) 1 else 0
                                    menuVisible = false
                                    true
                                },
                            )
                            .graphicsLayer {
                                scaleX = zoom.scale
                                scaleY = zoom.scale
                                translationX = zoom.offset.x
                                translationY = zoom.offset.y
                                transformOrigin = TransformOrigin(0f, 0f)
                            },
                        label = "lật trang PDF",
                    ) { state ->
                        val first = state.first * controller.pagesPerView
                        val invert = settings.theme == ReaderTheme.BLACK
                        if (controller.pagesPerView == 2) {
                            Row(Modifier.fillMaxSize()) {
                                val leftWidth = with(density) { (center - gutter / 2).toDp() }
                                PdfPage(controller, first, Alignment.CenterEnd, invert, zoom.isZoomed, Modifier.width(leftWidth).fillMaxHeight()) { notePage = it }
                                Spacer(Modifier.width(with(density) { gutter.toDp() }))
                                if (first + 1 < controller.pageCount) {
                                    PdfPage(controller, first + 1, Alignment.CenterStart, invert, zoom.isZoomed, Modifier.weight(1f).fillMaxHeight()) { notePage = it }
                                }
                            }
                        } else {
                            PdfPage(controller, first, Alignment.Center, invert, zoom.isZoomed, Modifier.fillMaxSize().padding(horizontal = 4.dp)) { notePage = it }
                        }
                    }
                    Row(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                    ) {
                        val label = if (controller.pagesPerView == 2 && controller.firstPageInView + 1 < controller.pageCount) {
                            "Trang ${controller.firstPageInView + 1}–${controller.firstPageInView + 2} / ${controller.pageCount}"
                        } else {
                            "Trang ${controller.firstPageInView + 1} / ${controller.pageCount}"
                        }
                        Text(label, fontSize = 12.sp, color = colors.secondaryText, modifier = Modifier.weight(1f))
                        Text("${(controller.progress * 100).roundToInt()}%", fontSize = 12.sp, color = colors.secondaryText)
                    }
                }
                if (settings.pinToolbar && railOnRight) PinnedRail(actions)
            }

            AnimatedVisibility(
                visible = menuVisible,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
            ) {
                val p = LocalHyper.current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .glass(RoundedCornerShape(26.dp), elevation = 10.dp)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AccentTile(Icons.Rounded.PictureAsPdf, size = 38.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(controller.title, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${controller.pageCount} trang", color = p.textSecondary, fontSize = 12.sp)
                    }
                }
            }
            AnimatedVisibility(
                visible = menuVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
            ) {
                PdfBottomBar(controller, colors.chrome, railOnRight, reserveRail = false)
            }
            if (!settings.pinToolbar) {
                Box(Modifier.fillMaxSize().padding(top = 96.dp, bottom = 128.dp)) {
                    AnimatedVisibility(
                        visible = menuVisible,
                        modifier = Modifier.align(BiasAlignment(if (railOnRight) 1f else -1f, 0.3f)),
                        enter = fadeIn() + slideInHorizontally { if (railOnRight) it else -it },
                        exit = fadeOut() + slideOutHorizontally { if (railOnRight) it else -it },
                    ) {
                        FloatingRail(actions, onRight = railOnRight)
                    }
                }
            }
            notePage?.let { page ->
                val existing = controller.notesOn(page).firstOrNull()
                NoteEditorDialog(
                    quote = "Trang ${page + 1}",
                    initialText = existing?.text.orEmpty(),
                    editing = existing != null,
                    onDismiss = { notePage = null },
                    onSave = { text ->
                        notePage = null
                        when {
                            existing != null && text.isBlank() -> controller.removeNote(existing)
                            existing != null -> controller.updateNote(existing.copy(text = text.trim()))
                            text.isNotBlank() -> controller.addPageNote(page, text)
                        }
                    },
                )
            }

            SidePanel(
                visible = panel != null,
                onRight = railOnRight,
                title = if ((panel ?: lastPanel) == PdfPanel.PAGES) "Trang, dấu trang, ghi chú" else "Hiển thị",
                onDismiss = { panel = null },
            ) {
                when (panel ?: lastPanel) {
                    PdfPanel.PAGES -> PdfPagesPanel(controller) {
                        panel = null
                        menuVisible = false
                    }
                    PdfPanel.DISPLAY -> DisplayPanel(settings, onSettingsChange, textOptions = false)
                }
            }
        }
        }
    }
}

@Composable
private fun PdfPage(
    controller: PdfReaderController,
    index: Int,
    alignment: Alignment,
    invert: Boolean,
    sharp: Boolean,
    modifier: Modifier,
    onOpenNotes: (Int) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val pageSize by produceState<IntSize?>(null, index) { value = controller.pageSize(index) }
        val size = pageSize
        if (size == null || size.width <= 0 || size.height <= 0) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp))
            return@BoxWithConstraints
        }
        val scale = minOf(constraints.maxWidth / size.width.toFloat(), constraints.maxHeight / size.height.toFloat())
        val w = (size.width * scale).roundToInt().coerceAtLeast(1)
        val h = (size.height * scale).roundToInt().coerceAtLeast(1)
        val renderWidth = if (sharp) minOf(w * 2, 4096) else w
        val bitmap by produceState<ImageBitmap?>(null, index, renderWidth) { value = controller.bitmap(index, renderWidth) }
        Box(
            Modifier
                .align(alignment)
                .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                .shadow(6.dp, RoundedCornerShape(6.dp))
                .clip(RoundedCornerShape(6.dp))
                .background(if (invert) Color.Black else Color.White),
        ) {
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = "Trang ${index + 1}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                    colorFilter = if (invert) InvertColors else null,
                )
            }
            val count = controller.notesOn(index).size
            if (count > 0) {
                // Huy hiệu ghi chú ở góc trang; chạm để xem/sửa.
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .glass(CircleShape, strong = true, elevation = 4.dp)
                        .clickable { onOpenNotes(index) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.EditNote, contentDescription = "Ghi chú", tint = LocalHyper.current.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("$count", color = LocalHyper.current.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun PdfBottomBar(controller: PdfReaderController, chrome: Color, railOnRight: Boolean, reserveRail: Boolean) {
    val p = LocalHyper.current
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val value = if (dragging) dragValue else controller.progress
    val pageLabel = (value * (controller.pageCount - 1)).roundToInt() + 1
    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(
                start = if (!railOnRight && reserveRail) 96.dp else 12.dp,
                end = if (railOnRight && reserveRail) 96.dp else 12.dp,
                top = 8.dp,
                bottom = 10.dp,
            )
            .glass(RoundedCornerShape(28.dp), elevation = 10.dp)
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Trang $pageLabel / ${controller.pageCount}", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("${(value * 100).roundToInt()}%", color = p.textSecondary, fontSize = 12.sp)
        }
        HyperSlider(
            value = value,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                if (dragging) controller.goToFraction(dragValue)
                dragging = false
            },
            knob = true,
        )
    }
}

@Composable
private fun PdfPagesPanel(controller: PdfReaderController, onNavigate: () -> Unit) {
    val p = LocalHyper.current
    var input by rememberSaveable { mutableStateOf("") }
    fun go() {
        val number = input.trim().toIntOrNull() ?: return
        controller.goTo(number - 1)
        onNavigate()
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassSearchField(
            value = input,
            onValueChange = { text -> input = text.filter { it.isDigit() }.take(6) },
            placeholder = "Đi tới trang (1–${controller.pageCount})",
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Go,
            onAction = { go() },
            leadingIcon = null,
            modifier = Modifier.weight(1f),
        )
        PillButton("Đi", onClick = { go() })
    }
    Spacer(Modifier.height(8.dp))
    SectionLabel("Dấu trang", modifier = Modifier.padding(horizontal = 16.dp))
    if (controller.bookmarks.isEmpty()) {
        Text(
            "Chưa có dấu trang. Bấm nút “Dấu trang” trên thanh công cụ để đánh dấu trang đang xem.",
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
            color = p.textSecondary,
            fontSize = 14.sp,
        )
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (controller.notes.isNotEmpty()) {
            item(key = "tieu-de-ghi-chu") { SectionLabel("Ghi chú (nhấn giữ trên trang để thêm)") }
            items(controller.notes, key = { "note-" + it.id }) { note ->
                NoteCard(
                    note = note,
                    onClick = {
                        controller.goTo(note.chapter)
                        onNavigate()
                    },
                    onDelete = { controller.removeNote(note) },
                )
            }
            item(key = "tieu-de-dau-trang") { SectionLabel("Dấu trang") }
        }
        items(controller.bookmarks, key = { it.id }) { mark ->
            GlassGroup(Modifier.clickable {
                controller.goTo(mark.position.chapter)
                onNavigate()
            }) {
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(mark.label, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    GlassIconButton(Icons.Rounded.DeleteOutline, null, onClick = { controller.removeBookmark(mark) }, size = 36.dp)
                }
            }
        }
    }
}
