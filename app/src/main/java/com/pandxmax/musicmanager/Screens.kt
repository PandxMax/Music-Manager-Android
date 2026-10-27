package com.pandxmax.musicmanager

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

// ------------------------------------------------------------------ Espectrograma y comparación
private val CURVE_COLORS = listOf(
    Color(0xFFE74C3C), Color(0xFF3498DB), Color(0xFF2ECC71), Color(0xFFF1C40F), Color(0xFF9B59B6), Color(0xFF1ABC9C), Color(0xFFE67E22), Color(0xFFEC407A)
)

private fun levelColor(l: Int) = when (l) { 0 -> Color(0xFF2ECC71); 1 -> Color(0xFFF39C12); else -> Color(0xFFE74C3C) }

@Composable
fun SpectrumScreen(initial: List<SpecItem>) {
    val ctx = LocalContext.current
    val items = remember { mutableStateListOf<SpecItem>().apply { addAll(initial) } }
    val results = remember { mutableStateMapOf<String, SpectroResult>() }
    val errors = remember { mutableStateMapOf<String, String>() }
    var current by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        for (u in uris) {
            var name = ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: (u.lastPathSegment ?: "audio")
            val low = name.lowercase()
            if (items.none { it.uri == u }) items.add(SpecItem(u, name, low.endsWith(".flac") || low.endsWith(".wav")))
        }
    }

    LaunchedEffect(items.size) {
        for (it in items.toList()) {
            val k = it.uri.toString()
            if (results.containsKey(k) || errors.containsKey(k)) continue
            current = it.name
            try {
                val r = withContext(Dispatchers.Default) { Spectro.analyze(ctx, it.uri, it.name, it.lossless) }
                results[k] = r
            } catch (e: Throwable) {
                errors[k] = e.message ?: e.javaClass.simpleName
            }
        }
        current = ""
    }

    Column(Modifier.fillMaxSize()) {
        Bar("Analizador de calidad", { Nav.pop() }) {
            IconBtn("➕") { picker.launch(arrayOf("audio/*")) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)) {
            Text(
                "Pulsa ➕ para añadir canciones de cualquier lugar y compararlas entre sí.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (current.isNotEmpty()) {
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  Analizando «$current»…", fontSize = 13.sp)
                }
            }
            val done = items.mapNotNull { results[it.uri.toString()] }
            items.forEachIndexed { idx, it ->
                val r = results[it.uri.toString()]
                val err = errors[it.uri.toString()]
                Card(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(CURVE_COLORS[idx % CURVE_COLORS.size]))
                            Text(it.name, Modifier.weight(1f).padding(start = 8.dp), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            IconBtn("✕") { items.remove(it); results.remove(it.uri.toString()); errors.remove(it.uri.toString()) }
                        }
                        if (err != null) Text("⚠ $err", color = Color(0xFFE74C3C), fontSize = 13.sp)
                        if (r != null) {
                            Text(r.verdict, color = levelColor(r.level), fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
                            Text(
                                "${"%g".format(r.sampleRate / 1000f)} kHz · corte ≈ ${"%.1f".format(r.cutoffHz / 1000f)} kHz de ${"%.1f".format(r.nyquist / 1000f)} · " +
                                    "pico ${"%.1f".format(r.peakDb)} dB · RMS ${"%.1f".format(r.rmsDb)} dB",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Box(Modifier.padding(top = 8.dp)) {
                                Image(r.bmp.asImageBitmap(), null, Modifier.fillMaxWidth().height(200.dp), contentScale = ContentScale.FillBounds)
                                Text("${r.nyquist / 1000} kHz", Modifier.align(Alignment.TopStart).padding(3.dp), fontSize = 10.sp, color = Color.White)
                                Text("0", Modifier.align(Alignment.BottomStart).padding(3.dp), fontSize = 10.sp, color = Color.White)
                            }
                        }
                    }
                }
            }
            if (done.size >= 2) {
                Text("Comparación", Modifier.padding(top = 16.dp, bottom = 4.dp), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Espectro medio superpuesto: si una curva cae antes, tiene menos agudos.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val maxNyq = done.maxOf { it.nyquist }.toFloat()
                Canvas(Modifier.fillMaxWidth().height(190.dp).padding(top = 8.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    val w = size.width
                    val h = size.height
                    items.forEachIndexed { idx, it ->
                        val r = results[it.uri.toString()] ?: return@forEachIndexed
                        val path = Path()
                        val n = r.curve.size
                        for (i in 0 until n) {
                            val x = (i.toFloat() / n) * (r.nyquist / maxNyq) * w
                            val y = (1f - ((r.curve[i] + 110f) / 90f).coerceIn(0f, 1f)) * h
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, CURVE_COLORS[idx % CURVE_COLORS.size], style = Stroke(width = 3f))
                    }
                }
                val best = done.maxByOrNull { it.cutoffHz }
                if (best != null) Text("🏆 Más agudos: ${best.name} (hasta ${"%.1f".format(best.cutoffHz / 1000f)} kHz)", Modifier.padding(top = 8.dp), fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

// ------------------------------------------------------------------ YouTube → MP3
@Composable
fun YoutubeScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var dest by remember { mutableStateOf(Store.musicDir.ifBlank { Store.musicRoot().absolutePath }) }
    var kbps by remember { mutableIntStateOf(320) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var log by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }
    var showDetail by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<List<File>>(emptyList()) }

    Column(Modifier.fillMaxSize()) {
        Bar("Descargar de YouTube / SoundCloud", { Nav.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp)) {
            Text(
                "Descarga solo música o contenido que tengas derecho a guardar (tuyo, con licencia libre o con permiso). " +
                    "Eres responsable del uso que le des.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                url, { url = it }, label = { Text("Enlace de YouTube o SoundCloud") }, placeholder = { Text("https://soundcloud.com/artista/cancion") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            )
            TextButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.primaryClip?.getItemAt(0)?.text?.toString()?.let { url = it.trim() }
            }) { Text("📋 Pegar del portapapeles") }
            OutlinedTextField(dest, { dest = it }, label = { Text("Guardar en") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Calidad MP3", Modifier.padding(top = 10.dp), fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in listOf(128, 192, 256, 320)) FilterChip(selected = kbps == k, onClick = { kbps = k }, label = { Text("$k") })
            }
            Button(
                enabled = !running && url.startsWith("http"), modifier = Modifier.padding(top = 12.dp),
                onClick = {
                    val d = File(dest)
                    if (url.contains("deezer.", ignoreCase = true)) {
                        log = "Deezer no permite descargar canciones completas: están cifradas (DRM) y solo hay 30 s de muestra pública. " +
                            "Busca la misma canción en YouTube o SoundCloud y pega ese enlace."
                        return@Button
                    }
                    if (!d.isDirectory) { log = "Esa carpeta de destino no existe."; return@Button }
                    running = true; progress = 0f; detail = ""; showDetail = false; log = "Empezando… (la primera vez prepara los componentes, puede tardar)"; done = emptyList()
                    scope.launch {
                        try {
                            val files = withContext(Dispatchers.IO) {
                                Ytdl.download(ctx, url.trim(), d, kbps, onStatus = { log = it }) { p, line -> progress = p; if (line.isNotBlank()) log = line }
                            }
                            done = files
                            log = if (files.isEmpty()) "Terminó, pero no encuentro el MP3 nuevo (quizá ya existía)." else "✔ Listo. Puedes editarle metadatos y portada."
                            Store.libVersion++; Lib.requestScan()
                        } catch (e: Throwable) {
                            detail = e.message ?: e.javaClass.simpleName
                            log = Ytdl.friendly(detail)
                            progress = 0f
                        }
                        running = false
                    }
                }
            ) { Text("⬇ Descargar MP3") }
            if (running || progress > 0f) LinearProgressIndicator(progress = { (progress / 100f).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 10.dp))
            if (log.isNotEmpty()) Text(log, Modifier.padding(top = 8.dp), fontSize = 13.sp)
            if (detail.isNotEmpty()) {
                TextButton(onClick = { showDetail = !showDetail }) { Text(if (showDetail) "Ocultar detalles técnicos" else "Ver detalles técnicos") }
                if (showDetail) Text(detail.takeLast(1500), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (f in done) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                    Button(onClick = { Nav.push(Screen.Editor(listOf(f.absolutePath))) }) { Text("✏ Editar") }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Ajustes
private val ACCENTS = listOf(0xFF7C5CFF, 0xFFE74C3C, 0xFF2ECC71, 0xFF3498DB, 0xFFF39C12, 0xFFEC407A, 0xFF1ABC9C)

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(Store.provider) }
    var model by remember { mutableStateOf(Store.model(provider)) }
    var key by remember { mutableStateOf(Store.key(provider)) }
    var discogs by remember { mutableStateOf(Store.discogsToken) }
    var info by remember { mutableStateOf<String?>(null) }
    var updUrl by remember { mutableStateOf<String?>(null) }
    var about by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Bar("Ajustes", { Nav.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp)) {
            Text("🤖 Inteligencia artificial", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in listOf("Claude", "ChatGPT", "Gemini")) {
                    FilterChip(selected = provider == p, onClick = {
                        provider = p; Store.provider = p; Store.save(); model = Store.model(p); key = Store.key(p)
                    }, label = { Text(p) })
                }
            }
            OutlinedTextField(model, { model = it; Store.setModel(provider, it.trim()) }, label = { Text("Modelo") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in Store.defaultModels(provider)) AssistChip(onClick = { model = m; Store.setModel(provider, m) }, label = { Text(m, fontSize = 10.sp) })
            }
            OutlinedTextField(
                key, { key = it; Store.setKey(provider, it.trim()) }, label = { Text("API key de $provider") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            var test by remember { mutableStateOf<String?>(null) }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(enabled = key.isNotBlank() && test != "Probando…", onClick = {
                    test = "Probando…"
                    scope.launch {
                        test = try {
                            val r = withContext(Dispatchers.IO) { Ai.chat(provider, model.trim(), key.trim(), "", "Responde solo con la palabra OK.") }
                            if (r.isNotBlank()) "✔ La key funciona" else "Respondió vacío"
                        } catch (e: Throwable) {
                            "✖ No funciona: " + (e.message ?: "error").take(160)
                        }
                    }
                }) { Text("Probar la key") }
                test?.let { Text(it, Modifier.padding(start = 10.dp), fontSize = 12.sp) }
            }
            AiKeyHelp(provider)
            OutlinedTextField(
                discogs, { discogs = it; Store.discogsToken = it.trim(); Store.save() }, label = { Text("Token de Discogs (opcional)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            Text("⬇ Componente de descargas (yt-dlp)", Modifier.padding(top = 20.dp), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            var ytv by remember { mutableStateOf<String?>(null) }
            var ytBusy by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { ytv = withContext(Dispatchers.IO) { Ytdl.version(ctx) } }
            Text(
                "Se actualiza solo antes de cada descarga, porque YouTube cambia a menudo. Versión instalada: ${ytv ?: "…"}",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
            )
            OutlinedButton(enabled = !ytBusy, modifier = Modifier.padding(top = 6.dp), onClick = {
                ytBusy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { Ytdl.update(ctx, force = true) }
                    ytv = withContext(Dispatchers.IO) { Ytdl.version(ctx) } + "  ($r)"
                    ytBusy = false
                }
            }) { Text(if (ytBusy) "Actualizando…" else "Actualizar ahora") }

            Text("🎨 Aspecto", Modifier.padding(top = 20.dp), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text("Tema oscuro", Modifier.weight(1f))
                Switch(checked = Store.dark, onCheckedChange = { Store.dark = it; Store.save() })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Colores según la portada", Modifier.weight(1f))
                Switch(checked = Store.coverTint, onCheckedChange = { Store.coverTint = it; if (!it) Store.dynamicAccent = null; Store.save() })
            }
            Text("Color principal", Modifier.padding(top = 8.dp), fontSize = 12.sp)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (c in ACCENTS) {
                    val ci = c.toInt()
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(Color(ci))
                            .clickable { Store.accent = ci; Store.save() },
                        contentAlignment = Alignment.Center
                    ) { if (Store.accent == ci) Text("✓", color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }

            Text("🖼 Portadas", Modifier.padding(top = 20.dp), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Tamaño máximo al poner portadas nuevas", fontSize = 12.sp)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in listOf(600, 1000, 1200, 2000)) FilterChip(selected = Store.coverMax == s, onClick = { Store.coverMax = s; Store.save() }, label = { Text("$s px") })
            }

            Text("ℹ Acerca de", Modifier.padding(top = 20.dp), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        info = "Buscando…"
                        try {
                            val r = withContext(Dispatchers.IO) { latestRelease() }
                            val cur = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
                            if (r == null) info = "No encontré versiones publicadas."
                            else if (r.first == cur) info = "Ya tienes la última versión ($cur)."
                            else { info = "Hay una versión nueva: ${r.first} (tienes $cur)."; updUrl = r.second }
                        } catch (e: Throwable) {
                            info = "No se pudo comprobar: ${e.message}"
                        }
                    }
                }) { Text("Buscar actualización") }
                OutlinedButton(onClick = { about = true }) { Text("Acerca de") }
            }
            info?.let { Text(it, Modifier.padding(top = 8.dp), fontSize = 13.sp) }
            updUrl?.let { u -> Button(onClick = { openUrl(ctx, u) }, modifier = Modifier.padding(top = 6.dp)) { Text("⬇ Descargar") } }
        }
    }

    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text("Music Manager") },
            text = {
                Text(
                    "Organiza tu música: carpetas, etiquetas, portadas, letras, calidad de audio y ayuda de IA.\n\n" +
                        "Creado por PandxMax\ngithub.com/PandxMax/Music-Manager-Android"
                )
            },
            confirmButton = { TextButton(onClick = { openUrl(ctx, "https://github.com/PandxMax/Music-Manager-Android"); about = false }) { Text("Abrir GitHub") } },
            dismissButton = { TextButton(onClick = { about = false }) { Text("Cerrar") } }
        )
    }
}

private fun latestRelease(): Pair<String, String>? {
    val txt = Net.get("https://api.github.com/repos/PandxMax/Music-Manager-Android/releases?per_page=30", mapOf("Accept" to "application/vnd.github+json"))
    val a = JSONArray(txt)
    for (i in 0 until a.length()) {
        val r = a.getJSONObject(i)
        val tag = r.optString("tag_name")
        if (tag.startsWith("v") || tag.startsWith("android-v")) {
            var url = r.optString("html_url")
            val assets = r.optJSONArray("assets")
            if (assets != null && assets.length() > 0) url = assets.getJSONObject(0).optString("browser_download_url", url)
            return tag.removePrefix("android-").removePrefix("v") to url
        }
    }
    return null
}


/** Explica cómo conseguir la API key del proveedor elegido, con el enlace directo. */
@Composable
fun AiKeyHelp(provider: String) {
    val ctx = LocalContext.current
    val (steps, url, btn) = when (provider) {
        "Gemini" -> Triple(
            "Gemini tiene un plan GRATUITO (con un límite diario de usos, más que suficiente para tu música):\n" +
                "1. Pulsa el botón de abajo (se abre Google AI Studio).\n" +
                "2. Entra con tu cuenta de Google (la de Gmail).\n" +
                "3. Acepta las condiciones si te las pide.\n" +
                "4. Pulsa «Create API key» / «Crear clave de API».\n" +
                "5. Copia la clave (empieza por «AIza…») y pégala arriba en «API key de Gemini».\n" +
                "6. Pulsa «Probar la key». No hace falta poner tarjeta.",
            "https://aistudio.google.com/apikey", "🔑 Conseguir key gratis de Gemini"
        )
        "Claude" -> Triple(
            "Claude es de pago (se recarga saldo). Entra en la consola de Anthropic, ve a «API Keys», pulsa " +
                "«Create Key», copia la clave (empieza por «sk-ant-») y pégala arriba.\nSi quieres algo gratis, elige Gemini.",
            "https://console.anthropic.com/settings/keys", "🔑 Abrir consola de Anthropic"
        )
        else -> Triple(
            "ChatGPT (API de OpenAI) es de pago (se recarga saldo). Entra en la plataforma de OpenAI, ve a «API keys», " +
                "pulsa «Create new secret key», cópiala (empieza por «sk-») y pégala arriba.\nSi quieres algo gratis, elige Gemini.",
            "https://platform.openai.com/api-keys", "🔑 Abrir OpenAI"
        )
    }
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("¿Cómo consigo la key de $provider?", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(steps, Modifier.padding(top = 4.dp), fontSize = 13.sp)
            Button(onClick = { openUrl(ctx, url) }, modifier = Modifier.padding(top = 8.dp)) { Text(btn) }
            Text(url, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
