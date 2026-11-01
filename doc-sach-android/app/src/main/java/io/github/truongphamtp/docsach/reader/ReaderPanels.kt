package io.github.truongphamtp.docsach.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.truongphamtp.docsach.core.PageLayout
import io.github.truongphamtp.docsach.core.ReaderFont
import io.github.truongphamtp.docsach.core.ReaderSettings
import io.github.truongphamtp.docsach.core.ReaderTheme
import io.github.truongphamtp.docsach.core.ReadingPosition
import io.github.truongphamtp.docsach.core.ToolbarSide
import io.github.truongphamtp.docsach.format.SearchHit
import io.github.truongphamtp.docsach.library.NoteCard
import io.github.truongphamtp.docsach.ui.GlassGroup
import io.github.truongphamtp.docsach.ui.GlassIconButton
import io.github.truongphamtp.docsach.ui.GlassSearchField
import io.github.truongphamtp.docsach.ui.GroupDivider
import io.github.truongphamtp.docsach.ui.HyperSegmented
import io.github.truongphamtp.docsach.ui.HyperSlider
import io.github.truongphamtp.docsach.ui.HyperSwitch
import io.github.truongphamtp.docsach.ui.LocalHyper
import io.github.truongphamtp.docsach.ui.SectionLabel
import io.github.truongphamtp.docsach.ui.SettingRow
import io.github.truongphamtp.docsach.ui.pressable
import io.github.truongphamtp.docsach.ui.readerColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------- Mục lục & dấu trang

@Composable
internal fun TocPanel(controller: ReaderController, onNavigate: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        HyperSegmented(
            options = listOf("Mục lục", "Dấu trang (${controller.bookmarks.size})", "Ghi chú (${controller.notes.size})"),
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        when (tab) {
            0 -> TocList(controller, onNavigate)
            1 -> BookmarkList(controller, onNavigate)
            else -> NoteList(controller, onNavigate)
        }
    }
}

@Composable
private fun ChipButton(text: String, onClick: () -> Unit) {
    val p = LocalHyper.current
    Text(
        text,
        color = p.accent,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .pressable(pressedScale = 0.92f, onClick = onClick)
            .clip(CircleShape)
            .background(p.accent.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun TocList(controller: ReaderController, onNavigate: () -> Unit) {
    val p = LocalHyper.current
    val book = controller.book
    val current = remember(controller.chapter, controller.anchor) { controller.currentTocIndex() }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChipButton("Đầu sách") {
            controller.goTo(ReadingPosition(0, 0))
            onNavigate()
        }
        book.startPosition?.let { start ->
            ChipButton("Bắt đầu đọc") {
                controller.goTo(start)
                onNavigate()
            }
        }
    }
    LazyColumn(state = state, contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 20.dp)) {
        itemsIndexed(book.toc) { index, entry ->
            val selected = index == current
            val percent = (book.progress(ReadingPosition(entry.chapter, entry.offset)) * 100).roundToInt()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressable(pressedScale = 0.98f) {
                        controller.goTo(ReadingPosition(entry.chapter, entry.offset))
                        onNavigate()
                    }
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (selected) p.accent.copy(alpha = 0.13f) else Color.Transparent)
                    .padding(start = (14 + 16 * entry.depth.coerceAtMost(4)).dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    entry.title,
                    color = if (selected) p.accent else p.text,
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("$percent%", color = p.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp))
            }
        }
        if (book.toc.isEmpty()) {
            item { Text("Sách này không có mục lục.", modifier = Modifier.padding(20.dp), color = p.textSecondary) }
        }
    }
}

