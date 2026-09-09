package com.watchmen.tracker.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

object AuthManager {

    private const val TAG = "AuthManager"
    private const val PREF_NAME = "watchmen_auth_prefs"

    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USER_EMAIL = "user_email"
    private const val KEY_USER_FULL_NAME = "user_full_name"
    private const val KEY_USER_IS_ACTIVE = "user_is_active"
    private const val KEY_TOKEN_EXPIRY = "token_expiry_timestamp"

    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            Log.i(TAG, "AuthManager initialized. Logged in state: ${isLoggedIn()}")
        }
    }

    private fun getPrefs(context: Context? = null): SharedPreferences {
        if (prefs == null && context != null) {
            init(context)
        }
        return prefs ?: throw IllegalStateException("AuthManager must be initialized with context before use.")
    }

    /**
     * Checks whether user is currently authenticated with a valid stored session.
     */
    fun isLoggedIn(context: Context? = null): Boolean {
        val p = try {
            getPrefs(context)
        } catch (e: Exception) {
            return false
        }
        val token = p.getString(KEY_ACCESS_TOKEN, null)
        return !token.isNullOrBlank()
    }

    /**
     * Persists authentication tokens and user information securely.
     */
    fun saveSession(loginResponse: LoginResponse, context: Context? = null) {
        val p = getPrefs(context)
        val expiryTimestamp = System.currentTimeMillis() + (loginResponse.expiresIn * 1000L)

        p.edit()
            .putString(KEY_ACCESS_TOKEN, loginResponse.accessToken)
            .putString(KEY_REFRESH_TOKEN, loginResponse.refreshToken)
            .putInt(KEY_USER_ID, loginResponse.user.id)
            .putString(KEY_USER_EMAIL, loginResponse.user.email)
            .putString(KEY_USER_FULL_NAME, loginResponse.user.fullName)
            .putBoolean(KEY_USER_IS_ACTIVE, loginResponse.user.isActive)
            .putLong(KEY_TOKEN_EXPIRY, expiryTimestamp)
            .apply()

        Log.i(TAG, "Auth session saved successfully for user ID: ${loginResponse.user.id}")
    }

    /**
     * Clears authentication session (logout) without affecting device ID or tracking config.
     */
    fun clearSession(context: Context? = null) {
        try {
            val p = getPrefs(context)
            p.edit().clear().apply()
            Log.i(TAG, "Auth session cleared successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing auth session: ${e.message}")
        }
    }

    fun getAccessToken(context: Context? = null): String? {
        return try {
            getPrefs(context).getString(KEY_ACCESS_TOKEN, null)
        } catch (e: Exception) {
            null
        }
    }

    fun getRefreshToken(context: Context? = null): String? {
        return try {
            getPrefs(context).getString(KEY_REFRESH_TOKEN, null)
        } catch (e: Exception) {
            null
        }
    }

    fun getUser(context: Context? = null): UserResponse? {
        val p = try {
            getPrefs(context)
        } catch (e: Exception) {
            return null
        }

        val id = p.getInt(KEY_USER_ID, -1)
        if (id == -1) return null

        val email = p.getString(KEY_USER_EMAIL, "") ?: ""
        val fullName = p.getString(KEY_USER_FULL_NAME, "") ?: ""
        val isActive = p.getBoolean(KEY_USER_IS_ACTIVE, true)

        return UserResponse(
            id = id,
            email = email,
            fullName = fullName,
            isActive = isActive
        )
    }
}
