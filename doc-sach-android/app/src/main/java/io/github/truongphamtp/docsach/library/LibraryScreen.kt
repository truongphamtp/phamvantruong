@file:OptIn(ExperimentalFoundationApi::class)

package io.github.truongphamtp.docsach.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import io.github.truongphamtp.docsach.core.BookRecord
import io.github.truongphamtp.docsach.core.LibraryPrefs
import io.github.truongphamtp.docsach.core.LibrarySort
import io.github.truongphamtp.docsach.core.LibraryView
import io.github.truongphamtp.docsach.core.ToolbarSide
import io.github.truongphamtp.docsach.format.TextFold
import io.github.truongphamtp.docsach.ui.AccentTile
import io.github.truongphamtp.docsach.ui.GeneratedCover
import io.github.truongphamtp.docsach.ui.GlassSearchField
import io.github.truongphamtp.docsach.ui.GlassToast
import io.github.truongphamtp.docsach.ui.HyperDialog
import io.github.truongphamtp.docsach.ui.LiquidBackground
import io.github.truongphamtp.docsach.ui.LocalHazeState
import io.github.truongphamtp.docsach.ui.LocalHyper
import io.github.truongphamtp.docsach.ui.PillButton
import io.github.truongphamtp.docsach.ui.PinnedRail
import io.github.truongphamtp.docsach.ui.RailAction
import io.github.truongphamtp.docsach.ui.ThinProgress
import io.github.truongphamtp.docsach.ui.glass
import kotlinx.coroutines.delay
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
    onOpenNotes: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showSort by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<BookRecord?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    val searchFocus = remember { FocusRequester() }
    val hazeState = remember { HazeState() }

    LaunchedEffect(message) {
        if (message != null) {
            toast = message
            onMessageShown()
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(3200)
            toast = null
        }
    }

    val sorted = remember(books, prefs.sort) { sortBooks(books, prefs.sort) }
    val folded = TextFold.foldString(query.trim())
    val visible = if (folded.isEmpty()) sorted else sorted.filter {
        TextFold.foldString(it.title).contains(folded) || TextFold.foldString(it.author).contains(folded)
    }
    val continueBook = if (folded.isEmpty()) books.filter { it.lastOpenedAt > 0 }.maxByOrNull { it.lastOpenedAt } else null

    val actions = listOf(
        RailAction(Icons.Rounded.Search, "Tìm") { runCatching { searchFocus.requestFocus() } },
        RailAction(Icons.Rounded.EditNote, "Ghi chú") { onOpenNotes() },
        RailAction(Icons.AutoMirrored.Rounded.Sort, "Sắp xếp") { showSort = true },
        if (prefs.view == LibraryView.GRID) {
            RailAction(Icons.AutoMirrored.Rounded.ViewList, "Danh sách") { onPrefsChange(prefs.copy(view = LibraryView.LIST)) }
        } else {
            RailAction(Icons.Rounded.GridView, "Lưới") { onPrefsChange(prefs.copy(view = LibraryView.GRID)) }
        },
    )
    val onRight = toolbarSide == ToolbarSide.RIGHT

    Box(modifier.fillMaxSize()) {
        // Nền "chất lỏng" làm nguồn cho hiệu ứng kính mờ.
        LiquidBackground(Modifier.fillMaxSize().haze(hazeState))
        CompositionLocalProvider(LocalHazeState provides hazeState) {
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
                    LibraryHeader(books.size, prefs.sort, busy)
                    GlassSearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "Tìm sách hoặc tác giả",
                        focusRequester = searchFocus,
                        modifier = Modifier.padding(start = 16.dp, end = if (onRight) 4.dp else 16.dp, top = 4.dp, bottom = 8.dp),
                    )
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
            AnimatedVisibility(
                visible = toast != null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(bottom = 18.dp, start = 24.dp, end = 24.dp),
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
            ) {
                GlassToast(toast.orEmpty())
            }
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
    val p = LocalHyper.current
    PinnedRail(
        actions = actions,
        header = {
            AccentTile(Icons.AutoMirrored.Rounded.MenuBook, size = 42.dp)
            Spacer(Modifier.height(10.dp))
        },
        footer = {
            Spacer(Modifier.height(8.dp))
            // Nút chính đặt thấp nhất bên phải: vừa tầm ngón cái tay phải.
            Box(
                Modifier
                    .size(58.dp)
                    .shadow(14.dp, CircleShape, ambientColor = p.accent, spotColor = p.accent)
                    .clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(p.accentLight, p.accent)))
                    .border(1.dp, Color.White.copy(alpha = 0.55f), CircleShape)
                    .clickable(onClick = onAddBooks),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Thêm sách", tint = p.onAccent, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(5.dp))
            Text("Thêm sách", color = p.text.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
        },
    )
}

@Composable
private fun LibraryHeader(count: Int, sort: LibrarySort, busy: Boolean) {
    val p = LocalHyper.current
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Thư viện", color = p.text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(
                if (count == 0) "Chưa có sách" else "$count cuốn · ${sort.label.lowercase()}",
                color = p.textSecondary,
                fontSize = 14.sp,
            )
        }
        if (busy) {
            Row(
                Modifier.glass(CircleShape).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), color = p.accent, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Đang thêm sách…", color = p.text, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onAddBooks: () -> Unit) {
    val p = LocalHyper.current
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .glass(RoundedCornerShape(32.dp), elevation = 10.dp)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AccentTile(Icons.AutoMirrored.Rounded.MenuBook, size = 64.dp)
            Spacer(Modifier.height(18.dp))
            Text("Thư viện đang trống", color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Bấm “Thêm sách” ở thanh công cụ bên phải để mở file EPUB, PDF hoặc TXT trong máy. " +
                    "File EPUB bạn xuất để gửi sang Kindle cũng mở được ở đây.",
                color = p.textSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            PillButton("Thêm sách", onClick = onAddBooks, icon = Icons.Rounded.Add)
        }
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
    val p = LocalHyper.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val columns = if (view == LibraryView.GRID) GridCells.Adaptive(if (wide) 132.dp else 104.dp) else GridCells.Adaptive(if (wide) 340.dp else 280.dp)
        LazyVerticalGrid(
            columns = columns,
            contentPadding = PaddingValues(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(if (view == LibraryView.GRID) 12.dp else 8.dp),
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
                    Text("Không có sách nào khớp.", modifier = Modifier.padding(20.dp), color = p.textSecondary)
                }
            }
        }
    }
}

