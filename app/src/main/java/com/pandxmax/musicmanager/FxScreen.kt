package com.pandxmax.musicmanager

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private fun audioInfo(path: String): Pair<Int, Double> {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(path)
        val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val sr = if (Build.VERSION.SDK_INT >= 31) r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 44100 else 44100
        sr to ms / 1000.0
    } catch (_: Throwable) {
        44100 to 0.0
    } finally {
        try { r.release() } catch (_: Throwable) {}
    }
}

@Composable
private fun FxSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, default: Float, text: String, steps: Int = 0, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 14.sp)
            Text(
                text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = if (value != default) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { onChange(default) }.padding(4.dp)
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps, modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun FxCheck(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(text, fontSize = 14.sp)
    }
}

private fun db(v: Int) = (if (v > 0) "+" else "") + "$v dB"
private fun x2(v: Double) = String.format(Locale.US, "%.2f×", v)

/** «Ajustes de la canción»: graves, medios, agudos, tempo, tono, bucle, eco… (como en el programa de Windows). */
@Composable
fun FxScreen(path: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember(path) { File(path) }
    var p by remember { mutableStateOf(FxParams()) }
    var tab by remember { mutableIntStateOf(0) }
    var presetMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    var askReplace by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf(false) }
    val info by produceState(44100 to 0.0, path) { value = withContext(Dispatchers.IO) { audioInfo(path) } }
    val mp = remember { arrayOfNulls<MediaPlayer>(1) }
    val previewFile = remember { File(ctx.cacheDir, "mmfx_preview.wav") }

    fun stopPreview() {
        try { mp[0]?.stop(); mp[0]?.release() } catch (_: Throwable) {}
        mp[0] = null
        previewing = false
    }
    DisposableEffect(Unit) { onDispose { stopPreview(); if (busy) Ff.cancel(); previewFile.delete() } }
    BackHandler(enabled = true) { if (busy) Ff.cancel(); stopPreview(); Nav.pop() }

    fun preview() {
        if (busy) return
        stopPreview()
        val offset = if (PlayerManager.currentPath == path) PlayerManager.position / 1000.0 else 0.0
        busy = true; progress = 0; status = "Preparando la muestra… (la primera vez prepara los componentes)"
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                previewFile.delete()
                Ff.run(ctx, Fx.args(path, previewFile.absolutePath, p, info.first, info.second, preview = 30, offset = offset), 30.0) { progress = it }
            }
            busy = false
            if (err != null || !previewFile.exists() || previewFile.length() == 0L) { status = "No se pudo crear la muestra: " + (err ?: "sin salida"); return@launch }
            if (PlayerManager.isPlaying) PlayerManager.toggle()
            try {
                val m = MediaPlayer()
                m.setDataSource(previewFile.absolutePath)
                m.setOnCompletionListener { previewing = false }
                m.prepare(); m.start()
                mp[0] = m
                previewing = true
                status = "Reproduciendo la muestra. Puedes mover los ajustes y volver a pulsar «Escuchar»."
            } catch (e: Throwable) {
                status = "No se pudo reproducir la muestra: ${e.message}"
            }
        }
    }

    fun save(replace: Boolean) {
        if (busy) return
        if (p.isDefault()) { status = "No has cambiado nada todavía."; return }
        stopPreview()
        val ext = file.extension.lowercase().let { if (it in FX_CODECS) it else "mp3" }
        val dst = if (replace) File(file.parentFile, ".mmfx_tmp.$ext") else Fx.uniqueName(file, ext = ext)
        val outDur = Fx.filters(p, info.first, info.second).second.let { if (it > 0) it else if (info.second > 0) info.second else 60.0 }
        busy = true; progress = 0; status = "Aplicando los ajustes… (según la duración puede tardar un momento)"
        scope.launch {
            val err = withContext(Dispatchers.IO) { Ff.run(ctx, Fx.args(path, dst.absolutePath, p, info.first, info.second), outDur) { progress = it } }
            if (err != null || !dst.exists() || dst.length() == 0L) {
                dst.delete(); busy = false; status = "No se pudo aplicar: " + (err ?: "sin salida"); return@launch
            }
            var final = dst
            if (replace) {
                if (PlayerManager.currentPath == path) PlayerManager.stop()
                final = if (ext == file.extension.lowercase()) file else File(file.parentFile, file.nameWithoutExtension + "." + ext)
                val ok = withContext(Dispatchers.IO) {
                    val moved = dst.renameTo(final) || run { dst.copyTo(final, overwrite = true); dst.delete() }
                    if (moved && final.absolutePath != file.absolutePath) file.delete()
                    moved
                }
                if (!ok) { busy = false; status = "No se pudo reemplazar el archivo. La versión nueva quedó en ${dst.name}"; return@launch }
            }
            busy = false
            Covers.forget(""); Store.coverVersion++; Store.libVersion++
            Lib.requestScan()
            status = (if (replace) "✔ Original reemplazada: " else "✔ Guardada como canción nueva: ") + final.name
        }
    }

    Column(Modifier.fillMaxSize()) {
        Bar("Ajustes de la canción", { if (busy) Ff.cancel(); stopPreview(); Nav.pop() })
        Text(
            file.name, Modifier.padding(horizontal = 14.dp, vertical = 4.dp), fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(onClick = { presetMenu = true }) { Text("Ajuste rápido ▾") }
                DropdownMenu(presetMenu, { presetMenu = false }) {
                    for ((n, v) in FX_PRESETS) DropdownMenuItem(text = { Text(n) }, onClick = { presetMenu = false; p = v })
                }
            }
            TextButton(onClick = { p = FxParams() }) { Text("↺ Restablecer todo") }
        }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            listOf("Sonido", "Velocidad y tono", "Bucle y recorte", "Efectos").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 13.sp) })
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp)) {
            when (tab) {
                0 -> {
                    FxSlider("Graves", p.bass.toFloat(), -15f..15f, 0f, db(p.bass), 29) { p = p.copy(bass = it.toInt()) }
                    FxSlider("Medios", p.mid.toFloat(), -15f..15f, 0f, db(p.mid), 29) { p = p.copy(mid = it.toInt()) }
                    FxSlider("Agudos", p.treble.toFloat(), -15f..15f, 0f, db(p.treble), 29) { p = p.copy(treble = it.toInt()) }
                    FxSlider("Volumen", p.volume.toFloat(), -20f..12f, 0f, db(p.volume), 31) { p = p.copy(volume = it.toInt()) }
                    FxCheck("Normalizar volumen (que suene parejo, estilo streaming)", p.normalize) { p = p.copy(normalize = it) }
                }
                1 -> {
                    FxSlider("Tempo (velocidad sin cambiar el tono)", p.tempo.toFloat(), 0.5f..2f, 1f, x2(p.tempo)) { p = p.copy(tempo = Math.round(it * 100) / 100.0) }
                    FxSlider("Tono (semitonos, sin cambiar la velocidad)", p.pitch.toFloat(), -12f..12f, 0f, (if (p.pitch > 0) "+" else "") + "${p.pitch} st", 23) { p = p.copy(pitch = it.toInt()) }
                    FxSlider("Velocidad (tasa: velocidad y tono juntos)", p.rate.toFloat(), 0.5f..2f, 1f, x2(p.rate)) { p = p.copy(rate = Math.round(it * 100) / 100.0) }
                    Text(
                        "Tempo = más rápida o lenta con el mismo tono · Tono = más aguda o grave a la misma velocidad · Velocidad = las dos cosas juntas (como un vinilo).",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)
                    )
                }
                2 -> {
                    val mx = (if (info.second > 0) info.second else 600.0).toFloat()
                    FxSlider("Empezar en", p.trimStart.toFloat().coerceAtMost(mx), 0f..mx, 0f, String.format(Locale.US, "%.1f s", p.trimStart)) { p = p.copy(trimStart = Math.round(it * 10) / 10.0) }
                    FxSlider("Terminar en", p.trimEnd.toFloat().coerceAtMost(mx), 0f..mx, 0f, if (p.trimEnd <= 0) "hasta el final" else String.format(Locale.US, "%.1f s", p.trimEnd)) { p = p.copy(trimEnd = Math.round(it * 10) / 10.0) }
                    FxSlider("Repetir (bucle)", p.loops.toFloat(), 1f..20f, 1f, if (p.loops == 1) "1 vez" else "${p.loops} veces", 18) { p = p.copy(loops = it.toInt()) }
                    FxCheck("Reproducir al revés", p.reverse) { p = p.copy(reverse = it) }
                    Text(
                        "El bucle repite el fragmento elegido (o la canción entera) las veces que indiques y lo guarda como una sola pista.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)
                    )
                }
                else -> {
                    FxSlider("Eco", p.echo.toFloat(), 0f..100f, 0f, "${p.echo} %") { p = p.copy(echo = it.toInt()) }
                    FxSlider("Reverberación (sensación de sala)", p.reverb.toFloat(), 0f..100f, 0f, "${p.reverb} %") { p = p.copy(reverb = it.toInt()) }
                    FxSlider("Ancho estéreo", p.width.toFloat(), 0f..300f, 100f, "${p.width} %") { p = p.copy(width = it.toInt()) }
                    FxSlider("Entrada gradual", p.fadeIn.toFloat(), 0f..15f, 0f, String.format(Locale.US, "%.1f s", p.fadeIn)) { p = p.copy(fadeIn = Math.round(it * 10) / 10.0) }
                    FxSlider("Salida gradual", p.fadeOut.toFloat(), 0f..15f, 0f, String.format(Locale.US, "%.1f s", p.fadeOut)) { p = p.copy(fadeOut = Math.round(it * 10) / 10.0) }
                    FxCheck("Convertir a mono", p.mono) { p = p.copy(mono = it) }
                    FxCheck("Quitar la voz (karaoke; funciona mejor en grabaciones estéreo)", p.karaoke) { p = p.copy(karaoke = it) }
                }
            }
            Text("Toca el valor de un ajuste para volver a lo normal.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                if (busy) LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxWidth().padding(bottom = 6.dp))
                if (status.isNotEmpty()) Text(status, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, onClick = { preview() }, modifier = Modifier.weight(1f)) { Text("▶ Escuchar 30 s") }
                    OutlinedButton(enabled = previewing, onClick = { stopPreview(); status = "" }, modifier = Modifier.weight(1f)) { Text("⏹ Parar") }
                }
                Button(enabled = !busy, onClick = { save(false) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("💾 Guardar como canción nueva") }
                TextButton(enabled = !busy, onClick = { if (p.isDefault()) status = "No has cambiado nada todavía." else askReplace = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Reemplazar la original")
                }
            }
        }
    }

    if (askReplace) {
        AlertDialog(
            onDismissRequest = { askReplace = false },
            title = { Text("Reemplazar la original") },
            text = { Text("Se sustituirá el archivo original por la versión con los ajustes. No se puede deshacer. ¿Continuar?") },
            confirmButton = { TextButton(onClick = { askReplace = false; save(true) }) { Text("Sí") } },
            dismissButton = { TextButton(onClick = { askReplace = false }) { Text("No") } }
        )
    }
}

