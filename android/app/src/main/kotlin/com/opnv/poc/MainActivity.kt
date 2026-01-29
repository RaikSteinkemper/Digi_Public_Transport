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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private lateinit var sessionManager: SessionManager
    private lateinit var bleManager: BleManager
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

        // Request permissions
        requestBlePermissions()

        // Initialize Device
        val deviceId = sessionManager.getOrCreateDeviceId()
        sessionManager.registerDevice(deviceId)
        updateStatus("Device: $deviceId")

        // Buttons
        findViewById<Button>(R.id.startBtn).setOnClickListener { manualStartSession() }
        findViewById<Button>(R.id.endBtn).setOnClickListener { manualEndSession() }
        findViewById<Button>(R.id.billBtn).setOnClickListener { billToday() }
        findViewById<Button>(R.id.scanBtn).setOnClickListener { startBleScanning() }

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
        GlobalScope.launch(Dispatchers.Main) {
            val sessionId = sessionManager.startSession("BUS_MANUAL")
            if (sessionId != null) {
                isSessionActive = true
                updateStatus("Session active: $sessionId")
                updateQrCode()
            } else {
                updateStatus("Failed to start session")
            }
        }
    }

    private fun manualEndSession() {
        GlobalScope.launch(Dispatchers.Main) {
            if (sessionManager.endSession()) {
                isSessionActive = false
                qrImage.setImageBitmap(null)
                tokenText.text = ""
                updateStatus("Session ended")
            } else {
                updateStatus("Failed to end session")
            }
        }
    }

    private fun billToday() {
        val deviceId = sessionManager.getOrCreateDeviceId()
        GlobalScope.launch(Dispatchers.Main) {
            try {
                val url = "$API_BASE/fare/today?deviceId=$deviceId"
                val request = okhttp3.Request.Builder().url(url).get().build()
                val client = okhttp3.OkHttpClient()
                val response = client.newCall(request).execute()
                val respText = response.body?.string() ?: ""
                updateStatus("Today's bill:\n$respText")
            } catch (e: Exception) {
                updateStatus("Bill error: ${e.message}")
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

    override fun onDestroy() {
        super.onDestroy()
        bleManager.stopScanning()
    }
}
