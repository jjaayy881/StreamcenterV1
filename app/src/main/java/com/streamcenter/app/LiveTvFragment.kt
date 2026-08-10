package com.streamcenter.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
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

class LiveTvFragment : Fragment(R.layout.fragment_livetv) {

    private lateinit var editUrl: EditText
    private lateinit var btnLoad: Button
    private lateinit var genreBar: LinearLayout
    private lateinit var recyclerChannels: RecyclerView

    // Eine einzige Adapter-Instanz statt bei jedem Rendern eine neue zu erzeugen - sonst
    // greift der "nur einmal pro update() fokussieren"-Schutz in RowAdapter nicht, weil
    // jede neue Instanz wieder bei pendingInitialFocus=true anfaengt. Ausserdem bekommt die
    // Liste hier bewusst NIE den initialen D-Pad-Fokus (focusFirst=false ueberall unten) -
    // das M3U-Eingabefeld ist das wichtigste Element auf diesem Screen und muss immer
    // zuverlaessig erreichbar bleiben, auch nachdem Kanaele geladen sind.
    private val channelAdapter = RowAdapter(
        emptyList(),
        onLongClick = { pos -> onChannelLongClick(pos) }
    ) { pos -> openPlayer(filtered, pos) }

    private var allChannels: List<LiveTvChannel> = emptyList()
    private var filtered: List<LiveTvChannel> = emptyList()
    private var currentGroup: String = "Alle"

    // JSON-Datei statt SharedPreferences - leicht nachvollziehbar/inspizierbar,
    // wird bei jeder neuen Eingabe komplett ueberschrieben (nur eine URL gespeichert).
    private val m3uConfigFile by lazy { File(requireContext().filesDir, "m3u_config.json") }

    private fun loadSavedM3uUrl(): String? {
        return try {
            if (!m3uConfigFile.exists()) return null
            JSONObject(m3uConfigFile.readText()).optString("url", null)
        } catch (e: Exception) {
            null
        }
    }

    private fun saveM3uUrl(url: String) {
        val json = JSONObject()
        json.put("url", url)
        m3uConfigFile.writeText(json.toString()) // ueberschreibt die Datei komplett
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        editUrl = view.findViewById(R.id.edit_m3u_url)
        btnLoad = view.findViewById(R.id.btn_load)
        genreBar = view.findViewById(R.id.genre_bar)
        recyclerChannels = view.findViewById(R.id.recycler_channels)

        recyclerChannels.layoutManager = LinearLayoutManager(requireContext())
        recyclerChannels.adapter = channelAdapter

        val savedUrl = loadSavedM3uUrl()
        if (!savedUrl.isNullOrBlank()) {
            editUrl.setText(savedUrl)
            loadM3u(savedUrl)
        } else {
            // Auch ohne geladene M3U schon die Favoriten-Leiste anbieten
            renderGenres()
            renderChannels(FAVORITES_GROUP)
        }

        btnLoad.setOnClickListener {
            val url = editUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                saveM3uUrl(url)
                loadM3u(url)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // ViewPager2 weist beim Tab-Wechsel per D-Pad keinen Fokus automatisch zu (bei
        // Maus/Touch ist das egal, ein Klick setzt Fokus direkt) - ohne das hier landete
        // der D-Pad-Fokus beim ersten Reinschalten in diesen Tab nirgends, das M3U-Feld
        // war nur per Maus/Touch erreichbar. onResume() feuert bei ViewPager2 zuverlaessig
        // jedes Mal, wenn diese Seite zur aktuell sichtbaren wird (nicht nur beim ersten
        // Erzeugen des Fragments).
        editUrl.post { editUrl.requestFocus() }
    }

    private fun loadM3u(url: String) {
        lifecycleScope.launch {
            try {
                allChannels = ApiClient.loadM3u(url)
                renderGenres()
                renderChannels("Alle")
            } catch (e: Exception) {
                channelAdapter.update(listOf("Fehler: ${e.message}"), focusFirst = false)
            }
        }
    }

    private fun renderGenres() {
        val groups = listOf(FAVORITES_GROUP, "Alle") + allChannels.map { it.group }.distinct()
        genreBar.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        groups.forEach { group ->
            val chip = inflater.inflate(R.layout.item_chip, genreBar, false) as TextView
            chip.text = group
            chip.setOnClickListener { renderChannels(group) }
            chip.applyTvFocusAnimation()
            genreBar.addView(chip)
        }
    }

    private fun renderChannels(group: String) {
        currentGroup = group
        filtered = when (group) {
            FAVORITES_GROUP -> FavoritesManager.getLiveTvFavorites(requireContext())
            "Alle" -> allChannels
            else -> allChannels.filter { it.group == group }
        }
        bindChannelAdapter()
    }

    private fun bindChannelAdapter() {
        val ctx = requireContext()
        val titles = filtered.map { c ->
            val star = if (FavoritesManager.isLiveTvFavorite(ctx, c)) "\u2605 " else "\uD83D\uDCFA "
            star + c.title
        }
        // focusFirst=false: das M3U-Feld oben soll immer der verlaessliche Ausgangspunkt
        // bleiben, die Liste wird nur per bewusster D-Pad-Navigation (runter) erreicht.
        channelAdapter.update(titles, focusFirst = false)
    }

    private fun onChannelLongClick(pos: Int): Boolean {
        val channel = filtered.getOrNull(pos) ?: return true
        val ctx = requireContext()
        val nowFav = FavoritesManager.toggleLiveTvFavorite(ctx, channel)
        Toast.makeText(
            ctx,
            if (nowFav) "Zu Favoriten hinzugefuegt" else "Aus Favoriten entfernt",
            Toast.LENGTH_SHORT
        ).show()
        // Wenn wir gerade die Favoriten-Ansicht zeigen und einer entfernt wurde,
        // Liste neu aufbauen; sonst nur Stern-Praefix aktualisieren.
        if (currentGroup == FAVORITES_GROUP) renderChannels(FAVORITES_GROUP) else bindChannelAdapter()
        return true
    }

    private fun openPlayer(channels: List<LiveTvChannel>, startIndex: Int) {
        val urls = ArrayList(channels.map { it.streamUrl })
        val titles = ArrayList(channels.map { it.title })
        val intent = Intent(requireContext(), PlayerActivity::class.java)
        intent.putStringArrayListExtra(PlayerActivity.EXTRA_URLS, urls)
        intent.putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, titles)
        intent.putExtra(PlayerActivity.EXTRA_START_INDEX, startIndex)
        intent.putExtra(PlayerActivity.EXTRA_IS_LIVE, true)
        startActivity(intent)
    }
}
