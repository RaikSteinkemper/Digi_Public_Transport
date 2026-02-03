package com.opnv.poc

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private lateinit var sessionManager: SessionManager
    private lateinit var bleManager: BleManager
    private lateinit var httpClient: okhttp3.OkHttpClient
    private lateinit var statusText: TextView
    private lateinit var tokenText: TextView
    private lateinit var qrImage: ImageView
    private var isSessionActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        tokenText = findViewById(R.id.tokenText)
        qrImage = findViewById(R.id.qrImage)

        sessionManager = SessionManager(this)
        bleManager = BleManager(this, sessionManager)
        httpClient = okhttp3.OkHttpClient.Builder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .build()

        // Request permissions
        requestBlePermissions()

        // Initialize Device
        val deviceId = sessionManager.getOrCreateDeviceId()
        GlobalScope.launch(Dispatchers.IO) {
            sessionManager.registerDevice(deviceId)
        }
        updateStatus("Device: $deviceId")

        // Buttons
        findViewById<Button>(R.id.startBtn).setOnClickListener { manualStartSession() }
        findViewById<Button>(R.id.endBtn).setOnClickListener { manualEndSession() }
        findViewById<Button>(R.id.billBtn).setOnClickListener { billToday() }
        findViewById<Button>(R.id.scanBtn).setOnClickListener { startBleScanning() }

        // Setup callbacks for automatic session events
        bleManager.setSessionCallbacks(
            onStarted = { sessionId -> showSessionStartedPopup(sessionId) },
            onEnded = { showSessionEndedPopup() }
        )

        // Start scanning automatically on launch
        startBleScanning()

        // QR refresh loop
        GlobalScope.launch(Dispatchers.Main) {
            while (true) {
                if (isSessionActive) {
                    updateQrCode()
                }
                kotlinx.coroutines.delay(5000)  // Refresh every 5s
            }
        }
    }

    private fun requestBlePermissions() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.INTERNET
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val toRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 100)
        }
    }

    private fun startBleScanning() {
        bleManager.startScanning { status ->
            updateStatus(status)
        }
    }

    private fun manualStartSession() {
        GlobalScope.launch(Dispatchers.IO) {
            val result = sessionManager.startSessionWithResult("BUS_MANUAL")
            withContext(Dispatchers.Main) {
                if (result.sessionId != null) {
                    isSessionActive = true
                    updateStatus("Session active: ${result.sessionId}")
                    updateQrCode()
                } else {
                    val err = result.error ?: "Unknown error"
                    updateStatus("Failed to start session: $err")
                }
            }
        }
    }

    private fun manualEndSession() {
        GlobalScope.launch(Dispatchers.IO) {
            val success = sessionManager.endSession()
            withContext(Dispatchers.Main) {
                if (success) {
                    isSessionActive = false
                    qrImage.setImageBitmap(null)
                    tokenText.text = ""
                    updateStatus("Session ended")
                } else {
                    updateStatus("Failed to end session")
                }
            }
        }
    }

    private fun billToday() {
        val deviceId = sessionManager.getOrCreateDeviceId()
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val url = "$API_BASE/fare/today?deviceId=$deviceId"
                val request = okhttp3.Request.Builder().url(url).get().build()
                val response = httpClient.newCall(request).execute()
                val respText = response.body?.string() ?: ""
                withContext(Dispatchers.Main) {
                    updateStatus("Today's bill:\n$respText")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateStatus("Bill error: ${e.message}")
                }
            }
        }
    }

    private fun updateQrCode() {
        GlobalScope.launch(Dispatchers.Main) {
            val qrJson = sessionManager.getCurrentQrJson()
            if (qrJson != null) {
                tokenText.text = qrJson
                val bitmap = QrGenerator.generateQrBitmap(qrJson, 512)
                if (bitmap != null) {
                    qrImage.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun updateStatus(msg: String) {
        GlobalScope.launch(Dispatchers.Main) {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            statusText.text = "$timestamp\n$msg\n${statusText.text}"
            // Keep log size small
            val lines = statusText.text.toString().split("\n").take(20).joinToString("\n")
            statusText.text = lines
        }
    }

    private fun showSessionStartedPopup(sessionId: String) {
        AlertDialog.Builder(this)
            .setTitle("Fahrt gestartet")
            .setMessage("Bus-Beacon erkannt!\nIhre Fahrt wurde automatisch gestartet.\n\nSession-ID: $sessionId")
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                isSessionActive = true
                updateQrCode()
            }
            .setCancelable(false)
            .show()
    }

    private fun showSessionEndedPopup() {
        AlertDialog.Builder(this)
            .setTitle("Fahrt beendet")
            .setMessage("Bus-Beacon-Signal verloren.\nIhre Fahrt wurde automatisch beendet.\n\nDie Kosten werden abgerechnet.")
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                isSessionActive = false
                qrImage.setImageBitmap(null)
                tokenText.text = ""
            }
            .setCancelable(false)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.stopScanning()
    }
}
