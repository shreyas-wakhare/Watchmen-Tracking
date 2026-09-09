package com.watchmen.tracker.auth

import android.util.Log
import com.watchmen.tracker.BackendEndpointManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AuthApiClient {

    private const val TAG = "AuthApiClient"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Executes login request to POST /auth/login
     */
    suspend fun login(email: String, password: String): AuthResult<LoginResponse> {
        return withContext(Dispatchers.IO) {
            // Await discovery if currently discovering
            val baseUrl = BackendEndpointManager.awaitEndpoint(3000L)
            Log.i(TAG, "[DISCOVERY] [12/12] AuthApiClient awaitEndpoint() returned: $baseUrl")
            if (baseUrl == null) {
                val stateMsg = when (BackendEndpointManager.getDiscoveryState()) {
                    BackendEndpointManager.DiscoveryState.DISCOVERING ->
                        "Searching for Watchmen server on network... Please try again in a moment."
                    else ->
                        "Unable to discover Watchmen server. Please check Wi-Fi connection."
                }
                return@withContext AuthResult.Error(stateMsg)
            }

            val loginUrl = "$baseUrl/auth/login"
            val requestBody = LoginRequest(email, password).toJson().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(loginUrl)
                .post(requestBody)
                .build()

            try {
                Log.i(TAG, "Executing login request for email: $email to $loginUrl")
                val response = httpClient.newCall(request).execute()
                val responseBodyStr = response.body?.string() ?: ""

                when (response.code) {
                    200 -> {
                        val json = JSONObject(responseBodyStr)
                        val loginResult = LoginResponse.fromJson(json)
                        Log.i(TAG, "Login successful for user ID: ${loginResult.user.id}")
                        AuthResult.Success(loginResult)
                    }
                    401 -> {
                        AuthResult.Error("Invalid email or password", 401)
                    }
                    403 -> {
                        AuthResult.Error("Your account is currently inactive. Please contact administrator.", 403)
                    }
                    422 -> {
                        val userMsg = extractErrorDetail(responseBodyStr, "Validation error occurred")
                        AuthResult.Error(userMsg, 422)
                    }
                    else -> {
                        val userMsg = extractErrorDetail(responseBodyStr, "Server error (${response.code})")
                        AuthResult.Error(userMsg, response.code)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Login network request failed: ${e.message}")
                AuthResult.Error("Network request failed. Please check connection and backend server.")
            }
        }
    }

    /**
     * Executes signup request to POST /auth/signup
     */
    suspend fun signup(fullName: String, email: String, password: String): AuthResult<SignupResponse> {
        return withContext(Dispatchers.IO) {
            val baseUrl = BackendEndpointManager.awaitEndpoint(3000L)
            if (baseUrl == null) {
                val stateMsg = when (BackendEndpointManager.getDiscoveryState()) {
                    BackendEndpointManager.DiscoveryState.DISCOVERING ->
                        "Searching for Watchmen server on network... Please try again in a moment."
                    else ->
                        "Unable to discover Watchmen server. Please check Wi-Fi connection."
                }
                return@withContext AuthResult.Error(stateMsg)
            }

            val signupUrl = "$baseUrl/auth/signup"
            val requestBody = SignupRequest(email, password, fullName).toJson().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(signupUrl)
                .post(requestBody)
                .build()

            try {
                Log.i(TAG, "Executing signup request for email: $email to $signupUrl")
                val response = httpClient.newCall(request).execute()
                val responseBodyStr = response.body?.string() ?: ""

                when (response.code) {
                    200, 201 -> {
                        val json = JSONObject(responseBodyStr)
                        val signupResult = SignupResponse.fromJson(json)
                        Log.i(TAG, "Signup successful for user ID: ${signupResult.user.id}")
                        AuthResult.Success(signupResult)
                    }
                    409 -> {
                        AuthResult.Error("An account with this email address already exists.", 409)
                    }
                    422 -> {
                        val userMsg = extractErrorDetail(responseBodyStr, "Invalid registration details")
                        AuthResult.Error(userMsg, 422)
                    }
                    else -> {
                        val userMsg = extractErrorDetail(responseBodyStr, "Server error (${response.code})")
                        AuthResult.Error(userMsg, response.code)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Signup network request failed: ${e.message}")
                AuthResult.Error("Network request failed. Please check connection and backend server.")
            }
        }
    }

    private fun extractErrorDetail(rawJson: String, defaultMsg: String): String {
        return try {
            val json = JSONObject(rawJson)
            val detail = json.opt("detail")
            when (detail) {
                is String -> detail
                is org.json.JSONArray -> {
                    if (detail.length() > 0) {
                        val firstErr = detail.optJSONObject(0)
                        firstErr?.optString("msg") ?: defaultMsg
                    } else defaultMsg
                }
                else -> defaultMsg
            }
        } catch (e: Exception) {
            defaultMsg
        }
    }
}
