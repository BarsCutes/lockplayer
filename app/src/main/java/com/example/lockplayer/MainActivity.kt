package com.example.lockplayer

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var autoBtn: Button
    private lateinit var closeBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        status = TextView(this).apply { textSize = 16f }
        root.addView(status)

        fun button(text: String, onClick: () -> Unit): Button {
            val b = Button(this).apply {
                this.text = text
                setOnClickListener { onClick() }
            }
            root.addView(b)
            return b
        }

        button("1. Доступ к уведомлениям") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        button("2. Разрешить показ уведомлений") {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        button("3. Разрешить полноэкранный показ") {
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                            .setData(Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                }
            }
        }
        autoBtn = button("") {
            AutoLaunch.setEnabled(this, !AutoLaunch.isEnabled(this))
            updateAutoText()
        }
        closeBtn = button("") {
            AutoLaunch.setAutoClose(this, !AutoLaunch.isAutoClose(this))
            updateAutoText()
        }
        button("Не давать системе убивать приложение") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        button("Открыть плеер (проверка)") {
            startActivity(Intent(this, PlayerActivity::class.java))
        }

        root.addView(TextView(this).apply {
            textSize = 14f
            setPadding(0, pad, 0, 0)
            text = "Плитку «Плеер» добавь в шторку: потяни шторку, значок карандаша, найди «Плеер» и перетащи наверх.\n" +
                "Автопоказ: когда играет музыка и ты включаешь экран, плеер сам откроется поверх блокировки (если Android разрешит)."
        })
        setContentView(root)
    }

    private fun updateAutoText() {
        closeBtn.text = "Закрывать плеер после минуты паузы: " + if (AutoLaunch.isAutoClose(this)) "ВКЛ" else "ВЫКЛ"
        autoBtn.text = "Автопоказ при включении экрана: " + if (AutoLaunch.isEnabled(this)) "ВКЛ" else "ВЫКЛ"
    }

    override fun onResume() {
        super.onResume()
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        status.text = if (enabled) "Доступ к уведомлениям выдан ✅" else "Доступ к уведомлениям не выдан ❌"
        updateAutoText()
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
