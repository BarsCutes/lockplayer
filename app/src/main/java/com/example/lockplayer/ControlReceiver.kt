package com.example.lockplayer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val c = PlayerState.currentController() ?: return
        val t = c.transportControls
        when (intent.action) {
            ACTION_PREV -> t.skipToPrevious()
            ACTION_NEXT -> t.skipToNext()
            ACTION_PLAY_PAUSE -> {
                if (c.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) t.pause()
                else t.play()
            }
        }
    }

    companion object {
        const val ACTION_PREV = "com.example.lockplayer.PREV"
        const val ACTION_NEXT = "com.example.lockplayer.NEXT"
        const val ACTION_PLAY_PAUSE = "com.example.lockplayer.PLAY_PAUSE"

        fun pending(ctx: Context, action: String): PendingIntent {
            val i = Intent(ctx, ControlReceiver::class.java).setAction(action)
            return PendingIntent.getBroadcast(
                ctx, action.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
