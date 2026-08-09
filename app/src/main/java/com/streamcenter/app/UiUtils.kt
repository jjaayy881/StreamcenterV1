package com.streamcenter.app

import android.widget.TextView
import androidx.core.content.ContextCompat

/** Fehlertexte deutlich abheben statt in derselben Farbe wie normale Titel/Status
 * untergehen zu lassen - sonst uebersieht man auf dem Fernseher leicht, dass gerade
 * etwas schiefgelaufen ist. */
fun TextView.showError(message: String) {
    text = message
    setTextColor(ContextCompat.getColor(context, R.color.color_error))
}

fun TextView.showNormal(message: CharSequence) {
    text = message
    setTextColor(ContextCompat.getColor(context, R.color.color_text_primary))
}

fun TextView.showSecondary(message: CharSequence) {
    text = message
    setTextColor(ContextCompat.getColor(context, R.color.color_text_secondary))
}
