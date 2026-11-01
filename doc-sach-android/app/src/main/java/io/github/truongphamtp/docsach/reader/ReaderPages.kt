package io.github.truongphamtp.docsach.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import io.github.truongphamtp.docsach.core.HighlightColor
import io.github.truongphamtp.docsach.core.Note
import io.github.truongphamtp.docsach.core.PageLayout
import io.github.truongphamtp.docsach.core.ReaderSettings
import io.github.truongphamtp.docsach.format.ChapterPages
import io.github.truongphamtp.docsach.format.ImagePiece
import io.github.truongphamtp.docsach.format.PageModel
import io.github.truongphamtp.docsach.format.TextPiece
import io.github.truongphamtp.docsach.ui.LocalHyper
import io.github.truongphamtp.docsach.ui.Motion
import io.github.truongphamtp.docsach.ui.ReaderColors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bố cục trang trong vùng đọc (đơn vị pixel).
 * Khi mở máy gập (màn hình rộng gần vuông) hiển thị 2 trang như sách thật, rãnh giữa trùng bản lề.
 */
data class ReaderGeometry(
    val pagesPerView: Int,
    val pageWidth: Int,
    val pageHeight: Int,
    val pageLefts: List<Float>,
    val pageTop: Float,
    val areaWidth: Int,
    val areaHeight: Int,
)

/** Có nên hiển thị hai trang cạnh nhau với kích thước vùng đọc (dp) này không. */
fun shouldUseSpread(layout: PageLayout, widthDp: Float, heightDp: Float): Boolean = when (layout) {
    PageLayout.SINGLE -> false
    PageLayout.DOUBLE -> widthDp >= 480f
    // Mix Fold 2 mở ra ≈ 760×860 dp (gần vuông) -> 2 trang; gập lại ≈ 390×880 dp -> 1 trang.
    PageLayout.AUTO -> widthDp >= 600f && widthDp >= heightDp * 0.72f
}

fun computeGeometry(
    width: Int,
    height: Int,
    density: Density,
    settings: ReaderSettings,
    foldCenterX: Float?,
    foldWidth: Float,
): ReaderGeometry {
    val d = density.density
    val widthDp = width / d
    val heightDp = height / d
    val spread = shouldUseSpread(settings.pageLayout, widthDp, heightDp)
    val margin = settings.margin.coerceIn(0, 2)
    val top = 44f * d
    val bottom = 40f * d
    val pageHeight = (height - top - bottom).roundToInt().coerceAtLeast(1)
    return if (!spread) {
        val side = listOf(16f, 26f, 42f)[margin] * d
        val maxWidth = 720f * d
        val pageWidth = minOf(width - 2 * side, maxWidth).coerceAtLeast(1f)
        val left = (width - pageWidth) / 2f
        ReaderGeometry(1, pageWidth.roundToInt(), pageHeight, listOf(left), top, width, height)
    } else {
        val outer = listOf(20f, 30f, 46f)[margin] * d
        val inner = listOf(18f, 26f, 36f)[margin] * d
        val center = foldCenterX?.takeIf { it > width * 0.35f && it < width * 0.65f } ?: (width / 2f)
        val gutterHalf = maxOf(inner, foldWidth / 2f + 12f * d)
        val leftWidth = center - gutterHalf - outer
        val rightWidth = width - outer - (center + gutterHalf)
        // Máy tính bảng nằm ngang rất rộng: giới hạn bề rộng mỗi trang để dòng chữ không quá dài.
        val pageWidth = minOf(leftWidth, rightWidth, 600f * d).coerceAtLeast(1f)
        ReaderGeometry(
            pagesPerView = 2,
            pageWidth = pageWidth.roundToInt(),
            pageHeight = pageHeight,
            pageLefts = listOf(center - gutterHalf - pageWidth, center + gutterHalf),
            pageTop = top,
            areaWidth = width,
            areaHeight = height,
        )
    }
}

