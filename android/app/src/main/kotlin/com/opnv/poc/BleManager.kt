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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

const val BEACON_NAME = "BUS_4711"
const val BEACON_TIMEOUT_MS = 20000L  // 20s ohne Signal = Session End
const val RSSI_THRESHOLD = -65  // Nur Beacons näher als ~1-5m akzeptieren

/**
 * BleManager: Scannt nach BLE-Beacon mit localName = "BUS_4711"
 * und triggert Session-Start/End
 */
class BleManager(private val context: Context, private val sessionManager: SessionManager) {
    private val TAG = "BleManager"
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private val bleScanner = bluetoothAdapter?.bluetoothLeScanner
    private var lastBeaconTime = 0L
    private var isScanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val deviceName = result.device.name ?: ""
            if (deviceName.contains(BEACON_NAME, ignoreCase = true)) {
                Log.i(TAG, "Beacon found: $deviceName (rssi=${result.rssi})")
                
                // Nur akzeptieren wenn Signal stark genug ist (1-5m Bereich)
                if (result.rssi < RSSI_THRESHOLD) {
                    Log.i(TAG, "Beacon zu weit weg (RSSI=${result.rssi} < $RSSI_THRESHOLD). Ignoriert.")
                    return
                }
                
                lastBeaconTime = System.currentTimeMillis()

                // Auto-start session wenn nicht aktiv
                if (sessionManager.getSessionId() == null) {
                    GlobalScope.launch(Dispatchers.Main) {
                        Log.i(TAG, "Auto-starting session...")
                        sessionManager.startSession(BEACON_NAME)
                    }
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
        GlobalScope.launch(Dispatchers.Main) {
            while (isScanning) {
                val timeSinceLastBeacon = System.currentTimeMillis() - lastBeaconTime
                if (lastBeaconTime > 0 && timeSinceLastBeacon > BEACON_TIMEOUT_MS && sessionManager.getSessionId() != null) {
                    Log.i(TAG, "Beacon lost, ending session...")
                    sessionManager.endSession()
                    lastBeaconTime = 0
                    onStatusChanged("Beacon lost. Session ended.")
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
}
