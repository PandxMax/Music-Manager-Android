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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
    fun put(f: File, q: Quality) { mem[key(f)] = q }

    /** Lee solo la cabecera del archivo (rápido). Llamar fuera del hilo principal. */
    fun get(f: File): Quality {
        mem[key(f)]?.let { return it }
        probe(f)
        return mem[key(f)] ?: Quality(f.extension.uppercase(), "", 5)
    }

    internal fun store(f: File, q: Quality) { mem[key(f)] = q; dirty = true }
}

/** Datos de la canción para la biblioteca (artista, álbum, género…), guardados para no volver a leerlos. */
data class Meta(val title: String, val artist: String, val album: String, val genre: String, val durMs: Long, val year: String = "")

object MetaCache {
    private val mem = ConcurrentHashMap<String, Meta>()
    @Volatile private var loaded = false
    @Volatile var dirty = false
    /** Sube cada vez que llegan datos nuevos (para reagrupar artistas y géneros). */
    var version by mutableIntStateOf(0)
    private fun file() = File(Store.app.filesDir, "meta.tsv")
    private fun key(f: File) = "${f.absolutePath}|${f.lastModified()}|${f.length()}"
    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').trim()

    @Synchronized
    fun load() {
        if (loaded) return
        loaded = true
        try {
            file().forEachLine { line ->
                val p = line.split('\t')
                if (p.size == 7) mem[p[0]] = Meta(p[1], p[2], p[3], p[4], p[5].toLongOrNull() ?: 0L, p[6])
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
            for ((k, m) in mem) if (k.substringBefore('|') in valid)
                sb.append(k).append('\t').append(clean(m.title)).append('\t').append(clean(m.artist)).append('\t')
                    .append(clean(m.album)).append('\t').append(clean(m.genre)).append('\t').append(m.durMs).append('\t').append(clean(m.year)).append('\n')
            val tmp = File(file().path + ".tmp")
            tmp.writeText(sb.toString())
            tmp.renameTo(file())
        } catch (_: Throwable) {
        }
    }

    fun peek(f: File): Meta? = mem[key(f)]
    internal fun store(f: File, m: Meta) { mem[key(f)] = m; dirty = true }

    fun get(f: File): Meta {
        mem[key(f)]?.let { return it }
        probe(f)
        return mem[key(f)] ?: Meta(f.nameWithoutExtension, "", "", "", 0L)
    }

    fun bump() { version++ }
}

/** Artista principal (sin «feat.», «&», «,»…), para agrupar. */
fun mainArtist(m: Meta, f: File): String {
    var a = m.artist.ifBlank { f.nameWithoutExtension.split(" - ", limit = 2).let { if (it.size == 2) it[0] else "" } }
    a = a.split(Regex("(?i)\\s+(feat\\.?|ft\\.?|featuring|con|with|x|&|y)\\s+|[,;/]")).first().trim()
    return a.ifBlank { "Artista desconocido" }
}

fun genresOf(m: Meta): List<String> =
    m.genre.split(Regex("[;/,]")).map { it.trim() }.filter { it.isNotEmpty() }.map { g -> g.replaceFirstChar { it.uppercase() } }
        .ifEmpty { listOf("Sin género") }

/** Una sola lectura con MediaMetadataRetriever llena la calidad y los datos de la canción. */
fun probe(f: File) {
    val ext = f.extension.lowercase()
    val fmt = when (ext) { "m4a", "mp4" -> "M4A"; "oga" -> "OGG"; else -> ext.uppercase() }
    var kbps = 0; var sr = 0; var bits = 0; var ms = 0L
    var title = ""; var artist = ""; var album = ""; var genre = ""; var year = ""
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(f.absolutePath)
        kbps = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0) / 1000
        ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        if (Build.VERSION.SDK_INT >= 31) {
            sr = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
            bits = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 0
        }
        title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
        artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
        album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
        genre = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: ""
        year = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE) ?: "")
            .let { Regex("(19|20)\\d{2}").find(it)?.value ?: "" }
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
    QualityCache.store(f, Quality(fmt, parts.joinToString(" · "), level))
    // género numérico de ID3v1 «(17)» → se deja vacío
    if (genre.matches(Regex("\\(?\\d+\\)?"))) genre = ""
    MetaCache.store(f, Meta(title.trim(), artist.trim(), album.trim(), genre.trim(), ms, year))
}