/** Định danh "màn hình trang" đang hiển thị; đổi khi người đọc lật trang. */
data class PageTarget(val chapter: Int, val view: Int, val serial: Int, val direction: Int)

/**
 * Trạng thái lật trang: trang đang xem trượt theo ngón tay, trang kế bên trượt vào cùng lúc.
 * Thả tay thì lò xo đưa trang tới đích (hoặc bật về nếu kéo chưa đủ).
 *
 * Độ trượt là một biến trạng thái thường (không phải Animatable) để lúc lật xong, việc "đổi sang trang
 * mới" và "đưa độ trượt về 0" xảy ra cùng một lúc, không có khung hình nào lọt vào giữa.
 */
@Stable
class PageTurnState {
    /** Độ trượt hiện tại (px): âm = đang kéo sang trang sau, dương = sang trang trước. */
    var offset by mutableFloatStateOf(0f)
        private set

    /** 1 = đang thấy trang sau, -1 = đang thấy trang trước, 0 = chỉ trang hiện tại. */
    var direction by mutableIntStateOf(0)
        private set

    var width: Float = 1f
    private var pending = 0
    private val mutex = MutatorMutex()

    /** Phần trang đã trượt (0..1), để làm mờ dần bóng ở mép trang. */
    val progress: Float get() = (abs(offset) / width).coerceIn(0f, 1f)

    /** Hoàn tất lần lật đang dở: đổi trang và đặt lại độ trượt trong cùng một bước (không tạm dừng). */
    private fun flush(commit: (Int) -> Unit) {
        val dir = pending
        if (dir != 0) {
            pending = 0
            commit(dir)
            offset = 0f
            direction = 0
        }
    }

    /** Dừng hiệu ứng đang chạy (lần lật dở dang vẫn được giữ trong [pending]). */
    private suspend fun stop() {
        mutex.mutate { }
    }

    private suspend fun animateOffset(target: Float, spec: AnimationSpec<Float>, velocity: Float = 0f) {
        mutex.mutate {
            animate(offset, target, velocity, spec) { value, _ -> offset = value }
        }
    }

    suspend fun startDrag(commit: (Int) -> Unit) {
        stop()
        flush(commit)
    }

    fun drag(delta: Float, canNext: Boolean, canPrevious: Boolean) {
        var value = offset + delta
        // Ở đầu/cuối sách: kéo bị "nặng" lại như cao su.
        if (value < 0f && !canNext) value = (offset + delta * 0.3f).coerceAtLeast(-width * 0.12f)
        if (value > 0f && !canPrevious) value = (offset + delta * 0.3f).coerceAtMost(width * 0.12f)
        value = value.coerceIn(-width, width)
        direction = when {
            value < 0f && canNext -> 1
            value > 0f && canPrevious -> -1
            else -> 0
        }
        offset = value
    }

    suspend fun settle(velocity: Float, canNext: Boolean, canPrevious: Boolean, commit: (Int) -> Unit) {
        val value = offset
        val dir = when {
            value < 0f && canNext && (value < -width * 0.2f || velocity < -700f) -> 1
            value > 0f && canPrevious && (value > width * 0.2f || velocity > 700f) -> -1
            else -> 0
        }
        val v = velocity.coerceIn(-MAX_VELOCITY, MAX_VELOCITY)
        if (dir == 0) {
            animateOffset(0f, spring(dampingRatio = 0.82f, stiffness = 520f, visibilityThreshold = 0.5f), v)
            direction = 0
            return
        }
        pending = dir
        direction = dir
        animateOffset(-dir * width, spring(dampingRatio = 1f, stiffness = 380f, visibilityThreshold = 0.5f), v)
        // Lò xo có thể vượt quá đích một chút: chốt đúng vị trí rồi mới đổi trang.
        offset = -dir * width
        flush(commit)
    }

