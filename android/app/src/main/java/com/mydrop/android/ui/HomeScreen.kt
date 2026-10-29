package com.mydrop.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class NearbyDevice(val name: String, val status: String = "Available", val id: String = name, val isLaptop: Boolean = false)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    phoneName: String,
    trusted: List<NearbyDevice>,
    searching: Boolean,
    statusLine: String = "",
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    transfer: com.mydrop.android.TransferUi? = null,
    onRename: () -> Unit = {},
    onSettings: () -> Unit,
    onSend: (NearbyDevice, String) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(MyDropColors.Black).statusBarsPadding().navigationBarsPadding()) {
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize(),
        ) {
        Column(Modifier.fillMaxSize().androidx_scroll().padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(44.dp))
            Text("MyDrop", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(10.dp))
            VisiblePill(phoneName, onRename)
            Spacer(Modifier.height(22.dp))

            if (trusted.isNotEmpty()) {
                Text("Nearby Devices", color = MyDropColors.Secondary, fontSize = 13.sp,
                    modifier = Modifier.padding(start = 10.dp, bottom = 8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    trusted.forEach { DeviceCard(it, if (it.isLaptop) Icons.Rounded.Laptop else Icons.Rounded.PhoneAndroid, onSend) }
                }
            }
            if (transfer != null) TransferBar(transfer)
            else if (statusLine.isNotEmpty()) {
                Text(statusLine, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            if (searching) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 22.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Searching for nearby devices…", color = MyDropColors.Secondary, fontSize = 13.sp)
                    Spacer(Modifier.width(10.dp))
                    CircularProgressIndicator(Modifier.size(12.dp), color = MyDropColors.Secondary, strokeWidth = 2.dp)
                }
            }
        }
        }
        SettingsHandle(onSettings, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun VisiblePill(name: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(MyDropColors.Card).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(MyDropColors.Green))
        Spacer(Modifier.width(8.dp))
        Text("Visible as $name", color = Color(0xFFD1D1D6), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(10.dp))
        Icon(Icons.Rounded.Edit, "Rename", tint = MyDropColors.Secondary, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun DeviceCard(device: NearbyDevice, icon: ImageVector, onSend: (NearbyDevice, String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(MyDropColors.Card)
                .clickable { menu = true }.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val macRes = remember { context.resources.getIdentifier("mac_icon", "drawable", context.packageName) }
            Box(Modifier.size(width = 62.dp, height = 44.dp), contentAlignment = Alignment.Center) {
                if (macRes != 0 && device.isLaptop) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(macRes),
                        contentDescription = device.name,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(device.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(device.status, color = MyDropColors.Secondary, fontSize = 14.sp)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MyDropColors.Secondary)
        }
        DropdownMenu(
            expanded = menu, onDismissRequest = { menu = false },
            modifier = Modifier.background(Color(0xFF151516)), shape = RoundedCornerShape(28.dp),
        ) {
            listOf(
                Triple("Send Images", Icons.Rounded.PhotoLibrary, "images"),
                Triple("Send Files", Icons.Rounded.InsertDriveFile, "files"),
                Triple("Send Folder", Icons.Rounded.Folder, "folder"),
                Triple("Send Clipboard", Icons.Rounded.ContentPaste, "clipboard"),
            ).forEach { (label, ic, kind) ->
                DropdownMenuItem(
                    text = { Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
                    leadingIcon = { Icon(ic, null, tint = MyDropColors.Secondary) },
                    onClick = { menu = false; onSend(device, kind) },
                )
            }
        }
    }
}

/** Clean progress card: title, percentage, a thin rounded bar and the live speed. */
@Composable
private fun TransferBar(t: com.mydrop.android.TransferUi) {
    val animated by androidx.compose.animation.core.animateFloatAsState(t.fraction, label = "progress")
    Column(
        Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(24.dp))
            .background(MyDropColors.Card).padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(t.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f), maxLines = 1)
            Text("${(animated * 100).toInt()}%", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = Color.White,
            trackColor = MyDropColors.Track,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        if (t.detail.isNotEmpty()) {
            Text(t.detail, color = MyDropColors.Secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun Modifier.androidx_scroll(): Modifier = this.verticalScroll(androidx.compose.foundation.rememberScrollState())

/** A big chevron at the bottom of the screen. Tap it, or drag it up, to open Settings. */
@Composable
private fun SettingsHandle(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) { 40.dp.toPx() }
    var dragged by remember { mutableStateOf(0f) }
    val bob by androidx.compose.animation.core.rememberInfiniteTransition(label = "bob").animateFloat(
        initialValue = 0f, targetValue = -6f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "bobValue",
    )
    Column(
        modifier.fillMaxWidth().height(120.dp)
            .androidx_pointer(
                onStart = { dragged = 0f },
                onDrag = { dragged += it },
                onEnd = { if (dragged < -threshold) onOpen(); dragged = 0f },
            )
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { onOpen() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Icon(
            Icons.Rounded.KeyboardArrowUp, "Open settings", tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(64.dp).offset(y = bob.dp),
        )
        Text("Settings", color = MyDropColors.Secondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 18.dp))
    }
}

private fun Modifier.androidx_pointer(onStart: () -> Unit, onDrag: (Float) -> Unit, onEnd: () -> Unit): Modifier =
    this.pointerInput(Unit) {
        detectVerticalDragGestures(
            onDragStart = { onStart() },
            onDragEnd = { onEnd() },
            onDragCancel = { onEnd() },
            onVerticalDrag = { _, dy -> onDrag(dy) },
        )
    }
