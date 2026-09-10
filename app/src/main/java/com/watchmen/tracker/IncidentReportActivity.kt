package com.watchmen.tracker


import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import com.google.android.gms.location.LocationServices
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class IncidentReportActivity : AppCompatActivity() {

    private lateinit var incidentTypeSpinner: Spinner
    private lateinit var descriptionEditText: TextInputEditText
    private lateinit var photoImageView: ImageView
    private lateinit var btnCapturePhoto: Button
    private lateinit var btnSubmitReport: Button
    private lateinit var progressBar: ProgressBar

    private var capturedPhotoUri: Uri? = null
    private var capturedPhotoPath: String? = null

    companion object {
        private const val REQUEST_IMAGE_CAPTURE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_incident_report)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Incident Report"

        initializeViews()
        setupIncidentTypes()
        setupClickListeners()
    }

    private fun initializeViews() {
        incidentTypeSpinner = findViewById(R.id.incidentTypeSpinner)
        descriptionEditText = findViewById(R.id.descriptionEditText)
        photoImageView = findViewById(R.id.photoImageView)
        btnCapturePhoto = findViewById(R.id.btnCapturePhoto)
        btnSubmitReport = findViewById(R.id.btnSubmitReport)
        progressBar = findViewById(R.id.progressBar)
    }

    private fun setupIncidentTypes() {
        val types = resources.getStringArray(R.array.incident_types)

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            types
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        incidentTypeSpinner.adapter = adapter

    }

    private fun setupClickListeners() {
        btnCapturePhoto.setOnClickListener {
            if (checkCameraPermission()) {
                capturePhoto()
            } else {
                requestCameraPermission()
            }
        }

        btnSubmitReport.setOnClickListener {
            submitIncidentReport()
        }
    }

    private fun checkCameraPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            100
        )
    }

    private fun capturePhoto() {
        try {
            val photoFile = createImageFile()
            capturedPhotoPath = photoFile.absolutePath

            capturedPhotoUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                photoFile
            )

            val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, capturedPhotoUri)
            startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE)

        } catch (e: Exception) {
            Toast.makeText(this, "Failed to capture photo: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = getExternalFilesDir("incidents")
        storageDir?.mkdirs()
        return File.createTempFile("INCIDENT_${timeStamp}_", ".jpg", storageDir)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == RESULT_OK) {
            capturedPhotoUri?.let {
                photoImageView.setImageURI(it)
                photoImageView.visibility = ImageView.VISIBLE
                Toast.makeText(this, "✅ Photo captured", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun submitIncidentReport() {
        val type = incidentTypeSpinner.selectedItem.toString()
        val description = descriptionEditText.text.toString().trim()

        if (description.isBlank()) {
            descriptionEditText.error = getString(R.string.incident_description_required)
            Toast.makeText(
                this,
                getString(R.string.incident_description_required),
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        progressBar.visibility = ProgressBar.VISIBLE
        btnSubmitReport.isEnabled = false

        // Get current location
        val fusedClient = LocationServices.getFusedLocationProviderClient(this)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) {

            fusedClient.lastLocation.addOnSuccessListener { location ->
                saveAndSubmitReport(type, description, location)
            }.addOnFailureListener {
                saveAndSubmitReport(type, description, null)
            }
        } else {
            saveAndSubmitReport(type, description, null)
        }
    }

    private fun saveAndSubmitReport(type: String, description: String, location: Location?) {
        val json = JSONObject().apply {
            put("device_id", getTrackerDeviceId())
            put("incident_type", type)
            put("description", description)
            put("latitude", location?.latitude ?: 0.0)
            put("longitude", location?.longitude ?: 0.0)
            put("accuracy", location?.accuracy ?: 0f)
            put("has_photo", capturedPhotoPath != null)
            put("photo_path", capturedPhotoPath ?: "")
            put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date()))
        }

        // Save locally first (offline support)
        try {
            val file = File(filesDir, "incident_${System.currentTimeMillis()}.json")
            file.writeText(json.toString())
        } catch (e: Exception) {}

        // Try to send online
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
                        progressBar.visibility = ProgressBar.GONE

                        if (response.isSuccessful) {
                            Toast.makeText(this@IncidentReportActivity, "✅ Incident reported successfully", Toast.LENGTH_LONG).show()
                            finish()
                        } else {
                            Toast.makeText(this@IncidentReportActivity, "⚠️ Saved offline, will sync later", Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = ProgressBar.GONE
                    Toast.makeText(this@IncidentReportActivity, "📦 Saved offline, will sync when online", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun getTrackerDeviceId(): String {
        val prefs = getSharedPreferences("watchmen_prefs", Context.MODE_PRIVATE)
        return prefs.getString("device_token", "UNKNOWN") ?: "UNKNOWN"
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
