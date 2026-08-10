package com.streamcenter.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

private const val TYPE_ITV = "itv"
private const val TYPE_VOD = "vod"
private const val TYPE_SERIES = "series"
private const val PROFILE_SLOTS = 3

private sealed class StalkerLevel {
    object TypeSelect : StalkerLevel()
    object ProfileSelect : StalkerLevel()
    object Favorites : StalkerLevel()
    data class Categories(val type: String, val label: String) : StalkerLevel()
    data class Items(val type: String, val categoryId: String, val label: String) : StalkerLevel()
    data class Seasons(val movieId: String, val label: String) : StalkerLevel()
    data class Episodes(val movieId: String, val seasonId: String, val label: String) : StalkerLevel()
}

/**
 * Stalker-Portal-Tab (Ministra-Protokoll): Live TV / Filme / Serien.
 * EPG bewusst ausgelassen - alles andere (Kategorien, Zapping-faehige Live-Sender,
 * VOD, Serien mit Staffeln/Episoden) laeuft ueber die Endpunkte unter /api/stalker/ im
 * Backend und den bestehenden LibVLC-Player.
 *
 * Bis zu 3 Portal-Profile ("Portal 1/2/3") lassen sich speichern und per Tap ohne
 * erneute Eingabe wechseln - lang druecken auf einen Slot oeffnet das Eingabeformular
 * zum Eintragen/Ueberschreiben. Das Backend schreibt alles selbst in die config.json
 * zurueck, uebersteht also auch einen App-Neustart ohne ADB-Push.
 */
class StalkerFragment : Fragment(R.layout.fragment_stalker) {

    private lateinit var recycler: RecyclerView
    private lateinit var header: TextView
    private lateinit var connectBar: LinearLayout
    private lateinit var connectTitle: TextView
    private lateinit var editName: EditText
    private lateinit var editUrl: EditText
    private lateinit var editMac: EditText
    private lateinit var btnConnect: Button
    private lateinit var connectStatus: TextView
    private lateinit var rowAdapter: RowAdapter
    private lateinit var movieAdapter: MovieAdapter

    private val backStack = ArrayDeque<StalkerLevel>()
    private var level: StalkerLevel = StalkerLevel.TypeSelect

    // Welcher Slot (0/1/2) gerade im Eingabeformular bearbeitet wird - null, solange
    // das Formular nicht sichtbar ist.
    private var pendingSlot: Int? = null

    // Parallele Rohlisten zu dem, was der jeweilige Adapter gerade anzeigt -
    // fuer den Klick-Handler, da RowAdapter/MovieAdapter nur die Position kennen.
    private var currentProfiles: List<StalkerProfile> = emptyList()
    private var currentCategories: List<StalkerNode> = emptyList()
    private var currentItems: List<StalkerNode> = emptyList()
    private var currentSeasons: List<StalkerNode> = emptyList()
    private var currentEpisodes: List<StalkerNode> = emptyList()
    private var currentFavorites: List<Movie> = emptyList()

    // Host des aktuell verbundenen Portals - Favoriten sind pro Portal getrennt
    // gespeichert (movie_id/cmd sind pro Portal vergeben, ein Portalwechsel soll
    // nicht ploetzlich fremde Favoriten zeigen). Wird bei jedem Status-Check aktualisiert.
    private var currentPortalScope: String = "default"

    // Nur lokale Vorbelegung fuers Eingabeformular (schneller als jedes Mal auf den
    // Server zu warten) - die eigentliche "Quelle der Wahrheit" ist die config.json,
    // die das Backend nach erfolgreichem Connect selbst aktualisiert.
    private val uiPrefsFile by lazy { File(requireContext().filesDir, "stalker_ui_prefs.json") }

    private fun loadUiPrefs(): Pair<String, String> = try {
        if (uiPrefsFile.exists()) {
            val o = JSONObject(uiPrefsFile.readText())
            o.optString("url", "") to o.optString("mac", "")
        } else "" to ""
    } catch (e: Exception) {
        "" to ""
    }

