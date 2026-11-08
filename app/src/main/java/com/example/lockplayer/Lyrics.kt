package com.example.lockplayer

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/** Тексты песен из открытой базы LRCLIB (lrclib.net): без ключей, есть версии с таймкодами. */
object Lyrics {
    data class Line(val ms: Long, val text: String)

    enum class Status { IDLE, LOADING, FOUND, NONE }

    var status = Status.IDLE
    var lines: List<Line> = emptyList()
    var synced = false
    private var key: String? = null
    private val main = Handler(Looper.getMainLooper())
    private val timeRe = Regex("""\[(\d+):(\d+(?:\.\d+)?)](.*)""")

    fun request(title: String?, artist: String?, durationMs: Long, onDone: () -> Unit) {
        if (title.isNullOrBlank()) {
            key = null
            status = Status.IDLE
            lines = emptyList()
            return
        }
        val k = "$artist|$title|$durationMs"
        if (k == key) return
        key = k
        status = Status.LOADING
        lines = emptyList()
        thread {
            val result = try {
                fetch(title, artist.orEmpty(), durationMs)
            } catch (_: Exception) {
                null
            }
            main.post {
                if (key != k) return@post // трек уже сменился
                if (result == null) {
                    status = Status.NONE
                } else {
                    lines = result.first
                    synced = result.second
                    status = if (lines.isEmpty()) Status.NONE else Status.FOUND
                }
                onDone()
            }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "LockPlayer/1.0 (personal project)")
        return try {
            if (c.responseCode != 200) null else c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    /** Возвращает (строки, есть_таймкоды) или null. */
    private fun fetch(title: String, artist: String, durMs: Long): Pair<List<Line>, Boolean>? {
        val dur = durMs / 1000
        var obj: JSONObject? = null
        val exact = get(
            "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}" +
                if (dur > 0) "&duration=$dur" else ""
        )
        if (exact != null) obj = JSONObject(exact)
        if (obj == null) {
            val found = get("https://lrclib.net/api/search?q=${enc("$artist $title".trim())}")
            if (found != null) {
                val arr = JSONArray(found)
                if (arr.length() > 0) obj = arr.getJSONObject(0)
            }
        }
        if (obj == null) return null

        val syncedText = obj.optString("syncedLyrics", "")
        if (syncedText.isNotBlank() && syncedText != "null") {
            val out = ArrayList<Line>()
            for (raw in syncedText.lines()) {
                val m = timeRe.matchEntire(raw.trim()) ?: continue
                val ms = (m.groupValues[1].toLong() * 60 + m.groupValues[2].toDouble()).times(1000).toLong()
                out.add(Line(ms, m.groupValues[3].trim()))
            }
            if (out.isNotEmpty()) return out to true
        }
        val plain = obj.optString("plainLyrics", "")
        if (plain.isNotBlank() && plain != "null") {
            return plain.lines().map { Line(0, it) } to false
        }
        return null
    }

    /** Индекс строки, которая звучит сейчас (для версии с таймкодами). */
    fun currentIndex(posMs: Long): Int {
        var idx = -1
        for ((i, l) in lines.withIndex()) {
            if (l.ms <= posMs + 150) idx = i else break
        }
        return idx
    }
}
