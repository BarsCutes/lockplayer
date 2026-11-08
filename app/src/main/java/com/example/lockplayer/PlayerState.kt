package com.example.lockplayer

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.URL
import kotlin.concurrent.thread

/** Хранит текущий трек и перерисовывает виджеты при любых изменениях. */
object PlayerState {

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var manager: MediaSessionManager? = null
    private var listenerComponent: ComponentName? = null
    private var controller: MediaController? = null

    private var cover: Bitmap? = null
    private var coverKey: String? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = onChanged()
        override fun onPlaybackStateChanged(state: PlaybackState?) = onChanged()
        override fun onSessionDestroyed() = pickController()
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { pickController() }

    fun start(context: Context, component: ComponentName) {
        appContext = context.applicationContext
        listenerComponent = component
        val m = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        manager = m
        try {
            m.addOnActiveSessionsChangedListener(sessionsListener, component)
        } catch (_: SecurityException) {
            return
        }
        pickController()
    }

    fun stop() {
        try {
            manager?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    fun currentController(): MediaController? = controller

    /** Вызывается при любом изменении трека/состояния (для экрана плеера). */
    var onUpdate: (() -> Unit)? = null

    fun coverBitmap(): Bitmap? = cover

    fun isPlaying(): Boolean = controller?.playbackState?.state == PlaybackState.STATE_PLAYING

    /** Текущая позиция трека в мс с учётом времени, прошедшего с последнего обновления. */
    fun positionNow(): Long {
        val st = controller?.playbackState ?: return 0L
        var p = st.position
        if (st.state == PlaybackState.STATE_PLAYING) {
            p += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        return p.coerceAtLeast(0L)
    }

    private fun pickController() {
        val m = manager ?: return
        val comp = listenerComponent ?: return
        val list = try {
            m.getActiveSessions(comp)
        } catch (_: SecurityException) {
            emptyList()
        }
        // Приоритет: тот, что играет; иначе — первый в списке
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(controllerCallback)
            controller = best
            best?.registerCallback(controllerCallback, main)
        }
        onChanged()
    }

    private fun onChanged() {
        val md = controller?.metadata
        loadCover(md)
        pushToWidgets()
    }

    // ---------- Обложка ----------

    private var lastRaw: Bitmap? = null
    private var coverTitle: String? = null
    private var loadingUri: String? = null
    private var failedAt = 0L

    private fun loadCover(md: MediaMetadata?) {
        // Временно пропал список метаданных — оставляем то, что было, чтобы обложка не мигала
        if (md == null) return
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
        val titleChanged = title != coverTitle
        coverTitle = title

        val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bmp != null) {
            val prev = lastRaw
            // Сравниваем содержимое, а не название: плеер часто сначала присылает новое название
            // со старой картинкой, а настоящую обложку — следующим обновлением.
            val same = prev != null && prev.width == bmp.width && prev.height == bmp.height &&
                (prev === bmp || prev.sameAs(bmp))
            if (!same) {
                lastRaw = bmp
                coverKey = "bmp"
                loadingUri = null
                cover = rounded(bmp)
            }
            return
        }
        val uri = md.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        if (uri == null) {
            // Картинки в этом обновлении нет: сбрасываем только если сменилась песня
            if (titleChanged) {
                cover = null
                coverKey = null
                lastRaw = null
                loadingUri = null
            }
            return
        }
        if (uri == coverKey) {
            if (cover != null) return                    // уже загружена
            if (loadingUri == uri) return                // качается
            if (SystemClock.elapsedRealtime() - failedAt < 5000) return // недавно не вышло
        }
        coverKey = uri
        loadingUri = uri
        lastRaw = null
        if (titleChanged) cover = null
        val ctx = appContext ?: return
        thread {
            val loaded = try {
                if (uri.startsWith("http")) {
                    URL(uri).openStream().use { BitmapFactory.decodeStream(it) }
                } else {
                    ctx.contentResolver.openInputStream(Uri.parse(uri))
                        ?.use { BitmapFactory.decodeStream(it) }
                }
            } catch (_: Exception) {
                null
            }
            main.post {
                if (loadingUri == uri) loadingUri = null
                // трек мог смениться, пока качалось
                if (coverKey == uri) {
                    if (loaded != null) {
                        cover = rounded(loaded)
                        pushToWidgets()
                    } else {
                        failedAt = SystemClock.elapsedRealtime()
                    }
                }
            }
        }
    }

    /** Квадрат по центру со скруглёнными углами, не больше 600 px (лимит RemoteViews). */
    private fun rounded(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        val size = minOf(side, 600)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val r = size * 0.12f
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val left = (src.width - side) / 2
        val top = (src.height - side) / 2
        canvas.drawBitmap(
            src,
            Rect(left, top, left + side, top + side),
            Rect(0, 0, size, size),
            paint
        )
        return out
    }

    // ---------- Виджеты ----------

    fun pushToWidgets() {
        onUpdate?.invoke()
        val ctx = appContext ?: return
        val awm = AppWidgetManager.getInstance(ctx)
        val ids = awm.getAppWidgetIds(ComponentName(ctx, PlayerWidgetProvider::class.java))
        if (ids.isEmpty()) return
        for (id in ids) awm.updateAppWidget(id, buildViews(ctx))
    }

    fun buildViews(ctx: Context): android.widget.RemoteViews {
        val v = android.widget.RemoteViews(ctx.packageName, R.layout.widget_player)
        val c = controller
        val md = c?.metadata
        val st = c?.playbackState

        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        v.setTextViewText(R.id.title, title ?: ctx.getString(R.string.idle_title))
        v.setTextViewText(R.id.artist, if (title == null) ctx.getString(R.string.idle_artist) else (artist ?: ""))

        val cov = cover
        if (cov != null) v.setImageViewBitmap(R.id.cover, cov)
        else v.setImageViewResource(R.id.cover, R.drawable.cover_placeholder)

        val playing = st?.state == PlaybackState.STATE_PLAYING
        v.setImageViewResource(R.id.btn_play, if (playing) R.drawable.ic_pause else R.drawable.ic_play)

        // Время и полоска
        val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        var position = st?.position ?: 0L
        if (playing && st != null) {
            // позиция в состоянии устарела — дорисовываем, сколько прошло с момента обновления
            position += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        if (duration > 0) position = position.coerceIn(0L, duration) else position = position.coerceAtLeast(0L)

        v.setChronometer(R.id.elapsed, SystemClock.elapsedRealtime() - position, null, playing)
        v.setTextViewText(R.id.duration, if (duration > 0) fmt(duration) else "")
        val progress = if (duration > 0) (position * 1000 / duration).toInt() else 0
        v.setProgressBar(R.id.progress, 1000, progress, false)

        v.setOnClickPendingIntent(R.id.btn_prev, ControlReceiver.pending(ctx, ControlReceiver.ACTION_PREV))
        v.setOnClickPendingIntent(R.id.btn_play, ControlReceiver.pending(ctx, ControlReceiver.ACTION_PLAY_PAUSE))
        v.setOnClickPendingIntent(R.id.btn_next, ControlReceiver.pending(ctx, ControlReceiver.ACTION_NEXT))
        return v
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
