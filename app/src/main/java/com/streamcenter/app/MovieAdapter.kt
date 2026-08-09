package com.streamcenter.app

import android.content.Context
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class MovieAdapter(
    private var movies: List<Movie>,
    private val onLongClick: ((Int) -> Boolean)? = null,
    private val isFavorite: ((Context, Movie) -> Boolean) = FavoritesManager::isTelegramFavorite,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<MovieAdapter.MovieHolder>() {

    class MovieHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.text_title)
        val overview: TextView = view.findViewById(R.id.text_overview)
    }

    // Nur beim naechsten Bind von Position 0 nach einem update() den initialen D-Pad-Fokus
    // setzen - nicht bei jedem Rebind (Scroll-Recycling etc.), sonst reisst das dem Nutzer
    // staendig den Fokus zurueck auf die Liste, selbst wenn er laengst manuell zu einem
    // Eingabefeld darueber navigiert ist ("man weiss nie wo man gerade ist").
    private var pendingInitialFocus = true

    fun update(newMovies: List<Movie>) {
        movies = newMovies
        pendingInitialFocus = true
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MovieHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_movie, parent, false)
        return MovieHolder(view)
    }

    override fun onBindViewHolder(holder: MovieHolder, position: Int) {
        val movie = movies[position]
        val context: Context = holder.itemView.context
        val isFav = isFavorite(context, movie)
        val channelPrefix = movie.channel?.let { "[$it] " } ?: ""
        val icon = if (isFav) "\u2605 " else "\uD83C\uDFAC "
        val ratingText = movie.rating?.takeIf { it > 0 }?.let { " \u2605 %.1f".format(it) } ?: ""
        val fullText = icon + channelPrefix + movie.title + ratingText

        // Favoriten-/Bewertungs-Stern farblich absetzen (gold), statt alles in derselben
        // Textfarbe verschwinden zu lassen - sonst sieht man aus Sitzabstand kaum, dass
        // ein Eintrag ueberhaupt favorisiert ist.
        val spannable = SpannableStringBuilder(fullText)
        val favoriteColor = ContextCompat.getColor(context, R.color.color_favorite)
        if (isFav) {
            spannable.setSpan(ForegroundColorSpan(favoriteColor), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (ratingText.isNotEmpty()) {
            val ratingStarIndex = fullText.indexOf('\u2605', icon.length + channelPrefix.length + movie.title.length)
            if (ratingStarIndex >= 0) {
                spannable.setSpan(
                    ForegroundColorSpan(favoriteColor), ratingStarIndex, ratingStarIndex + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        holder.title.text = spannable

        if (!movie.overview.isNullOrBlank()) {
            holder.overview.text = movie.overview
            holder.overview.visibility = TextView.VISIBLE
        } else {
            holder.overview.visibility = TextView.GONE
        }

        holder.itemView.setOnClickListener { onClick(position) }
        holder.itemView.setOnLongClickListener { onLongClick?.invoke(position) ?: false }
        holder.itemView.applyTvFocusAnimation()
        if (position == 0 && pendingInitialFocus) {
            pendingInitialFocus = false
            holder.itemView.post { holder.itemView.requestFocus() }
        }
    }

    override fun getItemCount() = movies.size
}
