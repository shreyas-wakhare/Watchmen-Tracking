package com.watchmen.tracker

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.*

class SetupActivity : AppCompatActivity() {

    private lateinit var languageSpinner: Spinner
    private lateinit var etDeviceName: EditText
    private lateinit var etSupervisorPhone: EditText
    private lateinit var etProjectNumber: EditText
    private lateinit var etServerUrl: EditText
    private lateinit var btnSaveSetup: Button
    private lateinit var ttsHelper: MultilingualTTS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        BackendEndpointManager.init(applicationContext)

        // Initialize TTS
        ttsHelper = MultilingualTTS(this)
        ttsHelper.initialize()

        initializeViews()
        setupLanguageSelector()
        setupSaveButton()
    }

    private fun initializeViews() {
        languageSpinner = findViewById(R.id.languageSpinner)
        etDeviceName = findViewById(R.id.etDeviceName)
        etSupervisorPhone = findViewById(R.id.etSupervisorPhone)
        etProjectNumber = findViewById(R.id.etProjectNumber)
        etServerUrl = findViewById(R.id.etServerUrl)
        btnSaveSetup = findViewById(R.id.btnSaveSetup)

        etServerUrl.setText(BackendEndpointManager.getHttpBaseUrl() ?: "")
    }

    private fun setupLanguageSelector() {
        val languages = arrayOf(
            "English",
            "اردو (Urdu)",
            "हिन्दी (Hindi)",
            "العربية (Arabic)"
        )

        val languageCodes = arrayOf("en", "ur", "hi", "ar")

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        languageSpinner.adapter = adapter

        // Test TTS when language is selected
        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selectedLang = languageCodes[position]
                ttsHelper.speak("Language selected: ${languages[position]}", selectedLang, false)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSaveButton() {
        btnSaveSetup.setOnClickListener {
            val deviceName = etDeviceName.text.toString().trim()
            val supervisorPhone = etSupervisorPhone.text.toString().trim()
            val projectNumber = etProjectNumber.text.toString().trim()
            val rawServerUrl = etServerUrl.text.toString().trim()

            // Validate device name
            if (deviceName.isEmpty()) {
                Toast.makeText(this, "Please enter device name", Toast.LENGTH_SHORT).show()
                ttsHelper.speak("Please enter device name", "en", false)
                return@setOnClickListener
            }

            // Validate supervisor phone
            if (supervisorPhone.isEmpty()) {
                Toast.makeText(this, "Please enter supervisor phone", Toast.LENGTH_SHORT).show()
                ttsHelper.speak("Please enter supervisor phone number", "en", false)
                return@setOnClickListener
            }

            // Validate project number
            if (projectNumber.isEmpty()) {
                Toast.makeText(this, "Please enter project number", Toast.LENGTH_SHORT).show()
                ttsHelper.speak("Please enter project number", "en", false)
                return@setOnClickListener
            }

            // Validate server URL (optional override; if blank, automatic discovery is used)
            if (rawServerUrl.isNotEmpty()) {
                if (!BackendEndpointManager.updateEndpoint(rawServerUrl, applicationContext)) {
                    Toast.makeText(this, "Please enter a valid server URL or leave blank for auto-discovery", Toast.LENGTH_SHORT).show()
                    ttsHelper.speak("Please enter a valid server URL or leave blank for discovery", "en", false)
                    return@setOnClickListener
                }
            }

            val selectedIndex = languageSpinner.selectedItemPosition
            val languageCodes = arrayOf("en", "ur", "hi", "ar")
            val selectedLanguage = languageCodes[selectedIndex]

            // Save preferences
            val prefs = getSharedPreferences("watchmen_prefs", MODE_PRIVATE)
            prefs.edit().apply {
                putString("app_language", selectedLanguage)
                putString("device_name", deviceName)
                putString("supervisor_phone", supervisorPhone)
                putString("project_number", projectNumber)
                putBoolean("setup_complete", true)
                apply()
            }

            // Apply language
            setAppLocale(selectedLanguage)

            // Announce completion
            ttsHelper.speak("Setup complete. Starting application", selectedLanguage, false)

            Toast.makeText(this, "Setup complete!", Toast.LENGTH_SHORT).show()

            // Wait briefly for TTS to finish, then proceed
            android.os.Handler(mainLooper).postDelayed({
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }, 2000)
        }
    }


    private fun setAppLocale(languageCode: String) {
        val locale = when (languageCode) {
            "ur" -> Locale("ur", "PK")
            "hi" -> Locale("hi", "IN")
            "ar" -> Locale("ar", "SA")
            else -> Locale.US
        }

        Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        config.setLayoutDirection(locale)  // Important for RTL languages
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    override fun onDestroy() {
        ttsHelper.shutdown()
        super.onDestroy()
    }
}
