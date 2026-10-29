package com.mydrop.android

import android.app.Application
import android.content.Context
import com.mydrop.android.engine.QuickShareEngine
import com.mydrop.android.receive.Notifier
import com.mydrop.android.receive.TrustStore

data class TransferUi(val label: String, val fraction: Float, val detail: String)

class MyDropApp : Application() {
    lateinit var engine: QuickShareEngine
    lateinit var trust: TrustStore
    lateinit var notifier: Notifier
    @Volatile var lastClipboard: String = ""

    /** What the home screen's progress bar shows. Null when nothing is transferring. */
    val transfer = kotlinx.coroutines.flow.MutableStateFlow<TransferUi?>(null)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastUiAt = 0L
    private var startedAt = 0L
    private var totalBytes = 0L

    fun beginTransfer(label: String, total: Long) {
        startedAt = android.os.SystemClock.elapsedRealtime(); totalBytes = total; lastUiAt = 0
        transfer.value = TransferUi(label, 0f, "Starting…")
    }

    fun progressTransfer(label: String, fraction: Double) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (fraction < 1.0 && now - lastUiAt < 200) return
        lastUiAt = now
        val secs = ((now - startedAt) / 1000.0).coerceAtLeast(0.2)
        val mbs = fraction * totalBytes / 1048576.0 / secs
        transfer.value = TransferUi(label, fraction.toFloat(), if (totalBytes > 0) "%.1f MB/s".format(mbs) else "")
    }

    fun endTransfer(label: String, ok: Boolean) {
        transfer.value = TransferUi(label, if (ok) 1f else 0f, if (ok) "Done" else "")
        handler.postDelayed({ transfer.value = null }, 2000)
    }

    override fun onCreate() {
        super.onCreate()
        engine = QuickShareEngine(this)
        trust = TrustStore(this)
        notifier = Notifier(this)
        notifier.createChannels()
    }

    companion object {
        fun get(context: Context) = context.applicationContext as MyDropApp
    }
}
