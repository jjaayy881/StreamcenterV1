package com.streamcenter.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/**
 * Zeigt nach dem Telegram-Login alle Kanaele/Gruppen des Accounts zum Antippen an,
 * statt dass der Nutzer IDs von Hand in die config.json eintragen muss. Ausgewaehlte
 * Kanaele werden ueber ApiClient.saveTelegramChannels() dauerhaft gespeichert.
 */
class ChannelSelectionActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var btnDone: Button
    private lateinit var adapter: ChannelPickAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channel_selection)

        statusText = findViewById(R.id.text_channels_status)
        recycler = findViewById(R.id.recycler_channels)
        btnDone = findViewById(R.id.btn_channels_done)
        recycler.layoutManager = LinearLayoutManager(this)

        adapter = ChannelPickAdapter(emptyList()) { updateDoneButtonLabel() }
        recycler.adapter = adapter

        btnDone.isEnabled = false
        btnDone.text = "Fertig"
        btnDone.setOnClickListener { saveSelection() }

        loadDialogs()
    }

    private fun updateDoneButtonLabel() {
        val count = adapter.selectedCount()
        btnDone.isEnabled = count > 0
        btnDone.text = if (count > 0) "Fertig ($count ausgewaehlt)" else "Fertig"
    }

    private fun loadDialogs() {
        statusText.showSecondary("Lade deine Kanaele/Gruppen...")
        lifecycleScope.launch {
            val (dialogs, error) = ApiClient.telegramDialogs()
            if (error != null || dialogs == null) {
                statusText.showError("Konnte Kanalliste nicht laden: ${error ?: "unbekannter Fehler"}")
                return@launch
            }
            if (dialogs.isEmpty()) {
                statusText.showError("Keine Kanaele/Gruppen gefunden")
                return@launch
            }
            statusText.showSecondary("Kanaele antippen, die in der App erscheinen sollen:")
            adapter.update(dialogs)
        }
    }

    private fun saveSelection() {
        val selected = adapter.selectedAsMap()
        if (selected.isEmpty()) return
        btnDone.isEnabled = false
        statusText.showSecondary("Speichere Auswahl...")
        lifecycleScope.launch {
            val (status, error) = ApiClient.saveTelegramChannels(selected)
            if (error != null) {
                statusText.showError("Speichern fehlgeschlagen: $error")
                btnDone.isEnabled = true
                return@launch
            }
            if (status == "ok") {
                startActivity(Intent(this@ChannelSelectionActivity, MainActivity::class.java))
                finish()
            } else {
                statusText.showError("Unerwartete Antwort: $status")
                btnDone.isEnabled = true
            }
        }
    }
}

/**
 * Einfache Mehrfachauswahl-Liste, D-Pad-tauglich: OK/Klick auf eine Zeile schaltet
 * die Auswahl um (Haekchen-Praefix + Akzentfarbe statt echter CheckBox-View, damit es
 * sich optisch nahtlos in den bestehenden item_row-Stil der App einfuegt).
 */
class ChannelPickAdapter(
    private var items: List<ApiClient.TelegramDialog>,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<ChannelPickAdapter.Holder>() {

    private val selectedIds = mutableSetOf<Long>()

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.text_title)
    }

    fun update(newItems: List<ApiClient.TelegramDialog>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun selectedCount() = selectedIds.size

    fun selectedAsMap(): Map<String, Long> =
        items.filter { selectedIds.contains(it.id) }.associate { it.name to it.id }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val isSelected = selectedIds.contains(item.id)
        holder.text.text = if (isSelected) "\u2713  ${item.name}" else "\u2610  ${item.name}"
        holder.text.setTextColor(
            ContextCompat.getColor(
                holder.itemView.context,
                if (isSelected) R.color.color_primary else R.color.color_text_primary
            )
        )
        holder.itemView.setOnClickListener {
            if (isSelected) selectedIds.remove(item.id) else selectedIds.add(item.id)
            notifyItemChanged(position)
            onSelectionChanged()
        }
        holder.itemView.applyTvFocusAnimation()
    }

    override fun getItemCount() = items.size
}
