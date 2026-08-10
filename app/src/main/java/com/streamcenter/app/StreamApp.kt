package com.streamcenter.app

import android.app.Application
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class StreamApp : Application() {

    companion object {
        const val BASE_URL = "http://127.0.0.1:9090"
        const val TAG = "StreamApp"
        private val backendStarted = AtomicBoolean(false)

        fun configFile(app: Application): File = File(app.filesDir, "config.json")

        fun hasConfig(app: Application): Boolean = configFile(app).exists()

        /** true, wenn eine config.json existiert, aber noch kein session_string drinsteht -
         * z.B. weil der Telefonnummer/Code-Login noch nicht durchgefuehrt oder unterbrochen
         * wurde (App-Neustart mitten im Login). MainActivity leitet in diesem Fall zu
         * TelegramLoginActivity statt zu den Tabs. */
        fun needsTelegramLogin(app: Application): Boolean {
            if (!hasConfig(app)) return false
            return try {
                val json = org.json.JSONObject(configFile(app).readText())
                !json.has("session_string") || json.optString("session_string").isBlank()
            } catch (e: Exception) {
                false
            }
        }

        /** true, wenn schon eingeloggt (session_string vorhanden), aber noch keine Kanaele
         * ausgewaehlt wurden - z.B. weil die App zwischen Login und Kanalauswahl beendet
         * wurde. MainActivity leitet in diesem Fall zu ChannelSelectionActivity. */
        fun needsChannelSelection(app: Application): Boolean {
            if (needsTelegramLogin(app)) return false
            return try {
                val json = org.json.JSONObject(configFile(app).readText())
                val channels = json.optJSONObject("channels")
                channels == null || channels.length() == 0
            } catch (e: Exception) {
                false
            }
        }

        /** Darf mehrfach aufgerufen werden - startet den Server nur beim ersten Mal. */
        fun startBackendIfNeeded(app: Application) {
            if (!hasConfig(app)) return
            if (!backendStarted.compareAndSet(false, true)) return

            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(app))
            }
            val configFile = configFile(app)
            Thread {
                try {
                    val py = Python.getInstance()
                    val backend = py.getModule("backend")
                    backend.callAttr("start_server", configFile.absolutePath)
                } catch (e: Exception) {
                    Log.e(TAG, "Backend-Fehler", e)
                    backendStarted.set(false)
                }
            }.start()
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        // Start erfolgt erst, sobald config.json existiert (siehe MainActivity/SetupActivity)
        startBackendIfNeeded(this)
    }
}
