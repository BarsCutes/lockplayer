package com.example.lockplayer

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** При включении экрана на заблокированном телефоне во время музыки пытается открыть плеер. */
object AutoLaunch {
    private const val CHANNEL = "lockplayer_auto"
    private const val NOTIF_ID = 42

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("auto", false)

    fun setEnabled(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putBoolean("auto", v).apply()
    }

    fun onScreenOn(ctx: Context) {
        if (!isEnabled(ctx)) return
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isKeyguardLocked || !PlayerState.isPlaying()) return

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Плеер на экране блокировки", NotificationManager.IMPORTANCE_HIGH)
        )
        val pi = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle("Плеер")
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .setTimeoutAfter(4000)
            .build()
        nm.notify(NOTIF_ID, n)
    }
}
