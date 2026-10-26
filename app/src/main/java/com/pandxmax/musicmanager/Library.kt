package com.pandxmax.musicmanager

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// ------------------------------------------------------------------ Calidad de cada archivo

/** level: 0 Hi-Res, 1 Sin pérdida, 2 Alta, 3 Media, 4 Baja, 5 desconocida */
data class Quality(val format: String, val detail: String, val level: Int)

private val LOSSLESS = setOf("flac", "wav", "aiff", "aif", "alac")

fun qualityLabel(l: Int) = when (l) { 0 -> "Hi-Res"; 1 -> "Sin pérdida"; 2 -> "Alta"; 3 -> "Media"; 4 -> "Baja"; else -> "—" }
fun qualityColor(l: Int) = when (l) {
    0 -> Color(0xFFB388FF); 1 -> Color(0xFF2ECC71); 2 -> Color(0xFF4FC3F7); 3 -> Color(0xFFF39C12); 4 -> Color(0xFFE74C3C)
    else -> Color(0xFF8A8A9A)
}

object QualityCache {
    private val mem = ConcurrentHashMap<String, Quality>()

    private fun key(f: File) = "${f.absolutePath}|${f.lastModified()}|${f.length()}"

    fun peek(f: File): Quality? = mem[key(f)]

    /** Lee solo la cabecera del archivo (rápido). Llamar fuera del hilo principal. */
    fun get(f: File): Quality {
        val k = key(f)
        mem[k]?.let { return it }
        val ext = f.extension.lowercase()
        val fmt = when (ext) { "m4a", "mp4" -> "M4A"; "oga" -> "OGG"; else -> ext.uppercase() }
        var kbps = 0; var sr = 0; var bits = 0; var ms = 0L
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            kbps = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0) / 1000
            ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (Build.VERSION.SDK_INT >= 31) {
                sr = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
                bits = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 0
            }
        } catch (_: Throwable) {
        } finally {
            try { r.release() } catch (_: Throwable) {}
        }
        if (kbps <= 0 && ms > 0) kbps = (f.length() * 8 / ms).toInt()
        val lossless = ext in LOSSLESS
        val parts = ArrayList<String>()
        if (lossless && bits > 0) parts.add("$bits bit")
        if (sr > 0) parts.add((if (sr % 1000 == 0) "${sr / 1000}" else "%.1f".format(sr / 1000.0)) + " kHz")
        if (kbps > 0) parts.add("$kbps kbps")
        val level = when {
            lossless && (bits > 16 || sr > 48000) -> 0
            lossless -> 1
            kbps >= 256 -> 2
            kbps >= 160 -> 3
            kbps > 0 -> 4
            else -> 5
        }
        val q = Quality(fmt, parts.joinToString(" · "), level)
        mem[k] = q
        return q
    }
}

// ------------------------------------------------------------------ Biblioteca (canciones de la carpeta elegida)

object Lib {
    var songs by mutableStateOf<List<File>>(emptyList())
    var scanning by mutableStateOf(false)
    var found by mutableIntStateOf(0)
    var scannedDir = ""

    suspend fun scan(dirPath: String) {
        if (dirPath.isEmpty()) return
        scanning = true
        found = 0
        val list = withContext(Dispatchers.IO) {
            val root = File(dirPath)
            val androidDir = File(Environment.getExternalStorageDirectory(), "Android").absolutePath
            val out = ArrayList<File>()
            try {
                root.walkTopDown()
                    .onEnter { !it.name.startsWith(".") && it.absolutePath != androidDir }
                    .forEach { f ->
                        if (f.isFile && f.extension.lowercase() in AUDIO_EXT && !f.name.startsWith(".")) {
                            out.add(f)
                            if (out.size % 50 == 0) found = out.size
                        }
                    }
            } catch (_: Throwable) {
            }
            out.sortBy { it.nameWithoutExtension.lowercase() }
            out
        }
        songs = list
        found = list.size
        scannedDir = dirPath
        scanning = false
    }
}

