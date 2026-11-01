package io.github.truongphamtp.docsach.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.truongphamtp.docsach.core.HighlightColor
import io.github.truongphamtp.docsach.core.Note
import io.github.truongphamtp.docsach.library.highlightSwatch
import io.github.truongphamtp.docsach.ui.GlassIconButton
import io.github.truongphamtp.docsach.ui.HyperDialog
import io.github.truongphamtp.docsach.ui.LocalHyper
import io.github.truongphamtp.docsach.ui.glass

/**
 * Thanh thao tác hiện khi đã chọn một đoạn chữ (hoặc chạm vào đoạn đã đánh dấu):
 * chọn màu đánh dấu, viết ghi chú, sao chép, xoá.
 */
@Composable
fun SelectionBar(
    activeNote: Note?,
    onColor: (HighlightColor) -> Unit,
    onNote: () -> Unit,
    onCopy: () -> Unit,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalHyper.current
    Row(
        modifier
            .glass(RoundedCornerShape(30.dp), strong = true, elevation = 14.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HighlightColor.entries.forEach { color ->
            val selected = activeNote?.color == color
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(highlightSwatch(color))
                    .border(if (selected) 2.5.dp else 1.dp, if (selected) p.text else p.divider, CircleShape)
                    .clickable { onColor(color) },
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Rounded.Check, contentDescription = color.label, tint = p.onAccent, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(4.dp))
        Box(Modifier.width(1.dp).height(30.dp).background(p.divider))
        Spacer(Modifier.width(2.dp))
        GlassIconButton(Icons.Rounded.EditNote, null, onClick = onNote, size = 36.dp)
        GlassIconButton(Icons.Rounded.ContentCopy, null, onClick = onCopy, size = 36.dp)
        if (onDelete != null) GlassIconButton(Icons.Rounded.DeleteOutline, null, onClick = onDelete, size = 36.dp)
        GlassIconButton(Icons.Rounded.Close, null, onClick = onClose, size = 36.dp)
    }
}

/** Hộp thoại viết/sửa ghi chú cho một đoạn đã chọn. */
@Composable
fun NoteEditorDialog(
    quote: String,
    initialText: String,
    editing: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val p = LocalHyper.current
    var text by remember { mutableStateOf(initialText) }
    val focus = remember { FocusRequester() }
    HyperDialog(
        onDismissRequest = onDismiss,
        title = if (editing) "Sửa ghi chú" else "Thêm ghi chú",
        confirmText = "Lưu",
        onConfirm = { onSave(text) },
        dismissText = "Huỷ",
    ) {
        if (quote.isNotBlank()) {
            Text(
                "“$quote”",
                color = p.textSecondary,
                fontSize = 14.sp,
                fontStyle = FontStyle.Italic,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(p.controlTrack)
                .padding(14.dp),
        ) {
            if (text.isEmpty()) Text("Viết ghi chú của bạn…", color = p.textSecondary, fontSize = 16.sp)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = p.text, fontSize = 16.sp),
                cursorBrush = SolidColor(p.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}
