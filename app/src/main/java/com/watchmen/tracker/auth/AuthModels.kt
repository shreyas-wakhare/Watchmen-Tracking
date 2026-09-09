package com.watchmen.tracker.auth

import org.json.JSONObject

data class SignupRequest(
    val email: String,
    val password: String,
    val fullName: String
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("email", email.trim().lowercase())
        json.put("password", password)
        json.put("full_name", fullName.trim())
        return json.toString()
    }
}

data class LoginRequest(
    val email: String,
    val password: String
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("email", email.trim().lowercase())
        json.put("password", password)
        return json.toString()
    }
}

data class UserResponse(
    val id: Int,
    val email: String,
    val fullName: String,
    val isActive: Boolean
) {
    companion object {
        fun fromJson(json: JSONObject): UserResponse {
            return UserResponse(
                id = json.optInt("id", -1),
                email = json.optString("email", ""),
                fullName = json.optString("full_name", ""),
                isActive = json.optBoolean("is_active", true)
            )
        }
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("email", email)
        json.put("full_name", fullName)
        json.put("is_active", isActive)
        return json
    }
}

data class SignupResponse(
    val message: String,
    val user: UserResponse
) {
    companion object {
        fun fromJson(json: JSONObject): SignupResponse {
            val userJson = json.optJSONObject("user") ?: JSONObject()
            return SignupResponse(
                message = json.optString("message", "User registered successfully"),
                user = UserResponse.fromJson(userJson)
            )
        }
    }
}

data class LoginResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val expiresIn: Int,
    val user: UserResponse
) {
    companion object {
        fun fromJson(json: JSONObject): LoginResponse {
            val userJson = json.optJSONObject("user") ?: JSONObject()
            return LoginResponse(
                accessToken = json.optString("access_token", ""),
                refreshToken = json.optString("refresh_token", ""),
                tokenType = json.optString("token_type", "bearer"),
                expiresIn = json.optInt("expires_in", 1800),
                user = UserResponse.fromJson(userJson)
            )
        }
    }
}

sealed class AuthResult<out T> {
    data class Success<out T>(val data: T) : AuthResult<T>()
    data class Error(val message: String, val statusCode: Int? = null) : AuthResult<Nothing>()
}
