@file:OptIn(ExperimentalFoundationApi::class)

package io.github.truongphamtp.docsach.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.truongphamtp.docsach.core.BookRecord
import io.github.truongphamtp.docsach.core.LibraryPrefs
import io.github.truongphamtp.docsach.core.LibrarySort
import io.github.truongphamtp.docsach.core.LibraryView
import io.github.truongphamtp.docsach.core.ToolbarSide
import io.github.truongphamtp.docsach.format.TextFold
import io.github.truongphamtp.docsach.ui.GeneratedCover
import io.github.truongphamtp.docsach.ui.PinnedRail
import io.github.truongphamtp.docsach.ui.RailAction
import java.text.Collator
import java.util.Locale
import kotlin.math.roundToInt

/** Nạp ảnh bìa đã lưu của một cuốn sách. */
fun interface CoverLoader {
    suspend fun load(book: BookRecord): ImageBitmap?
}

fun sortBooks(books: List<BookRecord>, sort: LibrarySort): List<BookRecord> {
    val collator = Collator.getInstance(Locale.forLanguageTag("vi-VN"))
    return when (sort) {
        LibrarySort.RECENT -> books.sortedWith(compareByDescending<BookRecord> { maxOf(it.lastOpenedAt, it.addedAt) })
        LibrarySort.TITLE -> books.sortedWith { a, b -> collator.compare(a.title, b.title) }
        LibrarySort.AUTHOR -> books.sortedWith { a, b ->
            val c = collator.compare(a.author.ifBlank { "￿" }, b.author.ifBlank { "￿" })
            if (c != 0) c else collator.compare(a.title, b.title)
        }
        LibrarySort.ADDED -> books.sortedByDescending { it.addedAt }
    }
}

@Composable
fun LibraryScreen(
    books: List<BookRecord>,
    prefs: LibraryPrefs,
    onPrefsChange: (LibraryPrefs) -> Unit,
    coverLoader: CoverLoader?,
    busy: Boolean,
    message: String?,
    onMessageShown: () -> Unit,
    onOpen: (BookRecord) -> Unit,
    onAddBooks: () -> Unit,
    onDelete: (BookRecord) -> Unit,
    toolbarSide: ToolbarSide = ToolbarSide.RIGHT,
    modifier: Modifier = Modifier,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSort by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<BookRecord?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            onMessageShown()
        }
    }

    val sorted = remember(books, prefs.sort) { sortBooks(books, prefs.sort) }
    val folded = TextFold.foldString(query.trim())
    val visible = if (folded.isEmpty()) sorted else sorted.filter {
        TextFold.foldString(it.title).contains(folded) || TextFold.foldString(it.author).contains(folded)
    }
    val continueBook = if (folded.isEmpty()) books.filter { it.lastOpenedAt > 0 }.maxByOrNull { it.lastOpenedAt } else null

    val actions = listOf(
        RailAction(Icons.Filled.Search, "Tìm", selected = searching) {
            searching = !searching
            if (!searching) query = ""
        },
        RailAction(Icons.AutoMirrored.Filled.Sort, "Sắp xếp") { showSort = true },
        if (prefs.view == LibraryView.GRID) {
            RailAction(Icons.AutoMirrored.Filled.ViewList, "Danh sách") { onPrefsChange(prefs.copy(view = LibraryView.LIST)) }
        } else {
            RailAction(Icons.Filled.GridView, "Lưới") { onPrefsChange(prefs.copy(view = LibraryView.GRID)) }
        },
    )
    val onRight = toolbarSide == ToolbarSide.RIGHT

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                if (!onRight) LibraryRail(actions, onAddBooks)
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Bottom +
                                    if (onRight) WindowInsetsSides.Start else WindowInsetsSides.End,
                            ),
                        ),
                ) {
                    LibraryHeader(books.size, prefs.sort)
                    if (searching) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text("Tìm theo tên sách hoặc tác giả") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    query = ""
                                    searching = false
                                }) { Icon(Icons.Filled.Close, contentDescription = "Đóng tìm kiếm") }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                    if (books.isEmpty()) {
                        EmptyLibrary(onAddBooks)
                    } else {
                        BookGrid(
                            books = visible,
                            view = prefs.view,
                            continueBook = continueBook,
                            coverLoader = coverLoader,
                            onOpen = onOpen,
                            onLongPress = { selected = it },
                        )
                    }
                }
                if (onRight) LibraryRail(actions, onAddBooks)
            }
            SnackbarHost(
                snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(bottom = 12.dp),
            )
        }
    }

    if (showSort) {
        SortDialog(prefs.sort, onDismiss = { showSort = false }) {
            onPrefsChange(prefs.copy(sort = it))
            showSort = false
        }
    }
    selected?.let { book ->
        BookDialog(
            book = book,
            onDismiss = { selected = null },
            onOpen = {
                selected = null
                onOpen(book)
            },
            onDelete = {
                selected = null
                onDelete(book)
            },
        )
    }
}

