package com.watchmen.tracker.auth

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.watchmen.tracker.BackendEndpointManager
import com.watchmen.tracker.MainActivity
import com.watchmen.tracker.R
import com.watchmen.tracker.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PREFILL_EMAIL = "extra_prefill_email"
    }

    private lateinit var binding: ActivityLoginBinding

    private val endpointChangeListener = object : BackendEndpointManager.EndpointChangeListener {
        override fun onEndpointChanged(newHttpUrl: String, newWsUrl: String) {
            runOnUiThread {
                updateServerStatusUI(BackendEndpointManager.getDiscoveryState(), newHttpUrl)
            }
        }

        override fun onDiscoveryStateChanged(
            state: BackendEndpointManager.DiscoveryState,
            currentUrl: String?
        ) {
            runOnUiThread {
                updateServerStatusUI(state, currentUrl)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AuthManager.init(this)
        BackendEndpointManager.init(this)

        // Handle prefilled email from SignupActivity if redirected
        intent?.getStringExtra(EXTRA_PREFILL_EMAIL)?.let { email ->
            binding.etEmail.setText(email)
            binding.etPassword.requestFocus()
        }

        binding.btnLogin.setOnClickListener {
            attemptLogin()
        }

        binding.tvGoToSignup.setOnClickListener {
            val signupIntent = Intent(this, SignupActivity::class.java)
            startActivity(signupIntent)
        }

        binding.ibServerSettings.setOnClickListener {
            showServerSettingsDialog()
        }

        updateServerStatusUI(
            BackendEndpointManager.getDiscoveryState(),
            BackendEndpointManager.getHttpBaseUrl()
        )
    }

    override fun onResume() {
        super.onResume()
        BackendEndpointManager.addListener(endpointChangeListener)
        updateServerStatusUI(
            BackendEndpointManager.getDiscoveryState(),
            BackendEndpointManager.getHttpBaseUrl()
        )
    }

    override fun onPause() {
        super.onPause()
        BackendEndpointManager.removeListener(endpointChangeListener)
    }

    private fun updateServerStatusUI(
        state: BackendEndpointManager.DiscoveryState,
        currentUrl: String?
    ) {
        when {
            !currentUrl.isNullOrBlank() -> {
                val displayUrl = currentUrl.removePrefix("http://").removePrefix("https://")
                binding.tvServerStatus.text = "● Connected: $displayUrl"
                binding.tvServerStatus.setTextColor(getColor(R.color.watchmen_online))
            }
            state == BackendEndpointManager.DiscoveryState.DISCOVERING -> {
                binding.tvServerStatus.text = "○ Searching for server..."
                binding.tvServerStatus.setTextColor(getColor(R.color.watchmen_text_secondary))
            }
            else -> {
                binding.tvServerStatus.text = "● Server Not Found · Configure Server"
                binding.tvServerStatus.setTextColor(getColor(R.color.watchmen_warning))
            }
        }
    }

    private fun attemptLogin() {
        hideError()

        val email = binding.etEmail.text?.toString()?.trim() ?: ""
        val password = binding.etPassword.text?.toString() ?: ""

        if (email.isEmpty()) {
            showError(getString(R.string.err_empty_email))
            binding.etEmail.requestFocus()
            return
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showError(getString(R.string.err_invalid_email))
            binding.etEmail.requestFocus()
            return
        }

        if (password.isEmpty()) {
            showError(getString(R.string.err_empty_password))
            binding.etPassword.requestFocus()
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            val result = AuthApiClient.login(email, password)
            setLoading(false)

            when (result) {
                is AuthResult.Success -> {
                    AuthManager.saveSession(result.data, this@LoginActivity)
                    Toast.makeText(
                        this@LoginActivity,
                        "Welcome back, ${result.data.user.fullName}!",
                        Toast.LENGTH_SHORT
                    ).show()

                    val intent = Intent(this@LoginActivity, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }
                is AuthResult.Error -> {
                    showError(result.message)
                }
            }
        }
    }

    private fun showServerSettingsDialog() {
        val currentUrl = BackendEndpointManager.getHttpBaseUrl() ?: ""
        val input = EditText(this).apply {
            hint = "http://192.168.1.53:8000"
            setText(currentUrl)
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Server Configuration")
            .setMessage("Enter the FastAPI backend HTTP base URL:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newUrl = input.text.toString().trim()
                if (newUrl.isNotEmpty()) {
                    if (BackendEndpointManager.updateEndpoint(newUrl, this)) {
                        Toast.makeText(this, "Backend URL updated successfully!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Invalid server URL format", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setLoading(isLoading: Boolean) {
        binding.btnLogin.isEnabled = !isLoading
        binding.etEmail.isEnabled = !isLoading
        binding.etPassword.isEnabled = !isLoading
        binding.tvGoToSignup.isEnabled = !isLoading
        binding.ibServerSettings.isEnabled = !isLoading
        binding.pbLoginLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        binding.tvLoginError.text = message
        binding.tvLoginError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.tvLoginError.visibility = View.GONE
    }
}
