package io.github.truongphamtp.docsach.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import kotlin.math.roundToInt

/**
 * Bảng màu theo phong cách "Liquid Glass" của HyperOS 4:
 * nền xanh nhạt có mảng màu loang, thẻ kính mờ viền sáng, điểm nhấn xanh dương.
 */
data class HyperPalette(
    val dark: Boolean,
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val blobs: List<Color>,
    val glass: Color,
    val glassStrong: Color,
    val glassBorderTop: Color,
    val glassBorderBottom: Color,
    val text: Color,
    val textSecondary: Color,
    val accent: Color,
    val accentLight: Color,
    val onAccent: Color,
    val controlTrack: Color,
    val segmentThumb: Color,
    val divider: Color,
    val shadow: Color,
    val danger: Color,
)

val HyperLight = HyperPalette(
    dark = false,
    backgroundTop = Color(0xFFE6F0FF),
    backgroundBottom = Color(0xFFF6F9FF),
    blobs = listOf(Color(0x9EA9CCFF), Color(0x80C9DEFF), Color(0x8FB3E3FF), Color(0x66D9C9FF)),
    glass = Color(0xADF2F5FB),
    glassStrong = Color(0xE6F6F8FD),
    glassBorderTop = Color(0xF2FFFFFF),
    glassBorderBottom = Color(0x2E14213D),
    text = Color(0xFF14213D),
    textSecondary = Color(0xFF66748C),
    accent = Color(0xFF2F7BF5),
    accentLight = Color(0xFF63A4FF),
    onAccent = Color.White,
    controlTrack = Color(0x1714213D),
    segmentThumb = Color(0xFFFFFFFF),
    divider = Color(0x1214213D),
    shadow = Color(0x332F5FA8),
    danger = Color(0xFFE5484D),
)

val HyperDark = HyperPalette(
    dark = true,
    backgroundTop = Color(0xFF0B1324),
    backgroundBottom = Color(0xFF04060C),
    blobs = listOf(Color(0x7A1E4A9A), Color(0x5C163A78), Color(0x66244C8C), Color(0x4D3A2E7A)),
    glass = Color(0x8C18213A),
    glassStrong = Color(0xE6141B2D),
    glassBorderTop = Color(0x40FFFFFF),
    glassBorderBottom = Color(0x0FFFFFFF),
    text = Color(0xFFEEF2FA),
    textSecondary = Color(0xFF8E9AB0),
    accent = Color(0xFF4C8DFF),
    accentLight = Color(0xFF7AAEFF),
    onAccent = Color.White,
    controlTrack = Color(0x1FFFFFFF),
    segmentThumb = Color(0x38FFFFFF),
    divider = Color(0x14FFFFFF),
    shadow = Color(0x66000000),
    danger = Color(0xFFFF6369),
)

val LocalHyper = staticCompositionLocalOf { HyperLight }

/** Vùng nội dung phía sau để làm mờ (kính mờ). Null = không làm mờ, chỉ dùng lớp màu trong suốt. */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/** Tắt hiệu ứng làm mờ (dùng khi chụp ảnh giao diện tự động). */
val LocalGlassBlur = staticCompositionLocalOf { true }

/** Màu nền phía sau lớp kính (giúp hiệu ứng mờ hiển thị đúng ở mép). */
val LocalGlassBackdrop = staticCompositionLocalOf { Color.Unspecified }

@Composable
fun ProvideHyper(palette: HyperPalette, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHyper provides palette, content = content)
}

