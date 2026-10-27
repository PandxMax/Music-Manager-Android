package com.pandxmax.musicmanager

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).toInt().coerceAtLeast(0)
    return if (s >= 3600) "${s / 3600}:${((s % 3600) / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}"
    else "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

data class SongMeta(val title: String, val artist: String, val album: String)

/** Título, artista y álbum desde las etiquetas (rápido); si faltan, se deduce del nombre del archivo. */
fun songMeta(path: String): SongMeta {
    val f = File(path)
    val base = f.nameWithoutExtension
    val parts = base.split(" - ", limit = 2)
    var t = ""; var a = ""; var al = ""
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(path)
        t = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
        a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
        al = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
    } catch (_: Throwable) {
    } finally {
        try { r.release() } catch (_: Throwable) {}
    }
    if (t.isBlank()) t = if (parts.size == 2) parts[1] else base
    if (a.isBlank()) a = if (parts.size == 2) parts[0] else ""
    return SongMeta(t.trim(), a.trim(), al.trim())
}

private data class LrcLine(val ms: Long, val text: String)

private fun parseLrc(txt: String): List<LrcLine>? {
    val re = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    val out = ArrayList<LrcLine>()
    for (line in txt.lines()) {
        val ms = re.findAll(line).toList()
        if (ms.isEmpty()) continue
        val text = line.replace(re, "").trim()
        for (m in ms) {
            val frac = m.groupValues[3]
            val f = if (frac.isEmpty()) 0L else (frac.padEnd(3, '0').take(3).toLongOrNull() ?: 0L)
            out.add(LrcLine(m.groupValues[1].toLong() * 60000 + m.groupValues[2].toLong() * 1000 + f, text))
        }
    }
    return if (out.size >= 3) out.sortedBy { it.ms } else null
}

/** Barra de progreso gruesa y redondeada (estilo Poweramp): se puede tocar o arrastrar. */
@Composable
private fun SeekBar(pos: Float, dur: Float, accent: Color, onSeek: (Float) -> Unit, onDrag: (Float?) -> Unit) {
    var w by remember { mutableFloatStateOf(1f) }
    val frac = if (dur > 0) (pos / dur).coerceIn(0f, 1f) else 0f
    Canvas(
        Modifier.fillMaxWidth().height(36.dp)
            .pointerInput(dur) {
                w = size.width.toFloat()
                detectTapGestures { o -> onSeek((o.x / size.width).coerceIn(0f, 1f) * dur) }
            }
            .pointerInput(dur) {
                var x = 0f
                detectHorizontalDragGestures(
                    onDragStart = { o -> x = o.x; onDrag((x / size.width).coerceIn(0f, 1f) * dur) },
                    onDragEnd = { onSeek((x / size.width).coerceIn(0f, 1f) * dur); onDrag(null) },
                    onDragCancel = { onDrag(null) },
                    onHorizontalDrag = { ch, dx -> ch.consume(); x += dx; onDrag((x / size.width).coerceIn(0f, 1f) * dur) }
                )
            }
    ) {
        val h = 6.dp.toPx()
        val y = size.height / 2
        drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2))
        val fx = size.width * frac
        drawRoundRect(
            Brush.horizontalGradient(listOf(accent.copy(alpha = 0.7f), accent)), Offset(0f, y - h / 2),
            Size(fx.coerceAtLeast(h), h), CornerRadius(h / 2)
        )
        drawCircle(Color.White, 9.dp.toPx(), Offset(fx, y))
        drawCircle(accent, 5.dp.toPx(), Offset(fx, y))
    }
}

@Composable
private fun RoundCtl(icon: ImageVector, size: Int, tint: Color, bg: Color = Color.Transparent, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size((size * 0.5f).dp)) }
}

@Composable
private fun BottomAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = if (enabled) 0.9f else 0.35f), modifier = Modifier.size(22.dp))
        Text(label, fontSize = 10.sp, color = Color.White.copy(alpha = if (enabled) 0.75f else 0.35f), maxLines = 1, modifier = Modifier.padding(top = 3.dp))
    }
}

