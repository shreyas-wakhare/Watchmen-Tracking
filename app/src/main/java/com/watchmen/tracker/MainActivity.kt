package com.watchmen.tracker

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.provider.Telephony
import android.telephony.SmsManager
import android.app.role.RoleManager
import android.app.Activity
import java.io.File
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import android.widget.ImageButton
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.location.Location
import android.os.Vibrator
import android.os.VibrationEffect
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import android.util.Log
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.watchmen.tracker.BuildConfig

class MainActivity : AppCompatActivity() {
    companion object {
        private const val PREF_GUIDE_ACK = "guide_acknowledged"
    }
    private lateinit var statusText: TextView
    private lateinit var btnCheckIn: Button
    private lateinit var btnScanCheckpoint: Button
    private lateinit var btnIncidentReport: Button
    private lateinit var btnPanic: Button
    private lateinit var btnSettings: ImageButton  // ✅ ADD
    private var movementDetector: MovementAuthenticityDetector? = null
    private lateinit var sensorManager: SensorManager
    private var movementMonitorHandler: Handler? = null
    private lateinit var btnAppGuide: Button

    private lateinit var ttsHelper: MultilingualTTS  // ✅ ADD
    private var currentLanguage: String = "en"  // ✅ ADD

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions.entries.all { it.value }

            val deniedPermissions = permissions.entries
                .filter { !it.value }
                .map { it.key.substringAfterLast(".") }
                .joinToString(", ")

