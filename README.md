# Music Manager para Android

Versión para Android de Music Manager (Kotlin + Jetpack Compose). Creada por **PandxMax**.

[![Descargar APK](https://img.shields.io/badge/Descargar-APK-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/PandxMax/Music-Manager-Android/releases/latest/download/MusicManager-Android.apk)

La versión para Windows está en [PandxMax/MusicManager](https://github.com/PandxMax/MusicManager).

## Qué incluye
- **Tu música en una lista**: eliges la carpeta de tu música (cambiable cuando quieras) y aparecen todas las canciones
  con su formato y calidad (Hi-Res, Sin pérdida, Alta, Media, Baja), o **en carpetas** como un árbol. Se actualiza sola. Buscar, selección múltiple y eliminar.
- **Al tocar una canción** se abre la edición de datos y portada, con **▶ Reproducir** y **📊 Analizador de calidad** arriba.
- **Reproductor** en segundo plano con notificación: cola, aleatorio, repetir, listas guardadas y mini-reproductor.
  Los colores de la app cambian según la portada de la canción.
- **Editor de metadatos** (uno o varios archivos a la vez): Título, Intérprete, Álbum, Intérprete del álbum,
  Compositor, Género, Año, Pista, Número de disco, Comentario y Directorio; etiquetas técnicas (codec, tipo de etiqueta,
  bitrate, frecuencia, duración, modo, tamaño). Los MP3 se guardan en ID3v2.3 (lo que entiende Windows).
- **Portadas**: elegir de la galería, **buscar automáticamente** (iTunes, Deezer y SoundCloud, varias opciones), quitar, guardar como
  `cover.jpg`, ver en grande, y si no hay nada, abrir Google Imágenes.
- **Letras** con LRCLIB (sincronizadas) y edición.
- **IA** (Gemini gratis, Claude o ChatGPT con tu propia API key): rellenar metadatos y sugerir géneros.
  La key gratuita de Gemini se saca en [Google AI Studio](https://aistudio.google.com/apikey); en Ajustes está explicado paso a paso.
- **MusicBrainz** y **Discogs** (token) para rellenar etiquetas.
- **Botones web**: YouTube, Spotify, Tidal, Qobuz y Wikipedia.
- **Analizador de calidad** (espectrograma) de audio, con **comparación** de canciones de distintos lugares.
- **Descargar de YouTube o SoundCloud a MP3** (yt-dlp) y luego editar metadatos y portada.
- **Ajustes de la canción**: ecualizador (graves/medios/agudos), volumen, normalizar, tempo, tono, velocidad, recorte, bucle,
  al revés, eco, reverberación, ancho estéreo, mono, karaoke y fundidos; ajustes rápidos (Nightcore, Slowed + reverb…),
  muestra de 30 s y guardar como canción nueva o reemplazar la original.
- **Convertir formato** a MP3, M4A, FLAC, OPUS, OGG o WAV conservando etiquetas y portada.
- Aviso «¿Desea guardar los cambios?» al salir, y botón 💾 para guardar.

## Cómo obtener el APK
1. Pulsa el botón **Descargar APK** de arriba (o ve a **Releases**).
2. Para generar una versión nueva: **Actions → «Crear APK» → Run workflow**; al terminar aparece en **Releases**.
3. Descárgalo en el teléfono e instálalo (permite instalar apps de origen desconocido si te lo pide).
4. La primera vez, concede el permiso **«Acceso a todos los archivos»** para poder editar etiquetas y mover carpetas.

Requiere Android 11 o superior.

> Descarga solo música o contenido que tengas derecho a guardar.

## Historial de versiones
- **1.07** — Aleatorio y repetir ahora se ven activados (relleno de color, punto debajo y texto «Aleatorio · Repetir todo»);
  antes la app no se enteraba del cambio. Cola de reproducción rediseñada («Sonando ahora» con barras animadas,
  «A continuación» y «Ya sonaron», con título y artista). Al abrir la app por primera vez lo primero es elegir la carpeta
  de la música. Animación al cambiar de canción: la portada se desliza de costado como una ventanita (también en el mini
  reproductor) y el fondo cambia suavemente.
- **1.06** — Arreglada la descarga de YouTube («Please sign in» / «Precondition check failed»): el componente de descargas
  (yt-dlp) ahora se actualiza solo desde GitHub antes de descargar; si YouTube rechaza la petición se prueban otras formas
  de conexión y, como último recurso, la versión más nueva (nightly). Los errores se explican en lenguaje claro (con los
  detalles técnicos aparte), la portada del vídeo se incrusta en el MP3 y en Ajustes se puede ver y actualizar la versión.
- **1.05** — Reproductor nuevo al estilo Poweramp: portada grande que se desliza para cambiar de canción, fondo con la portada
  difuminada, barra de progreso gruesa, botones grandes, calidad del archivo, cola, letra sincronizada (LRC) y accesos a
  Ajustes, Editar y Calidad; mini reproductor con título y artista. Diseño más limpio: los textos ya no se encimen ni se
  aplastan (también con la letra del teléfono agrandada) y los botones de edición son iconos con nombre.
- **1.04** — Se pasan a Android las novedades del programa de Windows (1.38–1.45): **Ajustes de la canción** (graves, medios,
  agudos, volumen, normalizar, tempo, tono, velocidad, recorte, bucle, al revés, eco, reverberación, ancho estéreo, mono,
  karaoke y fundidos, con muestra de 30 s y guardar como nueva o reemplazar); portadas y descargas de **SoundCloud**
  (y aviso claro si pegas un enlace de Deezer); **Rellenar con IA** completa todos los campos usando primero el catálogo real
  de iTunes/Deezer; iconos dibujados en vez de emojis; **logo del panda**; miniaturas en caché para listas instantáneas.
  Además: **Convertir formato** (MP3, M4A, FLAC, OPUS, OGG, WAV) y «Ver en carpetas» como árbol (se entra a las subcarpetas).
- **1.03** — Opción «Ver en carpetas» (muestra en qué carpeta está cada canción); la lista se guarda y se actualiza
  sola en segundo plano cuando cambias tu música (ya no hay que volver a analizar); ayuda paso a paso para conseguir
  una API key gratis de Gemini, con enlace directo y botón «Probar la key».
- **1.02** — Al abrir se elige la ubicación de la música (se puede cambiar cuando quieras); lista de canciones con formato y calidad;
  tocar una canción abre la edición de datos y portada, con ▶ Reproducir y 📊 Analizador de calidad arriba; reproducción
  inmediata y app mucho más rápida (versión optimizada y APK más ligero).
- **1.01** — La app pasa a su propio repositorio (Music-Manager-Android); busca actualizaciones aquí.
- **1.0** — Primera versión.
