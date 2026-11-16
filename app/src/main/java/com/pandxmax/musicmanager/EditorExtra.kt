package com.pandxmax.musicmanager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** «Pegar metadatos…»: eliges qué campos pegar (título y pista vienen desmarcados). */
@Composable
fun PasteMetaDialog(onApply: (Map<String, String>, ByteArray?) -> Unit, onDismiss: () -> Unit) {
    val clip = Store.clipMeta ?: emptyMap()
    val checks = remember { mutableStateMapOf<String, Boolean>().apply { for (k in clip.keys) put(k, k != "title" && k != "track") } }
    var cover by remember { mutableStateOf(Store.clipCover != null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pegar metadatos") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Marca lo que quieres pegar en esta(s) canción(es).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for ((id, label) in FIELDS) {
                    val v = clip[id] ?: continue
                    Row(Modifier.fillMaxWidth().clickable { checks[id] = !(checks[id] ?: false) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checks[id] ?: false, { checks[id] = it })
                        Column {
                            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(v, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (Store.clipCover != null) Row(Modifier.fillMaxWidth().clickable { cover = !cover }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(cover, { cover = it }); Text("Portada")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(clip.filterKeys { checks[it] == true }, if (cover) Store.clipCover else null) }) { Text("Pegar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/** «Summer (Official Video) [HD]» → «Summer». Quita lo que va entre paréntesis (y si quieres corchetes), también anidado. */
fun stripParens(name: String, brackets: Boolean): String {
    var s = name
    var prev: String
    do {
        prev = s
        s = s.replace(Regex("\\s*\\([^()]*\\)"), "")
        if (brackets) s = s.replace(Regex("\\s*\\[[^\\[\\]]*]"), "")
    } while (s != prev)
    return s.replace(Regex("\\s{2,}"), " ").trim(' ', '-', '_').ifBlank { name }
}

@Composable
fun ParensDialog(files: List<File>, onDone: (List<String>?) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var brackets by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    val preview = remember(brackets) { files.map { it to stripParens(it.nameWithoutExtension, brackets) } }
    val changes = preview.count { it.first.nameWithoutExtension != it.second }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Quitar paréntesis del nombre") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Se cambia el nombre del archivo y el título de la canción.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.clickable { brackets = !brackets }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(brackets, { brackets = it }); Text("También lo que va entre [corchetes]")
                }
                for ((f, n) in preview.take(60)) {
                    val same = f.nameWithoutExtension == n
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(f.nameWithoutExtension, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (same) "(sin cambios)" else "→ $n", fontSize = 14.sp, fontWeight = if (same) FontWeight.Normal else FontWeight.SemiBold,
                            color = if (same) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                    }
                }
                if (preview.size > 60) Text("…y ${preview.size - 60} más", fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = changes > 0 && !busy, onClick = {
                busy = true
                scope.launch {
                    val out = withContext(Dispatchers.IO) {
                        files.map { f ->
                            val n = stripParens(f.nameWithoutExtension, brackets)
                            if (n == f.nameWithoutExtension) return@map f.absolutePath
                            val dst = File(f.parentFile, n + "." + f.extension)
                            try {
                                val title = MetaCache.get(f).title
                                if (title.isNotBlank()) { val nt = stripParens(title, brackets); if (nt != title) Tags.write(f, mapOf("title" to nt), null, false, null) }
                            } catch (e: Throwable) { ErrLog.note("quitar paréntesis (título)", e) }
                            if (!dst.exists() && f.renameTo(dst)) {
                                if (PlayerManager.currentPath == f.absolutePath) PlayerManager.currentPath = dst.absolutePath
                                dst.absolutePath
                            } else f.absolutePath
                        }
                    }
                    busy = false
                    onDone(out)
                }
            }) { Text(if (busy) "Aplicando…" else "Aplicar ($changes)") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") } }
    )
}

/**
 * Portada que no es cuadrada (por ejemplo 712×1000): se elige qué parte queda en el cuadrado
 * moviendo el recuadro y su tamaño, y el tamaño final.
 */
@Composable
fun CropDialog(bytes: ByteArray, onDone: (ByteArray) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
    if (bmp == null) { onDismiss(); return }
    val minSide = minOf(bmp.width, bmp.height)
    var side by remember { mutableFloatStateOf(minSide.toFloat()) }          // tamaño del cuadrado (px de la imagen)
    var cx by remember { mutableFloatStateOf((bmp.width - minSide) / 2f) }  // esquina sup. izq. (px de la imagen)
    var cy by remember { mutableFloatStateOf((bmp.height - minSide) / 2f) }
    var outSize by remember { mutableIntStateOf(minOf(Store.coverMax, 1200).coerceAtMost(maxOf(500, minSide))) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var busy by remember { mutableStateOf(false) }

    fun clamp() {
        side = side.coerceIn(minSide * 0.3f, minSide.toFloat())
        cx = cx.coerceIn(0f, bmp.width - side); cy = cy.coerceIn(0f, bmp.height - side)
    }
    fun crop(centered: Boolean) {
        busy = true
        scope.launch {
            val out = withContext(Dispatchers.Default) {
                val s = if (centered) minSide else side.toInt()
                val x = if (centered) (bmp.width - s) / 2 else cx.toInt()
                val y = if (centered) (bmp.height - s) / 2 else cy.toInt()
                val c = Bitmap.createBitmap(bmp, x.coerceAtLeast(0), y.coerceAtLeast(0), s.coerceAtMost(bmp.width - x), s.coerceAtMost(bmp.height - y))
                val sc = Bitmap.createScaledBitmap(c, outSize, outSize, true)
                val bo = ByteArrayOutputStream(); sc.compress(Bitmap.CompressFormat.JPEG, 92, bo); bo.toByteArray()
            }
            busy = false
            onDone(out)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Recortar la portada en cuadrado") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("La imagen mide ${bmp.width}×${bmp.height}. Arrastra el recuadro hasta la parte que quieres.", fontSize = 12.sp)
                val ratio = bmp.width.toFloat() / bmp.height
                Box(Modifier.fillMaxWidth().padding(top = 8.dp).aspectRatio(ratio).onSizeChanged { box = it }) {
                    Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    val k = if (box.width > 0) box.width / bmp.width.toFloat() else 1f
                    Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                        detectDragGestures { ch, d -> ch.consume(); cx += d.x / k; cy += d.y / k; clamp() }
                    }) {
                        val l = cx * k; val t = cy * k; val sz = side * k
                        val dim = Color.Black.copy(alpha = 0.55f)
                        drawRect(dim, Offset(0f, 0f), Size(size.width, t))
                        drawRect(dim, Offset(0f, t + sz), Size(size.width, size.height - t - sz))
                        drawRect(dim, Offset(0f, t), Size(l, sz))
                        drawRect(dim, Offset(l + sz, t), Size(size.width - l - sz, sz))
                        drawRect(Color.White, Offset(l, t), Size(sz, sz), style = Stroke(2.dp.toPx()))
                    }
                }
                Text("Tamaño del recuadro", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Slider(value = side, onValueChange = { side = it; clamp() }, valueRange = minSide * 0.3f..minSide.toFloat())
                Text("Tamaño final", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (s in listOf(500, 600, 800, 1000, 1200)) FilterChip(selected = outSize == s, onClick = { outSize = s }, label = { Text("$s", fontSize = 12.sp) })
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { crop(false) }) { Text(if (busy) "Recortando…" else "Recortar") } },
        dismissButton = {
            Row {
                TextButton(enabled = !busy, onClick = { crop(true) }) { Text("Centrada") }
                TextButton(enabled = !busy, onClick = {
                    // dejarla como está (sin recortar)
                    onDone(bytes)
                }) { Text("Sin recortar") }
            }
        }
    )
}
