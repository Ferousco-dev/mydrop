package com.mydrop.android.receive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mydrop.android.MainActivity
import com.mydrop.android.R
import com.mydrop.android.engine.IncomingRequest

enum class Decision { ACCEPT, ACCEPT_AND_TRUST, DECLINE }

/** Answers waiting to be given from a notification button. */
object PendingDecisions {
    val map = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.CompletableFuture<Decision>>()
}

class Notifier(private val context: Context) {
    private val nm = NotificationManagerCompat.from(context)

    fun createChannels() {
        val sys = context.getSystemService(NotificationManager::class.java)
        sys.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Receiving in background", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps MyDrop ready to receive files"
                setShowBadge(false)
            },
        )
        sys.createNotificationChannel(
            NotificationChannel(CH_TRANSFERS, "File transfers", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming files and finished transfers"
            },
        )
        sys.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Transfer progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows progress while files arrive"
                setShowBadge(false)
            },
        )
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(requestCode: Int, action: String, id: Int): PendingIntent = PendingIntent.getBroadcast(
        context, requestCode * 1000 + id,
        Intent(context, ControlReceiver::class.java).setAction(action).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // ---------- the always-on notification ----------

    fun serviceNotification(deviceName: String): android.app.Notification =
        NotificationCompat.Builder(context, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_send)
            .setContentTitle("MyDrop is ready to receive")
            .setContentText("Visible as $deviceName")
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .addAction(0, "Turn off", PendingIntent.getBroadcast(
                context, 7, Intent(context, ControlReceiver::class.java).setAction(ControlReceiver.ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()

    // ---------- someone wants to send us something ----------

    fun askToAccept(id: Int, req: IncomingRequest) {
        val count = req.files.size
        val what = if (req.isText) "some text" else if (count == 1) req.files[0].name else "$count files"
        val n = NotificationCompat.Builder(context, CH_TRANSFERS)
            .setSmallIcon(R.drawable.ic_stat_send)
            .setContentTitle("${req.deviceName} wants to send you $what")
            .setContentText(if (req.isText) "Code ${req.pin}" else "Code ${req.pin} · ${readable(req.files.sumOf { it.size })}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Make sure the code ${req.pin} matches on ${req.deviceName}."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setTimeoutAfter(60_000)
            .setContentIntent(openApp())
            .addAction(0, "Accept", action(1, ControlReceiver.ACTION_ACCEPT, id))
            .addAction(0, "Always accept", action(2, ControlReceiver.ACTION_TRUST, id))
            .addAction(0, "Decline", action(3, ControlReceiver.ACTION_DECLINE, id))
            .build()
        notify(id, n)
    }

    private var lastProgressAt = 0L

    fun progress(id: Int, from: String, percent: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (percent < 100 && now - lastProgressAt < 700) return     // the system drops faster updates anyway
        lastProgressAt = now
        val n = NotificationCompat.Builder(context, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Receiving from $from")
            .setContentText("$percent%")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()
        notify(id, n)
    }

    fun received(id: Int, from: String, files: List<SavedFile>) {
        val first = files.firstOrNull()
        val open: PendingIntent = if (files.size == 1 && first != null) {
            PendingIntent.getActivity(
                context, id,
                Intent(Intent.ACTION_VIEW).setDataAndType(first.uri, first.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } else {
            PendingIntent.getActivity(
                context, id,
                Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val builder = NotificationCompat.Builder(context, CH_TRANSFERS)
            .setSmallIcon(R.drawable.ic_stat_send)
            .setContentTitle(if (files.size == 1) "Received ${first?.name}" else "Received ${files.size} files")
            .setContentText("From $from · tap to open")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
        // Show a preview for pictures.
        if (files.size == 1 && first != null && first.mime.startsWith("image/")) {
            try {
                val src = android.graphics.ImageDecoder.createSource(context.contentResolver, first.uri)
                val bmp = android.graphics.ImageDecoder.decodeBitmap(src) { dec, _, _ -> dec.setTargetSize(900, 600); dec.setTargetSampleSize(2) }
                builder.setLargeIcon(bmp).setStyle(NotificationCompat.BigPictureStyle().bigPicture(bmp).bigLargeIcon(null as android.graphics.Bitmap?))
            } catch (_: Throwable) {}
        }
        notify(id, builder.build())
    }

    fun textReceived(id: Int, from: String, text: String) {
        notify(id, NotificationCompat.Builder(context, CH_TRANSFERS)
            .setSmallIcon(R.drawable.ic_stat_send)
            .setContentTitle("Copied text from $from")
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(600)))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build())
    }

    fun failed(id: Int, from: String?, reason: String) {
        notify(id, NotificationCompat.Builder(context, CH_TRANSFERS)
            .setSmallIcon(R.drawable.ic_stat_send)
            .setContentTitle("Couldn't receive")
            .setContentText(if (from != null) "From $from: $reason" else reason)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build())
    }

    fun cancel(id: Int) = nm.cancel(id)

    @Suppress("MissingPermission")
    private fun notify(id: Int, n: android.app.Notification) {
        if (nm.areNotificationsEnabled()) nm.notify(id, n)
    }

    private fun readable(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
        else -> "%.2f GB".format(bytes / 1073741824.0)
    }

    companion object {
        const val CH_SERVICE = "service"
        const val CH_TRANSFERS = "transfers"
        const val CH_PROGRESS = "progress"
        const val SERVICE_ID = 1
    }
}
