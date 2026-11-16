package com.pandxmax.musicmanager

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

private data class ToolDef(val id: String, val icon: ImageVector, val title: String, val desc: String)

private val TOOL_GROUPS = listOf(
    "Audio y calidad" to listOf(
        ToolDef("recortes", Icons.Filled.AutoFixHigh, "Reparar recortes (clipping)", "Reconstruye los golpes fuertes «aplastados» que suenan ásperos."),
        ToolDef("analizador", Icons.Filled.GraphicEq, "Analizador de calidad", "Espectrograma: descubre MP3 disfrazados de FLAC y compara versiones."),
    ),
    "Etiquetas y portadas" to listOf(
        ToolDef("nombre", Icons.Filled.DriveFileRenameOutline, "Etiquetas desde el nombre del archivo", "Llena artista, título y pista a partir de «Artista - Título.mp3»."),
        ToolDef("parentesis", Icons.Filled.CleaningServices, "Quitar paréntesis del nombre", "«Summer (Official Video) [HD]» → «Summer», en el archivo y el título."),
    ),
    "Organizar la biblioteca" to listOf(
        ToolDef("repetidas", Icons.Filled.ContentCopy, "Buscar canciones repetidas", "Encuentra duplicados y deja marcadas las copias de peor calidad."),
        ToolDef("informe", Icons.Filled.Assessment, "Informe de la biblioteca", "Qué canciones no tienen portada, letra, género, año… o son de calidad baja."),
        ToolDef("organizar", Icons.Filled.AccountTree, "Organizar en carpetas", "Reparte las canciones en carpetas por artista, álbum, género, año…"),
    ),
)

