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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.palette.graphics.Palette
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
private fun RoundCtl(icon: ImageVector, size: Int, tint: Color, bg: Color = Color.Transparent, iconScale: Float = 0.5f, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size((size * iconScale).dp)) }
}

@Composable
private fun BottomAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
        Text(label, fontSize = 10.sp, color = Color.White.copy(alpha = 0.65f), maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

private enum class Sheet { Lyrics, Lists }

/** Colores sacados de la portada para el fondo (como Spotify / Apple Music). */
private fun coverColors(b: Bitmap?): Pair<Color, Color> {
    if (b == null) return Color(0xFF2A2340) to Color(0xFF15131F)
    return try {
        val pal = Palette.from(b).maximumColorCount(12).generate()
        val dom = pal.getDominantColor(0xFF2A2340.toInt())
        val dark = pal.getDarkMutedColor(pal.getDarkVibrantColor(dom))
        Color(dom) to Color(dark)
    } catch (_: Throwable) { Color(0xFF2A2340) to Color(0xFF15131F) }
}

private fun Color.dim(k: Float) = lerp(this, Color.Black, k)

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
    val queue = PlayerManager.queue
    val idx = cur?.let { queue.indexOf(it) } ?: -1

    val cover by produceState<Bitmap?>(null, cur, Store.coverVersion) {
        value = cur?.let { p -> withContext(Dispatchers.IO) { Covers.thumb(p, 720) } }
    }
    val cols by produceState(coverColors(null), cover) { value = withContext(Dispatchers.Default) { coverColors(cover) } }
    val top by animateColorAsState(cols.first.dim(0.35f), tween(700), label = "top")
    val mid by animateColorAsState(cols.second.dim(0.55f), tween(700), label = "mid")
    val bgBase = Color(0xFF0E0E14)

    // fondo: degradado suave con los colores de la portada (cambia con una transición lenta)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to top, 0.45f to mid, 1f to bgBase))) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            // barra superior
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundCtl(Icons.Filled.KeyboardArrowDown, 44, Color.White, iconScale = 0.65f) { Nav.pop() }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("REPRODUCIENDO", fontSize = 11.sp, letterSpacing = 1.5.sp, color = Color.White.copy(alpha = 0.6f))
                    if (idx >= 0) Text("${idx + 1} de ${queue.size}", fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Medium)
                }
                Box {
                    RoundCtl(Icons.Filled.MoreVert, 44, Color.White) { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Editar datos y portada") }, enabled = cur != null, onClick = { menu = false; cur?.let { Nav.push(Screen.Editor(listOf(it))) } })
                        DropdownMenuItem(text = { Text("Ajustes de la canción") }, enabled = cur != null, onClick = { menu = false; cur?.let { Nav.push(Screen.Fx(it)) } })
                        DropdownMenuItem(text = { Text("Guardar la cola como lista") }, enabled = queue.isNotEmpty(), onClick = { menu = false; showSave = true })
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

            Spacer(Modifier.weight(0.5f))
            // portadas en carrusel: se arrastran con el dedo y la siguiente aparece al lado
            if (idx >= 0) {
                val pager = rememberPagerState(initialPage = idx) { queue.size }
                val latestIdx by rememberUpdatedState(idx)
                LaunchedEffect(idx) {
                    if (pager.currentPage != idx && !pager.isScrollInProgress) {
                        if (kotlin.math.abs(pager.currentPage - idx) == 1) pager.animateScrollToPage(idx, animationSpec = tween(420))
                        else pager.scrollToPage(idx)
                    }
                }
                LaunchedEffect(pager) {
                    snapshotFlow { pager.settledPage }.collect { p ->
                        if (p != latestIdx && p in queue.indices) {
                            PlayerManager.navDir = if (p > latestIdx) 1 else -1
                            PlayerManager.playIndex(p)
                        }
                    }
                }
                HorizontalPager(
                    state = pager, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 28.dp),
                    pageSpacing = 16.dp, beyondViewportPageCount = 1, key = { "$it|" + queue.getOrElse(it) { "" } }
                ) { page ->
                    val off = ((pager.currentPage - page) + pager.currentPageOffsetFraction).let { kotlin.math.abs(it) }.coerceIn(0f, 1f)
                    CoverPage(queue[page], Modifier.graphicsLayer {
                        val sc = 1f - 0.10f * off
                        scaleX = sc; scaleY = sc
                        alpha = 1f - 0.45f * off
                    })
                }
            } else {
                CoverPage(cur, Modifier.padding(horizontal = 28.dp))
            }

            Spacer(Modifier.height(22.dp))
            Crossfade(cur, animationSpec = tween(350), label = "info", modifier = Modifier.padding(horizontal = 26.dp)) { p -> SongInfo(p, accent) }

            Column(Modifier.padding(horizontal = 24.dp)) {
                Spacer(Modifier.height(10.dp))
                SeekBar(pos, dur, accent, onSeek = { PlayerManager.seek(it.toLong()); PlayerManager.position = it.toLong() }, onDrag = { drag = it })
                Row(Modifier.fillMaxWidth()) {
                    Text(fmtTime(pos.toLong()), fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f))
                    Spacer(Modifier.weight(1f))
                    Text("-" + fmtTime((dur - pos).toLong()), fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f))
                }
                // controles
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ToggleCtl(Icons.Filled.Shuffle, PlayerManager.shuffle, accent) { PlayerManager.toggleShuffle() }
                    RoundCtl(Icons.Filled.SkipPrevious, 62, Color.White, iconScale = 0.62f) { PlayerManager.prev() }
                    Box(
                        Modifier.size(76.dp).shadow(16.dp, CircleShape, ambientColor = accent, spotColor = accent).clip(CircleShape)
                            .background(Color.White).clickable { PlayerManager.toggle() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (PlayerManager.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null,
                            tint = Color(0xFF14141C), modifier = Modifier.size(40.dp)
                        )
                    }
                    RoundCtl(Icons.Filled.SkipNext, 62, Color.White, iconScale = 0.62f) { PlayerManager.next() }
                    ToggleCtl(if (PlayerManager.repeat == 2) Icons.Filled.RepeatOne else Icons.Filled.Repeat, PlayerManager.repeat != 0, accent) { PlayerManager.cycleRepeat() }
                }
            }
            Spacer(Modifier.weight(0.5f))
            // acciones de abajo, en una barra translúcida
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
                    .background(Color.White.copy(alpha = 0.07f)).padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                BottomAction(Icons.AutoMirrored.Filled.QueueMusic, "Cola") { Nav.push(Screen.Queue) }
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
                Sheet.Lyrics -> LyricsSheet(cur)
                Sheet.Lists -> ListsSheet { sheet = null }
            }
        }
    }
}