            if (granted) {
                statusText.text = getString(R.string.permissions_granted_starting_service)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                        requestBackgroundLocationPermission()
                        return@registerForActivityResult
                    }
                }

                allPermissionsGranted()
            } else {
                statusText.text = getString(R.string.permissions_denied, deniedPermissions)

                AlertDialog.Builder(this)
                    .setTitle("⚠️ Permissions Required")
                    .setMessage(
                        "The following permissions were denied:\n\n" +
                                "$deniedPermissions\n\n" +
                                "All permissions are REQUIRED for the app to function.\n\n" +
                                "Please grant them in Settings."
                    )
                    .setPositiveButton("Open Settings") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            intent.data = Uri.parse("package:$packageName")
                            startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(this, "Please enable permissions manually in Settings", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("Try Again") { _, _ ->
                        checkAndRequestPermissions()
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    // 🔥 Receives state updates from TrackingService
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra("state") ?: return
            statusText.text = getString(R.string.state, state)
            findViewById<TextView?>(R.id.tvStatusBadge)?.text = state
            findViewById<TextView?>(R.id.tvMainServiceMode)?.text = state
        }
    }
    override fun onResume() {
        super.onResume()
        registerReceiver(
            stateReceiver,
            IntentFilter("TRACKING_STATE_UPDATE"),
            Context.RECEIVER_NOT_EXPORTED
        )
        registerReceiver(
            sentReceiver,
            IntentFilter("SMS_SENT"),
            RECEIVER_NOT_EXPORTED
        )
        registerReceiver(
            deliveredReceiver,
            IntentFilter("SMS_DELIVERED"),
            RECEIVER_NOT_EXPORTED
        )
        updateDashboardHeaderAndStatus()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(stateReceiver)
        unregisterReceiver(sentReceiver)
        unregisterReceiver(deliveredReceiver)
    }

    private val sentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (resultCode) {
                Activity.RESULT_OK -> Log.i("SMS", "📤 SMS SENT to carrier")
                SmsManager.RESULT_ERROR_GENERIC_FAILURE -> Log.e("SMS", "❌ Generic failure")
                SmsManager.RESULT_ERROR_NO_SERVICE -> Log.e("SMS", "❌ No service")
                SmsManager.RESULT_ERROR_NULL_PDU -> Log.e("SMS", "❌ Null PDU")
                SmsManager.RESULT_ERROR_RADIO_OFF -> Log.e("SMS", "❌ Radio off")
            }
        }
    }
    private fun requestDefaultSmsApp() {
        Log.i("SMS", "🔍 requestDefaultSmsApp() called")
        Log.i("SMS", "Android version: ${Build.VERSION.SDK_INT} (need 29+)")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "❌ Requires Android 10+", Toast.LENGTH_LONG).show()
            Log.e("SMS", "Android version too old: ${Build.VERSION.SDK_INT}")
            return
        }

        try {
            val roleManager = getSystemService(RoleManager::class.java)
            Log.i("SMS", "✅ RoleManager obtained")

            if (!roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                Toast.makeText(this, "❌ SMS role not available on this device", Toast.LENGTH_LONG).show()
                Log.e("SMS", "SMS role not available")
                return
            }

            Log.i("SMS", "✅ SMS role is available")

            if (roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                Toast.makeText(this, "✅ Already default SMS app!", Toast.LENGTH_SHORT).show()
                Log.i("SMS", "✅ App already holds SMS role")
                return
            }

            Log.i("SMS", "🚀 Requesting SMS role...")

            AlertDialog.Builder(this)
                .setTitle("🚨 Emergency SMS Setup")
                .setMessage(
                    "To send automatic panic alerts, this app needs to become your default SMS app.\n\n" +
                            "⚠️ WARNING:\n" +
                            "• Your current SMS app will stop working\n" +
                            "• All messages will come to this app\n" +
                            "• You can change back in Settings later\n\n" +
                            "Proceed?"
                )
                .setPositiveButton("Enable") { _, _ ->
                    try {
                        val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                        startActivityForResult(intent, 123)
                        Log.i("SMS", "✅ SMS role intent launched")
                    } catch (e: Exception) {
                        Toast.makeText(this, "❌ Failed: ${e.message}", Toast.LENGTH_LONG).show()
                        Log.e("SMS", "Failed to launch intent: ${e.message}", e)
                    }
                }
                .setNegativeButton("Cancel") { _, _ ->
                    Toast.makeText(this, "SMS role request cancelled", Toast.LENGTH_SHORT).show()
                }
                .show()

        } catch (e: Exception) {
            Toast.makeText(this, "❌ Error: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("SMS", "requestDefaultSmsApp error: ${e.message}", e)
        }
    }

    // ✅ ADD this to handle the result
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 123) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "✅ SMS role granted! Panic alerts will send automatically", Toast.LENGTH_LONG).show()
                Log.i("SMS", "✅ User granted SMS role")
            } else {
                Toast.makeText(this, "❌ SMS role denied. Panic will open SMS app instead", Toast.LENGTH_LONG).show()
                Log.w("SMS", "❌ User denied SMS role")
            }
        }
    }


    private val deliveredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i("SMS", "📩 Delivered to phone inbox")
        }
    }
    private val requestBackgroundLocation =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions.entries.all { it.value }
            if (granted) {
                allPermissionsGranted()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("⚠️ Background Location Required")
                    .setMessage(
                        "Background location permission is REQUIRED for 24/7 tracking.\n\n" +
                                "Please go to:\n" +
                                "Settings → Apps → Watchmen Tracker → Permissions → Location\n\n" +
                                "And select 'Allow all the time'"
                    )
                    .setPositiveButton("Open Settings") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            intent.data = Uri.parse("package:$packageName")
                            startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(this, "Please enable permissions in Settings", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        BackendEndpointManager.init(applicationContext)
        com.watchmen.tracker.auth.AuthManager.init(applicationContext)

        // ------------------------------------------------------------
        // 0️⃣ Enforce user authentication
        // ------------------------------------------------------------
        if (!com.watchmen.tracker.auth.AuthManager.isLoggedIn(this)) {
            startActivity(Intent(this, com.watchmen.tracker.auth.LoginActivity::class.java))
            finish()
            return
        }

        // ✅ Shared preferences (declare ONCE)
        val prefs = getSharedPreferences("watchmen_prefs", MODE_PRIVATE)

        // ------------------------------------------------------------
        // 1️⃣ Enforce initial setup
        // ------------------------------------------------------------
        val setupComplete = prefs.getBoolean("setup_complete", false)
        if (!setupComplete) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }


        // ------------------------------------------------------------
        // 2️⃣ Enforce guide acknowledgment
        // ------------------------------------------------------------
        if (!prefs.getBoolean(PREF_GUIDE_ACK, false)) {
            startActivity(
                Intent(this, AppGuideActivity::class.java).apply {
                    putExtra("force_ack", true)
                }
            )
            finish()
            return
        }

        // ------------------------------------------------------------
        // 3️⃣ Normal app startup
        // ------------------------------------------------------------
        setContentView(R.layout.activity_main)

        // ✅ Apply saved language BEFORE initializing views
        currentLanguage = getPreferredLanguage()
        applyLanguage(currentLanguage)

        // ✅ Initialize TTS
        initializeTTS()

        initializeViews()
        setupClickListeners()

        statusText.text = getString(R.string.checking_permissions)

        requestBatteryOptimizationExemption()
        checkAndRequestPermissions()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        movementDetector = MovementAuthenticityDetector(
            sensorManager,
            object : MovementAuthenticityDetector.MovementListener {
                override fun onSuspiciousMovement(
                    result: MovementAuthenticityDetector.AuthenticityResult
                ) {
                    runOnUiThread {
                        val msg = when (result.suspiciousActivity) {
                            "Phone shaking to fake steps" ->
                                getString(R.string.movement_fake_steps_shake)
                            "Phone on ground but reporting steps" ->
                                getString(R.string.movement_fake_steps_ground)
                            "Artificial vibration pattern" ->
                                getString(R.string.movement_fake_steps_vibration)
                            else ->
                                getString(R.string.liveness_hold_steady)
                        }

                        ttsHelper.speak(msg, currentLanguage, urgent = true)
                    }
                }
            }
        )

        movementDetector?.startMonitoring()
        startMovementPollingLoop()
    }

    private fun startMovementPollingLoop() {
        // Poll every 3 seconds
        val handler = Handler(Looper.getMainLooper())
        movementMonitorHandler = handler

        val runnable = object : Runnable {
            override fun run() {
                // TODO: replace with your real current step count source
                val currentSteps = getCurrentStepCountForAuth()
                movementDetector?.analyzeMovement(currentSteps)

                handler.postDelayed(this, 3000L)
            }
        }
        handler.postDelayed(runnable, 3000L)
    }

    // Stub – hook this into your existing step counter
    private fun getCurrentStepCountForAuth(): Int {
        // Example: if you store it in shared prefs or a singleton
        return 0
    }

    // ✅ ADD: Initialize TTS
    private fun initializeTTS() {
        ttsHelper = MultilingualTTS(this)
        ttsHelper.initialize(
            onSuccess = {
                Log.i("Watchmen", "✅ TTS ready")
            },
            onError = {
                Log.e("Watchmen", "❌ TTS failed to initialize")
            }
        )
    }

    // ✅ ADD: Get preferred language
    private fun getPreferredLanguage(): String {
        val prefs = getSharedPreferences("watchmen_prefs", MODE_PRIVATE)
        return prefs.getString("app_language", "en") ?: "en"
    }

    // ✅ ADD: Apply language
    private fun applyLanguage(languageCode: String) {
        val locale = when (languageCode) {
            "ur" -> Locale("ur", "PK")
            "hi" -> Locale("hi", "IN")
            "ar" -> Locale("ar", "SA")
            else -> Locale.US
        }

        Locale.setDefault(locale)

        val config = resources.configuration
        config.setLocale(locale)
        config.setLayoutDirection(locale)

        // ✅ CRITICAL: Update both display metrics and configuration
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)

        // ✅ Also update base context configuration
        baseContext.resources.updateConfiguration(config, baseContext.resources.displayMetrics)

        Log.i("Watchmen", "✅ Language applied: $languageCode")

    }


    private fun initializeViews() {
        statusText = findViewById(R.id.statusText)
        btnCheckIn = findViewById(R.id.btnCheckIn)
        btnScanCheckpoint = findViewById(R.id.btnScanCheckpoint)
        btnIncidentReport = findViewById(R.id.btnIncidentReport)
        btnPanic = findViewById(R.id.btnPanic)
        btnSettings = findViewById(R.id.btnSettings)  // ✅ ADD
        btnAppGuide = findViewById(R.id.btnAppGuide)   // ✅ ADD THIS

    }

    private fun setupClickListeners() {
        btnCheckIn.setOnClickListener {
            // Use string resource instead of hardcoded text
            ttsHelper.speak(getString(R.string.check_in_announcement), currentLanguage, false)

            val intent = Intent(this, CameraCaptureActivity::class.java)
            startActivity(intent)
        }
        btnAppGuide.setOnClickListener {
            startActivity(Intent(this, AppGuideActivity::class.java))
        }

        btnScanCheckpoint.setOnClickListener {
            Toast.makeText(this, "QR Scanner coming soon", Toast.LENGTH_SHORT).show()
        }

        btnIncidentReport.setOnClickListener {
            startActivity(
                Intent(this, IncidentReportActivity::class.java)
            )
        }

        btnPanic.setOnLongClickListener {
            showPanicConfirmation()
            true
        }

        btnPanic.setOnClickListener {
            Toast.makeText(this, "Hold button for 2 seconds to trigger panic", Toast.LENGTH_SHORT).show()
        }

        btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        findViewById<View?>(R.id.cardCheckIn)?.setOnClickListener { btnCheckIn.performClick() }
        findViewById<View?>(R.id.cardScanCheckpoint)?.setOnClickListener { btnScanCheckpoint.performClick() }
        findViewById<View?>(R.id.cardIncidentReport)?.setOnClickListener { btnIncidentReport.performClick() }
        findViewById<View?>(R.id.cardAppGuide)?.setOnClickListener { btnAppGuide.performClick() }
        findViewById<View?>(R.id.btnLogout)?.setOnClickListener { showLogoutConfirmation() }
        findViewById<View?>(R.id.userProfilePill)?.setOnClickListener { showSettingsDialog() }
    }

    private fun updateDashboardHeaderAndStatus() {
        try {
            val user = com.watchmen.tracker.auth.AuthManager.getUser(this)
            if (user != null && user.fullName.isNotBlank()) {
                findViewById<TextView?>(R.id.tvUserName)?.text = user.fullName
                val initials = user.fullName.split(" ")
                    .filter { it.isNotBlank() }
                    .take(2)
                    .map { it.first().uppercase() }
                    .joinToString("")
                if (initials.isNotEmpty()) {
                    findViewById<TextView?>(R.id.tvUserAvatar)?.text = initials
                }
            }

            val serverUrl = BackendEndpointManager.getHttpBaseUrl()
            val serverText = findViewById<TextView?>(R.id.tvMainServerStatus)
            if (serverText != null) {
                if (!serverUrl.isNullOrBlank()) {
                    val cleanUrl = serverUrl.removePrefix("http://").removePrefix("https://")
                    serverText.text = cleanUrl
                    serverText.setTextColor(getColor(R.color.watchmen_online))
                } else {
                    serverText.text = "Searching..."
                    serverText.setTextColor(getColor(R.color.watchmen_warning))
                }
            }
        } catch (e: Exception) {
            Log.e("Watchmen", "Error updating dashboard header: ${e.message}")
        }
    }

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Sign Out")
            .setMessage("Are you sure you want to sign out from Watchmen Tracker?")
            .setPositiveButton("Sign Out") { _, _ ->
                com.watchmen.tracker.auth.AuthManager.clearSession(this)
                val intent = Intent(this, com.watchmen.tracker.auth.LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Settings dialog
    private fun showSettingsDialog() {
        val options = arrayOf(
            getString(R.string.change_language),
            getString(R.string.device_info),
            getString(R.string.guide_button_label),
            getString(R.string.report_bug_title),
            getString(R.string.edit_setup),
            "Sign Out",
            getString(R.string.close)
        )

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showLanguageDialog()
                    1 -> showDeviceInfo()
                    2 -> startActivity(Intent(this, AppGuideActivity::class.java))
                    3 -> showBugReportDialog()
                    4 -> editSetup()
                    5 -> showLogoutConfirmation()
                    6 -> {}
                }
            }
            .show()
    }



    // ✅ ADD: Language selection dialog
    private fun showLanguageDialog() {
        val languages = arrayOf(
            "English",
            "اردو (Urdu)",
            "हिन्दी (Hindi)",
            "العربية (Arabic)"
        )

        val languageCodes = arrayOf("en", "ur", "hi", "ar")
        val currentIndex = languageCodes.indexOf(currentLanguage)

        AlertDialog.Builder(this)
            .setTitle("🌐 Select Language")
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                val selectedLanguage = languageCodes[which]

                if (selectedLanguage != currentLanguage) {
                    AlertDialog.Builder(this)
                        .setTitle("Change Language?")
                        .setMessage("App will refresh to apply:\n\n${languages[which]}")
                        .setPositiveButton("Confirm") { _, _ ->
                            changeLanguage(selectedLanguage)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }

                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ✅ ADD: Change language function
    private fun changeLanguage(newLanguage: String) {
        // Save the new language preference
        val prefs = getSharedPreferences("watchmen_prefs", MODE_PRIVATE)
        prefs.edit().putString("app_language", newLanguage).apply()

        // Update current language immediately
        currentLanguage = newLanguage

        // Apply language BEFORE announcing
        applyLanguage(newLanguage)

        // Force recreate resources with new locale
        val resources = resources
        val config = resources.configuration
        val locale = when (newLanguage) {
            "ur" -> Locale("ur", "PK")
            "hi" -> Locale("hi", "IN")
            "ar" -> Locale("ar", "SA")
            else -> Locale.US
        }

        Locale.setDefault(locale)
        config.setLocale(locale)
        config.setLayoutDirection(locale)

        // ✅ CRITICAL: Create new context with updated configuration
        createConfigurationContext(config)

        // Announce in NEW language (now resources are updated)
        val announcement = when (newLanguage) {
            "ur" -> "زبان تبدیل ہو گئی۔ ایپلیکیشن دوبارہ شروع ہو رہی ہے"
            "hi" -> "भाषा बदल गई। एप्लिकेशन पुनः आरंभ हो रहा है"
            "ar" -> "تم تغيير اللغة. إعادة تشغيل التطبيق"
            else -> "Language changed. Restarting application"
        }
        ttsHelper.speak(announcement, newLanguage, false)

        Toast.makeText(this, "Language changed. Refreshing...", Toast.LENGTH_SHORT).show()

        // ✅ IMPORTANT: Longer delay to allow TTS to finish
        android.os.Handler(mainLooper).postDelayed({
            // Recreate the activity (cleaner than restart)
            recreate()
        }, 2000)
    }


    // ✅ ADD: Device info dialog
    private fun showDeviceInfo() {
        val prefs = getSharedPreferences("watchmen_prefs", MODE_PRIVATE)
        val deviceName = prefs.getString("device_name", "Unknown")
        val projectNumber = prefs.getString("project_number", "Unknown")
        val supervisorPhone = prefs.getString("supervisor_phone", "Unknown")
        val deviceId = prefs.getString("device_id", "Unknown")
        val language = when (currentLanguage) {
            "ur" -> "اردو (Urdu)"
            "hi" -> "हिन्दी (Hindi)"
            "ar" -> "العربية (Arabic)"
            else -> "English"
        }

        val info = """
            📱 Device Name: $deviceName
            🏗️ Project Number: $projectNumber
            📞 Supervisor: $supervisorPhone
            🌐 Language: $language
            🆔 Device ID: ${deviceId?.take(20)}...
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("📱 Device Information")
            .setMessage(info)
            .setPositiveButton("OK", null)
            .show()
    }




    // ✅ ADD: Edit setup function
    private fun editSetup() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Edit Setup")
            .setMessage("This will allow you to change device name, project number, supervisor phone, and language.\n\nProceed?")
            .setPositiveButton("Yes") { _, _ ->
                val intent = Intent(this, SetupActivity::class.java)
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }

        val packageName = packageName
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager

        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Log.i("Watchmen", "✅ Battery optimization already disabled")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("⚡ CRITICAL: Battery Optimization")
            .setMessage(
                "🚨 WARNING: Without disabling battery optimization, tracking WILL STOP after 10-15 minutes!\n\n" +
                        "This is REQUIRED for:\n" +
                        "✓ 24/7 continuous GPS tracking\n" +
                        "✓ Real-time location updates\n" +
                        "✓ Emergency panic alerts\n" +
                        "✓ Hourly check-ins\n\n" +
                        "The app uses minimal battery power but Android will kill it in Doze mode unless you allow it."
            )
            .setPositiveButton("ALLOW (Required)") { _, _ ->
                try {
                    val intent = Intent().apply {
                        action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)

                    Toast.makeText(
                        this,
                        "⚠️ You MUST tap 'Allow' on the next screen for tracking to work!",
                        Toast.LENGTH_LONG
                    ).show()

                } catch (e: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(intent)
                        Toast.makeText(
                            this,
                            "⚠️ Find 'Watchmen Tracker' and select 'Don't optimize'",
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (e2: Exception) {
                        Toast.makeText(
                            this,
                            "❌ Please go to: Settings → Apps → Watchmen Tracker → Battery → Unrestricted",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton("Skip (NOT RECOMMENDED)") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("⚠️ WARNING")
                    .setMessage(
                        "Tracking will NOT work reliably!\n\n" +
                                "Your location will STOP updating after 10-15 minutes.\n\n" +
                                "Are you SURE you want to skip?"
                    )
                    .setPositiveButton("Go Back") { _, _ ->
                        requestBatteryOptimizationExemption()
                    }
                    .setNegativeButton("Skip Anyway") { _, _ ->
                        Toast.makeText(
                            this,
                            "⚠️ Tracking will be UNRELIABLE!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    .setCancelable(false)
                    .show()
            }
            .setCancelable(false)
            .show()
    }

    // ... Keep all your existing incident reporting functions ...
    private fun showIncidentTypeDialog() {
        val incidentTypes = arrayOf(
            "Suspicious Activity",
            "Equipment Failure",
            "Safety Hazard",
            "Unauthorized Person",
            "Vandalism",
            "Fire/Smoke",
            "Medical Emergency",
            "Other"
        )

        AlertDialog.Builder(this)
            .setTitle("📝 Report Incident")
            .setItems(incidentTypes) { _, which ->
                val selectedType = incidentTypes[which]
                showIncidentDescriptionDialog(selectedType)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    private fun showBugReportDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.bug_description_hint)
            minLines = 3
            maxLines = 6
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.report_bug_title))
            .setMessage(getString(R.string.report_bug_message))
            .setView(input)
            .setPositiveButton(getString(R.string.submit)) { _, _ ->
                val description = input.text.toString().trim()
                if (description.isEmpty()) {
                    Toast.makeText(
                        this,
                        getString(R.string.bug_empty),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    sendBugReport(description)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    private fun sendBugReport(description: String) {

        val payloadJson = JSONObject().apply {
            put("deviceid", getTrackerDeviceId())
            put("title", "User Reported Bug")
            put("description", description)
            put("severity", "MEDIUM")
            put("app_version", BuildConfig.VERSION_NAME)
            put("os_version", "Android ${Build.VERSION.RELEASE}")
            put("device_model", Build.MODEL)
            put("logs", statusText.text.toString())
        }

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("payload", payloadJson.toString())
            // later you can add screenshot here
            .build()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url(BackendEndpointManager.getHttpUrl("/bug-report"))
                    .post(multipart)
                    .build()

                OkHttpClient().newCall(request).execute().use { response ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            if (response.isSuccessful)
                                getString(R.string.bug_sent)
                            else
                                getString(R.string.bug_failed),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.bug_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }


    private fun showIncidentDescriptionDialog(type: String) {
        val input = EditText(this).apply {
            hint = "Describe the incident in detail..."
            minLines = 3
            maxLines = 6
            setPadding(40, 20, 40, 20)
        }

        AlertDialog.Builder(this)
            .setTitle("Incident Details")
            .setMessage("Type: $type\n\nProvide more information:")
            .setView(input)
            .setPositiveButton("Submit") { _, _ ->
                val description = input.text.toString().trim()
                if (description.isEmpty()) {
                    Toast.makeText(this, "⚠️ Description cannot be empty", Toast.LENGTH_SHORT).show()
                } else {
                    sendIncidentReport(type, description)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendIncidentReport(type: String, description: String) {
        Toast.makeText(this, "📤 Sending incident report...", Toast.LENGTH_SHORT).show()

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    submitIncidentToServer(type, description, location)
                } else {
                    Toast.makeText(this, "⚠️ No location available", Toast.LENGTH_SHORT).show()
                }
            }.addOnFailureListener { e ->
                Log.e("Watchmen", "Location error: ${e.message}")
                Toast.makeText(this, "❌ Failed to get location", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "❌ Location permission required", Toast.LENGTH_SHORT).show()
        }
    }

    private fun submitIncidentToServer(type: String, description: String, location: Location) {
        val json = JSONObject().apply {
            put("device_id", getTrackerDeviceId())
            put("incident_type", type)
            put("description", description)
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("accuracy", location.accuracy.toDouble())
            put("has_photo", false)
            put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date()))
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(BackendEndpointManager.getHttpUrl("/incident"))
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    withContext(Dispatchers.Main) {
                        if (response.isSuccessful) {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("✅ Incident Reported")
                                .setMessage("Your incident report has been submitted to the control center.\n\nType: $type\nLocation: ${location.latitude}, ${location.longitude}")
                                .setPositiveButton("OK", null)
                                .show()
                        } else {
                            Toast.makeText(this@MainActivity, "❌ Server error: ${response.code}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e("Watchmen", "Incident report failed: ${e.message}")
                    Toast.makeText(this@MainActivity, "❌ Network error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ============================================================
    // 📸 HOURLY PHOTO SCHEDULER
    // ============================================================

    private fun scheduleHourlyPhotos() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                AlertDialog.Builder(this)
                    .setTitle("📸 Hourly Check-In Setup")
                    .setMessage("Allow exact alarms for hourly photo reminders?")
                    .setPositiveButton("Allow") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(this, "Please enable alarms in settings", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("Skip", null)
                    .show()
                return
            }
        }

        val intent = Intent(this, PhotoCaptureReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            1001,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val firstTrigger = System.currentTimeMillis() + (5 * 60 * 1000)

        try {
            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                firstTrigger,
                AlarmManager.INTERVAL_HOUR,
                pendingIntent
            )
            Toast.makeText(this, "✅ Hourly photos scheduled", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to schedule hourly photos", Toast.LENGTH_SHORT).show()
        }
    }

    // ============================================================
    // 🚨 PANIC BUTTON
    // ============================================================
    private fun showPanicConfirmation() {
        // ✅ ADD: Voice warning
        ttsHelper.speak(getString(R.string.panic_warning), currentLanguage, urgent = true)
        AlertDialog.Builder(this)
            .setTitle("🚨 TRIGGER PANIC ALERT?")
            .setMessage("This will:\n• Send emergency alert to control center\n• Share your current location\n• Notify supervisor via SMS")
            .setPositiveButton("SEND ALERT") { _, _ ->
                triggerPanicAlert()
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(true)
            .show()
    }

    private fun triggerPanicAlert() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(500)
        }

        val fusedClient = LocationServices.getFusedLocationProviderClient(this)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {

            fusedClient.lastLocation.addOnSuccessListener { location ->
                sendPanicAlert(location)

                // ✅ ADD: Voice confirmation
                ttsHelper.speak("Emergency alert has been sent to your supervisor", currentLanguage, urgent = true)

                AlertDialog.Builder(this)
                    .setTitle("✅ PANIC ALERT SENT")
                    .setMessage("Emergency alert transmitted.\nLocation: ${location?.latitude}, ${location?.longitude}")
                    .setPositiveButton("OK", null)
                    .setCancelable(false)
                    .show()
            }.addOnFailureListener {
                sendPanicAlert(null)
                Toast.makeText(this, "⚠️ Panic sent without location", Toast.LENGTH_LONG).show()
            }
        } else {
            sendPanicAlert(null)
        }
    }

    private fun sendPanicAlert(location: Location?) {

        val alertId = UUID.randomUUID().toString()
        val localTimestamp = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date())

        val utcTimestamp = SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            Locale.US
        ).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val json = JSONObject().apply {
            put("alert_id", alertId)
            put("status", "PENDING")          // 👈 critical
            put("created_at", localTimestamp)
            put("timestamp", utcTimestamp)

            put("device_id", getTrackerDeviceId())
            put("alert_type", "PANIC")

            put("latitude", location?.latitude ?: 0.0)
            put("longitude", location?.longitude ?: 0.0)
            put("accuracy", location?.accuracy ?: 0f)
            put("battery", getBatteryLevel())
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val body = json.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(BackendEndpointManager.getHttpUrl("/alert"))
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        json.put("status", "SENT")
                    } else {
                        saveOfflineAlert(json)
                    }
                }
            } catch (e: Exception) {
                saveOfflineAlert(json)
            }
        }

        location?.let { sendEmergencySMS(it) }
    }


    private fun sendEmergencySMS(location: Location?) {
        val prefs = getSharedPreferences("watchmen_prefs", MODE_PRIVATE)
        val supervisorPhone = prefs.getString("supervisor_phone", null)

        if (supervisorPhone.isNullOrBlank()) {
            Log.e("SMS", "❌ No supervisor phone configured")
            Toast.makeText(this, "No supervisor phone configured", Toast.LENGTH_SHORT).show()
            return
        }

        val lat = location?.latitude ?: 0.0
        val lng = location?.longitude ?: 0.0

        val message = """
🚨 WATCHMAN PANIC ALERT
Location: $lat, $lng
Map: https://maps.google.com/?q=$lat,$lng
Time: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}
    """.trimIndent()

        try {
            // ✅ Opens default SMS app with message pre-filled
            val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$supervisorPhone")
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            startActivity(smsIntent)
            Log.i("SMS", "✅ SMS app opened with panic message")

            // ✅ Show clear instruction
            Toast.makeText(
                this,
                "📱 TAP SEND BUTTON to alert supervisor!",
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {
            Log.e("SMS", "❌ Failed to open SMS app: ${e.message}")
            Toast.makeText(this, "Error opening SMS: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }








    private fun checkAndRequestPermissions() {
        val foregroundPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.CAMERA,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            foregroundPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            foregroundPermissions.add(Manifest.permission.BODY_SENSORS)
        }

        val notGranted = foregroundPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                    requestBackgroundLocationPermission()
                } else {
                    allPermissionsGranted()
                }
            } else {
                allPermissionsGranted()
            }
        } else {
            val missingPerms = notGranted.joinToString(", ") {
                it.substringAfterLast(".")
            }
            Log.w("Watchmen", "Missing permissions: $missingPerms")

            requestPermissions.launch(foregroundPermissions.toTypedArray())
        }
    }

    private fun requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AlertDialog.Builder(this)
                .setTitle("📍 Background Location Required")
                .setMessage(
                    "For continuous 24/7 tracking, this app needs to access your location even when the app is closed or not in use.\n\n" +
                            "On the next screen, please select:\n" +
                            "✓ 'Allow all the time'\n\n" +
                            "This is REQUIRED for tracking to work."
                )
                .setPositiveButton("Continue") { _, _ ->
                    requestBackgroundLocation.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                }
                .setNegativeButton("Skip (Not Recommended)") { _, _ ->
                    AlertDialog.Builder(this)
                        .setTitle("⚠️ WARNING")
                        .setMessage(
                            "Without background location, tracking will STOP when you close the app.\n\n" +
                                    "Go to Settings to enable it later."
                        )
                        .setPositiveButton("Open Settings") { _, _ ->
                            try {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                intent.data = Uri.parse("package:$packageName")
                                startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(this, "Please enable manually", Toast.LENGTH_LONG).show()
                            }
                        }
                        .setNegativeButton("Skip Anyway") { _, _ ->
                            Toast.makeText(this, "⚠️ Tracking will be limited!", Toast.LENGTH_LONG).show()
                        }
                        .show()
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun allPermissionsGranted() {
        statusText.text = "Permissions Verified · Initializing Service"

        // Voice confirmation
        ttsHelper.speak(getString(R.string.all_permissions_granted), currentLanguage, false)

        startTrackingService()
        scheduleHourlyPhotos()
        retryOfflineAlerts()
    }

    private fun startTrackingService() {
        if (!com.watchmen.tracker.auth.AuthManager.isLoggedIn(this)) {
            Log.w("Watchmen", "Cannot start service - user is not authenticated")
            return
        }

        val hasLocationPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasLocationPermission) {
            statusText.text = getString(R.string.waiting_for_permissions)
            Log.w("Watchmen", "Cannot start service - missing location permission")
            return
        }

        try {
            val intent = Intent(this, TrackingService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            statusText.text = getString(R.string.tracking_active_service_will_not_stop)
            findViewById<TextView?>(R.id.tvStatusBadge)?.text = "ACTIVE"
            findViewById<View?>(R.id.statusIndicatorDot)?.setBackgroundResource(R.drawable.bg_status_dot_online)
            Log.i("Watchmen", "TrackingService started successfully")

            btnCheckIn.isEnabled = true
            btnScanCheckpoint.isEnabled = true
            btnIncidentReport.isEnabled = true
            btnPanic.isEnabled = true

        } catch (e: Exception) {
            Log.e("Watchmen", "Failed to start service: ${e.message}", e)
            statusText.text = getString(R.string.service_failed_to_start)
            findViewById<TextView?>(R.id.tvStatusBadge)?.text = "ERROR"
            findViewById<View?>(R.id.statusIndicatorDot)?.setBackgroundResource(R.drawable.bg_status_dot_critical)
            Toast.makeText(this, "Tracking service error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getTrackerDeviceId(): String {
        val prefs = getSharedPreferences("watchmen_prefs", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)

        if (deviceId == null) {
            deviceId = "DEVICE_${Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)}_${(10000..99999).random()}"
            prefs.edit().putString("device_id", deviceId).apply()
            Log.i("Watchmen", "Generated new device ID: $deviceId")
        }

        return deviceId
    }

    private fun getBatteryLevel(): Float {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        return batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()
    }

    private fun saveOfflineAlert(json: JSONObject) {
        try {
            val alertId = json.optString("alert_id", UUID.randomUUID().toString())
            val fileName = "panic_$alertId.json"

            val file = File(filesDir, fileName)
            file.writeText(json.toString())

            // Optional lightweight index (recommended)
            val prefs = getSharedPreferences("watchmen_prefs", MODE_PRIVATE)
            val pending = prefs.getStringSet("pending_alerts", mutableSetOf()) ?: mutableSetOf()
            pending.add(fileName)
            prefs.edit().putStringSet("pending_alerts", pending).apply()

            Log.i("Watchmen", "📦 Saved offline panic alert: $fileName")

        } catch (e: Exception) {
            Log.e("Watchmen", "❌ Failed to save offline alert: ${e.message}")
        }
    }
    private fun retryOfflineAlerts() {
        CoroutineScope(Dispatchers.IO).launch {
            val files = filesDir.listFiles { file ->
                file.name.startsWith("panic_") && file.name.endsWith(".json")
            } ?: return@launch

            if (files.isEmpty()) return@launch

            val client = OkHttpClient()

            for (file in files) {
                try {
                    val json = JSONObject(file.readText())

                    if (json.optString("status") != "PENDING") {
                        file.delete()
                        continue
                    }

                    val body = json.toString()
                        .toRequestBody("application/json".toMediaType())

                    val request = Request.Builder()
                        .url(BackendEndpointManager.getHttpUrl("/alert"))
                        .post(body)
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            Log.i("Watchmen", "✅ Replayed panic alert ${file.name}")
                            file.delete()
                            removeFromPendingIndex(file.name)
                        } else {
                            Log.w("Watchmen", "⚠️ Replay failed (${response.code}) for ${file.name}")
                        }
                    }

                } catch (e: Exception) {
                    Log.e("Watchmen", "❌ Replay error for ${file.name}: ${e.message}")
                }
            }
        }
    }

    private fun removeFromPendingIndex(fileName: String) {
        val prefs = getSharedPreferences("watchmen_prefs", MODE_PRIVATE)
        val pending = prefs.getStringSet("pending_alerts", mutableSetOf()) ?: return
        pending.remove(fileName)
        prefs.edit().putStringSet("pending_alerts", pending).apply()
    }


    // ✅ ADD: Cleanup TTS
    override fun onDestroy() {
        super.onDestroy()

        movementMonitorHandler?.removeCallbacksAndMessages(null)
        movementDetector?.stopMonitoring()
        if (::ttsHelper.isInitialized) {
            ttsHelper.shutdown()
        }
    }



}
