package com.mydrop.android.receive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Handles notification buttons and the widget tap. */
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        when (intent.action) {
            ACTION_ACCEPT -> PendingDecisions.map[id]?.complete(Decision.ACCEPT)
            ACTION_TRUST -> PendingDecisions.map[id]?.complete(Decision.ACCEPT_AND_TRUST)
            ACTION_DECLINE -> PendingDecisions.map[id]?.complete(Decision.DECLINE)
            ACTION_STOP -> setReceiving(context, false)
            ACTION_TOGGLE -> setReceiving(context, !Prefs.receiveEnabled(context))
        }
    }

    companion object {
        const val ACTION_ACCEPT = "com.mydrop.android.ACCEPT"
        const val ACTION_TRUST = "com.mydrop.android.TRUST"
        const val ACTION_DECLINE = "com.mydrop.android.DECLINE"
        const val ACTION_STOP = "com.mydrop.android.STOP"
        const val ACTION_TOGGLE = "com.mydrop.android.TOGGLE"

        /** Turns background receiving on or off and keeps the widget in step. */
        fun setReceiving(context: Context, on: Boolean, restart: Boolean = false) {
            Prefs.setReceiveEnabled(context, on)
            val intent = Intent(context, ReceiverService::class.java).putExtra("restart", restart)
            if (on) ContextCompat.startForegroundService(context, intent) else context.stopService(intent)
            ReceiveWidget.refresh(context)
        }
    }
}
