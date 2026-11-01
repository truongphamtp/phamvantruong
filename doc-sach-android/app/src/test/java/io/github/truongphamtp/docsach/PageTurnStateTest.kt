package io.github.truongphamtp.docsach

import androidx.compose.runtime.BroadcastFrameClock
import io.github.truongphamtp.docsach.reader.PageTurnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Lật trang theo từng khung hình (đồng hồ giả, 60 khung/giây). Sau mỗi khung hình, "màn hình" được vẽ
 * từ độ trượt và trang hiện tại; kiểm tra rằng không bao giờ có khung hình hiện lại trang cũ
 * (hay khoảng trống) sau khi trang mới đã vào hết — đó là lỗi "nháy" sau khi lật trang.
 */
class PageTurnStateTest {
    private val width = 1000f
    private val clock = BroadcastFrameClock()
    private val scope = CoroutineScope(clock + Dispatchers.Unconfined + Job())
    private var time = 0L
    private var page = 10
    private val pageCount = 20

    private val turn = PageTurnState().also { it.width = width }
    private val commit: (Int) -> Unit = { dir -> page += dir }

    /** Trang đang nằm giữa màn hình theo cách vẽ của PagesContent: vị trí = độ trượt + (trang - trang hiện tại) × bề rộng. */
    private fun visiblePage(): Int {
        val candidates = listOf(page, page + turn.direction).distinct()
        return candidates.minByOrNull { abs(turn.offset + (it - page) * width) }!!
    }

    private fun frame() {
        time += 16_666_667L
        clock.sendFrame(time)
    }

    /** Chạy tới khi [job] xong, ghi lại trang hiện ra ở mỗi khung hình. */
    private fun runFrames(job: Job, maxFrames: Int = 400): List<Int> {
        val shown = ArrayList<Int>()
        var frames = 0
        while (job.isActive && frames < maxFrames) {
            frame()
            frames++
            shown += visiblePage()
            // Không bao giờ được thấy "khoảng trống": trang giữa màn hình phải lệch ít hơn nửa trang.
            val misplacement = abs(turn.offset + (visiblePage() - page) * width)
            assertTrue("trang lệch $misplacement px ở khung $frames", misplacement <= width / 2 + 1)
        }
        assertFalse("hiệu ứng không dừng", job.isActive)
        return shown
    }

    private fun assertNoFlashBack(shown: List<Int>, from: Int, to: Int) {
        val firstNew = shown.indexOf(to)
        assertTrue("không thấy trang mới $to", firstNew >= 0)
        assertTrue("trang cũ $from hiện lại sau khi đã sang trang $to: $shown", shown.drop(firstNew).none { it == from })
    }

    @Test
    fun tapTurnCommitsOnceWithoutFlashingTheOldPage() = runBlocking {
        val job = scope.launch { turn.turn(1, { page + 1 < pageCount }, commit) }
        val shown = runFrames(job)
        assertEquals(11, page)
        assertEquals(0f, turn.offset, 0.001f)
        assertEquals(0, turn.direction)
        assertNoFlashBack(shown, from = 10, to = 11)
        scope.cancel()
    }

    @Test
    fun rapidSecondTapFinishesTheFirstTurnInstantly() = runBlocking {
        val first = scope.launch { turn.turn(1, { page + 1 < pageCount }, commit) }
        repeat(4) { frame() }
        assertTrue(first.isActive)
        val second = scope.launch { turn.turn(1, { page + 1 < pageCount }, commit) }
        // Lần lật đầu được chốt ngay (trang 11), lần hai đang trượt sang trang 12.
        assertEquals(11, page)
        val shown = runFrames(second)
        assertEquals(12, page)
        assertNoFlashBack(shown, from = 11, to = 12)
        assertTrue(shown.none { it == 10 })
        scope.cancel()
    }

    @Test
    fun flingSettlesOnTheNextPageAndShortDragSpringsBack() = runBlocking {
        scope.launch { turn.startDrag(commit) }
        for (i in 1..12) turn.drag(-25f, canNext = true, canPrevious = true)
        assertEquals(1, turn.direction)
        val fling = scope.launch { turn.settle(-2500f, canNext = true, canPrevious = true, commit = commit) }
        val shown = runFrames(fling)
        assertEquals(11, page)
        assertNoFlashBack(shown, from = 10, to = 11)

        // Kéo một chút rồi thả chậm: trang bật về, không đổi trang.
        scope.launch { turn.startDrag(commit) }
        turn.drag(-60f, canNext = true, canPrevious = true)
        val back = scope.launch { turn.settle(0f, canNext = true, canPrevious = true, commit = commit) }
        runFrames(back)
        assertEquals(11, page)
        assertEquals(0f, turn.offset, 0.5f)
        assertEquals(0, turn.direction)
        scope.cancel()
    }

    @Test
    fun grabbingThePageMidTurnCompletesThatTurnFirst() = runBlocking {
        val tap = scope.launch { turn.turn(-1, { page > 0 }, commit) }
        repeat(5) { frame() }
        scope.launch { turn.startDrag(commit) }
        assertFalse(tap.isActive)
        assertEquals(9, page)
        assertEquals(0f, turn.offset, 0.001f)
        scope.cancel()
    }

    @Test
    fun lastPageBouncesWithoutTurning() = runBlocking {
        page = pageCount - 1
        val job = scope.launch { turn.turn(1, { page + 1 < pageCount }, commit) }
        val shown = runFrames(job)
        assertEquals(pageCount - 1, page)
        assertTrue(shown.all { it == pageCount - 1 })
        assertEquals(0f, turn.offset, 0.5f)
        scope.cancel()
    }
}
