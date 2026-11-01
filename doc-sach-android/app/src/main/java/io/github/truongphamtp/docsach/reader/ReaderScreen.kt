package io.github.truongphamtp.docsach.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import io.github.truongphamtp.docsach.ui.Motion
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.truongphamtp.docsach.core.FoldInfo
import io.github.truongphamtp.docsach.core.HardwareKey
import io.github.truongphamtp.docsach.core.HighlightColor
import io.github.truongphamtp.docsach.core.ReadingPosition
import io.github.truongphamtp.docsach.core.ReaderSettings
import io.github.truongphamtp.docsach.core.ToolbarSide
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import io.github.truongphamtp.docsach.ui.AccentTile
import io.github.truongphamtp.docsach.ui.FloatingRail
import io.github.truongphamtp.docsach.ui.GlassIconButton
import io.github.truongphamtp.docsach.ui.HyperDialog
import io.github.truongphamtp.docsach.ui.HyperSlider
import io.github.truongphamtp.docsach.ui.LocalHazeState
import io.github.truongphamtp.docsach.ui.LocalHyper
import io.github.truongphamtp.docsach.ui.glass
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
    val targetColors = remember(settings.theme) { readerColors(settings.theme) }
    // Đổi màu nền/chữ mượt khi chọn chủ đề khác.
    val colors = animateReaderColors(targetColors)
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
    val turn = remember { PageTurnState() }
    val turnScope = rememberCoroutineScope()
    val commitTurn: (Int) -> Unit = { dir ->
        controller.clearSelection()
        if (dir > 0) controller.nextPage() else controller.previousPage()
    }
    /** Lật trang có hiệu ứng trượt (hoặc lật ngay nếu đã tắt hiệu ứng). */
    val animatedTurn: (Int) -> Unit = { dir ->
        if (!latestSettings.pageAnimation) {
            commitTurn(dir)
        } else {
            turnScope.launch { turn.turn(dir, controller.neighbor(dir) != null, commitTurn) }
        }
    }
    DisposableEffect(controller) {
        platform.setKeyHandler { key ->
            when (key) {
                HardwareKey.PAGE_DOWN -> { animatedTurn(1); true }
                HardwareKey.PAGE_UP -> { animatedTurn(-1); true }
                HardwareKey.VOLUME_DOWN ->
                    if (latestSettings.volumeKeys && !controller.speaking) { animatedTurn(1); true } else false
                HardwareKey.VOLUME_UP ->
                    if (latestSettings.volumeKeys && !controller.speaking) { animatedTurn(-1); true } else false
            }
        }
        onDispose { platform.setKeyHandler(null) }
    }
    controller.speechRate = settings.speechRate

    LocalBackHandler.current(panel != null || menuVisible || controller.selection != null) {
        when {
            controller.selection != null -> controller.clearSelection()
            panel != null -> panel = null
            else -> menuVisible = false
        }
    }
    var editingNote by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    val actions = listOf(
        RailAction(Icons.AutoMirrored.Rounded.ArrowBackIos, "Thư viện", onClick = onBack),
        RailAction(Icons.AutoMirrored.Rounded.FormatListBulleted, "Mục lục", selected = panel == ReaderPanel.TOC) { openPanel(ReaderPanel.TOC) },
        RailAction(Icons.Rounded.Search, "Tìm", selected = panel == ReaderPanel.SEARCH) { openPanel(ReaderPanel.SEARCH) },
        RailAction(Icons.Rounded.TextFields, "Hiển thị", selected = panel == ReaderPanel.DISPLAY) { openPanel(ReaderPanel.DISPLAY) },
        RailAction(
            if (controller.isBookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
            "Dấu trang",
            selected = controller.isBookmarked,
        ) { controller.toggleBookmark() },
        RailAction(Icons.Rounded.Headphones, "Đọc to", selected = controller.speaking) {
            if (controller.speaking) controller.stopSpeech() else controller.startSpeech()
        },
    )

    val hazeState = remember { HazeState() }
    ReaderChromeTheme(colors) {
        CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(modifier.fillMaxSize().background(colors.background)) {
            Row(Modifier.fillMaxSize()) {
                if (settings.pinToolbar && !railOnRight) {
                    PinnedRail(actions)
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
                    turn = turn,
                    onTurn = animatedTurn,
                    onCommitTurn = commitTurn,
                    modifier = Modifier.weight(1f).fillMaxHeight().haze(hazeState),
                )
                if (settings.pinToolbar && railOnRight) {
                    PinnedRail(actions)
                }
            }

            AnimatedVisibility(
                visible = menuVisible,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = Motion.enterFromTop(),
                exit = Motion.exitToTop(),
            ) {
                ReaderTopBar(controller, colors)
            }
            AnimatedVisibility(
                visible = menuVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = Motion.enterFromBottom(),
                exit = Motion.exitToBottom(),
            ) {
                ReaderBottomBar(controller, colors, railOnRight, reserveRail = false)
            }
            if (!settings.pinToolbar) {
                Box(Modifier.fillMaxSize().padding(top = 96.dp, bottom = 128.dp)) {
                    AnimatedVisibility(
                        visible = menuVisible,
                        // Lệch xuống dưới một chút: vùng ngón cái khi cầm máy.
                        modifier = Modifier.align(BiasAlignment(if (railOnRight) 1f else -1f, 0.3f)),
                        enter = Motion.enterFromSide(fromRight = railOnRight),
                        exit = Motion.exitToSide(toRight = railOnRight),
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

            AnimatedVisibility(
                visible = controller.selection != null && !controller.selecting,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                enter = Motion.enterFromBottom(),
                exit = Motion.exitToBottom(),
            ) {
                val active = controller.activeNote
                SelectionBar(
                    activeNote = active,
                    onColor = { color ->
                        if (active != null) {
                            controller.updateNote(active.copy(color = color))
                            controller.clearSelection()
                        } else {
                            controller.saveSelection(color)
                        }
                    },
                    onNote = { editingNote = true },
                    onCopy = {
                        clipboard.setText(AnnotatedString(controller.selectedText))
                        controller.clearSelection()
                    },
                    onDelete = active?.let { note -> { controller.removeNote(note) } },
                    onClose = { controller.clearSelection() },
                )
            }

            if (editingNote && controller.selection != null) {
                val active = controller.activeNote
                NoteEditorDialog(
                    quote = active?.quote ?: controller.selectedText,
                    initialText = active?.text.orEmpty(),
                    editing = active != null,
                    onDismiss = { editingNote = false },
                    onSave = { text ->
                        editingNote = false
                        if (active != null) {
                            controller.updateNote(active.copy(text = text.trim()))
                            controller.clearSelection()
                        } else {
                            controller.saveSelection(active?.color ?: HighlightColor.YELLOW, text)
                        }
                    },
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
                HyperDialog(
                    onDismissRequest = { controller.speechError = null },
                    title = "Chưa đọc to được",
                    message = message,
                    confirmText = "Mở cài đặt",
                    onConfirm = {
                        controller.speechError = null
                        platform.openSpeechSettings()
                    },
                    dismissText = "Đóng",
                )
            }
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
    turn: PageTurnState,
    onTurn: (Int) -> Unit,
    onCommitTurn: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val resolver = LocalFontFamilyResolver.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val smooth = settings.pageAnimation
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
            turn = turn,
            modifier = Modifier
                .fillMaxSize()
                .pageGestures(
                    onTap = { pos ->
                        val pages = controller.currentPages
                        val note = if (pages != null && !menuVisible && controller.selection == null) {
                            hitText(pages, geometry, controller.viewIndex, pos)?.let { controller.noteAt(it.offset) }
                        } else {
                            null
                        }
                        when {
                            controller.selection != null -> controller.clearSelection()
                            menuVisible -> onMenuVisibleChange(false)
                            // Chạm vào đoạn đã đánh dấu: mở thanh thao tác để sửa/xoá ghi chú.
                            note != null -> controller.selectNote(note)
                            else -> {
                                val fx = pos.x / width
                                when {
                                    pos.y < geometry.pageTop -> onMenuVisibleChange(true)
                                    fx < 0.3f -> onTurn(-1)
                                    fx > 0.7f -> onTurn(1)
                                    else -> onMenuVisibleChange(true)
                                }
                            }
                        }
                        controller.clearSearchHighlight()
                    },
                    onSwipe = { dir -> onCommitTurn(dir) },
                    // Kéo trang theo ngón tay, thả ra thì trang trượt tiếp hoặc bật về.
                    onDragStart = if (smooth) {
                        { scope.launch { turn.startDrag(onCommitTurn) } }
                    } else {
                        null
                    },
                    onDrag = if (smooth) {
                        { dx ->
                            scope.launch {
                                turn.drag(dx, controller.neighbor(1) != null, controller.neighbor(-1) != null)
                            }
                        }
                    } else {
                        null
                    },
                    onDragEnd = if (smooth) {
                        { velocity ->
                            scope.launch {
                                turn.settle(velocity, controller.neighbor(1) != null, controller.neighbor(-1) != null, onCommitTurn)
                            }
                        }
                    } else {
                        null
                    },
                    onLongPress = { pos ->
                        val hit = controller.currentPages?.let { hitText(it, geometry, controller.viewIndex, pos) }
                        if (hit != null) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onMenuVisibleChange(false)
                            controller.startSelection(hit.word)
                            true
                        } else {
                            false
                        }
                    },
                    onLongPressDrag = { pos ->
                        controller.currentPages
                            ?.let { hitText(it, geometry, controller.viewIndex, pos, nearest = true) }
                            ?.let { controller.extendSelection(it.word) }
                    },
                    onLongPressEnd = { controller.finishSelection() },
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
    val p = LocalHyper.current
    Row(
        Modifier
            .widthIn(max = 760.dp)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .glass(RoundedCornerShape(26.dp), elevation = 10.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccentTile(Icons.AutoMirrored.Rounded.MenuBook, size = 38.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                controller.book.title,
                color = p.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val author = controller.book.author
            Text(
                if (author.isBlank()) controller.book.chapterTitle(controller.chapter) else author,
                color = p.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ReaderBottomBar(controller: ReaderController, colors: ReaderColors, railOnRight: Boolean, reserveRail: Boolean) {
    val p = LocalHyper.current
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val value = if (dragging) dragValue else controller.progress
    val preview = if (dragging) controller.book.positionAt((dragValue * controller.book.totalLength).toInt()) else null
    val location = controller.book.globalOffset(preview ?: ReadingPosition(controller.chapter, controller.anchor)) /
        ReaderController.LOCATION_CHARS + 1
    Column(
        Modifier
            .widthIn(max = 760.dp)
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
            Text(
                controller.book.chapterTitle(preview?.chapter ?: controller.chapter),
                color = p.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text("${(value * 100).roundToInt()}% · vị trí $location", color = p.textSecondary, fontSize = 12.sp)
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
private fun SpeechBar(controller: ReaderController, modifier: Modifier = Modifier) {
    val p = LocalHyper.current
    Row(
        modifier
            .glass(CircleShape, strong = true, elevation = 12.dp)
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccentTile(Icons.Rounded.Headphones, size = 34.dp)
        Text(
            if (controller.speechPaused) "Đã tạm dừng" else "Đang đọc to",
            color = p.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        GlassIconButton(
            if (controller.speechPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
            null,
            onClick = { if (controller.speechPaused) controller.resumeSpeech() else controller.pauseSpeech() },
            size = 38.dp,
        )
        GlassIconButton(Icons.Rounded.Stop, null, onClick = { controller.stopSpeech() }, size = 38.dp)
    }
}


/** Đổi màu trang mượt mà khi chuyển chủ đề đọc. */
@Composable
private fun animateReaderColors(target: ReaderColors): ReaderColors {
    val spec = tween<Color>(420, easing = Motion.Emphasized)
    val background by animateColorAsState(target.background, spec, label = "màu nền")
    val text by animateColorAsState(target.text, spec, label = "màu chữ")
    val secondary by animateColorAsState(target.secondaryText, spec, label = "màu chữ phụ")
    return target.copy(background = background, text = text, secondaryText = secondary)
}
