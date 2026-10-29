package com.mydrop.android

import android.Manifest
import android.content.pm.PackageManager
import android.app.DownloadManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Notifications
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.mydrop.android.engine.DeviceKind
import com.mydrop.android.engine.Peer
import com.mydrop.android.engine.QuickShareEngine
import com.mydrop.android.engine.SendItem
import com.mydrop.android.engine.SendListener
import com.mydrop.android.receive.ControlReceiver
import com.mydrop.android.receive.Prefs
import com.mydrop.android.ui.HomeScreen
import com.mydrop.android.ui.MyDropTheme
import com.mydrop.android.ui.NearbyDevice
import com.mydrop.android.ui.SettingsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var sharing by mutableStateOf(false)
    private var writeOn by mutableStateOf(false)
    private var mirrorOn by mutableStateOf(false)
    private var syncOn by mutableStateOf(false)
    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener { onClipboardChanged() }

    /** Phone to Mac clipboard sync. Android only lets an app read the clipboard while it is on screen. */
    private fun onClipboardChanged() {
        if (!Prefs.syncClipboard(this)) return
        val app = MyDropApp.get(this)
        val text = getSystemService(android.content.ClipboardManager::class.java).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank() || text == app.lastClipboard) return
        app.lastClipboard = text
        val peer = engine.peers.value.firstOrNull { app.trust.isTrusted(it.name) } ?: return
        engine.sendText(peer, text, object : SendListener {})
    }

    /** Opens the privacy policy. Put your link in res/values/strings.xml (privacy_policy_url). */
    private fun openPrivacy() {
        val url = getString(R.string.privacy_policy_url).trim()
        if (url.isEmpty()) {
            android.widget.Toast.makeText(this, "Privacy policy link not added yet", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun changeMirror(on: Boolean) {
        Prefs.setMirror(this, on); mirrorOn = on
        val allowed = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        if (on && !allowed) startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        ControlReceiver.setReceiving(this, true)      // the service starts the server that hands notifications to the Mac
    }

    override fun onResume() {
        super.onResume()
        sharing = Prefs.shareFiles(this) && android.os.Environment.isExternalStorageManager()
        writeOn = Prefs.shareWrite(this)
        mirrorOn = Prefs.mirror(this)
        syncOn = Prefs.syncClipboard(this)
    }

    private fun changeSharing(on: Boolean) {
        if (on && !android.os.Environment.isExternalStorageManager()) {
            // One-time permission screen; switch the setting on again when you come back.
            Prefs.setShareFiles(this, true)
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        Prefs.setShareFiles(this, on)
        sharing = on
        ControlReceiver.setReceiving(this, true)      // the service starts or stops the file server
    }
    private lateinit var engine: QuickShareEngine
    private val status = MutableStateFlow("")

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* the service runs either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        engine = MyDropApp.get(this).engine

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)

        // Receiving lives in a background service so it keeps working when the app is closed.
        if (Prefs.receiveEnabled(this)) ControlReceiver.setReceiving(this, true)

        setContent {
            MyDropTheme {
                val peers by engine.peers.collectAsState()
                val statusLine by status.collectAsState()
                val transferUi by MyDropApp.get(this@MainActivity).transfer.collectAsState()
                var showSettings by remember { mutableStateOf(false) }
                var receiving by remember { mutableStateOf(Prefs.receiveEnabled(this@MainActivity)) }
                var target by remember { mutableStateOf<Peer?>(null) }
                var phoneName by remember { mutableStateOf(engine.deviceName) }
                var refreshing by remember { mutableStateOf(false) }
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                var renaming by remember { mutableStateOf(false) }
                var trusted by remember { mutableStateOf(MyDropApp.get(this@MainActivity).trust.all().sorted()) }

                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                    val peer = target
                    if (peer != null && uris.isNotEmpty()) sendTo(peer, uris)
                }

                val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    val peer = target
                    if (peer != null && uri != null) sendFolder(peer, uri)
                }

                HomeScreen(
                    phoneName = phoneName,
                    onRename = { renaming = true },
                    trusted = peers.sortedBy { it.name }.map { NearbyDevice(it.name, "Available", it.id, it.kind == DeviceKind.LAPTOP) },
                    searching = peers.isEmpty(),
                    statusLine = statusLine,
                    transfer = transferUi,
                    refreshing = refreshing,
                    onRefresh = {
                        refreshing = true
                        engine.stopDiscovery(); engine.startDiscovery()      // look again from scratch
                        scope.launch { kotlinx.coroutines.delay(1500); refreshing = false }
                    },
                    onSettings = { showSettings = true },
                    onSend = { device, kind ->
                        target = peers.firstOrNull { it.id == device.id }
                        if (kind == "files" || kind == "images") picker.launch(arrayOf("*/*"))
                        if (kind == "folder") folderPicker.launch(null)
                        if (kind == "clipboard") target?.let { sendClipboard(it) }
                    },
                )
                if (showSettings) SettingsScreen(
                    receiveWhenClosed = receiving,
                    onReceiveChange = { receiving = it; ControlReceiver.setReceiving(this@MainActivity, it) },
                    trustedNames = trusted,
                    onRemoveTrusted = { MyDropApp.get(this@MainActivity).trust.remove(it); trusted = MyDropApp.get(this@MainActivity).trust.all().sorted() },
                    extras = listOfNotNull(
                        com.mydrop.android.ui.SettingToggle(Icons.Rounded.Edit, com.mydrop.android.ui.MyDropColors.Orange, "Allow editing", writeOn,
                            "Lets your Mac change, add and delete files on this phone through Finder. Only switch this on while you need it.") { Prefs.setShareWrite(this@MainActivity, it); writeOn = it },
                        if (BuildConfig.MIRROR) com.mydrop.android.ui.SettingToggle(Icons.Rounded.Notifications, com.mydrop.android.ui.MyDropColors.Red, "Mirror Android notifications", mirrorOn,
                            "Shows this phone's notifications on your Mac. Needs Notification access, and the Mac needs the phone's code once.") { changeMirror(it) } else null,
                        com.mydrop.android.ui.SettingToggle(Icons.Rounded.ContentCopy, com.mydrop.android.ui.MyDropColors.Orange, "Sync clipboard with Mac", syncOn,
                            "Mac to phone works in the background. Phone to Mac works while MyDrop is open, because Android doesn't let apps read the clipboard from the background. The Mac must be set to \"Always accept\".") { Prefs.setSyncClipboard(this@MainActivity, it); syncOn = it },
                    ),
                    shareFiles = sharing,
                    shareCode = Prefs.shareCode(this@MainActivity),
                    onShareChange = { changeSharing(it) },
                    onOpenPrivacy = { openPrivacy() },
                    onOpenAppSettings = {
                        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    },
                    onOpenDownloads = { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    onDismiss = { showSettings = false },
                )
                if (renaming) {
                    var draft by remember { mutableStateOf(phoneName) }
                    AlertDialog(
                        onDismissRequest = { renaming = false },
                        containerColor = com.mydrop.android.ui.MyDropColors.Sheet,
                        title = { Text("Device name", color = Color.White, fontWeight = FontWeight.Bold) },
                        text = { OutlinedTextField(draft, { draft = it.take(40) }, singleLine = true) },
                        confirmButton = {
                            TextButton(onClick = {
                                val n = draft.trim()
                                if (n.isNotEmpty()) {
                                    engine.deviceName = n; phoneName = n
                                    if (Prefs.receiveEnabled(this@MainActivity)) ControlReceiver.setReceiving(this@MainActivity, true, restart = true)
                                }
                                renaming = false
                            }) { Text("Save", color = com.mydrop.android.ui.MyDropColors.Blue) }
                        },
                        dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel", color = com.mydrop.android.ui.MyDropColors.Secondary) } },
                    )
                }
            }
        }
    }

    private fun sendClipboard(peer: Peer) {
        val clip = getSystemService(android.content.ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) { status.value = "Your clipboard is empty"; return }
        status.value = "Connecting to ${peer.name}…"
        engine.sendText(peer, text, object : SendListener {
            override fun onConnected(pin: String) { status.value = "Accept on ${peer.name} (code $pin)" }
            override fun onFinished() { status.value = "Clipboard sent" }
            override fun onFailed(reason: String) { status.value = "Couldn't send: $reason" }
        })
    }

    private fun sendTo(peer: Peer, uris: List<Uri>) = sendItems(peer, SendHelper.items(contentResolver, uris))

    private fun sendFolder(peer: Peer, tree: Uri) {
        status.value = "Packing the folder…"
        Thread {
            try {
                val zip = com.mydrop.android.share.FolderZip.zip(this, tree)
                runOnUiThread { sendItems(peer, listOf(SendItem(zip.name, zip.length(), "application/zip") { zip.inputStream() })) }
            } catch (e: Exception) { runOnUiThread { status.value = "Couldn't read that folder" } }
        }.start()
    }

    private fun sendItems(peer: Peer, items: List<SendItem>) {
        val app = MyDropApp.get(this)
        status.value = "Connecting to ${peer.name}…"
        engine.send(peer, items, object : SendListener {
            override fun onConnected(pin: String) { status.value = "Accept on ${peer.name} (code $pin)" }
            override fun onAccepted() { status.value = ""; app.beginTransfer("Sending to ${peer.name}", items.sumOf { it.size }) }
            override fun onProgress(fraction: Double) { app.progressTransfer("Sending to ${peer.name}", fraction) }
            override fun onFinished() { app.endTransfer("Sent to ${peer.name}", true) }
            override fun onFailed(reason: String) { status.value = "Couldn't send: $reason"; app.endTransfer("Send failed", false) }
        })
    }

    // Look for nearby devices whenever the screen is visible; receiving keeps running in the service.
    override fun onStart() {
        super.onStart(); engine.startDiscovery()
        getSystemService(android.content.ClipboardManager::class.java).addPrimaryClipChangedListener(clipListener)
    }
    override fun onStop() {
        super.onStop(); if (!com.mydrop.android.receive.ReceiverService.running) engine.stopDiscovery()
        getSystemService(android.content.ClipboardManager::class.java).removePrimaryClipChangedListener(clipListener)
    }
}
