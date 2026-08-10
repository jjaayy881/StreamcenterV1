package com.streamcenter.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Telegram-Login direkt in der App: Telefonnummer -> Code (SMS/App) -> ggf. 2FA-Passwort.
 * Der session_string wird intern von backend.py erzeugt und in die config.json geschrieben -
 * der Nutzer sieht ihn nie und muss ihn nie von Hand eintragen/kopieren.
 */
class TelegramLoginActivity : AppCompatActivity() {

    private enum class Step { PHONE, CODE, PASSWORD }

    private lateinit var labelText: TextView
    private lateinit var editInput: EditText
    private lateinit var btnNext: Button
    private lateinit var statusText: TextView

    private var step: Step = Step.PHONE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_telegram_login)

        labelText = findViewById(R.id.text_login_label)
        editInput = findViewById(R.id.edit_login_input)
        btnNext = findViewById(R.id.btn_login_next)
        statusText = findViewById(R.id.text_login_status)

        // Backend sicherstellen (idempotent - startet nur einmal wirklich)
        StreamApp.startBackendIfNeeded(application)

        showStep(Step.PHONE)

        btnNext.setOnClickListener { onNextClicked() }
    }

    private fun showStep(newStep: Step) {
        step = newStep
        editInput.text.clear()
        statusText.text = ""
        when (newStep) {
            Step.PHONE -> {
                labelText.text = "Telefonnummer mit Laendervorwahl (z.B. +49...)"
                editInput.hint = "+49 151 12345678"
                btnNext.text = "Code anfordern"
            }
            Step.CODE -> {
                labelText.text = "Code, den Telegram dir gerade geschickt hat"
                editInput.hint = "12345"
                btnNext.text = "Bestaetigen"
            }
            Step.PASSWORD -> {
                labelText.text = "Dein Telegram-Cloud-Passwort (2FA)"
                editInput.hint = "Passwort"
                btnNext.text = "Bestaetigen"
            }
        }
    }

    private fun onNextClicked() {
        val value = editInput.text.toString().trim()
        if (value.isEmpty()) {
            statusText.showError("Bitte etwas eingeben")
            return
        }
        btnNext.isEnabled = false
        statusText.showSecondary("Einen Moment...")

        lifecycleScope.launch {
            val (status, error) = when (step) {
                Step.PHONE -> ApiClient.telegramLoginStart(value)
                Step.CODE -> ApiClient.telegramLoginCode(value)
                Step.PASSWORD -> ApiClient.telegramLoginPassword(value)
            }
            btnNext.isEnabled = true

            if (error != null) {
                statusText.showError(error)
                return@launch
            }

            when (status) {
                "code_sent" -> showStep(Step.CODE)
                "need_password" -> showStep(Step.PASSWORD)
                "ok" -> {
                    startActivity(Intent(this@TelegramLoginActivity, MainActivity::class.java))
                    finish()
                }
                else -> statusText.showError("Unerwartete Antwort: $status")
            }
        }
    }
}