/** Aleatorio / repetir: icono simple; cuando está activo se pinta del color de la app con un punto debajo. */
@Composable
private fun ToggleCtl(icon: ImageVector, on: Boolean, accent: Color, onClick: () -> Unit) {
    val fg by animateColorAsState(if (on) accent else Color.White.copy(alpha = 0.5f), label = "fg")
    Column(
        Modifier.clip(CircleShape).clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(26.dp))
        Box(Modifier.padding(top = 3.dp).size(5.dp).clip(CircleShape).background(if (on) accent else Color.Transparent))
    }
}

@Composable
private fun CoverPage(path: String, modifier: Modifier = Modifier) {
    val cover by produceState<Bitmap?>(null, path, Store.coverVersion) { value = withContext(Dispatchers.IO) { Covers.thumb(path, 720) } }
    Box(
        modifier.fillMaxWidth().aspectRatio(1f)
            .shadow(22.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF22222E))
            .pointerInput(path) { detectTapGestures(onDoubleTap = { PlayerManager.toggle() }) },
        contentAlignment = Alignment.Center
    ) {
        val b = cover
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Filled.MusicNote, null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(96.dp))
    }
}

@Composable
private fun SongInfo(path: String, accent: Color) {
    val meta by produceState(SongMeta(File(path).nameWithoutExtension, "", ""), path, Store.libVersion) { value = withContext(Dispatchers.IO) { songMeta(path) } }
    val q by produceState(QualityCache.peek(File(path)), path) { if (value == null) value = withContext(Dispatchers.IO) { QualityCache.get(File(path)) } }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(meta.title, Modifier.fillMaxWidth().basicMarquee(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, textAlign = TextAlign.Center)
        Text(
            meta.artist.ifBlank { "Artista desconocido" }, Modifier.fillMaxWidth().padding(top = 4.dp), fontSize = 16.sp,
            color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
        Row(Modifier.padding(top = 10.dp).height(22.dp), verticalAlignment = Alignment.CenterVertically) {
            q?.let { qq ->
                Text(
                    qq.format, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp)
                )
                if (qq.detail.isNotEmpty()) Text(qq.detail, Modifier.padding(horizontal = 8.dp), fontSize = 12.sp, color = Color.White.copy(alpha = 0.65f), maxLines = 1)
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

/** Cola de reproducción a pantalla completa: se reordena arrastrando ≡ y se quita deslizando hacia un lado. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun QueueScreen() {
    val cur = PlayerManager.currentPath
    val queue = PlayerManager.queue
    val accent = MaterialTheme.colorScheme.primary
    val ci = queue.indexOf(cur)
    val st = rememberLazyListState(initialFirstVisibleItemIndex = (ci - 1).coerceAtLeast(0))
    // claves estables aunque la misma canción esté dos veces
    val keys = remember(queue) {
        val seen = HashMap<String, Int>()
        queue.map { p -> val n = (seen[p] ?: 0) + 1; seen[p] = n; "$p#$n" }
    }
    val latestQueue by rememberUpdatedState(queue)
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowH = with(LocalDensity.current) { 72.dp.toPx() }
    val cover by produceState<Bitmap?>(null, cur) { value = cur?.let { withContext(Dispatchers.IO) { Covers.thumb(it, 300) } } }
    val cols by produceState(coverColors(null), cover) { value = withContext(Dispatchers.Default) { coverColors(cover) } }
    val headTop by animateColorAsState(cols.first.dim(0.45f), tween(600), label = "h")

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(headTop, MaterialTheme.colorScheme.background)))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn("←") { Nav.pop() }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text("Cola de reproducción", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(plural(queue.size, "canción", "canciones"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ToggleSmall(Icons.Filled.Shuffle, PlayerManager.shuffle, accent) { PlayerManager.toggleShuffle() }
                ToggleSmall(if (PlayerManager.repeat == 2) Icons.Filled.RepeatOne else Icons.Filled.Repeat, PlayerManager.repeat != 0, accent) { PlayerManager.cycleRepeat() }
            }
            if (cur != null) {
                val meta by produceState(SongMeta(File(cur).nameWithoutExtension, "", ""), cur) { value = withContext(Dispatchers.IO) { songMeta(cur) } }
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(72.dp).shadow(10.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))) {
                        CoverThumb(cur, 200, Modifier.fillMaxSize())
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text("SONANDO AHORA", fontSize = 10.sp, letterSpacing = 1.3.sp, color = accent, fontWeight = FontWeight.Bold)
                        Text(meta.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        if (meta.artist.isNotBlank()) Text(meta.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(accent).clickable { PlayerManager.toggle() },
                        contentAlignment = Alignment.Center
                    ) { Icon(if (PlayerManager.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White) }
                }
            }
        }
        Text(
            "Mantén y arrastra ≡ para cambiar el orden · desliza hacia un lado para quitar",
            Modifier.padding(horizontal = 18.dp, vertical = 4.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyColumn(state = st, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(queue, key = { i, _ -> keys.getOrElse(i) { "x$i" } }) { i, p ->
                val key = keys.getOrElse(i) { "x$i" }
                val isCur = i == ci
                val dragging = draggingKey == key
                val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                    if (v != SwipeToDismissBoxValue.Settled) {
                        val at = latestQueue.indices.firstOrNull { keysFor(latestQueue)[it] == key }
                        if (at != null) PlayerManager.removeAt(at)
                        true
                    } else false
                })
                SwipeToDismissBox(
                    state = dismiss,
                    modifier = (if (dragging) Modifier else Modifier.animateItem()).zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragOffset else 0f; shadowElevation = if (dragging) 16f else 0f },
                    backgroundContent = {
                        Box(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error.copy(alpha = 0.25f)).padding(horizontal = 24.dp),
                            contentAlignment = if (dismiss.dismissDirection == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart
                        ) { Icon(Icons.Filled.Delete, "Quitar", tint = MaterialTheme.colorScheme.error) }
                    }
                ) {
                    val meta by produceState(SongMeta(File(p).nameWithoutExtension, "", ""), p) { value = withContext(Dispatchers.IO) { songMeta(p) } }
                    Row(
                        Modifier.fillMaxWidth().height(72.dp)
                            .background(MaterialTheme.colorScheme.background)
                            .background(
                                when {
                                    dragging -> MaterialTheme.colorScheme.surfaceVariant
                                    isCur -> accent.copy(alpha = 0.16f)
                                    else -> Color.Transparent
                                }
                            )
                            .clickable { PlayerManager.playIndex(i) }
                            .padding(start = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.width(34.dp), contentAlignment = Alignment.Center) {
                            if (isCur) PlayingBars(accent, PlayerManager.isPlaying)
                            else Text("${i + 1}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (i < ci) 0.5f else 1f))
                        }
                        CoverThumb(p, 96, Modifier.size(50.dp).clip(RoundedCornerShape(10.dp)).graphicsLayer { alpha = if (i < ci) 0.55f else 1f })
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(
                                meta.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp,
                                fontWeight = if (isCur) FontWeight.Bold else FontWeight.Medium,
                                color = when { isCur -> accent; i < ci -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f); else -> MaterialTheme.colorScheme.onSurface }
                            )
                            if (meta.artist.isNotBlank()) Text(meta.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // asa para arrastrar y cambiar el orden
                        Box(
                            Modifier.size(44.dp).pointerInput(key) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { draggingKey = key; dragOffset = 0f },
                                    onDragEnd = { draggingKey = null; dragOffset = 0f },
                                    onDragCancel = { draggingKey = null; dragOffset = 0f },
                                    onDrag = { ch, d ->
                                        ch.consume()
                                        dragOffset += d.y
                                        val q = latestQueue
                                        val at = keysFor(q).indexOf(key)
                                        if (at >= 0) {
                                            if (dragOffset > rowH / 2 && at < q.size - 1) { PlayerManager.move(at, at + 1); dragOffset -= rowH }
                                            else if (dragOffset < -rowH / 2 && at > 0) { PlayerManager.move(at, at - 1); dragOffset += rowH }
                                        }
                                    }
                                )
                            },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Filled.DragHandle, "Arrastrar", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

private fun keysFor(queue: List<String>): List<String> {
    val seen = HashMap<String, Int>()
    return queue.map { p -> val n = (seen[p] ?: 0) + 1; seen[p] = n; "$p#$n" }
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
