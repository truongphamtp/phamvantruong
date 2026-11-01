package io.github.truongphamtp.docsach.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import io.github.truongphamtp.docsach.core.ReaderFont
import io.github.truongphamtp.docsach.format.BlockKind
import io.github.truongphamtp.docsach.format.ImageBlock
import io.github.truongphamtp.docsach.format.MeasuredText
import io.github.truongphamtp.docsach.format.PageStyle
import io.github.truongphamtp.docsach.format.PieceSize
import io.github.truongphamtp.docsach.format.TextBlock

/** Nguồn ảnh minh hoạ trong sách (bản Android đọc từ file EPUB). */
interface BookImageProvider {
    fun imageSize(path: String): IntSize?
    suspend fun loadImage(path: String, maxWidth: Int, maxHeight: Int): ImageBitmap?
}

data class ReaderTypography(
    val fontFamily: FontFamily,
    val fontSize: Float,
    val lineSpacing: Float,
    val justify: Boolean,
)

fun fontFamilyFor(font: ReaderFont): FontFamily = when (font) {
    ReaderFont.SERIF -> FontFamily.Serif
    ReaderFont.SANS -> FontFamily.SansSerif
}

/** Kết quả đo chữ của Compose, dùng cho thuật toán dàn trang. */
class LayoutText(val layout: TextLayoutResult) : MeasuredText {
    override val lineCount: Int get() = layout.lineCount
    override fun lineTop(line: Int): Float = layout.getLineTop(line)
    override fun lineBottom(line: Int): Float = layout.getLineBottom(line)
    override fun lineStart(line: Int): Int = layout.getLineStart(line)
    override fun lineEnd(line: Int): Int = layout.getLineEnd(line, visibleEnd = false)
}

class ComposePageStyle(
    private val measurer: TextMeasurer,
    private val density: Density,
    private val typography: ReaderTypography,
    private val images: BookImageProvider?,
) : PageStyle<LayoutText> {
    private val size = typography.fontSize
    private val fontPx = with(density) { size.sp.toPx() }
    override val bodyLineHeight: Float = fontPx * typography.lineSpacing

    private val base = TextStyle(
        fontFamily = typography.fontFamily,
        fontSize = size.sp,
        lineHeight = (size * typography.lineSpacing).sp,
        textAlign = if (typography.justify) TextAlign.Justify else TextAlign.Start,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
        lineBreak = LineBreak.Paragraph,
    )

    private val styles = HashMap<BlockKind, TextStyle>()

    private fun heading(scale: Float, align: TextAlign) = base.copy(
        fontSize = (size * scale).sp,
        lineHeight = (size * scale * 1.3f).sp,
        fontWeight = FontWeight.Bold,
        textAlign = align,
    )

    fun styleFor(kind: BlockKind): TextStyle = styles.getOrPut(kind) {
        when (kind) {
            BlockKind.HEADING1 -> heading(1.45f, TextAlign.Center)
            BlockKind.HEADING2 -> heading(1.25f, TextAlign.Center)
            BlockKind.HEADING3 -> heading(1.1f, TextAlign.Start)
            BlockKind.CENTER -> base.copy(textAlign = TextAlign.Center)
            BlockKind.RIGHT -> base.copy(textAlign = TextAlign.End)
            BlockKind.PREFORMATTED -> base.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = (size * 0.85f).sp,
                lineHeight = (size * 0.85f * 1.35f).sp,
                textAlign = TextAlign.Start,
            )
            BlockKind.LIST_ITEM -> base.copy(textAlign = TextAlign.Start)
            BlockKind.QUOTE, BlockKind.PARAGRAPH -> base
        }
    }

    override fun measure(block: TextBlock, width: Int): LayoutText {
        var style = styleFor(block.kind)
        if (block.indent) style = style.copy(textIndent = TextIndent(firstLine = (size * 1.6f).sp))
        if (block.kind == BlockKind.LIST_ITEM) {
            // Thụt treo: các dòng sau thẳng hàng với chữ, không thẳng với dấu đầu dòng.
            val marker = LIST_MARKER.find(block.text)?.value
            if (marker != null) style = style.copy(textIndent = TextIndent(restLine = (size * markerWidthEm(marker)).sp))
        }
        val result = measurer.measure(
            text = block.annotated(),
            style = style,
            // Bề rộng cố định để căn giữa/căn phải đúng cả với dòng ngắn (tiêu đề).
            constraints = Constraints.fixedWidth(width.coerceAtLeast(1)),
            density = density,
            skipCache = true,
        )
        return LayoutText(result)
    }

    override fun inset(kind: BlockKind): Float = when (kind) {
        BlockKind.QUOTE -> fontPx * 1.4f
        BlockKind.LIST_ITEM -> fontPx * 0.6f
        else -> 0f
    }

    override fun spaceBefore(kind: BlockKind): Float = when (kind) {
        BlockKind.HEADING1 -> fontPx * 1.6f
        BlockKind.HEADING2 -> fontPx * 1.3f
        BlockKind.HEADING3 -> fontPx * 0.9f
        else -> 0f
    }

    override fun spaceAfter(kind: BlockKind): Float = when (kind) {
        BlockKind.HEADING1 -> fontPx * 0.9f
        BlockKind.HEADING2 -> fontPx * 0.7f
        BlockKind.HEADING3 -> fontPx * 0.45f
        BlockKind.LIST_ITEM -> fontPx * 0.2f
        else -> fontPx * 0.5f
    }

    override fun imageSize(block: ImageBlock, maxWidth: Float, maxHeight: Float): PieceSize {
        val natural = images?.imageSize(block.path)
        if (natural == null || natural.width <= 0 || natural.height <= 0) {
            return PieceSize(minOf(maxWidth, fontPx * 8f), minOf(maxHeight, fontPx * 5f))
        }
        val w = natural.width * density.density
        val h = natural.height * density.density
        val scale = minOf(1f, maxWidth / w, maxHeight / h)
        return PieceSize(w * scale, h * scale)
    }
}

private val LIST_MARKER = Regex("^(•|[0-9]{1,3}\\.) ")

/** Bề rộng ước lượng (theo em) của dấu đầu dòng "• " hoặc "12. ". */
private fun markerWidthEm(marker: String): Float =
    if (marker.startsWith("•")) 0.8f else 0.56f * (marker.length - 2) + 0.75f

internal fun TextBlock.annotated(): AnnotatedString {
    if (styles.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (s in styles) {
            val end = s.end.coerceAtMost(text.length)
            if (end <= s.start) continue
            addStyle(
                SpanStyle(
                    fontWeight = if (s.bold) FontWeight.Bold else null,
                    fontStyle = if (s.italic) FontStyle.Italic else null,
                ),
                s.start,
                end,
            )
        }
    }
}