    /** Lật trang khi chạm hoặc bấm phím (có hiệu ứng trượt). [canGo] được hỏi sau khi hoàn tất lần lật trước. */
    suspend fun turn(dir: Int, canGo: () -> Boolean, commit: (Int) -> Unit) {
        stop()
        flush(commit)
        if (!canGo()) {
            animateOffset(-dir * width * 0.05f, tween(110, easing = Motion.Emphasized))
            animateOffset(0f, spring(dampingRatio = 0.5f, stiffness = 520f, visibilityThreshold = 0.5f))
            return
        }
        pending = dir
        direction = dir
        animateOffset(-dir * width, tween(340, easing = Motion.Emphasized))
        flush(commit)
    }

    private companion object {
        const val MAX_VELOCITY = 9000f
    }
}

/**
 * Vị trí của [target] so với trang đang xem: 0 = chính nó, 1 = trang sau, -1 = trang trước.
 * Được tính lúc VẼ (không phải lúc dựng giao diện), nên ngay khoảnh khắc lật xong — khi trang hiện
 * tại vừa đổi và độ trượt vừa về 0 — mọi trang vẫn được vẽ đúng chỗ, không nháy lại trang cũ.
 */
private fun relativeSlot(controller: ReaderController, target: PageTarget): Int {
    val chapter = controller.chapter
    val view = controller.viewIndex
    return when {
        target.chapter == chapter && target.view == view -> 0
        target.chapter > chapter || (target.chapter == chapter && target.view > view) -> 1
        else -> -1
    }
}

/** Bóng mềm ở mép trang đang trượt vào (như tờ giấy chồng lên), nhạt dần khi trang vào hết. */
internal fun DrawScope.drawTurnShadow(rel: Int, progress: Float) {
    if (rel == 0) return
    val alpha = 0.10f * (1f - progress)
    if (alpha <= 0.002f) return
    val shadowWidth = 18.dp.toPx()
    val edgeX = if (rel > 0) 0f else size.width - shadowWidth
    drawRect(
        brush = Brush.horizontalGradient(
            colors = if (rel > 0) {
                listOf(Color.Black.copy(alpha = alpha), Color.Transparent)
            } else {
                listOf(Color.Transparent, Color.Black.copy(alpha = alpha))
            },
            startX = edgeX,
            endX = edgeX + shadowWidth,
        ),
        topLeft = Offset(edgeX, 0f),
        size = Size(shadowWidth, size.height),
    )
}

@Composable
internal fun PagesContent(
    controller: ReaderController,
    geometry: ReaderGeometry,
    colors: ReaderColors,
    turn: PageTurnState,
    modifier: Modifier = Modifier,
) {
    val current = PageTarget(controller.chapter, controller.viewIndex, 0, 0)
    // Nhảy tới vị trí khác (mục lục, tìm kiếm, ghi chú): trang mới hiện dần lên.
    val jumpAlpha = remember { Animatable(1f) }
    LaunchedEffect(controller.navSerial) {
        if (controller.navSerial > 0 && controller.navDirection == 0) {
            jumpAlpha.snapTo(0.2f)
            jumpAlpha.animateTo(1f, tween(320, easing = Motion.Emphasized))
        }
    }
    BoxWithConstraints(modifier.clipToBounds().background(colors.background)) {
        val width = constraints.maxWidth.toFloat()
        SideEffect { turn.width = width }
        val dir = turn.direction
        val neighbor = if (dir != 0) controller.neighbor(dir) else null
        val shown = if (neighbor == null) listOf(current) else listOf(current, neighbor)
        for (target in shown) {
            // Mỗi trang giữ nguyên "bản vẽ" của nó khi đổi từ trang kế bên thành trang hiện tại.
            key(target.chapter, target.view) {
                PageSpread(
                    controller,
                    target,
                    geometry,
                    colors,
                    Modifier
                        .graphicsLayer {
                            val rel = relativeSlot(controller, target)
                            translationX = (turn.offset + rel * width).coerceIn(-width, width)
                            alpha = if (rel == 0) jumpAlpha.value else 1f
                        }
                        .drawWithContent {
                            drawContent()
                            drawTurnShadow(relativeSlot(controller, target), turn.progress)
                        },
                )
            }
        }
    }
}

