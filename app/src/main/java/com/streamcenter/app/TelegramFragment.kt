package com.streamcenter.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

private const val FAVORITES_ENTRY = "\u2605 Favoriten"

private sealed class Level {
    object Channels : Level()
    object Favorites : Level()
    data class Topics(val channel: String) : Level()
    data class Movies(val channel: String, val topicId: String?, val topicTitle: String?) : Level()
}

class TelegramFragment : Fragment(R.layout.fragment_telegram) {

    private lateinit var recycler: RecyclerView
    private lateinit var header: TextView
    private lateinit var rowAdapter: RowAdapter
    private lateinit var movieAdapter: MovieAdapter

    private var level: Level = Level.Channels
    private var currentTopics: List<Topic> = emptyList()
    private var currentMovies: List<Movie> = emptyList()
    private var lastChannelNames: List<String> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recycler = view.findViewById(R.id.recycler)
        header = view.findViewById(R.id.header)
        recycler.layoutManager = LinearLayoutManager(requireContext())

        rowAdapter = RowAdapter(emptyList()) { pos -> onItemClick(pos) }
        movieAdapter = MovieAdapter(
            emptyList(),
            onLongClick = { pos -> onMovieLongClick(pos) }
        ) { pos -> openPlayer(currentMovies, pos) }
        recycler.adapter = rowAdapter

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when (val l = level) {
                        is Level.Movies -> {
                            if (l.topicId != null) loadTopics(l.channel) else loadChannels()
                        }
                        is Level.Topics -> loadChannels()
                        Level.Favorites -> loadChannels()
                        Level.Channels -> {
                            isEnabled = false
                            requireActivity().onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            }
        )

        loadChannels()
    }

    private fun onMovieLongClick(pos: Int): Boolean {
        val movie = currentMovies.getOrNull(pos) ?: return true
        val ctx = requireContext()
        val nowFav = FavoritesManager.toggleTelegramFavorite(ctx, movie)
        Toast.makeText(
            ctx,
            if (nowFav) "Zu Favoriten hinzugefuegt" else "Aus Favoriten entfernt",
            Toast.LENGTH_SHORT
        ).show()
        if (level == Level.Favorites) loadFavorites() else movieAdapter.update(currentMovies)
        return true
    }

    private fun onItemClick(pos: Int) {
        when (val l = level) {
            Level.Channels -> {
                if (pos == 0) {
                    loadFavorites()
                    return
                }
                val channelName = lastChannelNames.getOrNull(pos - 1) ?: return
                loadTopics(channelName)
            }
            is Level.Topics -> {
                val topic = currentTopics.getOrNull(pos) ?: return
                loadMovies(l.channel, topic.id, topic.title)
            }
            is Level.Movies, Level.Favorites -> {
                // Klicks in der Filmliste laufen ueber movieAdapter, nicht hier
            }
        }
    }

    private fun loadFavorites() {
        level = Level.Favorites
        header.showNormal(FAVORITES_ENTRY)
        currentMovies = FavoritesManager.getTelegramFavorites(requireContext())
        recycler.adapter = movieAdapter
        movieAdapter.update(currentMovies)
    }

    private fun loadChannels() {
        level = Level.Channels
        header.showNormal("Kanal waehlen")
        recycler.adapter = rowAdapter
        lifecycleScope.launch {
            try {
                val channels = ApiClient.getChannels()
                lastChannelNames = channels.map { it.name }
                rowAdapter.update(listOf(FAVORITES_ENTRY) + lastChannelNames)
            } catch (e: Exception) {
                rowAdapter.update(listOf(FAVORITES_ENTRY, "Fehler beim Laden: ${e.message}"))
            }
        }
    }

    private fun loadTopics(channel: String) {
        header.showSecondary("Lade...")
        recycler.adapter = rowAdapter
        lifecycleScope.launch {
            try {
                val topics = ApiClient.getTopics(channel)
                if (topics.isEmpty()) {
                    loadMovies(channel, null, null)
                } else {
                    level = Level.Topics(channel)
                    currentTopics = topics
                    header.showNormal("$channel - Thema waehlen")
                    rowAdapter.update(topics.map { "\uD83D\uDCC1 " + it.title })
                }
            } catch (e: Exception) {
                rowAdapter.update(listOf("Fehler beim Laden: ${e.message}"))
            }
        }
    }

    private fun loadMovies(channel: String, topicId: String?, topicTitle: String?) {
        header.showSecondary("Lade... (Beschreibungen werden nachgeladen)")
        recycler.adapter = movieAdapter
        movieAdapter.update(emptyList())
        lifecycleScope.launch {
            try {
                val movies = ApiClient.getMovies(channel, topicId)
                level = Level.Movies(channel, topicId, topicTitle)
                currentMovies = movies
                header.showNormal(if (topicTitle != null) "$channel > $topicTitle" else channel)
                movieAdapter.update(movies)
            } catch (e: Exception) {
                header.showError("Fehler beim Laden: ${e.message}")
            }
        }
    }

    private fun openPlayer(movies: List<Movie>, startIndex: Int) {
        val urls = ArrayList(movies.map { it.streamUrl })
        val titles = ArrayList(movies.map { it.title })
        val intent = Intent(requireContext(), PlayerActivity::class.java)
        intent.putStringArrayListExtra(PlayerActivity.EXTRA_URLS, urls)
        intent.putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, titles)
        intent.putExtra(PlayerActivity.EXTRA_START_INDEX, startIndex)
        startActivity(intent)
    }
}
