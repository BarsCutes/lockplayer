package com.example.lockplayer

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.SystemClock
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.Shader
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs

/** Большой плеер. Показывается поверх экрана блокировки. Свайп вверх — разблокировать, вниз — закрыть. */
class PlayerActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var content: View
    private lateinit var backdrop: ImageView
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
    private var shownCover: Bitmap? = null
    private var shownTitle: String? = null
    private lateinit var gestures: GestureDetector
    private lateinit var audio: AudioManager
    private lateinit var volume: SeekBar
    private var pausedSince = 0L

    // Экран погас — закрываем, чтобы при следующем включении плеер открылся заново
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            finish()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContentView(R.layout.activity_player)
        window.statusBarColor = 0
        window.navigationBarColor = 0

        content = findViewById(R.id.content)
        AutoLaunch.cancelNotification(this) // убираем мелькнувшее уведомление
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        volume = findViewById(R.id.volume)
        volume.max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volume.progressTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        volume.thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        volume.progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        backdrop = findViewById(R.id.backdrop)
        cover = findViewById(R.id.cover)
        title = findViewById(R.id.title)
        artist = findViewById(R.id.artist)
        seek = findViewById(R.id.seek)
        elapsed = findViewById(R.id.elapsed)
        durationView = findViewById(R.id.duration)
        play = findViewById(R.id.btn_play)
        btnA = findViewById(R.id.btn_a)
        btnB = findViewById(R.id.btn_b)

        backdrop.setRenderEffect(RenderEffect.createBlurEffect(70f, 70f, Shader.TileMode.CLAMP))
        title.isSelected = true // включает бегущую строку

        // Белая тонкая полоска вместо стандартной
        val white = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        seek.progressTintList = white
        seek.thumbTintList = white
        seek.progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
        play.setColorFilter(0xFF12161C.toInt())

        val prev = findViewById<ImageButton>(R.id.btn_prev)
        val next = findViewById<ImageButton>(R.id.btn_next)
        prev.setOnClickListener { PlayerState.currentController()?.transportControls?.skipToPrevious() }
        next.setOnClickListener { PlayerState.currentController()?.transportControls?.skipToNext() }
        play.setOnClickListener {
            val t = PlayerState.currentController()?.transportControls ?: return@setOnClickListener
            if (PlayerState.isPlaying()) t.pause() else t.play()
        }
        // Нажатие на обложку = плей/пауза
        cover.setOnClickListener { play.performClick() }
        for (b in listOf(prev, next, play, btnA, btnB)) pressEffect(b)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = currentDuration()
                    if (dur > 0) elapsed.text = fmt(dur * p / 10000)
                }
            }

            override fun onStartTrackingTouch(s: SeekBar?) {
                dragging = true
            }

            override fun onStopTrackingTouch(s: SeekBar?) {
                dragging = false
                val dur = currentDuration()
                if (dur > 0) {
                    PlayerState.currentController()?.transportControls
                        ?.seekTo(dur * (s?.progress ?: 0) / 10000)
                }
            }
        })

        // Свайпы: вверх — разблокировать, вниз — закрыть
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || dragging) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (abs(dy) < 150 || abs(dy) < abs(dx) * 1.5f) return false
                if (dy < 0) unlock() else closeAnimated()
                return true
            }
        })

        // Плавное появление
        content.alpha = 0f
        content.translationY = 60f
        content.animate().alpha(1f).translationY(0f).setDuration(350).start()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun unlock() {
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                finish()
            }
        })
    }

    private fun closeAnimated() {
        content.animate().alpha(0f).translationY(120f).setDuration(220)
            .withEndAction { finish() }.start()
    }

    private fun pressEffect(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
    }

    override fun onStart() {
        super.onStart()
        PlayerState.onUpdate = { refresh() }
        handler.post(ticker)
        val f = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOff, f)
    }

    override fun onStop() {
        super.onStop()
        PlayerState.onUpdate = null
        handler.removeCallbacks(ticker)
        try { unregisterReceiver(screenOff) } catch (_: Exception) {}
    }

    private fun currentDuration(): Long =
        PlayerState.currentController()?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

    private fun refresh() {
        val c = PlayerState.currentController()
        val md = c?.metadata
        val t = md?.getString(MediaMetadata.METADATA_KEY_TITLE)

        val bmp = PlayerState.coverBitmap()
        if (t != shownTitle || bmp !== shownCover) {
            val changed = shownTitle != null && t != shownTitle
            shownTitle = t
            shownCover = bmp
            val newTitle = t ?: getString(R.string.idle_title)
            val newArtist = if (t == null) getString(R.string.idle_artist)
            else (md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "")
            if (changed) {
                // Плавная смена трека: быстро гасим, меняем, показываем
                content.findViewById<View>(R.id.cover).animate().alpha(0f).setDuration(120).withEndAction {
                    applyTrack(newTitle, newArtist, bmp)
                    cover.animate().alpha(1f).setDuration(220).start()
                }.start()
            } else {
                applyTrack(newTitle, newArtist, bmp)
            }
        }

        play.setImageResource(if (PlayerState.isPlaying()) R.drawable.ic_pause else R.drawable.ic_play)

        val dur = currentDuration()
        val pos = if (dur > 0) PlayerState.positionNow().coerceAtMost(dur) else PlayerState.positionNow()
        durationView.text = if (dur > 0) fmt(dur) else ""
        if (!dragging) {
            elapsed.text = fmt(pos)
            seek.progress = if (dur > 0) (pos * 10000 / dur).toInt() else 0
        }

        if (!volume.isPressed) volume.progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)

        // Автозакрытие: пауза дольше минуты
        if (PlayerState.isPlaying()) pausedSince = 0L
        else {
            if (pausedSince == 0L) pausedSince = SystemClock.elapsedRealtime()
            if (AutoLaunch.isAutoClose(this) && SystemClock.elapsedRealtime() - pausedSince > 60_000) finish()
        }

        val actions: List<PlaybackState.CustomAction> = c?.playbackState?.customActions ?: emptyList()
        bindAction(btnA, actions.getOrNull(0))
        bindAction(btnB, actions.getOrNull(1))
    }

    private fun applyTrack(newTitle: String, newArtist: String, bmp: Bitmap?) {
        title.text = newTitle
        title.isSelected = true
        artist.text = newArtist
        if (bmp != null) {
            cover.setImageBitmap(bmp)
            backdrop.setImageBitmap(bmp)
        } else {
            cover.setImageResource(R.drawable.cover_placeholder)
            backdrop.setImageDrawable(null)
        }
    }

    private fun bindAction(btn: ImageButton, a: PlaybackState.CustomAction?) {
        val c = PlayerState.currentController()
        if (a == null || c == null) {
            btn.visibility = View.INVISIBLE
            return
        }
        try {
            val res = packageManager.getResourcesForApplication(c.packageName)
            val d = res.getDrawable(a.icon, null)
            d.setTint(0xFFFFFFFF.toInt())
            btn.setImageDrawable(d)
            btn.visibility = View.VISIBLE
            btn.setOnClickListener { c.transportControls.sendCustomAction(a, null) }
        } catch (_: Exception) {
            btn.visibility = View.INVISIBLE
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