/** Convierte la carpeta elegida en el selector del sistema a una ruta normal. */
private fun treeToPath(uri: Uri): String? {
    return try {
        val id = DocumentsContract.getTreeDocumentId(uri)
        val parts = id.split(":", limit = 2)
        val vol = parts[0]
        val rel = if (parts.size > 1) parts[1] else ""
        val base = if (vol.equals("primary", true)) Environment.getExternalStorageDirectory().absolutePath else "/storage/$vol"
        if (rel.isEmpty()) base else "$base/$rel"
    } catch (_: Throwable) {
        null
    }
}

// ------------------------------------------------------------------ Pantalla: elegir ubicación

@Composable
fun LocationScreen() {
    var err by remember { mutableStateOf<String?>(null) }
    fun choose(path: String) {
        val f = File(path)
        if (!f.isDirectory) { err = "No se puede abrir esa carpeta"; return }
        Store.musicDir = f.absolutePath
        Store.choosingDir = false
        Store.save()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) treeToPath(uri)?.let { choose(it) } ?: run { err = "No se pudo usar esa carpeta" }
    }
    val canCancel = Store.musicDir.isNotEmpty()
    BackHandler(enabled = canCancel) { Store.choosingDir = false }

    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("♪", fontSize = 60.sp, color = MaterialTheme.colorScheme.primary)
        Text("Selecciona la ubicación de tu música", fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            "Elige la carpeta donde guardas tus canciones. Buscaré la música dentro de ella y de sus subcarpetas.\n" +
                "Podrás cambiarla cuando quieras.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("📂  Elegir carpeta", fontSize = 16.sp)
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = { choose(Store.musicRoot().absolutePath) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("🎵  Usar la carpeta «Music» del teléfono")
        }
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = { choose(Environment.getExternalStorageDirectory().absolutePath) }) {
            Text("Buscar en todo el teléfono")
        }
        if (canCancel) {
            Spacer(Modifier.height(6.dp))
            Text("Ahora: ${Store.musicDir}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            TextButton(onClick = { Store.choosingDir = false }) { Text("Cancelar") }
        }
        err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
    }
}

