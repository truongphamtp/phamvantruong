@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.truongphamtp.docsach.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
        Segmented(
            options = listOf("Mục lục", "Dấu trang (${controller.bookmarks.size})"),
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (tab == 0) TocList(controller, onNavigate) else BookmarkList(controller, onNavigate)
    }
}

@Composable
private fun TocList(controller: ReaderController, onNavigate: () -> Unit) {
    val book = controller.book
    val current = remember(controller.chapter, controller.anchor) { controller.currentTocIndex() }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(onClick = {
            controller.goTo(ReadingPosition(0, 0))
            onNavigate()
        }, label = { Text("Đầu sách") })
        book.startPosition?.let { start ->
            AssistChip(onClick = {
                controller.goTo(start)
                onNavigate()
            }, label = { Text("Bắt đầu đọc") })
        }
    }
    LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
        itemsIndexed(book.toc) { index, entry ->
            val selected = index == current
            val percent = (book.progress(ReadingPosition(entry.chapter, entry.offset)) * 100).roundToInt()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        controller.goTo(ReadingPosition(entry.chapter, entry.offset))
                        onNavigate()
                    }
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                    .padding(start = (20 + 16 * entry.depth.coerceAtMost(4)).dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$percent%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        if (book.toc.isEmpty()) {
            item {
                Text(
                    "Sách này không có mục lục.",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BookmarkList(controller: ReaderController, onNavigate: () -> Unit) {
    val marks = controller.bookmarks
    if (marks.isEmpty()) {
        Text(
            "Chưa có dấu trang.\nBấm nút “Dấu trang” trên thanh công cụ để đánh dấu trang đang đọc.",
            modifier = Modifier.padding(20.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy", Locale.forLanguageTag("vi-VN")) }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(marks, key = { it.id }) { mark ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        controller.goTo(mark.position)
                        onNavigate()
                    }
                    .padding(start = 20.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    val percent = (controller.book.progress(mark.position) * 100).roundToInt()
                    Text(
                        "${mark.label} · $percent% · ${dateFormat.format(Date(mark.createdAt))}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        mark.snippet,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { controller.removeBookmark(mark) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Xoá dấu trang")
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        }
    }
}

// ---------------------------------------------------------------- Tìm kiếm

@Composable
internal fun SearchPanel(controller: ReaderController, onNavigate: () -> Unit) {
    var query by rememberSaveable { mutableStateOf(controller.searchQuery) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    OutlinedTextField(
        value = query,
        onValueChange = {
            query = it
            controller.search(it)
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .focusRequester(focus),
        singleLine = true,
        placeholder = { Text("Nhập từ cần tìm…") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = {
                    query = ""
                    controller.search("")
                }) { Icon(Icons.Filled.Close, contentDescription = "Xoá") }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { controller.search(query) }),
    )
    val results = controller.searchResults
    val status = when {
        query.isBlank() -> "Mẹo: gõ không dấu (ví dụ “tieng viet”) để tìm cả chữ có dấu."
        controller.searching -> "Đang tìm…"
        results.isEmpty() -> "Không tìm thấy “$query”."
        results.size >= 300 -> "Hơn 300 kết quả — hãy gõ cụ thể hơn."
        else -> "${results.size} kết quả"
    }
    if (controller.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    Text(
        status,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(results) { hit -> SearchRow(controller, hit, onNavigate) }
    }
}

@Composable
private fun SearchRow(controller: ReaderController, hit: SearchHit, onNavigate: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val text: AnnotatedString = remember(hit) {
        buildAnnotatedString {
            append(hit.snippet.substring(0, hit.matchStart))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent)) {
                append(hit.snippet.substring(hit.matchStart, hit.matchEnd))
            }
            append(hit.snippet.substring(hit.matchEnd))
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable {
                controller.openSearchHit(hit)
                onNavigate()
            }
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(
            controller.book.chapterTitle(hit.chapter),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- Hiển thị (Aa)

@Composable
internal fun DisplayPanel(settings: ReaderSettings, onChange: (ReaderSettings) -> Unit, textOptions: Boolean) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Section("Màu nền") { ThemePicker(settings.theme) { onChange(settings.copy(theme = it)) } }
        if (textOptions) {
            Section("Cỡ chữ") { FontSizePicker(settings.fontSize) { onChange(settings.copy(fontSize = it)) } }
            Section("Phông chữ") {
                Segmented(ReaderFont.entries.map { it.label }, settings.font.ordinal, onSelect = {
                    onChange(settings.copy(font = ReaderFont.entries[it]))
                })
            }
            Section("Giãn dòng") {
                val index = ReaderSettings.LINE_SPACINGS.indexOfFirst { abs(it - settings.lineSpacing) < 0.01f }.coerceAtLeast(0)
                Segmented(ReaderSettings.SPACING_LABELS, index, onSelect = {
                    onChange(settings.copy(lineSpacing = ReaderSettings.LINE_SPACINGS[it]))
                })
            }
            Section("Lề trang") {
                Segmented(ReaderSettings.MARGIN_LABELS, settings.margin, onSelect = { onChange(settings.copy(margin = it)) })
            }
            Section("Căn chữ") {
                Segmented(listOf("Đều hai bên", "Lề trái"), if (settings.justify) 0 else 1, onSelect = {
                    onChange(settings.copy(justify = it == 0))
                })
            }
        }
        Section("Bố cục trang", "Tự động: mở máy gập hiện 2 trang như sách, gập lại hiện 1 trang.") {
            Segmented(PageLayout.entries.map { it.label }, settings.pageLayout.ordinal, onSelect = {
                onChange(settings.copy(pageLayout = PageLayout.entries[it]))
            })
        }
        Section("Thanh công cụ") {
            Segmented(ToolbarSide.entries.map { it.label }, settings.toolbarSide.ordinal, onSelect = {
                onChange(settings.copy(toolbarSide = ToolbarSide.entries[it]))
            })
            SwitchRow("Luôn hiện khi đọc", settings.pinToolbar) { onChange(settings.copy(pinToolbar = it)) }
        }
        Section("Độ sáng") {
            SwitchRow("Theo hệ thống", settings.brightness < 0f) {
                onChange(settings.copy(brightness = if (it) -1f else 0.5f))
            }
            if (settings.brightness >= 0f) {
                Slider(
                    value = settings.brightness,
                    onValueChange = { onChange(settings.copy(brightness = it.coerceIn(0.02f, 1f))) },
                    valueRange = 0.02f..1f,
                )
            }
        }
        Section("Lật trang") {
            SwitchRow("Hiệu ứng trượt khi lật trang", settings.pageAnimation) { onChange(settings.copy(pageAnimation = it)) }
            SwitchRow("Dùng phím âm lượng để lật trang", settings.volumeKeys) { onChange(settings.copy(volumeKeys = it)) }
            SwitchRow("Giữ màn hình luôn sáng khi đọc", settings.keepScreenOn) { onChange(settings.copy(keepScreenOn = it)) }
        }
        if (textOptions) {
            Section("Tốc độ đọc to", "${"%.1f".format(settings.speechRate)}×") {
                Slider(
                    value = settings.speechRate,
                    onValueChange = { onChange(settings.copy(speechRate = (it * 10).roundToInt() / 10f)) },
                    valueRange = 0.5f..2f,
                    steps = 14,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, hint: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        }
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable { onChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selected,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                icon = {},
            ) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ThemePicker(selected: ReaderTheme, onSelect: (ReaderTheme) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        ReaderTheme.entries.forEach { theme ->
            val c = readerColors(theme)
            val isSelected = theme == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onSelect(theme) }
                    .padding(4.dp),
            ) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(c.background)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Aa", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(4.dp))
                Text(theme.label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun FontSizePicker(size: Float, onChange: (Float) -> Unit) {
    val sizes = ReaderSettings.FONT_SIZES
    val index = sizes.indexOfFirst { it >= size - 0.01f }.let { if (it < 0) sizes.size - 1 else it }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedIconButton(onClick = { onChange(sizes[(index - 1).coerceAtLeast(0)]) }, enabled = index > 0) {
            Text("A", fontSize = 13.sp)
        }
        Slider(
            value = index.toFloat(),
            onValueChange = { onChange(sizes[it.roundToInt().coerceIn(0, sizes.size - 1)]) },
            valueRange = 0f..(sizes.size - 1).toFloat(),
            steps = sizes.size - 2,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        OutlinedIconButton(onClick = { onChange(sizes[(index + 1).coerceAtMost(sizes.size - 1)]) }, enabled = index < sizes.size - 1) {
            Text("A", fontSize = 21.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text("${size.roundToInt()}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(24.dp))
    }
}
