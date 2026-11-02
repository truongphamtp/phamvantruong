package io.github.truongphamtp.docsach

import io.github.truongphamtp.docsach.core.ReadingPosition
import io.github.truongphamtp.docsach.format.TxtParser
import io.github.truongphamtp.docsach.reader.ReaderController
import io.github.truongphamtp.docsach.reader.SpeechCallback
import io.github.truongphamtp.docsach.reader.SpeechEngine
import io.github.truongphamtp.docsach.reader.SpeechItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Đọc to: câu trước/câu sau, tạm dừng, dừng — dùng giọng đọc giả để kiểm tra thứ tự các câu. */
class ReadAloudControllerTest {
    private class FakeEngine : SpeechEngine {
        override var callback: SpeechCallback? = null
        val batches = ArrayList<Pair<List<SpeechItem>, Boolean>>()
        var stops = 0

        override fun speak(items: List<SpeechItem>, rate: Float, flush: Boolean) {
            batches += items to flush
        }

        override fun stop() {
            stops++
        }

        override fun release() = Unit

        /** Giả lập: giọng đọc bắt đầu đọc câu thứ [index] của lượt gần nhất. */
        fun start(index: Int) = batches.last().first[index].also { callback?.onStart(it.id) }
    }

    private fun book() = TxtParser.parse(
        "Chương 1\nCâu thứ nhất. Câu thứ hai! Câu thứ ba?\nĐoạn hai mở đầu. Đoạn hai kết thúc.\nChương 2\nChương hai câu một. Chương hai câu hai.\n",
        "Sách thử",
    )

    private fun controller(engine: FakeEngine, changes: MutableList<String>) =
        ReaderController(book(), ReadingPosition(), emptyList(), CoroutineScope(Dispatchers.Unconfined)).also { c ->
            c.attachSpeech(engine)
            c.onSpeechChanged = { changes += if (!c.speaking) "dừng" else if (c.speechPaused) "tạm dừng" else (c.speechSentence ?: "bắt đầu") }
        }

    @Test
    fun skipMovesBetweenSentencesAndPauseResumeStopNotify() {
        val engine = FakeEngine()
        val changes = ArrayList<String>()
        val c = controller(engine, changes)

        c.startSpeech()
        assertTrue(c.speaking)
        val first = engine.batches.single()
        assertTrue(first.second)
        assertTrue(first.first.size >= 5)
        engine.start(0)
        val firstText = first.first[0].text
        assertEquals(firstText, c.speechSentence)

        // Câu sau: đọc lại từ câu thứ hai (xếp lại hàng, bỏ câu đang đọc).
        c.skipSpeech(1)
        val second = engine.batches.last()
        assertTrue(second.second)
        assertEquals(first.first[1].text, second.first[0].text)
        engine.start(0)
        assertEquals(first.first[1].text, c.speechSentence)

        // Câu trước: quay về câu thứ nhất.
        c.skipSpeech(-1)
        assertEquals(firstText, engine.batches.last().first[0].text)

        c.pauseSpeech()
        assertTrue(c.speechPaused)
        assertTrue(engine.stops > 0)
        c.resumeSpeech()
        assertFalse(c.speechPaused)
        c.stopSpeech()
        assertFalse(c.speaking)
        assertEquals("dừng", changes.last())
        assertTrue(changes.contains("tạm dừng"))
        assertTrue(changes.contains(firstText))
    }

    @Test
    fun finishingAChapterContinuesWithTheNext() {
        val engine = FakeEngine()
        val c = controller(engine, ArrayList())
        c.startSpeech()
        val batch = engine.batches.single().first
        engine.callback!!.onDone(batch.last().id)
        val next = engine.batches.last()
        assertFalse(next.second)
        assertTrue(next.first.any { it.text.startsWith("Chương hai câu một") })
        // Câu trước ở câu đầu chương 2: quay về câu cuối chương 1.
        engine.start(0)
        c.skipSpeech(-1)
        assertEquals(batch.last().text, engine.batches.last().first.first().text)
        // Câu sau ở câu cuối của sách: dừng đọc.
        c.skipSpeech(1)
        engine.start(engine.batches.last().first.lastIndex)
        c.skipSpeech(1)
        assertFalse(c.speaking)
    }
}