@Composable
private fun BookmarkList(controller: ReaderController, onNavigate: () -> Unit) {
    val p = LocalHyper.current
    val marks = controller.bookmarks
    if (marks.isEmpty()) {
        Text(
            "Chưa có dấu trang.\nBấm nút “Dấu trang” trên thanh công cụ để đánh dấu trang đang đọc.",
            modifier = Modifier.padding(20.dp),
            color = p.textSecondary,
            fontSize = 14.sp,
        )
        return
    }
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy", Locale.forLanguageTag("vi-VN")) }
    LazyColumn(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(marks, key = { it.id }) { mark ->
            GlassGroup(Modifier.pressable(pressedScale = 0.98f) {
                controller.goTo(mark.position)
                onNavigate()
            }) {
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val percent = (controller.book.progress(mark.position) * 100).roundToInt()
                        Text(
                            "${mark.label} · $percent% · ${dateFormat.format(Date(mark.createdAt))}",
                            color = p.accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(mark.snippet, color = p.text, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    GlassIconButton(Icons.Rounded.DeleteOutline, null, onClick = { controller.removeBookmark(mark) }, size = 36.dp)
                }
            }
        }
    }
}

@Composable
private fun NoteList(controller: ReaderController, onNavigate: () -> Unit) {
    val p = LocalHyper.current
    val notes = controller.notes
    if (notes.isEmpty()) {
        Text(
            "Chưa có ghi chú.\nNhấn giữ vào một chữ trên trang, kéo để chọn đoạn, rồi chọn màu đánh dấu hoặc nút ghi chú.",
            modifier = Modifier.padding(20.dp),
            color = p.textSecondary,
            fontSize = 14.sp,
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(notes, key = { it.id }) { note ->
            NoteCard(
                note = note,
                onClick = {
                    controller.goToNote(note)
                    onNavigate()
                },
                onDelete = { controller.removeNote(note) },
            )
        }
    }
}

// ---------------------------------------------------------------- Tìm kiếm

@Composable
internal fun SearchPanel(controller: ReaderController, onNavigate: () -> Unit) {
    val p = LocalHyper.current
    var query by rememberSaveable { mutableStateOf(controller.searchQuery) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    GlassSearchField(
        value = query,
        onValueChange = {
            query = it
            controller.search(it)
        },
        placeholder = "Nhập từ cần tìm…",
        focusRequester = focus,
        onAction = { controller.search(query) },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
    val results = controller.searchResults
    val status = when {
        query.isBlank() -> "Mẹo: gõ không dấu (ví dụ “tieng viet”) để tìm cả chữ có dấu."
        controller.searching -> "Đang tìm…"
        results.isEmpty() -> "Không tìm thấy “$query”."
        results.size >= 300 -> "Hơn 300 kết quả — hãy gõ cụ thể hơn."
        else -> "${results.size} kết quả"
    }
    if (controller.searching) {
        LinearProgressIndicator(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).clip(CircleShape),
            color = p.accent,
            trackColor = p.controlTrack,
        )
    }
    Text(status, color = p.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
    LazyColumn(contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 20.dp)) {
        items(results) { hit -> SearchRow(controller, hit, onNavigate) }
    }
}

@Composable
private fun SearchRow(controller: ReaderController, hit: SearchHit, onNavigate: () -> Unit) {
    val p = LocalHyper.current
    val text: AnnotatedString = remember(hit, p.accent) {
        buildAnnotatedString {
            append(hit.snippet.substring(0, hit.matchStart))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = p.accent, background = p.accent.copy(alpha = 0.12f))) {
                append(hit.snippet.substring(hit.matchStart, hit.matchEnd))
            }
            append(hit.snippet.substring(hit.matchEnd))
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.98f) {
                controller.openSearchHit(hit)
                onNavigate()
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            controller.book.chapterTitle(hit.chapter),
            color = p.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text, color = p.text, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- Hiển thị (Aa)

@Composable
internal fun DisplayPanel(settings: ReaderSettings, onChange: (ReaderSettings) -> Unit, textOptions: Boolean) {
    val p = LocalHyper.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionLabel("Màu nền")
        ThemePicker(settings.theme) { onChange(settings.copy(theme = it)) }
        if (textOptions) {
            Spacer(Modifier.height(6.dp))
            SectionLabel("Cỡ chữ", trailing = "${settings.fontSize.roundToInt()}")
            FontSizePicker(settings.fontSize) { onChange(settings.copy(fontSize = it)) }
            Spacer(Modifier.height(6.dp))
            SectionLabel("Phông chữ")
            HyperSegmented(ReaderFont.entries.map { it.label }, settings.font.ordinal, onSelect = {
                onChange(settings.copy(font = ReaderFont.entries[it]))
            })
            SectionLabel("Giãn dòng")
            val spacing = ReaderSettings.LINE_SPACINGS.indexOfFirst { abs(it - settings.lineSpacing) < 0.01f }.coerceAtLeast(0)
            HyperSegmented(ReaderSettings.SPACING_LABELS, spacing, onSelect = {
                onChange(settings.copy(lineSpacing = ReaderSettings.LINE_SPACINGS[it]))
            })
            SectionLabel("Lề trang")
            HyperSegmented(ReaderSettings.MARGIN_LABELS, settings.margin, onSelect = { onChange(settings.copy(margin = it)) })
            SectionLabel("Căn chữ")
            HyperSegmented(listOf("Đều hai bên", "Lề trái"), if (settings.justify) 0 else 1, onSelect = {
                onChange(settings.copy(justify = it == 0))
            })
        }
        Spacer(Modifier.height(6.dp))
        SectionLabel("Bố cục trang")
        HyperSegmented(PageLayout.entries.map { it.label }, settings.pageLayout.ordinal, onSelect = {
            onChange(settings.copy(pageLayout = PageLayout.entries[it]))
        })
        Text(
            "Tự động: mở máy gập hiện 2 trang như sách, gập lại hiện 1 trang.",
            color = p.textSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Spacer(Modifier.height(6.dp))
        SectionLabel("Thanh công cụ")
        HyperSegmented(ToolbarSide.entries.map { it.label }, settings.toolbarSide.ordinal, onSelect = {
            onChange(settings.copy(toolbarSide = ToolbarSide.entries[it]))
        })
        GlassGroup {
            SettingRow("Luôn hiện khi đọc") {
                HyperSwitch(settings.pinToolbar, { onChange(settings.copy(pinToolbar = it)) })
            }
        }
        Spacer(Modifier.height(6.dp))
        SectionLabel("Độ sáng")
        GlassGroup {
            SettingRow("Theo hệ thống") {
                HyperSwitch(settings.brightness < 0f, { onChange(settings.copy(brightness = if (it) -1f else 0.5f)) })
            }
            if (settings.brightness >= 0f) {
                GroupDivider()
                Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.LightMode, contentDescription = null, tint = p.textSecondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    HyperSlider(
                        value = settings.brightness,
                        onValueChange = { onChange(settings.copy(brightness = it.coerceIn(0.02f, 1f))) },
                        valueRange = 0.02f..1f,
                        height = 30.dp,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        SectionLabel("Lật trang")
        GlassGroup {
            SettingRow("Hiệu ứng trượt") {
                HyperSwitch(settings.pageAnimation, { onChange(settings.copy(pageAnimation = it)) })
            }
            GroupDivider()
            SettingRow("Lật bằng phím âm lượng") {
                HyperSwitch(settings.volumeKeys, { onChange(settings.copy(volumeKeys = it)) })
            }
            GroupDivider()
            SettingRow("Giữ màn hình luôn sáng") {
                HyperSwitch(settings.keepScreenOn, { onChange(settings.copy(keepScreenOn = it)) })
            }
        }
        if (textOptions) {
            Spacer(Modifier.height(6.dp))
            SectionLabel("Tốc độ đọc to", trailing = "${"%.1f".format(settings.speechRate)}×")
            HyperSlider(
                value = settings.speechRate,
                onValueChange = { onChange(settings.copy(speechRate = (it * 10).roundToInt() / 10f)) },
                valueRange = 0.5f..2f,
                steps = 14,
                height = 30.dp,
            )
        }
    }
}

@Composable
private fun ThemePicker(selected: ReaderTheme, onSelect: (ReaderTheme) -> Unit) {
    val p = LocalHyper.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        ReaderTheme.entries.forEach { theme ->
            val c = readerColors(theme)
            val isSelected = theme == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .pressable(pressedScale = 0.9f) { onSelect(theme) }
                    .padding(6.dp),
            ) {
                Box(
                    Modifier
                        .size(56.dp)
                        .border(if (isSelected) 2.5.dp else 0.dp, if (isSelected) p.accent else Color.Transparent, CircleShape)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(c.background)
                        .border(1.dp, p.divider, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = c.text, modifier = Modifier.size(22.dp))
                    } else {
                        Text("Aa", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    theme.label,
                    color = if (isSelected) p.accent else p.text,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun FontSizePicker(size: Float, onChange: (Float) -> Unit) {
    val p = LocalHyper.current
    val sizes = ReaderSettings.FONT_SIZES
    val index = sizes.indexOfFirst { it >= size - 0.01f }.let { if (it < 0) sizes.size - 1 else it }
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlassStepButton("A", 13) { onChange(sizes[(index - 1).coerceAtLeast(0)]) }
        HyperSlider(
            value = index.toFloat(),
            onValueChange = { onChange(sizes[it.roundToInt().coerceIn(0, sizes.size - 1)]) },
            valueRange = 0f..(sizes.size - 1).toFloat(),
            steps = sizes.size - 2,
            height = 34.dp,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        GlassStepButton("A", 21) { onChange(sizes[(index + 1).coerceAtMost(sizes.size - 1)]) }
    }
}

@Composable
private fun GlassStepButton(label: String, fontSize: Int, onClick: () -> Unit) {
    val p = LocalHyper.current
    Box(
        Modifier
            .pressable(pressedScale = 0.88f, onClick = onClick)
            .size(42.dp)
            .clip(CircleShape)
            .background(p.controlTrack),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = p.text, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold)
    }
}
