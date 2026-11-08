package com.example.lockplayer

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.widget.ScrollView
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
    private lateinit var lyricsScroll: ScrollView
    private lateinit var lyricsList: android.widget.LinearLayout
    private var lyricLines: List<TextView> = emptyList()
    private var lyricsMode = false
    private var lyricsIdx = -2
    private var lyricsDirty = true

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
        // Рисуем под статус-баром и нижней панелью, чтобы фон тянулся на весь экран
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = 0
        window.navigationBarColor = 0
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        content = findViewById(R.id.content)
        lyricsScroll = findViewById(R.id.lyrics_scroll)
        lyricsList = findViewById(R.id.lyrics_list)
        findViewById<View>(R.id.lyrics_btn).setOnClickListener { setLyricsMode(!lyricsMode) }
        lyricsList.setOnClickListener { setLyricsMode(false) }
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
            if (t != shownTitle) {
                lyricsDirty = true
                lyricsIdx = -2
                Lyrics.request(t, md?.getString(MediaMetadata.METADATA_KEY_ARTIST), md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L) {
                    lyricsDirty = true
                }
            }
            shownTitle = t
            shownCover = bmp
            val newTitle = t ?: getString(R.string.idle_title)
            val newArtist = if (t == null) getString(R.string.idle_artist)
            else (md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "")
            applyTrack(newTitle, newArtist, bmp)
            if (changed) {
                // Плавное проявление новой обложки (без отложенных действий, чтобы не зависала невидимой)
                cover.animate().cancel()
                cover.alpha = 0.2f
                cover.animate().alpha(1f).setDuration(250).start()
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

        refreshLyrics(pos)
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

    private fun setLyricsMode(on: Boolean) {
        lyricsMode = on
        lyricsScroll.visibility = if (on) View.VISIBLE else View.GONE
        cover.visibility = if (on) View.INVISIBLE else View.VISIBLE
        lyricsDirty = true
        if (on) refreshLyrics(PlayerState.positionNow())
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun refreshLyrics(pos: Long) {
        if (!lyricsMode) return
        val idx = if (Lyrics.status == Lyrics.Status.FOUND && Lyrics.synced) Lyrics.currentIndex(pos) else -1

        if (lyricsDirty) {
            lyricsDirty = false
            lyricsIdx = idx
            rebuildLyrics()
            highlight(idx, animate = false)
            return
        }
        if (idx != lyricsIdx) {
            lyricsIdx = idx
            highlight(idx, animate = true)
        }
    }

    private fun rebuildLyrics() {
        lyricsList.removeAllViews()
        val texts: List<String> = when (Lyrics.status) {
            Lyrics.Status.LOADING -> listOf("Ищу текст…")
            Lyrics.Status.FOUND -> Lyrics.lines.map { it.text.ifEmpty { " " } }
            else -> listOf("Текст не найден")
        }
        lyricLines = texts.map { line ->
            TextView(this).apply {
                text = line
                textSize = 24f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(7), 0, dp(7))
                alpha = 0.35f
                scaleX = 0.88f
                scaleY = 0.88f
            }.also { lyricsList.addView(it) }
        }
        lyricsScroll.scrollTo(0, 0)
    }

    /** Плавно подсвечивает текущую строку: размер и яркость меняются по дистанции от неё. */
    private fun highlight(idx: Int, animate: Boolean) {
        val synced = Lyrics.status == Lyrics.Status.FOUND && Lyrics.synced
        for ((i, v) in lyricLines.withIndex()) {
            val (a, sc) = when {
                Lyrics.status != Lyrics.Status.FOUND || !synced -> 0.95f to 1f
                idx < 0 -> 0.5f to 0.9f
                i == idx -> 1f to 1f
                else -> when (kotlin.math.abs(i - idx)) {
                    1 -> 0.55f to 0.9f
                    2 -> 0.4f to 0.88f
                    else -> 0.28f to 0.86f
                } 
            }
            if (animate) {
                v.animate().alpha(a).scaleX(sc).scaleY(sc).setDuration(350)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            } else {
                v.alpha = a; v.scaleX = sc; v.scaleY = sc
            }
        }
        if (synced && idx in lyricLines.indices) {
            lyricsList.post {
                val v = lyricLines[idx]
                val target = (v.top + v.height / 2 - lyricsScroll.height / 3).coerceAtLeast(0)
                if (animate) {
                    android.animation.ObjectAnimator.ofInt(lyricsScroll, "scrollY", lyricsScroll.scrollY, target).apply {
                        duration = 500
                        interpolator = android.view.animation.DecelerateInterpolator(1.5f)
                        start()
                    }
                } else lyricsScroll.scrollTo(0, target)
            }
        }
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
