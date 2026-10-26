package com.pandxmax.musicmanager

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

object PlayerManager {
    private var controller: MediaController? = null
    /** Lo que el usuario pidió antes de que el reproductor estuviera listo. */
    private var pending: ((MediaController) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    var isPlaying by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var currentPath by mutableStateOf<String?>(null)
    var queue by mutableStateOf<List<String>>(emptyList())
    var shuffle by mutableStateOf(false)
    var repeat by mutableStateOf(0)   // 0 no, 1 todo, 2 una

    fun connect(ctx: Context) {
        if (controller != null) return
        val app = ctx.applicationContext
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val f = MediaController.Builder(app, token).buildAsync()
        f.addListener({
            try {
                controller = f.get()
                attach()
            } catch (_: Exception) {
            }
        }, ContextCompat.getMainExecutor(app))
    }

    private fun attach() {
        val c = controller ?: return
        c.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { sync() }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { sync(); tint() }
            override fun onPlaybackStateChanged(playbackState: Int) { sync() }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { sync() }
        })
        sync()
        pending?.let { pending = null; it(c) }
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else pending = block
    }

    private fun sync() {
        val c = controller ?: return
        isPlaying = c.isPlaying
        currentPath = c.currentMediaItem?.mediaId
        duration = if (c.duration > 0) c.duration else 0L
        queue = List(c.mediaItemCount) { c.getMediaItemAt(it).mediaId }
        shuffle = c.shuffleModeEnabled
        repeat = when (c.repeatMode) { Player.REPEAT_MODE_ALL -> 1; Player.REPEAT_MODE_ONE -> 2; else -> 0 }
    }

    fun tick() {
        val c = controller ?: return
        position = c.currentPosition.coerceAtLeast(0L)
        if (duration == 0L && c.duration > 0) duration = c.duration
    }

    private fun tint() {
        val p = currentPath
        if (p == null || !Store.coverTint) { Store.dynamicAccent = null; return }
        scope.launch {
            val col = withContext(Dispatchers.IO) {
                val bmp = Covers.thumb(p, 120)
                if (bmp == null) null else {
                    val pal = Palette.from(bmp).generate()
                    val c = pal.getVibrantColor(pal.getMutedColor(pal.getDominantColor(0)))
                    if (c == 0) null else c
                }
            }
            Store.dynamicAccent = col
        }
    }

    private fun item(path: String): MediaItem {
        val f = File(path)
        val base = f.nameWithoutExtension
        val parts = base.split(" - ", limit = 2)
        val md = MediaMetadata.Builder()
            .setTitle(if (parts.size == 2) parts[1] else base)
            .setArtist(if (parts.size == 2) parts[0] else null)
        // La portada no se decodifica aquí (era lo que hacía lenta la reproducción):
        // el reproductor la lee del propio archivo al empezar a sonar.
        return MediaItem.Builder().setMediaId(path).setUri(Uri.fromFile(f)).setMediaMetadata(md.build()).build()
    }

    fun playList(paths: List<String>, index: Int = 0) {
        if (paths.isEmpty()) return
        val i = index.coerceIn(0, paths.size - 1)
        currentPath = paths[i]
        withController { c ->
            // Primero suena la canción elegida; el resto de la cola se añade después.
            c.setMediaItem(item(paths[i]))
            c.prepare()
            c.play()
            if (paths.size > 1) scope.launch {
                val before = withContext(Dispatchers.Default) { paths.subList(0, i).map { item(it) } }
                val after = withContext(Dispatchers.Default) { paths.subList(i + 1, paths.size).map { item(it) } }
                if (c.currentMediaItem?.mediaId != paths[i]) return@launch
                if (after.isNotEmpty()) c.addMediaItems(after)
                if (before.isNotEmpty()) c.addMediaItems(0, before)
            }
        }
    }

    /** Reproduce (o pausa si ya suena) esta canción, con el resto de la biblioteca como cola. */
    fun playOrToggle(path: String, library: List<String>) {
        if (currentPath == path && controller?.currentMediaItem?.mediaId == path) { toggle(); return }
        val idx = library.indexOf(path)
        if (idx >= 0) playList(library, idx) else playList(listOf(path), 0)
    }

    fun enqueue(paths: List<String>) {
        if (paths.isEmpty()) return
        withController { c ->
            val items = paths.map { item(it) }
            val wasEmpty = c.mediaItemCount == 0
            c.addMediaItems(items)
            if (wasEmpty) { c.prepare(); c.play() }
        }
    }

    fun playIndex(i: Int) { controller?.let { it.seekTo(i, 0L); it.play() } }
    fun removeAt(i: Int) { controller?.removeMediaItem(i) }
    fun clear() { controller?.let { it.stop(); it.clearMediaItems() } }
    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun next() { controller?.seekToNext() }
    fun prev() { controller?.let { if (it.currentPosition > 3000) it.seekTo(0) else it.seekToPrevious() } }
    fun stop() { controller?.let { it.stop(); it.seekTo(0, 0L) } }
    fun seek(ms: Long) { controller?.seekTo(ms) }
    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }
}
