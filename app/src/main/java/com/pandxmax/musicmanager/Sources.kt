package com.pandxmax.musicmanager

import org.json.JSONObject
import java.net.URLEncoder

data class MetaCandidate(val source: String, val values: Map<String, String>) {
    val label: String
        get() = (values["artist"] ?: "") + " — " + (values["title"] ?: values["album"] ?: "") +
            (values["album"]?.let { if (values["title"] != null) "  [$it]" else "" } ?: "") +
            (values["date"]?.let { "  ($it)" } ?: "") + "  · $source"
}

object Sources {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Quita adornos que hacen fallar la búsqueda: (feat. …), [Official Video], - Remastered 2011, (Letra)… */
    fun cleanTitle(t: String): String {
        var x = t.replace(Regex("(?i)\\s*[(\\[][^)\\]]*(feat|ft\\.|official|video|audio|lyric|letra|remaster|live|en vivo|version|versión|hd|hq)[^)\\]]*[)\\]]"), "")
        x = x.replace(Regex("(?i)\\s+-\\s+(remaster(ed)?|live|en vivo|radio edit|single version)\\b.*$"), "")
        x = x.replace(Regex("(?i)\\s+(feat\\.?|ft\\.?)\\s+.*$"), "")
        return x.trim(' ', '-', '_')
    }

    fun mainArtistOf(a: String): String = a.split(Regex("(?i)\\s*(?:,|&|;|/| feat\\.? | ft\\.? | x | y )\\s*"), 2)[0].trim()

