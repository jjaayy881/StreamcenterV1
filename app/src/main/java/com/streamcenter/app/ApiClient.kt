package com.streamcenter.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Channel(val name: String)
data class Topic(val id: String, val title: String)
data class Movie(
    val id: String,
    val title: String,
    val streamUrl: String,
    val overview: String? = null,
    val posterUrl: String? = null,
    val rating: Double? = null,
    // Nur von der Mediathek befuellt (Telegram nutzt diese Felder nicht)
    val channel: String? = null,
    val topic: String? = null
)
data class LiveTvChannel(val title: String, val group: String, val streamUrl: String)

data class StalkerStatus(
    val connected: Boolean,
    val error: String?,
    val portalUrl: String?
)

data class StalkerProfile(
    val name: String,
    val url: String,
    val mac: String
) {
    val isFilled: Boolean get() = url.isNotBlank() && mac.isNotBlank()
}

data class StalkerNode(
    val id: String?,
    val title: String,
    val posterUrl: String?,
    val streamUrl: String?,
    val categoryId: String?,
    val movieId: String?,
    val seasonId: String?
)

object ApiClient {
    private const val BASE = StreamApp.BASE_URL

    // org.json's optString(key, null) gibt bei einem echten JSON-null-Wert (nicht
    // fehlendem Key) den literalen String "null" zurueck statt Kotlin null - deshalb
    // hier explizit pruefen statt sich auf den Fallback-Parameter zu verlassen.
    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    private suspend fun getText(url: String, readTimeoutMs: Int = 15000): String = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        // Der Python-Server braucht beim allerersten Start ein paar Sekunden
        // (Interpreter-Start, aiohttp/pyrogram-Import). Bis zu ~20s warten,
        // statt beim ersten Fehlschlag sofort aufzugeben.
        repeat(20) { attempt ->
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = readTimeoutMs
                return@withContext conn.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                lastError = e
                if (attempt < 19) Thread.sleep(1000)
            }
        }
        throw lastError ?: IllegalStateException("Unbekannter Verbindungsfehler")
    }

    suspend fun getChannels(): List<Channel> {
        val arr = JSONArray(getText("$BASE/api/channels"))
        return (0 until arr.length()).map { Channel(arr.getString(it)) }
    }

    suspend fun getTopics(channel: String): List<Topic> {
        val enc = URLEncoder.encode(channel, "UTF-8")
        val arr = JSONArray(getText("$BASE/api/topics?channel=$enc"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Topic(o.get("id").toString(), o.getString("title"))
        }
    }

    suspend fun getMovies(channel: String, topicId: String? = null): List<Movie> {
        val enc = URLEncoder.encode(channel, "UTF-8")
        var url = "$BASE/api/movies?channel=$enc"
        if (topicId != null) url += "&topic=" + URLEncoder.encode(topicId, "UTF-8")
        val arr = JSONArray(getText(url))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Movie(
                id = o.get("id").toString(),
                title = o.getString("title"),
                streamUrl = o.getString("stream_url"),
                overview = o.optNullableString("overview"),
                posterUrl = o.optNullableString("poster_url"),
                rating = if (o.has("rating") && !o.isNull("rating")) o.optDouble("rating") else null
            )
        }
    }

    suspend fun getMediathek(query: String): List<Movie> {
        val enc = URLEncoder.encode(query, "UTF-8")
        val arr = JSONArray(getText("$BASE/api/mediathek?q=$enc", readTimeoutMs = 45000))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val minutes = if (o.has("duration_min") && !o.isNull("duration_min")) o.optInt("duration_min") else null
            val overview = o.optNullableString("overview")
            val overviewWithDuration = when {
                minutes != null && !overview.isNullOrBlank() -> "$minutes Min. \u00b7 $overview"
                minutes != null -> "$minutes Min."
                else -> overview
            }
            Movie(
                id = o.get("id").toString(),
                title = o.getString("title"),
                streamUrl = o.getString("stream_url"),
                overview = overviewWithDuration,
                posterUrl = o.optNullableString("poster_url"),
                rating = null,
                channel = o.optString("channel", "Mediathek"),
                topic = o.optString("topic", "Sonstiges")
            )
        }
    }

    /** Einmaliger Verbindungsversuch (keine Retry-Schleife wie getText() - ein Verbindungsfehler
     * hier ist eine echte, sofort anzuzeigende Rueckmeldung fuers Eingabeformular, kein
     * voruebergehendes Netzwerkflackern). Liest bei Fehlern auch den errorStream aus, um die
     * Fehlermeldung aus dem Backend zu bekommen statt nur eines generischen HTTP-Codes.
     * slot (0/1/2) legt das Ergebnis zusaetzlich in einem der drei "Portal"-Slots ab. */
    suspend fun connectStalker(
        url: String, mac: String, slot: Int? = null, name: String? = null
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        val encUrl = URLEncoder.encode(url, "UTF-8")
        val encMac = URLEncoder.encode(mac, "UTF-8")
        var urlStr = "$BASE/api/stalker/connect?url=$encUrl&mac=$encMac"
        if (slot != null) urlStr += "&slot=$slot"
        if (!name.isNullOrBlank()) urlStr += "&name=" + URLEncoder.encode(name, "UTF-8")
        singleAttempt(urlStr)
    }

    /** Schnelles Umschalten auf ein bereits gespeichertes Portal-Profil, ohne URL/MAC erneut
     * einzutippen. */
    suspend fun connectStalkerSlot(slot: Int): Pair<Boolean, String?> =
        singleAttempt("$BASE/api/stalker/connect_slot?slot=$slot")

    /** Wie singleAttempt(), gibt bei Erfolg aber den "status"-Wert aus der Antwort zurueck
     * statt nur true/false - fuer den Telegram-Login-Flow, der zwischen "code_sent",
     * "need_password" und "ok" unterscheiden muss. */
    private suspend fun singleAttemptWithStatus(urlStr: String): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 45000
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val o = if (body.isNotBlank()) JSONObject(body) else JSONObject()
            if (code in 200..299) (o.optString("status", "ok")) to null
            else null to (o.optNullableString("error") ?: "HTTP $code")
        } catch (e: Exception) {
            null to (e.message ?: "Unbekannter Fehler")
        } finally {
            conn.disconnect()
        }
    }

    /** Schritt 1: Telefonnummer senden -> Telegram schickt einen Code per SMS/App.
     * Erfolg liefert status="code_sent". */
    suspend fun telegramLoginStart(phone: String): Pair<String?, String?> {
        val enc = URLEncoder.encode(phone, "UTF-8")
        return singleAttemptWithStatus("$BASE/api/telegram/login/start?phone=$enc")
    }

    /** Schritt 2: erhaltenen Code bestaetigen. Erfolg liefert entweder status="ok"
     * (fertig) oder status="need_password" (2FA aktiv, siehe telegramLoginPassword). */
    suspend fun telegramLoginCode(code: String): Pair<String?, String?> {
        val enc = URLEncoder.encode(code, "UTF-8")
        return singleAttemptWithStatus("$BASE/api/telegram/login/code?code=$enc")
    }

    /** Schritt 3 (nur bei 2FA): Cloud-Passwort bestaetigen. Erfolg liefert status="ok". */
    suspend fun telegramLoginPassword(password: String): Pair<String?, String?> {
        val enc = URLEncoder.encode(password, "UTF-8")
        return singleAttemptWithStatus("$BASE/api/telegram/login/password?password=$enc")
    }

    data class TelegramDialog(val name: String, val id: Long)

    /** Listet alle Kanaele/Gruppen des gerade eingeloggten Accounts - fuer die
     * Antipp-Auswahl statt manuellem Eintragen von IDs in die config.json. */
    suspend fun telegramDialogs(): Pair<List<TelegramDialog>?, String?> = withContext(Dispatchers.IO) {
        val conn = URL("$BASE/api/telegram/login/dialogs").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 20000
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val o = if (body.isNotBlank()) JSONObject(body) else JSONObject()
            if (code in 200..299) {
                val arr = o.optJSONArray("dialogs") ?: JSONArray()
                val list = (0 until arr.length()).map { i ->
                    val d = arr.getJSONObject(i)
                    TelegramDialog(d.getString("name"), d.getLong("id"))
                }
                list to null
            } else {
                null to (o.optNullableString("error") ?: "HTTP $code")
            }
        } catch (e: Exception) {
            null to (e.message ?: "Unbekannter Fehler")
        } finally {
            conn.disconnect()
        }
    }

    /** Speichert die angetippte Kanalauswahl (Name -> ID) dauerhaft in der config.json. */
    suspend fun saveTelegramChannels(selected: Map<String, Long>): Pair<String?, String?> {
        val json = JSONObject()
        selected.forEach { (name, id) -> json.put(name, id) }
        val enc = URLEncoder.encode(json.toString(), "UTF-8")
        return singleAttemptWithStatus("$BASE/api/telegram/login/channels/save?selected=$enc")
    }

    private suspend fun singleAttempt(urlStr: String): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 45000
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val o = if (body.isNotBlank()) JSONObject(body) else JSONObject()
            if (code in 200..299) true to null else false to (o.optNullableString("error") ?: "HTTP $code")
        } catch (e: Exception) {
            false to (e.message ?: "Unbekannter Fehler")
        } finally {
            conn.disconnect()
        }
    }

    suspend fun getStalkerProfiles(): List<StalkerProfile> {
        val arr = JSONArray(getText("$BASE/api/stalker/profiles"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            StalkerProfile(
                name = o.optString("name", "Portal ${it + 1}"),
                url = o.optString("url", ""),
                mac = o.optString("mac", "")
            )
        }
    }

    suspend fun getStalkerStatus(): StalkerStatus {
        val o = JSONObject(getText("$BASE/api/stalker/status"))
        return StalkerStatus(
            connected = o.optBoolean("connected", false),
            error = o.optNullableString("error"),
            portalUrl = o.optNullableString("portal_url")
        )
    }

    private fun parseStalkerNodes(json: String): List<StalkerNode> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            StalkerNode(
                id = o.optNullableString("id"),
                title = o.optString("title", "Ohne Titel"),
                posterUrl = o.optNullableString("poster_url"),
                streamUrl = o.optNullableString("stream_url"),
                categoryId = o.optNullableString("category_id"),
                movieId = o.optNullableString("movie_id"),
                seasonId = o.optNullableString("season_id")
            )
        }
    }

    suspend fun getStalkerCategories(type: String): List<StalkerNode> =
        parseStalkerNodes(getText("$BASE/api/stalker/categories?type=$type"))

    suspend fun getStalkerItems(type: String, categoryId: String): List<StalkerNode> {
        val enc = URLEncoder.encode(categoryId, "UTF-8")
        return parseStalkerNodes(getText("$BASE/api/stalker/items?type=$type&category_id=$enc", readTimeoutMs = 30000))
    }

    suspend fun getStalkerSeasons(movieId: String): List<StalkerNode> {
        val enc = URLEncoder.encode(movieId, "UTF-8")
        return parseStalkerNodes(getText("$BASE/api/stalker/seasons?movie_id=$enc"))
    }

    suspend fun getStalkerEpisodes(movieId: String, seasonId: String): List<StalkerNode> {
        val encMovie = URLEncoder.encode(movieId, "UTF-8")
        val encSeason = URLEncoder.encode(seasonId, "UTF-8")
        return parseStalkerNodes(getText("$BASE/api/stalker/episodes?movie_id=$encMovie&season_id=$encSeason"))
    }

    suspend fun loadM3u(url: String): List<LiveTvChannel> {
        val enc = URLEncoder.encode(url, "UTF-8")
        val text = getText("$BASE/api/m3u?url=$enc")
        val parsed = JSONArray(text)
        return (0 until parsed.length()).map {
            val o = parsed.getJSONObject(it)
            LiveTvChannel(
                o.optString("title", "Unbekannt"),
                o.optString("group", "Allgemein"),
                o.getString("stream_url")
            )
        }
    }
}
