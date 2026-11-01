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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.GridView
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.truongphamtp.docsach.core.Bookmark
import io.github.truongphamtp.docsach.core.FoldInfo
import io.github.truongphamtp.docsach.core.HardwareKey
import io.github.truongphamtp.docsach.core.ReaderSettings
import io.github.truongphamtp.docsach.core.ReaderTheme
import io.github.truongphamtp.docsach.core.ReadingPosition
import io.github.truongphamtp.docsach.core.ToolbarSide
import io.github.truongphamtp.docsach.ui.FloatingRail
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
) {
    val pageCount: Int = source.pageCount

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
        RailAction(Icons.AutoMirrored.Filled.ArrowBack, "Thư viện", onClick = onBack),
        RailAction(Icons.Filled.GridView, "Trang", selected = panel == PdfPanel.PAGES) { openPanel(PdfPanel.PAGES) },
        RailAction(Icons.Filled.FormatSize, "Hiển thị", selected = panel == PdfPanel.DISPLAY) { openPanel(PdfPanel.DISPLAY) },
        RailAction(
            if (controller.isBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
            "Dấu trang",
            selected = controller.isBookmarked,
        ) { controller.toggleBookmark() },
    )

    ReaderChromeTheme(colors) {
        Box(modifier.fillMaxSize().background(colors.background)) {
            Row(Modifier.fillMaxSize()) {
                if (settings.pinToolbar && !railOnRight) PinnedRail(actions, containerColor = colors.chrome)
                var origin by remember { mutableStateOf(Offset.Zero) }
                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
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
                                PdfPage(controller, first, Alignment.CenterEnd, invert, zoom.isZoomed, Modifier.width(leftWidth).fillMaxHeight())
                                Spacer(Modifier.width(with(density) { gutter.toDp() }))
                                if (first + 1 < controller.pageCount) {
                                    PdfPage(controller, first + 1, Alignment.CenterStart, invert, zoom.isZoomed, Modifier.weight(1f).fillMaxHeight())
                                }
                            }
                        } else {
                            PdfPage(controller, first, Alignment.Center, invert, zoom.isZoomed, Modifier.fillMaxSize().padding(horizontal = 4.dp))
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
                if (settings.pinToolbar && railOnRight) PinnedRail(actions, containerColor = colors.chrome)
            }

            AnimatedVisibility(
                visible = menuVisible,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
            ) {
                Surface(color = colors.chrome, shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        controller.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                    )
                }
            }
            AnimatedVisibility(
                visible = menuVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
            ) {
                PdfBottomBar(controller, colors.chrome, railOnRight, reserveRail = !settings.pinToolbar)
            }
            if (!settings.pinToolbar) {
                Box(Modifier.fillMaxSize().padding(top = 72.dp, bottom = 118.dp)) {
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
            SidePanel(
                visible = panel != null,
                onRight = railOnRight,
                title = if ((panel ?: lastPanel) == PdfPanel.PAGES) "Trang & dấu trang" else "Hiển thị",
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

@Composable
private fun PdfPage(
    controller: PdfReaderController,
    index: Int,
    alignment: Alignment,
    invert: Boolean,
    sharp: Boolean,
    modifier: Modifier,
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
        }
    }
}

@Composable
private fun PdfBottomBar(controller: PdfReaderController, chrome: Color, railOnRight: Boolean, reserveRail: Boolean) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val value = if (dragging) dragValue else controller.progress
    val pageLabel = ((if (dragging) dragValue else controller.progress) * (controller.pageCount - 1)).roundToInt() + 1
    Surface(color = chrome, shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(
                    start = if (!railOnRight && reserveRail) 96.dp else 24.dp,
                    end = if (railOnRight && reserveRail) 96.dp else 24.dp,
                    top = 10.dp,
                    bottom = 6.dp,
                ),
        ) {
            Text("Trang $pageLabel / ${controller.pageCount}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = value,
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    controller.goToFraction(dragValue)
                    dragging = false
                },
            )
        }
    }
}

@Composable
private fun PdfPagesPanel(controller: PdfReaderController, onNavigate: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    fun go() {
        val number = input.trim().toIntOrNull() ?: return
        controller.goTo(number - 1)
        onNavigate()
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { text -> input = text.filter { it.isDigit() }.take(6) },
            label = { Text("Đi tới trang (1–${controller.pageCount})") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { go() }),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { go() }) { Text("Đi") }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Dấu trang",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
    if (controller.bookmarks.isEmpty()) {
        Text(
            "Chưa có dấu trang. Bấm nút “Dấu trang” trên thanh công cụ để đánh dấu trang đang xem.",
            modifier = Modifier.padding(horizontal = 20.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(controller.bookmarks, key = { it.id }) { mark ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        controller.goTo(mark.position.chapter)
                        onNavigate()
                    }
                    .padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(mark.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { controller.removeBookmark(mark) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Xoá dấu trang")
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        }
    }
}