// ------------------------------------------------------------------ Pantalla principal: lista de canciones

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen() {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var menu by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Store.musicDir) {
        if (Lib.scannedDir != Store.musicDir) Lib.scan(Store.musicDir)
    }

    val songs = Lib.songs
    val shown = remember(songs, query) {
        if (query.isBlank()) songs else songs.filter { it.name.contains(query, ignoreCase = true) }
    }

    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    BackHandler(enabled = selected.isEmpty() && searching) { searching = false; query = "" }

    Column(Modifier.fillMaxSize()) {
        when {
            selected.isNotEmpty() -> Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconBtn("✕") { selected = emptySet() }
                    Text("${selected.size}", Modifier.padding(horizontal = 6.dp), fontWeight = FontWeight.Bold)
                    val sel = shown.map { it.absolutePath }.filter { it in selected }
                    IconBtn("▶") { PlayerManager.playList(sel, 0); selected = emptySet() }
                    IconBtn("✏") { Nav.push(Screen.Editor(sel)) }
                    IconBtn("📊") {
                        Nav.push(Screen.Spectrum(sel.map { val f = File(it); SpecItem(Uri.fromFile(f), f.name, f.extension.lowercase() in LOSSLESS) }))
                    }
                    IconBtn("🗑") { askDelete = true }
                    IconBtn("☑") { selected = shown.map { it.absolutePath }.toSet() }
                }
            }
            searching -> Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Buscar canción") })
                    IconBtn("✕") { searching = false; query = "" }
                }
            }
            else -> Bar("Mi música", null) {
                IconBtn("🔍") { searching = true }
                IconBtn("⟳", enabled = !Lib.scanning) { scope.launch { Lib.scan(Store.musicDir) } }
                Box {
                    IconBtn("⋮") { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("📂 Cambiar ubicación") }, onClick = { menu = false; Store.choosingDir = true })
                        DropdownMenuItem(text = { Text("📊 Analizador de calidad") }, onClick = { menu = false; Nav.push(Screen.Spectrum(emptyList())) })
                        DropdownMenuItem(text = { Text("⬇ Descargar de YouTube a MP3") }, onClick = { menu = false; Nav.push(Screen.Youtube) })
                        DropdownMenuItem(text = { Text("⚙ Ajustes") }, onClick = { menu = false; Nav.push(Screen.Settings) })
                        DropdownMenuItem(text = { Text("❔ Ayuda") }, onClick = { menu = false; help = true })
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = { Store.choosingDir = true }).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "📂 " + File(Store.musicDir).name.ifEmpty { Store.musicDir }, Modifier.weight(1f), fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (Lib.scanning) "Buscando… ${Lib.found}" else "${shown.size} canciones",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (Lib.scanning) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!Lib.scanning && shown.isEmpty()) {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (query.isNotBlank()) "Ninguna canción coincide con «$query»." else "No encontré música en esta ubicación.",
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (query.isBlank()) TextButton(onClick = { Store.choosingDir = true }) { Text("Cambiar ubicación") }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(shown, key = { it.absolutePath }) { f ->
                        val path = f.absolutePath
                        SongRow(
                            f, path in selected, path == PlayerManager.currentPath,
                            onClick = {
                                if (selected.isNotEmpty()) selected = if (path in selected) selected - path else selected + path
                                else Nav.push(Screen.Editor(listOf(path)))
                            },
                            onLong = { selected = selected + path }
                        )
                    }
                }
            }
            msg?.let { m ->
                Snackbar(Modifier.align(Alignment.BottomCenter).padding(12.dp), action = { TextButton(onClick = { msg = null }) { Text("OK") } }) { Text(m) }
            }
        }
    }

    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("Eliminar") },
            text = { Text("¿Eliminar ${selected.size} canción(es) del teléfono? No se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = {
                    askDelete = false
                    val ps = selected.toList()
                    selected = emptySet()
                    scope.launch {
                        withContext(Dispatchers.IO) { ps.forEach { File(it).delete() } }
                        Lib.songs = Lib.songs.filter { it.absolutePath !in ps }
                        msg = "${ps.size} eliminada(s)"
                    }
                }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Cancelar") } }
        )
    }
    if (help) {
        AlertDialog(
            onDismissRequest = { help = false },
            title = { Text("Ayuda") },
            text = {
                Text(
                    "• Toca una canción para editar sus datos y su portada. Arriba tendrás ▶ Reproducir y 📊 Analizador de calidad.\n" +
                        "• Mantén pulsada una canción para seleccionar varias (reproducir, editar juntas, analizar o eliminar).\n" +
                        "• La etiqueta de color indica la calidad: Hi-Res, Sin pérdida, Alta (≥256 kbps), Media o Baja.\n" +
                        "• ⟳ vuelve a buscar música si añadiste archivos desde fuera.\n" +
                        "• Toca la ruta 📂 para cambiar la ubicación de tu música."
                )
            },
            confirmButton = { TextButton(onClick = { help = false }) { Text("Entendido") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(f: File, sel: Boolean, playing: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val q by produceState(QualityCache.peek(f), f) {
        if (value == null) value = withContext(Dispatchers.IO) { QualityCache.get(f) }
    }
    Row(
        Modifier.fillMaxWidth()
            .background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.30f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverThumb(f.absolutePath, 128, Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                (if (playing) "▶ " else "") + f.nameWithoutExtension, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium, fontSize = 15.sp,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                val qq = q
                Text(
                    qq?.format ?: f.extension.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
                Text(
                    qq?.detail ?: "…", Modifier.padding(start = 6.dp), fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        q?.let { qq ->
            if (qq.level < 5) Text(
                qualityLabel(qq.level), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = qualityColor(qq.level),
                modifier = Modifier.clip(RoundedCornerShape(50)).background(qualityColor(qq.level).copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
fun TextPrompt(title: String, label: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(t, { t = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (t.isNotBlank()) onOk(t.trim()) }) { Text("Aceptar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
