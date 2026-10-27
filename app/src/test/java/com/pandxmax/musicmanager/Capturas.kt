package com.pandxmax.musicmanager

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Genera capturas de las pantallas para revisar el diseño (no es parte de la app). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
class Capturas {
    @get:Rule val rule = createComposeRule()

    private lateinit var songs: List<File>

    private fun cover(f: File, a: Int, b: Int) {
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        p.shader = LinearGradient(0f, 0f, 400f, 400f, a, b, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 400f, 400f, p)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        Store.init(ctx)
        val root = File(ctx.filesDir, "Music").apply { deleteRecursively(); mkdirs() }
        val d1 = File(root, "Bad Bunny/Un Verano Sin Ti").apply { mkdirs() }
        val d2 = File(root, "Rock clásico").apply { mkdirs() }
        val d3 = File(root, "Descargas YouTube").apply { mkdirs() }
        cover(File(d1, "cover.jpg"), 0xFFFF6B6B.toInt(), 0xFF5F27CD.toInt())
        cover(File(d2, "cover.jpg"), 0xFF1DD1A1.toInt(), 0xFF222F3E.toInt())
        val names = listOf(
            d1 to "Bad Bunny - Tití Me Preguntó.flac", d1 to "Bad Bunny - Me Porto Bonito.flac", d1 to "Bad Bunny - Ojitos Lindos (feat. Bomba Estéreo).mp3",
            d2 to "Queen - Bohemian Rhapsody.flac", d2 to "Led Zeppelin - Stairway to Heaven (Remaster).mp3", d2 to "Eagles - Hotel California.m4a",
            d3 to "Canción descargada con un nombre muy pero muy largo para ver cómo se corta.mp3", root to "Shakira - Hips Don't Lie.opus"
        )
        songs = names.map { (d, n) -> File(d, n).apply { writeBytes(ByteArray(2048)) } }
        val qs = listOf(
            Quality("FLAC", "24 bit · 96 kHz · 2950 kbps", 0), Quality("FLAC", "16 bit · 44.1 kHz · 980 kbps", 1),
            Quality("MP3", "44.1 kHz · 320 kbps", 2), Quality("FLAC", "16 bit · 44.1 kHz · 1011 kbps", 1),
            Quality("MP3", "44.1 kHz · 192 kbps", 3), Quality("M4A", "44.1 kHz · 256 kbps", 2),
            Quality("MP3", "44.1 kHz · 128 kbps", 4), Quality("OPUS", "48 kHz · 160 kbps", 3)
        )
        songs.forEachIndexed { i, f -> QualityCache.put(f, qs[i]) }
        Store.musicDir = root.absolutePath
        Lib.songs = songs.sortedBy { it.nameWithoutExtension.lowercase() }
        Lib.scannedDir = root.absolutePath
        PlayerManager.currentPath = songs[0].absolutePath
        PlayerManager.queue = songs.map { it.absolutePath }
        PlayerManager.duration = 245_000
        PlayerManager.position = 83_000
        PlayerManager.isPlaying = true
        PlayerManager.shuffle = false
        PlayerManager.repeat = 0
    }

    private fun shot(name: String, scale: Float = 1f, content: @Composable () -> Unit) {
        RuntimeEnvironment.setFontScale(scale)
        rule.setContent {
            MMTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } }
        }
        rule.waitForIdle()
        Thread.sleep(300)
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/capturas/$name.png")
    }

    @Composable
    private fun WithMini(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { content() }
            MiniPlayer()
        }
    }

    @Test fun a1_ubicacion() { Store.musicDir = ""; shot("01_ubicacion") { LocationScreen() } }
    @Test fun a2_canciones() { Store.folderView = false; shot("02_canciones") { WithMini { LibraryScreen() } } }
    @Test fun a3_carpetas() { Store.folderView = true; shot("03_carpetas") { WithMini { LibraryScreen() } } }
    @Test fun a4_reproductor() { shot("04_reproductor") { PlayerScreen() } }
    @Test fun a4b_reproductor_aleatorio() { PlayerManager.shuffle = true; PlayerManager.repeat = 1; shot("04b_reproductor_aleatorio_repetir") { PlayerScreen() } }
    @Test fun a4c_cola() { PlayerManager.currentPath = songs[2].absolutePath; shot("04c_cola") { QueueSheet() } }
    @Test fun a5_editor() { shot("05_editor") { EditorScreen(listOf(songs[0].absolutePath)) } }
    @Test fun a6_ajustes_cancion() { shot("06_ajustes_cancion") { FxScreen(songs[0].absolutePath) } }
    @Test fun a7_ajustes() { shot("07_ajustes") { SettingsScreen() } }
    @Test fun b2_canciones_letra_grande() { Store.folderView = false; shot("12_canciones_letra_grande", 1.3f) { WithMini { LibraryScreen() } } }
    @Test fun b3_carpetas_letra_grande() { Store.folderView = true; shot("13_carpetas_letra_grande", 1.3f) { WithMini { LibraryScreen() } } }
    @Test fun b6_ajustes_cancion_letra_grande() { shot("16_ajustes_cancion_letra_grande", 1.3f) { FxScreen(songs[0].absolutePath) } }
    @Test fun b4_reproductor_letra_grande() { shot("14_reproductor_letra_grande", 1.3f) { PlayerScreen() } }
    @Test fun b5_editor_letra_grande() { shot("15_editor_letra_grande", 1.3f) { EditorScreen(listOf(songs[0].absolutePath)) } }
}
