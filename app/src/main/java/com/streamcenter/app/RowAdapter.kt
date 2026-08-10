package com.streamcenter.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class RowAdapter(
    private var items: List<String>,
    private val layoutRes: Int = R.layout.item_row,
    private val onLongClick: ((Int) -> Boolean)? = null,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<RowAdapter.RowHolder>() {

    class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.text_title)
    }

    // Nur beim naechsten Bind von Position 0 nach einem update() den initialen D-Pad-Fokus
    // setzen - nicht bei jedem Rebind (Scroll-Recycling etc.), sonst reisst das dem Nutzer
    // staendig den Fokus zurueck auf die Liste, selbst wenn er laengst manuell zu einem
    // Eingabefeld darueber navigiert ist ("man weiss nie wo man gerade ist").
    private var pendingInitialFocus = true

    fun update(newItems: List<String>, focusFirst: Boolean = true) {
        items = newItems
        pendingInitialFocus = focusFirst
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return RowHolder(view)
    }

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        holder.text.text = items[position]
        holder.itemView.setOnClickListener { onClick(position) }
        holder.itemView.setOnLongClickListener { onLongClick?.invoke(position) ?: false }
        holder.itemView.applyTvFocusAnimation()
        if (position == 0 && pendingInitialFocus) {
            pendingInitialFocus = false
            // post(): requestFocus() waehrend onBindViewHolder greift oft nicht zuverlaessig,
            // weil die View da noch nicht zwingend angehaengt/layoutet ist.
            holder.itemView.post { holder.itemView.requestFocus() }
        }
    }

    override fun getItemCount() = items.size
}
