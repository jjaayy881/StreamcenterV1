package com.streamcenter.app

import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Der typische "TV-App-Pop": fokussierte/angetippte Karten skalieren leicht hoch und
 * heben sich per Elevation vom Hintergrund ab, damit auf dem Fernseher (D-Pad,
 * Sitzabstand) immer eindeutig erkennbar ist, welches Element gerade aktiv ist. Reine
 * Hintergrundfarbe (wie im bisherigen focus_background) allein ist auf der Couch oft
 * zu subtil.
 */
private const val FOCUS_SCALE = 1.06f
private const val FOCUS_ELEVATION_DP = 10f
private const val FOCUS_ANIM_DURATION_MS = 150L

fun View.applyTvFocusAnimation(onFocusChanged: ((Boolean) -> Unit)? = null) {
    val density = resources.displayMetrics.density

    fun animateTo(active: Boolean) {
        val targetScale = if (active) FOCUS_SCALE else 1f
        val targetElevation = if (active) FOCUS_ELEVATION_DP * density else 0f
        animate()
            .scaleX(targetScale)
            .scaleY(targetScale)
            .translationZ(targetElevation)
            .setDuration(FOCUS_ANIM_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    // D-Pad/Fernbedienung (Fire TV)
    onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
        animateTo(hasFocus)
        onFocusChanged?.invoke(hasFocus)
    }

    // Touchscreen (Handy/Tablet): eine normale klickbare View wird durch einen Tap
    // NICHT "fokussiert" im Android-Sinne (state_focused greift praktisch nur bei
    // D-Pad/Tastatur-Navigation) - ohne diesen Touch-Listener waere auf dem Handy also
    // ueberhaupt kein Pop-Effekt sichtbar. isFocused wird dabei respektiert, damit ein
    // per D-Pad fokussiertes Element beim Loslassen eines zusaetzlichen Taps nicht
    // "zurueckspringt".
    setOnTouchListener { view, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> animateTo(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                if (!view.isFocused) animateTo(false)
        }
        false
    }
}
