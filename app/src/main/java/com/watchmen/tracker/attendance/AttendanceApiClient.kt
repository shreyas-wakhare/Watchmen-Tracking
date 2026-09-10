package com.watchmen.tracker.attendance

import android.util.Log
import com.watchmen.tracker.BackendEndpointManager
import com.watchmen.tracker.auth.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AttendanceApiClient {

    private const val TAG = "AttendanceApiClient"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Executes GET /attendance/schedule
     */
    suspend fun getSchedule(): AttendanceResult<ScheduleResponse> {
        return executeGet("/attendance/schedule") { json ->
            ScheduleResponse.fromJson(json)
        }
    }

    /**
     * Executes PUT /attendance/schedule
     */
    suspend fun updateSchedule(scheduleRequest: ScheduleRequest): AttendanceResult<ScheduleResponse> {
        return executePut("/attendance/schedule", scheduleRequest.toJson()) { json ->
            ScheduleResponse.fromJson(json)
        }
    }

    /**
     * Executes POST /attendance/clock-in
     */
    suspend fun clockIn(clockInRequest: ClockInRequest): AttendanceResult<AttendanceRecordResponse> {
        return executePost("/attendance/clock-in", clockInRequest.toJson()) { json ->
            AttendanceRecordResponse.fromJson(json)
        }
    }

    /**
     * Executes POST /attendance/clock-out
     */
    suspend fun clockOut(clockOutRequest: ClockOutRequest): AttendanceResult<AttendanceRecordResponse> {
        return executePost("/attendance/clock-out", clockOutRequest.toJson()) { json ->
            AttendanceRecordResponse.fromJson(json)
        }
    }

    /**
     * Executes GET /attendance/today
     */
    suspend fun getToday(): AttendanceResult<TodayAttendanceResponse> {
        return executeGet("/attendance/today") { json ->
            TodayAttendanceResponse.fromJson(json)
        }
    }

    /**
     * Executes GET /attendance/active
     */
    suspend fun getActive(): AttendanceResult<ActiveAttendanceResponse> {
        return executeGet("/attendance/active") { json ->
            ActiveAttendanceResponse.fromJson(json)
        }
    }

    /**
     * Executes GET /attendance/history
     */
    suspend fun getHistory(
        limit: Int = 20,
        offset: Int = 0,
        startDate: String? = null,
        endDate: String? = null
    ): AttendanceResult<AttendanceHistoryResponse> {
        val queryParams = mutableListOf("limit=$limit", "offset=$offset")
        if (!startDate.isNullOrBlank()) queryParams.add("start_date=${startDate.trim()}")
        if (!endDate.isNullOrBlank()) queryParams.add("end_date=${endDate.trim()}")
        val path = "/attendance/history?${queryParams.joinToString("&")}"

        return executeGet(path) { json ->
            AttendanceHistoryResponse.fromJson(json)
        }
    }

    // -------------------------------------------------------------
    // Generic HTTP Execution Helpers
    // -------------------------------------------------------------

    private suspend fun <T> executeGet(
        subPath: String,
        parser: (JSONObject) -> T
    ): AttendanceResult<T> = withContext(Dispatchers.IO) {
        val (baseUrl, token) = resolveBaseUrlAndToken()
            ?: return@withContext AttendanceResult.Error("Unable to connect to Watchmen server. Please verify network.", isNetworkError = true)

        val fullUrl = "$baseUrl$subPath"
        val request = Request.Builder()
            .url(fullUrl)
            .get()
            .header("Authorization", "Bearer $token")
            .build()

        executeCall(request, parser)
    }

    private suspend fun <T> executePost(
        subPath: String,
        jsonBody: String,
        parser: (JSONObject) -> T
    ): AttendanceResult<T> = withContext(Dispatchers.IO) {
        val (baseUrl, token) = resolveBaseUrlAndToken()
            ?: return@withContext AttendanceResult.Error("Unable to connect to Watchmen server. Please verify network.", isNetworkError = true)

        val fullUrl = "$baseUrl$subPath"
        val requestBody = jsonBody.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(fullUrl)
            .post(requestBody)
            .header("Authorization", "Bearer $token")
            .build()

        executeCall(request, parser)
    }

    private suspend fun <T> executePut(
        subPath: String,
        jsonBody: String,
        parser: (JSONObject) -> T
    ): AttendanceResult<T> = withContext(Dispatchers.IO) {
        val (baseUrl, token) = resolveBaseUrlAndToken()
            ?: return@withContext AttendanceResult.Error("Unable to connect to Watchmen server. Please verify network.", isNetworkError = true)

        val fullUrl = "$baseUrl$subPath"
        val requestBody = jsonBody.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(fullUrl)
            .put(requestBody)
            .header("Authorization", "Bearer $token")
            .build()

        executeCall(request, parser)
    }

    private fun <T> executeCall(
        request: Request,
        parser: (JSONObject) -> T
    ): AttendanceResult<T> {
        return try {
            val response = httpClient.newCall(request).execute()
            val responseBodyStr = response.body?.string() ?: ""

            when (response.code) {
                200, 201 -> {
                    val json = JSONObject(responseBodyStr)
                    AttendanceResult.Success(parser(json))
                }
                400 -> {
                    val msg = extractErrorDetail(responseBodyStr, "Invalid attendance request")
                    AttendanceResult.Error(msg, 400)
                }
                401 -> {
                    AttendanceResult.Error("Authentication session expired. Please log in again.", 401)
                }
                403 -> {
                    AttendanceResult.Error("User account is inactive. Please contact administrator.", 403)
                }
                404 -> {
                    AttendanceResult.Error("Schedule or attendance record not found.", 404)
                }
                409 -> {
                    val msg = extractErrorDetail(responseBodyStr, "Shift attendance conflict occurred.")
                    AttendanceResult.Error(msg, 409)
                }
                422 -> {
                    val msg = extractErrorDetail(responseBodyStr, "Invalid attendance input parameters.")
                    AttendanceResult.Error(msg, 422)
                }
                else -> {
                    val msg = extractErrorDetail(responseBodyStr, "Server error (${response.code})")
                    AttendanceResult.Error(msg, response.code)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network call failed for ${request.url}: ${e.message}")
            AttendanceResult.Error(
                message = "Connection unavailable — attendance could not be confirmed.",
                statusCode = null,
                isNetworkError = true
            )
        }
    }

    private suspend fun resolveBaseUrlAndToken(): Pair<String, String>? {
        val token = AuthManager.getAccessToken()
        if (token.isNullOrBlank()) {
            Log.w(TAG, "Access token missing in AuthManager.")
            return null
        }

        val baseUrl = BackendEndpointManager.awaitEndpoint(3000L)
            ?: BackendEndpointManager.getHttpBaseUrl()

        if (baseUrl.isNullOrBlank()) {
            Log.w(TAG, "Backend base URL unavailable.")
            return null
        }

        return Pair(baseUrl.trimEnd('/'), token)
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
