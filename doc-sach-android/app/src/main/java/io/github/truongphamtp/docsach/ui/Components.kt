package io.github.truongphamtp.docsach.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.truongphamtp.docsach.core.BookFormat
import kotlin.math.abs

/** Nút "quay lại" của hệ thống; bản Android cung cấp BackHandler thật. */
val LocalBackHandler = staticCompositionLocalOf<@Composable (enabled: Boolean, onBack: () -> Unit) -> Unit> {
    { _, _ -> }
}

data class RailAction(
    val icon: ImageVector,
    val label: String,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
fun RailButton(action: RailAction, showLabel: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .width(76.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = action.enabled, onClick = action.onClick)
            .padding(vertical = if (showLabel) 6.dp else 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 34.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(if (action.selected) colors.secondaryContainer else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = action.label,
                tint = when {
                    !action.enabled -> colors.onSurface.copy(alpha = 0.38f)
                    action.selected -> colors.primary
                    else -> colors.onSurface
                },
            )
        }
        if (showLabel) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = action.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (action.enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Thanh công cụ dọc nổi bám mép phải (hoặc trái) màn hình — vùng ngón cái dễ chạm khi cầm máy.
 */
@Composable
fun FloatingRail(actions: List<RailAction>, onRight: Boolean, modifier: Modifier = Modifier) {
    val shape = if (onRight) {
        RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)
    } else {
        RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)
    }
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
        tonalElevation = 2.dp,
    ) {
        BoxWithConstraints {
            val showLabels = maxHeight >= (actions.size * 66 + 24).dp
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp, horizontal = 2.dp),
                verticalArrangement = Arrangement.spacedBy(if (showLabels) 2.dp else 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                actions.forEach { RailButton(it, showLabels) }
            }
        }
    }
}

/** Thanh công cụ dọc cố định chiếm hết chiều cao, các nút dồn xuống vùng ngón cái. */
@Composable
fun PinnedRail(
    actions: List<RailAction>,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Surface(modifier = modifier.fillMaxHeight(), color = containerColor) {
        BoxWithConstraints {
            val railHeight = maxHeight
            val showLabels = railHeight >= (actions.size * 66 + 200).dp
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(84.dp)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                header?.invoke(this)
                // Dồn nút xuống khoảng giữa-dưới: vùng ngón cái chạm tới khi cầm máy bằng một tay.
                Spacer(Modifier.height(if (railHeight > 560.dp) railHeight * 0.22f else 8.dp))
                actions.forEach { RailButton(it, showLabels) }
                Spacer(Modifier.height(16.dp))
                footer?.invoke(this)
            }
        }
    }
}

/** Bảng trượt ra từ cạnh phải (hoặc trái) — mục lục, tìm kiếm, cài đặt hiển thị. */
@Composable
fun SidePanel(
    visible: Boolean,
    onRight: Boolean,
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val panelWidth: Dp = minOf(420.dp, maxWidth * 0.92f)
            AnimatedVisibility(
                visible = visible,
                modifier = Modifier.align(if (onRight) Alignment.CenterEnd else Alignment.CenterStart),
                enter = slideInHorizontally { if (onRight) it else -it } + fadeIn(),
                exit = slideOutHorizontally { if (onRight) it else -it } + fadeOut(),
            ) {
                val shape = if (onRight) {
                    RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
                } else {
                    RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
                }
                Surface(
                    modifier = Modifier.fillMaxHeight().width(panelWidth),
                    shape = shape,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 8.dp,
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(
                                    WindowInsetsSides.Vertical + if (onRight) WindowInsetsSides.End else WindowInsetsSides.Start,
                                ),
                            ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Filled.Close, contentDescription = "Đóng")
                            }
                        }
                        content()
                    }
                }
            }
        }
    }
}

/** Trạng thái phóng to (dùng cho PDF). */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    val isZoomed: Boolean get() = scale > 1.01f

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    fun transform(zoom: Float, pan: Offset, centroid: Offset, size: IntSize) {
        val newScale = (scale * zoom).coerceIn(1f, 5f)
        val factor = newScale / scale
        offset = clamp(centroid - (centroid - offset) * factor + pan, newScale, size)
        scale = newScale
    }

    fun pan(delta: Offset, size: IntSize) {
        offset = clamp(offset + delta, scale, size)
    }

    fun zoomAt(point: Offset, size: IntSize) {
        if (isZoomed) reset() else transform(2.5f, Offset.Zero, point, size)
    }

    private fun clamp(value: Offset, s: Float, size: IntSize): Offset {
        val minX = size.width * (1f - s)
        val minY = size.height * (1f - s)
        return Offset(value.x.coerceIn(minX, 0f), value.y.coerceIn(minY, 0f))
    }
}

