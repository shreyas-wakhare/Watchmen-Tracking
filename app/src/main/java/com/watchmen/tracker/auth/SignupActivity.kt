package com.watchmen.tracker.auth

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.watchmen.tracker.BackendEndpointManager
import com.watchmen.tracker.R
import com.watchmen.tracker.databinding.ActivitySignupBinding
import kotlinx.coroutines.launch

class SignupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AuthManager.init(this)
        BackendEndpointManager.init(this)

        binding.btnSignup.setOnClickListener {
            attemptSignup()
        }

        binding.tvGoToLogin.setOnClickListener {
            finish()
        }
    }

    private fun attemptSignup() {
        hideError()

        val fullName = binding.etFullName.text?.toString()?.trim() ?: ""
        val email = binding.etSignupEmail.text?.toString()?.trim() ?: ""
        val password = binding.etSignupPassword.text?.toString() ?: ""
        val confirmPassword = binding.etConfirmPassword.text?.toString() ?: ""

        if (fullName.isEmpty()) {
            showError(getString(R.string.err_empty_name))
            binding.etFullName.requestFocus()
            return
        }

        if (email.isEmpty()) {
            showError(getString(R.string.err_empty_email))
            binding.etSignupEmail.requestFocus()
            return
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showError(getString(R.string.err_invalid_email))
            binding.etSignupEmail.requestFocus()
            return
        }

        if (password.isEmpty()) {
            showError(getString(R.string.err_empty_password))
            binding.etSignupPassword.requestFocus()
            return
        }

        if (password.length < 8) {
            showError(getString(R.string.err_short_password))
            binding.etSignupPassword.requestFocus()
            return
        }

        if (password != confirmPassword) {
            showError(getString(R.string.err_password_mismatch))
            binding.etConfirmPassword.requestFocus()
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            val result = AuthApiClient.signup(fullName, email, password)
            setLoading(false)

            when (result) {
                is AuthResult.Success -> {
                    Toast.makeText(
                        this@SignupActivity,
                        "Account created successfully! Please log in.",
                        Toast.LENGTH_LONG
                    ).show()

                    val loginIntent = Intent(this@SignupActivity, LoginActivity::class.java).apply {
                        putExtra(LoginActivity.EXTRA_PREFILL_EMAIL, email)
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(loginIntent)
                    finish()
                }
                is AuthResult.Error -> {
                    showError(result.message)
                }
            }
        }
    }

    private fun setLoading(isLoading: Boolean) {
        binding.btnSignup.isEnabled = !isLoading
        binding.etFullName.isEnabled = !isLoading
        binding.etSignupEmail.isEnabled = !isLoading
        binding.etSignupPassword.isEnabled = !isLoading
        binding.etConfirmPassword.isEnabled = !isLoading
        binding.tvGoToLogin.isEnabled = !isLoading
        binding.pbSignupLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        binding.tvSignupError.text = message
        binding.tvSignupError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.tvSignupError.visibility = View.GONE
    }
}