/** Bề mặt kính mờ: làm mờ nội dung phía sau, phủ lớp màu trong suốt và viền sáng. */
@Composable
fun Modifier.glass(
    shape: Shape,
    strong: Boolean = false,
    elevation: Dp = 0.dp,
    fill: Color? = null,
): Modifier {
    val p = LocalHyper.current
    val haze = LocalHazeState.current
    val blur = LocalGlassBlur.current
    val backdrop = LocalGlassBackdrop.current
    val tint = fill ?: if (strong) p.glassStrong else p.glass
    var m = this
    if (elevation > 0.dp) m = m.shadow(elevation, shape, ambientColor = p.shadow, spotColor = p.shadow)
    m = m.clip(shape)
    m = if (haze != null && blur) {
        m.hazeChild(
            state = haze,
            style = HazeStyle(
                backgroundColor = if (backdrop.isSpecified()) backdrop else p.backgroundBottom,
                tint = HazeTint(tint),
                blurRadius = 26.dp,
                noiseFactor = 0.06f,
            ),
        )
    } else {
        // Không làm mờ được: tăng độ đục để chữ phía sau không rối mắt.
        m.background(tint.copy(alpha = (tint.alpha + 0.22f).coerceAtMost(0.97f)))
    }
    return m.border(
        width = 1.dp,
        brush = Brush.verticalGradient(listOf(p.glassBorderTop, p.glassBorderBottom)),
        shape = shape,
    )
}

private fun Color.isSpecified(): Boolean = this != Color.Unspecified

/** Nền "chất lỏng": dải màu xanh nhạt với các mảng loang mềm, giống hình nền HyperOS 4. */
@Composable
fun LiquidBackground(modifier: Modifier = Modifier) {
    val p = LocalHyper.current
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(p.backgroundTop, p.backgroundBottom)))
        val w = size.width
        val h = size.height
        val m = minOf(w, h)
        fun blob(cx: Float, cy: Float, radius: Float, color: Color) {
            drawCircle(
                brush = Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center = Offset(cx, cy), radius = radius),
                radius = radius,
                center = Offset(cx, cy),
            )
        }
        blob(w * 0.92f, h * 0.06f, m * 0.85f, p.blobs[0])
        blob(w * 0.02f, h * 0.42f, m * 0.7f, p.blobs[1])
        blob(w * 0.78f, h * 0.88f, m * 0.9f, p.blobs[2])
        blob(w * 0.3f, h * 0.98f, m * 0.5f, p.blobs[3])
        // Dải sáng chéo như mặt kính.
        drawRect(
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0f),
                    Color.White.copy(alpha = if (p.dark) 0.04f else 0.38f),
                    Color.White.copy(alpha = 0f),
                ),
                start = Offset(0f, h * 0.15f),
                end = Offset(w, h * 0.55f),
            ),
        )
    }
}

