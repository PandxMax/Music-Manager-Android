# Music Manager para Android

Versión para Android de Music Manager (Kotlin + Jetpack Compose). Creada por **PandxMax**.

[![Descargar APK](https://img.shields.io/badge/Descargar-APK-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/PandxMax/Music-Manager-Android/releases/latest/download/MusicManager-Android.apk)

La versión para Windows está en [PandxMax/MusicManager](https://github.com/PandxMax/MusicManager).

## Qué incluye
- **Tu música en una lista**: eliges la carpeta de tu música (cambiable cuando quieras) y aparecen todas las canciones
  con su formato y calidad (Hi-Res, Sin pérdida, Alta, Media, Baja). Buscar, ⟳ actualizar, selección múltiple y eliminar.
- **Al tocar una canción** se abre la edición de datos y portada, con **▶ Reproducir** y **📊 Analizador de calidad** arriba.
- **Reproductor** en segundo plano con notificación: cola, aleatorio, repetir, listas guardadas y mini-reproductor.
  Los colores de la app cambian según la portada de la canción.
- **Editor de metadatos** (uno o varios archivos a la vez): Título, Intérprete, Álbum, Intérprete del álbum,
  Compositor, Género, Año, Pista, Número de disco, Comentario y Directorio; etiquetas técnicas (codec, tipo de etiqueta,
  bitrate, frecuencia, duración, modo, tamaño). Los MP3 se guardan en ID3v2.3 (lo que entiende Windows).
- **Portadas**: elegir de la galería, **buscar automáticamente** (iTunes y Deezer, varias opciones), quitar, guardar como
  `cover.jpg`, ver en grande, y si no hay nada, abrir Google Imágenes.
- **Letras** con LRCLIB (sincronizadas) y edición.
- **IA** (Gemini gratis, Claude o ChatGPT con tu propia API key): rellenar metadatos y sugerir géneros.
  La key gratuita de Gemini se saca en [Google AI Studio](https://aistudio.google.com/apikey); en Ajustes está explicado paso a paso.
- **MusicBrainz** y **Discogs** (token) para rellenar etiquetas.
- **Botones web**: YouTube, Spotify, Tidal, Qobuz y Wikipedia.
- **Analizador de calidad** (espectrograma) de audio, con **comparación** de canciones de distintos lugares.
- **Descargar de YouTube a MP3** (yt-dlp) y luego editar metadatos y portada.
- Aviso «¿Desea guardar los cambios?» al salir, y botón 💾 para guardar.

## Qué no incluye (todavía)
- Convertir formato (en Android no hay un ffmpeg libre mantenido que se pueda incluir fácilmente).

## Cómo obtener el APK
1. Pulsa el botón **Descargar APK** de arriba (o ve a **Releases**).
2. Para generar una versión nueva: **Actions → «Crear APK» → Run workflow**; al terminar aparece en **Releases**.
3. Descárgalo en el teléfono e instálalo (permite instalar apps de origen desconocido si te lo pide).
4. La primera vez, concede el permiso **«Acceso a todos los archivos»** para poder editar etiquetas y mover carpetas.

Requiere Android 11 o superior.

> Descarga solo música o contenido que tengas derecho a guardar.

## Historial de versiones
- **1.03** — Opción «Ver en carpetas» (muestra en qué carpeta está cada canción); la lista se guarda y se actualiza
  sola en segundo plano cuando cambias tu música (ya no hay que volver a analizar); ayuda paso a paso para conseguir
  una API key gratis de Gemini, con enlace directo y botón «Probar la key».
- **1.02** — Al abrir se elige la ubicación de la música (se puede cambiar cuando quieras); lista de canciones con formato y calidad;
  tocar una canción abre la edición de datos y portada, con ▶ Reproducir y 📊 Analizador de calidad arriba; reproducción
  inmediata y app mucho más rápida (versión optimizada y APK más ligero).
- **1.01** — La app pasa a su propio repositorio (Music-Manager-Android); busca actualizaciones aquí.
- **1.0** — Primera versión.
