package io.github.truongphamtp.docsach.ui

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.truongphamtp.docsach.core.HardwareKey

/** Các thao tác phụ thuộc hệ điều hành mà màn hình đọc cần tới. */
interface ReaderPlatform {
    /** Ẩn thanh trạng thái/điều hướng để đọc toàn màn hình. */
    fun setImmersive(enabled: Boolean) {}

    /** Màu biểu tượng thanh hệ thống: true = biểu tượng tối (nền sáng). */
    fun setLightSystemBars(light: Boolean?) {}

    fun setKeepScreenOn(enabled: Boolean) {}

    /** Độ sáng 0..1, số âm = theo hệ thống. */
    fun setBrightness(value: Float) {}

    fun setKeyHandler(handler: ((HardwareKey) -> Boolean)?) {}

    fun openSpeechSettings() {}
}

object NoopReaderPlatform : ReaderPlatform

val LocalReaderPlatform = staticCompositionLocalOf<ReaderPlatform> { NoopReaderPlatform }
