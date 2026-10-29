package com.mydrop.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    receiveWhenClosed: Boolean,
    onReceiveChange: (Boolean) -> Unit,
    trustedNames: List<String>,
    onRemoveTrusted: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenPrivacy: () -> Unit,
    extras: List<SettingToggle>,
    shareFiles: Boolean,
    shareCode: String,
    onShareChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var showTrusted by remember { mutableStateOf(false) }
    var showBackgroundHelp by remember { mutableStateOf(false) }


    val listState = rememberLazyListState()
    // The big title collapses into a small centered one once the list scrolls up.
    val collapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 90 }
    }
    val barAlpha by animateFloatAsState(if (collapsed) 1f else 0f, label = "bar")
    val card = MyDropColors.CardOnSheet

    if (showBackgroundHelp) BackgroundHelpDialog(onOpenAppSettings) { showBackgroundHelp = false }
    if (showTrusted) TrustedDevicesDialog(trustedNames, onRemoveTrusted) { showTrusted = false }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.statusBarsPadding(),          // keep the sheet below the status bar
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MyDropColors.Sheet,
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Box(Modifier.fillMaxWidth().fillMaxHeight()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 64.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text("Settings", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                }
                item {
                    SettingToggleCard(Icons.Rounded.FileDownload, MyDropColors.Blue, "Receive files when app is closed",
                        receiveWhenClosed, onReceiveChange,
                        "Enable the setting above to receive files even if MyDrop is closed and the screen is turned off.", card)
                }
                item {
                    SettingToggleCard(Icons.Rounded.Dns, MyDropColors.Blue, "Let my Mac browse this phone", shareFiles, onShareChange,
                        if (shareFiles) "On. On your Mac, choose Browse Files on this phone and enter the code $shareCode once. Turn this off when you're done."
                        else "Lets your Mac open this phone's folders in Finder, read-only. Needs All files access. A code protects it.", card)
                }
                extras.forEach { x ->
                    item { SettingToggleCard(x.icon, x.color, x.title, x.checked, x.onChange, x.footer, card) }
                }
                item {
                    SettingNavCard(Icons.Rounded.BatteryFull, MyDropColors.Orange, "Keep receiving in the background",
                        "Optional. Some phones pause apps that aren't on screen. You can always just open MyDrop when you want to receive. Tap to see how to keep it running.", card) { showBackgroundHelp = true }
                }
                item {
                    SettingNavCard(Icons.Rounded.Folder, MyDropColors.Blue, "Browse Downloaded Files",
                        "All received files are stored in Downloads/MyDrop on this device. Tap to browse this folder.", card, onOpenDownloads)
                }
                item {
                    SettingNavCard(Icons.Rounded.VerifiedUser, MyDropColors.Green, "Manage Trusted Devices",
                        "Trusted devices are devices you have already verified. You will not need to compare verification codes again for future transfers with them.", card) { showTrusted = true }
                }
                item {
                    SettingNavCard(Icons.Rounded.PrivacyTip, MyDropColors.Red, "Privacy Policy", null, card, onOpenPrivacy)
                }
                item { Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(8.dp)) }
            }

            // Top bar: small centered title and divider fade in when collapsed; close button is always there.
            Box(Modifier.fillMaxWidth().height(64.dp)) {
                Box(Modifier.fillMaxWidth().height(64.dp).alpha(barAlpha).background(MyDropColors.Sheet)) {
                    Text("Settings", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.Center))
                    HorizontalDivider(Modifier.align(Alignment.BottomCenter), color = MyDropColors.Divider)
                }
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 20.dp)) {
                    RoundIconButton(Icons.Rounded.Close, "Close", onDismiss)
                }
            }
        }
    }
}

@Composable
fun TrustedDevicesDialog(names: List<String>, onRemove: (String) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MyDropColors.Sheet,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Trusted Devices", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            if (names.isEmpty()) Text("No trusted devices yet. Choose \"Always accept\" on an incoming file to add one.", color = MyDropColors.Secondary)
            else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                names.forEach { n ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(n, color = Color.White, modifier = Modifier.weight(1f))
                        androidx.compose.material3.TextButton(onClick = { onRemove(n) }) { Text("Remove", color = MyDropColors.Red) }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Done", color = MyDropColors.Blue) } },
    )
}

@Composable
fun BackgroundHelpDialog(onOpenAppSettings: () -> Unit, onDismiss: () -> Unit) {
    val steps = listOf(
        "Lock it in Recents" to "Open the recent apps screen and find the MyDrop card. Tap the lock icon on the card, or pull the card down and tap Lock.",
        "Unrestrict the battery" to "Go to Settings \u2192 Apps \u2192 MyDrop \u2192 Battery (or \"Battery usage\"), then choose No restrictions or Allow background activity.",
        "Allow auto-start" to "Open the Phone Master app (or Settings \u2192 Battery), find Auto-start or Startup manager, and switch MyDrop on. Also switch off any power saving or Battery Lab option that restricts it.",
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MyDropColors.Sheet,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Keep receiving in the background", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
        text = {
            Column(
                Modifier.heightIn(max = 380.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("This is optional. Without it, MyDrop still receives whenever the app is open.", color = MyDropColors.Secondary, fontSize = 14.sp)
                steps.forEachIndexed { i, (title, body) ->
                    Column {
                        Text("${i + 1}. $title", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(body, color = MyDropColors.Secondary, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                Text("Menu names vary a little between phone brands.", color = MyDropColors.Secondary, fontSize = 12.sp)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Done", color = MyDropColors.Blue) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onOpenAppSettings) { Text("Open app settings", color = MyDropColors.Blue) } },
    )
}

data class SettingToggle(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val color: Color,
    val title: String,
    val checked: Boolean,
    val footer: String,
    val onChange: (Boolean) -> Unit,
)
