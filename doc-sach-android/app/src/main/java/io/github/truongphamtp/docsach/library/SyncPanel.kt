package io.github.truongphamtp.docsach.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.truongphamtp.docsach.core.LanPeerUi
import io.github.truongphamtp.docsach.core.LanUiState
import io.github.truongphamtp.docsach.core.NearbyDeviceUi
import io.github.truongphamtp.docsach.core.SyncUiState
import io.github.truongphamtp.docsach.ui.AccentTile
import io.github.truongphamtp.docsach.ui.GlassGroup
import io.github.truongphamtp.docsach.ui.GlassSearchField
import io.github.truongphamtp.docsach.ui.GroupDivider
import io.github.truongphamtp.docsach.ui.HyperDialog
import io.github.truongphamtp.docsach.ui.LocalHyper
import io.github.truongphamtp.docsach.ui.Motion
import io.github.truongphamtp.docsach.ui.PillButton
import io.github.truongphamtp.docsach.ui.SectionLabel
import io.github.truongphamtp.docsach.ui.SidePanel
import io.github.truongphamtp.docsach.ui.pressable

/** Các thao tác đồng bộ qua Wi‑Fi. */
class LanActions(
    val onStartPairing: () -> Unit,
    val onStopPairing: () -> Unit,
    val onPair: (deviceId: String, code: String) -> Unit,
    val onPairAddress: (address: String, code: String) -> Unit,
    val onSyncNow: () -> Unit,
    val onUnpair: (peerId: String) -> Unit,
)

/** Biểu tượng nút Đồng bộ ở thanh công cụ. */
fun syncRailIcon(lan: LanUiState?, cloud: SyncUiState?): ImageVector = when {
    lan?.syncing == true || cloud?.syncing == true -> Icons.Rounded.Sync
    lan != null -> Icons.Rounded.Devices
    cloud != null -> syncIcon(cloud)
    else -> Icons.Rounded.Devices
}

/** Bảng "Đồng bộ": qua Wi‑Fi giữa các máy (chính), OneDrive (nâng cao). */
@Composable
fun SyncPanel(
    visible: Boolean,
    onRight: Boolean,
    lan: LanUiState,
    lanActions: LanActions,
    cloud: SyncUiState?,
    cloudActions: SyncActions?,
    onDismiss: () -> Unit,
) {
    SidePanel(visible = visible, onRight = onRight, title = "Đồng bộ", onDismiss = onDismiss) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SyncPanelContent(lan, lanActions, cloud, cloudActions)
        }
    }
}

@Composable
fun SyncPanelContent(
    lan: LanUiState,
    lanActions: LanActions,
    cloud: SyncUiState?,
    cloudActions: SyncActions?,
    now: Long = System.currentTimeMillis(),
) {
    val p = LocalHyper.current
    ThisDeviceCard(lan, now)
    AnimatedVisibility(visible = lan.lastError != null, enter = Motion.enterFromTop(), exit = Motion.exitToTop()) {
        LanErrorCard(lan.lastError.orEmpty())
    }
    AnimatedContent(
        targetState = lan.pairingCode != null,
        transitionSpec = { fadeIn(tween(260, delayMillis = 60)) togetherWith fadeOut(tween(160)) },
        label = "ghép đôi",
    ) { pairing ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (pairing) {
                PairingSection(lan, lanActions)
            } else {
                PeersSection(lan, lanActions, now)
            }
        }
    }
    GlassGroup {
        Text(
            "Mở ứng dụng trên cả hai máy, dùng chung Wi‑Fi (hoặc điểm phát của điện thoại) là tự đồng bộ: danh sách sách, " +
                "tệp sách, vị trí đang đọc, dấu trang, ghi chú. Không cần tài khoản, dữ liệu không đi qua Internet.",
            color = p.textSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 10.dp),
        )
    }
    if (cloud != null && cloudActions != null) {
        var showCloud by rememberSaveable { mutableStateOf(cloud.signedIn) }
        val arrow by animateFloatAsState(if (showCloud) 180f else 0f, Motion.snappy(), label = "mũi tên")
        Row(
            Modifier
                .fillMaxWidth()
                .pressable(pressedScale = 0.98f) { showCloud = !showCloud }
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Cloud, contentDescription = null, tint = p.textSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("OneDrive (nâng cao)", color = p.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = p.textSecondary, modifier = Modifier.rotate(arrow))
        }
        AnimatedVisibility(visible = showCloud, enter = expandVertically(Motion.soft()) + fadeIn(), exit = shrinkVertically(tween(200)) + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AccountPanelContent(cloud, cloudActions, now)
            }
        }
    }
}

