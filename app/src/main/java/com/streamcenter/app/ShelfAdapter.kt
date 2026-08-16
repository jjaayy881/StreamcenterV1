package com.streamcenter.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

/** Eine Regal-Zeile: eine Kategorie + ihre Eintraege. items=null heisst "noch nicht
 * geladen" (loest onNeedLoad im Adapter aus), emptyList() heisst "geladen, aber leer". */
data class ShelfRowData(val category: StalkerNode, var items: List<StalkerNode>? = null)

/** Netflix-artige Regal-Ansicht: eine vertikale Liste von Kategorie-Zeilen, jede Zeile
 * selbst eine horizontal scrollende Poster-Liste. Laedt die Eintraege pro Zeile erst,
 * wenn die Zeile tatsaechlich gebunden/sichtbar wird (nicht alle Kategorien auf einmal -
 * das waere bei vielen Kategorien unnoetig viele parallele Anfragen ans Portal). */
class ShelfAdapter(
    private var rows: List<ShelfRowData>,
    private val onNeedLoad: (rowIndex: Int) -> Unit,
    private val onItemClick: (rowIndex: Int, itemIndex: Int) -> Unit
) : RecyclerView.Adapter<ShelfAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.text_row_title)
        val status: TextView = view.findViewById(R.id.text_row_status)
        val recycler: RecyclerView = view.findViewById(R.id.recycler_row)
    }

    fun update(newRows: List<ShelfRowData>) {
        rows = newRows
        notifyDataSetChanged()
    }

    /** Aktualisiert nur eine einzelne Zeile (z.B. sobald ihre Eintraege fertig geladen
     * sind) - vermeidet ein komplettes Neuzeichnen der ganzen Liste samt Scroll-Sprung. */
    fun setRowItems(rowIndex: Int, items: List<StalkerNode>) {
        if (rowIndex !in rows.indices) return
        rows[rowIndex].items = items
        notifyItemChanged(rowIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_shelf_row, parent, false)
        val holder = Holder(view)
        holder.recycler.layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.title.text = row.category.title
        val items = row.items
        when {
            items == null -> {
                holder.status.visibility = View.VISIBLE
                holder.status.text = "Lade..."
                holder.recycler.visibility = View.GONE
                onNeedLoad(position)
            }
            items.isEmpty() -> {
                holder.status.visibility = View.VISIBLE
                holder.status.text = "Keine Eintraege"
                holder.recycler.visibility = View.GONE
            }
            else -> {
                holder.status.visibility = View.GONE
                holder.recycler.visibility = View.VISIBLE
                holder.recycler.adapter = ShelfPosterAdapter(items) { itemPos -> onItemClick(position, itemPos) }
            }
        }
    }

    override fun getItemCount() = rows.size
}

/** Einzelne Poster-Kachel innerhalb einer Regal-Zeile. */
class ShelfPosterAdapter(
    private val items: List<StalkerNode>,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<ShelfPosterAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val poster: ImageView = view.findViewById(R.id.image_poster)
        val title: TextView = view.findViewById(R.id.text_title)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_shelf_poster, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        if (!item.posterUrl.isNullOrBlank()) {
            Glide.with(holder.itemView.context).load(item.posterUrl).centerCrop().into(holder.poster)
        } else {
            Glide.with(holder.itemView.context).clear(holder.poster)
            holder.poster.setImageDrawable(null)
        }
        holder.itemView.setOnClickListener { onClick(position) }
        holder.itemView.applyTvFocusAnimation()
    }

    override fun getItemCount() = items.size
}
