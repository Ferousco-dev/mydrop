package com.mydrop.android.receive

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.mydrop.android.MainActivity
import com.mydrop.android.MyDropApp
import com.mydrop.android.R

/** Home-screen widget: shows whether MyDrop is receiving and turns it on or off with one tap. */
class ReceiveWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context)) }
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ReceiveWidget::class.java))
            ids.forEach { manager.updateAppWidget(it, views(context)) }
        }

        private fun views(context: Context): RemoteViews {
            val on = Prefs.receiveEnabled(context)
            val v = RemoteViews(context.packageName, R.layout.widget_receive)
            v.setTextViewText(R.id.widget_title, "MyDrop")
            v.setTextViewText(R.id.widget_status, if (on) "Ready to receive" else "Receiving is off")
            v.setTextViewText(R.id.widget_name, MyDropApp.get(context).engine.deviceName)
            v.setInt(R.id.widget_dot, "setBackgroundResource", if (on) R.drawable.dot_on else R.drawable.dot_off)
            v.setOnClickPendingIntent(
                R.id.widget_toggle,
                PendingIntent.getBroadcast(
                    context, 11, Intent(context, ControlReceiver::class.java).setAction(ControlReceiver.ACTION_TOGGLE),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            v.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context, 12, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            return v
        }
    }
}