    private fun saveUiPrefs(url: String, mac: String) {
        val o = JSONObject()
        o.put("url", url)
        o.put("mac", mac)
        uiPrefsFile.writeText(o.toString())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recycler = view.findViewById(R.id.recycler)
        header = view.findViewById(R.id.header)
        connectBar = view.findViewById(R.id.connect_bar)
        connectTitle = view.findViewById(R.id.connect_title)
        editName = view.findViewById(R.id.edit_stalker_name)
        editUrl = view.findViewById(R.id.edit_stalker_url)
        editMac = view.findViewById(R.id.edit_stalker_mac)
        btnConnect = view.findViewById(R.id.btn_stalker_connect)
        connectStatus = view.findViewById(R.id.connect_status)
        recycler.layoutManager = LinearLayoutManager(requireContext())

        rowAdapter = RowAdapter(emptyList(), onLongClick = { pos -> onRowLongClick(pos) }) { pos -> onRowClick(pos) }
        movieAdapter = MovieAdapter(
            emptyList(),
            onLongClick = { pos -> onMovieLongClick(pos) },
            isFavorite = { ctx, movie -> FavoritesManager.isStalkerFavorite(ctx, currentPortalScope, movie) }
        ) { pos -> onLeafClick(pos) }

        btnConnect.setOnClickListener { onConnectClicked() }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (connectBar.visibility == View.VISIBLE && level != StalkerLevel.TypeSelect) {
                        // Formular wurde aus der Profilauswahl heraus geoeffnet - Abbrechen
                        // soll dahin zurueckkehren, nicht die ganze App verlassen.
                        pendingSlot = null
                        showConnectForm(false)
                        showLevel(StalkerLevel.ProfileSelect, pushToStack = false)
                    } else if (backStack.isEmpty()) {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    } else {
                        showLevel(backStack.removeLast(), pushToStack = false)
                    }
                }
            }
        )

        showLevel(StalkerLevel.TypeSelect, pushToStack = false)
    }

    private fun onConnectClicked() {
        val name = editName.text.toString().trim()
        val url = editUrl.text.toString().trim()
        val mac = editMac.text.toString().trim()
        if (url.isBlank() || mac.isBlank()) {
            connectStatus.visibility = View.VISIBLE
            connectStatus.showError("Bitte Portal-URL und MAC-Adresse eingeben.")
            return
        }
        saveUiPrefs(url, mac)
        btnConnect.isEnabled = false
        connectStatus.visibility = View.VISIBLE
        connectStatus.showSecondary("Verbinde...")
        val slot = pendingSlot
        lifecycleScope.launch {
            val (ok, error) = ApiClient.connectStalker(url, mac, slot, name.ifBlank { null })
            btnConnect.isEnabled = true
            if (ok) {
                connectStatus.visibility = View.GONE
                pendingSlot = null
                showConnectForm(false)
                showLevel(StalkerLevel.TypeSelect, pushToStack = false)
            } else {
                connectStatus.showError("Fehler: $error")
            }
        }
    }

    /** show=true zeigt das Formular fuer pendingSlot; slotLabel/prefill kommen vom Aufrufer. */
    private fun showConnectForm(show: Boolean, error: String? = null, slotLabel: String = "", prefillUrl: String = "", prefillMac: String = "", prefillName: String = "") {
        connectBar.visibility = if (show) View.VISIBLE else View.GONE
        recycler.visibility = if (show) View.GONE else View.VISIBLE
        if (show) {
            connectTitle.text = if (slotLabel.isNotBlank()) "$slotLabel verbinden" else "Stalker-Portal verbinden"
            editName.setText(prefillName)
            if (prefillUrl.isNotBlank()) {
                editUrl.setText(prefillUrl)
                editMac.setText(prefillMac)
            } else {
                val (savedUrl, savedMac) = loadUiPrefs()
                if (editUrl.text.isNullOrBlank()) editUrl.setText(savedUrl)
                if (editMac.text.isNullOrBlank()) editMac.setText(savedMac)
            }
            connectStatus.visibility = if (error != null) View.VISIBLE else View.GONE
            if (error != null) connectStatus.showError(error) else connectStatus.text = ""
            // Ohne das hier landet der D-Pad-Fokus nirgends Sinnvollem, sobald die
            // (bisher fokussierte) Liste auf GONE springt - man "verliert" den Fokus
            // komplett und kommt mangels sichtbarem Ziel nicht mehr ins Formular.
            editName.post { editName.requestFocus() }
        }
    }

    private fun navigateTo(next: StalkerLevel) {
        backStack.addLast(level)
        showLevel(next, pushToStack = true)
    }

    private fun showLevel(target: StalkerLevel, pushToStack: Boolean) {
        level = target
        pendingSlot = null
        showConnectForm(false)
        when (target) {
            StalkerLevel.TypeSelect -> loadTypeSelect()
            StalkerLevel.ProfileSelect -> loadProfileSelect()
            StalkerLevel.Favorites -> loadFavorites()
            is StalkerLevel.Categories -> loadCategories(target)
            is StalkerLevel.Items -> loadItems(target)
            is StalkerLevel.Seasons -> loadSeasons(target)
            is StalkerLevel.Episodes -> loadEpisodes(target)
        }
    }

    private fun onRowClick(pos: Int) {
        when (val l = level) {
            StalkerLevel.TypeSelect -> {
                when (pos) {
                    0 -> navigateTo(StalkerLevel.Categories(TYPE_ITV, "Live TV"))
                    1 -> navigateTo(StalkerLevel.Categories(TYPE_VOD, "Filme"))
                    2 -> navigateTo(StalkerLevel.Categories(TYPE_SERIES, "Serien"))
                    3 -> navigateTo(StalkerLevel.Favorites)
                    else -> navigateTo(StalkerLevel.ProfileSelect)
                }
            }
            StalkerLevel.ProfileSelect -> {
                val profile = currentProfiles.getOrNull(pos) ?: return
                if (profile.isFilled) {
                    connectToSavedProfile(pos, profile)
                } else {
                    openConnectFormForSlot(pos)
                }
            }
            is StalkerLevel.Categories -> {
                val cat = currentCategories.getOrNull(pos) ?: return
                val catId = cat.categoryId ?: return
                navigateTo(StalkerLevel.Items(l.type, catId, cat.title))
            }
            is StalkerLevel.Seasons -> {
                val season = currentSeasons.getOrNull(pos) ?: return
                val seasonId = season.seasonId ?: return
                navigateTo(StalkerLevel.Episodes(l.movieId, seasonId, season.title))
            }
            else -> { /* Items/Episodes laufen ueber movieAdapter, nicht hier */ }
        }
    }

    private fun onRowLongClick(pos: Int): Boolean {
        if (level == StalkerLevel.ProfileSelect) {
            openConnectFormForSlot(pos)
            return true
        }
        return false
    }

    private fun openConnectFormForSlot(slot: Int) {
        val profile = currentProfiles.getOrNull(slot)
        pendingSlot = slot
        val label = profile?.name?.takeIf { it.isNotBlank() } ?: "Portal ${slot + 1}"
        showConnectForm(
            true,
            slotLabel = label,
            prefillUrl = profile?.url ?: "",
            prefillMac = profile?.mac ?: "",
            prefillName = profile?.name?.takeIf { profile.isFilled } ?: ""
        )
    }

    private fun connectToSavedProfile(slot: Int, profile: StalkerProfile) {
        header.showSecondary("Verbinde mit ${profile.name}...")
        lifecycleScope.launch {
            val (ok, error) = ApiClient.connectStalkerSlot(slot)
            if (ok) {
                Toast.makeText(requireContext(), "Verbunden: ${profile.name}", Toast.LENGTH_SHORT).show()
                showLevel(StalkerLevel.TypeSelect, pushToStack = false)
            } else {
                header.showError("Fehler beim Verbinden mit ${profile.name}")
                Toast.makeText(requireContext(), "Fehler: $error", Toast.LENGTH_LONG).show()
                loadProfileSelect()
            }
        }
    }

    private fun onLeafClick(pos: Int) {
        when (level) {
            is StalkerLevel.Items -> {
                val item = currentItems.getOrNull(pos) ?: return
                if (item.streamUrl != null) {
                    playSingle(item.streamUrl, item.title)
                } else if (item.movieId != null) {
                    navigateTo(StalkerLevel.Seasons(item.movieId, item.title))
                }
            }
            is StalkerLevel.Episodes -> {
                val urls = currentEpisodes.mapNotNull { it.streamUrl }
                val titles = currentEpisodes.filter { it.streamUrl != null }.map { it.title }
                val startIndex = currentEpisodes.take(pos + 1).count { it.streamUrl != null } - 1
                if (urls.isNotEmpty() && startIndex >= 0) openPlayer(urls, titles, startIndex)
            }
            StalkerLevel.Favorites -> {
                val movie = currentFavorites.getOrNull(pos) ?: return
                if (movie.streamUrl.isNotBlank()) playSingle(movie.streamUrl, movie.title)
            }
            else -> { /* nicht relevant */ }
        }
    }

    /** Favoriten funktionieren nur bei Live-TV-Kanaelen/Filmen/Episoden (die haben eine
     * direkt abspielbare streamUrl) - Serien-Eintraege selbst (nur Navigation zu Staffeln)
     * lassen sich nicht favorisieren. */
    private fun onMovieLongClick(pos: Int): Boolean {
        val movie = when (level) {
            is StalkerLevel.Items -> currentItems.getOrNull(pos)?.let { toMovie(it) }
            is StalkerLevel.Episodes -> currentEpisodes.getOrNull(pos)?.let { toMovie(it) }
            StalkerLevel.Favorites -> currentFavorites.getOrNull(pos)
            else -> null
        } ?: return true
        if (movie.streamUrl.isBlank()) return true

        val ctx = requireContext()
        val nowFavorite = FavoritesManager.toggleStalkerFavorite(ctx, currentPortalScope, movie)
        Toast.makeText(
            ctx,
            if (nowFavorite) "Zu Favoriten hinzugefuegt" else "Aus Favoriten entfernt",
            Toast.LENGTH_SHORT
        ).show()

        when (level) {
            is StalkerLevel.Items -> movieAdapter.update(currentItems.map { toMovie(it) })
            is StalkerLevel.Episodes -> movieAdapter.update(currentEpisodes.map { toMovie(it) })
            StalkerLevel.Favorites -> loadFavorites()
            else -> {}
        }
        return true
    }

    private fun loadTypeSelect() {
        header.showNormal("Stalker Portal")
        recycler.adapter = rowAdapter
        rowAdapter.update(listOf("Pruefe Verbindung..."))
        lifecycleScope.launch {
            try {
                val status = refreshStatus()
                if (status.connected) {
                    rowAdapter.update(listOf(
                        "\uD83D\uDCFA Live TV", "\uD83C\uDFAC Filme", "\uD83D\uDCFA Serien",
                        "\u2605 Favoriten", "\uD83D\uDD04 Portal wechseln"
                    ))
                } else {
                    // Direkt in die Profilauswahl, damit man ohne Umweg ein Portal
                    // (neu oder gespeichert) waehlen kann.
                    showLevel(StalkerLevel.ProfileSelect, pushToStack = false)
                }
            } catch (e: Exception) {
                rowAdapter.update(listOf("Fehler: ${e.message}"))
            }
        }
    }

    private fun loadFavorites() {
        recycler.adapter = movieAdapter
        val favs = FavoritesManager.getStalkerFavorites(requireContext(), currentPortalScope)
        currentFavorites = favs
        if (favs.isEmpty()) {
            header.showSecondary("\u2605 Favoriten (noch keine - in einer Liste lange druecken zum Merken)")
        } else {
            header.showNormal("\u2605 Favoriten")
        }
        movieAdapter.update(favs)
    }

    private fun loadProfileSelect() {
        header.showNormal("Portal waehlen (lange druecken zum Bearbeiten)")
        recycler.adapter = rowAdapter
        rowAdapter.update(listOf("Lade..."))
        lifecycleScope.launch {
            try {
                var profiles = ApiClient.getStalkerProfiles()
                if (profiles.size < PROFILE_SLOTS) {
                    // Backend liefert normalerweise schon genau PROFILE_SLOTS Eintraege,
                    // hier nur zur Sicherheit falls mal weniger zurueckkommen.
                    profiles = profiles + List(PROFILE_SLOTS - profiles.size) {
                        StalkerProfile("Portal ${profiles.size + it + 1}", "", "")
                    }
                }
                currentProfiles = profiles
                rowAdapter.update(profiles.map { p ->
                    if (p.isFilled) {
                        val host = try { java.net.URI(p.url).host ?: p.url } catch (e: Exception) { p.url }
                        "${p.name}: $host"
                    } else {
                        "${p.name} (leer - antippen zum Eintragen)"
                    }
                })
            } catch (e: Exception) {
                rowAdapter.update(listOf("Fehler: ${e.message}"))
            }
        }
    }

    private fun loadCategories(target: StalkerLevel.Categories) {
        header.showNormal(target.label)
        recycler.adapter = rowAdapter
        rowAdapter.update(listOf("Lade..."))
        lifecycleScope.launch {
            try {
                checkConnected()
                val cats = ApiClient.getStalkerCategories(target.type)
                currentCategories = cats
                if (cats.isEmpty()) {
                    rowAdapter.update(listOf("Keine Kategorien gefunden"))
                } else {
                    rowAdapter.update(cats.map { it.title })
                }
            } catch (e: Exception) {
                rowAdapter.update(listOf("Fehler: ${e.message}"))
            }
        }
    }

    private fun loadItems(target: StalkerLevel.Items) {
        header.showNormal(target.label)
        recycler.adapter = movieAdapter
        movieAdapter.update(emptyList())
        lifecycleScope.launch {
            try {
                checkConnected()
                val items = ApiClient.getStalkerItems(target.type, target.categoryId)
                currentItems = items
                movieAdapter.update(items.map { toMovie(it) })
            } catch (e: Exception) {
                header.showError("Fehler: ${e.message}")
            }
        }
    }

    private fun loadSeasons(target: StalkerLevel.Seasons) {
        header.showNormal(target.label)
        recycler.adapter = rowAdapter
        rowAdapter.update(listOf("Lade..."))
        lifecycleScope.launch {
            try {
                checkConnected()
                val seasons = ApiClient.getStalkerSeasons(target.movieId)
                currentSeasons = seasons
                if (seasons.isEmpty()) {
                    rowAdapter.update(listOf("Keine Staffeln gefunden"))
                } else {
                    rowAdapter.update(seasons.map { it.title })
                }
            } catch (e: Exception) {
                rowAdapter.update(listOf("Fehler: ${e.message}"))
            }
        }
    }

    private fun loadEpisodes(target: StalkerLevel.Episodes) {
        header.showNormal(target.label)
        recycler.adapter = movieAdapter
        movieAdapter.update(emptyList())
        lifecycleScope.launch {
            try {
                checkConnected()
                val episodes = ApiClient.getStalkerEpisodes(target.movieId, target.seasonId)
                currentEpisodes = episodes
                movieAdapter.update(episodes.map { toMovie(it) })
            } catch (e: Exception) {
                header.showError("Fehler: ${e.message}")
            }
        }
    }

    private suspend fun checkConnected() {
        val status = refreshStatus()
        if (!status.connected) {
            throw IllegalStateException(status.error ?: "Stalker-Portal nicht verbunden - bitte oben im Tab neu verbinden")
        }
    }

    /** Fragt den Verbindungsstatus ab UND aktualisiert dabei currentPortalScope (fuer
     * portalspezifische Favoriten) - zentrale Stelle statt das an mehreren Orten zu
     * duplizieren. */
    private suspend fun refreshStatus(): StalkerStatus {
        val status = ApiClient.getStalkerStatus()
        currentPortalScope = scopeFromPortalUrl(status.portalUrl)
        return status
    }

    private fun scopeFromPortalUrl(portalUrl: String?): String {
        if (portalUrl.isNullOrBlank()) return "default"
        return try {
            java.net.URI(portalUrl).host ?: portalUrl
        } catch (e: Exception) {
            portalUrl
        }
    }

    private fun toMovie(node: StalkerNode) = Movie(
        id = node.id ?: node.title,
        title = node.title,
        streamUrl = node.streamUrl ?: "",
        posterUrl = node.posterUrl
    )

    private fun playSingle(url: String, title: String) = openPlayer(listOf(url), listOf(title), 0)

    private fun openPlayer(urls: List<String>, titles: List<String>, startIndex: Int) {
        val intent = Intent(requireContext(), PlayerActivity::class.java)
        intent.putStringArrayListExtra(PlayerActivity.EXTRA_URLS, ArrayList(urls))
        intent.putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(titles))
        intent.putExtra(PlayerActivity.EXTRA_START_INDEX, startIndex)
        startActivity(intent)
    }
}
