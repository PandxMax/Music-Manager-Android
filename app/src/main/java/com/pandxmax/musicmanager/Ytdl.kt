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

    /** Versión de yt-dlp que se está usando ahora. */
    fun version(ctx: Context): String = try { prepare(ctx); YoutubeDL.getInstance().version(ctx) ?: "?" } catch (_: Throwable) { "?" }

    /**
     * YouTube cambia a menudo y una versión vieja de yt-dlp deja de funcionar («Please sign in», «Precondition check failed»).
     * Se actualiza sola desde GitHub (como mucho cada 6 horas, o siempre si force = true).
     */
    fun update(ctx: Context, force: Boolean = false, nightly: Boolean = false): String {
        prepare(ctx)
        val prefs = Store.prefs
        val last = prefs.getLong("ytdlpCheck", 0L)
        if (!force && System.currentTimeMillis() - last < 6 * 3600_000L) return "reciente"
        return try {
            val ch = if (nightly) YoutubeDL.UpdateChannel.NIGHTLY else YoutubeDL.UpdateChannel.STABLE
            val st = YoutubeDL.getInstance().updateYoutubeDL(ctx, ch)
            prefs.edit().putLong("ytdlpCheck", System.currentTimeMillis()).apply()
            if (st == YoutubeDL.UpdateStatus.DONE) "actualizado" else "al día"
        } catch (e: Throwable) {
            "sin conexión con GitHub (${e.message?.take(60)})"
        }
    }

    private fun isYoutube(url: String) = Regex("(youtube\\.com|youtu\\.be|music\\.youtube)", RegexOption.IGNORE_CASE).containsMatchIn(url)

    private fun blocked(msg: String) = listOf("sign in", "not a bot", "precondition", "http error 400", "http error 403", "po token", "unavailable")
        .any { msg.lowercase().contains(it) }

    /** Formas distintas de pedirle el audio a YouTube; si una está bloqueada se prueba la siguiente. */
    private val CLIENTS = listOf(null, "tv,web_safari", "mweb", "android_vr,ios", "web_embedded,tv_simple")

    /**
     * Descarga el audio como MP3 en la carpeta indicada y devuelve los archivos nuevos.
     * onStatus informa de lo que está haciendo (actualizar, reintentar…).
     */
    fun download(ctx: Context, url: String, dir: File, kbps: Int, onStatus: (String) -> Unit = {}, onProgress: (Float, String) -> Unit): List<File> {
        prepare(ctx)
        onStatus("Comprobando que el componente de descargas esté al día…")
        update(ctx)
        val yt = isYoutube(url)
        val before = dir.list()?.toSet() ?: emptySet()
        var lastErr: Throwable? = null
        val attempts = if (yt) CLIENTS else listOf(null)
        var updatedNightly = false
        var i = 0
        while (i < attempts.size) {
            val client = attempts[i]
            if (i > 0) onStatus("YouTube rechazó la petición; probando otra forma de conexión (${i + 1}/${attempts.size})…")
            val req = YoutubeDLRequest(url)
            req.addOption("-x")
            req.addOption("--audio-format", "mp3")
            req.addOption("--audio-quality", "${kbps}K")
            req.addOption("--no-playlist")
            req.addOption("--embed-metadata")
            req.addOption("--no-mtime")
            req.addOption("--retries", "3")
            if (yt) {
                req.addOption("--embed-thumbnail")
                req.addOption("--convert-thumbnails", "jpg")
                req.addOption("--force-ipv4")
                if (client != null) req.addOption("--extractor-args", "youtube:player_client=$client")
            }
            req.addOption("-o", dir.absolutePath + "/%(title)s.%(ext)s")
            try {
                YoutubeDL.getInstance().execute(req) { progress, _, line -> onProgress(progress, line) }
                val after = dir.list()?.toSet() ?: emptySet()
                return (after - before).filter { it.lowercase().endsWith(".mp3") }.map { File(dir, it) }
            } catch (e: Throwable) {
                lastErr = e
                val msg = e.message ?: ""
                if (!yt || !blocked(msg)) break
                // último recurso: la versión «nightly» de yt-dlp, que recibe los arreglos para YouTube antes
                if (i == attempts.size - 1 && !updatedNightly) {
                    updatedNightly = true
                    onStatus("Probando la versión más nueva de yt-dlp (nightly)…")
                    val r = update(ctx, force = true, nightly = true)
                    if (r == "actualizado") { i = 0; continue }
                }
            }
            i++
        }
        throw lastErr ?: RuntimeException("No se pudo descargar")
    }

    /** Explicación clara del error para el usuario (el detalle técnico se muestra aparte). */
    fun friendly(msg: String): String {
        val m = msg.lowercase()
        return when {
            m.contains("sign in") || m.contains("not a bot") || m.contains("precondition") ->
                "YouTube bloqueó la descarga desde tu conexión (pide «iniciar sesión» para comprobar que no eres un robot). " +
                    "Ya probé varias formas de conexión y la versión más nueva del componente.\n\n" +
                    "Qué puedes hacer:\n• Cambia de red (de datos a Wi-Fi o al revés) y vuelve a intentarlo.\n" +
                    "• Espera unos minutos: el bloqueo suele ser temporal.\n• Busca la canción en SoundCloud y pega ese enlace."
            m.contains("private") || m.contains("members") -> "Ese vídeo es privado o solo para miembros: no se puede descargar."
            m.contains("age") && m.contains("confirm") -> "Ese vídeo tiene restricción de edad y YouTube no deja bajarlo sin iniciar sesión."
            m.contains("unable to resolve") || m.contains("network") || m.contains("timed out") ->
                "No hay conexión a internet o es muy lenta. Revisa tu conexión y vuelve a intentarlo."
            m.contains("unsupported url") -> "Ese enlace no es compatible. Usa un enlace de YouTube o SoundCloud."
            else -> "No se pudo descargar."
        }
    }
}
