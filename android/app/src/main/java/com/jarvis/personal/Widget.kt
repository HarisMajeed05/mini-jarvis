package com.jarvis.personal

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

// The home screen button. One tap opens Jarvis and starts listening.
class JarvisWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val open = Intent(context, MainActivity::class.java)
            .putExtra("mic", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(context, 77, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val views = RemoteViews(context.packageName, R.layout.widget).apply { setOnClickPendingIntent(R.id.widget_root, pi) }
        for (id in ids) manager.updateAppWidget(id, views)
    }
}