@Composable
private fun PageSpread(
    controller: ReaderController,
    target: PageTarget,
    geometry: ReaderGeometry,
    colors: ReaderColors,
    modifier: Modifier = Modifier,
) {
    val chapterPages = controller.pagesFor(target.chapter)
    Box(modifier.fillMaxSize().background(colors.background)) {
        if (chapterPages == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp), color = colors.secondaryText)
            return@Box
        }
        val ppv = geometry.pagesPerView
        val first = target.view * ppv
        val pages = (first until first + ppv).mapNotNull { chapterPages.pages.getOrNull(it) }
        val images = controller.images
        val bitmaps = remember(target.chapter, pages) { mutableStateMapOf<String, ImageBitmap>() }
        if (images != null) {
            val needed = pages.flatMap { page -> page.pieces.filterIsInstance<ImagePiece>() }
            if (needed.isNotEmpty()) {
                LaunchedEffect(needed) {
                    for (piece in needed) {
                        if (bitmaps.containsKey(piece.block.path)) continue
                        images.loadImage(piece.block.path, piece.width.roundToInt(), piece.height.roundToInt())
                            ?.let { bitmaps[piece.block.path] = it }
                    }
                }
            }
        }
        val highlights = listOfNotNull(controller.searchHighlight, controller.speechHighlight)
            .filter { it.chapter == target.chapter }
        val notes = controller.notes.filter { it.chapter == target.chapter }
        val selection = controller.selection?.takeIf { it.chapter == target.chapter }
        val marker = LocalHyper.current.accent
        Canvas(Modifier.fillMaxSize()) {
            pages.forEachIndexed { i, page ->
                val left = geometry.pageLefts.getOrElse(i) { geometry.pageLefts.first() }
                drawPage(page, left, geometry.pageTop, colors, highlights, bitmaps, notes, selection, marker)
            }
        }
    }
}

private fun DrawScope.drawPage(
    page: PageModel,
    left: Float,
    top: Float,
    colors: ReaderColors,
    highlights: List<TextHighlight>,
    bitmaps: Map<String, ImageBitmap>,
    notes: List<Note>,
    selection: TextHighlight?,
    marker: Color,
) {
    for (piece in page.pieces) {
        when (piece) {
            is TextPiece<*> -> {
                val layout = (piece.text as LayoutText).layout
                val x = left + piece.x
                val y = top + piece.y
                val blockStart = piece.block.start
                val blockEnd = piece.block.start + piece.block.text.length
                fun local(start: Int, end: Int): IntRange? {
                    val s = maxOf(start, blockStart) - blockStart
                    val e = minOf(end, blockEnd) - blockStart
                    return if (e > s) s until e else null
                }
                clipRect(left = x - 12f, top = y, right = x + layout.size.width + 12f, bottom = y + piece.height) {
                    translate(left = x, top = y - piece.top) {
                        for (note in notes) {
                            val r = local(note.start, note.end) ?: continue
                            drawPath(layout.getPathForRange(r.first, r.last + 1), color = highlightFill(note.color, colors.dark))
                        }
                        for (h in highlights) {
                            val r = local(h.start, h.end) ?: continue
                            drawPath(layout.getPathForRange(r.first, r.last + 1), color = colors.selection)
                        }
                        if (selection != null) {
                            local(selection.start, selection.end)?.let { r ->
                                drawPath(layout.getPathForRange(r.first, r.last + 1), color = marker.copy(alpha = 0.28f))
                            }
                        }
                        drawText(layout, color = colors.text)
                        // Dấu chấm nhỏ ở cuối đoạn có ghi chú (giống biểu tượng ghi chú của Kindle).
                        for (note in notes) {
                            if (note.text.isBlank() || note.end - 1 < blockStart || note.end - 1 >= blockEnd) continue
                            val box = layout.getBoundingBox(note.end - 1 - blockStart)
                            drawCircle(marker, radius = 4.dp.toPx(), center = Offset(box.right + 5.dp.toPx(), box.top + 3.dp.toPx()))
                        }
                    }
                }
            }
            is ImagePiece -> {
                val bitmap = bitmaps[piece.block.path]
                val dstOffset = IntOffset((left + piece.x).roundToInt(), (top + piece.y).roundToInt())
                val dstSize = IntSize(piece.width.roundToInt().coerceAtLeast(1), piece.height.roundToInt().coerceAtLeast(1))
                if (bitmap != null) {
                    drawImage(
                        image = bitmap,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(bitmap.width, bitmap.height),
                        dstOffset = dstOffset,
                        dstSize = dstSize,
                        filterQuality = FilterQuality.Medium,
                    )
                } else {
                    drawRect(
                        color = colors.secondaryText.copy(alpha = 0.12f),
                        topLeft = Offset(dstOffset.x.toFloat(), dstOffset.y.toFloat()),
                        size = Size(dstSize.width.toFloat(), dstSize.height.toFloat()),
                    )
                }
            }
        }
    }
}