/** Convertir una o varias canciones a otro formato (se crea un archivo nuevo junto al original). */
@Composable
fun ConvertDialog(files: List<File>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var fmt by remember { mutableStateOf(CONVERT_FORMATS[0]) }
    var deleteOrig by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("🔄 Convertir formato") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(if (files.size == 1) files[0].name else "${files.size} canciones", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                for (f in CONVERT_FORMATS) {
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { fmt = f }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(fmt == f, { fmt = f }, enabled = !busy)
                        Text(f.label, fontSize = 14.sp)
                    }
                }
                FxCheck("Borrar el original después de convertir", deleteOrig) { if (!busy) deleteOrig = it }
                Text(
                    "Se conservan las etiquetas (y la portada en MP3, M4A y FLAC). Pasar de MP3 a FLAC no mejora la calidad.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (busy) LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxWidth().padding(top = 8.dp))
                if (status.isNotEmpty()) Text(status, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    var ok = 0
                    val errs = ArrayList<String>()
                    for ((i, f) in files.withIndex()) {
                        status = "Convirtiendo ${i + 1} de ${files.size}: ${f.name}"
                        val dst = if (f.extension.lowercase() == fmt.ext) Fx.uniqueName(f, " (convertida)", fmt.ext)
                        else File(f.parentFile, f.nameWithoutExtension + "." + fmt.ext).let { if (it.exists()) Fx.uniqueName(f, " (convertida)", fmt.ext) else it }
                        val dur = withContext(Dispatchers.IO) { audioInfo(f.absolutePath).second }
                        val err = withContext(Dispatchers.IO) { Ff.run(ctx, convertArgs(f, dst, fmt), if (dur > 0) dur else 180.0) { progress = it } }
                        if (err == null && dst.exists() && dst.length() > 0) {
                            ok++
                            if (deleteOrig) { if (PlayerManager.currentPath == f.absolutePath) PlayerManager.stop(); withContext(Dispatchers.IO) { f.delete() } }
                        } else { dst.delete(); errs.add("${f.name}: ${err ?: "sin salida"}") }
                    }
                    busy = false
                    Lib.requestScan()
                    status = "✔ $ok de ${files.size} convertida(s)" + if (errs.isNotEmpty()) "\n" + errs.first() else ""
                }
            }) { Text("Convertir") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cerrar") } }
    )
}