/** Công tắc kiểu HyperOS: rãnh bo tròn chuyển xanh khi bật, núm trắng nảy nhẹ và giãn ra khi nhấn. */
@Composable
fun HyperSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalHyper.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val progress by animateFloatAsState(if (checked) 1f else 0f, Motion.bouncy(), label = "công tắc")
    val stretch by animateDpAsState(if (pressed) 6.dp else 0.dp, Motion.snappy(), label = "giãn núm")
    val track by animateColorAsState(
        if (checked) p.accent else p.controlTrack.copy(alpha = if (p.dark) 0.22f else 0.14f),
        tween(220),
        label = "màu rãnh",
    )
    Box(
        modifier
            .size(width = 52.dp, height = 31.dp)
            .clip(CircleShape)
            .background(track)
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = null,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .offset(x = (21.dp - stretch) * progress)
                .size(width = 25.dp + stretch, height = 25.dp)
                .shadow(3.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

/** Nút chọn dạng viên thuốc có "con trượt" trắng di chuyển, kiểu HyperOS. */
@Composable
fun HyperSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalHyper.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(CircleShape)
            .background(p.controlTrack)
            .padding(3.dp),
    ) {
        val itemWidth = maxWidth / options.size.coerceAtLeast(1)
        val offset by animateDpAsState(
            itemWidth * selected.coerceIn(0, options.size - 1),
            spring(dampingRatio = 0.74f, stiffness = 520f, visibilityThreshold = Dp.VisibilityThreshold),
            label = "chọn",
        )
        Box(
            Modifier
                .offset(x = offset)
                .width(itemWidth)
                .fillMaxHeight()
                .shadow(if (p.dark) 0.dp else 2.dp, CircleShape)
                .background(p.segmentThumb, CircleShape),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { index, label ->
                val textColor by animateColorAsState(if (index == selected) p.text else p.textSecondary, tween(200), label = "chữ")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pressable(pressedScale = 0.95f) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = textColor,
                        fontSize = 13.sp,
                        fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Thanh trượt kiểu Trung tâm điều khiển HyperOS: rãnh dày bo tròn, phần đã chọn tô xanh.
 * [knob] = true thêm núm tròn trắng (dùng cho thanh tiến độ đọc).
 */
@Composable
fun HyperSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    height: Dp = 34.dp,
    knob: Boolean = false,
) {
    val p = LocalHyper.current
    val onChange by rememberUpdatedState(onValueChange)
    val onFinished by rememberUpdatedState(onValueChangeFinished)
    val start = valueRange.start
    val end = valueRange.endInclusive
    var dragging by remember { mutableStateOf(false) }
    val target = if (end > start) ((value - start) / (end - start)).coerceIn(0f, 1f) else 0f
    val animated by animateFloatAsState(target, Motion.snappy(), label = "thanh trượt")
    val fraction = if (dragging) target else animated
    val knobScale by animateFloatAsState(if (dragging) 1.15f else 1f, Motion.bouncy(), label = "núm")
    val trackHeight = if (knob) 8.dp else height
    Box(
        modifier
            .fillMaxWidth()
            .height(if (knob) 30.dp else height)
            .pointerInput(start, end, steps) {
                fun valueAt(x: Float): Float {
                    val f = (x / size.width).coerceIn(0f, 1f)
                    var v = start + f * (end - start)
                    if (steps > 0) {
                        val step = (end - start) / (steps + 1)
                        v = start + ((v - start) / step).roundToInt() * step
                    }
                    return v.coerceIn(start, end)
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    dragging = true
                    onChange(valueAt(down.position.x))
                    down.consume()
                    drag(down.id) { change ->
                        onChange(valueAt(change.position.x))
                        change.consume()
                    }
                    dragging = false
                    onFinished?.invoke()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(CircleShape)
                .background(p.controlTrack),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(maxWidth * fraction)
                    .background(Brush.horizontalGradient(listOf(p.accentLight, p.accent))),
            )
        }
        if (knob) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val knobSize = 24.dp
                Box(
                    Modifier
                        .offset(x = (maxWidth - knobSize) * fraction)
                        .size(knobSize)
                        .graphicsLayer {
                            scaleX = knobScale
                            scaleY = knobScale
                        }
                        .shadow(4.dp, CircleShape)
                        .background(Color.White, CircleShape),
                )
            }
        }
    }
}

/** Thanh tiến độ mảnh bo tròn. */
@Composable
fun ThinProgress(progress: Float, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    val p = LocalHyper.current
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), Motion.soft(), label = "tiến độ")
    BoxWithConstraints(modifier.fillMaxWidth().height(height).clip(CircleShape).background(p.controlTrack)) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(maxWidth * animated)
                .background(Brush.horizontalGradient(listOf(p.accentLight, p.accent))),
        )
    }
}

/** Nút hình viên thuốc: chính (xanh) hoặc phụ (xám trong). */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    danger: Boolean = false,
    icon: ImageVector? = null,
) {
    val p = LocalHyper.current
    val background: Brush = when {
        danger -> SolidColor(p.danger)
        primary -> Brush.horizontalGradient(listOf(p.accentLight, p.accent))
        else -> SolidColor(p.controlTrack)
    }
    val content = if (primary || danger) p.onAccent else p.text
    Row(
        modifier
            .pressable(pressedScale = 0.95f, onClick = onClick)
            .height(46.dp)
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Nút biểu tượng tròn trên thanh công cụ kính; khi chọn thì nền xanh, biểu tượng trắng. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    label: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 44.dp,
) {
    val p = LocalHyper.current
    val selection by animateFloatAsState(if (selected) 1f else 0f, Motion.snappy(), label = "chọn nút")
    Column(
        modifier
            .pressable(enabled = enabled, pressedScale = 0.88f, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(p.controlTrack)
                .graphicsLayer { alpha = 1f },
            contentAlignment = Alignment.Center,
        ) {
            // Nền xanh hiện dần và "nở" ra khi được chọn.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = selection
                        scaleX = 0.6f + 0.4f * selection
                        scaleY = 0.6f + 0.4f * selection
                    }
                    .background(Brush.verticalGradient(listOf(p.accentLight, p.accent)), CircleShape),
            )
            Icon(
                icon,
                contentDescription = label,
                tint = when {
                    !enabled -> p.textSecondary.copy(alpha = 0.5f)
                    else -> lerp(p.text, p.onAccent, selection)
                },
                modifier = Modifier.size(22.dp),
            )
        }
        if (label != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                color = p.text.copy(alpha = if (enabled) 0.85f else 0.4f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Nhóm các dòng cài đặt trong một thẻ kính bo tròn (như màn hình Cài đặt HyperOS). */
@Composable
fun GlassGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalHyper.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(if (p.dark) Color(0x1AFFFFFF) else Color(0x99FFFFFF))
            .border(1.dp, if (p.dark) Color(0x14FFFFFF) else Color(0xCCFFFFFF), RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        content = content,
    )
}

@Composable
fun GroupDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(LocalHyper.current.divider))
}

