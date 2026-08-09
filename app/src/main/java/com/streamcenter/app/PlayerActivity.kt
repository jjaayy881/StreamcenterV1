package com.streamcenter.app

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URLS = "urls"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_START_INDEX = "start_index"
        const val EXTRA_IS_LIVE = "is_live"
    }

    private lateinit var libVLC: LibVLC
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var infoText: TextView

    private var urls: List<String> = emptyList()
    private var titles: List<String> = emptyList()
    private var currentIndex: Int = 0
    private val hideInfoHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        // Ohne das hier greift nach dem Standard-Leerlauf-Timeout des Fire TV (auch
        // waehrend eine Fernbedienung nicht bedient wird, weil man ja gerade nur zuschaut)
        // der Bildschirmschoner - unabhaengig davon, dass gerade aktiv ein Video laeuft.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val videoLayout: VLCVideoLayout = findViewById(R.id.video_layout)
        infoText = findViewById(R.id.text_channel_info)

        urls = intent.getStringArrayListExtra(EXTRA_URLS) ?: emptyList()
        titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: emptyList()
        currentIndex = intent.getIntExtra(EXTRA_START_INDEX, 0).coerceIn(0, (urls.size - 1).coerceAtLeast(0))

        val args = arrayListOf("--no-drop-late-frames", "--no-skip-frames", "--rtsp-tcp")
        libVLC = LibVLC(this, args)
        mediaPlayer = MediaPlayer(libVLC)
        mediaPlayer.attachViews(videoLayout, null, false, false)

        if (urls.isNotEmpty()) {
            playIndex(currentIndex)
        }
    }

    private fun playIndex(index: Int) {
        currentIndex = index
        val media = Media(libVLC, Uri.parse(urls[index]))
        media.setHWDecoderEnabled(true, false)
        mediaPlayer.media = media
        media.release() // Player haelt eine eigene Referenz, lokale kann freigegeben werden
        mediaPlayer.play()
        showChannelInfo()
    }

    private fun zapNext() {
        if (urls.isEmpty()) return
        playIndex((currentIndex + 1) % urls.size)
    }

    private fun zapPrevious() {
        if (urls.isEmpty()) return
        playIndex((currentIndex - 1 + urls.size) % urls.size)
    }

    private fun showChannelInfo() {
        val title = titles.getOrNull(currentIndex) ?: ""
        infoText.text = "${currentIndex + 1}/${urls.size}  $title"
        infoText.visibility = TextView.VISIBLE
        hideInfoHandler.removeCallbacksAndMessages(null)
        hideInfoHandler.postDelayed({ infoText.visibility = TextView.GONE }, 2500)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            // Zappen: D-Pad hoch/runter ODER dedizierte Kanaltasten der Fernbedienung
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                zapNext(); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                zapPrevious(); true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (mediaPlayer.isPlaying) mediaPlayer.pause() else mediaPlayer.play()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (mediaPlayer.isSeekable) mediaPlayer.time = mediaPlayer.time + 10_000
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (mediaPlayer.isSeekable) mediaPlayer.time = (mediaPlayer.time - 10_000).coerceAtLeast(0)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hideInfoHandler.removeCallbacksAndMessages(null)
        mediaPlayer.stop()
        mediaPlayer.detachViews()
        mediaPlayer.release()
        libVLC.release()
    }
}
