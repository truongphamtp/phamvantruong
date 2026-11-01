package io.github.truongphamtp.docsach

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.truongphamtp.docsach.core.BookFormat
import io.github.truongphamtp.docsach.core.BookRecord
import io.github.truongphamtp.docsach.core.LibraryPrefs
import io.github.truongphamtp.docsach.core.ReaderSettings
import io.github.truongphamtp.docsach.core.ReaderTheme
import io.github.truongphamtp.docsach.core.ReadingPosition
import io.github.truongphamtp.docsach.format.BookContent
import io.github.truongphamtp.docsach.format.EpubArchive
import io.github.truongphamtp.docsach.format.EpubParser
import io.github.truongphamtp.docsach.format.TxtParser
import io.github.truongphamtp.docsach.library.LibraryScreen
import io.github.truongphamtp.docsach.reader.ReaderController
import io.github.truongphamtp.docsach.reader.ReaderPanel
import io.github.truongphamtp.docsach.reader.ReaderScreen
import io.github.truongphamtp.docsach.ui.AppTheme
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Chụp ảnh giao diện ở kích thước màn hình Xiaomi Mix Fold 2:
 * màn hình trong khi mở máy (1914×2160 px) và màn hình ngoài khi gập (1080×2520 px).
 * Chỉ chạy khi có -Pscreenshots=true (ảnh lưu ở app/build/screenshots).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class ScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun onlyWhenRequested() {
        assumeTrue(System.getProperty("docsach.screenshots") == "true")
    }

    private fun save(name: String) {
        compose.waitForIdle()
        // Vẽ cả cửa sổ ra ảnh (không dùng PixelCopy vì Robolectric không hỗ trợ ổn định).
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Bản thu nhỏ để xem nhanh trong nhật ký CI.
        val small = Bitmap.createScaledBitmap(bitmap, bitmap.width * 45 / 100, bitmap.height * 45 / 100, true)
        val smallDir = File("build/screenshots-small").apply { mkdirs() }
        File(smallDir, "$name.jpg").outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 72, it) }
    }

    private fun guide(): BookContent =
        EpubArchive(File("src/main/assets/huong-dan-su-dung.epub")).use { EpubParser.parse(it, "Hướng dẫn") }

    private fun story(): BookContent {
        val paragraph = listOf(
            "Buổi sáng hôm ấy, sương còn phủ kín mặt hồ khi cô giáo trẻ mở cuốn sách cũ ra đọc. Những trang giấy đã ngả màu, " +
                "nhưng từng dòng chữ vẫn rõ ràng như ngày đầu cô nhận nó từ tay người thầy năm xưa.",
            "Cô nhớ lại những buổi chiều ngồi bên cửa sổ lớp học, nghe tiếng ve kêu và tiếng lật trang sột soạt. Đọc sách khi ấy " +
                "không chỉ là học bài; đó là cách để đi thật xa mà không cần rời khỏi chỗ ngồi.",
            "Giờ đây, mọi cuốn sách đều nằm gọn trong chiếc điện thoại gập. Mở máy ra, hai trang sách hiện lên cạnh nhau như một " +
                "cuốn sách thật; gập lại, cô vẫn đọc tiếp được bằng một tay trên chuyến xe buýt về nhà.",
            "“Đọc chậm thôi,” thầy từng dặn, “vì có những câu văn cần được nghĩ lâu hơn thời gian để đọc nó.” Cô mỉm cười, " +
                "chạm nhẹ vào mép phải màn hình để sang trang.",
        )
        val text = buildString {
            for (chapter in 1..3) {
                append("Chương $chapter: ${listOf("Sương sớm", "Lớp học cũ", "Chuyến xe buýt")[chapter - 1]}\n")
                repeat(4) { paragraph.forEach { append(it).append('\n') } }
            }
        }
        return TxtParser.parse(text, "Mùa sương trên hồ")
    }

    @Composable
    private fun Reader(
        book: BookContent,
        position: ReadingPosition,
        settings: ReaderSettings = ReaderSettings(),
        menu: Boolean = false,
        panel: ReaderPanel? = null,
        onController: (ReaderController) -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        val controller = remember { ReaderController(book, position, emptyList(), scope).also(onController) }
        AppTheme {
            ReaderScreen(
                controller = controller,
                settings = settings,
                onSettingsChange = {},
                onBack = {},
                fold = null,
                initialMenuVisible = menu,
                initialPanel = panel,
            )
        }
    }

    private fun shootReader(
        name: String,
        book: BookContent,
        position: ReadingPosition,
        settings: ReaderSettings = ReaderSettings(),
        menu: Boolean = false,
        panel: ReaderPanel? = null,
    ) {
        var controller: ReaderController? = null
        compose.setContent { Reader(book, position, settings, menu, panel) { controller = it } }
        compose.waitUntil(20_000) { controller?.isReady == true }
        compose.mainClock.advanceTimeBy(1_000)
        save(name)
    }

    @Test
    @Config(qualifiers = "w766dp-h864dp-400dpi")
    fun readerUnfoldedTwoPages() = shootReader("1-mo-may-hai-trang", story(), ReadingPosition(0, 0))

    @Test
    @Config(qualifiers = "w766dp-h864dp-400dpi")
    fun readerUnfoldedMenu() = shootReader("2-mo-may-thanh-cong-cu", guide(), ReadingPosition(3, 0), menu = true)

    @Test
    @Config(qualifiers = "w766dp-h864dp-400dpi")
    fun readerUnfoldedDisplayPanel() =
        shootReader("3-mo-may-bang-hien-thi", story(), ReadingPosition(1, 0), ReaderSettings(theme = ReaderTheme.SEPIA), panel = ReaderPanel.DISPLAY)

    @Test
    @Config(qualifiers = "w393dp-h916dp-440dpi")
    fun readerFoldedSinglePage() = shootReader("4-gap-may-mot-trang", story(), ReadingPosition(0, 0))

    @Test
    @Config(qualifiers = "w393dp-h916dp-440dpi")
    fun readerFoldedMenuDark() =
        shootReader("5-gap-may-nen-den", guide(), ReadingPosition(1, 0), ReaderSettings(theme = ReaderTheme.BLACK), menu = true)

    @Test
    @Config(qualifiers = "w393dp-h916dp-440dpi")
    fun readerFoldedToc() = shootReader("6-gap-may-muc-luc", guide(), ReadingPosition(4, 0), panel = ReaderPanel.TOC)

    private val sampleBooks = listOf(
        BookRecord("1", "Hướng dẫn sử dụng Đọc Sách", "Đọc Sách", BookFormat.EPUB, "", null, 16_000, "a", addedAt = 5, lastOpenedAt = 50, progress = 0.37f),
        BookRecord("2", "Giáo án Ngữ văn 10 – Học kỳ I", "Tổ Ngữ văn", BookFormat.EPUB, "", null, 420_000, "b", addedAt = 4, lastOpenedAt = 40, progress = 0.12f),
        BookRecord("3", "Truyện Kiều", "Nguyễn Du", BookFormat.EPUB, "", null, 300_000, "c", addedAt = 3),
        BookRecord("4", "Đề cương ôn tập Toán 12", "", BookFormat.PDF, "", null, 2_400_000, "d", addedAt = 6, lastOpenedAt = 30, progress = 0.8f),
        BookRecord("5", "Mùa sương trên hồ", "Truyện ngắn", BookFormat.TXT, "", null, 90_000, "e", addedAt = 2),
        BookRecord("6", "Lịch sử Việt Nam qua các thời kỳ", "Nhiều tác giả", BookFormat.EPUB, "", null, 1_200_000, "f", addedAt = 1),
        BookRecord("7", "Kỹ năng đọc hiểu", "", BookFormat.PDF, "", null, 800_000, "g", addedAt = 7),
    )

    private fun shootLibrary(name: String) {
        compose.setContent {
            AppTheme {
                LibraryScreen(
                    books = sampleBooks,
                    prefs = LibraryPrefs(),
                    onPrefsChange = {},
                    coverLoader = null,
                    busy = false,
                    message = null,
                    onMessageShown = {},
                    onOpen = {},
                    onAddBooks = {},
                    onDelete = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(500)
        save(name)
    }

    @Test
    @Config(qualifiers = "w766dp-h864dp-400dpi")
    fun libraryUnfolded() = shootLibrary("7-thu-vien-mo-may")

    @Test
    @Config(qualifiers = "w393dp-h916dp-440dpi")
    fun libraryFolded() = shootLibrary("8-thu-vien-gap-may")
}
