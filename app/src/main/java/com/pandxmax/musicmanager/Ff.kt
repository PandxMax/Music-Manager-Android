package com.pandxmax.musicmanager

import android.content.Context
import java.io.File
import kotlin.math.pow

/** ffmpeg que ya trae la app (el mismo que usa la descarga de YouTube). */
object Ff {
    private fun bin(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")

    private fun libPath(ctx: Context): String {
        val pk = File(File(ctx.noBackupFilesDir, "youtubedl-android"), "packages")
        return listOf("python", "ffmpeg").joinToString(":") { File(pk, it).absolutePath + "/usr/lib" }
    }

    @Volatile private var current: Process? = null

    /**
     * Ejecuta ffmpeg. totalSec sirve para calcular el progreso (0–100).
     * Devuelve null si todo fue bien, o el texto del error.
     */
    fun run(ctx: Context, args: List<String>, totalSec: Double, onProgress: (Int) -> Unit = {}): String? {
        Ytdl.prepare(ctx)
        val cmd = ArrayList<String>()
        cmd.add(bin(ctx).absolutePath)
        cmd.addAll(listOf("-hide_banner", "-nostdin", "-y", "-progress", "pipe:1", "-nostats", "-loglevel", "error"))
        cmd.addAll(args)
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = libPath(ctx)
        pb.environment()["TMPDIR"] = ctx.cacheDir.absolutePath
        val p = try { pb.start() } catch (e: Throwable) { return "No se pudo iniciar ffmpeg: ${e.message}" }
        current = p
        val tail = ArrayDeque<String>()
        val re = Regex("out_time_(?:us|ms)=(\\d+)")
        try {
            p.inputStream.bufferedReader().forEachLine { line ->
                val m = re.find(line)
                if (m != null) {
                    val sec = (m.groupValues[1].toLongOrNull() ?: 0L) / 1e6
                    if (totalSec > 0) onProgress((sec * 100 / totalSec).toInt().coerceIn(0, 99))
                } else if (line.isNotBlank() && !line.substringBefore('=').matches(Regex("[a-z_0-9]+"))) {
                    tail.addLast(line); if (tail.size > 6) tail.removeFirst()
                }
            }
        } catch (_: Throwable) {
        }
        val rc = p.waitFor()
        current = null
        if (rc != 0) return tail.joinToString("\n").ifBlank { "ffmpeg terminó con el código $rc" }
        onProgress(100)
        return null
    }

    fun cancel() { try { current?.destroy() } catch (_: Throwable) {} }
}

// ------------------------------------------------------------------ Ajustes de la canción (igual que en Windows)

data class FxParams(
    val bass: Int = 0, val mid: Int = 0, val treble: Int = 0, val volume: Int = 0, val normalize: Boolean = false,
    val tempo: Double = 1.0, val pitch: Int = 0, val rate: Double = 1.0,
    val trimStart: Double = 0.0, val trimEnd: Double = 0.0, val loops: Int = 1, val reverse: Boolean = false,
    val echo: Int = 0, val reverb: Int = 0, val width: Int = 100, val mono: Boolean = false, val karaoke: Boolean = false,
    val fadeIn: Double = 0.0, val fadeOut: Double = 0.0
) {
    fun isDefault() = this == FxParams()
}

val FX_PRESETS = listOf(
    "Sin cambios" to FxParams(),
    "Bajos potentes" to FxParams(bass = 9, treble = 1, volume = -3),
    "Voz clara" to FxParams(bass = -4, mid = 4, treble = 3),
    "Agudos brillantes" to FxParams(treble = 8, bass = -2),
    "Nightcore (más rápida y aguda)" to FxParams(rate = 1.25),
    "Slowed + reverb" to FxParams(rate = 0.85, reverb = 55, bass = 3),
    "Más lenta (mismo tono)" to FxParams(tempo = 0.8),
    "Más rápida (mismo tono)" to FxParams(tempo = 1.2),
    "Karaoke (quitar la voz)" to FxParams(karaoke = true),
    "Sonido de radio vieja" to FxParams(bass = -12, treble = -6, mid = 6, mono = true),
)

/** Códec de salida según la extensión (se conserva el formato) y si admite portada. */
val FX_CODECS: Map<String, Pair<List<String>, Boolean>> = mapOf(
    "mp3" to (listOf("-c:a", "libmp3lame", "-b:a", "320k", "-id3v2_version", "3") to true),
    "m4a" to (listOf("-c:a", "aac", "-b:a", "256k") to true),
    "mp4" to (listOf("-c:a", "aac", "-b:a", "256k") to true),
    "aac" to (listOf("-c:a", "aac", "-b:a", "256k") to false),
    "flac" to (listOf("-c:a", "flac") to true),
    "ogg" to (listOf("-c:a", "libvorbis", "-q:a", "8") to false),
    "opus" to (listOf("-c:a", "libopus", "-b:a", "192k") to false),
    "wav" to (listOf("-c:a", "pcm_s16le") to false),
)

object Fx {
    private fun f2(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
    private fun f3(v: Double) = String.format(java.util.Locale.US, "%.3f", v)
    private fun f5(v: Double) = String.format(java.util.Locale.US, "%.5f", v)

    private fun atempo(t0: Double): List<String> {
        var t = t0
        val out = ArrayList<String>()
        while (t > 2.0) { out.add("atempo=2.0"); t /= 2.0 }
        while (t < 0.5) { out.add("atempo=0.5"); t /= 0.5 }
        if (kotlin.math.abs(t - 1.0) > 1e-4) out.add("atempo=${f5(t)}")
        return out
    }

    /** Cadena de filtros y duración estimada de la salida. */
    fun filters(q: FxParams, sr0: Int, dur: Double): Pair<String, Double> {
        val sr = if (sr0 > 0) sr0 else 44100
        val f = ArrayList<String>()
        var seg = dur
        if (q.trimStart > 0 || q.trimEnd > 0) {
            val a = q.trimStart; val b = q.trimEnd
            f.add("atrim=start=${f3(a)}" + if (b > a) ":end=${f3(b)}" else ""); f.add("asetpts=PTS-STARTPTS")
            seg = (if (b > a) b else dur) - a
        }
        if (q.loops > 1) { f.add("aloop=loop=${q.loops - 1}:size=2000000000"); seg *= q.loops }
        if (q.reverse) f.add("areverse")
        val ratio = 2.0.pow(q.pitch / 12.0)
        val mult = ratio * q.rate
        val tempo = q.tempo / ratio
        if (kotlin.math.abs(mult - 1.0) > 1e-4) { f.add("asetrate=${f2(sr * mult)}"); f.add("aresample=$sr") }
        f.addAll(atempo(tempo))
        val speed = q.tempo * q.rate
        val outDur = if (speed > 0) seg / speed else seg
        if (q.bass != 0) f.add("bass=g=${q.bass}:f=110:w=0.8")
        if (q.mid != 0) f.add("equalizer=f=1000:t=q:w=0.7:g=${q.mid}")
        if (q.treble != 0) f.add("treble=g=${q.treble}:f=3500:w=0.8")
        if (q.karaoke) f.add("pan=stereo|c0=c0-c1|c1=c1-c0")
        if (q.width != 100 && !q.mono) f.add("extrastereo=m=${f2(q.width / 100.0)}")
        if (q.mono) f.add("pan=stereo|c0=0.5*c0+0.5*c1|c1=0.5*c0+0.5*c1")
        if (q.echo > 0) {
            val e = q.echo / 100.0
            f.add("aecho=0.8:${f2(0.9 - 0.3 * e)}:${(250 + 350 * e).toInt()}:${f2(0.25 + 0.4 * e)}")
        }
        if (q.reverb > 0) {
            val r = q.reverb / 100.0
            f.add("aecho=0.8:${f2(0.7 + 0.15 * r)}:40|70|110|160:${f2(0.35 * r + .05)}|${f2(0.3 * r + .04)}|${f2(0.25 * r + .03)}|${f2(0.2 * r + .02)}")
        }
        if (q.volume != 0) f.add("volume=${q.volume}dB")
        if (q.normalize) f.add("loudnorm=I=-14:TP=-1.5:LRA=11")
        if (q.fadeIn > 0) f.add("afade=t=in:st=0:d=${f2(q.fadeIn)}")
        if (q.fadeOut > 0 && outDur > q.fadeOut) f.add("afade=t=out:st=${f2(outDur - q.fadeOut)}:d=${f2(q.fadeOut)}")
        f.add("aresample=$sr")
        return f.joinToString(",") to outDur
    }

    /** Argumentos de ffmpeg. preview = segundos de muestra (sale en WAV) o 0 para el archivo final. */
    fun args(src: String, dst: String, q: FxParams, sr: Int, dur: Double, preview: Int = 0, offset: Double = 0.0): List<String> {
        val flt = filters(q, sr, dur).first
        val a = ArrayList<String>()
        if (preview > 0 && offset > 0 && !q.reverse && q.trimStart == 0.0 && q.trimEnd == 0.0 && q.loops <= 1) {
            a.addAll(listOf("-ss", f2(offset)))
        }
        a.addAll(listOf("-i", src))
        if (preview > 0) {
            a.addAll(listOf("-vn", "-af", flt, "-t", "$preview", "-ac", "2", "-ar", "44100", "-c:a", "pcm_s16le", dst))
        } else {
            val ext = File(dst).extension.lowercase()
            val (codec, cover) = FX_CODECS[ext] ?: FX_CODECS.getValue("mp3")
            a.addAll(listOf("-map", "0:a:0"))
            if (cover) a.addAll(listOf("-map", "0:v?", "-c:v", "copy", "-disposition:v", "attached_pic"))
            a.addAll(listOf("-map_metadata", "0", "-af", flt))
            a.addAll(codec)
            a.add(dst)
        }
        return a
    }

    fun uniqueName(f: File, suffix: String = " (editada)", ext: String = f.extension): File {
        val base = f.nameWithoutExtension
        var c = File(f.parentFile, "$base$suffix.$ext")
        var i = 2
        while (c.exists()) { c = File(f.parentFile, "$base$suffix $i.$ext"); i++ }
        return c
    }
}

// ------------------------------------------------------------------ Convertir formato

data class ConvFormat(val label: String, val ext: String, val args: List<String>)

val CONVERT_FORMATS = listOf(
    ConvFormat("MP3 320 kbps", "mp3", listOf("-c:a", "libmp3lame", "-b:a", "320k", "-id3v2_version", "3")),
    ConvFormat("MP3 192 kbps", "mp3", listOf("-c:a", "libmp3lame", "-b:a", "192k", "-id3v2_version", "3")),
    ConvFormat("M4A (AAC 256 kbps)", "m4a", listOf("-c:a", "aac", "-b:a", "256k")),
    ConvFormat("FLAC (sin pérdida)", "flac", listOf("-c:a", "flac")),
    ConvFormat("OPUS 192 kbps", "opus", listOf("-c:a", "libopus", "-b:a", "192k")),
    ConvFormat("OGG Vorbis", "ogg", listOf("-c:a", "libvorbis", "-q:a", "8")),
    ConvFormat("WAV", "wav", listOf("-c:a", "pcm_s16le")),
)

fun convertArgs(src: File, dst: File, fmt: ConvFormat): List<String> {
    val a = arrayListOf("-i", src.absolutePath, "-map", "0:a:0")
    if (fmt.ext in setOf("mp3", "m4a", "flac")) a.addAll(listOf("-map", "0:v?", "-c:v", "copy", "-disposition:v", "attached_pic"))
    a.addAll(listOf("-map_metadata", "0"))
    a.addAll(fmt.args)
    a.add(dst.absolutePath)
    return a
}
