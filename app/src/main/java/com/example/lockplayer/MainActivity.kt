package com.example.lockplayer

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        status = TextView(this).apply { textSize = 16f }
        val btn = Button(this).apply {
            text = "Открыть доступ к уведомлениям"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        val help = TextView(this).apply {
            textSize = 14f
            setPadding(0, pad, 0, 0)
            text = "1. Включи переключатель «Lock Player» в списке.\n" +
                "2. Заблокируй телефон → долгое нажатие на экран блокировки → виджеты → добавь «Плеер».\n" +
                "3. Включи музыку в Яндекс Музыке."
        }
        root.addView(status)
        root.addView(btn)
        root.addView(help)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        status.text = if (enabled) "Доступ выдан ✅" else "Доступ не выдан ❌"
        if (enabled) {
            try {
                NotificationListenerService.requestRebind(
                    ComponentName(this, MediaListenerService::class.java)
                )
            } catch (_: Exception) {
            }
        }
    }
}
