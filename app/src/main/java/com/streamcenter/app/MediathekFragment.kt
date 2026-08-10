package com.streamcenter.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

private const val FAVORITES_GROUP = "\u2605 Favoriten"
private const val ALL_GROUP = "Alle"
private const val DEFAULT_QUERY = "Tatort"

/**
 * Mediathek-Tab: durchsucht mediathekviewweb.de (aggregiert ARD/ZDF/arte/... Mediatheken)
 * ueber den Backend-Endpunkt /api/mediathek und spielt Treffer direkt im eingebetteten
 * LibVLC-Player ab - Pendant zur Desktop-Version (media.py), dort lief die Wiedergabe
 * ueber Browser + externes VLC per subprocess, hier braucht es das nicht mehr.
 */
class MediathekFragment : Fragment(R.layout.fragment_mediathek) {

    private lateinit var editQuery: EditText
    private lateinit var btnSearch: Button
    private lateinit var genreBar: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var recyclerResults: RecyclerView
    private lateinit var movieAdapter: MovieAdapter

    private var allResults: List<Movie> = emptyList()
    private var filtered: List<Movie> = emptyList()
    private var currentGroup: String = ALL_GROUP

    // Merkt sich Suchbegriff + Genre zwischen App-Starts, analog zu media.py (mediathek_prefs.json)
    private val prefsFile by lazy { File(requireContext().filesDir, "mediathek_prefs.json") }

    private fun loadPrefs(): Pair<String, String> {
        return try {
            if (!prefsFile.exists()) return DEFAULT_QUERY to ALL_GROUP
            val o = JSONObject(prefsFile.readText())
            (o.optString("last_query", DEFAULT_QUERY).ifBlank { DEFAULT_QUERY }) to
                o.optString("selected_genre", ALL_GROUP)
        } catch (e: Exception) {
            DEFAULT_QUERY to ALL_GROUP
        }
    }

    private fun savePrefs(query: String, genre: String) {
        val o = JSONObject()
        o.put("last_query", query)
        o.put("selected_genre", genre)
        prefsFile.writeText(o.toString())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        editQuery = view.findViewById(R.id.edit_query)
        btnSearch = view.findViewById(R.id.btn_search)
        genreBar = view.findViewById(R.id.genre_bar)
        statusText = view.findViewById(R.id.status_text)
        recyclerResults = view.findViewById(R.id.recycler_results)

        recyclerResults.layoutManager = LinearLayoutManager(requireContext())
        movieAdapter = MovieAdapter(
            emptyList(),
            onLongClick = { pos -> onLongClick(pos) },
            isFavorite = FavoritesManager::isMediathekFavorite
        ) { pos -> openPlayer(filtered, pos) }
        recyclerResults.adapter = movieAdapter

        val (savedQuery, savedGenre) = loadPrefs()
        editQuery.setText(savedQuery)
        currentGroup = savedGenre

        btnSearch.setOnClickListener { runSearch() }
        editQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch(); true
            } else {
                false
            }
        }

        runSearch()
    }

    private fun onLongClick(pos: Int): Boolean {
        val movie = filtered.getOrNull(pos) ?: return true
        val ctx = requireContext()
        val nowFav = FavoritesManager.toggleMediathekFavorite(ctx, movie)
        Toast.makeText(
            ctx,
            if (nowFav) "Zu Favoriten hinzugefuegt" else "Aus Favoriten entfernt",
            Toast.LENGTH_SHORT
        ).show()
        if (currentGroup == FAVORITES_GROUP) applyGroup(FAVORITES_GROUP) else movieAdapter.update(filtered)
        return true
    }

    private fun runSearch() {
        val query = editQuery.text.toString().trim().ifBlank { DEFAULT_QUERY }
        savePrefs(query, currentGroup)
        statusText.visibility = View.VISIBLE
        statusText.showSecondary("Suche laeuft...")
        recyclerResults.adapter = movieAdapter
        movieAdapter.update(emptyList())

        lifecycleScope.launch {
            try {
                allResults = ApiClient.getMediathek(query)
                renderGenres()
                applyGroup(if (currentGroup == FAVORITES_GROUP) FAVORITES_GROUP else ALL_GROUP)
                statusText.visibility = if (allResults.isEmpty()) View.VISIBLE else View.GONE
                statusText.showSecondary("Keine Treffer fuer \"$query\"")
            } catch (e: Exception) {
                statusText.visibility = View.VISIBLE
                statusText.showError("Fehler: ${e.message}")
                allResults = emptyList()
                renderGenres()
                movieAdapter.update(emptyList())
            }
        }
    }

    private fun renderGenres() {
        val groups = listOf(FAVORITES_GROUP, ALL_GROUP) + allResults.mapNotNull { it.topic }.distinct().sorted()
        genreBar.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        groups.forEach { group ->
            val chip = inflater.inflate(R.layout.item_chip, genreBar, false) as TextView
            chip.text = group
            chip.setOnClickListener { applyGroup(group) }
            chip.applyTvFocusAnimation()
            genreBar.addView(chip)
        }
    }

    private fun applyGroup(group: String) {
        currentGroup = group
        savePrefs(editQuery.text.toString().trim().ifBlank { DEFAULT_QUERY }, group)
        filtered = when (group) {
            FAVORITES_GROUP -> FavoritesManager.getMediathekFavorites(requireContext())
            ALL_GROUP -> allResults
            else -> allResults.filter { it.topic == group }
        }
        movieAdapter.update(filtered)
        if (group == FAVORITES_GROUP && filtered.isEmpty()) {
            statusText.visibility = View.VISIBLE
            statusText.showSecondary("Noch keine Favoriten (lange druecken zum Merken)")
        } else if (filtered.isNotEmpty()) {
            statusText.visibility = View.GONE
        }
    }

    private fun openPlayer(results: List<Movie>, startIndex: Int) {
        val urls = ArrayList(results.map { it.streamUrl })
        val titles = ArrayList(results.map { it.title })
        val intent = Intent(requireContext(), PlayerActivity::class.java)
        intent.putStringArrayListExtra(PlayerActivity.EXTRA_URLS, urls)
        intent.putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, titles)
        intent.putExtra(PlayerActivity.EXTRA_START_INDEX, startIndex)
        startActivity(intent)
    }
}