@Composable
internal fun Cover(book: BookRecord, coverLoader: CoverLoader?, modifier: Modifier, radius: Int = 14) {
    val p = LocalHyper.current
    val cover by produceState<ImageBitmap?>(null, book.id, book.coverFileName) {
        value = coverLoader?.load(book)
    }
    val shape = RoundedCornerShape(radius.dp)
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .shadow(8.dp, shape, ambientColor = p.shadow, spotColor = p.shadow)
            .clip(shape),
    ) {
        val bitmap = cover
        if (bitmap != null) {
            Image(bitmap, contentDescription = book.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            GeneratedCover(book.title, book.author, book.format, Modifier.fillMaxSize())
        }
        // Lớp bóng kính mỏng trên bìa.
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.18f), Color.Transparent, Color.Transparent)))
                .border(1.dp, Color.White.copy(alpha = 0.25f), shape),
        )
    }
}

@Composable
private fun ContinueCard(book: BookRecord, coverLoader: CoverLoader?, wide: Boolean, onClick: () -> Unit) {
    val p = LocalHyper.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .glass(RoundedCornerShape(28.dp), elevation = 8.dp)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(book, coverLoader, Modifier.width(if (wide) 92.dp else 74.dp), radius = 12)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("ĐỌC TIẾP", color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                book.title,
                color = p.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (book.author.isNotBlank()) {
                Text(book.author, color = p.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            ThinProgress(book.progress, height = 6.dp)
            Spacer(Modifier.height(6.dp))
            Text("Đã đọc ${(book.progress * 100).roundToInt()}%", color = p.textSecondary, fontSize = 12.sp)
        }
        if (wide) {
            Spacer(Modifier.width(16.dp))
            PillButton("Đọc tiếp", onClick = onClick, icon = Icons.Rounded.PlayArrow)
        }
    }
}

@Composable
private fun BookTile(book: BookRecord, coverLoader: CoverLoader?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val p = LocalHyper.current
    Column(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(6.dp),
    ) {
        Box {
            Cover(book, coverLoader, Modifier.fillMaxWidth())
            if (book.lastOpenedAt == 0L) {
                Text(
                    "MỚI",
                    color = p.onAccent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(Brush.horizontalGradient(listOf(p.accentLight, p.accent)))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        if (book.progress > 0f) {
            ThinProgress(book.progress, Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            book.title,
            color = p.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 18.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (book.progress > 0f) "${(book.progress * 100).roundToInt()}% · ${book.format.label}" else book.author.ifBlank { book.format.label },
            color = p.textSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BookRow(book: BookRecord, coverLoader: CoverLoader?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val p = LocalHyper.current
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(22.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(book, coverLoader, Modifier.width(52.dp), radius = 10)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (book.author.isNotBlank()) {
                Text(book.author, color = p.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            if (book.progress > 0f) ThinProgress(book.progress, Modifier.padding(end = 40.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                "${book.format.label} · ${if (book.lastOpenedAt == 0L) "chưa đọc" else "đã đọc ${(book.progress * 100).roundToInt()}%"}",
                color = p.textSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SortDialog(current: LibrarySort, onDismiss: () -> Unit, onSelect: (LibrarySort) -> Unit) {
    val p = LocalHyper.current
    HyperDialog(
        onDismissRequest = onDismiss,
        title = "Sắp xếp theo",
        confirmText = "Xong",
        onConfirm = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            LibrarySort.entries.forEach { sort ->
                val isCurrent = sort == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isCurrent) p.accent.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable { onSelect(sort) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        sort.label,
                        color = if (isCurrent) p.accent else p.text,
                        fontSize = 16.sp,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    if (isCurrent) Icon(Icons.Rounded.Check, contentDescription = null, tint = p.accent)
                }
            }
        }
    }
}

@Composable
private fun BookDialog(book: BookRecord, onDismiss: () -> Unit, onOpen: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    val info = buildString {
        if (book.author.isNotBlank()) append("Tác giả: ${book.author}\n")
        append("${book.format.label} · ${formatSize(book.sizeBytes)} · đã đọc ${(book.progress * 100).roundToInt()}%")
        if (book.bookmarks.isNotEmpty()) append(" · ${book.bookmarks.size} dấu trang")
    }
    if (confirmDelete) {
        HyperDialog(
            onDismissRequest = onDismiss,
            title = "Xoá “${book.title}”?",
            message = "Sách sẽ bị xoá khỏi thư viện cùng dấu trang. Tệp gốc trong máy của bạn không bị ảnh hưởng.",
            confirmText = "Xoá",
            onConfirm = onDelete,
            dismissText = "Huỷ",
            onDismiss = { confirmDelete = false },
            danger = true,
        )
    } else {
        HyperDialog(
            onDismissRequest = onDismiss,
            title = book.title,
            message = info,
            confirmText = "Mở sách",
            onConfirm = onOpen,
            dismissText = "Xoá khỏi thư viện",
            onDismiss = { confirmDelete = true },
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
