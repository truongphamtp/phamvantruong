package io.github.truongphamtp.docsach.data

import android.content.SharedPreferences
import io.github.truongphamtp.docsach.core.LibraryPrefs
import io.github.truongphamtp.docsach.core.LibrarySort
import io.github.truongphamtp.docsach.core.LibraryView
import io.github.truongphamtp.docsach.core.ReaderSettings
import io.github.truongphamtp.docsach.core.enumByName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Cài đặt hiển thị và thư viện, lưu bằng SharedPreferences. */
class SettingsStore(private val prefs: SharedPreferences) {
    private val _reader = MutableStateFlow(loadReader())
    val reader: StateFlow<ReaderSettings> = _reader.asStateFlow()

    private val _library = MutableStateFlow(loadLibrary())
    val library: StateFlow<LibraryPrefs> = _library.asStateFlow()

    var guideAdded: Boolean
        get() = prefs.getBoolean(KEY_GUIDE, false)
        set(value) = prefs.edit().putBoolean(KEY_GUIDE, value).apply()

    fun updateReader(settings: ReaderSettings) {
        _reader.value = settings
        prefs.edit()
            .putString("theme", settings.theme.name)
            .putString("font", settings.font.name)
            .putFloat("fontSize", settings.fontSize)
            .putFloat("lineSpacing", settings.lineSpacing)
            .putInt("margin", settings.margin)
            .putBoolean("justify", settings.justify)
            .putString("pageLayout", settings.pageLayout.name)
            .putBoolean("pageAnimation", settings.pageAnimation)
            .putBoolean("volumeKeys", settings.volumeKeys)
            .putString("toolbarSide", settings.toolbarSide.name)
            .putBoolean("pinToolbar", settings.pinToolbar)
            .putBoolean("keepScreenOn", settings.keepScreenOn)
            .putFloat("brightness", settings.brightness)
            .putFloat("speechRate", settings.speechRate)
            .apply()
    }

    fun updateLibrary(value: LibraryPrefs) {
        _library.value = value
        prefs.edit()
            .putString("librarySort", value.sort.name)
            .putString("libraryView", value.view.name)
            .apply()
    }

    private fun loadReader(): ReaderSettings {
        val d = ReaderSettings()
        return ReaderSettings(
            theme = enumByName(prefs.getString("theme", null), d.theme),
            font = enumByName(prefs.getString("font", null), d.font),
            fontSize = prefs.getFloat("fontSize", d.fontSize),
            lineSpacing = prefs.getFloat("lineSpacing", d.lineSpacing),
            margin = prefs.getInt("margin", d.margin).coerceIn(0, 2),
            justify = prefs.getBoolean("justify", d.justify),
            pageLayout = enumByName(prefs.getString("pageLayout", null), d.pageLayout),
            pageAnimation = prefs.getBoolean("pageAnimation", d.pageAnimation),
            volumeKeys = prefs.getBoolean("volumeKeys", d.volumeKeys),
            toolbarSide = enumByName(prefs.getString("toolbarSide", null), d.toolbarSide),
            pinToolbar = prefs.getBoolean("pinToolbar", d.pinToolbar),
            keepScreenOn = prefs.getBoolean("keepScreenOn", d.keepScreenOn),
            brightness = prefs.getFloat("brightness", d.brightness),
            speechRate = prefs.getFloat("speechRate", d.speechRate),
        )
    }

    private fun loadLibrary(): LibraryPrefs {
        val d = LibraryPrefs()
        return LibraryPrefs(
            sort = enumByName<LibrarySort>(prefs.getString("librarySort", null), d.sort),
            view = enumByName<LibraryView>(prefs.getString("libraryView", null), d.view),
        )
    }

    private companion object {
        const val KEY_GUIDE = "guide_added"
    }
}