// ------------------------------------------------------------------ Biblioteca (canciones de la carpeta elegida)

/** Estado de navegación de la biblioteca (sobrevive al ir al editor o al reproductor y volver). */
object LibNav {
    var openFolder by mutableStateOf<String?>(null)
    var openGroup by mutableStateOf<String?>(null)
    var index = 0
    var offset = 0
    var key = ""
}

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
            MetaCache.load()
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
        if (Store.musicDirs.isEmpty()) return
        if (job?.isActive == true) { again = true; return }
        job = scope.launch {
            do {
                again = false
                scan(Store.libKey)
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
        val incl = Store.musicDirs
        val excl = Store.excludedDirs.toSet()
        val list = withContext(Dispatchers.IO) {
            val androidDir = File(Environment.getExternalStorageDirectory(), "Android").absolutePath
            val out = LinkedHashMap<String, File>()
            for (d in incl) {
                try {
                    File(d).walkTopDown()
                        .onEnter { !it.name.startsWith(".") && it.absolutePath != androidDir && it.absolutePath !in excl }
                        .forEach { f ->
                            if (f.isFile && f.extension.lowercase() in AUDIO_EXT && !f.name.startsWith(".")) {
                                out[f.absolutePath] = f
                                if (out.size % 50 == 0) found = out.size
                            }
                        }
                } catch (_: Throwable) {
                }
            }
            val l = ArrayList(out.values)
            l.sortBy { it.nameWithoutExtension.lowercase() }
            l
        }
        if (dirPath != Store.libKey) return
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

    /** Calcula en segundo plano la calidad y los datos (artista, género…) que aún faltan, y los guarda. */
    private suspend fun fillQuality() {
        val all = songs
        withContext(Dispatchers.IO) {
            QualityCache.load(); MetaCache.load()
            var n = 0
            for (f in all) {
                if (job?.isActive != true || again) break
                if (QualityCache.peek(f) == null || MetaCache.peek(f) == null) {
                    probe(f)
                    if (++n % 150 == 0) withContext(Dispatchers.Main) { MetaCache.bump() }
                }
            }
            val valid = all.map { it.absolutePath }.toSet()
            QualityCache.save(valid)
            MetaCache.save(valid)
        }
        MetaCache.bump()
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

// ------------------------------------------------------------------ Pantalla principal: lista de canciones

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen() {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var dq by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var menu by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var convertSel by remember { mutableStateOf<List<File>?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    // dónde estabas (carpeta, artista, posición) se conserva al volver del editor o del reproductor
    var openFolder by LibNav::openFolder
    var openGroup by LibNav::openGroup
    val listState = rememberLazyListState(LibNav.index, LibNav.offset)
    DisposableEffect(Unit) { onDispose { LibNav.index = listState.firstVisibleItemIndex; LibNav.offset = listState.firstVisibleItemScrollOffset } }

    LaunchedEffect(Store.libKey) {
        if (LibNav.key != Store.libKey) { if (LibNav.key.isNotEmpty()) { openFolder = null; openGroup = null }; LibNav.key = Store.libKey }
        Lib.loadSaved(Store.libKey)
        Lib.requestScan()
    }
    // la búsqueda espera a que dejes de escribir (no se recalcula letra por letra)
    LaunchedEffect(query) { if (query.isEmpty()) dq = "" else { kotlinx.coroutines.delay(250); dq = query } }

    val view = Store.libView
    val songs = Lib.songs
    val metaV = MetaCache.version
    val matching = remember(songs, dq, metaV) {
        if (dq.isBlank()) songs else songs.filter { f ->
            val m = MetaCache.peek(f)
            f.name.contains(dq, true) || (f.parentFile?.name ?: "").contains(dq, true) ||
                (m != null && (m.artist.contains(dq, true) || m.album.contains(dq, true) || m.title.contains(dq, true) || m.genre.contains(dq, true)))
        }
    }

    // ---- Carpetas (árbol)
    val multi = Store.musicDirs.size > 1
    val root = if (multi) "" else Store.musicDir.trimEnd('/')
    val curDir = if (view == 1) (openFolder ?: root) else root
    val tree = remember(matching, curDir, view, dq) {
        if (view != 1 || dq.isNotBlank()) null else {
            val sub = LinkedHashMap<String, ArrayList<File>>()
            val direct = ArrayList<File>()
            val prefix = "$curDir/"
            if (curDir.isEmpty()) for (f in matching) {
                val r = Store.musicDirs.firstOrNull { f.absolutePath.startsWith("$it/") } ?: continue
                sub.getOrPut(r) { ArrayList() }.add(f)
            } else for (f in matching) {
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
    // ---- Artistas / Géneros
    val groups = remember(matching, view, metaV) {
        when (view) {
            2 -> matching.groupBy { mainArtist(MetaCache.peek(it) ?: Meta("", "", "", "", 0), it) }
            3 -> {
                val m = LinkedHashMap<String, ArrayList<File>>()
                for (f in matching) for (g in genresOf(MetaCache.peek(f) ?: Meta("", "", "", "", 0))) m.getOrPut(g) { ArrayList() }.add(f)
                m
            }
            else -> emptyMap()
        }.entries.sortedWith(compareBy({ it.key.startsWith("Artista desconocido") || it.key == "Sin género" }, { it.key.lowercase() }))
            .map { it.key to (it.value as List<File>) }
    }

    val inFolder = view == 1 && openFolder != null && openFolder != root
    val inGroup = (view == 2 || view == 3) && openGroup != null
    val folders = tree?.first ?: emptyList()
    val shown: List<File> = when {
        view == 1 -> tree?.second ?: matching
        inGroup -> groups.firstOrNull { it.first == openGroup }?.second ?: emptyList()
        view == 2 || view == 3 -> if (dq.isNotBlank()) matching else emptyList()
        else -> matching
    }
    val groupList = if ((view == 2 || view == 3) && !inGroup && dq.isBlank()) groups else emptyList()
    // todo lo que suena con «Reproducir esta carpeta / artista / género» (con subcarpetas)
    val playAll: List<File> = when {
        inFolder -> matching.filter { it.absolutePath.startsWith("$curDir/") }
        inGroup -> shown
        view == 0 -> shown
        else -> emptyList()
    }

    fun goUp() {
        val par = File(curDir).parent
        openFolder = if (curDir in Store.musicDirs || par == null || (root.isNotEmpty() && (!par.startsWith(root) || par == root))) null else par
        selected = emptySet()
    }
    fun setView(v: Int) {
        Store.libView = v; Store.folderView = v == 1; openFolder = null; openGroup = null; selected = emptySet(); Store.save()
        scope.launch { listState.scrollToItem(0) }
    }

    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    BackHandler(enabled = selected.isEmpty() && searching) { searching = false; query = "" }
    BackHandler(enabled = selected.isEmpty() && !searching && inFolder) { goUp() }
    BackHandler(enabled = selected.isEmpty() && !searching && inGroup) { openGroup = null }

    Column(Modifier.fillMaxSize()) {
        when {
            selected.isNotEmpty() -> Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconBtn("✕") { selected = emptySet() }
                    Text("${selected.size}", Modifier.padding(horizontal = 6.dp), fontWeight = FontWeight.Bold)
                    val sel = (if (playAll.isNotEmpty()) playAll else shown).map { it.absolutePath }.filter { it in selected }
                        .ifEmpty { selected.toList() }
                    IconBtn("▶") { PlayerManager.playList(sel, 0); selected = emptySet() }
                    IconBtn("➕") { PlayerManager.enqueue(sel); msg = "${sel.size} añadida(s) a la cola"; selected = emptySet() }
                    IconBtn("✏") { Nav.push(Screen.Editor(sel)) }
                    if (sel.size == 1) IconBtn("📂") {
                        // ir a la carpeta de la canción
                        val f = File(sel[0])
                        setView(1); openFolder = f.parent
                        scope.launch { kotlinx.coroutines.delay(120); listState.scrollToItem(0) }
                    }
                    IconBtn("📊") {
                        Nav.push(Screen.Spectrum(sel.map { val f = File(it); SpecItem(Uri.fromFile(f), f.name, f.extension.lowercase() in LOSSLESS) }))
                    }
                    IconBtn("🔄") { convertSel = sel.map { File(it) } }
                    IconBtn("🗑") { askDelete = true }
                    IconBtn("☑") { selected = (if (playAll.isNotEmpty()) playAll else shown).map { it.absolutePath }.toSet() }
                }
            }
            searching -> Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Canción, artista, álbum o género") })
                    IconBtn("✕") { searching = false; query = "" }
                }
            }
            else -> Bar("Mi música", null) {
                IconBtn("🔍") { searching = true }
                Box {
                    IconBtn("⋮") { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("📂 Selección de carpetas") }, onClick = { menu = false; Store.choosingDir = true })
                        DropdownMenuItem(text = { Text("🧰 Herramientas") }, onClick = { menu = false; Nav.push(Screen.Tools) })
                        DropdownMenuItem(text = { Text("📊 Analizador de calidad") }, onClick = { menu = false; Nav.push(Screen.Spectrum(emptyList())) })
                        DropdownMenuItem(text = { Text("⬇ Descargar de YouTube / SoundCloud") }, onClick = { menu = false; Nav.push(Screen.Youtube) })
                        DropdownMenuItem(text = { Text("⚙ Ajustes") }, onClick = { menu = false; Nav.push(Screen.Settings) })
                        DropdownMenuItem(text = { Text("☕ Apoyar el proyecto") }, onClick = { menu = false; Nav.push(Screen.About) })
                        DropdownMenuItem(text = { Text("❔ Ayuda") }, onClick = { menu = false; help = true })
                    }
                }
            }
        }

        // pestañas de la biblioteca
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                Triple("Canciones", Icons.Filled.MusicNote, 0), Triple("Carpetas", Icons.Filled.Folder, 1),
                Triple("Artistas", Icons.Filled.Person, 2), Triple("Géneros", Icons.Filled.Sell, 3)
            ).forEach { (t, ic, v) ->
                FilterChip(
                    selected = view == v, onClick = { setView(v) }, label = { Text(t, maxLines = 1) },
                    leadingIcon = { Icon(ic, null, Modifier.size(18.dp)) }
                )
            }
        }
        val countText = when {
            Lib.scanning -> "Buscando… ${Lib.found}"
            view == 1 -> listOfNotNull(
                if (folders.isNotEmpty()) plural(folders.size, "carpeta", "carpetas") else null,
                if (shown.isNotEmpty() || folders.isEmpty()) plural(shown.size, "canción", "canciones") else null
            ).joinToString(" · ")
            groupList.isNotEmpty() -> plural(groupList.size, if (view == 2) "artista" else "género", if (view == 2) "artistas" else "géneros")
            else -> plural(shown.size, "canción", "canciones")
        }
        if (inFolder || inGroup) {
            Row(
                Modifier.fillMaxWidth().combinedClickable(onClick = { if (inFolder) goUp() else openGroup = null }).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBtn("←") { if (inFolder) goUp() else openGroup = null }
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(if (inFolder) File(openFolder!!).name else openGroup!!, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (inFolder) relPath(openFolder!!) + "  ·  " else "") + countText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().combinedClickable(onClick = { Store.choosingDir = true }).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.FolderOpen, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    (if (multi) plural(Store.musicDirs.size, "carpeta elegida", "carpetas elegidas") else File(Store.musicDir).name.ifEmpty { Store.musicDir }) + "  ·  cambiar", Modifier.weight(1f).padding(start = 6.dp, end = 8.dp),
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(countText, fontSize = 12.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (Lib.scanning) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        if (dq.isNotBlank()) Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Mostrando ${shown.size} de ${songs.size} · «$dq»", Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { query = ""; searching = false }) { Text("Quitar búsqueda", fontSize = 12.sp) }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!Lib.scanning && shown.isEmpty() && folders.isEmpty() && groupList.isEmpty()) {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (dq.isNotBlank()) "Nada coincide con «$dq»." else "No encontré música en esta ubicación.",
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (dq.isBlank()) TextButton(onClick = { Store.choosingDir = true }) { Text("Selección de carpetas") }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 4.dp)) {
                    if (playAll.size > 1) item(key = "playall") {
                        PlayAllRow(
                            when { inFolder -> "Reproducir esta carpeta"; inGroup -> "Reproducir todo"; else -> "Reproducir todo" },
                            plural(playAll.size, "canción", "canciones") + if (inFolder && folders.isNotEmpty()) " · con subcarpetas" else "",
                            onPlay = { PlayerManager.playList(playAll.map { it.absolutePath }, 0); Nav.push(Screen.Player) },
                            onShuffle = {
                                val l = playAll.map { it.absolutePath }.shuffled()
                                PlayerManager.playList(l, 0); Nav.push(Screen.Player)
                            }
                        )
                    }
                    items(groupList, key = { "g:" + it.first }) { (name, list) ->
                        GroupRow(name, list, view == 2) { openGroup = name; selected = emptySet(); scope.launch { listState.scrollToItem(0) } }
                    }
                    items(folders, key = { "dir:" + it.first }) { (dirPath, list) ->
                        FolderRow(dirPath, list) { openFolder = dirPath; selected = emptySet(); scope.launch { listState.scrollToItem(0) } }
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
                LaunchedEffect(m) { kotlinx.coroutines.delay(2500); msg = null }
                Snackbar(Modifier.align(Alignment.BottomCenter).padding(12.dp), action = { TextButton(onClick = { msg = null }) { Text("OK") } }) { Text(m) }
            }
        }
    }

    convertSel?.let { fs -> ConvertDialog(fs) { convertSel = null; selected = emptySet() } }

    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("Eliminar") },
            text = { Text("¿Eliminar ${plural(selected.size, "canción", "canciones")} del teléfono? No se puede deshacer.") },
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
                    "• Toca una canción para editar sus datos y su portada. Arriba tendrás Reproducir, Analizador de calidad, Ajustes y Convertir.\n" +
                        "• Mantén pulsada una canción para elegir varias: reproducir, añadir a la cola, editar juntas, ir a la carpeta, analizar, convertir o eliminar.\n" +
                        "• Arriba eliges cómo ver tu música: Canciones, Carpetas, Artistas o Géneros. Dentro de una carpeta, artista o género, «Reproducir» pone todas sus canciones.\n" +
                        "• La etiqueta de color indica la calidad: Hi-Res, Sin pérdida, Alta (≥256 kbps), Media o Baja.\n" +
                        "• La lista se actualiza sola cuando añades, borras o cambias música.\n" +
                        "• En ⋮ → Herramientas: canciones repetidas, informe de la biblioteca, organizar en carpetas y más."
                )
            },
            confirmButton = { TextButton(onClick = { help = false }) { Text("Entendido") } }
        )
    }
}

