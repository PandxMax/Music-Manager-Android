package com.pandxmax.musicmanager

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder

private sealed class EDlg {
    object AiFill : EDlg()
    object Genres : EDlg()
    object MusicBrainz : EDlg()
    object Discogs : EDlg()
    object CoverSearch : EDlg()
    object CoverView : EDlg()
    object PasteMeta : EDlg()
    object Parens : EDlg()
    object Rename : EDlg()
    class Crop(val bytes: ByteArray) : EDlg()
}

fun openUrl(ctx: android.content.Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(paths: List<String>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember(paths) { paths.map { File(it) } }
    val values = remember { mutableStateMapOf<String, String>() }
    val multi = remember { mutableStateMapOf<String, Boolean>() }
    val edited = remember { mutableStateListOf<String>() }
    var tech by remember { mutableStateOf<Tech?>(null) }
    var lyrics by remember { mutableStateOf("") }
    var lyricsEdited by remember { mutableStateOf(false) }
    var coverBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingCover by remember { mutableStateOf<ByteArray?>(null) }
    var coverRemoved by remember { mutableStateOf(false) }
    var dlg by remember { mutableStateOf<EDlg?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var askBack by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var lyrBusy by remember { mutableStateOf(false) }
    var showConvert by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var showClip by remember { mutableStateOf(false) }
    var searchMenu by remember { mutableStateOf(false) }

    LaunchedEffect(files) {
        withContext(Dispatchers.IO) {
            val p = files[0].absolutePath
            coverBytes = Covers.embedded(p) ?: files[0].parentFile?.let { Covers.folderImage(it) }?.readBytes()
            if (files.size == 1) {
                val (v, t, l) = Tags.readAll(files[0])
                for ((id, _) in FIELDS) { values[id] = v[id] ?: ""; multi[id] = false }
                tech = t
                lyrics = l
            } else {
                val reads = files.map { Tags.read(it) }
                for ((id, _) in FIELDS) {
                    val vs = reads.map { it[id] ?: "" }.toSet()
                    values[id] = if (vs.size == 1) vs.first() else ""
                    multi[id] = vs.size > 1
                }
            }
        }
    }

    val dirty = edited.isNotEmpty() || pendingCover != null || coverRemoved || lyricsEdited
    val shownBytes: ByteArray? = if (coverRemoved) null else (pendingCover ?: coverBytes)
    val bmp by produceState<Bitmap?>(null, shownBytes) {
        value = withContext(Dispatchers.IO) { shownBytes?.let { Covers.decode(it, 900) } }
    }
    val coverInfo by produceState("", shownBytes) {
        value = withContext(Dispatchers.IO) {
            val b = shownBytes
            if (b == null) "" else {
                val o = BitmapFactory.Options()
                o.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(b, 0, b.size, o)
                val kind = if (b.size > 4 && b[1] == 'P'.code.toByte()) "PNG" else "JPEG"
                "$kind · ${o.outWidth}×${o.outHeight} px · ${b.size / 1024} KB" + if (pendingCover != null) " · pendiente" else ""
            }
        }
    }

    fun markEdited(id: String) { if (id !in edited) edited.add(id) }
    /** Las portadas que no son cuadradas pasan primero por «recortar en cuadrado». */
    fun setCover(b: ByteArray) {
        val o = BitmapFactory.Options(); o.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        if (o.outWidth > 0 && kotlin.math.abs(o.outWidth - o.outHeight) > 2) dlg = EDlg.Crop(b)
        else { pendingCover = b; coverRemoved = false }
    }
    fun applyVals(m: Map<String, String>) {
        for ((k, v) in m) if (v.isNotBlank() && FIELDS.any { it.first == k }) { values[k] = v; markEdited(k) }
    }

    fun save(after: () -> Unit = {}) {
        if (busy) return
        busy = true
        val vals = edited.associateWith { values[it] ?: "" }
        val pc = pendingCover
        val rc = coverRemoved
        val ly = if (lyricsEdited && files.size == 1) lyrics else null
        scope.launch {
            val errs = withContext(Dispatchers.IO) {
                val e = ArrayList<String>()
                for (f in files) {
                    try { Tags.write(f, vals, pc, rc, ly) } catch (t: Throwable) { e.add("${f.name}: ${t.message ?: t.javaClass.simpleName}") }
                }
                e
            }
            busy = false
            if (errs.isEmpty()) {
                if (pc != null) coverBytes = pc
                if (rc) coverBytes = null
                edited.clear(); pendingCover = null; coverRemoved = false; lyricsEdited = false
                Covers.forget("")
                Store.coverVersion++
                Store.libVersion++
                if (files.size == 1) tech = withContext(Dispatchers.IO) { Tags.tech(files[0]) }
                msg = "${files.size} archivo(s) guardado(s)"
                after()
            } else {
                msg = errs.first()
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val b = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { Covers.prepare(it, Store.coverMax) }
                } catch (_: Throwable) { null }
            }
            if (b != null) setCover(b) else msg = "No se pudo leer esa imagen"
        }
    }

    BackHandler(enabled = true) { if (dirty) askBack = true else Nav.pop() }

    val title = if (files.size == 1) files[0].nameWithoutExtension else "${files.size} canciones"
    val artist = values["artist"] ?: ""
    val songTitle = values["title"] ?: ""

    Column(Modifier.fillMaxSize()) {
        Bar(title, { if (dirty) askBack = true else Nav.pop() }) {
            IconBtn("💾", enabled = dirty && !busy, accent = true) { save() }
            Box {
                IconBtn("⋮") { more = true }
                DropdownMenu(more, { more = false }) {
                    DropdownMenuItem(text = { Text("Copiar metadatos") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) }, onClick = {
                        more = false
                        Store.clipMeta = FIELDS.associate { (id, _) -> id to (values[id] ?: "") }.filterValues { it.isNotBlank() }
                        Store.clipCover = shownBytes
                        msg = "Metadatos copiados. Abre otras canciones y usa «Pegar metadatos…»."
                    })
                    DropdownMenuItem(
                        text = { Text("Pegar metadatos…") }, leadingIcon = { Icon(Icons.Filled.ContentPaste, null) },
                        enabled = Store.clipMeta != null, onClick = { more = false; dlg = EDlg.PasteMeta }
                    )
                    DropdownMenuItem(text = { Text("Reparar recortes (clipping)…") }, leadingIcon = { Icon(Icons.Filled.AutoFixHigh, null) }, onClick = { more = false; showClip = true })
                    DropdownMenuItem(text = { Text("Quitar paréntesis del nombre…") }, leadingIcon = { Icon(Icons.Filled.CleaningServices, null) }, onClick = { more = false; dlg = EDlg.Parens })
                    if (files.size == 1) DropdownMenuItem(text = { Text("Renombrar archivo") }, leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) }, onClick = { more = false; dlg = EDlg.Rename })
                    if (files.size == 1) DropdownMenuItem(text = { Text("Ir a la carpeta") }, leadingIcon = { Icon(Icons.Filled.FolderOpen, null) }, onClick = {
                        more = false
                        Store.libView = 1; Store.folderView = true; Store.save(); LibNav.openFolder = files[0].parent; LibNav.index = 0; LibNav.offset = 0
                        Nav.stack.retainAll { it is Screen.Library }
                    })
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            val paths0 = files.map { it.absolutePath }
            val isThis = PlayerManager.currentPath != null && PlayerManager.currentPath in paths0
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ActionTile(
                    if (isThis && PlayerManager.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (isThis && PlayerManager.isPlaying) "Pausar" else "Reproducir", primary = true, modifier = Modifier.weight(1f)
                ) {
                    if (files.size == 1) PlayerManager.playOrToggle(paths0[0], Lib.songs.map { it.absolutePath })
                    else if (isThis) PlayerManager.toggle() else PlayerManager.playList(paths0, 0)
                }
                ActionTile(Icons.Filled.GraphicEq, "Analizador de calidad", modifier = Modifier.weight(1f)) {
                    Nav.push(Screen.Spectrum(files.map {
                        SpecItem(Uri.fromFile(it), it.name, it.extension.lowercase() in setOf("flac", "wav", "aiff", "aif"))
                    }))
                }
                if (files.size == 1) ActionTile(Icons.Filled.Equalizer, "Ajustes de la canción", modifier = Modifier.weight(1f)) {
                    Nav.push(Screen.Fx(files[0].absolutePath))
                }
                ActionTile(Icons.Filled.SwapHoriz, "Convertir formato", modifier = Modifier.weight(1f)) { showConvert = true }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconBtn("🖼") { picker.launch("image/*") }
                    IconBtn("✨") { dlg = EDlg.CoverSearch }
                    IconBtn("💾", enabled = shownBytes != null) {
                        val b = shownBytes
                        if (b != null) {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    try { File(files[0].parentFile, "cover.jpg").writeBytes(b); true } catch (_: Throwable) { false }
                                }
                                msg = if (ok) "Guardada como cover.jpg en la carpeta" else "No se pudo guardar la imagen"
                                Store.libVersion++
                            }
                        }
                    }
                    IconBtn("🗑", enabled = shownBytes != null) { coverRemoved = true; pendingCover = null }
                    IconBtn("🔍", enabled = bmp != null) { dlg = EDlg.CoverView }
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier.size(200.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    val b = bmp
                    if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Text(if (files.size > 1) "Varias canciones" else "Sin portada", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (coverInfo.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) { Chip(coverInfo) }
            }

            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                val q = (artist + " " + songTitle).trim().ifBlank { files[0].nameWithoutExtension }
                fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
                OutlinedButton(onClick = { searchMenu = true }) {
                    Icon(Icons.Filled.TravelExplore, null, Modifier.size(18.dp)); Text("  Buscar la canción en…")
                }
                DropdownMenu(searchMenu, { searchMenu = false }) {
                    for ((name, url) in listOf(
                        "YouTube" to "https://www.youtube.com/results?search_query=" + enc(q),
                        "Spotify" to "https://open.spotify.com/search/" + enc(q),
                        "Tidal" to "https://tidal.com/search?q=" + enc(q),
                        "Qobuz" to "https://www.qobuz.com/search?q=" + enc(q),
                        "Wikipedia" to "https://es.wikipedia.org/wiki/Special:Search?go=Go&search=" + enc(artist.ifBlank { q })
                    )) DropdownMenuItem(text = { Text(name) }, onClick = { searchMenu = false; openUrl(ctx, url) })
                }
            }

            tech?.let { t ->
                FlowRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Chip("🎵 ${t.codec}"); Chip("🧩 ${t.enc}"); Chip("🏷 ${t.tag}"); Chip("⚡ ${t.bitrate}")
                    Chip("🔊 ${t.freq}"); Chip("⏱ ${t.dur}"); Chip("⚙ ${t.mode}"); Chip("💾 ${t.size}")
                }
            }

            Spacer(Modifier.height(4.dp))
            for ((id, label) in FIELDS) {
                OutlinedTextField(
                    value = values[id] ?: "",
                    onValueChange = { values[id] = it; markEdited(id) },
                    label = { Text(label) },
                    placeholder = { if (multi[id] == true) Text("‹varios valores›") },
                    singleLine = true,
                    keyboardOptions = if (id == "date" || id == "track" || id == "disc") KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
            }
            OutlinedTextField(
                value = files.map { it.parent ?: "" }.toSet().let { if (it.size == 1) it.first() else "‹varios›" },
                onValueChange = {}, readOnly = true, singleLine = true, label = { Text("Directorio") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )

            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { dlg = EDlg.AiFill }) { Text("✨ Rellenar con IA") }
                OutlinedButton(onClick = { dlg = EDlg.Genres }) { Text("🎼 Géneros") }
                OutlinedButton(onClick = { dlg = EDlg.MusicBrainz }) { Text("MusicBrainz") }
                OutlinedButton(onClick = { dlg = EDlg.Discogs }) { Text("Discogs") }
            }

            if (files.size == 1) {
                Text("Letra", Modifier.padding(top = 16.dp, bottom = 4.dp), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                OutlinedTextField(
                    value = lyrics, onValueChange = { lyrics = it; lyricsEdited = true },
                    minLines = 6, maxLines = 14, modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = !lyrBusy, modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
                    onClick = {
                        lyrBusy = true
                        scope.launch {
                            val secs = tech?.dur?.split(":")?.let { if (it.size == 2) (it[0].toIntOrNull() ?: 0) * 60 + (it[1].toIntOrNull() ?: 0) else 0 } ?: 0
                            val r = withContext(Dispatchers.IO) {
                                try {
                                    Result.success(Sources.findLyrics(artist.ifBlank { "" }, songTitle.ifBlank { files[0].nameWithoutExtension }, values["album"] ?: "", secs))
                                } catch (e: Throwable) { Result.failure(e) }
                            }
                            lyrBusy = false
                            val found = r.getOrNull()
                            if (found != null) {
                                lyrics = found.first; lyricsEdited = true
                                msg = "Letra encontrada en ${found.second}" + (if (Sources.isLrc(found.first)) " (sincronizada)" else "") + ". Falta guardar."
                            } else if (r.isFailure) msg = "No hay conexión con los servicios de letras. Inténtalo en un momento."
                            else msg = "Ningún servicio (LRCLIB, NetEase, lyrics.ovh) tiene esta letra. Prueba «musica.com» y luego «Pegar letra»."
                        }
                    }
                ) { Text(if (lyrBusy) "Buscando…" else "🔎 Buscar letra (LRCLIB, NetEase, lyrics.ovh)") }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { openUrl(ctx, Sources.musicaComUrl(artist, songTitle.ifBlank { files[0].nameWithoutExtension })); msg = "Copia la letra en el navegador y vuelve a pulsar «Pegar letra»." },
                        modifier = Modifier.weight(1f)
                    ) { Text("🌐 musica.com", maxLines = 1) }
                    OutlinedButton(
                        onClick = {
                            val t = Sources.tidyLyrics(pasteText(ctx))
                            if (t.isBlank()) msg = "No hay texto copiado." else { lyrics = t; lyricsEdited = true; msg = "Letra pegada. Falta guardar." }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("📋 Pegar letra", maxLines = 1) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        msg?.let { m ->
            Snackbar(Modifier.padding(12.dp), action = { TextButton(onClick = { msg = null }) { Text("OK") } }) { Text(m) }
        }
    }

    if (showConvert) ConvertDialog(files) { showConvert = false }
    if (showClip) ClipRepairDialog(files) { showClip = false }

    if (askBack) {
        AlertDialog(
            onDismissRequest = { askBack = false },
            title = { Text("Cambios sin guardar") },
            text = { Text("¿Desea guardar los cambios?") },
            confirmButton = { TextButton(onClick = { askBack = false; save { Nav.pop() } }) { Text("Sí") } },
            dismissButton = { TextButton(onClick = { askBack = false; Nav.pop() }) { Text("No") } }
        )
    }

    val firstName = files[0].nameWithoutExtension
    val guessTitle = songTitle.ifBlank { if (" - " in firstName) firstName.split(" - ", limit = 2)[1] else firstName }
    val guessArtist = artist.ifBlank { if (" - " in firstName) firstName.split(" - ", limit = 2)[0] else "" }

    when (dlg) {
        is EDlg.AiFill -> AiFillDialog(guessTitle, guessArtist, { applyVals(it); dlg = null }, { dlg = null })
        is EDlg.Genres -> GenresDialog(files, guessTitle, guessArtist, values["album"] ?: "", { g -> values["genre"] = g; markEdited("genre"); dlg = null }, { dlg = null })
        is EDlg.PasteMeta -> PasteMetaDialog({ m, cov ->
            applyVals(m); if (cov != null) { pendingCover = cov; coverRemoved = false }; dlg = null
            msg = "Pegado. Revisa y pulsa 💾 para guardar."
        }, { dlg = null })
        is EDlg.Parens -> ParensDialog(files, { newPaths -> dlg = null; if (newPaths != null) { Lib.requestScan(); Nav.stack[Nav.stack.size - 1] = Screen.Editor(newPaths) } }, { dlg = null })
        is EDlg.Rename -> TextPrompt("Renombrar archivo", "Nombre (sin extensión)", files[0].nameWithoutExtension, { dlg = null }) { n ->
            val f = files[0]
            val safe = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            val dst = File(f.parentFile, safe + "." + f.extension)
            dlg = null
            if (dst.exists() && dst.absolutePath != f.absolutePath) msg = "Ya existe un archivo con ese nombre."
            else if (f.renameTo(dst)) {
                if (PlayerManager.currentPath == f.absolutePath) PlayerManager.currentPath = dst.absolutePath
                Lib.requestScan(); Nav.stack[Nav.stack.size - 1] = Screen.Editor(listOf(dst.absolutePath))
            } else msg = "No se pudo renombrar."
        }
        is EDlg.Crop -> CropDialog((dlg as EDlg.Crop).bytes, { b -> pendingCover = b; coverRemoved = false; dlg = null }, { dlg = null })
        is EDlg.MusicBrainz -> CandidatesDialog("MusicBrainz", guessArtist, guessTitle, { applyVals(it); dlg = null }, { dlg = null })
        is EDlg.Discogs -> CandidatesDialog("Discogs", guessArtist, guessTitle, { applyVals(it); dlg = null }, { dlg = null })
        is EDlg.CoverSearch -> CoverSearchDialog(guessArtist, guessTitle, { b -> dlg = null; setCover(b) }, { dlg = null })
        is EDlg.CoverView -> {
            val b = bmp
            AlertDialog(
                onDismissRequest = { dlg = null }, confirmButton = { TextButton(onClick = { dlg = null }) { Text("Cerrar") } },
                text = { if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
            )
        }
        null -> {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiFillDialog(initTitle: String, initArtist: String, onApply: (Map<String, String>) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var t by remember { mutableStateOf(initTitle) }
    var a by remember { mutableStateOf(initArtist) }
    val res = remember { mutableStateMapOf<String, String>() }
    var opts by remember { mutableStateOf<Map<String, List<Pair<String, List<String>>>>>(emptyMap()) }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("✨ Rellenar metadatos") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Busca en iTunes, Deezer, MusicBrainz" + (if (Store.discogsToken.isNotBlank()) ", Discogs" else "") +
                        (if (Store.key(Store.provider).isNotBlank()) " y la IA" else " (pon tu key en Ajustes para usar también la IA)") +
                        ". Si las fuentes no coinciden, cada campo tiene un desplegable para elegir.",
                    fontSize = 12.sp
                )
                OutlinedTextField(t, { t = it }, label = { Text("Canción") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(a, { a = it }, label = { Text("Artista") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                Button(enabled = !loading && t.isNotBlank(), modifier = Modifier.padding(top = 8.dp), onClick = {
                    loading = true
                    status = "Buscando en las fuentes…"
                    scope.launch {
                        try {
                            val r = withContext(Dispatchers.IO) { Ai.fillOptions(t, a) }
                            opts = r
                            res.clear(); for ((k, v) in r) res[k] = v.first().first
                            found = r.isNotEmpty()
                            status = if (found) "Revisa los datos y pulsa Aplicar." else "No se encontraron datos fiables."
                        } catch (e: Throwable) {
                            status = "No se pudo: ${e.message}"
                        }
                        loading = false
                    }
                }) { Text(if (loading) "Buscando…" else "🔎 Buscar") }
                if (status.isNotEmpty()) Text(status, Modifier.padding(top = 6.dp), fontSize = 12.sp)
                if (found) for ((id, label) in FIELDS) {
                    val o = opts[id].orEmpty()
                    var open by remember(id) { mutableStateOf(false) }
                    val cur = res[id] ?: ""
                    val from = o.firstOrNull { it.first == cur }?.second
                    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (o.size > 1) open = it }, modifier = Modifier.padding(top = 6.dp)) {
                        OutlinedTextField(
                            cur, { res[id] = it }, label = { Text(label) }, singleLine = true,
                            supportingText = { if (from != null) Text("← " + from.joinToString(", "), fontSize = 11.sp) else if (cur.isNotEmpty()) Text("escrito a mano", fontSize = 11.sp) },
                            trailingIcon = { if (o.size > 1) ExposedDropdownMenuDefaults.TrailingIcon(open) },
                            colors = if (o.size > 1) OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.primary) else OutlinedTextFieldDefaults.colors(),
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        if (o.size > 1) ExposedDropdownMenu(open, { open = false }) {
                            for ((v, srcs) in o) DropdownMenuItem(
                                text = { Column { Text(v); Text(srcs.joinToString(", "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                                onClick = { res[id] = v; open = false }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = found, onClick = { onApply(res.filterValues { it.isNotBlank() }) }) { Text("Aplicar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

val COMMON_GENRES = listOf(
    "Pop", "Rock", "Reguetón", "Trap latino", "Salsa", "Bachata", "Cumbia", "Merengue", "Hip hop", "Rap", "R&B", "Electrónica",
    "House", "Techno", "EDM", "Balada", "Pop latino", "Rock alternativo", "Indie", "Metal", "Punk", "Jazz", "Blues", "Clásica",
    "Soul", "Funk", "Disco", "K-pop", "J-pop", "Anime", "Banda sonora", "Folk", "Country", "Reggae", "Huayno", "Vals criollo",
    "Bolero", "Ranchera", "Corrido", "Tango", "Bossa nova", "Lo-fi", "Ambient", "Gospel", "Infantil"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenresDialog(files: List<File>, title: String, artist: String, album: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var items by remember { mutableStateOf<List<Pair<String, Int>>?>(null) }
    var err by remember { mutableStateOf("") }
    var other by remember { mutableStateOf("") }
    val hasKey = Store.key(Store.provider).isNotBlank()
    LaunchedEffect(Unit) {
        if (!hasKey) { items = emptyList(); return@LaunchedEffect }
        try {
            items = withContext(Dispatchers.IO) {
                if (files.size == 1) Ai.suggestGenres(title, artist, album).map { it to 1 }
                else {
                    // con varias canciones se juntan las sugerencias (y cuántas canciones sugieren cada una)
                    val combos = files.map { f -> val m = MetaCache.get(f); Triple(m.title.ifBlank { f.nameWithoutExtension }, m.artist, m.album) }
                        .distinctBy { (it.second + "|" + it.third).lowercase() }.take(6)
                    val count = LinkedHashMap<String, Int>()
                    for ((t, a, al) in combos) try { Ai.suggestGenres(t, a, al).forEach { g -> count[g] = (count[g] ?: 0) + 1 } } catch (_: Throwable) {}
                    count.entries.sortedByDescending { it.value }.map { it.key to it.value }
                }
            }
        } catch (e: Throwable) { err = e.message ?: "Error"; items = emptyList() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🎼 Géneros") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (files.size > 1) Text("Lo que elijas se pone a las ${files.size} canciones.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Sugeridos por la IA", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                if (!hasKey) NoKeyNotice(onDismiss)
                else if (items == null) Text("Consultando a la IA…")
                else if (items!!.isEmpty()) Text(if (err.isNotEmpty()) "No se pudo: $err" else "Sin sugerencias.")
                else for ((g, n) in items!!) TextButton(onClick = { onPick(g) }, modifier = Modifier.fillMaxWidth()) {
                    Text(g + if (files.size > 1 && n > 0) "  ($n)" else "", textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                }
                Text("Otro género", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(other, { other = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Escribe o elige abajo") })
                    Button(enabled = other.isNotBlank(), onClick = { onPick(other.trim()) }, modifier = Modifier.padding(start = 8.dp)) { Text("Poner") }
                }
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (g in COMMON_GENRES.filter { other.isBlank() || it.contains(other, true) }.take(24)) AssistChip(onClick = { other = g }, label = { Text(g, fontSize = 12.sp) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

@Composable
private fun CandidatesDialog(source: String, artist: String, title: String, onPick: (Map<String, String>) -> Unit, onDismiss: () -> Unit) {
    var items by remember { mutableStateOf<List<MetaCandidate>?>(null) }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try {
            items = withContext(Dispatchers.IO) { if (source == "Discogs") Sources.discogs(artist, title) else Sources.musicBrainz(artist, title) }
        } catch (e: Throwable) { err = e.message ?: "Error"; items = emptyList() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resultados de $source") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (items == null) Text("Buscando…")
                else if (items!!.isEmpty()) Text(if (err.isNotEmpty()) "No se pudo: $err" else "Sin resultados.")
                else for (c in items!!) {
                    Text(c.label, Modifier.fillMaxWidth().clickable { onPick(c.values) }.padding(vertical = 10.dp), fontSize = 13.sp)
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

@Composable
private fun CoverSearchDialog(artist: String, title: String, onPick: (ByteArray) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<CoverCandidate>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var scBusy by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        items = withContext(Dispatchers.IO) { Covers.search(artist, title) }
        val sc = withContext(Dispatchers.IO) { try { Ytdl.soundcloudCovers(ctx, artist, title) } catch (_: Throwable) { emptyList() } }
        items = (items ?: emptyList()) + sc
        scBusy = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("✨ Portadas encontradas") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (items == null) Text("Buscando en iTunes y Deezer…")
                else if (items!!.isEmpty() && scBusy) Text("Buscando también en SoundCloud…")
                else if (items!!.isEmpty()) {
                    Text("No encontré portadas en iTunes, Deezer ni SoundCloud. Puedes buscarla en Google Imágenes, guardarla en el teléfono y elegirla con el botón 🖼.")
                    Button(onClick = { openUrl(ctx, "https://www.google.com/search?tbm=isch&q=" + URLEncoder.encode("$artist $title portada", "UTF-8")) }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Abrir Google Imágenes")
                    }
                } else {
                    if (scBusy) Text("Buscando también en SoundCloud…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (busy) Text("Descargando…")
                    if (err.isNotEmpty()) Text(err, fontSize = 12.sp)
                    for (c in items!!) {
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                busy = true
                                scope.launch {
                                    val b = withContext(Dispatchers.IO) {
                                        try { Net.getBytes(c.url)?.let { Covers.prepare(it, Store.coverMax) } } catch (_: Throwable) { null }
                                    }
                                    busy = false
                                    if (b != null) onPick(b) else err = "No se pudo descargar esa imagen"
                                }
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(model = c.thumb, contentDescription = null, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                            Text(c.label, Modifier.padding(start = 10.dp), fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}


@Composable
private fun NoKeyNotice(onDismiss: () -> Unit) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(
            "Para usar la IA falta poner tu API key de ${Store.provider}. Con Gemini es gratis: en Ajustes te explico cómo sacarla paso a paso.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.error
        )
        Button(onClick = { onDismiss(); Nav.push(Screen.Settings) }, modifier = Modifier.padding(top = 6.dp)) { Text("⚙ Ir a poner la key") }
    }
}


@Composable
private fun ActionTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (primary) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(bg).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(24.dp))
        Text(
            label, color = fg, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)
        )
    }
}
