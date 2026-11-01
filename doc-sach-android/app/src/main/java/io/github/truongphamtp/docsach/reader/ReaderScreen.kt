package io.github.truongphamtp.docsach.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.truongphamtp.docsach.core.FoldInfo
import io.github.truongphamtp.docsach.core.HardwareKey
import io.github.truongphamtp.docsach.core.ReaderSettings
import io.github.truongphamtp.docsach.core.ToolbarSide
import io.github.truongphamtp.docsach.ui.FloatingRail
import io.github.truongphamtp.docsach.ui.LocalBackHandler
import io.github.truongphamtp.docsach.ui.LocalReaderPlatform
import io.github.truongphamtp.docsach.ui.PinnedRail
import io.github.truongphamtp.docsach.ui.RailAction
import io.github.truongphamtp.docsach.ui.ReaderChromeTheme
import io.github.truongphamtp.docsach.ui.ReaderColors
import io.github.truongphamtp.docsach.ui.SidePanel
import io.github.truongphamtp.docsach.ui.pageGestures
import io.github.truongphamtp.docsach.ui.readerColors
import kotlin.math.roundToInt

enum class ReaderPanel { TOC, SEARCH, DISPLAY }

/** Thông tin hiển thị ở chân trang; chạm vào để đổi (giống Kindle). */
enum class FooterMode { PAGE, LOCATION, TIME_CHAPTER, TIME_BOOK, NONE }

private fun FooterMode.next(): FooterMode = FooterMode.entries[(ordinal + 1) % FooterMode.entries.size]

@Composable
fun ReaderScreen(
    controller: ReaderController,
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    onBack: () -> Unit,
    fold: FoldInfo?,
    modifier: Modifier = Modifier,
    initialMenuVisible: Boolean = false,
    initialPanel: ReaderPanel? = null,
) {
    val colors = remember(settings.theme) { readerColors(settings.theme) }
    var menuVisible by rememberSaveable { mutableStateOf(initialMenuVisible) }
    var panel by remember { mutableStateOf(initialPanel) }
    var lastPanel by remember { mutableStateOf(initialPanel ?: ReaderPanel.TOC) }
    var footerMode by rememberSaveable { mutableStateOf(FooterMode.PAGE) }
    val platform = LocalReaderPlatform.current
    val railOnRight = settings.toolbarSide == ToolbarSide.RIGHT

    fun openPanel(p: ReaderPanel) {
        lastPanel = p
        panel = p
    }

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
                HardwareKey.PAGE_DOWN -> controller.nextPage()
                HardwareKey.PAGE_UP -> controller.previousPage()
                HardwareKey.VOLUME_DOWN ->
                    if (latestSettings.volumeKeys && !controller.speaking) { controller.nextPage(); true } else false
                HardwareKey.VOLUME_UP ->
                    if (latestSettings.volumeKeys && !controller.speaking) { controller.previousPage(); true } else false
            }
        }
        onDispose { platform.setKeyHandler(null) }
    }
    controller.speechRate = settings.speechRate

    LocalBackHandler.current(panel != null || menuVisible) {
        if (panel != null) panel = null else menuVisible = false
    }

    val actions = listOf(
        RailAction(Icons.AutoMirrored.Filled.ArrowBack, "Thư viện", onClick = onBack),
        RailAction(Icons.AutoMirrored.Filled.MenuBook, "Mục lục", selected = panel == ReaderPanel.TOC) { openPanel(ReaderPanel.TOC) },
        RailAction(Icons.Filled.Search, "Tìm", selected = panel == ReaderPanel.SEARCH) { openPanel(ReaderPanel.SEARCH) },
        RailAction(Icons.Filled.FormatSize, "Hiển thị", selected = panel == ReaderPanel.DISPLAY) { openPanel(ReaderPanel.DISPLAY) },
        RailAction(
            if (controller.isBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
            "Dấu trang",
            selected = controller.isBookmarked,
        ) { controller.toggleBookmark() },
        RailAction(Icons.Filled.RecordVoiceOver, "Đọc to", selected = controller.speaking) {
            if (controller.speaking) controller.stopSpeech() else controller.startSpeech()
        },
    )

    ReaderChromeTheme(colors) {
        Box(modifier.fillMaxSize().background(colors.background)) {
            Row(Modifier.fillMaxSize()) {
                if (settings.pinToolbar && !railOnRight) {
                    PinnedRail(actions, containerColor = colors.chrome)
                }
                ReadingArea(
                    controller = controller,
                    settings = settings,
                    colors = colors,
                    fold = fold,
                    footerMode = footerMode,
                    onFooterTap = { footerMode = footerMode.next() },
                    menuVisible = menuVisible,
                    onMenuVisibleChange = { menuVisible = it },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                if (settings.pinToolbar && railOnRight) {
                    PinnedRail(actions, containerColor = colors.chrome)
                }
            }

            AnimatedVisibility(
                visible = menuVisible,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
            ) {
                ReaderTopBar(controller, colors)
            }
            AnimatedVisibility(
                visible = menuVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
            ) {
                ReaderBottomBar(controller, colors, railOnRight, reserveRail = !settings.pinToolbar)
            }
            if (!settings.pinToolbar) {
                Box(Modifier.fillMaxSize().padding(top = 72.dp, bottom = 118.dp)) {
                    AnimatedVisibility(
                        visible = menuVisible,
                        // Lệch xuống dưới một chút: vùng ngón cái khi cầm máy.
                        modifier = Modifier.align(BiasAlignment(if (railOnRight) 1f else -1f, 0.3f)),
                        enter = fadeIn() + slideInHorizontally { if (railOnRight) it else -it },
                        exit = fadeOut() + slideOutHorizontally { if (railOnRight) it else -it },
                    ) {
                        FloatingRail(actions, onRight = railOnRight)
                    }
                }
            }

            if (controller.speaking) {
                SpeechBar(
                    controller = controller,
                    modifier = Modifier
                        .align(if (railOnRight) Alignment.BottomEnd else Alignment.BottomStart)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 16.dp, vertical = if (menuVisible) 124.dp else 48.dp),
                )
            }

            SidePanel(
                visible = panel != null,
                onRight = railOnRight,
                title = when (panel ?: lastPanel) {
                    ReaderPanel.TOC -> "Mục lục"
                    ReaderPanel.SEARCH -> "Tìm trong sách"
                    ReaderPanel.DISPLAY -> "Hiển thị"
                },
                onDismiss = { panel = null },
            ) {
                when (panel ?: lastPanel) {
                    ReaderPanel.TOC -> TocPanel(controller) {
                        panel = null
                        menuVisible = false
                    }
                    ReaderPanel.SEARCH -> SearchPanel(controller) {
                        panel = null
                        menuVisible = false
                    }
                    ReaderPanel.DISPLAY -> DisplayPanel(settings, onSettingsChange, textOptions = true)
                }
            }

            controller.speechError?.let { message ->
                AlertDialog(
                    onDismissRequest = { controller.speechError = null },
                    title = { Text("Chưa đọc to được") },
                    text = { Text(message) },
                    confirmButton = {
                        TextButton(onClick = {
                            controller.speechError = null
                            platform.openSpeechSettings()
                        }) { Text("Mở cài đặt giọng nói") }
                    },
                    dismissButton = {
                        TextButton(onClick = { controller.speechError = null }) { Text("Đóng") }
                    },
                )
            }
        }
    }
}

