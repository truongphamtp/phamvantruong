package io.github.truongphamtp.docsach.core

/** Định dạng sách được hỗ trợ. */
enum class BookFormat(val label: String) {
    EPUB("EPUB"),
    PDF("PDF"),
    TXT("TXT"),
}

/**
 * Vị trí đọc.
 * - EPUB/TXT: [chapter] là chỉ số chương, [offset] là vị trí ký tự trong chương.
 * - PDF: [chapter] là chỉ số trang, [offset] luôn bằng 0.
 */
data class ReadingPosition(val chapter: Int = 0, val offset: Int = 0)

data class Bookmark(
    val id: String,
    val position: ReadingPosition,
    val label: String,
    val snippet: String,
    val createdAt: Long,
)

enum class HighlightColor(val label: String) {
    YELLOW("Vàng"),
    GREEN("Xanh lá"),
    BLUE("Xanh dương"),
    PINK("Hồng"),
}

/**
 * Đoạn được đánh dấu (tô màu) kèm ghi chú.
 * - EPUB/TXT: [chapter] là chương, [start]..[end] là vị trí ký tự trong chương.
 * - PDF: ghi chú cho cả trang, [chapter] là chỉ số trang, [start] = [end] = 0.
 */
data class Note(
    val id: String,
    val chapter: Int,
    val start: Int,
    val end: Int,
    /** Đoạn chữ được đánh dấu (để xem lại trong sổ ghi chú). */
    val quote: String,
    /** Nội dung ghi chú người đọc viết (có thể trống). */
    val text: String,
    val color: HighlightColor,
    /** Tên chương hoặc "Trang N". */
    val label: String,
    val createdAt: Long,
)

/** Một cuốn sách trong thư viện. */
data class BookRecord(
    val id: String,
    val title: String,
    val author: String,
    val format: BookFormat,
    val fileName: String,
    val coverFileName: String?,
    val sizeBytes: Long,
    val fingerprint: String,
    val addedAt: Long,
    val lastOpenedAt: Long = 0L,
    val progress: Float = 0f,
    val position: ReadingPosition = ReadingPosition(),
    val bookmarks: List<Bookmark> = emptyList(),
    val notes: List<Note> = emptyList(),
)

enum class ReaderTheme(val label: String) {
    WHITE("Trắng"),
    SEPIA("Giấy ngà"),
    GREEN("Xanh dịu"),
    BLACK("Đen"),
}

enum class ReaderFont(val label: String) {
    SERIF("Có chân"),
    SANS("Không chân"),
}

enum class PageLayout(val label: String) {
    AUTO("Tự động"),
    SINGLE("1 trang"),
    DOUBLE("2 trang"),
}

enum class ToolbarSide(val label: String) {
    RIGHT("Bên phải"),
    LEFT("Bên trái"),
}

/** Cài đặt hiển thị khi đọc (lưu chung cho mọi cuốn sách, giống Kindle). */
data class ReaderSettings(
    val theme: ReaderTheme = ReaderTheme.WHITE,
    val font: ReaderFont = ReaderFont.SERIF,
    val fontSize: Float = 19f,
    val lineSpacing: Float = LINE_SPACINGS[1],
    val margin: Int = 1,
    val justify: Boolean = true,
    val pageLayout: PageLayout = PageLayout.AUTO,
    val pageAnimation: Boolean = true,
    val volumeKeys: Boolean = true,
    val toolbarSide: ToolbarSide = ToolbarSide.RIGHT,
    val pinToolbar: Boolean = false,
    val keepScreenOn: Boolean = true,
    /** Độ sáng 0..1, hoặc số âm để theo hệ thống. */
    val brightness: Float = -1f,
    val speechRate: Float = 1f,
) {
    companion object {
        val FONT_SIZES = listOf(13f, 14f, 15f, 16f, 17f, 18f, 19f, 20f, 22f, 24f, 26f, 28f, 31f, 34f, 38f)
        val LINE_SPACINGS = listOf(1.3f, 1.5f, 1.8f)
        val MARGIN_LABELS = listOf("Hẹp", "Vừa", "Rộng")
        val SPACING_LABELS = listOf("Hẹp", "Vừa", "Rộng")
    }
}

enum class LibrarySort(val label: String) {
    RECENT("Đọc gần đây"),
    TITLE("Tên sách"),
    AUTHOR("Tác giả"),
    ADDED("Mới thêm"),
}

enum class LibraryView { GRID, LIST }

data class LibraryPrefs(
    val sort: LibrarySort = LibrarySort.RECENT,
    val view: LibraryView = LibraryView.GRID,
)

/**
 * Thông tin bản lề màn hình gập (toạ độ pixel trong cửa sổ).
 * Trên Xiaomi Mix Fold 2 khi mở phẳng, bản lề nằm dọc giữa màn hình.
 */
data class FoldInfo(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val vertical: Boolean,
    val halfOpened: Boolean,
    val separating: Boolean,
) {
    val centerX: Float get() = (left + right) / 2f
    val width: Int get() = right - left
}

/** Phím cứng có thể dùng để lật trang. */
enum class HardwareKey { VOLUME_UP, VOLUME_DOWN, PAGE_UP, PAGE_DOWN }

inline fun <reified T : Enum<T>> enumByName(name: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default
