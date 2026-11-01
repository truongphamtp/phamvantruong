package io.github.truongphamtp.docsach.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.truongphamtp.docsach.core.PositionOffer
import io.github.truongphamtp.docsach.core.SyncUiState
import io.github.truongphamtp.docsach.ui.GlassGroup
import io.github.truongphamtp.docsach.ui.GlassSearchField
import io.github.truongphamtp.docsach.ui.GroupDivider
import io.github.truongphamtp.docsach.ui.HyperSwitch
import io.github.truongphamtp.docsach.ui.LocalHyper
import io.github.truongphamtp.docsach.ui.Motion
import io.github.truongphamtp.docsach.ui.PillButton
import io.github.truongphamtp.docsach.ui.SectionLabel
import io.github.truongphamtp.docsach.ui.SettingRow
import io.github.truongphamtp.docsach.ui.SidePanel
import io.github.truongphamtp.docsach.ui.glass
import io.github.truongphamtp.docsach.ui.pressable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Các thao tác của bảng "Tài khoản & đồng bộ". */
class SyncActions(
    val onSaveClientId: (String) -> Unit,
    val onSignIn: () -> Unit,
    val onSignOut: () -> Unit,
    val onSyncNow: () -> Unit,
    val onSyncFilesChange: (Boolean) -> Unit,
    val onWifiOnlyChange: (Boolean) -> Unit,
    val onDownloadPending: () -> Unit,
)

private val GuidPattern = Regex("^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$")

/** Biểu tượng đám mây theo trạng thái đồng bộ (dùng ở thanh công cụ). */
fun syncIcon(state: SyncUiState): ImageVector = when {
    !state.signedIn -> Icons.Rounded.Cloud
    state.syncing -> Icons.Rounded.Sync
    state.lastError != null -> Icons.Rounded.CloudOff
    else -> Icons.Rounded.CloudDone
}

@Composable
fun AccountPanel(
    visible: Boolean,
    onRight: Boolean,
    state: SyncUiState,
    actions: SyncActions,
    onDismiss: () -> Unit,
) {
    SidePanel(visible = visible, onRight = onRight, title = "Tài khoản & đồng bộ", onDismiss = onDismiss) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AccountPanelContent(state, actions)
        }
    }
}

@Composable
fun AccountPanelContent(state: SyncUiState, actions: SyncActions, now: Long = System.currentTimeMillis()) {
    val p = LocalHyper.current
    var editingId by rememberSaveable { mutableStateOf(false) }
    AccountCard(state, now)
    AnimatedVisibility(visible = state.lastError != null, enter = Motion.enterFromTop(), exit = Motion.exitToTop()) {
        ErrorCard(state.lastError.orEmpty())
    }
    AnimatedContent(
        targetState = when {
            !state.configured || (editingId && !state.signedIn) -> 0
            !state.signedIn -> 1
            else -> 2
        },
        transitionSpec = { fadeIn(tween(260, delayMillis = 60)) togetherWith fadeOut(tween(160)) },
        label = "bước đồng bộ",
    ) { step ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (step) {
                0 -> SetupSection(state) { id ->
                    actions.onSaveClientId(id)
                    editingId = false
                }
                1 -> SignInSection(state, actions, onChangeId = { editingId = true })
                else -> SyncSection(state, actions)
            }
        }
    }
    SectionLabel("Được đồng bộ", Modifier.padding(top = 4.dp))
    GlassGroup {
        Text(
            "Danh sách sách và tệp sách · vị trí đang đọc · dấu trang · ghi chú và đoạn tô sáng · sách đã xoá.",
            color = p.text,
            fontSize = 14.sp,
            modifier = Modifier.padding(vertical = 10.dp),
        )
        GroupDivider()
        Text(
            "Cài đặt hiển thị (cỡ chữ, màu nền…) giữ riêng từng máy cho hợp với màn hình điện thoại và máy tính bảng.",
            color = p.textSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 10.dp),
        )
    }
}