private enum class Sheet { Queue, Lyrics, Lists }

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen() {
    val cur = PlayerManager.currentPath
    val accent = MaterialTheme.colorScheme.primary
    var drag by remember { mutableStateOf<Float?>(null) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var menu by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    val dur = PlayerManager.duration.toFloat().coerceAtLeast(1f)
    val pos = (drag ?: PlayerManager.position.toFloat()).coerceIn(0f, dur)

    val cover by produceState<Bitmap?>(null, cur, Store.coverVersion) {
        value = cur?.let { p -> withContext(Dispatchers.IO) { Covers.thumb(p, 720) } }
    }
    val idx = cur?.let { PlayerManager.queue.indexOf(it) } ?: -1

    Box(Modifier.fillMaxSize().background(Color(0xFF0E0E14))) {
        // Fondo: la portada difuminada y oscurecida
        Crossfade(cover, animationSpec = tween(600), label = "fondo") { b ->
            if (b != null) Image(
                b.asImageBitmap(), null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.55f }
                    .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(60.dp) else Modifier)
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color(0xFF0E0E14).copy(alpha = 0.75f), Color(0xFF0E0E14)))
            )
        )

        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // barra superior
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundCtl(Icons.Filled.KeyboardArrowDown, 44, Color.White) { Nav.pop() }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("REPRODUCIENDO", fontSize = 11.sp, letterSpacing = 1.5.sp, color = Color.White.copy(alpha = 0.6f))
                    if (idx >= 0) Text("${idx + 1} de ${PlayerManager.queue.size}", fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
                }
                Box {
                    RoundCtl(Icons.Filled.MoreVert, 44, Color.White) { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Editar datos y portada") }, enabled = cur != null, onClick = { menu = false; cur?.let { Nav.push(Screen.Editor(listOf(it))) } })
                        DropdownMenuItem(text = { Text("Ajustes de la canción") }, enabled = cur != null, onClick = { menu = false; cur?.let { Nav.push(Screen.Fx(it)) } })
                        DropdownMenuItem(text = { Text("Analizador de calidad") }, enabled = cur != null, onClick = {
                            menu = false
                            cur?.let { val f = File(it); Nav.push(Screen.Spectrum(listOf(SpecItem(android.net.Uri.fromFile(f), f.name, f.extension.lowercase() in setOf("flac", "wav"))))) }
                        })
                        DropdownMenuItem(text = { Text("Guardar la cola como lista") }, enabled = PlayerManager.queue.isNotEmpty(), onClick = { menu = false; showSave = true })
                        DropdownMenuItem(text = { Text("Listas guardadas") }, onClick = { menu = false; sheet = Sheet.Lists })
                    }
                }
            }

            if (cur == null) {
                Spacer(Modifier.weight(1f))
                Text("Nada en reproducción", color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.weight(1f))
                return@Column
            }

            Spacer(Modifier.weight(0.6f))
            // portada + datos: al cambiar de canción se deslizan de costado como una ventanita
            AnimatedContent(
                targetState = cur,
                transitionSpec = {
                    val d = PlayerManager.navDir
                    (slideInHorizontally(tween(380)) { w -> d * w } + fadeIn(tween(300)) + scaleIn(tween(380), initialScale = 0.92f)) togetherWith
                        (slideOutHorizontally(tween(380)) { w -> -d * w } + fadeOut(tween(250)) + scaleOut(tween(380), targetScale = 0.92f)) using
                        SizeTransform(clip = false)
                },
                label = "cancion"
            ) { path -> SongCard(path, accent) }
            Spacer(Modifier.height(14.dp))
            SeekBar(pos, dur, accent, onSeek = { PlayerManager.seek(it.toLong()); PlayerManager.position = it.toLong() }, onDrag = { drag = it })
            Row(Modifier.fillMaxWidth()) {
                Text(fmtTime(pos.toLong()), fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f))
                Spacer(Modifier.weight(1f))
                Text("-" + fmtTime((dur - pos).toLong()), fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f))
            }

            // estado de aleatorio / repetir en palabras, para que se note
            Text(
                listOfNotNull(
                    if (PlayerManager.shuffle) "Aleatorio" else null,
                    when (PlayerManager.repeat) { 1 -> "Repetir todo"; 2 -> "Repetir una"; else -> null }
                ).joinToString("  ·  ").ifEmpty { " " },
                fontSize = 11.sp, color = accent, modifier = Modifier.padding(top = 2.dp)
            )
            // controles
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                ToggleCtl(Icons.Filled.Shuffle, PlayerManager.shuffle, accent) { PlayerManager.toggleShuffle() }
                RoundCtl(Icons.Filled.SkipPrevious, 60, Color.White) { PlayerManager.prev() }
                RoundCtl(if (PlayerManager.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, 78, Color.White, accent) { PlayerManager.toggle() }
                RoundCtl(Icons.Filled.SkipNext, 60, Color.White) { PlayerManager.next() }
                ToggleCtl(if (PlayerManager.repeat == 2) Icons.Filled.RepeatOne else Icons.Filled.Repeat, PlayerManager.repeat != 0, accent) { PlayerManager.cycleRepeat() }
            }
            Spacer(Modifier.weight(0.5f))
            // acciones de abajo
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                BottomAction(Icons.AutoMirrored.Filled.QueueMusic, "Cola") { sheet = Sheet.Queue }
                BottomAction(Icons.Filled.Mic, "Letra") { sheet = Sheet.Lyrics }
                BottomAction(Icons.Filled.Equalizer, "Ajustes") { Nav.push(Screen.Fx(cur)) }
                BottomAction(Icons.Filled.Edit, "Editar") { Nav.push(Screen.Editor(listOf(cur))) }
                BottomAction(Icons.Filled.GraphicEq, "Calidad") {
                    val f = File(cur)
                    Nav.push(Screen.Spectrum(listOf(SpecItem(android.net.Uri.fromFile(f), f.name, f.extension.lowercase() in setOf("flac", "wav")))))
                }
            }
        }
    }

    if (showSave) TextPrompt("Guardar lista", "Nombre de la lista", "", { showSave = false }) { name ->
        Store.saveList(name, PlayerManager.queue)
        showSave = false
    }

    sheet?.let { s ->
        ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = MaterialTheme.colorScheme.surface) {
            when (s) {
                Sheet.Queue -> QueueSheet()
                Sheet.Lyrics -> LyricsSheet(cur)
                Sheet.Lists -> ListsSheet { sheet = null }
            }
        }
    }
}