@Composable
private fun ReadingArea(
    controller: ReaderController,
    settings: ReaderSettings,
    colors: ReaderColors,
    fold: FoldInfo?,
    footerMode: FooterMode,
    onFooterTap: () -> Unit,
    menuVisible: Boolean,
    onMenuVisibleChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val resolver = LocalFontFamilyResolver.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        modifier
            .windowInsetsPadding(WindowInsets.displayCutout)
            .onGloballyPositioned { origin = it.positionInWindow() },
    ) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val verticalFold = fold?.takeIf { it.vertical }
        val foldX = verticalFold?.let { it.centerX - origin.x }
        val foldWidth = verticalFold?.width?.toFloat() ?: 0f
        val geometry = remember(width, height, density, settings.pageLayout, settings.margin, foldX, foldWidth) {
            computeGeometry(width, height, density, settings, foldX, foldWidth)
        }
        val typography = remember(settings.font, settings.fontSize, settings.lineSpacing, settings.justify) {
            ReaderTypography(fontFamilyFor(settings.font), settings.fontSize, settings.lineSpacing, settings.justify)
        }
        val request = LayoutRequest(density, resolver, typography, geometry.pageWidth, geometry.pageHeight, geometry.pagesPerView)
        SideEffect { controller.updateLayout(request) }

        PagesContent(
            controller = controller,
            geometry = geometry,
            colors = colors,
            animate = settings.pageAnimation,
            modifier = Modifier
                .fillMaxSize()
                .pageGestures(
                    onTap = { pos ->
                        if (menuVisible) {
                            onMenuVisibleChange(false)
                        } else {
                            val fx = pos.x / width
                            when {
                                pos.y < geometry.pageTop -> onMenuVisibleChange(true)
                                fx < 0.3f -> controller.previousPage()
                                fx > 0.7f -> controller.nextPage()
                                else -> onMenuVisibleChange(true)
                            }
                        }
                        controller.clearSearchHighlight()
                    },
                    onSwipe = { dir -> if (dir > 0) controller.nextPage() else controller.previousPage() },
                ),
        )
        PageDecorations(controller, geometry, colors, footerMode, onFooterTap)
    }
}

