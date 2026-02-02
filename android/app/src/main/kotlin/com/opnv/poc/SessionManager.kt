package com.opnv.poc

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

const val API_BASE = "http://192.168.2.146:3000"  // <- ändere IP hier

data class SessionStartRequest(val deviceId: String, val vehicleId: String)
data class SessionStartResponse(val token: String, val sessionId: String)
data class SessionEndRequest(val sessionId: String)
data class SessionEndResponse(val ok: Boolean, val totalCents: Int, val capped: Boolean)
data class DeviceRegisterRequest(val deviceId: String, val devicePubKeyPem: String)

/**
 * SessionManager: Verwaltet Session-Lifecycle über REST API
 */
class SessionManager(private val context: Context) {
    private val TAG = "SessionManager"
    private val gson = Gson()
    private val client = OkHttpClient()
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
                respObj.sessionId
            } else {
                Log.e(TAG, "Session start failed: ${response.code} - $respText")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "startSession error", e)
            null
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
}