    private fun norm(t: String?): String {
        val n = java.text.Normalizer.normalize((t ?: "").lowercase(), java.text.Normalizer.Form.NFD)
        return n.replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), "")
    }

    fun isLrc(t: String) = Regex("\\[\\d{1,2}:\\d{2}").containsMatchIn(t)

    /** Pide una dirección con reintentos (los servicios a veces responden 503 / 429 por saturación). 404 = null. */
    private fun fetch(url: String, headers: Map<String, String> = emptyMap(), tries: Int = 3): String? {
        var last: Throwable? = null
        repeat(tries) { a ->
            try {
                return Net.get(url, headers, 12000)
            } catch (e: Throwable) {
                if ((e.message ?: "").contains("404")) return null
                last = e
                Thread.sleep(1200L * (a + 1))
            }
        }
        throw last ?: RuntimeException("sin respuesta")
    }

    private fun pick(o: org.json.JSONObject?): String {
        if (o == null || o.optBoolean("instrumental")) return ""
        val s = o.optString("syncedLyrics", "").takeIf { it.isNotBlank() && it != "null" }
        val p = o.optString("plainLyrics", "").takeIf { it.isNotBlank() && it != "null" }
        return s ?: p ?: ""
    }

    private fun lrclibOnce(artist: String, title: String, album: String, secs: Int): String {
        var u = "https://lrclib.net/api/get?track_name=${enc(title)}"
        if (artist.isNotBlank()) u += "&artist_name=${enc(artist)}"
        if (album.isNotBlank()) u += "&album_name=${enc(album)}"
        if (secs > 0) u += "&duration=$secs"
        fetch(u)?.let { val t = pick(JSONObject(it)); if (t.isNotBlank()) return t }
        var su = "https://lrclib.net/api/search?track_name=${enc(title)}"
        if (artist.isNotBlank()) su += "&artist_name=${enc(artist)}"
        val arr = fetch(su)?.let { org.json.JSONArray(it) } ?: return ""
        val res = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { !it.optBoolean("instrumental") }
            .sortedWith(compareBy({ it.optString("syncedLyrics", "").let { s -> s.isBlank() || s == "null" } }, { if (secs > 0) kotlin.math.abs(it.optDouble("duration", 0.0) - secs) else 0.0 }))
        for (o in res) { val t = pick(o); if (t.isNotBlank()) return t }
        return ""
    }

    private fun netease(artist: String, title: String, secs: Int): String {
        val h = mapOf("Referer" to "https://music.163.com/", "User-Agent" to "Mozilla/5.0")
        val d = fetch("https://music.163.com/api/search/get/web?s=${enc("$title $artist".trim())}&type=1&limit=8", h, 2) ?: return ""
        val songs = JSONObject(d).optJSONObject("result")?.optJSONArray("songs") ?: return ""
        val nt = norm(title); val na = norm(artist)
        var best: JSONObject? = null
        for (i in 0 until songs.length()) {
            val sg = songs.getJSONObject(i)
            val arts = sg.optJSONArray("artists")?.let { a -> (0 until a.length()).joinToString(" ") { a.getJSONObject(it).optString("name") } } ?: ""
            val n = norm(sg.optString("name"))
            if (nt.isNotEmpty() && !n.contains(nt) && !nt.contains(n)) continue
            // sin coincidir el artista podría ser otra canción: mejor nada que una letra equivocada
            if (na.isNotEmpty() && !norm(arts).contains(na) && !na.contains(norm(arts))) continue
            if (secs > 0 && sg.optLong("duration") > 0 && kotlin.math.abs(sg.optLong("duration") / 1000 - secs) > 8) continue
            best = sg; break
        }
        val b = best ?: return ""
        val l = fetch("https://music.163.com/api/song/lyric?id=${b.optLong("id")}&lv=1&kv=1&tv=-1", h, 2) ?: return ""
        val text = JSONObject(l).optJSONObject("lrc")?.optString("lyric")?.trim() ?: ""
        val clean = text.lines().filter { !Regex("]\\s*(作词|作曲|编曲|制作人|混音|母带)").containsMatchIn(it) }.joinToString("\n")
        return if (clean.trim().length > 20) clean else ""
    }

    private fun lyricsOvh(artist: String, title: String): String {
        if (artist.isBlank()) return ""
        val d = fetch("https://api.lyrics.ovh/v1/${enc(artist).replace("+", "%20")}/${enc(title).replace("+", "%20")}", tries = 2) ?: return ""
        val t = JSONObject(d).optString("lyrics").trim()
        return if (t.length > 20) t else ""
    }

    /**
     * Busca la letra: LRCLIB (con el título tal cual y luego limpio), NetEase (sincronizada) y lyrics.ovh.
     * Devuelve (letra, fuente) o null; lanza error solo si ningún servicio respondió.
     */
    fun findLyrics(artist: String, title: String, album: String = "", secs: Int = 0): Pair<String, String>? {
        val ca = mainArtistOf(artist); val ct = cleanTitle(title)
        val tries = ArrayList<Pair<String, () -> String>>()
        tries.add("LRCLIB" to { lrclibOnce(artist, title, album, secs) })
        if (ca != artist || ct != title) tries.add("LRCLIB" to { lrclibOnce(ca, ct, "", secs) })
        tries.add("NetEase" to { netease(ca, ct, secs) })
        tries.add("lyrics.ovh" to { lyricsOvh(ca, ct) })
        var answered = false
        var last: Throwable? = null
        var plain: Pair<String, String>? = null
        for ((name, fn) in tries) {
            val t = try { fn().also { answered = true } } catch (e: Throwable) { last = e; continue }
            if (t.isNotBlank() && !isLrc(t)) { if (plain == null) plain = t to name; continue }  // se sigue buscando una sincronizada
            if (t.isNotBlank()) return t to name
        }
        if (plain != null) return plain
        if (!answered && last != null) throw last!!
        return null
    }

    fun lrclib(artist: String, title: String, album: String = "", seconds: Int = 0): String? =
        try { findLyrics(artist, title, album, seconds)?.first } catch (_: Throwable) { null }

    /** Limpia una letra pegada: sin espacios de sobra ni líneas vacías repetidas. */
    fun tidyLyrics(text: String): String {
        val out = ArrayList<String>()
        var blank = false
        for (l0 in text.replace("\r\n", "\n").replace("\r", "\n").split("\n")) {
            val l = l0.trim()
            if (l.isEmpty()) { if (out.isNotEmpty() && !blank) out.add(""); blank = true; continue }
            blank = false; out.add(l)
        }
        return out.joinToString("\n").trim()
    }

    fun musicaComUrl(artist: String, title: String): String =
        "https://www.google.com/search?q=" + enc("site:musica.com ${mainArtistOf(artist)} ${cleanTitle(title)} letra".trim())

    fun musicBrainz(artist: String, title: String): List<MetaCandidate> {
        val q = "recording:\"$title\"" + if (artist.isNotBlank()) " AND artist:\"$artist\"" else ""
        val o = JSONObject(Net.get("https://musicbrainz.org/ws/2/recording/?query=${enc(q)}&fmt=json&limit=10"))
        val arr = o.optJSONArray("recordings") ?: return emptyList()
        val out = ArrayList<MetaCandidate>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val v = LinkedHashMap<String, String>()
            v["title"] = r.optString("title")
            val ac = r.optJSONArray("artist-credit")
            if (ac != null && ac.length() > 0) v["artist"] = ac.getJSONObject(0).optString("name")
            val rels = r.optJSONArray("releases")
            if (rels != null && rels.length() > 0) {
                val rel = rels.getJSONObject(0)
                v["album"] = rel.optString("title")
                val d = rel.optString("date")
                if (d.length >= 4) v["date"] = d.substring(0, 4)
                val media = rel.optJSONArray("media")
                if (media != null && media.length() > 0) {
                    val tr = media.getJSONObject(0).optJSONArray("track")
                    if (tr != null && tr.length() > 0) v["track"] = tr.getJSONObject(0).optString("number")
                }
            }
            if (!v.containsKey("date")) {
                val d = r.optString("first-release-date")
                if (d.length >= 4) v["date"] = d.substring(0, 4)
            }
            v.entries.removeAll { it.value.isBlank() }
            out.add(MetaCandidate("MusicBrainz", v))
        }
        return out
    }

    fun discogs(artist: String, title: String): List<MetaCandidate> {
        val tok = Store.discogsToken
        if (tok.isBlank()) throw RuntimeException("Falta el token de Discogs (Ajustes).")
        val q = "$artist $title".trim()
        val o = JSONObject(Net.get("https://api.discogs.com/database/search?q=${enc(q)}&type=release&per_page=10&token=$tok"))
        val arr = o.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<MetaCandidate>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val v = LinkedHashMap<String, String>()
            val full = r.optString("title")
            val parts = full.split(" - ", limit = 2)
            if (parts.size == 2) { v["artist"] = parts[0]; v["album"] = parts[1] } else v["album"] = full
            v["date"] = r.optString("year")
            val g = r.optJSONArray("genre")
            val st = r.optJSONArray("style")
            val gl = ArrayList<String>()
            if (st != null && st.length() > 0) gl.add(st.getString(0))
            if (g != null && g.length() > 0) gl.add(g.getString(0))
            if (gl.isNotEmpty()) v["genre"] = gl.joinToString(" / ")
            v.entries.removeAll { it.value.isBlank() }
            out.add(MetaCandidate("Discogs", v))
        }
        return out
    }
}
