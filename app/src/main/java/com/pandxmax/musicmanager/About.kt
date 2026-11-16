package com.pandxmax.musicmanager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val KOFI_URL = "https://ko-fi.com/pandxmax"

/** Novedades de cada versión de la app (lo más nuevo arriba). */
val CHANGELOG: List<Pair<String, String>> = listOf(
    "1.09" to "Novedades del programa de Windows (1.46–2.31) adaptadas al celular: biblioteca con Artistas y Géneros, " +
        "«Reproducir esta carpeta», búsqueda por artista/álbum/género, ir a la carpeta de una canción; onda de la canción en la " +
        "barra de avance, volumen y portada grande difuminada; letras con más fuentes, «Pegar letra» y musica.com; copiar y pegar " +
        "metadatos, quitar paréntesis, «Buscar la canción en…», recortar portadas no cuadradas, Rellenar con IA con fuentes " +
        "(iTunes, Deezer, MusicBrainz) y «Otro género»; Herramientas (repetidas, informe de la biblioteca, organizar en carpetas, " +
        "etiquetas desde el nombre, reparar recortes) y aviso de FLAC falso; botón Apoyar (Ko-fi), registro de errores y «Reportar un problema».",
    "1.08" to "Selección de carpetas en árbol (incluir, excluir, varias carpetas), carrusel de portadas, fondo con los colores de la portada y cola reordenable.",
    "1.07" to "Aleatorio y repetir visibles, cola rediseñada, la carpeta se elige primero y animación al cambiar de canción.",
    "1.06" to "Descarga de YouTube arreglada: yt-dlp se actualiza solo y prueba otras formas de conexión.",
    "1.05" to "Reproductor estilo Poweramp y textos que ya no se encimen ni se aplastan.",
    "1.04" to "Ajustes de la canción, SoundCloud, Rellenar con IA completo, iconos dibujados, logo del panda, convertir formato y carpetas en árbol.",
    "1.03" to "Ver en carpetas, biblioteca que se actualiza sola y ayuda para la key gratis de Gemini.",
    "1.02" to "Elegir la ubicación de la música, lista con formato y calidad, edición al tocar y app mucho más rápida.",
    "1.01" to "La app pasa a su propio repositorio.",
    "1.0" to "Primera versión.",
)

/** Registro de errores: si algo falla se anota aquí (y la app no se cierra en silencio sin dejar rastro). */
object ErrLog {
    private fun file() = File(Store.app.filesDir, "errores.txt")

    fun install() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            note("CIERRE INESPERADO", e)
            prev?.uncaughtException(t, e)
        }
    }

    @Synchronized
    fun note(where: String, e: Throwable) {
        try {
            val f = file()
            if (f.length() > 200_000) f.writeText(f.readText().takeLast(100_000))
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("[$ts] $where: ${e.javaClass.simpleName}: ${e.message}\n" + e.stackTrace.take(12).joinToString("\n") { "    at $it" } + "\n\n")
        } catch (_: Throwable) {
        }
    }

    fun text(): String = try { file().readText() } catch (_: Throwable) { "" }
    fun clear() { try { file().delete() } catch (_: Throwable) {} }
}

fun appVersion(ctx: Context): String = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" } catch (_: Throwable) { "?" }

@Composable
fun KofiButton(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Button(
        onClick = { openUrl(ctx, KOFI_URL) }, modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF72A5F2), contentColor = Color.White)
    ) {
        Image(painterResource(R.drawable.kofi), null, Modifier.height(20.dp))
        Text("  Invítame un café en Ko-fi", fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun AboutScreen() {
    val ctx = LocalContext.current
    var report by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    val ver = remember { appVersion(ctx) }
    Column(Modifier.fillMaxSize()) {
        Bar("Acerca de", { Nav.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.logo_panda), "Music Manager", Modifier.size(96.dp))
            Text("Music Manager", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            Text("Versión $ver · creado por PandxMax", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // Apoyar
            Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painterResource(R.drawable.kofi), null, Modifier.height(44.dp))
                    Text("¿Te gusta Music Manager?", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
                    Text(
                        "Es gratis y sin anuncios. Si te sirve, puedes apoyar el proyecto invitándome un café. ¡Gracias!",
                        fontSize = 13.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    KofiButton(Modifier.padding(top = 10.dp))
                    Text(KOFI_URL.removePrefix("https://"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }

            OutlinedButton(onClick = { report = true }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("🐞  Reportar un problema") }
            OutlinedButton(onClick = { openUrl(ctx, "https://github.com/PandxMax/Music-Manager-Android") }, modifier = Modifier.fillMaxWidth()) { Text("Ver el proyecto en GitHub") }
            TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Ocultar registro de errores" else "Ver registro de errores") }
            if (showLog) {
                val t = remember { ErrLog.text() }
                Text(
                    t.ifBlank { "Sin errores anotados. 👍" }.takeLast(4000), fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)
                )
                if (t.isNotBlank()) TextButton(onClick = { ErrLog.clear(); showLog = false }) { Text("Borrar registro") }
            }

            Text("Novedades", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp))
            for ((v, txt) in CHANGELOG) {
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text(
                        v, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(44.dp)
                    )
                    Text(txt, fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
    if (report) ReportDialog { report = false }
}

/** «Reportar un problema»: prepara el texto (versión, teléfono y errores anotados); nada se envía solo. */
@Composable
fun ReportDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var body by remember {
        mutableStateOf(
            "¿Qué estabas haciendo y qué pasó? (escríbelo aquí)\n\n\n" +
                "Music Manager Android ${appVersion(ctx)} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n" +
                ErrLog.text().takeLast(3000).ifBlank { "(sin errores anotados)" }
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reportar un problema") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Esto es lo que se enviará; puedes editarlo o borrar lo que no quieras compartir. Nada se envía solo: " +
                        "«Abrir en GitHub» abre la página con el texto listo (hace falta una cuenta gratuita). Si no tienes, usa «Copiar».",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 160.dp), textStyle = LocalTextStyle.current.copy(fontSize = 12.sp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                copyText(ctx, body)
                val q = "title=" + java.net.URLEncoder.encode("[${appVersion(ctx)}] ", "UTF-8") + "&body=" + java.net.URLEncoder.encode(body.take(5000), "UTF-8")
                openUrl(ctx, "https://github.com/PandxMax/Music-Manager-Android/issues/new?$q")
                onDismiss()
            }) { Text("Abrir en GitHub") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { copyText(ctx, body) }) { Text("Copiar") }
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
        }
    )
}

fun copyText(ctx: Context, s: String) {
    try {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Music Manager", s))
    } catch (_: Throwable) {
    }
}

fun pasteText(ctx: Context): String = try {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
} catch (_: Throwable) { "" }
