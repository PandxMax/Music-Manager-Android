package com.pandxmax.musicmanager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Convierte la carpeta elegida en el selector del sistema a una ruta normal. */
fun treeToPath(uri: Uri): String? {
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

/** Árbol de carpetas que contienen música: carpeta → cuántas canciones hay dentro (contando subcarpetas). */
private class MusicTree(val counts: Map<String, Int>, val children: Map<String, List<String>>, val roots: List<String>)

private fun volumeRoot(path: String): String {
    val internal = Environment.getExternalStorageDirectory().absolutePath
    if (path == internal || path.startsWith("$internal/")) return internal
    val m = Regex("^(/storage/[^/]+)").find(path)
    return m?.value ?: (File(path).parent ?: path)
}

fun volumeLabel(path: String): String {
    val internal = Environment.getExternalStorageDirectory().absolutePath
    return if (path == internal || path.startsWith("$internal/")) Build.MODEL ?: "Memoria interna" else "Tarjeta SD / USB"
}

private fun buildTree(ctx: Context, extra: List<String>): MusicTree {
    val files = ArrayList<String>()
    try {
        val uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        @Suppress("DEPRECATION")
        ctx.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { c ->
            while (c.moveToNext()) c.getString(0)?.let { files.add(it) }
        }
    } catch (_: Throwable) {
    }
    // carpetas añadidas a mano que Android quizá no tiene indexadas
    for (r in extra) {
        try {
            File(r).walkTopDown().maxDepth(6).onEnter { !it.name.startsWith(".") }
                .filter { it.isFile && it.extension.lowercase() in AUDIO_EXT }.take(20000).forEach { files.add(it.absolutePath) }
        } catch (_: Throwable) {
        }
    }
    val counts = HashMap<String, Int>()
    val children = HashMap<String, MutableSet<String>>()
    val tops = LinkedHashSet<String>()
    for (p in files.toSet()) {
        if (p.contains("/.") || p.contains("/Android/data/") || p.contains("/Android/media/")) continue
        val vr = volumeRoot(p)
        var dir = File(p).parent ?: continue
        // sube carpeta por carpeta hasta la raíz del almacenamiento
        while (true) {
            counts[dir] = (counts[dir] ?: 0) + 1
            val par = File(dir).parent ?: break
            if (dir == vr) break
            if (par == vr) { tops.add(dir); break }
            children.getOrPut(par) { LinkedHashSet() }.add(dir)
            dir = par
        }
    }
    for (r in extra) if (counts.containsKey(r) || File(r).isDirectory) tops.add(r)
    // si una carpeta añadida está dentro de otra raíz, no se repite arriba
    val roots = tops.filter { t -> tops.none { o -> o != t && t.startsWith("$o/") } }
        .sortedBy { File(it).name.lowercase() }
    return MusicTree(counts, children.mapValues { e -> e.value.sortedBy { File(it).name.lowercase() } }, roots)
}

private data class Node(val path: String, val depth: Int)

/**
 * «Selección de carpetas»: ▶ abre las subcarpetas, ✕ excluye una carpeta y la casilla la incluye.
 * Es lo primero que se ve al abrir la app por primera vez.
 */
@Composable
fun LocationScreen() {
    val ctx = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    var hasAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    var tree by remember { mutableStateOf<MusicTree?>(null) }
    var extra by remember { mutableStateOf(Store.extraRoots) }
    val inc = remember { mutableStateListOf<String>().apply { addAll(Store.musicDirs) } }
    val exc = remember { mutableStateListOf<String>().apply { addAll(Store.excludedDirs) } }
    val open = remember { mutableStateListOf<String>() }
    var msg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { while (!hasAccess) { delay(700); hasAccess = Environment.isExternalStorageManager() } }
    LaunchedEffect(hasAccess, extra) { tree = withContext(Dispatchers.IO) { buildTree(ctx, extra + Store.musicDirs) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val p = uri?.let { treeToPath(it) }
        if (p == null) { if (uri != null) msg = "No se pudo usar esa carpeta"; return@rememberLauncherForActivityResult }
        if (p !in extra) { extra = extra + p; Store.extraRoots = extra; Store.save() }
        if (inc.none { p == it || p.startsWith("$it/") }) inc.add(p)
        exc.remove(p)
    }
    val canCancel = Store.musicDirs.isNotEmpty()
    BackHandler(enabled = canCancel) { Store.choosingDir = false }

    fun includedBy(p: String) = inc.firstOrNull { p == it || p.startsWith("$it/") }
    fun excludedBy(p: String) = exc.firstOrNull { p == it || p.startsWith("$it/") }

    // filas visibles del árbol (las abiertas muestran sus hijas)
    val rows = remember(tree, open.toList()) {
        val t = tree ?: return@remember emptyList()
        val out = ArrayList<Node>()
        fun add(p: String, d: Int) {
            out.add(Node(p, d))
            if (p in open) t.children[p]?.forEach { add(it, d + 1) }
        }
        t.roots.forEach { add(it, 0) }
        out
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Selección de carpetas", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Marca las carpetas donde está tu música. ▶ abre las subcarpetas y ✕ excluye una carpeta que no quieras.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (canCancel) IconBtn("✕") { Store.choosingDir = false }
        }

        if (!hasAccess) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Para ver todas tus carpetas con música necesito el permiso «Acceso a todos los archivos».", fontSize = 13.sp)
                    Button(onClick = {
                        try {
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName)))
                        } catch (_: Exception) {
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    }, modifier = Modifier.padding(top = 8.dp)) { Text("Dar permiso") }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                tree == null -> Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("  Buscando carpetas con música…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                rows.isEmpty() -> Text(
                    if (hasAccess) "No encontré música todavía.\nUsa «Agregar carpeta o almacenamiento»." else "Da el permiso o agrega una carpeta.",
                    Modifier.align(Alignment.Center).padding(24.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(rows, key = { it.path }) { n ->
                        val t = tree!!
                        val kids = t.children[n.path].orEmpty()
                        val isOpen = n.path in open
                        val incBy = includedBy(n.path)
                        val excBy = excludedBy(n.path)
                        val arrow by animateFloatAsState(if (isOpen) 90f else 0f, label = "flecha")
                        val dimmed = excBy != null
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = kids.isNotEmpty()) { if (isOpen) open.remove(n.path) else open.add(n.path) }
                                .padding(start = (8 + n.depth * 22).dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                                if (kids.isNotEmpty()) Icon(Icons.Filled.PlayArrow, "Abrir", Modifier.size(22.dp).rotate(arrow), tint = MaterialTheme.colorScheme.onSurface)
                            }
                            Icon(
                                if (isOpen) Icons.Filled.FolderOpen else Icons.Filled.Folder, null, Modifier.size(26.dp),
                                tint = if (incBy != null && !dimmed) accent else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                                Text(
                                    File(n.path).name.ifEmpty { n.path }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (dimmed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    (if (n.depth == 0) volumeLabel(n.path) + " · " else "") + plural(t.counts[n.path] ?: 0, "canción", "canciones"),
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                                )
                            }
                            // ✕ excluir
                            val exHere = n.path in exc
                            Box(
                                Modifier.size(40.dp).clip(CircleShape)
                                    .background(if (exHere) MaterialTheme.colorScheme.error.copy(alpha = 0.18f) else Color.Transparent)
                                    .clickable {
                                        if (exHere) exc.remove(n.path) else { exc.add(n.path); inc.remove(n.path) }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Close, "Excluir", Modifier.size(22.dp),
                                    tint = when {
                                        exHere -> MaterialTheme.colorScheme.error
                                        excBy != null -> MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            // casilla incluir
                            val here = n.path in inc
                            val inherited = incBy != null && !here
                            Box(
                                Modifier.padding(start = 6.dp).size(28.dp).clip(RoundedCornerShape(6.dp))
                                    .background(if ((here || inherited) && !dimmed) accent.copy(alpha = if (here) 1f else 0.45f) else Color.Transparent)
                                    .border(2.dp, if ((here || inherited) && !dimmed) Color.Transparent else MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(6.dp))
                                    .clickable {
                                        if (here) inc.remove(n.path)
                                        else if (inherited) { exc.add(n.path) }
                                        else {
                                            inc.removeAll { it.startsWith(n.path + "/") }
                                            exc.removeAll { it == n.path }
                                            inc.add(n.path)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if ((here || inherited) && !dimmed) Icon(Icons.Filled.Check, null, Modifier.size(20.dp), tint = Color.White)
                            }
                        }
                    }
                }
            }
            msg?.let { Snackbar(Modifier.align(Alignment.BottomCenter).padding(12.dp)) { Text(it) } }
        }

        HorizontalDivider()
        TextButton(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Filled.CreateNewFolder, null, Modifier.size(20.dp))
            Text("  Agregar carpeta o almacenamiento", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider()
        Button(
            enabled = inc.isNotEmpty(),
            onClick = {
                Store.setFolders(inc.toList(), exc.filter { e -> inc.any { e.startsWith("$it/") } })
                Store.choosingDir = false
                Lib.requestScanSoon(200)
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).height(52.dp)
        ) {
            Text(if (inc.isEmpty()) "Marca al menos una carpeta" else "Guardar y escanear", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