/** Màu tô của đoạn đánh dấu. */
fun highlightFill(color: HighlightColor, dark: Boolean): Color = when (color) {
    HighlightColor.YELLOW -> if (dark) Color(0x66C9A227) else Color(0x80FFD84D)
    HighlightColor.GREEN -> if (dark) Color(0x5C3F9E5A) else Color(0x6670D68A)
    HighlightColor.BLUE -> if (dark) Color(0x664C7FD1) else Color(0x5C7FB2FF)
    HighlightColor.PINK -> if (dark) Color(0x66C2507E) else Color(0x5CFF8DB9)
}

/** Vị trí ký tự (trong chương) và từ nằm dưới điểm chạm. */
data class TextHit(val offset: Int, val word: IntRange)

/**
 * Tìm chữ dưới điểm [point] (toạ độ trong vùng đọc). Với [nearest] = true, nếu điểm nằm giữa các đoạn
 * thì lấy dòng gần nhất (dùng khi đang kéo để chọn).
 */
fun hitText(pages: ChapterPages, geometry: ReaderGeometry, view: Int, point: Offset, nearest: Boolean = false): TextHit? {
    val first = view * geometry.pagesPerView
    var best: Pair<Float, TextHit>? = null
    for (i in 0 until geometry.pagesPerView) {
        val page = pages.pages.getOrNull(first + i) ?: continue
        val left = geometry.pageLefts.getOrElse(i) { geometry.pageLefts.first() }
        val insidePage = point.x >= left - 24f && point.x <= left + geometry.pageWidth + 24f
        if (!insidePage && !nearest) continue
        for (piece in page.pieces) {
            if (piece !is TextPiece<*>) continue
            val layout = (piece.text as LayoutText).layout
            val top = geometry.pageTop + piece.y
            val bottom = top + piece.height
            val dy = when {
                point.y < top -> top - point.y
                point.y > bottom -> point.y - bottom
                else -> 0f
            }
            val dx = when {
                point.x < left -> left - point.x
                point.x > left + geometry.pageWidth -> point.x - left - geometry.pageWidth
                else -> 0f
            }
            val distance = dy + dx
            if (distance > 0f && !nearest) continue
            if (best != null && best.first <= distance) continue
            val localY = (point.y.coerceIn(top, bottom - 1f) - top) + piece.top
            val localX = (point.x - left - piece.x).coerceIn(0f, layout.size.width.toFloat())
            val length = piece.block.text.length
            if (length == 0) continue
            val offset = layout.getOffsetForPosition(Offset(localX, localY)).coerceIn(0, length - 1)
            val wordRange = layout.getWordBoundary(offset)
            val wordStart = wordRange.start.coerceIn(0, length - 1)
            val wordEnd = wordRange.end.coerceIn(wordStart + 1, length)
            val hit = TextHit(
                offset = piece.block.start + offset,
                word = (piece.block.start + wordStart) until (piece.block.start + wordEnd),
            )
            best = distance to hit
            if (distance == 0f) return hit
        }
    }
    return best?.second
}
