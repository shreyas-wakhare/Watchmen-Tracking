package com.watchmen.tracker

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.watchmen.tracker.attendance.AttendanceApiClient
import com.watchmen.tracker.attendance.AttendanceManager
import com.watchmen.tracker.attendance.AttendanceResult
import com.watchmen.tracker.attendance.ScheduleRequest
import com.watchmen.tracker.auth.AuthManager
import kotlinx.coroutines.launch
import java.util.*

class SetupActivity : AppCompatActivity() {

    private lateinit var languageSpinner: Spinner
    private lateinit var etDeviceName: EditText
    private lateinit var etSupervisorPhone: EditText
    private lateinit var etProjectNumber: EditText
    private lateinit var etServerUrl: EditText
    private lateinit var btnSaveSetup: Button
    private lateinit var ttsHelper: MultilingualTTS

    // Shift Schedule Views
    private lateinit var etShiftName: EditText
    private lateinit var btnSelectStartTime: LinearLayout
    private lateinit var tvStartTime: TextView
    private lateinit var btnSelectEndTime: LinearLayout
    private lateinit var tvEndTime: TextView
    private lateinit var etTimezone: EditText
    private lateinit var tvDaysSummary: TextView

    private var selectedStartTime = "07:00:00"
    private var selectedEndTime = "19:00:00"
    private var selectedDays = "1,2,3,4,5,6,7"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        AuthManager.init(applicationContext)
        BackendEndpointManager.init(applicationContext)
        AttendanceManager.init(applicationContext)

        // Initialize TTS
        ttsHelper = MultilingualTTS(this)
        ttsHelper.initialize()

        initializeViews()
        setupLanguageSelector()
        setupScheduleControls()
        loadExistingSchedule()
        setupSaveButton()
    }

    private fun initializeViews() {
        languageSpinner = findViewById(R.id.languageSpinner)
        etDeviceName = findViewById(R.id.etDeviceName)
        etSupervisorPhone = findViewById(R.id.etSupervisorPhone)
        etProjectNumber = findViewById(R.id.etProjectNumber)
        etServerUrl = findViewById(R.id.etServerUrl)
        btnSaveSetup = findViewById(R.id.btnSaveSetup)

        // Shift Schedule Views
        etShiftName = findViewById(R.id.etShiftName)
        btnSelectStartTime = findViewById(R.id.btnSelectStartTime)
        tvStartTime = findViewById(R.id.tvStartTime)
        btnSelectEndTime = findViewById(R.id.btnSelectEndTime)
        tvEndTime = findViewById(R.id.tvEndTime)
        etTimezone = findViewById(R.id.etTimezone)
        tvDaysSummary = findViewById(R.id.tvDaysSummary)

        etServerUrl.setText(BackendEndpointManager.getHttpBaseUrl() ?: "")
    }

    private fun setupScheduleControls() {
        tvStartTime.text = AttendanceManager.formatWallClock12h(selectedStartTime)
        tvEndTime.text = AttendanceManager.formatWallClock12h(selectedEndTime)

        btnSelectStartTime.setOnClickListener {
            val parts = selectedStartTime.split(":")
            val initialH = parts.getOrNull(0)?.toIntOrNull() ?: 7
            val initialM = parts.getOrNull(1)?.toIntOrNull() ?: 0

            TimePickerDialog(this, { _, hourOfDay, minute ->
                selectedStartTime = String.format(Locale.US, "%02d:%02d:00", hourOfDay, minute)
                tvStartTime.text = AttendanceManager.formatWallClock12h(selectedStartTime)
            }, initialH, initialM, false).show()
        }

        btnSelectEndTime.setOnClickListener {
            val parts = selectedEndTime.split(":")
            val initialH = parts.getOrNull(0)?.toIntOrNull() ?: 19
            val initialM = parts.getOrNull(1)?.toIntOrNull() ?: 0

            TimePickerDialog(this, { _, hourOfDay, minute ->
                selectedEndTime = String.format(Locale.US, "%02d:%02d:00", hourOfDay, minute)
                tvEndTime.text = AttendanceManager.formatWallClock12h(selectedEndTime)
            }, initialH, initialM, false).show()
        }
    }

    private fun loadExistingSchedule() {
        if (!AuthManager.isLoggedIn(this)) return

        lifecycleScope.launch {
            when (val result = AttendanceApiClient.getSchedule()) {
                is AttendanceResult.Success -> {
                    val s = result.data
                    etShiftName.setText(s.shiftName)
                    selectedStartTime = s.startTime
                    selectedEndTime = s.endTime
                    selectedDays = s.daysOfWeek
                    tvStartTime.text = AttendanceManager.formatWallClock12h(selectedStartTime)
                    tvEndTime.text = AttendanceManager.formatWallClock12h(selectedEndTime)
                    etTimezone.setText(s.timezone)
                }
                else -> {
                    // Default values remain
                }
            }
        }
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

            val shiftName = etShiftName.text.toString().trim()
            val timezone = etTimezone.text.toString().trim()

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

            // Validate shift name
            if (shiftName.isEmpty()) {
                Toast.makeText(this, "Please enter shift name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Validate timezone
            if (timezone.isEmpty()) {
                Toast.makeText(this, "Please enter site timezone", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Validate identical start/end times
            if (selectedStartTime == selectedEndTime) {
                Toast.makeText(this, "Shift start time and end time cannot be identical", Toast.LENGTH_SHORT).show()
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

            // If user is authenticated, persist shift schedule to backend
            if (AuthManager.isLoggedIn(this)) {
                btnSaveSetup.isEnabled = false
                lifecycleScope.launch {
                    val scheduleReq = ScheduleRequest(
                        shiftName = shiftName,
                        startTime = selectedStartTime,
                        endTime = selectedEndTime,
                        timezone = timezone,
                        daysOfWeek = selectedDays
                    )
                    val result = AttendanceApiClient.updateSchedule(scheduleReq)
                    btnSaveSetup.isEnabled = true

                    when (result) {
                        is AttendanceResult.Success -> {
                            persistLocalSetupAndFinish(deviceName, supervisorPhone, projectNumber)
                        }
                        is AttendanceResult.Error -> {
                            Toast.makeText(
                                this@SetupActivity,
                                "Failed to save schedule: ${result.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            } else {
                persistLocalSetupAndFinish(deviceName, supervisorPhone, projectNumber)
            }
        }
    }

    private fun persistLocalSetupAndFinish(
        deviceName: String,
        supervisorPhone: String,
        projectNumber: String
    ) {
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
        }, 1500)
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
