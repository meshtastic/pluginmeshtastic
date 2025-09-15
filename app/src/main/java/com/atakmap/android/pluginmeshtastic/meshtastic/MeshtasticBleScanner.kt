package com.atakmap.android.pluginmeshtastic.meshtastic

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.atakmap.android.maps.MapView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE Scanner for discovering Meshtastic devices
 */
class MeshtasticBleScanner(private val context: Context) {
    companion object {
        private const val TAG = "MeshtasticBleScanner"
        private const val SCAN_TIMEOUT_MS = 30000L // 30 seconds
        
        // Meshtastic BLE Service UUID
        val MESHTASTIC_SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        
        // Known Meshtastic device name patterns
        private val MESHTASTIC_NAME_PATTERNS = listOf(
            "Meshtastic",
            "Mesh",
            "T-Beam",
            "T-BEAM",
            "TTGO",
            "RAK",
            "Heltec",
            "LilyGO"
        )
    }
    
    data class MeshtasticDevice(
        val address: String,
        val name: String?,
        val rssi: Int,
        val lastSeen: Long = System.currentTimeMillis(),
        val isMeshtastic: Boolean = false
    )
    
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter = bluetoothManager?.adapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    
    private val _scanningState = MutableStateFlow(false)
    val scanningState: StateFlow<Boolean> = _scanningState
    
    private val _discoveredDevices = MutableStateFlow<Map<String, MeshtasticDevice>>(emptyMap())
    val discoveredDevices: StateFlow<Map<String, MeshtasticDevice>> = _discoveredDevices
    
    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError
    
    private val deviceMap = ConcurrentHashMap<String, MeshtasticDevice>()
    private var scanJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            handleScanResult(result)
        }
        
        override fun onBatchScanResults(results: List<ScanResult>) {
            super.onBatchScanResults(results)
            results.forEach { handleScanResult(it) }
        }
        
        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "Scan failed with error code: $errorCode")
            _scanError.value = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already in progress"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE not supported"
                else -> "Unknown error: $errorCode"
            }
            stopScan()
        }
    }
    
    /**
     * Check if Bluetooth is enabled
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }
    
    /**
     * Check if required permissions are granted
     */
    fun hasRequiredPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ requires new Bluetooth permissions
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Android 11 and below
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Get list of required permissions
     */
    fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }
    
    /**
     * Start BLE scan for Meshtastic devices
     */
    fun startScan() {
        if (_scanningState.value) {
            Log.w(TAG, "Scan already in progress")
            return
        }
        
        if (!isBluetoothEnabled()) {
            _scanError.value = "Bluetooth is disabled"
            return
        }
        
        if (!hasRequiredPermissions()) {
            _scanError.value = "Missing required permissions"
            return
        }
        
        try {
            bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
            
            if (bluetoothLeScanner == null) {
                _scanError.value = "BLE scanner not available"
                return
            }
            
            // Clear previous results
            deviceMap.clear()
            _discoveredDevices.value = emptyMap()
            _scanError.value = null
            
            // Set up scan filters for Meshtastic service UUID
            val scanFilters = mutableListOf<ScanFilter>()
            
            // Add filter for Meshtastic service UUID
            scanFilters.add(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(MESHTASTIC_SERVICE_UUID))
                    .build()
            )
            
            // Also scan without filter to catch devices that might not advertise the service
            if (scanFilters.isEmpty()) {
                scanFilters.add(ScanFilter.Builder().build())
            }
            
            // Set up scan settings
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()
            
            // Start scan - Use null filters to scan for all devices
            // We'll filter Meshtastic devices in the callback
            Log.i(TAG, "Starting BLE scan for Meshtastic devices")
            bluetoothLeScanner?.startScan(null, scanSettings, scanCallback)
            _scanningState.value = true
            
            // Set timeout for scan
            scanJob?.cancel()
            scanJob = scope.launch {
                delay(SCAN_TIMEOUT_MS)
                if (_scanningState.value) {
                    Log.i(TAG, "Scan timeout reached")
                    stopScan()
                }
            }
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception during scan", e)
            _scanError.value = "Permission denied: ${e.message}"
            stopScan()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting scan", e)
            _scanError.value = "Scan error: ${e.message}"
            stopScan()
        }
    }
    
    /**
     * Stop BLE scan
     */
    fun stopScan() {
        if (!_scanningState.value) {
            return
        }
        
        try {
            Log.i(TAG, "Stopping BLE scan")
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception stopping scan", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan", e)
        }
        
        _scanningState.value = false
        scanJob?.cancel()
        scanJob = null
    }
    
    /**
     * Handle scan result
     */
    private fun handleScanResult(result: ScanResult) {
        val device = result.device
        val rssi = result.rssi
        val scanRecord = result.scanRecord
        
        // Check if device has Meshtastic service UUID
        val hasServiceUuid = scanRecord?.serviceUuids?.any { 
            it.uuid == MESHTASTIC_SERVICE_UUID 
        } == true
        
        // Check if device name matches Meshtastic patterns
        val deviceName = try {
            device.name ?: scanRecord?.deviceName
        } catch (e: SecurityException) {
            "Unknown Device"
        }
        
        val nameMatchesMeshtastic = deviceName?.let { name ->
            MESHTASTIC_NAME_PATTERNS.any { pattern ->
                name.contains(pattern, ignoreCase = true)
            }
        } ?: false
        
        val isMeshtastic = hasServiceUuid || nameMatchesMeshtastic
        
        // Create or update device entry
        val meshtasticDevice = MeshtasticDevice(
            address = device.address,
            name = deviceName ?: "Unknown (${device.address})",
            rssi = rssi,
            isMeshtastic = isMeshtastic
        )
        
        // Only add if it's likely a Meshtastic device or if we're showing all devices
        if (isMeshtastic || deviceName != null) {
            deviceMap[device.address] = meshtasticDevice
            _discoveredDevices.value = deviceMap.toMap()
            
            if (isMeshtastic) {
                Log.d(TAG, "Found Meshtastic device: ${meshtasticDevice.name} (${meshtasticDevice.address}) RSSI: $rssi")
            }
        }
    }
    
    /**
     * Clear discovered devices
     */
    fun clearDevices() {
        deviceMap.clear()
        _discoveredDevices.value = emptyMap()
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        stopScan()
        scope.cancel()
        clearDevices()
    }
}