/** Dòng tên sách phía trên và thông tin tiến độ phía dưới mỗi trang (giống Kindle). */
@Composable
private fun PageDecorations(
    controller: ReaderController,
    geometry: ReaderGeometry,
    colors: ReaderColors,
    footerMode: FooterMode,
    onFooterTap: () -> Unit,
) {
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, color = colors.secondaryText)
    val bookTitle = controller.book.title
    val chapterTitle = controller.book.chapterTitle(controller.chapter)
    val pages = controller.currentPages
    val pageNumber = controller.viewIndex * geometry.pagesPerView + 1
    val totalPages = pages?.pages?.size ?: 1
    val footerText = when (footerMode) {
        FooterMode.PAGE -> if (geometry.pagesPerView == 2 && pageNumber + 1 <= totalPages) {
            "Trang $pageNumber–${pageNumber + 1} / $totalPages trong chương"
        } else {
            "Trang $pageNumber / $totalPages trong chương"
        }
        FooterMode.LOCATION -> "Vị trí ${controller.location} / ${controller.locationCount}"
        FooterMode.TIME_CHAPTER -> formatMinutes(controller.minutesLeftInChapter(), "trong chương")
        FooterMode.TIME_BOOK -> formatMinutes(controller.minutesLeftInBook(), "cả cuốn")
        FooterMode.NONE -> " "
    }
    val percent = "${(controller.progress * 100).roundToInt()}%"
    val pageWidthDp = with(density) { geometry.pageWidth.toDp() }
    val headerTop = 14.dp
    val footerBottom = 12.dp
    Box(Modifier.fillMaxSize()) {
        val lefts = geometry.pageLefts.map { with(density) { it.toDp() } }
        if (geometry.pagesPerView == 2) {
            HeaderText(bookTitle, style, Modifier.offset(x = lefts[0], y = headerTop).width(pageWidthDp))
            HeaderText(chapterTitle, style, Modifier.offset(x = lefts[1], y = headerTop).width(pageWidthDp))
        } else {
            HeaderText(bookTitle, style, Modifier.offset(x = lefts[0], y = headerTop).width(pageWidthDp))
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = lefts.first())
                .padding(bottom = footerBottom)
                .width(with(density) { (geometry.pageLefts.last() + geometry.pageWidth - geometry.pageLefts.first()).toDp() }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = footerText,
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onFooterTap,
                    )
                    .padding(vertical = 6.dp),
            )
            Text(percent, style = style)
        }
    }
}

@Composable
private fun HeaderText(text: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier) {
    Text(
        text = text,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

private fun formatMinutes(minutes: Int, suffix: String): String = when {
    minutes <= 1 -> "Còn chưa tới 1 phút $suffix"
    minutes < 60 -> "Còn khoảng $minutes phút $suffix"
    else -> "Còn khoảng ${minutes / 60} giờ ${minutes % 60} phút $suffix"
}

@Composable
private fun ReaderTopBar(controller: ReaderController, colors: ReaderColors) {
    Surface(color = colors.chrome, shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 24.dp, vertical = 10.dp),
        ) {
            Text(
                controller.book.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val author = controller.book.author
            Text(
                if (author.isBlank()) controller.book.chapterTitle(controller.chapter) else author,
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ReaderBottomBar(controller: ReaderController, colors: ReaderColors, railOnRight: Boolean, reserveRail: Boolean) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val value = if (dragging) dragValue else controller.progress
    val preview = if (dragging) controller.book.positionAt((dragValue * controller.book.totalLength).toInt()) else null
    Surface(color = colors.chrome, shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    controller.book.chapterTitle(preview?.chapter ?: controller.chapter),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "${(value * 100).roundToInt()}% · vị trí ${(controller.book.globalOffset(preview ?: io.github.truongphamtp.docsach.core.ReadingPosition(controller.chapter, controller.anchor)) / ReaderController.LOCATION_CHARS) + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.secondaryText,
                )
            }
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
private fun SpeechBar(controller: ReaderController, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Filled.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                if (controller.speechPaused) "Đã tạm dừng" else "Đang đọc to",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            IconButton(onClick = { if (controller.speechPaused) controller.resumeSpeech() else controller.pauseSpeech() }) {
                Icon(
                    if (controller.speechPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (controller.speechPaused) "Đọc tiếp" else "Tạm dừng",
                )
            }
            IconButton(onClick = { controller.stopSpeech() }) {
                Icon(Icons.Filled.Stop, contentDescription = "Dừng đọc")
            }
        }
    }
}
