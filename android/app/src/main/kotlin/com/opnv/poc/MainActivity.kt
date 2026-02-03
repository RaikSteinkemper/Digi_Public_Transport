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
        findViewById<Button>(R.id.debugDeleteBtn).setOnClickListener { debugDeleteTodayTrips() }

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
        updateStatus("Lade Rechnung...")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                android.util.Log.i(TAG, "billToday: Fetching bill data...")
                val billData = sessionManager.getTodayBill()
                android.util.Log.i(TAG, "billToday: Bill data = $billData")
                withContext(Dispatchers.Main) {
                    if (billData != null) {
                        android.util.Log.i(TAG, "billToday: Showing dialog")
                        showBillDialog(billData)
                        updateStatus("Rechnung geladen")
                    } else {
                        android.util.Log.e(TAG, "billToday: Bill data is null")
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Fehler")
                            .setMessage("Fehler beim Abrufen der Rechnung.\n\nBitte prüfen Sie:\n- Backend läuft auf $API_BASE\n- Netzwerkverbindung ist aktiv\n- Firewall erlaubt Verbindung")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                        updateStatus("Fehler beim Abrufen der Rechnung - siehe Logs")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "billToday: Exception", e)
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Fehler")
                        .setMessage("Fehler: ${e.message}\n\nBackend: $API_BASE")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
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

    private fun showBillDialog(billData: TodayBillResponse) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_bill_today, null)
        
        val tripsListText = dialogView.findViewById<TextView>(R.id.tripsListText)
        val billSummaryText = dialogView.findViewById<TextView>(R.id.billSummaryText)
        val savingsText = dialogView.findViewById<TextView>(R.id.savingsText)

        // Format trips list
        val tripsText = if (billData.trips.isEmpty()) {
            "Keine Fahrten heute"
        } else {
            buildString {
                billData.trips.forEachIndexed { index, trip ->
                    val startDate = java.util.Date(trip.startTime * 1000)
                    val endDate = java.util.Date(trip.endTime * 1000)
                    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    
                    append("${index + 1}. Fahrt mit ${trip.vehicleId}\n")
                    append("   Start: ${timeFormat.format(startDate)}\n")
                    append("   Ende:  ${timeFormat.format(endDate)}\n")
                    append("   Preis: 3,00 €\n")
                    if (index < billData.trips.size - 1) append("\n")
                }
            }
        }
        tripsListText.text = tripsText

        // Format bill summary
        val billSummary = buildString {
            append("Anzahl Fahrten: ${billData.tripCount}\n")
            append("Preis pro Fahrt: ${billData.pricePerTrip / 100.0} €\n")
            append("Zwischensumme: ${billData.subtotalCents / 100.0} €\n")
            append("\n")
            if (billData.capped) {
                append("Tagesticket-Cap greift!\n")
                append("Gesamt: ${billData.totalCents / 100.0} € (statt ${billData.subtotalCents / 100.0} €)\n")
            } else {
                append("Gesamt: ${billData.totalCents / 100.0} €\n")
            }
        }
        billSummaryText.text = billSummary

        // Show savings if applicable
        if (billData.capped) {
            val savings = (billData.subtotalCents - billData.totalCents) / 100.0
            savingsText.text = "✓ Sie sparen ${savings} € durch das Tagesticket!\nDas lohnt sich für Sie!"
            savingsText.visibility = android.view.View.VISIBLE
        } else {
            savingsText.visibility = android.view.View.GONE
        }

        AlertDialog.Builder(this)
            .setTitle("Rechnung Heute")
            .setView(dialogView)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun debugDeleteTodayTrips() {
        AlertDialog.Builder(this)
            .setTitle("DEBUG: Fahrten löschen?")
            .setMessage("Alle Fahrten von heute werden gelöscht.\n\nDies kann nicht rückgängig gemacht werden!")
            .setPositiveButton("JA, LÖSCHEN") { dialog, _ ->
                dialog.dismiss()
                updateStatus("Lösche Fahrten...")
                GlobalScope.launch(Dispatchers.IO) {
                    val success = sessionManager.debugDeleteTodayTrips()
                    withContext(Dispatchers.Main) {
                        if (success) {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Erfolg")
                                .setMessage("Alle Fahrten von heute wurden gelöscht!")
                                .setPositiveButton("OK") { d, _ -> d.dismiss() }
                                .show()
                            updateStatus("DEBUG: Fahrten gelöscht!")
                        } else {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Fehler")
                                .setMessage("Fehler beim Löschen der Fahrten")
                                .setPositiveButton("OK") { d, _ -> d.dismiss() }
                                .show()
                            updateStatus("DEBUG: Fehler beim Löschen")
                        }
                    }
                }
            }
            .setNegativeButton("Abbrechen") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.stopScanning()
    }
}