/** Hộp thoại kính nổi ở đáy màn hình (dễ chạm bằng ngón cái), nút hình viên thuốc. */
@Composable
fun HyperDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String? = null,
    onDismiss: (() -> Unit)? = null,
    message: String? = null,
    danger: Boolean = false,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val p = LocalHyper.current
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest,
                )
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(14.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val appear = remember { Animatable(0f) }
            LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 420f)) }
            Column(
                Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        val t = appear.value
                        alpha = t.coerceIn(0f, 1f)
                        translationY = (1f - t) * 90.dp.toPx()
                        val s = 0.94f + 0.06f * t
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .shadow(18.dp, RoundedCornerShape(32.dp), ambientColor = p.shadow, spotColor = p.shadow)
                    .clip(RoundedCornerShape(32.dp))
                    .background(if (p.dark) Color(0xF2161D2E) else Color(0xF7FFFFFF))
                    .border(1.dp, Brush.verticalGradient(listOf(p.glassBorderTop, p.glassBorderBottom)), RoundedCornerShape(32.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(horizontal = 22.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    title,
                    color = p.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (message != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(message, color = p.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
                }
                if (content != null) {
                    Spacer(Modifier.height(12.dp))
                    content()
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (dismissText != null) {
                        PillButton(dismissText, onClick = onDismiss ?: onDismissRequest, primary = false, modifier = Modifier.weight(1f))
                    }
                    PillButton(confirmText, onClick = onConfirm, danger = danger, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Thông báo ngắn dạng viên thuốc kính. */
@Composable
fun GlassToast(message: String, modifier: Modifier = Modifier) {
    val p = LocalHyper.current
    Box(
        modifier
            .widthIn(max = 520.dp)
            .glass(CircleShape, strong = true, elevation = 10.dp)
            .padding(horizontal = 20.dp, vertical = 13.dp),
    ) {
        Text(message, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

/** Hàng chữ + điều khiển bên phải (dùng trong GlassGroup). */
@Composable
fun SettingRow(label: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit) {
    val p = LocalHyper.current
    Row(
        modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = p.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Ô tìm kiếm dạng viên thuốc (kiểu thanh tìm kiếm HyperOS). */
@Composable
fun GlassSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Search,
    onAction: () -> Unit = {},
    leadingIcon: ImageVector? = Icons.Rounded.Search,
) {
    val p = LocalHyper.current
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .glass(CircleShape)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = p.textSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(placeholder, color = p.textSecondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = p.text, fontSize = 16.sp),
                cursorBrush = SolidColor(p.accent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                keyboardActions = KeyboardActions(onAny = { onAction() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
            )
        }
        if (value.isNotEmpty()) {
            Box(
                Modifier
                    .size(36.dp)
                    .pressable { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "Xoá", tint = p.textSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Nhãn nhỏ phía trên một nhóm cài đặt. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    val p = LocalHyper.current
    Row(modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, color = p.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Ô biểu tượng ứng dụng bo tròn có nền xanh chuyển màu (như icon HyperOS). */
@Composable
fun AccentTile(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val p = LocalHyper.current
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(Brush.linearGradient(listOf(p.accentLight, p.accent))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = p.onAccent, modifier = Modifier.size(size * 0.55f))
    }
}
