package com.example.lockplayer

import android.content.ComponentName
import android.service.notification.NotificationListenerService

/**
 * Сам по себе ничего не делает с уведомлениями. Нужен только затем,
 * чтобы Android разрешил читать список активных плееров (доступ к уведомлениям).
 */
class MediaListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        PlayerState.start(this, ComponentName(this, MediaListenerService::class.java))
    }

    override fun onListenerDisconnected() {
        PlayerState.stop()
    }
}
