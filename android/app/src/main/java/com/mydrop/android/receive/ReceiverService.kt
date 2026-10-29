package com.mydrop.android.receive

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.mydrop.android.MyDropApp
import com.mydrop.android.engine.IncomingFile
import com.mydrop.android.engine.IncomingRequest
import com.mydrop.android.engine.ReceiveListener
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** Keeps MyDrop discoverable and ready to receive, even when the app is closed. */
class ReceiverService : Service() {
    private val ids = AtomicInteger(100)
    private var dav: com.mydrop.android.share.DavServer? = null

    /** Starts or stops the file server to match the setting and the All-files permission. */
    private fun syncSharing(app: MyDropApp) {
        val filesOn = Prefs.shareFiles(this) && android.os.Environment.isExternalStorageManager()
        val want = filesOn || Prefs.mirror(this)
        if (want && dav == null) {
            val server = com.mydrop.android.share.DavServer(
                android.os.Environment.getExternalStorageDirectory(),
                password = { Prefs.shareCode(this) },
                filesAllowed = { Prefs.shareFiles(this) && android.os.Environment.isExternalStorageManager() },
                writable = { Prefs.shareWrite(this) },
            )
            app.engine.advertiseFiles(server.start())
            dav = server
        } else if (!want && dav != null) {
            dav?.stop(); dav = null
            app.engine.stopAdvertisingFiles()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = MyDropApp.get(this)
        ServiceCompat.startForeground(
            this, Notifier.SERVICE_ID, app.notifier.serviceNotification(app.engine.deviceName),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        syncSharing(app)
        // Already listening: leave it alone, so the port and network record stay stable. Only a rename restarts it.
        val restart = intent?.getBooleanExtra("restart", false) == true
        if (running && !restart) return START_STICKY
        app.engine.stopReceiving()
        app.engine.startReceiving(listener(app))
        app.engine.startDiscovery()                  // keeping the network responder active also keeps us reachable in the background
        running = true
        ReceiveWidget.refresh(this)
        return START_STICKY
    }

    override fun onDestroy() {
        dav?.stop(); MyDropApp.get(this).engine.stopAdvertisingFiles()
        MyDropApp.get(this).engine.stopReceiving()
        MyDropApp.get(this).engine.stopDiscovery()
        running = false
        ReceiveWidget.refresh(this)
        super.onDestroy()
    }

    private fun listener(app: MyDropApp) = object : ReceiveListener {
        private var transferId = 0
        private val saved = ArrayList<SavedFile>()
        private var from = ""

        override fun decide(request: IncomingRequest): Boolean {
            transferId = ids.incrementAndGet()
            from = request.deviceName
            saved.clear()
            if (app.trust.isTrusted(request.deviceName)) {
                app.beginTransfer("Receiving from ${request.deviceName}", request.files.sumOf { it.size })
                return true
            }

            val answer = java.util.concurrent.CompletableFuture<Decision>()
            PendingDecisions.map[transferId] = answer
            app.notifier.askToAccept(transferId, request)
            val decision = try { answer.get(60, TimeUnit.SECONDS) } catch (_: TimeoutException) { Decision.DECLINE }
            PendingDecisions.map.remove(transferId)
            app.notifier.cancel(transferId)
            if (decision == Decision.ACCEPT_AND_TRUST) app.trust.add(request.deviceName)
            if (decision != Decision.DECLINE) app.beginTransfer("Receiving from ${request.deviceName}", request.files.sumOf { it.size })
            return decision != Decision.DECLINE
        }

        override fun openOutput(file: IncomingFile): OutputStream {
            val (stream, info) = Storage.create(this@ReceiverService, file.name, file.mime)
            saved.add(info)
            return stream
        }

        override fun onText(text: String, from: String) {
            app.lastClipboard = text
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("MyDrop", text))
            app.notifier.textReceived(transferId + 300_000, from, text)
        }

        override fun onProgress(fraction: Double) {
            app.progressTransfer("Receiving from $from", fraction)
            app.notifier.progress(transferId, from, (fraction * 100).toInt())
        }

        override fun onFinished(request: IncomingRequest) {
            app.notifier.cancel(transferId)
            app.endTransfer("Received from $from", true)
            if (saved.isEmpty()) return
            app.notifier.received(transferId + 100_000, from, saved.toList())
        }

        override fun onFailed(reason: String) {
            app.endTransfer("Receive failed", false)
            app.notifier.cancel(transferId)
            app.notifier.failed(transferId + 200_000, from.ifEmpty { null }, reason)
        }
    }

    companion object {
        @Volatile var running = false
    }
}
