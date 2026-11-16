package com.pandxmax.musicmanager

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.LruCache
import java.io.File
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * La «onda» de la canción para la barra de avance. En vez de decodificar todo el archivo,
 * salta a 90 puntos de la canción y mide un pedacito en cada uno (tarda una fracción de segundo).
 */
object Wave {
    private const val BARS = 90
    private val mem = LruCache<String, FloatArray>(40)
    private val dir by lazy { File(Store.app.cacheDir, "waves").apply { mkdirs() } }

    private fun key(f: File) = "${f.absolutePath}|${f.lastModified()}|${f.length()}"

    fun peek(path: String): FloatArray? = mem.get(key(File(path)))

    fun get(path: String): FloatArray? {
        val f = File(path)
        val k = key(f)
        mem.get(k)?.let { return it }
        val df = File(dir, Integer.toHexString(k.hashCode()) + "_" + k.length + ".w")
        if (df.isFile && df.length() == BARS.toLong()) {
            val b = df.readBytes()
            val a = FloatArray(BARS) { (b[it].toInt() and 0xFF) / 255f }
            mem.put(k, a); return a
        }
        val a = try { compute(f) } catch (e: Throwable) { null } ?: return null
        mem.put(k, a)
        try { df.writeBytes(ByteArray(BARS) { (a[it] * 255).toInt().coerceIn(0, 255).toByte() }) } catch (_: Throwable) {}
        return a
    }

    private fun compute(f: File): FloatArray? {
        val ex = MediaExtractor()
        ex.setDataSource(f.absolutePath)
        var track = -1
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val tf = ex.getTrackFormat(i)
            if ((tf.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; fmt = tf; break }
        }
        if (track < 0 || fmt == null) { ex.release(); return null }
        ex.selectTrack(track)
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        if (durUs <= 0) { ex.release(); return null }
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        val out = FloatArray(BARS)
        val info = MediaCodec.BufferInfo()
        try {
            for (b in 0 until BARS) {
                val t = durUs * (b + 0.5) / BARS
                ex.seekTo(t.toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                codec.flush()
                var sum = 0.0
                var cnt = 0
                var tries = 0
                var inDone = false
                while (cnt < 6000 && tries < 40) {
                    tries++
                    if (!inDone) {
                        val ii = codec.dequeueInputBuffer(5000)
                        if (ii >= 0) {
                            val buf = codec.getInputBuffer(ii)!!
                            val sz = ex.readSampleData(buf, 0)
                            if (sz < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else { codec.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val oi = codec.dequeueOutputBuffer(info, 5000)
                    if (oi >= 0) {
                        val ob = codec.getOutputBuffer(oi)
                        if (ob != null && info.size > 0) {
                            ob.position(info.offset); ob.limit(info.offset + info.size)
                            val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                            while (sb.hasRemaining() && cnt < 6000) { val v = sb.get() / 32768.0; sum += v * v; cnt++ }
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
                out[b] = if (cnt > 0) sqrt(sum / cnt).toFloat() else 0f
            }
        } finally {
            try { codec.stop() } catch (_: Throwable) {}
            codec.release(); ex.release()
        }
        val mx = out.maxOrNull() ?: 0f
        if (mx <= 0f) return null
        // un poco de compresión para que las partes suaves también se vean
        return FloatArray(BARS) { (0.12f + 0.88f * sqrt(out[it] / mx)).coerceIn(0f, 1f) }
    }
}
