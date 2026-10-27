package com.pandxmax.musicmanager

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File

class SpecItem(val uri: Uri, val name: String, val lossless: Boolean)

sealed class Screen {
    object Library : Screen()
    class Editor(val paths: List<String>) : Screen()
    object Player : Screen()
    object Queue : Screen()
    class Spectrum(val items: List<SpecItem>) : Screen()
    object Youtube : Screen()
    class Fx(val path: String) : Screen()
    object Settings : Screen()
}

object Nav {
    val stack = mutableStateListOf<Screen>(Screen.Library)
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.size - 1) }
}

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        // Al volver a la app se revisa la carpeta en silencio por si cambió algo
        if (Environment.isExternalStorageManager()) Lib.requestScanSoon(400)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlayerManager.connect(this)
        LibWatcher.register(this)
        if (Build.VERSION.SDK_INT >= 33) {
            val l = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
            l.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { MMTheme { Root() } }
    }
}

@Composable
fun Root() {
    var hasAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    LaunchedEffect(Unit) {
        while (true) {
            if (!hasAccess) hasAccess = Environment.isExternalStorageManager()
            if (PlayerManager.isPlaying) PlayerManager.tick()
            delay(if (!hasAccess) 700 else if (Nav.stack.lastOrNull() is Screen.Player) 200 else 500)
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Lo primero que se pide es la carpeta de la música; después, el permiso para poder editarla
        if (Store.musicDirs.isEmpty() || Store.choosingDir) {
            Box(Modifier.fillMaxSize().systemBarsPadding()) { LocationScreen() }
        } else if (!hasAccess) {
            Box(Modifier.fillMaxSize().systemBarsPadding()) { PermissionScreen() }
        } else {
            val top = Nav.stack.last()
            BackHandler(enabled = Nav.stack.size > 1) { Nav.pop() }
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Box(Modifier.weight(1f)) {
                    when (top) {
                        is Screen.Library -> LibraryScreen()
                        is Screen.Editor -> EditorScreen(top.paths)
                        is Screen.Player -> PlayerScreen()
                        is Screen.Queue -> QueueScreen()
                        is Screen.Spectrum -> SpectrumScreen(top.items)
                        is Screen.Youtube -> YoutubeScreen()
                        is Screen.Fx -> FxScreen(top.path)
                        is Screen.Settings -> SettingsScreen()
                    }
                }
                if (top !is Screen.Player && top !is Screen.Queue && PlayerManager.currentPath != null) MiniPlayer()
            }
        }
    }
}

@Composable
fun PermissionScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(R.drawable.logo_panda), contentDescription = "Music Manager",
            modifier = Modifier.size(110.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text("Music Manager", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(
            "Un paso más: para editar etiquetas y portadas dentro de tus canciones necesito el permiso " +
                "«Acceso a todos los archivos».\n\nPulsa el botón y activa el interruptor de Music Manager.",
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = {
            try {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName)))
            } catch (_: Exception) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }) { Text("Dar permiso") }
        TextButton(onClick = { Store.choosingDir = true }) { Text("Cambiar la carpeta de música") }
    }
}
