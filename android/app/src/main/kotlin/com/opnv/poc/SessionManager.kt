package com.opnv.poc

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

const val API_BASE = "http://10.209.75.25:3000"  // <- ändere IP hier http://192.168.2.146:3000

data class SessionStartRequest(val deviceId: String, val vehicleId: String)
data class SessionStartResponse(val token: String, val sessionId: String)
data class SessionEndRequest(val sessionId: String)
data class SessionEndResponse(val ok: Boolean, val totalCents: Int, val capped: Boolean)
data class DeviceRegisterRequest(val deviceId: String, val devicePubKeyPem: String)
data class StartSessionResult(val sessionId: String?, val error: String?)
data class Trip(val sessionId: String, val vehicleId: String, val startTime: Long, val endTime: Long)
data class TodayBillResponse(
    val trips: List<Trip>,
    val tripCount: Int,
    val pricePerTrip: Int,
    val subtotalCents: Int,
    val totalCents: Int,
    val capped: Boolean,
    val dayCap: Int
)

/**
 * SessionManager: Verwaltet Session-Lifecycle über REST API
 */
class SessionManager(private val context: Context) {
    private val TAG = "SessionManager"
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()
    private val prefs = OPNVApp.prefs

    fun getOrCreateDeviceId(): String {
        var deviceId = prefs.getString("deviceId", null)
        if (deviceId == null) {
            deviceId = "dev-" + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString("deviceId", deviceId).apply()
        }
        return deviceId
    }

    fun getSessionId(): String? = prefs.getString("sessionId", null)
    fun getSessionToken(): String? = prefs.getString("sessionToken", null)

    fun registerDevice(deviceId: String) {
        val (_, pubB64) = CryptoHelper.getOrCreateKeyPair()
        val pubPem = CryptoHelper.pubKeyB64ToPem(pubB64)

        val req = DeviceRegisterRequest(deviceId, pubPem)
        val json = gson.toJson(req)
        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$API_BASE/device/register")
            .post(body)
            .build()

        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Log.i(TAG, "Device registered: $deviceId")
            } else {
                Log.e(TAG, "Device registration failed: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "registerDevice error", e)
        }
    }

    fun startSession(vehicleId: String): String? {
        return startSessionWithResult(vehicleId).sessionId
    }

    fun startSessionWithResult(vehicleId: String): StartSessionResult {
        val deviceId = getOrCreateDeviceId()

        // Sicherstellen, dass Device registriert ist
        registerDevice(deviceId)

        val req = SessionStartRequest(deviceId, vehicleId)
        val json = gson.toJson(req)
        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$API_BASE/session/start")
            .post(body)
            .build()

        return try {
            val response = client.newCall(request).execute()
            val respText = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val respObj = gson.fromJson(respText, SessionStartResponse::class.java)
                prefs.edit().apply {
                    putString("sessionId", respObj.sessionId)
                    putString("sessionToken", respObj.token)
                    putLong("sessionStartTime", System.currentTimeMillis())
                    apply()
                }
                Log.i(TAG, "Session started: ${respObj.sessionId}")
                StartSessionResult(respObj.sessionId, null)
            } else {
                Log.e(TAG, "Session start failed: ${response.code} - $respText")
                StartSessionResult(null, "HTTP ${response.code} - $respText")
            }
        } catch (e: Exception) {
            Log.e(TAG, "startSession error", e)
            StartSessionResult(null, e.message ?: "Unknown error")
        }
    }

    fun endSession(): Boolean {
        val sessionId = getSessionId() ?: return false

        val req = SessionEndRequest(sessionId)
        val json = gson.toJson(req)
        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$API_BASE/session/end")
            .post(body)
            .build()

        return try {
            val response = client.newCall(request).execute()
            val respText = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val respObj = gson.fromJson(respText, SessionEndResponse::class.java)
                prefs.edit().apply {
                    remove("sessionId")
                    remove("sessionToken")
                    remove("sessionStartTime")
                    apply()
                }
                Log.i(TAG, "Session ended. Fare: ${respObj.totalCents} cents, capped: ${respObj.capped}")
                true
            } else {
                Log.e(TAG, "Session end failed: ${response.code} - $respText")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "endSession error", e)
            false
        }
    }

    fun getCurrentQrJson(): String? {
        val token = getSessionToken() ?: return null
        val now = System.currentTimeMillis() / 1000
        val slot = now / 30

        val (privB64, _) = CryptoHelper.getOrCreateKeyPair()
        val payload = "$token|$slot"
        val sig = CryptoHelper.signPayload(payload, privB64)

        val qrData = mapOf(
            "token" to token,
            "slot" to slot,
            "sig" to sig
        )
        return gson.toJson(qrData)
    }

    fun getTodayBill(): TodayBillResponse? {
        val deviceId = getOrCreateDeviceId()
        return try {
            val url = "$API_BASE/trips/today?deviceId=$deviceId"
            Log.i(TAG, "Fetching today's bill from: $url")
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val respText = response.body?.string() ?: ""
            Log.i(TAG, "Response code: ${response.code}, body length: ${respText.length}")
            if (response.isSuccessful) {
                val result = gson.fromJson(respText, TodayBillResponse::class.java)
                Log.i(TAG, "Parsed bill response: ${result.tripCount} trips, total: ${result.totalCents} cents")
                result
            } else {
                Log.e(TAG, "getTodayBill failed: ${response.code} - $respText")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getTodayBill error: ${e.message}", e)
            null
        }
    }

    fun debugDeleteTodayTrips(): Boolean {
        val deviceId = getOrCreateDeviceId()
        return try {
            val req = mapOf("deviceId" to deviceId)
            val json = gson.toJson(req)
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$API_BASE/debug/delete-today-trips")
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            val respText = response.body?.string() ?: ""
            if (response.isSuccessful) {
                Log.i(TAG, "DEBUG: Deleted today's trips - $respText")
                true
            } else {
                Log.e(TAG, "DEBUG delete failed: ${response.code} - $respText")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "debugDeleteTodayTrips error", e)
            false
        }
    }
}
