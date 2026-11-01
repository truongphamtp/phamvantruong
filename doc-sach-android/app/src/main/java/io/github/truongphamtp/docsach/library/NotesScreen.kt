package io.github.truongphamtp.docsach.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import io.github.truongphamtp.docsach.core.BookRecord
import io.github.truongphamtp.docsach.core.HighlightColor
import io.github.truongphamtp.docsach.core.Note
import io.github.truongphamtp.docsach.core.ToolbarSide
import io.github.truongphamtp.docsach.format.TextFold
import io.github.truongphamtp.docsach.ui.AccentTile
import io.github.truongphamtp.docsach.ui.GlassIconButton
import io.github.truongphamtp.docsach.ui.GlassSearchField
import io.github.truongphamtp.docsach.ui.HyperDialog
import io.github.truongphamtp.docsach.ui.LiquidBackground
import io.github.truongphamtp.docsach.ui.LocalHazeState
import io.github.truongphamtp.docsach.ui.LocalHyper
import io.github.truongphamtp.docsach.ui.PinnedRail
import io.github.truongphamtp.docsach.ui.RailAction
import io.github.truongphamtp.docsach.ui.glass
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.graphics.Color

/** Màu đậm của từng màu đánh dấu (dùng cho vạch màu và ô chọn màu). */
fun highlightSwatch(color: HighlightColor): Color = when (color) {
    HighlightColor.YELLOW -> Color(0xFFFFC928)
    HighlightColor.GREEN -> Color(0xFF4CC46E)
    HighlightColor.BLUE -> Color(0xFF4E8DF7)
    HighlightColor.PINK -> Color(0xFFF2669B)
}

private val noteDateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.forLanguageTag("vi-VN"))

/** Thẻ một ghi chú: vạch màu, đoạn được đánh dấu, nội dung ghi chú. */
@Composable
fun NoteCard(note: Note, onClick: () -> Unit, onDelete: (() -> Unit)?, modifier: Modifier = Modifier) {
    val p = LocalHyper.current
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .glass(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(highlightSwatch(note.color)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${note.label} · ${noteDateFormat.format(Date(note.createdAt))}",
                color = p.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (note.quote.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "“${note.quote}”",
                    color = p.text,
                    fontSize = 14.sp,
                    fontStyle = FontStyle.Italic,
                    lineHeight = 20.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (note.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.EditNote, contentDescription = null, tint = p.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(note.text, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp)
                }
            }
        }
        if (onDelete != null) {
            GlassIconButton(Icons.Rounded.DeleteOutline, null, onClick = onDelete, size = 34.dp)
        }
    }
}

/** Sổ ghi chú: mọi đoạn đánh dấu và ghi chú của tất cả các cuốn sách. */
@Composable
fun NotesScreen(
    books: List<BookRecord>,
    coverLoader: CoverLoader?,
    onOpenNote: (BookRecord, Note) -> Unit,
    onDeleteNote: (BookRecord, Note) -> Unit,
    onBack: () -> Unit,
    toolbarSide: ToolbarSide = ToolbarSide.RIGHT,
    modifier: Modifier = Modifier,
) {
    val p = LocalHyper.current
    val hazeState = remember { HazeState() }
    var query by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Pair<BookRecord, Note>?>(null) }
    val folded = TextFold.foldString(query.trim())
    val total = books.sumOf { it.notes.size }
    val groups = books
        .filter { it.notes.isNotEmpty() }
        .sortedByDescending { book -> book.notes.maxOf { it.createdAt } }
        .map { book ->
            book to book.notes
                .filter { note ->
                    folded.isEmpty() ||
                        TextFold.foldString(note.quote).contains(folded) ||
                        TextFold.foldString(note.text).contains(folded) ||
                        TextFold.foldString(book.title).contains(folded)
                }
                .sortedWith(compareBy<Note>({ it.chapter }, { it.start }))
        }
        .filter { it.second.isNotEmpty() }
    val onRight = toolbarSide == ToolbarSide.RIGHT
    val actions = listOf(RailAction(Icons.AutoMirrored.Rounded.ArrowBackIos, "Thư viện", onClick = onBack))

    Box(modifier.fillMaxSize()) {
        LiquidBackground(Modifier.fillMaxSize().haze(hazeState))
        CompositionLocalProvider(LocalHazeState provides hazeState) {
            Row(Modifier.fillMaxSize()) {
                if (!onRight) PinnedRail(actions)
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
                    Column(Modifier.padding(start = 22.dp, end = 16.dp, top = 18.dp, bottom = 10.dp)) {
                        Text("Ghi chú", color = p.text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (total == 0) "Chưa có ghi chú" else "$total ghi chú trong ${books.count { it.notes.isNotEmpty() }} cuốn sách",
                            color = p.textSecondary,
                            fontSize = 14.sp,
                        )
                    }
                    GlassSearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "Tìm trong ghi chú",
                        modifier = Modifier.padding(start = 16.dp, end = if (onRight) 4.dp else 16.dp, top = 4.dp, bottom = 8.dp),
                    )
                    if (total == 0) {
                        EmptyNotes()
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 40.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            groups.forEach { (book, notes) ->
                                item(key = "sach-${book.id}") { BookHeader(book, coverLoader, notes.size) }
                                items(notes, key = { it.id }) { note ->
                                    NoteCard(
                                        note = note,
                                        onClick = { onOpenNote(book, note) },
                                        onDelete = { pendingDelete = book to note },
                                    )
                                }
                            }
                            if (groups.isEmpty()) {
                                item { Text("Không có ghi chú nào khớp.", color = p.textSecondary, modifier = Modifier.padding(16.dp)) }
                            }
                        }
                    }
                }
                if (onRight) PinnedRail(actions)
            }
        }
    }

    pendingDelete?.let { (book, note) ->
        HyperDialog(
            onDismissRequest = { pendingDelete = null },
            title = "Xoá ghi chú này?",
            message = note.quote.ifBlank { note.text }.take(160),
            confirmText = "Xoá",
            onConfirm = {
                pendingDelete = null
                onDeleteNote(book, note)
            },
            dismissText = "Huỷ",
            danger = true,
        )
    }
}

@Composable
private fun BookHeader(book: BookRecord, coverLoader: CoverLoader?, count: Int) {
    val p = LocalHyper.current
    Row(
        Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(book, coverLoader, Modifier.width(34.dp), radius = 6)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count ghi chú", color = p.textSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun EmptyNotes() {
    val p = LocalHyper.current
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .glass(RoundedCornerShape(32.dp), elevation = 10.dp)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AccentTile(Icons.Rounded.EditNote, size = 60.dp)
            Spacer(Modifier.height(16.dp))
            Text("Chưa có ghi chú", color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Khi đọc, nhấn giữ vào một chữ rồi kéo để chọn đoạn, sau đó chọn màu đánh dấu hoặc “Ghi chú”. " +
                    "Mọi ghi chú sẽ được lưu ở đây; bấm vào một ghi chú để mở đúng đoạn đó.",
                color = p.textSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
