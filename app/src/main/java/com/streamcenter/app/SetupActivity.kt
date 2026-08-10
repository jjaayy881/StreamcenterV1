package com.streamcenter.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File

class SetupActivity : AppCompatActivity() {

    private lateinit var editJson: EditText
    private lateinit var statusText: TextView

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalStateException("Datei leer")
                saveConfig(text)
            } catch (e: Exception) {
                statusText.showError("Fehler beim Lesen: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        editJson = findViewById(R.id.edit_json)
        statusText = findViewById(R.id.text_status)

        findViewById<Button>(R.id.btn_pick_file).setOnClickListener {
            pickFileLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }

        findViewById<Button>(R.id.btn_save_text).setOnClickListener {
            saveConfig(editJson.text.toString())
        }

        findViewById<Button>(R.id.btn_paste_clipboard).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip == null || clip.itemCount == 0) {
                statusText.showError("Zwischenablage ist leer")
            } else {
                val text = clip.getItemAt(0).coerceToText(this).toString()
                editJson.setText(text)
                statusText.showSecondary("${text.length} Zeichen aus Zwischenablage geladen - bitte pruefen und speichern")
            }
        }
    }

    private fun saveConfig(jsonText: String) {
        try {
            // Validierung: muss gueltiges JSON mit den erwarteten Feldern sein.
            // session_string ist bewusst NICHT mehr Pflicht - die App loggt sich bei
            // Bedarf selbst per Telefonnummer/Code ein (siehe TelegramLoginActivity)
            // und traegt den session_string danach automatisch nach.
            val json = JSONObject(jsonText)
            listOf("api_id", "api_hash").forEach {
                if (!json.has(it)) throw IllegalArgumentException("Feld '$it' fehlt")
            }
            if (!json.has("channels")) json.put("channels", JSONObject())

            val file = StreamApp.configFile(application)
            file.writeText(json.toString())

            StreamApp.startBackendIfNeeded(application)

            val hasSessionString = json.has("session_string") && json.optString("session_string").isNotBlank()
            val nextActivity = if (hasSessionString) MainActivity::class.java else TelegramLoginActivity::class.java
            startActivity(Intent(this, nextActivity))
            finish()
        } catch (e: Exception) {
            statusText.showError("Ungueltige config.json: ${e.message}")
        }
    }
}
