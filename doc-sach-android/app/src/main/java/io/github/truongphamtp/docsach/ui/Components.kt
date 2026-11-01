package io.github.truongphamtp.docsach.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.ui.input.pointer.util.VelocityTracker
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
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEvent
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

/**
 * Thanh công cụ dọc dạng viên kính nổi bám mép phải (hoặc trái) — vùng ngón cái dễ chạm khi cầm máy.
 */
@Composable
fun FloatingRail(actions: List<RailAction>, onRight: Boolean, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.padding(horizontal = 10.dp)) {
        val showLabels = maxHeight >= (actions.size * 76 + 28).dp
        Column(
            modifier = Modifier
                .glass(RoundedCornerShape(36.dp), elevation = 14.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 10.dp, horizontal = 3.dp),
            verticalArrangement = Arrangement.spacedBy(if (showLabels) 2.dp else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            actions.forEach {
                GlassIconButton(it.icon, if (showLabels) it.label else null, it.onClick, selected = it.selected, enabled = it.enabled)
            }
        }
    }
}

/** Thanh công cụ luôn hiện: viên kính nằm trong cột riêng, các nút dồn về vùng ngón cái. */
@Composable
fun PinnedRail(
    actions: List<RailAction>,
    modifier: Modifier = Modifier,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    BoxWithConstraints(
        modifier
            .fillMaxHeight()
            .width(96.dp)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))
            .padding(vertical = 12.dp),
    ) {
        val railHeight = maxHeight
        val showLabels = railHeight >= (actions.size * 76 + 220).dp
        Column(
            modifier = Modifier
                .align(BiasAlignment(0f, if (railHeight > 560.dp) 0.3f else 0f))
                .padding(horizontal = 10.dp)
                .glass(RoundedCornerShape(38.dp), elevation = 14.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp, horizontal = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (showLabels) 2.dp else 0.dp),
        ) {
            header?.invoke(this)
            actions.forEach {
                GlassIconButton(it.icon, if (showLabels) it.label else null, it.onClick, selected = it.selected, enabled = it.enabled)
            }
            footer?.invoke(this)
        }
    }
}

/** Tấm kính trượt ra từ cạnh phải (hoặc trái) — mục lục, tìm kiếm, cài đặt hiển thị. */
@Composable
fun SidePanel(
    visible: Boolean,
    onRight: Boolean,
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalHyper.current
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(tween(240)), exit = fadeOut(tween(200))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (p.dark) 0.35f else 0.18f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val panelWidth: Dp = minOf(440.dp, maxWidth * 0.94f)
            AnimatedVisibility(
                visible = visible,
                modifier = Modifier.align(if (onRight) Alignment.CenterEnd else Alignment.CenterStart),
                enter = Motion.enterFromSide(fromRight = onRight),
                exit = Motion.exitToSide(toRight = onRight),
            ) {
                Column(
                    Modifier
                        .fillMaxHeight()
                        .width(panelWidth)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Vertical + if (onRight) WindowInsetsSides.End else WindowInsetsSides.Start,
                            ),
                        )
                        .padding(10.dp)
                        .glass(RoundedCornerShape(34.dp), strong = true, elevation = 18.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, top = 14.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = title,
                            color = p.text,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        GlassIconButton(Icons.Rounded.Close, null, onDismiss, size = 38.dp)
                    }
                    content()
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
 * Cử chỉ trên trang sách: chạm (theo vùng), vuốt ngang để lật trang, chụm hai ngón để phóng to,
 * nhấn giữ rồi kéo để chọn chữ (đánh dấu, ghi chú).
 */
@Composable
fun Modifier.pageGestures(
    onTap: (Offset) -> Unit,
    onSwipe: (direction: Int) -> Unit,
    zoom: ZoomState? = null,
    onLongPress: ((Offset) -> Boolean)? = null,
    onLongPressDrag: ((Offset) -> Unit)? = null,
    onLongPressEnd: (() -> Unit)? = null,
    /** Kéo ngang theo ngón tay (lật trang mượt). Nếu có, [onSwipe] không được gọi. */
    onDragStart: (() -> Unit)? = null,
    onDrag: ((Float) -> Unit)? = null,
    onDragEnd: ((velocityX: Float) -> Unit)? = null,
): Modifier {
    val tap by rememberUpdatedState(onTap)
    val swipe by rememberUpdatedState(onSwipe)
    val dragStart by rememberUpdatedState(onDragStart)
    val dragMove by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val longPress by rememberUpdatedState(onLongPress)
    val longPressDrag by rememberUpdatedState(onLongPressDrag)
    val longPressEnd by rememberUpdatedState(onLongPressEnd)
    return this.pointerInput(zoom) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val slop = viewConfiguration.touchSlop
            var total = Offset.Zero
            var dragging = false
            var transformed = false
            var released = false
            var horizontal = false
            val velocity = VelocityTracker()
            velocity.addPosition(down.uptimeMillis, down.position)

            /** Xử lý một sự kiện chạm; trả về false khi mọi ngón tay đã nhấc. */
            fun handle(event: PointerEvent): Boolean {
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) return false
                if (zoom != null && pressed.size >= 2) {
                    transformed = true
                    zoom.transform(event.calculateZoom(), event.calculatePan(), event.calculateCentroid(useCurrent = true), size)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                    return true
                }
                val change = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                val delta = change.positionChange()
                if (zoom != null && zoom.isZoomed) {
                    if (delta != Offset.Zero) {
                        zoom.pan(delta, size)
                        transformed = true
                        change.consume()
                    }
                    return true
                }
                total += delta
                velocity.addPosition(change.uptimeMillis, change.position)
                if (!dragging && total.getDistance() > slop) {
                    dragging = true
                    horizontal = abs(total.x) > abs(total.y)
                    if (horizontal && dragMove != null) dragStart?.invoke()
                }
                if (dragging) {
                    if (horizontal && dragMove != null) dragMove?.invoke(delta.x)
                    change.consume()
                }
                return true
            }

            // Giai đoạn 1: chờ nhấc tay, kéo, hoặc đủ thời gian nhấn giữ.
            val timedOut = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    if (!handle(awaitPointerEvent())) {
                        released = true
                        break
                    }
                    if (dragging || transformed) break
                }
            } == null

            if (timedOut && !dragging && !transformed && longPress?.invoke(down.position) == true) {
                // Đang chọn chữ: kéo để mở rộng vùng chọn cho tới khi nhấc tay.
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) break
                    if (change.positionChanged()) longPressDrag?.invoke(change.position)
                    change.consume()
                }
                longPressEnd?.invoke()
                return@awaitEachGesture
            }

            if (!released) {
                while (handle(awaitPointerEvent())) Unit
            }
            if (transformed) return@awaitEachGesture
            if (dragging && horizontal && dragMove != null) {
                dragEnd?.invoke(velocity.calculateVelocity().x)
            } else if (dragging) {
                if (abs(total.x) > 40.dp.toPx() && abs(total.x) > abs(total.y) * 1.2f) {
                    swipe(if (total.x < 0) 1 else -1)
                }
            } else if (!timedOut) {
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
        if (maxWidth < 60.dp) {
            // Bìa rất nhỏ: chỉ hiện chữ cái đầu cho gọn.
            Text(
                text = title.trim().take(1).uppercase(),
                color = palette[2],
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = (maxWidth.value * 0.5f).sp,
                modifier = Modifier.align(Alignment.Center),
            )
            return@BoxWithConstraints
        }
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
