package com.watchmen.tracker.auth

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AuthModelsTest {

    @Test
    fun testSignupRequestJsonSerialization() {
        val signupReq = SignupRequest(
            email = "  USER@EXAMPLE.COM  ",
            password = "SecretPassword123",
            fullName = "  John Doe  "
        )
        val jsonStr = signupReq.toJson()
        val json = JSONObject(jsonStr)

        assertEquals("user@example.com", json.getString("email"))
        assertEquals("SecretPassword123", json.getString("password"))
        assertEquals("John Doe", json.getString("full_name"))
    }

    @Test
    fun testLoginRequestJsonSerialization() {
        val loginReq = LoginRequest(
            email = "  TestUser@Watchmen.com ",
            password = "MyPassword!"
        )
        val jsonStr = loginReq.toJson()
        val json = JSONObject(jsonStr)

        assertEquals("testuser@watchmen.com", json.getString("email"))
        assertEquals("MyPassword!", json.getString("password"))
    }

    @Test
    fun testUserResponseJsonDeserialization() {
        val rawJson = JSONObject().apply {
            put("id", 42)
            put("email", "guard@watchmen.com")
            put("full_name", "Guard Duty")
            put("is_active", true)
        }

        val user = UserResponse.fromJson(rawJson)

        assertEquals(42, user.id)
        assertEquals("guard@watchmen.com", user.email)
        assertEquals("Guard Duty", user.fullName)
        assertTrue(user.isActive)
    }

    @Test
    fun testLoginResponseJsonDeserialization() {
        val rawUser = JSONObject().apply {
            put("id", 101)
            put("email", "watchmen@domain.com")
            put("full_name", "Watchmen Guard")
            put("is_active", true)
        }
        val rawResponse = JSONObject().apply {
            put("access_token", "sample_access_token_jwt")
            put("refresh_token", "sample_refresh_token_string")
            put("token_type", "bearer")
            put("expires_in", 1800)
            put("user", rawUser)
        }

        val loginResponse = LoginResponse.fromJson(rawResponse)

        assertEquals("sample_access_token_jwt", loginResponse.accessToken)
        assertEquals("sample_refresh_token_string", loginResponse.refreshToken)
        assertEquals("bearer", loginResponse.tokenType)
        assertEquals(1800, loginResponse.expiresIn)
        assertEquals(101, loginResponse.user.id)
        assertEquals("watchmen@domain.com", loginResponse.user.email)
    }

    @Test
    fun testSignupResponseJsonDeserialization() {
        val rawUser = JSONObject().apply {
            put("id", 77)
            put("email", "newuser@domain.com")
            put("full_name", "New Guard")
            put("is_active", true)
        }
        val rawResponse = JSONObject().apply {
            put("message", "User registered successfully")
            put("user", rawUser)
        }

        val signupResponse = SignupResponse.fromJson(rawResponse)

        assertEquals("User registered successfully", signupResponse.message)
        assertEquals(77, signupResponse.user.id)
        assertEquals("newuser@domain.com", signupResponse.user.email)
    }
}