@Composable
fun ToolsScreen() {
    Column(Modifier.fillMaxSize()) {
        Bar("Herramientas", { Nav.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            for ((group, tools) in TOOL_GROUPS) {
                Text(
                    group.uppercase(), Modifier.padding(start = 18.dp, top = 18.dp, bottom = 6.dp), fontSize = 12.sp,
                    letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                )
                for (t in tools) Row(
                    Modifier.fillMaxWidth().clickable {
                        if (t.id == "analizador") Nav.push(Screen.Spectrum(emptyList())) else Nav.push(Screen.Tool(t.id))
                    }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                        Icon(t.icon, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(t.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(t.desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun ToolScreen(id: String) {
    when (id) {
        "repetidas" -> DuplicatesTool()
        "informe" -> ReportTool()
        "organizar" -> OrganizeTool()
        "nombre" -> TagsFromNameTool()
        "parentesis" -> PickSongsTool("Quitar paréntesis del nombre", "Quitar paréntesis") { fs, done -> ParensDialog(fs, { done(); Lib.requestScan() }, done) }
        "recortes" -> PickSongsTool("Reparar recortes (clipping)", "Reparar") { fs, done -> ClipRepairDialog(fs, done) }
        else -> Column { Bar("Herramienta", { Nav.pop() }) }
    }
}

// ------------------------------------------------------------------ elegir canciones (para herramientas que trabajan sobre algunas)

@Composable
private fun PickSongsTool(title: String, action: String, dialog: @Composable (List<File>, () -> Unit) -> Unit) {
    var q by remember { mutableStateOf("") }
    val sel = remember { mutableStateListOf<String>() }
    var run by remember { mutableStateOf<List<File>?>(null) }
    val shown = remember(Lib.songs, q) { Lib.songs.filter { q.isBlank() || it.name.contains(q, true) || (it.parentFile?.name ?: "").contains(q, true) } }
    Column(Modifier.fillMaxSize()) {
        Bar(title, { Nav.pop() })
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), singleLine = true, placeholder = { Text("Buscar canción o carpeta") })
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${sel.size} elegidas", Modifier.weight(1f), fontSize = 13.sp)
            TextButton(onClick = { sel.clear(); sel.addAll(shown.map { it.absolutePath }) }) { Text("Elegir todas") }
            TextButton(onClick = { sel.clear() }) { Text("Ninguna") }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.absolutePath }) { f ->
                val on = f.absolutePath in sel
                Row(Modifier.fillMaxWidth().clickable { if (on) sel.remove(f.absolutePath) else sel.add(f.absolutePath) }.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(on, { if (it) sel.add(f.absolutePath) else sel.remove(f.absolutePath) })
                    Column(Modifier.weight(1f)) {
                        Text(f.nameWithoutExtension, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                        Text(f.parentFile?.name ?: "", maxLines = 1, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Button(enabled = sel.isNotEmpty(), onClick = { run = sel.map { File(it) } }, modifier = Modifier.fillMaxWidth().padding(12.dp).height(50.dp)) {
            Text("$action (${sel.size})")
        }
    }
    run?.let { fs -> dialog(fs) { run = null; sel.clear() } }
}

/** Reparar recortes con ffmpeg (adeclip): copia «(reparada)» o reemplazar la original. */
@Composable
fun ClipRepairDialog(files: List<File>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var replace by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Reparar recortes (clipping)") },
        text = {
            Column {
                Text(
                    "Reconstruye los picos «aplastados» (suenan ásperos o distorsionados en los golpes fuertes). Conserva etiquetas y portada. " +
                        "Tarda un poco: se procesa el audio completo.", fontSize = 12.sp
                )
                Row(Modifier.clickable(enabled = !busy) { replace = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(!replace, { replace = false }, enabled = !busy); Text("Guardar como copia «(reparada)»")
                }
                Row(Modifier.clickable(enabled = !busy) { replace = true }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(replace, { replace = true }, enabled = !busy); Text("Reemplazar la original")
                }
                if (busy) LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxWidth().padding(top = 8.dp))
                if (status.isNotEmpty()) Text(status, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    var ok = 0
                    for ((i, f) in files.withIndex()) {
                        status = "Reparando ${i + 1} de ${files.size}: ${f.name}"
                        val ext = f.extension.lowercase().let { if (it in FX_CODECS) it else "mp3" }
                        val (codec, cover) = FX_CODECS.getValue(ext)
                        val dst = if (replace) File(f.parentFile, ".mmclip_tmp.$ext") else Fx.uniqueName(f, " (reparada)", ext)
                        val args = arrayListOf("-i", f.absolutePath, "-map", "0:a:0")
                        if (cover) args.addAll(listOf("-map", "0:v?", "-c:v", "copy", "-disposition:v", "attached_pic"))
                        args.addAll(listOf("-map_metadata", "0", "-af", "adeclip"))
                        args.addAll(codec); args.add(dst.absolutePath)
                        val dur = (MetaCache.get(f).durMs / 1000.0).takeIf { it > 0 } ?: 200.0
                        val err = withContext(Dispatchers.IO) { Ff.run(ctx, args, dur) { progress = it } }
                        if (err == null && dst.length() > 0) {
                            if (replace) withContext(Dispatchers.IO) {
                                if (PlayerManager.currentPath == f.absolutePath) withContext(Dispatchers.Main) { PlayerManager.stop() }
                                val final = File(f.parentFile, f.nameWithoutExtension + "." + ext)
                                if (dst.renameTo(final) && final.absolutePath != f.absolutePath) f.delete()
                            }
                            ok++
                        } else { dst.delete(); status = "No se pudo con ${f.name}: ${err ?: "sin salida"}" }
                    }
                    busy = false
                    Lib.requestScan()
                    status = "✔ $ok de ${files.size} reparada(s)."
                }
            }) { Text("Reparar") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cerrar") } }
    )
}

// ------------------------------------------------------------------ canciones repetidas

private fun normKey(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").replace(Regex("\\(.*?\\)|\\[.*?]"), " ").replace(Regex("[^a-z0-9]+"), "")

private fun qualityScore(f: File): Long {
    val q = QualityCache.get(f)
    val kbps = Regex("(\\d+) kbps").find(q.detail)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    // mejor nivel (Hi-Res, sin pérdida…) primero, luego más kbps, luego más grande
    return (10 - q.level) * 1_000_000_000L + kbps * 100_000L + (f.length() / 1024).coerceAtMost(99_999)
}

@Composable
private fun DuplicatesTool() {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableIntStateOf(0) }
    var groups by remember { mutableStateOf<List<List<File>>?>(null) }
    val marked = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    fun search() {
        busy = true; groups = null; marked.clear()
        scope.launch {
            val g = withContext(Dispatchers.IO) {
                val songs = Lib.songs
                val by = songs.groupBy { f ->
                    val m = MetaCache.get(f)
                    val t = m.title.ifBlank { f.nameWithoutExtension.split(" - ", limit = 2).last() }
                    when (mode) {
                        0 -> normKey(mainArtist(m, f)) + "|" + normKey(t)
                        1 -> normKey(t)
                        else -> "${f.length()}|${m.durMs / 1000}"
                    }
                }
                by.values.filter { it.size > 1 }.map { l -> l.sortedByDescending { qualityScore(it) } }.sortedBy { it.first().nameWithoutExtension.lowercase() }
            }
            groups = g
            for (l in g) l.drop(1).forEach { marked.add(it.absolutePath) }   // la mejor copia nunca se marca
            busy = false
        }
    }
    LaunchedEffect(mode) { search() }

    Column(Modifier.fillMaxSize()) {
        Bar("Canciones repetidas", { Nav.pop() })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Mismo artista y título", "Solo título", "Archivo idéntico").forEachIndexed { i, t ->
                FilterChip(selected = mode == i, onClick = { mode = i }, label = { Text(t) })
            }
        }
        val g = groups
        when {
            busy || g == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            g.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("No hay canciones repetidas. 👍") }
            else -> {
                Text(
                    "${plural(g.size, "grupo", "grupos")} · ${marked.size} marcadas para eliminar. La mejor copia de cada grupo (arriba) nunca se marca.",
                    Modifier.padding(horizontal = 14.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(Modifier.weight(1f)) {
                    items(g, key = { it.first().absolutePath }) { l ->
                        Card(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)) {
                            Column(Modifier.padding(vertical = 4.dp)) {
                                l.forEachIndexed { i, f ->
                                    val on = f.absolutePath in marked
                                    val q = QualityCache.peek(f)
                                    Row(Modifier.fillMaxWidth().clickable(enabled = i > 0) { if (on) marked.remove(f.absolutePath) else marked.add(f.absolutePath) }.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (i == 0) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Star, "Mejor copia", tint = MaterialTheme.colorScheme.primary) }
                                        else Checkbox(on, { if (it) marked.add(f.absolutePath) else marked.remove(f.absolutePath) })
                                        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal)
                                            Text(
                                                listOfNotNull(q?.format, q?.detail, "%.1f MB".format(f.length() / 1048576.0), f.parentFile?.name).joinToString(" · "),
                                                maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (msg.isNotEmpty()) Text(msg, Modifier.padding(horizontal = 14.dp), fontSize = 12.sp)
                Button(enabled = marked.isNotEmpty(), onClick = { ask = true }, modifier = Modifier.fillMaxWidth().padding(12.dp).height(50.dp)) {
                    Text("Eliminar las marcadas (${marked.size})")
                }
            }
        }
    }
    if (ask) AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text("Eliminar copias") },
        text = { Text("Se borrarán ${plural(marked.size, "archivo", "archivos")} del teléfono. No se puede deshacer.") },
        confirmButton = {
            TextButton(onClick = {
                ask = false
                val ps = marked.toList()
                scope.launch {
                    val n = withContext(Dispatchers.IO) { ps.count { File(it).delete() } }
                    Lib.forget(ps); msg = "✔ $n eliminada(s)."; search()
                }
            }) { Text("Eliminar") }
        },
        dismissButton = { TextButton(onClick = { ask = false }) { Text("Cancelar") } }
    )
}

// ------------------------------------------------------------------ informe de la biblioteca

private data class Issue(val id: String, val label: String)

private val ISSUES = listOf(
    Issue("nocover", "Sin portada"), Issue("smallcover", "Portada pequeña (menos de 500 px)"), Issue("nolyrics", "Sin letra"),
    Issue("nogenre", "Sin género"), Issue("noyear", "Sin año"), Issue("noalbum", "Sin álbum"),
    Issue("noartist", "Sin artista o título"), Issue("lowq", "Calidad baja (menos de 160 kbps)"),
)

@Composable
private fun ReportTool() {
    val found = remember { mutableStateMapOf<String, List<File>>() }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val songs = Lib.songs
        total = songs.size
        val acc = ISSUES.associate { it.id to ArrayList<File>() }
        withContext(Dispatchers.IO) {
            for (f in songs) {
                if (!isActive) break
                val m = MetaCache.get(f)
                if (m.genre.isBlank()) acc.getValue("nogenre").add(f)
                if (m.year.isBlank()) acc.getValue("noyear").add(f)
                if (m.album.isBlank()) acc.getValue("noalbum").add(f)
                if (m.artist.isBlank() || m.title.isBlank()) acc.getValue("noartist").add(f)
                if (QualityCache.get(f).level == 4) acc.getValue("lowq").add(f)
                try {
                    val tag = AudioFileIO.read(f).tag
                    val art = tag?.firstArtwork
                    val folder = f.parentFile?.let { Covers.folderImage(it) }
                    if (art == null && folder == null) acc.getValue("nocover").add(f)
                    else {
                        val b = art?.binaryData
                        if (b != null) {
                            val o = BitmapFactory.Options(); o.inJustDecodeBounds = true
                            BitmapFactory.decodeByteArray(b, 0, b.size, o)
                            if (o.outWidth in 1..499) acc.getValue("smallcover").add(f)
                        }
                    }
                    val lyr = try { tag?.getFirst(FieldKey.LYRICS) ?: "" } catch (_: Exception) { "" }
                    val lrc = File(f.parentFile, f.nameWithoutExtension + ".lrc").isFile
                    if (lyr.isBlank() && !lrc) acc.getValue("nolyrics").add(f)
                } catch (_: Throwable) {
                }
                done++
                if (done % 40 == 0 || done == total) withContext(Dispatchers.Main) { for ((k, v) in acc) found[k] = v.toList() }
            }
        }
        for ((k, v) in acc) found[k] = v.toList()
    }
    Column(Modifier.fillMaxSize()) {
        Bar("Informe de la biblioteca", { if (open != null) open = null else Nav.pop() })
        if (done < total) {
            LinearProgressIndicator(progress = { done / total.coerceAtLeast(1).toFloat() }, Modifier.fillMaxWidth())
            Text("Revisando $done de $total canciones…", Modifier.padding(horizontal = 14.dp, vertical = 4.dp), fontSize = 12.sp)
        } else Text("${plural(total, "canción revisada", "canciones revisadas")}. Toca un apartado para ver las canciones y arreglarlas.", Modifier.padding(14.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val o = open
        if (o == null) {
            LazyColumn(Modifier.weight(1f)) {
                items(ISSUES, key = { it.id }) { iss ->
                    val n = found[iss.id]?.size ?: 0
                    Row(Modifier.fillMaxWidth().clickable(enabled = n > 0) { open = iss.id }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (n == 0) Icons.Filled.CheckCircle else Icons.Filled.Warning, null,
                            tint = if (n == 0) qualityColor(1) else qualityColor(3))
                        Text(iss.label, Modifier.weight(1f).padding(start = 12.dp), fontSize = 15.sp)
                        Text("$n", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        if (n > 0) Icon(Icons.Filled.ChevronRight, null)
                    }
                    HorizontalDivider()
                }
            }
        } else {
            val list = found[o].orEmpty()
            Text(ISSUES.first { it.id == o }.label + " · " + plural(list.size, "canción", "canciones"), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontWeight = FontWeight.SemiBold)
            LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.absolutePath }) { f ->
                    Row(Modifier.fillMaxWidth().clickable { Nav.push(Screen.Editor(listOf(f.absolutePath))) }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CoverThumb(f.absolutePath, 96, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(f.nameWithoutExtension, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                            Text(f.parentFile?.name ?: "", maxLines = 1, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (list.size > 1) Button(onClick = { Nav.push(Screen.Editor(list.take(200).map { it.absolutePath })) }, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Text("Editarlas juntas (${minOf(list.size, 200)})")
            }
        }
    }
}

// ------------------------------------------------------------------ organizar en carpetas

private val LEVELS = listOf(
    "artist" to "Intérprete principal", "artistfull" to "Intérprete", "album" to "Álbum", "yearalbum" to "Año - Álbum",
    "genre" to "Género", "year" to "Año", "decade" to "Década", "letter" to "Primera letra", "format" to "Formato"
)

private fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trimEnd('.').ifBlank { "_" }.take(80)

private fun levelValue(id: String, f: File, missing: String): String {
    val m = MetaCache.get(f)
    return when (id) {
        "artist" -> mainArtist(m, f).let { if (it == "Artista desconocido") missing else it }
        "artistfull" -> m.artist.ifBlank { missing }
        "album" -> m.album.ifBlank { missing }
        "yearalbum" -> if (m.album.isBlank()) missing else (if (m.year.isNotBlank()) "${m.year} - " else "") + m.album
        "genre" -> genresOf(m).first().let { if (it == "Sin género") missing else it }
        "year" -> m.year.ifBlank { missing }
        "decade" -> m.year.toIntOrNull()?.let { "${it / 10 * 10}s" } ?: missing
        "letter" -> mainArtist(m, f).firstOrNull()?.uppercaseChar()?.let { if (it.isLetter()) it.toString() else "#" } ?: "#"
        "format" -> f.extension.uppercase()
        else -> missing
    }.let(::safeName)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrganizeTool() {
    val scope = rememberCoroutineScope()
    val levels = remember { mutableStateListOf("artist", "album") }
    var copy by remember { mutableStateOf(false) }
    var missing by remember { mutableStateOf("Desconocido") }
    var dest by remember { mutableStateOf(Store.musicDirs.firstOrNull() ?: Store.musicDir) }
    var source by remember { mutableStateOf("") }   // "" = toda la biblioteca
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf("") }
    var ask by remember { mutableStateOf(false) }

    val songs = remember(Lib.songs, source) { if (source.isEmpty()) Lib.songs else Lib.songs.filter { it.absolutePath.startsWith("$source/") } }
    val plan = remember(songs, levels.toList(), missing, dest) {
        songs.map { f -> f to File(dest, levels.joinToString("/") { levelValue(it, f, missing) }) }
    }
    val moves = plan.count { (f, d) -> f.parentFile?.absolutePath != d.absolutePath }
    val folders = remember(plan) { plan.groupBy { it.second.absolutePath }.mapValues { it.value.size }.entries.sortedBy { it.key.lowercase() } }

    Column(Modifier.fillMaxSize()) {
        Bar("Organizar en carpetas", { Nav.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            Text("Niveles de carpetas (hasta 3)", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
            for (i in 0 until 3) {
                var open by remember { mutableStateOf(false) }
                val cur = levels.getOrNull(i)
                ExposedDropdownMenuBox(open, { if (i <= levels.size) open = it }, Modifier.padding(top = 6.dp)) {
                    OutlinedTextField(
                        cur?.let { c -> LEVELS.first { it.first == c }.second } ?: (if (i == levels.size) "(ninguno)" else "—"),
                        {}, readOnly = true, enabled = i <= levels.size, label = { Text("Nivel ${i + 1}") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) }, modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(open, { open = false }) {
                        if (i > 0) DropdownMenuItem(text = { Text("(ninguno)") }, onClick = { while (levels.size > i) levels.removeAt(levels.size - 1); open = false })
                        for ((id, label) in LEVELS) DropdownMenuItem(text = { Text(label) }, onClick = {
                            if (i < levels.size) levels[i] = id else levels.add(id); open = false
                        })
                    }
                }
            }
            Text("Qué canciones", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(source.isEmpty(), { source = "" }, label = { Text("Toda la biblioteca") })
                for (d in Store.musicDirs) FilterChip(source == d, { source = d }, label = { Text(File(d).name) })
            }
            Text("Dónde crear las carpetas", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (d in Store.musicDirs) FilterChip(dest == d, { dest = d }, label = { Text(File(d).name) })
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(!copy, { copy = false }, label = { Text("Mover") }); Spacer(Modifier.width(8.dp))
                FilterChip(copy, { copy = true }, label = { Text("Copiar") })
            }
            OutlinedTextField(missing, { missing = it }, label = { Text("Si falta el dato, usar") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

            Text("Vista previa", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
            Text("${plural(songs.size, "canción", "canciones")} en ${plural(folders.size, "carpeta", "carpetas")} · $moves cambian de lugar", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for ((path, n) in folders.take(60)) Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Folder, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(path.removePrefix(dest).trimStart('/'), Modifier.weight(1f).padding(start = 8.dp), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$n", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (folders.size > 60) Text("…y ${folders.size - 60} carpetas más", fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
        }
        if (busy) LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxWidth())
        if (msg.isNotEmpty()) Text(msg, Modifier.padding(horizontal = 14.dp, vertical = 4.dp), fontSize = 12.sp)
        Button(enabled = !busy && moves > 0 && levels.isNotEmpty(), onClick = { ask = true }, modifier = Modifier.fillMaxWidth().padding(12.dp).height(50.dp)) {
            Text(if (busy) "Organizando… $progress%" else (if (copy) "Copiar" else "Mover") + " $moves canciones")
        }
    }
    if (ask) AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text("Organizar en carpetas") },
        text = { Text("Se van a ${if (copy) "copiar" else "mover"} $moves canciones a ${plural(folders.size, "carpeta", "carpetas")} dentro de «${File(dest).name}». La letra .lrc acompaña a su canción.") },
        confirmButton = {
            TextButton(onClick = {
                ask = false; busy = true; progress = 0
                val todo = plan.filter { (f, d) -> f.parentFile?.absolutePath != d.absolutePath }
                scope.launch {
                    var ok = 0
                    val errs = ArrayList<String>()
                    withContext(Dispatchers.IO) {
                        todo.forEachIndexed { i, (f, d) ->
                            try {
                                d.mkdirs()
                                var t = File(d, f.name); var k = 2
                                while (t.exists()) { t = File(d, "${f.nameWithoutExtension} ($k).${f.extension}"); k++ }
                                val lrc = File(f.parentFile, f.nameWithoutExtension + ".lrc")
                                if (copy) { f.copyTo(t); if (lrc.isFile) lrc.copyTo(File(d, t.nameWithoutExtension + ".lrc"), true) }
                                else {
                                    if (!f.renameTo(t)) { f.copyTo(t); f.delete() }
                                    if (lrc.isFile) lrc.renameTo(File(d, t.nameWithoutExtension + ".lrc"))
                                    if (PlayerManager.currentPath == f.absolutePath) PlayerManager.currentPath = t.absolutePath
                                }
                                ok++
                            } catch (e: Throwable) { errs.add("${f.name}: ${e.message}") }
                            progress = (i + 1) * 100 / todo.size
                        }
                        // carpetas que quedaron vacías al mover
                        if (!copy) todo.mapNotNull { it.first.parentFile }.toSet().forEach { p -> if (p.isDirectory && (p.list()?.isEmpty() == true)) p.delete() }
                    }
                    busy = false
                    Lib.requestScan()
                    msg = "✔ $ok de ${todo.size} " + (if (copy) "copiadas" else "movidas") + if (errs.isNotEmpty()) " · ${errs.size} con error: ${errs.first()}" else ""
                }
            }) { Text("Sí") }
        },
        dismissButton = { TextButton(onClick = { ask = false }) { Text("Cancelar") } }
    )
}

// ------------------------------------------------------------------ etiquetas desde el nombre del archivo

private val PATTERNS = listOf(
    "Artista - Título", "Título - Artista", "Pista - Título", "Pista - Artista - Título", "Pista. Título", "Pista. Artista - Título"
)

private fun parseName(name: String, pattern: String): Map<String, String>? {
    val n = name.trim()
    fun two(s: String) = s.split(" - ", limit = 2).takeIf { it.size == 2 }?.map { it.trim() }
    val trackRe = Regex("^(\\d{1,3})\\s*[.\\-_)]?\\s+(.*)$")
    return when (pattern) {
        "Artista - Título" -> two(n)?.let { mapOf("artist" to it[0], "title" to it[1]) }
        "Título - Artista" -> two(n)?.let { mapOf("title" to it[0], "artist" to it[1]) }
        "Pista - Título" -> two(n)?.takeIf { it[0].all(Char::isDigit) }?.let { mapOf("track" to it[0].trimStart('0').ifEmpty { "0" }, "title" to it[1]) }
        "Pista - Artista - Título" -> n.split(" - ", limit = 3).takeIf { it.size == 3 && it[0].trim().all(Char::isDigit) }
            ?.let { mapOf("track" to it[0].trim().trimStart('0'), "artist" to it[1].trim(), "title" to it[2].trim()) }
        "Pista. Título" -> trackRe.find(n)?.let { mapOf("track" to it.groupValues[1].trimStart('0'), "title" to it.groupValues[2].trim()) }
        "Pista. Artista - Título" -> trackRe.find(n)?.let { m -> two(m.groupValues[2])?.let { mapOf("track" to m.groupValues[1].trimStart('0'), "artist" to it[0], "title" to it[1]) } }
        else -> null
    }?.filterValues { it.isNotBlank() }
}

@Composable
private fun TagsFromNameTool() {
    val scope = rememberCoroutineScope()
    var pattern by remember { mutableStateOf(PATTERNS[0]) }
    var onlyMissing by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf("") }
    val metaV = MetaCache.version
    val rows = remember(Lib.songs, pattern, onlyMissing, metaV) {
        Lib.songs.mapNotNull { f ->
            val m = MetaCache.peek(f)
            if (onlyMissing && m != null && m.title.isNotBlank() && m.artist.isNotBlank()) return@mapNotNull null
            parseName(f.nameWithoutExtension, pattern)?.let { f to it }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Bar("Etiquetas desde el nombre", { Nav.pop() })
        Text("Formato de los nombres de archivo", Modifier.padding(start = 14.dp, top = 8.dp), fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in PATTERNS) FilterChip(pattern == p, { pattern = p }, label = { Text(p) })
        }
        Row(Modifier.clickable { onlyMissing = !onlyMissing }.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(onlyMissing, { onlyMissing = it }); Text("Solo las canciones a las que les falta artista o título", fontSize = 13.sp)
        }
        Text("${plural(rows.size, "canción", "canciones")} con nombre que encaja", Modifier.padding(horizontal = 14.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f)) {
            items(rows.take(300), key = { it.first.absolutePath }) { (f, v) ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text(f.nameWithoutExtension, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(v["track"]?.let { "Pista $it" }, v["artist"]?.let { "Artista: $it" }, v["title"]?.let { "Título: $it" }).joinToString("  ·  "),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, maxLines = 2
                    )
                }
            }
        }
        if (busy) LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxWidth())
        if (msg.isNotEmpty()) Text(msg, Modifier.padding(horizontal = 14.dp, vertical = 4.dp), fontSize = 12.sp)
        Button(enabled = !busy && rows.isNotEmpty(), onClick = {
            busy = true
            val todo = rows.toList()
            scope.launch {
                var ok = 0
                withContext(Dispatchers.IO) {
                    todo.forEachIndexed { i, (f, v) ->
                        try { Tags.write(f, v, null, false, null); ok++ } catch (e: Throwable) { ErrLog.note("etiquetas desde el nombre", e) }
                        progress = (i + 1) * 100 / todo.size
                    }
                }
                busy = false; Store.libVersion++; Lib.requestScan()
                msg = "✔ Etiquetas puestas en $ok de ${todo.size} canciones."
            }
        }, modifier = Modifier.fillMaxWidth().padding(12.dp).height(50.dp)) { Text(if (busy) "Guardando… $progress%" else "Poner etiquetas (${rows.size})") }
    }
}
