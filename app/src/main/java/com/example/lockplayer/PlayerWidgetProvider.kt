package com.example.lockplayer

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService

class PlayerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Если система остановила слушатель — просим запустить заново
        try {
            NotificationListenerService.requestRebind(
                ComponentName(context, MediaListenerService::class.java)
            )
        } catch (_: Exception) {
        }
        val views = PlayerState.buildViews(context)
        for (id in ids) manager.updateAppWidget(id, views)
    }
}
