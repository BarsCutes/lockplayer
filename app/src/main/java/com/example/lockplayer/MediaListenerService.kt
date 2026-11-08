package com.example.lockplayer

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.notification.NotificationListenerService

/**
 * Сам по себе ничего не делает с уведомлениями. Нужен затем,
 * чтобы Android разрешил читать список активных плееров (доступ к уведомлениям).
 */
class MediaListenerService : NotificationListenerService() {

    private var registered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            AutoLaunch.onScreenOn(context)
        }
    }

    override fun onListenerConnected() {
        PlayerState.start(this, ComponentName(this, MediaListenerService::class.java))
        if (!registered) {
            val f = IntentFilter(Intent.ACTION_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, f)
            }
            registered = true
        }
    }

    override fun onListenerDisconnected() {
        PlayerState.stop()
        if (registered) {
            unregisterReceiver(screenReceiver)
            registered = false
        }
    }
}
