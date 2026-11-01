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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
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
import io.github.truongphamtp.docsach.core.PageLayout
import io.github.truongphamtp.docsach.core.ReaderSettings
import io.github.truongphamtp.docsach.format.ImagePiece
import io.github.truongphamtp.docsach.format.PageModel
import io.github.truongphamtp.docsach.format.TextPiece
import io.github.truongphamtp.docsach.ui.ReaderColors
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
        val pageWidth = minOf(leftWidth, rightWidth).coerceAtLeast(1f)
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

@Composable
internal fun PagesContent(
    controller: ReaderController,
    geometry: ReaderGeometry,
    colors: ReaderColors,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    val target = PageTarget(controller.chapter, controller.viewIndex, controller.navSerial, controller.navDirection)
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = { pageTransition(initialState, targetState, animate) },
        contentKey = { Triple(it.chapter, it.view, it.serial) },
        label = "lật trang",
    ) { state ->
        PageSpread(controller, state, geometry, colors)
    }
}

private fun pageTransition(from: PageTarget, to: PageTarget, animate: Boolean): ContentTransform {
    if (!animate || from.serial == to.serial) return EnterTransition.None togetherWith ExitTransition.None
    return when (to.direction) {
        1 -> slideInHorizontally(tween(240)) { it } togetherWith slideOutHorizontally(tween(240)) { -it / 3 } + fadeOut(tween(240))
        -1 -> slideInHorizontally(tween(240)) { -it } togetherWith slideOutHorizontally(tween(240)) { it / 3 } + fadeOut(tween(240))
        else -> fadeIn(tween(160)) togetherWith fadeOut(tween(120))
    }
}

@Composable
private fun PageSpread(controller: ReaderController, target: PageTarget, geometry: ReaderGeometry, colors: ReaderColors) {
    val chapterPages = controller.pagesFor(target.chapter)
    Box(Modifier.fillMaxSize().background(colors.background)) {
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
        Canvas(Modifier.fillMaxSize()) {
            pages.forEachIndexed { i, page ->
                val left = geometry.pageLefts.getOrElse(i) { geometry.pageLefts.first() }
                drawPage(page, left, geometry.pageTop, colors, highlights, bitmaps)
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
) {
    for (piece in page.pieces) {
        when (piece) {
            is TextPiece<*> -> {
                val layout = (piece.text as LayoutText).layout
                val x = left + piece.x
                val y = top + piece.y
                clipRect(left = x - 12f, top = y, right = x + layout.size.width + 12f, bottom = y + piece.height) {
                    translate(left = x, top = y - piece.top) {
                        for (h in highlights) {
                            val s = maxOf(h.start, piece.block.start) - piece.block.start
                            val e = minOf(h.end, piece.block.start + piece.block.text.length) - piece.block.start
                            if (e > s) drawPath(layout.getPathForRange(s, e), color = colors.selection)
                        }
                        drawText(layout, color = colors.text)
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
