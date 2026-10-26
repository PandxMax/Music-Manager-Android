package com.pandxmax.musicmanager

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

object Ytdl {
    private var ready = false

    @Synchronized
    fun prepare(ctx: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(ctx)
        FFmpeg.getInstance().init(ctx)
        ready = true
    }

    /** Portadas de SoundCloud (búsqueda sin clave, con yt-dlp). */
    fun soundcloudCovers(ctx: Context, artist: String, title: String): List<CoverCandidate> {
        val q = "$artist $title".trim()
        if (q.isEmpty()) return emptyList()
        prepare(ctx)
        val req = YoutubeDLRequest("scsearch5:$q")
        req.addOption("-J")
        req.addOption("--skip-download")
        req.addOption("--socket-timeout", "15")
        val out = YoutubeDL.getInstance().execute(req).out
        val o = org.json.JSONObject(out.substring(out.indexOf('{')))
        val ents = o.optJSONArray("entries") ?: return emptyList()
        val res = ArrayList<CoverCandidate>()
        for (i in 0 until ents.length()) {
            val e = ents.optJSONObject(i) ?: continue
            var th = e.optString("thumbnail")
            if (th.isEmpty()) {
                val ts = e.optJSONArray("thumbnails")
                if (ts != null) for (j in ts.length() - 1 downTo 0) { val u = ts.optJSONObject(j)?.optString("url") ?: ""; if (u.isNotEmpty()) { th = u; break } }
            }
            if (th.isEmpty()) continue
            val big = th.replace(Regex("-(large|t\\d+x\\d+|original|crop|badge|small|tiny|mini)\\."), "-t500x500.")
            val ar = e.optString("uploader").ifEmpty { e.optString("artist") }
            res.add(CoverCandidate(e.optString("title") + " — " + ar + " (SoundCloud)", big, th))
        }
        return res
    }

    /** Descarga el audio de un enlace de YouTube como MP3 en la carpeta indicada y devuelve los archivos nuevos. */
    fun download(ctx: Context, url: String, dir: File, kbps: Int, onProgress: (Float, String) -> Unit): List<File> {
        prepare(ctx)
        val before = dir.list()?.toSet() ?: emptySet()
        val req = YoutubeDLRequest(url)
        req.addOption("-x")
        req.addOption("--audio-format", "mp3")
        req.addOption("--audio-quality", "${kbps}K")
        req.addOption("--no-playlist")
        req.addOption("--embed-metadata")
        req.addOption("-o", dir.absolutePath + "/%(title)s.%(ext)s")
        YoutubeDL.getInstance().execute(req) { progress, _, line -> onProgress(progress, line) }
        val after = dir.list()?.toSet() ?: emptySet()
        return (after - before).filter { it.lowercase().endsWith(".mp3") }.map { File(dir, it) }
    }
}
