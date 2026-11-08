package com.example.lockplayer

import android.app.Activity
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView

/** Большой плеер. Умеет показываться поверх экрана блокировки. */
class PlayerActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var cover: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var seek: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var durationView: TextView
    private lateinit var play: ImageButton
    private lateinit var btnA: ImageButton
    private lateinit var btnB: ImageButton
    private var dragging = false

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContentView(R.layout.activity_player)

        cover = findViewById(R.id.cover)
        title = findViewById(R.id.title)
        artist = findViewById(R.id.artist)
        seek = findViewById(R.id.seek)
        elapsed = findViewById(R.id.elapsed)
        durationView = findViewById(R.id.duration)
        play = findViewById(R.id.btn_play)
        btnA = findViewById(R.id.btn_a)
        btnB = findViewById(R.id.btn_b)

        findViewById<ImageButton>(R.id.btn_prev).setOnClickListener {
            PlayerState.currentController()?.transportControls?.skipToPrevious()
        }
        findViewById<ImageButton>(R.id.btn_next).setOnClickListener {
            PlayerState.currentController()?.transportControls?.skipToNext()
        }
        play.setOnClickListener {
            val t = PlayerState.currentController()?.transportControls ?: return@setOnClickListener
            if (PlayerState.isPlaying()) t.pause() else t.play()
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar?) {
                dragging = true
            }

            override fun onStopTrackingTouch(s: SeekBar?) {
                dragging = false
                val c = PlayerState.currentController() ?: return
                val dur = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                if (dur > 0) c.transportControls.seekTo(dur * (s?.progress ?: 0) / 1000)
            }
        })
    }

    override fun onStart() {
        super.onStart()
        PlayerState.onUpdate = { refresh() }
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        PlayerState.onUpdate = null
        handler.removeCallbacks(ticker)
    }

    private fun refresh() {
        val c = PlayerState.currentController()
        val md = c?.metadata
        val t = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        title.text = t ?: getString(R.string.idle_title)
        artist.text = if (t == null) getString(R.string.idle_artist)
        else (md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "")

        val bmp = PlayerState.coverBitmap()
        if (bmp != null) cover.setImageBitmap(bmp) else cover.setImageResource(R.drawable.cover_placeholder)

        play.setImageResource(if (PlayerState.isPlaying()) R.drawable.ic_pause else R.drawable.ic_play)

        val dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val pos = if (dur > 0) PlayerState.positionNow().coerceAtMost(dur) else PlayerState.positionNow()
        durationView.text = if (dur > 0) fmt(dur) else ""
        elapsed.text = fmt(pos)
        if (!dragging) seek.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0

        // Дополнительные кнопки самого плеера (лайк, перемешать и т.п.)
        val actions: List<PlaybackState.CustomAction> = c?.playbackState?.customActions ?: emptyList()
        bindAction(btnA, actions.getOrNull(0))
        bindAction(btnB, actions.getOrNull(1))
    }

    private fun bindAction(btn: ImageButton, a: PlaybackState.CustomAction?) {
        val c = PlayerState.currentController()
        if (a == null || c == null) {
            btn.visibility = android.view.View.INVISIBLE
            return
        }
        try {
            val res = packageManager.getResourcesForApplication(c.packageName)
            val d = res.getDrawable(a.icon, null)
            d.setTint(0xFFFFFFFF.toInt())
            btn.setImageDrawable(d)
            btn.visibility = android.view.View.VISIBLE
            btn.setOnClickListener { c.transportControls.sendCustomAction(a, null) }
        } catch (_: Exception) {
            btn.visibility = android.view.View.INVISIBLE
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