@Composable
private fun PlayAllRow(title: String, sub: String, onPlay: () -> Unit, onShuffle: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.12f)).clickable(onClick = onPlay).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, null, tint = Color.White)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(42.dp).clip(CircleShape).clickable(onClick = onShuffle), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Shuffle, "Aleatorio", tint = accent)
        }
    }
}

@Composable
private fun GroupRow(name: String, list: List<File>, artist: Boolean, onClick: () -> Unit) {
    val albums = remember(list) { if (artist) list.mapNotNull { MetaCache.peek(it)?.album?.takeIf { a -> a.isNotBlank() } }.toSet().size else 0 }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverThumb(list.first().absolutePath, 128, Modifier.size(50.dp).clip(if (artist) CircleShape else RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(name, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                plural(list.size, "canción", "canciones") + if (albums > 0) "  ·  " + plural(albums, "álbum", "álbumes") else "",
                fontSize = 12.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun plural(n: Int, one: String, many: String) = "$n " + if (n == 1) one else many

private fun relPath(dir: String): String {
    val base = (Store.musicDirs.firstOrNull { dir == it || dir.startsWith("$it/") } ?: Store.musicDir).trimEnd('/')
    if (dir == base && Store.musicDirs.size > 1) return volumeLabel(dir)
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
                plural(list.size, "canción", "canciones") + subInfo(dirPath, list), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
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
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val qq = q
                Text(
                    qq?.format ?: f.extension.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
                if (qq != null && qq.level < 5) Text(
                    qualityLabel(qq.level), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = qualityColor(qq.level), maxLines = 1,
                    modifier = Modifier.padding(start = 5.dp).clip(RoundedCornerShape(4.dp)).background(qualityColor(qq.level).copy(alpha = 0.15f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
                Text(
                    qq?.detail ?: "…", Modifier.padding(start = 6.dp), fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