@Composable
private fun ThisDeviceCard(lan: LanUiState, now: Long) {
    val p = LocalHyper.current
    GlassGroup {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AccentTile(Icons.Rounded.Smartphone, size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(lan.deviceName.ifBlank { "Máy này" }, color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (lan.active) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
                        contentDescription = null,
                        tint = if (lan.active) p.accent else p.textSecondary,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (lan.active) "Sẵn sàng đồng bộ" + (lan.address?.let { " · $it" } ?: "") else "Chưa sẵn sàng (cần Wi‑Fi)",
                        color = p.textSecondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (lan.syncing || lan.summary != null || lan.lastSyncAt > 0) {
            GroupDivider()
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    if (lan.syncing) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = p.accent, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Sync, contentDescription = null, tint = p.accent)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            lan.progress != null -> lan.progress
                            lan.lastSyncAt > 0 -> "Đồng bộ lần cuối: ${relativeTime(lan.lastSyncAt, now)}"
                            else -> "Đang đồng bộ…"
                        },
                        color = p.text,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!lan.syncing && lan.summary != null) Text(lan.summary, color = p.textSecondary, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun PeersSection(lan: LanUiState, actions: LanActions, now: Long) {
    val p = LocalHyper.current
    var unpairing by remember { mutableStateOf<LanPeerUi?>(null) }
    SectionLabel("Máy đã ghép đôi", Modifier.padding(top = 2.dp))
    if (lan.peers.isEmpty()) {
        GlassGroup {
            Text(
                "Chưa ghép đôi máy nào. Bấm \"Ghép đôi máy mới\" trên cả hai máy, rồi nhập mã hiện trên máy này vào máy kia.",
                color = p.text,
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    } else {
        GlassGroup {
            lan.peers.forEachIndexed { index, peer ->
                if (index > 0) GroupDivider()
                PeerRow(peer, now, onUnpair = { unpairing = peer })
            }
        }
    }
    // Hai nút xếp dọc để chữ không bị cắt trên màn hình hẹp (máy gập).
    PillButton("Ghép đôi máy mới", onClick = actions.onStartPairing, icon = Icons.Rounded.Devices, modifier = Modifier.fillMaxWidth())
    if (lan.peers.isNotEmpty()) {
        PillButton(
            if (lan.syncing) "Đang đồng bộ…" else "Đồng bộ ngay",
            onClick = { if (!lan.syncing) actions.onSyncNow() },
            primary = false,
            icon = Icons.Rounded.Sync,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    unpairing?.let { peer ->
        HyperDialog(
            onDismissRequest = { unpairing = null },
            title = "Bỏ ghép đôi với ${peer.name}?",
            message = "Hai máy sẽ thôi đồng bộ với nhau. Sách và ghi chú trên mỗi máy vẫn giữ nguyên.",
            confirmText = "Bỏ ghép đôi",
            onConfirm = {
                actions.onUnpair(peer.id)
                unpairing = null
            },
            dismissText = "Thôi",
            danger = true,
        )
    }
}

@Composable
private fun PeerRow(peer: LanPeerUi, now: Long, onUnpair: () -> Unit) {
    val p = LocalHyper.current
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        // Chấm xanh: đang thấy máy này trong cùng Wi‑Fi.
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (peer.online) p.accent else p.textSecondary.copy(alpha = 0.35f)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(peer.name, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    peer.syncing -> "Đang đồng bộ…"
                    peer.online && peer.lastSyncAt > 0 -> "Đang kết nối · đồng bộ ${relativeTime(peer.lastSyncAt, now)}"
                    peer.online -> "Đang kết nối"
                    peer.lastSyncAt > 0 -> "Không thấy trên Wi‑Fi · lần cuối ${relativeTime(peer.lastSyncAt, now)}"
                    else -> "Không thấy trên Wi‑Fi"
                },
                color = p.textSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (peer.syncing) {
            CircularProgressIndicator(Modifier.size(18.dp), color = p.accent, strokeWidth = 2.dp)
        } else {
            Box(
                Modifier
                    .size(36.dp)
                    .pressable(onClick = onUnpair),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.LinkOff, contentDescription = "Bỏ ghép đôi", tint = p.textSecondary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun PairingSection(lan: LanUiState, actions: LanActions) {
    val p = LocalHyper.current
    var chosen by remember { mutableStateOf<NearbyDeviceUi?>(null) }
    var code by remember { mutableStateOf("") }
    var manual by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    GlassGroup {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Mã ghép đôi của máy này", color = p.textSecondary, fontSize = 13.sp)
            Spacer(Modifier.size(6.dp))
            Text(
                lan.pairingCode.orEmpty().chunked(3).joinToString(" "),
                color = p.text,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 4.sp,
            )
            Spacer(Modifier.size(6.dp))
            Text(
                "Trên máy kia: mở Đồng bộ → Ghép đôi máy mới → chọn máy này trong danh sách rồi nhập mã trên.",
                color = p.textSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            lan.address?.let { address ->
                Spacer(Modifier.size(6.dp))
                Text("Địa chỉ máy này: $address", color = p.textSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
    }
    SectionLabel("Hoặc nhập mã của máy kia vào đây", Modifier.padding(top = 2.dp))
    GlassGroup {
        if (lan.nearby.isEmpty()) {
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = p.accent, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Đang tìm máy đang bật ghép đôi trong cùng Wi‑Fi…", color = p.textSecondary, fontSize = 14.sp)
            }
        } else {
            lan.nearby.forEachIndexed { index, device ->
                if (index > 0) GroupDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressable(pressedScale = 0.98f) {
                            chosen = device
                            code = ""
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Devices, contentDescription = null, tint = p.accent, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(device.name, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("Chọn", color = p.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    Text(
        if (manual) "Ẩn ô nhập địa chỉ" else "Không thấy máy kia? Nhập địa chỉ",
        color = p.accent,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .pressable { manual = !manual }
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
    AnimatedVisibility(visible = manual, enter = expandVertically(Motion.soft()) + fadeIn(), exit = shrinkVertically(tween(200)) + fadeOut()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassSearchField(
                value = address,
                onValueChange = { address = it.filter { c -> c.isDigit() || c == '.' || c == ':' } },
                placeholder = "Địa chỉ hiện trên máy kia, vd. 192.168.1.5:47123",
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
                leadingIcon = Icons.Rounded.Wifi,
            )
            GlassSearchField(
                value = code,
                onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                placeholder = "Mã 6 số hiện trên máy kia",
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
                onAction = { if (code.length == 6 && address.isNotBlank()) actions.onPairAddress(address, code) },
                leadingIcon = null,
            )
            PillButton(
                if (lan.pairingBusy) "Đang ghép đôi…" else "Ghép đôi",
                onClick = { if (!lan.pairingBusy && code.length == 6 && address.isNotBlank()) actions.onPairAddress(address, code) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    PillButton("Xong", onClick = actions.onStopPairing, primary = false, modifier = Modifier.fillMaxWidth())

    chosen?.let { device ->
        HyperDialog(
            onDismissRequest = { chosen = null },
            title = "Ghép đôi với ${device.name}",
            message = "Nhập mã 6 số đang hiện trên ${device.name}.",
            confirmText = if (lan.pairingBusy) "Đang ghép đôi…" else "Ghép đôi",
            onConfirm = {
                if (code.length == 6 && !lan.pairingBusy) {
                    actions.onPair(device.id, code)
                    chosen = null
                }
            },
            dismissText = "Thôi",
        ) {
            GlassSearchField(
                value = code,
                onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                placeholder = "Mã 6 số",
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
                onAction = {
                    if (code.length == 6) {
                        actions.onPair(device.id, code)
                        chosen = null
                    }
                },
                leadingIcon = null,
            )
        }
    }
}

@Composable
private fun LanErrorCard(message: String) {
    val p = LocalHyper.current
    GlassGroup {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.WifiOff, contentDescription = null, tint = p.danger, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(message, color = p.text, fontSize = 14.sp)
        }
    }
}
