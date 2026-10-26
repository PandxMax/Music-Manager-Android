package com.pandxmax.musicmanager

import org.json.JSONArray
import org.json.JSONObject

object Ai {
    fun chat(provider: String, model: String, key: String, system: String, prompt: String): String {
        when (provider) {
            "Claude" -> {
                val body = JSONObject()
                    .put("model", model).put("max_tokens", 2000)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                if (system.isNotEmpty()) body.put("system", system)
                val r = JSONObject(Net.postJson("https://api.anthropic.com/v1/messages", body.toString(),
                    mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")))
                val c = r.getJSONArray("content")
                val sb = StringBuilder()
                for (i in 0 until c.length()) sb.append(c.getJSONObject(i).optString("text"))
                return sb.toString()
            }
            "Gemini" -> {
                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                if (system.isNotEmpty()) {
                    body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                }
                val r = JSONObject(Net.postJson(
                    "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", body.toString(),
                    mapOf("x-goog-api-key" to key)))
                val parts = r.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                val sb = StringBuilder()
                for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
                return sb.toString()
            }
            else -> {
                val msgs = JSONArray()
                if (system.isNotEmpty()) msgs.put(JSONObject().put("role", "system").put("content", system))
                msgs.put(JSONObject().put("role", "user").put("content", prompt))
                val body = JSONObject().put("model", model).put("messages", msgs)
                val r = JSONObject(Net.postJson("https://api.openai.com/v1/chat/completions", body.toString(),
                    mapOf("Authorization" to "Bearer $key")))
                return r.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            }
        }
    }

    fun ask(system: String, prompt: String): String {
        val p = Store.provider
        val key = Store.key(p)
        if (key.isBlank()) throw RuntimeException("Falta la API key de $p (Ajustes → IA).")
        return chat(p, Store.model(p), key, system, prompt)
    }

    private fun jsonOf(txt: String): String {
        val a = txt.indexOf('{')
        val b = txt.lastIndexOf('}')
        return if (a >= 0 && b > a) txt.substring(a, b + 1) else "{}"
    }

    private fun norm(t: String?): String {
        val a = (t ?: "").lowercase().replace(Regex("\\(.*?\\)|\\[.*?]|feat\\..*|ft\\..*"), " ")
        return a.replace(Regex("[^0-9a-záéíóúñü]+"), " ").trim()
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** Datos reales del catálogo de iTunes (y Deezer de respaldo): álbum, año, pista, disco, género. */
    fun catalog(title: String, artist: String): Map<String, String> {
        val t = norm(title); val a = norm(artist)
        val out = LinkedHashMap<String, String>()
        try {
            val d = JSONObject(Net.get("https://itunes.apple.com/search?entity=song&limit=25&term=" + enc("$artist $title".trim())))
            val rs = d.optJSONArray("results") ?: JSONArray()
            var best: JSONObject? = null; var bestSc = 0
            val years = ArrayList<String>()
            for (i in 0 until rs.length()) {
                val r = rs.getJSONObject(i)
                val rt = norm(r.optString("trackName")); val ra = norm(r.optString("artistName"))
                var sc = if (rt == t) 3 else if (t.isNotEmpty() && (rt.contains(t) || t.contains(rt))) 2 else 0
                sc += if (a.isNotEmpty()) (if (ra.contains(a) || a.contains(ra)) 3 else 0) else 1
                val coll = r.optString("collectionName")
                if (sc >= 3 && !coll.endsWith(" - Single") && !coll.endsWith(" - EP")) sc += 1
                val rd = r.optString("releaseDate")
                if (sc >= 4 && rd.length >= 4 && rd.substring(0, 4).all { it.isDigit() }) years.add(rd.substring(0, 4))
                if (sc > bestSc) { best = r; bestSc = sc }
            }
            val b = best
            if (b != null && bestSc >= 4) {
                out["title"] = b.optString("trackName")
                out["artist"] = b.optString("artistName")
                out["album"] = b.optString("collectionName").replace(Regex(" - (Single|EP)$"), "")
                out["albumartist"] = b.optString("collectionArtistName").ifEmpty { b.optString("artistName") }
                out["genre"] = b.optString("primaryGenreName")
                out["date"] = years.minOrNull() ?: b.optString("releaseDate").take(4)
                if (b.optInt("trackNumber") > 0) out["track"] = b.optInt("trackNumber").toString()
                if (b.optInt("discNumber") > 0) out["disc"] = b.optInt("discNumber").toString()
            }
        } catch (_: Throwable) {
        }
        if (out["album"].isNullOrEmpty()) {
            try {
                val q = if (artist.isNotBlank()) "artist:\"$artist\" track:\"$title\"" else title
                val d = JSONObject(Net.get("https://api.deezer.com/search?limit=5&q=" + enc(q)))
                val hit = d.optJSONArray("data")?.optJSONObject(0)
                if (hit != null) {
                    val tr = JSONObject(Net.get("https://api.deezer.com/track/" + hit.optLong("id")))
                    val vals = mapOf(
                        "title" to tr.optString("title"), "artist" to (tr.optJSONObject("artist")?.optString("name") ?: ""),
                        "album" to (tr.optJSONObject("album")?.optString("title") ?: ""),
                        "albumartist" to (tr.optJSONObject("artist")?.optString("name") ?: ""),
                        "date" to tr.optString("release_date").take(4),
                        "track" to (if (tr.optInt("track_position") > 0) tr.optInt("track_position").toString() else ""),
                        "disc" to (if (tr.optInt("disk_number") > 0) tr.optInt("disk_number").toString() else "")
                    )
                    for ((k, v) in vals) if (v.isNotEmpty() && out[k].isNullOrEmpty()) out[k] = v
                }
            } catch (_: Throwable) {
            }
        }
        return out.filterValues { it.isNotEmpty() }
    }

    /** Rellena TODOS los campos: primero el catálogo real (iTunes/Deezer) y la IA completa el resto. */
    fun fillMetadata(title: String, artist: String): Map<String, String> {
        val found = catalog(title, artist)
        val system = "Eres un asistente experto en metadatos musicales. Respondes SOLO con un objeto JSON válido, sin texto " +
            "extra ni bloques de código. Tu trabajo es RELLENAR TODOS LOS CAMPOS con los datos oficiales de la " +
            "canción. Solo deja un campo vacío si ese dato de verdad no existe o no lo conoces en absoluto."
        val known = if (found.isEmpty()) "ninguno" else JSONObject(found as Map<*, *>).toString()
        val prompt = "Canción: «$title». Artista: «$artist».\n" +
            "Datos ya comprobados en el catálogo de iTunes/Deezer (respétalos): $known\n\n" +
            "Devuelve el JSON con TODAS estas claves (todos los valores como texto):\n" +
            "- title: título oficial\n- artist: intérprete principal (con «feat.» si corresponde)\n" +
            "- album: álbum original donde salió (si solo fue sencillo, el nombre del sencillo)\n" +
            "- albumartist: intérprete del álbum (normalmente el mismo artista; «Varios artistas» si es recopilatorio)\n" +
            "- composer: compositores/autores de la canción, separados por «; »\n" +
            "- genre: género principal y, si aplica, subgénero (ej. «Reguetón», «Rock alternativo», «Pop latino»)\n" +
            "- date: año de lanzamiento (4 dígitos)\n- track: número de pista en ese álbum (1 si es sencillo)\n" +
            "- disc: número de disco (1 si el álbum tiene un solo disco)\n" +
            "- comment: una nota breve y útil, por ejemplo «Sello: Sony Music · Productor: Tainy» (sin opiniones)"
        val ai = LinkedHashMap<String, String>()
        var err: Throwable? = null
        try {
            val o = JSONObject(jsonOf(ask(system, prompt)))
            val alias = mapOf("tracknumber" to "track", "discnumber" to "disc", "year" to "date", "album_artist" to "albumartist", "comments" to "comment")
            for (k0 in o.keys()) {
                val k = alias[k0] ?: k0
                val v = o.optString(k0, "").trim()
                if (FIELDS.any { it.first == k } && v.isNotEmpty() && v != "null" && v.lowercase() !in setOf("desconocido", "unknown", "n/a")) ai[k] = v
            }
        } catch (e: Throwable) {
            err = e
        }
        val data = LinkedHashMap(ai)
        for (k in listOf("title", "artist", "album", "albumartist", "date", "track", "disc")) found[k]?.let { data[k] = it }
        if (data["genre"].isNullOrEmpty()) found["genre"]?.let { data["genre"] = it }
        if (!data["artist"].isNullOrEmpty() && data["albumartist"].isNullOrEmpty()) data["albumartist"] = data["artist"]!!
        if (!data["album"].isNullOrEmpty() && data["disc"].isNullOrEmpty()) data["disc"] = "1"
        data["date"]?.let { d -> Regex("\\d{4}").find(d)?.let { data["date"] = it.value } }
        for (k in listOf("track", "disc")) data[k]?.let { v -> data[k] = Regex("\\d+").find(v)?.value ?: "" }
        val res = data.filterValues { it.isNotEmpty() }
        if (res.isEmpty() && err != null) throw err
        return res
    }

    /** Sugiere géneros y subgéneros para la canción. */
    fun suggestGenres(title: String, artist: String, album: String): List<String> {
        val system = "Eres un experto en géneros musicales. Respondes SOLO con JSON válido, sin texto extra."
        val prompt = "Canción: «$title». Artista: «$artist». Álbum: «$album».\n" +
            "Devuelve {\"generos\": [..]} con 5 opciones ordenadas de más a menos probable, cada una en el formato " +
            "«Género – Subgénero» (si no hay subgénero, solo el género)."
        val o = JSONObject(jsonOf(ask(system, prompt)))
        val a = o.optJSONArray("generos") ?: JSONArray()
        return List(a.length()) { a.getString(it) }
    }
}