@Composable
private fun LibraryRail(actions: List<RailAction>, onAddBooks: () -> Unit) {
    PinnedRail(
        actions = actions,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        header = {
            Icon(
                Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp).size(28.dp),
            )
        },
        footer = {
            // Nút chính đặt thấp nhất bên phải: vừa tầm ngón cái tay phải.
            LargeFloatingActionButton(
                onClick = onAddBooks,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(68.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Thêm sách")
            }
            Spacer(Modifier.height(4.dp))
            Text("Thêm sách", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(20.dp))
        },
    )
}

@Composable
private fun LibraryHeader(count: Int, sort: LibrarySort) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
        Text("Thư viện", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(
            if (count == 0) "Chưa có sách" else "$count cuốn · ${sort.label.lowercase()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyLibrary(onAddBooks: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("Thư viện đang trống", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Bấm “Thêm sách” ở thanh công cụ bên phải để mở file EPUB, PDF hoặc TXT trong máy. " +
                "File EPUB bạn xuất để gửi sang Kindle cũng mở được ở đây.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAddBooks) { Text("Thêm sách") }
    }
}

@Composable
private fun BookGrid(
    books: List<BookRecord>,
    view: LibraryView,
    continueBook: BookRecord?,
    coverLoader: CoverLoader?,
    onOpen: (BookRecord) -> Unit,
    onLongPress: (BookRecord) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val columns = if (view == LibraryView.GRID) GridCells.Adaptive(if (wide) 132.dp else 104.dp) else GridCells.Adaptive(if (wide) 340.dp else 280.dp)
        LazyVerticalGrid(
            columns = columns,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(if (view == LibraryView.GRID) 14.dp else 4.dp),
        ) {
            if (continueBook != null) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                    ContinueCard(continueBook, coverLoader, wide) { onOpen(continueBook) }
                }
            }
            items(books, key = { it.id }) { book ->
                if (view == LibraryView.GRID) {
                    BookTile(book, coverLoader, onClick = { onOpen(book) }, onLongClick = { onLongPress(book) })
                } else {
                    BookRow(book, coverLoader, onClick = { onOpen(book) }, onLongClick = { onLongPress(book) })
                }
            }
            if (books.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Không có sách nào khớp.",
                        modifier = Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Cover(book: BookRecord, coverLoader: CoverLoader?, modifier: Modifier) {
    val cover by produceState<ImageBitmap?>(null, book.id, book.coverFileName) {
        value = coverLoader?.load(book)
    }
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .shadow(3.dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp)),
    ) {
        val bitmap = cover
        if (bitmap != null) {
            Image(bitmap, contentDescription = book.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            GeneratedCover(book.title, book.author, book.format, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ContinueCard(book: BookRecord, coverLoader: CoverLoader?, wide: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(book, coverLoader, Modifier.width(if (wide) 92.dp else 72.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Đọc tiếp", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (book.author.isNotBlank()) {
                    Text(book.author, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { book.progress },
                    modifier = Modifier.fillMaxWidth(),
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Đã đọc ${(book.progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (wide) {
                Spacer(Modifier.width(16.dp))
                FilledTonalButton(onClick = onClick) { Text("Đọc tiếp") }
            }
        }
    }
}

@Composable
private fun BookTile(book: BookRecord, coverLoader: CoverLoader?, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
    ) {
        Box {
            Cover(book, coverLoader, Modifier.fillMaxWidth())
            if (book.lastOpenedAt == 0L) {
                Text(
                    "MỚI",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .padding(6.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        if (book.progress > 0f) {
            LinearProgressIndicator(
                progress = { book.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            book.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (book.progress > 0f) "${(book.progress * 100).roundToInt()}% · ${book.format.label}" else book.author.ifBlank { book.format.label },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BookRow(book: BookRecord, coverLoader: CoverLoader?, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(book, coverLoader, Modifier.width(52.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (book.author.isNotBlank()) {
                Text(
                    book.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "${book.format.label} · ${if (book.lastOpenedAt == 0L) "chưa đọc" else "đã đọc ${(book.progress * 100).roundToInt()}%"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SortDialog(current: LibrarySort, onDismiss: () -> Unit, onSelect: (LibrarySort) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sắp xếp theo") },
        text = {
            Column {
                LibrarySort.entries.forEach { sort ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(sort) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = sort == current, onClick = { onSelect(sort) })
                        Text(sort.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } },
    )
}

@Composable
private fun BookDialog(book: BookRecord, onDismiss: () -> Unit, onOpen: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(book.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (book.author.isNotBlank()) Text("Tác giả: ${book.author}")
                Text("Định dạng: ${book.format.label} · ${formatSize(book.sizeBytes)}")
                Text("Đã đọc: ${(book.progress * 100).roundToInt()}% · ${book.bookmarks.size} dấu trang")
                if (confirmDelete) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Xoá cuốn sách này khỏi thư viện? Tệp gốc trong máy của bạn không bị ảnh hưởng.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (confirmDelete) {
                TextButton(onClick = onDelete) { Text("Xoá", color = MaterialTheme.colorScheme.error) }
            } else {
                TextButton(onClick = onOpen) { Text("Mở sách") }
            }
        },
        dismissButton = {
            if (confirmDelete) {
                TextButton(onClick = { confirmDelete = false }) { Text("Huỷ") }
            } else {
                TextButton(onClick = { confirmDelete = true }) { Text("Xoá khỏi thư viện") }
            }
        },
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