/** Botón de aleatorio / repetir: cuando está activo se ve relleno, de color y con un punto debajo. */
@Composable
private fun ToggleCtl(icon: ImageVector, on: Boolean, accent: Color, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) accent.copy(alpha = 0.25f) else Color.Transparent, label = "bg")
    val fg by animateColorAsState(if (on) accent else Color.White.copy(alpha = 0.55f), label = "fg")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(bg)
                .then(if (on) Modifier.border(1.5.dp, accent.copy(alpha = 0.7f), CircleShape) else Modifier)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = fg, modifier = Modifier.size(24.dp)) }
        Box(Modifier.padding(top = 4.dp).size(5.dp).clip(CircleShape).background(if (on) accent else Color.Transparent))
    }
}

/** Portada grande (se desliza para cambiar de canción) con título, artista, álbum y calidad. */
@Composable
private fun SongCard(path: String, accent: Color) {
    val meta by produceState(SongMeta(File(path).nameWithoutExtension, "", ""), path, Store.libVersion) { value = withContext(Dispatchers.IO) { songMeta(path) } }
    val q by produceState(QualityCache.peek(File(path)), path) { if (value == null) value = withContext(Dispatchers.IO) { QualityCache.get(File(path)) } }
    val cover by produceState<Bitmap?>(null, path, Store.coverVersion) { value = withContext(Dispatchers.IO) { Covers.thumb(path, 720) } }
    var dragX by remember { mutableFloatStateOf(0f) }
    val shift by animateFloatAsState(dragX, label = "shift")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f)
                .graphicsLayer { translationX = shift; rotationZ = shift / 60f }
                .shadow(24.dp, RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF22222E))
                .pointerInput(path) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (dragX < -160) PlayerManager.next() else if (dragX > 160) PlayerManager.prev()
                            dragX = 0f
                        },
                        onDragCancel = { dragX = 0f },
                        onHorizontalDrag = { ch, dx -> ch.consume(); dragX += dx }
                    )
                }
                .pointerInput(path) { detectTapGestures(onDoubleTap = { PlayerManager.toggle() }) },
            contentAlignment = Alignment.Center
        ) {
            val b = cover
            if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Filled.MusicNote, null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(96.dp))
        }
        Spacer(Modifier.height(22.dp))
        Text(
            meta.title, Modifier.fillMaxWidth().basicMarquee(), fontSize = 22.sp, fontWeight = FontWeight.Bold,
            color = Color.White, maxLines = 1, textAlign = TextAlign.Center
        )
        Text(
            meta.artist.ifBlank { "Artista desconocido" }, Modifier.fillMaxWidth().padding(top = 4.dp), fontSize = 16.sp,
            color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
        Text(
            meta.album.ifBlank { " " }, Modifier.fillMaxWidth().padding(top = 2.dp), fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f),
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
        Row(Modifier.padding(top = 10.dp).height(22.dp), verticalAlignment = Alignment.CenterVertically) {
            q?.let { qq ->
                Text(
                    qq.format, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp)
                )
                if (qq.detail.isNotEmpty()) Text(qq.detail, Modifier.padding(horizontal = 8.dp), fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
                if (qq.level < 5) Text(
                    qualityLabel(qq.level), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = qualityColor(qq.level),
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(qualityColor(qq.level).copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

/** Barras animadas de «sonando ahora». */
@Composable
private fun PlayingBars(color: Color, playing: Boolean) {
    val t = rememberInfiniteTransition(label = "bars")
    val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(560), RepeatMode.Reverse), label = "b")
    val c by t.animateFloat(0.5f, 0.9f, infiniteRepeatable(tween(350), RepeatMode.Reverse), label = "c")
    Canvas(Modifier.size(18.dp)) {
        val hs = if (playing) listOf(a, b, c) else listOf(0.35f, 0.35f, 0.35f)
        val bw = size.width / 5
        hs.forEachIndexed { i, h ->
            val bh = size.height * h
            drawRoundRect(color, Offset(i * bw * 2, size.height - bh), Size(bw, bh), CornerRadius(bw / 2))
        }
    }
}

@Composable
private fun QueueRow(num: Int, path: String, dim: Boolean, onPlay: () -> Unit, onRemove: () -> Unit) {
    val meta by produceState(SongMeta(File(path).nameWithoutExtension, "", ""), path) { value = withContext(Dispatchers.IO) { songMeta(path) } }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = 16.dp, vertical = 6.dp)
            .graphicsLayer { alpha = if (dim) 0.5f else 1f },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$num", Modifier.width(30.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        CoverThumb(path, 96, Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(meta.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (meta.artist.isNotBlank()) Text(meta.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, "Quitar de la cola", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun QueueSheet() {
    val cur = PlayerManager.currentPath
    val queue = PlayerManager.queue
    val accent = MaterialTheme.colorScheme.primary
    val ci = queue.indexOf(cur)
    val next = if (ci >= 0) queue.drop(ci + 1) else queue
    val before = if (ci > 0) queue.take(ci) else emptyList()
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Cola de reproducción", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text(
                    plural(queue.size, "canción", "canciones") + (if (PlayerManager.shuffle) " · aleatorio" else "") +
                        when (PlayerManager.repeat) { 1 -> " · repetir todo"; 2 -> " · repetir una"; else -> "" },
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ToggleSmall(Icons.Filled.Shuffle, PlayerManager.shuffle, accent) { PlayerManager.toggleShuffle() }
            ToggleSmall(if (PlayerManager.repeat == 2) Icons.Filled.RepeatOne else Icons.Filled.Repeat, PlayerManager.repeat != 0, accent) { PlayerManager.cycleRepeat() }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (cur != null) item(key = "now") {
                val meta by produceState(SongMeta(File(cur).nameWithoutExtension, "", ""), cur) { value = withContext(Dispatchers.IO) { songMeta(cur) } }
                Text("SONANDO AHORA", Modifier.padding(start = 18.dp, top = 10.dp, bottom = 6.dp), fontSize = 11.sp, letterSpacing = 1.2.sp, color = accent, fontWeight = FontWeight.Bold)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp))
                        .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0.08f))))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CoverThumb(cur, 128, Modifier.size(58.dp).clip(RoundedCornerShape(12.dp)))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(meta.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        if (meta.artist.isNotBlank()) Text(meta.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = accent)
                    }
                    PlayingBars(accent, PlayerManager.isPlaying)
                    Spacer(Modifier.width(6.dp))
                }
            }
            if (next.isNotEmpty()) item(key = "nextH") {
                Text("A CONTINUACIÓN", Modifier.padding(start = 18.dp, top = 16.dp, bottom = 4.dp), fontSize = 11.sp, letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
            itemsIndexed(next, key = { i, p -> "n$i|$p" }) { i, p ->
                val qi = ci + 1 + i
                QueueRow(i + 1, p, false, { PlayerManager.playIndex(qi) }, { PlayerManager.removeAt(qi) })
            }
            if (before.isNotEmpty()) item(key = "prevH") {
                Text("YA SONARON", Modifier.padding(start = 18.dp, top = 16.dp, bottom = 4.dp), fontSize = 11.sp, letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
            itemsIndexed(before, key = { i, p -> "b$i|$p" }) { i, p ->
                QueueRow(i + 1, p, true, { PlayerManager.playIndex(i) }, { PlayerManager.removeAt(i) })
            }
        }
    }
}

@Composable
private fun ToggleSmall(icon: ImageVector, on: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier.padding(start = 6.dp).size(40.dp).clip(CircleShape).background(if (on) accent.copy(alpha = 0.22f) else Color.Transparent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun LyricsSheet(cur: String?) {
    val txt by produceState<String?>(null, cur) { value = cur?.let { withContext(Dispatchers.IO) { Tags.lyrics(File(it)) } } ?: "" }
    val lrc = remember(txt) { txt?.let { parseLrc(it) } }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f).padding(horizontal = 20.dp)) {
        Text("Letra", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(bottom = 8.dp))
        val t = txt
        when {
            t == null -> Text("Cargando…")
            t.isBlank() -> {
                Text("Esta canción no tiene letra guardada.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { cur?.let { Nav.push(Screen.Editor(listOf(it))) } }) { Text("Buscarla en LRCLIB (en Editar)") }
            }
            lrc != null -> {
                val now = PlayerManager.position + 300
                val active = lrc.indexOfLast { it.ms <= now }.coerceAtLeast(0)
                val st = rememberLazyListState()
                LaunchedEffect(active) { st.animateScrollToItem((active - 3).coerceAtLeast(0)) }
                LazyColumn(state = st, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(lrc) { i, l ->
                        Text(
                            l.text.ifBlank { "♪" }, Modifier.fillMaxWidth().clickable { PlayerManager.seek(l.ms) }.padding(vertical = 7.dp),
                            fontSize = if (i == active) 20.sp else 17.sp,
                            fontWeight = if (i == active) FontWeight.Bold else FontWeight.Normal,
                            color = if (i == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                    }
                }
            }
            else -> Text(t, Modifier.verticalScroll(rememberScrollState()), fontSize = 16.sp)
        }
    }
}

@Composable
private fun ListsSheet(onClose: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    val lists = remember(tick) { Store.lists() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
        Text("Listas guardadas", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(bottom = 8.dp))
        if (lists.isEmpty()) Text("Aún no guardaste ninguna lista. En ⋮ → «Guardar la cola como lista».", color = MaterialTheme.colorScheme.onSurfaceVariant)
        for ((name, paths) in lists) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text("${paths.size} canciones", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconBtn("▶") { PlayerManager.playList(paths.filter { File(it).exists() }, 0); onClose() }
                IconBtn("➕") { PlayerManager.enqueue(paths.filter { File(it).exists() }); onClose() }
                IconBtn("🗑") { Store.deleteList(name); tick++ }
            }
        }
    }
}

/** Mini reproductor de abajo (estilo Poweramp): portada, título y artista, y controles grandes. */
@Composable
fun MiniPlayer() {
    val path = PlayerManager.currentPath ?: return
    val accent = MaterialTheme.colorScheme.primary
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp, shadowElevation = 8.dp) {
        Column {
            val frac = if (PlayerManager.duration > 0) PlayerManager.position.toFloat() / PlayerManager.duration else 0f
            Box(Modifier.fillMaxWidth().height(3.dp).background(accent.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight().background(accent))
            }
            Row(
                Modifier.fillMaxWidth().clickable { Nav.push(Screen.Player) }.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AnimatedContent(
                    targetState = path, modifier = Modifier.weight(1f),
                    transitionSpec = {
                        val d = PlayerManager.navDir
                        (slideInHorizontally(tween(320)) { w -> d * w / 2 } + fadeIn(tween(260))) togetherWith
                            (slideOutHorizontally(tween(320)) { w -> -d * w / 2 } + fadeOut(tween(200)))
                    }, label = "mini"
                ) { p ->
                    val m by produceState(SongMeta(File(p).nameWithoutExtension, "", ""), p) { value = withContext(Dispatchers.IO) { songMeta(p) } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CoverThumb(p, 128, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(m.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            if (m.artist.isNotBlank()) Text(m.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                RoundCtl(Icons.Filled.SkipPrevious, 42, MaterialTheme.colorScheme.onSurface) { PlayerManager.prev() }
                RoundCtl(if (PlayerManager.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, 48, Color.White, accent) { PlayerManager.toggle() }
                RoundCtl(Icons.Filled.SkipNext, 42, MaterialTheme.colorScheme.onSurface) { PlayerManager.next() }
            }
        }
    }
}