@Composable
private fun AccountCard(state: SyncUiState, now: Long) {
    val p = LocalHyper.current
    GlassGroup {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(p.accentLight, p.accent))),
                contentAlignment = Alignment.Center,
            ) {
                val initial = state.accountName.trim().firstOrNull()?.uppercaseChar()
                if (state.signedIn && initial != null) {
                    Text(initial.toString(), color = p.onAccent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Rounded.Cloud, contentDescription = null, tint = p.onAccent, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.signedIn) state.accountName.ifBlank { "Tài khoản Microsoft" } else "OneDrive",
                    color = p.text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        state.signedIn && state.accountEmail.isNotBlank() -> state.accountEmail
                        state.signedIn -> "Đã đăng nhập"
                        else -> "Chưa đăng nhập"
                    },
                    color = p.textSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state.signedIn || state.signingIn) {
            GroupDivider()
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    if (state.syncing || state.signingIn) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = p.accent, strokeWidth = 2.dp)
                    } else {
                        Icon(syncIcon(state), contentDescription = null, tint = if (state.lastError != null) p.danger else p.accent)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            state.progress != null -> state.progress
                            state.syncing -> "Đang đồng bộ…"
                            state.lastSyncAt > 0 -> "Đồng bộ lần cuối: ${relativeTime(state.lastSyncAt, now)}"
                            else -> "Chưa đồng bộ lần nào"
                        },
                        color = p.text,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val summary = state.summary
                    if (!state.syncing && summary != null) {
                        Text(summary, color = p.textSecondary, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    val p = LocalHyper.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(p.danger.copy(alpha = if (p.dark) 0.22f else 0.1f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = p.danger, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(message, color = p.text, fontSize = 14.sp)
    }
}

@Composable
private fun SetupSection(state: SyncUiState, onSave: (String) -> Unit) {
    val p = LocalHyper.current
    var draft by rememberSaveable { mutableStateOf(state.clientId) }
    var invalid by remember { mutableStateOf(false) }
    val save = {
        val clean = draft.trim()
        invalid = !GuidPattern.matches(clean)
        if (!invalid) onSave(clean)
    }
    GlassGroup {
        Text(
            "Để đăng nhập OneDrive, ứng dụng cần một mã ứng dụng (Application/Client ID) miễn phí do bạn " +
                "đăng ký với Microsoft — làm một lần, khoảng 5 phút. Cách làm có trong mục " +
                "“Đồng bộ OneDrive” của hướng dẫn (README).",
            color = p.text,
            fontSize = 14.sp,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
    GlassSearchField(
        value = draft,
        onValueChange = {
            draft = it
            invalid = false
        },
        placeholder = "Mã ứng dụng, dạng 1a2b3c4d-…",
        keyboardType = KeyboardType.Ascii,
        imeAction = ImeAction.Done,
        onAction = { save() },
        leadingIcon = Icons.Rounded.Key,
    )
    if (invalid) {
        Text(
            "Mã chưa đúng dạng. Mã gồm 36 ký tự, ví dụ 1a2b3c4d-1234-5678-9abc-def012345678.",
            color = p.danger,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
    PillButton("Lưu mã ứng dụng", onClick = { save() }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun SignInSection(state: SyncUiState, actions: SyncActions, onChangeId: () -> Unit) {
    val p = LocalHyper.current
    PillButton(
        if (state.signingIn) "Đang đăng nhập…" else "Đăng nhập tài khoản Microsoft",
        onClick = { if (!state.signingIn) actions.onSignIn() },
        icon = Icons.AutoMirrored.Rounded.Login,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "Bạn đăng nhập trên trang của Microsoft; ứng dụng không thấy mật khẩu. Ứng dụng chỉ được dùng thư mục riêng " +
            "“Ứng dụng › …” trong OneDrive của bạn, không đọc được các tệp khác.",
        color = p.textSecondary,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    )
    Text(
        "Đổi mã ứng dụng",
        color = p.accent,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .pressable(onClick = onChangeId)
            .padding(vertical = 8.dp),
    )
}

@Composable
private fun SyncSection(state: SyncUiState, actions: SyncActions) {
    val p = LocalHyper.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PillButton(
            if (state.syncing) "Đang đồng bộ…" else "Đồng bộ ngay",
            onClick = { if (!state.syncing) actions.onSyncNow() },
            icon = Icons.Rounded.Sync,
            modifier = Modifier.weight(1f),
        )
        PillButton("Đăng xuất", onClick = actions.onSignOut, primary = false, icon = Icons.AutoMirrored.Rounded.Logout, modifier = Modifier.weight(1f))
    }
    if (state.pendingDownloads > 0) {
        GlassGroup {
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CloudDownload, contentDescription = null, tint = p.accent)
                Spacer(Modifier.width(12.dp))
                Text(
                    "${state.pendingDownloads} cuốn trên OneDrive chưa tải về máy này (đang chờ Wi‑Fi).",
                    color = p.text,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
            }
            PillButton(
                "Tải về ngay bằng dữ liệu di động",
                onClick = actions.onDownloadPending,
                primary = false,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
        }
    }
    GlassGroup {
        SettingRow("Đồng bộ cả tệp sách") { HyperSwitch(state.syncFiles, actions.onSyncFilesChange) }
        GroupDivider()
        SettingRow("Chỉ chuyển tệp sách qua Wi‑Fi", Modifier.alpha(if (state.syncFiles) 1f else 0.45f)) {
            HyperSwitch(state.wifiOnly, { if (state.syncFiles) actions.onWifiOnlyChange(it) })
        }
    }
    Text(
        "Vị trí đọc, ghi chú và dấu trang luôn được đồng bộ (rất nhẹ). Tệp sách chỉ tải một lần cho mỗi máy.",
        color = p.textSecondary,
        fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

/** Thẻ nổi trong màn hình đọc: máy khác đã đọc tới vị trí xa hơn. */
@Composable
fun SyncOfferBanner(offer: PositionOffer?, onGo: (PositionOffer) -> Unit, onStay: (PositionOffer) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalHyper.current
    var shown by remember { mutableStateOf<PositionOffer?>(null) }
    if (offer != null) shown = offer
    AnimatedVisibility(visible = offer != null, modifier = modifier, enter = Motion.enterFromTop(), exit = Motion.exitToTop()) {
        val current = shown ?: return@AnimatedVisibility
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .glass(RoundedCornerShape(28.dp), strong = true, elevation = 14.dp)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Devices, contentDescription = null, tint = p.accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Đọc tiếp từ máy khác?", color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Bạn đã đọc tới ${(current.progress * 100).roundToInt()}% trên một máy khác.",
                        color = p.textSecondary,
                        fontSize = 14.sp,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Ở lại đây", onClick = { onStay(current) }, primary = false, modifier = Modifier.weight(1f))
                PillButton("Chuyển tới", onClick = { onGo(current) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** "vừa xong", "5 phút trước", "14:32 hôm nay", "14:32 · 12/10". */
fun relativeTime(time: Long, now: Long): String {
    val diff = now - time
    if (diff < 60_000L) return "vừa xong"
    if (diff < 60 * 60_000L) return "${diff / 60_000L} phút trước"
    val clock = SimpleDateFormat("HH:mm", Locale.forLanguageTag("vi-VN")).format(Date(time))
    val a = Calendar.getInstance().apply { timeInMillis = time }
    val b = Calendar.getInstance().apply { timeInMillis = now }
    val sameDay = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return if (sameDay) "$clock hôm nay" else "$clock · " + SimpleDateFormat("dd/MM", Locale.forLanguageTag("vi-VN")).format(Date(time))
}
