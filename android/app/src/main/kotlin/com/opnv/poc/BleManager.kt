package com.opnv.poc

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val BEACON_NAME = "BUS_4711"
const val BEACON_TIMEOUT_MS = 20000L  // 20s ohne Signal = Session End
const val MIN_RSSI = -65  // Minimum RSSI für Session-Start (ca. 1-5m Entfernung)
const val MIN_STABLE_SIGNAL_MS = 10000L // 10s im Signal bevor Session startet

/**
 * BleManager: Scannt nach BLE-Beacon mit localName = "BUS_4711"
 * und triggert Session-Start/End
 */
@OptIn(DelicateCoroutinesApi::class)
class BleManager(private val context: Context, private val sessionManager: SessionManager) {
    private val TAG = "BleManager"
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private val bleScanner = bluetoothAdapter?.bluetoothLeScanner
    private var lastBeaconTime = 0L
    private var strongBeaconSince = 0L
    private var isScanning = false
    private var onSessionStarted: ((String) -> Unit)? = null
    private var onSessionEnded: (() -> Unit)? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val deviceName = result.device.name ?: ""
            if (deviceName.contains(BEACON_NAME, ignoreCase = true)) {
                Log.i(TAG, "Beacon found: $deviceName (rssi=${result.rssi})")
                
                // Prüfe RSSI - nur nah genug (1-5m) erlaubt Session-Start
                if (result.rssi >= MIN_RSSI) {
                    lastBeaconTime = System.currentTimeMillis()

                    if (strongBeaconSince == 0L) {
                        strongBeaconSince = lastBeaconTime
                        Log.i(TAG, "Strong beacon detected, starting stability timer...")
                    }

                    val stableFor = lastBeaconTime - strongBeaconSince

                    // Auto-start session wenn nicht aktiv
                    if (sessionManager.getSessionId() == null && stableFor >= MIN_STABLE_SIGNAL_MS) {
                        GlobalScope.launch(Dispatchers.IO) {
                            Log.i(TAG, "Auto-starting session (RSSI: ${result.rssi} >= $MIN_RSSI, stable ${stableFor}ms)...")
                            val sessionId = sessionManager.startSession(BEACON_NAME)
                            if (sessionId != null) {
                                strongBeaconSince = 0L
                                withContext(Dispatchers.Main) {
                                    onSessionStarted?.invoke(sessionId)
                                }
                            }
                        }
                    }
                } else {
                    strongBeaconSince = 0L
                    Log.i(TAG, "Beacon too far away (RSSI: ${result.rssi} < $MIN_RSSI), ignoring")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "Scan failed: $errorCode")
        }
    }

    fun startScanning(onStatusChanged: (String) -> Unit) {
        if (isScanning) return

        Log.i(TAG, "Starting BLE scan...")
        isScanning = true
        onStatusChanged("Scanning für $BEACON_NAME...")

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        try {
            bleScanner?.startScan(
                listOf(ScanFilter.Builder().setDeviceName(BEACON_NAME).build()),
                scanSettings,
                scanCallback
            )
        } catch (e: Exception) {
            Log.e(TAG, "startScanning error (may need location permission)", e)
            onStatusChanged("BLE Scan Error (check permissions)")
            isScanning = false
        }

        // Monitor timeout (wenn 20s kein Beacon = end session)
        GlobalScope.launch(Dispatchers.IO) {
            while (isScanning) {
                val timeSinceLastBeacon = System.currentTimeMillis() - lastBeaconTime
                if (lastBeaconTime > 0 && timeSinceLastBeacon > BEACON_TIMEOUT_MS && sessionManager.getSessionId() != null) {
                    Log.i(TAG, "Beacon lost, ending session...")
                    val success = sessionManager.endSession()
                    if (success) {
                        lastBeaconTime = 0
                        withContext(Dispatchers.Main) {
                            onSessionEnded?.invoke()
                            onStatusChanged("Beacon lost. Session ended.")
                        }
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    fun stopScanning() {
        if (!isScanning) return
        Log.i(TAG, "Stopping BLE scan...")
        bleScanner?.stopScan(scanCallback)
        isScanning = false
    }

    fun setSessionCallbacks(
        onStarted: (String) -> Unit,
        onEnded: () -> Unit
    ) {
        this.onSessionStarted = onStarted
        this.onSessionEnded = onEnded
    }
}
