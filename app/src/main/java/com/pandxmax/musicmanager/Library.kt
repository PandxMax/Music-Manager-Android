package com.pandxmax.musicmanager

import android.content.Context
import android.database.ContentObserver
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    @Volatile private var loaded = false
    @Volatile var dirty = false
    private fun file() = File(Store.app.filesDir, "quality.tsv")

    /** Carga la calidad ya calculada en otras sesiones (así no se vuelve a analizar). */
    @Synchronized
    fun load() {
        if (loaded) return
        loaded = true
        try {
            file().forEachLine { line ->
                val p = line.split('\t')
                if (p.size == 4) mem[p[0]] = Quality(p[1], p[2], p[3].toIntOrNull() ?: 5)
            }
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun save(valid: Set<String>) {
        if (!dirty) return
        dirty = false
        try {
            val sb = StringBuilder()
            for ((k, q) in mem) if (k.substringBefore('|') in valid) sb.append(k).append('\t').append(q.format).append('\t').append(q.detail).append('\t').append(q.level).append('\n')
            val tmp = File(file().path + ".tmp")
            tmp.writeText(sb.toString())
            tmp.renameTo(file())
        } catch (_: Throwable) {
        }
    }

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
        dirty = true
        return q
    }
}

// ------------------------------------------------------------------ Biblioteca (canciones de la carpeta elegida)

object Lib {
    var songs by mutableStateOf<List<File>>(emptyList())
    /** true solo la primera vez (cuando aún no hay nada que mostrar). */
    var scanning by mutableStateOf(false)
    var found by mutableIntStateOf(0)
    var scannedDir = ""

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    @Volatile private var again = false
    private val handler = Handler(Looper.getMainLooper())
    private val debounced = Runnable { requestScan() }

    private fun listFile() = File(Store.app.filesDir, "songs.txt")

    /** Muestra al instante la lista guardada de la última vez. */
    suspend fun loadSaved(dir: String) {
        if (scannedDir == dir && songs.isNotEmpty()) return
        val saved = withContext(Dispatchers.IO) {
            QualityCache.load()
            try {
                val lines = listFile().readLines()
                if (lines.firstOrNull() == dir) lines.drop(1).filter { it.isNotEmpty() }.map { File(it) } else null
            } catch (_: Throwable) { null }
        }
        songs = saved ?: emptyList()
        scannedDir = dir
    }

    /** Pide revisar la carpeta en segundo plano; se puede llamar todas las veces que haga falta. */
    fun requestScan() {
        if (Store.musicDir.isEmpty()) return
        if (job?.isActive == true) { again = true; return }
        job = scope.launch {
            do {
                again = false
                scan(Store.musicDir)
            } while (again)
            fillQuality()
        }
        job?.invokeOnCompletion { if (again) handler.post { requestScan() } }
    }

    /** Igual que requestScan pero agrupando muchos avisos seguidos (p. ej. al copiar varios archivos). */
    fun requestScanSoon(delayMs: Long = 1500) {
        handler.removeCallbacks(debounced)
        handler.postDelayed(debounced, delayMs)
    }

    private suspend fun scan(dirPath: String) {
        if (songs.isEmpty()) { scanning = true; found = 0 }
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
        if (dirPath != Store.musicDir) return
        val changed = list.map { it.absolutePath } != songs.map { it.absolutePath }
        if (changed) {
            songs = list
            withContext(Dispatchers.IO) {
                try {
                    listFile().writeText(dirPath + "\n" + list.joinToString("\n") { it.absolutePath })
                } catch (_: Throwable) {
                }
            }
        }
        found = list.size
        scannedDir = dirPath
        scanning = false
    }

    /** Calcula en segundo plano la calidad de las canciones que aún no la tienen y la guarda. */
    private suspend fun fillQuality() {
        val all = songs
        withContext(Dispatchers.IO) {
            for (f in all) {
                if (job?.isActive != true || again) break
                if (QualityCache.peek(f) == null) QualityCache.get(f)
            }
            QualityCache.save(all.map { it.absolutePath }.toSet())
        }
    }

    fun forget(paths: Collection<String>) {
        songs = songs.filter { it.absolutePath !in paths }
        requestScanSoon(300)
    }
}