/**
 * Cử chỉ trên trang sách: chạm (theo vùng), vuốt ngang để lật trang, chụm hai ngón để phóng to.
 */
@Composable
fun Modifier.pageGestures(
    onTap: (Offset) -> Unit,
    onSwipe: (direction: Int) -> Unit,
    zoom: ZoomState? = null,
): Modifier {
    val tap by rememberUpdatedState(onTap)
    val swipe by rememberUpdatedState(onSwipe)
    return this.pointerInput(zoom) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val slop = viewConfiguration.touchSlop
            var total = Offset.Zero
            var dragging = false
            var transformed = false
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (zoom != null && pressed.size >= 2) {
                    transformed = true
                    zoom.transform(event.calculateZoom(), event.calculatePan(), event.calculateCentroid(useCurrent = true), size)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                    continue
                }
                val change = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                val delta = change.positionChange()
                if (zoom != null && zoom.isZoomed) {
                    if (delta != Offset.Zero) {
                        zoom.pan(delta, size)
                        transformed = true
                        change.consume()
                    }
                    continue
                }
                total += delta
                if (!dragging && total.getDistance() > slop) dragging = true
                if (dragging) change.consume()
            }
            if (transformed) return@awaitEachGesture
            if (dragging) {
                if (abs(total.x) > 40.dp.toPx() && abs(total.x) > abs(total.y) * 1.2f) {
                    swipe(if (total.x < 0) 1 else -1)
                }
            } else {
                tap(down.position)
            }
        }
    }
}

private val coverPalettes = listOf(
    listOf(Color(0xFF243B55), Color(0xFF141E30), Color(0xFFE9D8A6)),
    listOf(Color(0xFF7B2D26), Color(0xFF4A1A16), Color(0xFFF2D0A4)),
    listOf(Color(0xFF2F5D50), Color(0xFF1B3A32), Color(0xFFE0E7C8)),
    listOf(Color(0xFF5B4B8A), Color(0xFF332A55), Color(0xFFEAD7F7)),
    listOf(Color(0xFF8C5A1E), Color(0xFF55360F), Color(0xFFFCE7C2)),
    listOf(Color(0xFF1F4E79), Color(0xFF0F2A44), Color(0xFFCFE3F7)),
    listOf(Color(0xFF6B705C), Color(0xFF3F4235), Color(0xFFF0EAD2)),
)

/** Bìa tự tạo cho sách không có ảnh bìa (giống bìa mặc định của các ứng dụng đọc sách). */
@Composable
fun GeneratedCover(title: String, author: String, format: BookFormat, modifier: Modifier = Modifier) {
    val palette = coverPalettes[(title.hashCode() and 0x7fffffff) % coverPalettes.size]
    BoxWithConstraints(
        modifier = modifier.background(Brush.verticalGradient(listOf(palette[0], palette[1]))),
    ) {
        val unit = maxWidth.value / 100f
        Column(Modifier.fillMaxSize().padding((7 * unit).dp)) {
            Box(Modifier.fillMaxWidth().height(maxOf(1f, 1.4f * unit).dp).background(palette[2].copy(alpha = 0.8f)))
            Spacer(Modifier.height((7 * unit).dp))
            Text(
                text = title,
                color = palette[2],
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = (12.5f * unit).coerceIn(9f, 26f).sp,
                lineHeight = (15f * unit).coerceIn(11f, 31f).sp,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            if (author.isNotBlank()) {
                Text(
                    text = author,
                    color = palette[2].copy(alpha = 0.85f),
                    fontSize = (8.5f * unit).coerceIn(7f, 16f).sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height((3 * unit).dp))
            }
            Text(
                text = format.label,
                color = palette[2].copy(alpha = 0.6f),
                fontSize = (7f * unit).coerceIn(6f, 12f).sp,
                letterSpacing = (0.8f * unit).coerceIn(0.5f, 2f).sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
