package com.mydrop.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mydrop.android.engine.DeviceKind
import com.mydrop.android.engine.Peer
import com.mydrop.android.engine.SendListener
import com.mydrop.android.ui.MyDropColors
import com.mydrop.android.ui.MyDropTheme

/** Appears in the system Share sheet (Gallery, Files, browsers…): pick a nearby device and the content is sent. */
class ShareActivity : ComponentActivity() {
    private val engine get() = MyDropApp.get(this).engine

    private fun sharedUris(): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM),
        )
        Intent.ACTION_SEND_MULTIPLE ->
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()
        else -> emptyList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uris = sharedUris()
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { uris.isEmpty() }

        setContent {
            MyDropTheme {
                val peers by engine.peers.collectAsState()
                var status by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }

                fun go(peer: Peer) {
                    if (busy) return
                    busy = true
                    status = "Connecting to ${peer.name}…"
                    val listener = object : SendListener {
                        override fun onConnected(pin: String) { status = "Accept on ${peer.name} (code $pin)" }
                        override fun onProgress(fraction: Double) { status = "Sending ${(fraction * 100).toInt()}%" }
                        override fun onFinished() { status = "Sent"; window.decorView.postDelayed({ finish() }, 900) }
                        override fun onFailed(reason: String) { status = "Couldn't send: $reason"; busy = false }
                    }
                    if (text != null) engine.sendText(peer, text, listener)
                    else engine.send(peer, SendHelper.items(contentResolver, uris), listener)
                }

                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() },
                ) {
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp)
                            .clip(RoundedCornerShape(32.dp)).background(MyDropColors.Sheet)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                            .padding(20.dp),
                    ) {
                        Text("Send with MyDrop", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (text != null) "Text" else if (uris.size == 1) "1 item" else "${uris.size} items",
                            color = MyDropColors.Secondary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
                        )
                        if (peers.isEmpty()) {
                            Text("Looking for nearby devices…", color = MyDropColors.Secondary, fontSize = 14.sp,
                                modifier = Modifier.padding(vertical = 18.dp))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            peers.sortedBy { it.name }.forEach { p ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MyDropColors.CardOnSheet)
                                        .clickable { go(p) }.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.size(40.dp).clip(CircleShape).background(MyDropColors.Avatar), contentAlignment = Alignment.Center) {
                                        Icon(if (p.kind == DeviceKind.LAPTOP) Icons.Rounded.Laptop else Icons.Rounded.PhoneAndroid, null,
                                            tint = Color.White, modifier = Modifier.size(22.dp))
                                    }
                                    Text(p.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(start = 14.dp))
                                }
                            }
                        }
                        if (status.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text(status, color = Color.White, fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); engine.startDiscovery() }
    override fun onStop() { super.onStop(); engine.stopDiscovery() }
}