/** Avisa a la biblioteca cuando Android detecta música nueva, borrada o cambiada. */
object LibWatcher {
    private var registered = false
    fun register(ctx: Context) {
        if (registered) return
        registered = true
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { Lib.requestScanSoon() }
        }
        try {
            ctx.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, obs)
        } catch (_: Throwable) {
        }
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
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(R.drawable.logo_panda), contentDescription = "Music Manager",
            modifier = Modifier.size(110.dp)
        )
        Spacer(Modifier.height(8.dp))
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
    var convertSel by remember { mutableStateOf<List<File>?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    var openFolder by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Store.musicDir) {
        openFolder = null
        Lib.loadSaved(Store.musicDir)
        Lib.requestScan()
    }

    val songs = Lib.songs
    val matching = remember(songs, query) {
        if (query.isBlank()) songs else songs.filter { it.name.contains(query, ignoreCase = true) || (it.parentFile?.name ?: "").contains(query, ignoreCase = true) }
    }
    // Vista por carpetas como un árbol: se empieza en la raíz y se entra a las subcarpetas
    val root = Store.musicDir.trimEnd('/')
    val curDir = if (Store.folderView) (openFolder ?: root) else root
    val tree = remember(matching, curDir, Store.folderView, query) {
        // al buscar se muestran directamente las canciones que coinciden
        if (!Store.folderView || query.isNotBlank()) null else {
            val sub = LinkedHashMap<String, ArrayList<File>>()
            val direct = ArrayList<File>()
            val prefix = "$curDir/"
            for (f in matching) {
                val par = f.parent ?: continue
                if (par == curDir) direct.add(f)
                else if (par.startsWith(prefix)) {
                    val child = prefix + par.substring(prefix.length).substringBefore('/')
                    sub.getOrPut(child) { ArrayList() }.add(f)
                }
            }
            sub.entries.sortedBy { File(it.key).name.lowercase() }.map { it.key to (it.value as List<File>) } to direct
        }
    }
    val folders = tree?.first ?: emptyList()
    val inFolder = Store.folderView && openFolder != null && openFolder != root
    val shown = if (tree != null) tree.second else matching
    fun goUp() {
        val par = File(curDir).parent
        openFolder = if (par == null || !par.startsWith(root) || par == root) null else par
        selected = emptySet()
    }

    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    BackHandler(enabled = selected.isEmpty() && searching) { searching = false; query = "" }
    BackHandler(enabled = selected.isEmpty() && !searching && inFolder) { goUp() }

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
                    IconBtn("🔄") { convertSel = sel.map { File(it) } }
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
                Box {
                    IconBtn("⋮") { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("📂 Cambiar ubicación") }, onClick = { menu = false; Store.choosingDir = true })
                        DropdownMenuItem(text = { Text("📊 Analizador de calidad") }, onClick = { menu = false; Nav.push(Screen.Spectrum(emptyList())) })
                        DropdownMenuItem(text = { Text("⬇ Descargar de YouTube / SoundCloud") }, onClick = { menu = false; Nav.push(Screen.Youtube) })
                        DropdownMenuItem(text = { Text("⚙ Ajustes") }, onClick = { menu = false; Nav.push(Screen.Settings) })
                        DropdownMenuItem(text = { Text("❔ Ayuda") }, onClick = { menu = false; help = true })
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = !Store.folderView, onClick = { Store.folderView = false; openFolder = null; Store.save() },
                label = { Text("🎵 Canciones") }
            )
            FilterChip(
                selected = Store.folderView, onClick = { Store.folderView = true; Store.save() },
                label = { Text("📁 Ver en carpetas") }
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (Lib.scanning) "Buscando… ${Lib.found}"
                else if (Store.folderView) listOfNotNull(
                    if (folders.isNotEmpty()) "${folders.size} carpetas" else null,
                    if (shown.isNotEmpty() || folders.isEmpty()) "${shown.size} canciones" else null
                ).joinToString(" · ")
                else "${shown.size} canciones",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (inFolder) {
            Row(
                Modifier.fillMaxWidth().combinedClickable(onClick = { goUp() }).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBtn("←") { goUp() }
                Column(Modifier.weight(1f)) {
                    Text("📁 " + File(openFolder!!).name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        relPath(openFolder!!), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Text(
                "📂 " + Store.musicDir + "  ·  cambiar", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { Store.choosingDir = true }).padding(horizontal = 14.dp, vertical = 4.dp)
            )
        }
        if (Lib.scanning) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!Lib.scanning && shown.isEmpty() && folders.isEmpty()) {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (query.isNotBlank()) "Ninguna canción coincide con «$query»." else "No encontré música en esta ubicación.",
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (query.isBlank()) TextButton(onClick = { Store.choosingDir = true }) { Text("Cambiar ubicación") }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(folders, key = { "dir:" + it.first }) { (dirPath, list) ->
                        FolderRow(dirPath, list) { openFolder = dirPath; selected = emptySet() }
                    }
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

    convertSel?.let { fs -> ConvertDialog(fs) { convertSel = null; selected = emptySet() } }

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
                        Lib.forget(ps)
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
                        "• «Ver en carpetas» muestra en qué carpeta está cada canción.\n" +
                        "• La lista se actualiza sola cuando añades, borras o cambias música.\n" +
                        "• Toca la ruta 📂 para cambiar la ubicación de tu música."
                )
            },
            confirmButton = { TextButton(onClick = { help = false }) { Text("Entendido") } }
        )
    }
}

private fun relPath(dir: String): String {
    val base = Store.musicDir.trimEnd('/')
    val r = dir.removePrefix(base).trimStart('/')
    return if (r.isEmpty()) "(carpeta principal)" else r
}

private fun subInfo(dir: String, list: List<File>): String {
    val subs = list.mapNotNull { f -> f.parent?.takeIf { it != dir }?.removePrefix("$dir/")?.substringBefore('/') }.toSet().size
    return if (subs > 0) "  ·  $subs subcarpeta" + (if (subs > 1) "s" else "") else ""
}

@Composable
private fun FolderRow(dirPath: String, list: List<File>, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(54.dp)) {
            CoverThumb(list.first().absolutePath, 128, Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)))
            Text(
                "📁", fontSize = 16.sp,
                modifier = Modifier.align(Alignment.BottomEnd).clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface).padding(horizontal = 2.dp)
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(File(dirPath).name.ifEmpty { dirPath }, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${list.size} canciones" + subInfo(dirPath, list), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
