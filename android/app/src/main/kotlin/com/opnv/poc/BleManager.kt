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
            val deviceName = result.device.name ?: "<unnamed>"
            val deviceAddr = result.device.address ?: "<unknown>"
            Log.d(TAG, "onScanResult: name=$deviceName, addr=$deviceAddr, rssi=${result.rssi}")
            
            if (deviceName.contains(BEACON_NAME, ignoreCase = true)) {
                Log.i(TAG, "✓ BEACON FOUND: $deviceName (rssi=${result.rssi})")
                lastBeaconTime = System.currentTimeMillis()

                // Check if signal is strong enough
                if (result.rssi >= MIN_RSSI) {
                    // Track how long we have a stable strong signal
                    if (strongBeaconSince == 0L) {
                        strongBeaconSince = System.currentTimeMillis()
                        Log.i(TAG, "Started tracking stable signal strength")
                    }
                    val stableFor = System.currentTimeMillis() - strongBeaconSince
                    Log.i(TAG, "Strong beacon signal: rssi=$result.rssi, stable=${stableFor}ms / ${MIN_STABLE_SIGNAL_MS}ms needed")

                    // Auto-start session wenn nicht aktiv
                    val hasActiveSession = sessionManager.getSessionId() != null
                    Log.i(TAG, "Active session: $hasActiveSession, stableFor=$stableFor, needStable=$MIN_STABLE_SIGNAL_MS")
                    
                    if (!hasActiveSession && stableFor >= MIN_STABLE_SIGNAL_MS) {
                        Log.i(TAG, "✓✓✓ TRIGGERING AUTO SESSION START ✓✓✓")
                        GlobalScope.launch(Dispatchers.IO) {
                            Log.i(TAG, "Auto-starting session (RSSI: ${result.rssi} >= $MIN_RSSI, stable ${stableFor}ms)...")
                            val sessionId = sessionManager.startSession(BEACON_NAME)
                            if (sessionId != null) {
                                strongBeaconSince = 0L
                                withContext(Dispatchers.Main) {
                                    onSessionStarted?.invoke(sessionId)
                                }
                            } else {
                                Log.e(TAG, "Session start returned null!")
                            }
                        }
                    } else {
                        Log.i(TAG, "Not starting session: hasActive=$hasActiveSession, stable=${stableFor >= MIN_STABLE_SIGNAL_MS}")
                    }
                } else {
                    strongBeaconSince = 0L
                    Log.i(TAG, "Beacon too far away (RSSI: ${result.rssi} < $MIN_RSSI), ignoring")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "Scan FAILED with error code: $errorCode")
            when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> Log.e(TAG, "  -> Scan already started")
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> Log.e(TAG, "  -> App registration failed")
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> Log.e(TAG, "  -> Internal error")
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> Log.e(TAG, "  -> Feature unsupported")
                else -> Log.e(TAG, "  -> Unknown error")
            }
        }
    }

    fun startScanning(onStatusChanged: (String) -> Unit) {
        if (isScanning) {
            Log.i(TAG, "Already scanning, ignoring duplicate request")
            return
        }

        Log.i(TAG, "Starting BLE scan...")
        Log.i(TAG, "Bluetooth Adapter: ${bluetoothAdapter?.isEnabled}")
        Log.i(TAG, "BLE Scanner available: ${bleScanner != null}")
        
        if (bluetoothAdapter == null) {
            Log.e(TAG, "ERROR: Bluetooth Adapter is null")
            onStatusChanged("ERROR: Bluetooth nicht unterstützt")
            return
        }
        
        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "ERROR: Bluetooth is disabled")
            onStatusChanged("ERROR: Bluetooth ist ausgeschaltet")
            return
        }
        
        if (bleScanner == null) {
            Log.e(TAG, "ERROR: BLE Scanner is null")
            onStatusChanged("ERROR: BLE Scanner nicht verfügbar")
            return
        }

        isScanning = true
        onStatusChanged("Scanning für $BEACON_NAME...")
        Log.i(TAG, "BLE Scan Status: isScanning=$isScanning")

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        try {
            Log.i(TAG, "Calling bleScanner.startScan()...")
            bleScanner?.startScan(
                listOf(ScanFilter.Builder().setDeviceName(BEACON_NAME).build()),
                scanSettings,
                scanCallback
            )
            Log.i(TAG, "bleScanner.startScan() called successfully")
        } catch (e: Exception) {
            Log.e(TAG, "startScanning EXCEPTION: ${e.message}", e)
            onStatusChanged("BLE Scan Error: ${e.message}")
            isScanning = false
            return
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
