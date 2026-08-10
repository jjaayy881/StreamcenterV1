package com.streamcenter.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object FavoritesManager {
    private const val FILE_LIVETV = "favorites_livetv.json"
    private const val FILE_TELEGRAM = "favorites_telegram.json"
    private const val FILE_MEDIATHEK = "favorites_mediathek.json"

    // ---------- LiveTV ----------

    fun getLiveTvFavorites(context: Context): MutableList<LiveTvChannel> {
        val file = File(context.filesDir, FILE_LIVETV)
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { jsonToChannel(arr.getJSONObject(it)) }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun isLiveTvFavorite(context: Context, channel: LiveTvChannel): Boolean =
        getLiveTvFavorites(context).any { it.streamUrl == channel.streamUrl }

    /** Schaltet um und gibt den neuen Zustand zurueck (true = jetzt favorisiert). */
    fun toggleLiveTvFavorite(context: Context, channel: LiveTvChannel): Boolean {
        val list = getLiveTvFavorites(context)
        val idx = list.indexOfFirst { it.streamUrl == channel.streamUrl }
        val nowFavorite = if (idx >= 0) {
            list.removeAt(idx)
            false
        } else {
            list.add(channel)
            true
        }
        saveLiveTv(context, list)
        return nowFavorite
    }

    private fun saveLiveTv(context: Context, list: List<LiveTvChannel>) {
        val arr = JSONArray()
        list.forEach { arr.put(channelToJson(it)) }
        File(context.filesDir, FILE_LIVETV).writeText(arr.toString())
    }

    private fun channelToJson(c: LiveTvChannel): JSONObject {
        val o = JSONObject()
        o.put("title", c.title)
        o.put("group", c.group)
        o.put("stream_url", c.streamUrl)
        return o
    }

    private fun jsonToChannel(o: JSONObject): LiveTvChannel =
        LiveTvChannel(o.getString("title"), o.optString("group", "Allgemein"), o.getString("stream_url"))

    // ---------- Telegram ----------

    fun getTelegramFavorites(context: Context): MutableList<Movie> {
        val file = File(context.filesDir, FILE_TELEGRAM)
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { jsonToMovie(arr.getJSONObject(it)) }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun isTelegramFavorite(context: Context, movie: Movie): Boolean =
        getTelegramFavorites(context).any { it.streamUrl == movie.streamUrl }

    fun toggleTelegramFavorite(context: Context, movie: Movie): Boolean {
        val list = getTelegramFavorites(context)
        val idx = list.indexOfFirst { it.streamUrl == movie.streamUrl }
        val nowFavorite = if (idx >= 0) {
            list.removeAt(idx)
            false
        } else {
            list.add(movie)
            true
        }
        saveTelegram(context, list)
        return nowFavorite
    }

    private fun saveTelegram(context: Context, list: List<Movie>) {
        val arr = JSONArray()
        list.forEach { arr.put(movieToJson(it)) }
        File(context.filesDir, FILE_TELEGRAM).writeText(arr.toString())
    }

    private fun movieToJson(m: Movie): JSONObject {
        val o = JSONObject()
        o.put("id", m.id)
        o.put("title", m.title)
        o.put("stream_url", m.streamUrl)
        o.put("overview", m.overview ?: JSONObject.NULL)
        o.put("poster_url", m.posterUrl ?: JSONObject.NULL)
        o.put("rating", m.rating ?: JSONObject.NULL)
        o.put("channel", m.channel ?: JSONObject.NULL)
        o.put("topic", m.topic ?: JSONObject.NULL)
        return o
    }

    private fun jsonToMovie(o: JSONObject): Movie = Movie(
        id = o.getString("id"),
        title = o.getString("title"),
        streamUrl = o.getString("stream_url"),
        overview = if (o.isNull("overview")) null else o.optString("overview"),
        posterUrl = if (o.isNull("poster_url")) null else o.optString("poster_url"),
        rating = if (o.isNull("rating")) null else o.optDouble("rating"),
        channel = if (o.has("channel") && !o.isNull("channel")) o.optString("channel") else null,
        topic = if (o.has("topic") && !o.isNull("topic")) o.optString("topic") else null
    )

    // ---------- Mediathek ----------

    fun getMediathekFavorites(context: Context): MutableList<Movie> {
        val file = File(context.filesDir, FILE_MEDIATHEK)
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { jsonToMovie(arr.getJSONObject(it)) }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun isMediathekFavorite(context: Context, movie: Movie): Boolean =
        getMediathekFavorites(context).any { it.streamUrl == movie.streamUrl }

    fun toggleMediathekFavorite(context: Context, movie: Movie): Boolean {
        val list = getMediathekFavorites(context)
        val idx = list.indexOfFirst { it.streamUrl == movie.streamUrl }
        val nowFavorite = if (idx >= 0) {
            list.removeAt(idx)
            false
        } else {
            list.add(movie)
            true
        }
        saveMediathek(context, list)
        return nowFavorite
    }

    private fun saveMediathek(context: Context, list: List<Movie>) {
        val arr = JSONArray()
        list.forEach { arr.put(movieToJson(it)) }
        File(context.filesDir, FILE_MEDIATHEK).writeText(arr.toString())
    }

    // ---------- Stalker (pro Portal getrennt) ----------
    // movie_id/cmd-Werte sind pro Portal vergeben - ohne Trennung koennten zwei
    // Portale zufaellig dieselbe ID fuer komplett unterschiedliche Inhalte nutzen,
    // und ein Portalwechsel wuerde Favoriten zeigen, die dort gar nicht existieren.

    fun getStalkerFavorites(context: Context, scopeKey: String): MutableList<Movie> {
        val file = File(context.filesDir, stalkerFileName(scopeKey))
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { jsonToMovie(arr.getJSONObject(it)) }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun isStalkerFavorite(context: Context, scopeKey: String, movie: Movie): Boolean =
        getStalkerFavorites(context, scopeKey).any { it.streamUrl == movie.streamUrl }

    fun toggleStalkerFavorite(context: Context, scopeKey: String, movie: Movie): Boolean {
        val list = getStalkerFavorites(context, scopeKey)
        val idx = list.indexOfFirst { it.streamUrl == movie.streamUrl }
        val nowFavorite = if (idx >= 0) {
            list.removeAt(idx)
            false
        } else {
            list.add(movie)
            true
        }
        saveStalker(context, scopeKey, list)
        return nowFavorite
    }

    private fun saveStalker(context: Context, scopeKey: String, list: List<Movie>) {
        val arr = JSONArray()
        list.forEach { arr.put(movieToJson(it)) }
        File(context.filesDir, stalkerFileName(scopeKey)).writeText(arr.toString())
    }

    private fun stalkerFileName(scopeKey: String): String {
        val safe = scopeKey.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80).ifBlank { "default" }
        return "favorites_stalker_$safe.json"
    }
}
