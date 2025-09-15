package com.atakmap.android.pluginmeshtastic.meshtastic

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import java.util.concurrent.LinkedBlockingQueue

interface RadioCallback {
    fun handleFromRadio(data: ByteArray)
    fun onConnect()  // Called when BLE is connected and ready for config
}

class BluetoothInterface(
    private val context: Context,
    private val deviceAddress: String,
    private val callback: RadioCallback
) {
    companion object {
        private const val TAG = "BluetoothInterface"
        
        // Meshtastic BLE Service and Characteristic UUIDs
        val BTM_SERVICE_UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        val BTM_TORADIO_CHARACTER = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        val BTM_FROMRADIO_CHARACTER = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        val BTM_FROMNUM_CHARACTER = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")
        
        // BLE notification descriptor
        val DESCRIPTOR_CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
    
    private var bluetoothGatt: BluetoothGatt? = null
    private var toRadioCharacteristic: BluetoothGattCharacteristic? = null
    private var fromRadioCharacteristic: BluetoothGattCharacteristic? = null
    private var fromNumCharacteristic: BluetoothGattCharacteristic? = null
    
    // Track if this is the first send to trigger initial read
    private var isFirstSend = true
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState
    
    // RSSI tracking
    private val _rssi = MutableStateFlow(0)
    val rssi: StateFlow<Int> = _rssi
    
    private val writeQueue = LinkedBlockingQueue<ByteArray>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isWriting = false // Track if a write is in progress
    private var isReading = false // Track if a read is in progress
    
    // Flag to track if fromNum has changed (like official app)
    @Volatile
    private var fromNumChanged = false
    
    // Track if we're doing the initial read
    private var isDoingInitialRead = false
    private val writeMutex = kotlinx.coroutines.sync.Mutex() // Prevent concurrent writes
    
    private val pairingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (device?.address == deviceAddress) {
                        val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                        Log.i(TAG, "Bond state changed for $deviceAddress: $bondState")
                        
                        when (bondState) {
                            BluetoothDevice.BOND_BONDED -> {
                                Log.i(TAG, "Pairing successful, connecting to GATT")
                                // After pairing, use direct connection (auto-connect=false)
                                bluetoothGatt = device.connectGatt(context, false, gattCallback)
                            }
                            BluetoothDevice.BOND_NONE -> {
                                Log.w(TAG, "Pairing failed or bond removed")
                                _connectionState.value = ConnectionState.DISCONNECTED
                            }
                        }
                    }
                }
            }
        }
    }
    
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        CONFIGURED
    }
    
    // Track if we need to do MTU negotiation
    private var shouldSetMtu = true
    private var mtuSize = 512 // Default MTU size to request
    
    // Track if device needs service refresh (ESP32 workaround)
    private var needsServiceRefresh = true
    
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.i(TAG, "GATT connection state change: status=$status, newState=$newState")
            
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG, "Connected to GATT server successfully")
                        _connectionState.value = ConnectionState.CONNECTED
                        // Reset retry count on successful connection
                        reconnectAttempts = 0
                        isExplicitlyDisconnecting = false
                        // Reset notification setup state
                        notificationRetryCount = 0
                        
                        // Request MTU before service discovery for better performance
                        if (shouldSetMtu && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                            Log.i(TAG, "Requesting MTU of $mtuSize")
                            gatt?.requestMtu(mtuSize)
                        } else {
                            // If MTU not supported or already set, proceed with service discovery
                            handleServiceRefreshAndDiscovery(gatt)
                        }
                    } else {
                        Log.w(TAG, "Connected to GATT server but with error status: $status")
                        _connectionState.value = ConnectionState.CONNECTED
                        // Reset notification setup state
                        notificationRetryCount = 0
                        
                        // Still try MTU and service discovery even with error status
                        if (shouldSetMtu && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                            gatt?.requestMtu(mtuSize)
                        } else {
                            handleServiceRefreshAndDiscovery(gatt)
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    when (status) {
                        BluetoothGatt.GATT_SUCCESS -> {
                            Log.i(TAG, "Disconnected from GATT server normally")
                        }
                        5 -> { // GATT_INSUFFICIENT_AUTHENTICATION
                            Log.w(TAG, "Disconnected due to authentication failure (status=5)")
                            scope.launch {
                                scheduleReconnect("Authentication failure")
                            }
                            return
                        }
                        133 -> { // Common BLE error, often means connection timeout
                            Log.w(TAG, "Disconnected with status 133 - connection timeout or device not available")
                            // If we were using direct connect, try auto-connect next time
                            scope.launch {
                                scheduleReconnect("Status 133 error")
                            }
                            return
                        }
                        257 -> { // Mystery error when phone BLE stack is hung
                            Log.e(TAG, "BLE stack may be hung (status=257), consider BLE restart")
                        }
                        else -> {
                            Log.w(TAG, "Disconnected from GATT server with error status: $status")
                        }
                    }
                    
                    _connectionState.value = ConnectionState.DISCONNECTED
                    
                    // Only cleanup GATT resources, keep the pairing receiver registered for reconnection
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                    toRadioCharacteristic = null
                    fromRadioCharacteristic = null
                    fromNumCharacteristic = null
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered")
                setupCharacteristics(gatt)
            } else {
                Log.w(TAG, "Service discovery failed: $status")
            }
        }
        
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicRead called - status: $status, characteristic: ${characteristic?.uuid}")
            
            // Always reset the reading flag when read completes
            isReading = false
            
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                Log.d(TAG, "Read success for ${characteristic.uuid}, data size: ${characteristic.value?.size ?: 0}")
                handleCharacteristicData(characteristic)
            } else {
                Log.w(TAG, "Read failed - status: $status")
            }
        }
        
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "MTU changed to $mtu")
                shouldSetMtu = false // Don't try to set MTU again
            } else {
                Log.w(TAG, "MTU change failed with status: $status")
                shouldSetMtu = false // Don't retry on failure
            }
            // Proceed with service discovery after MTU negotiation
            handleServiceRefreshAndDiscovery(gatt)
        }
        
        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "RSSI read: $rssi dBm")
                _rssi.value = rssi
            } else {
                Log.w(TAG, "Failed to read RSSI, status: $status")
            }
        }
        
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            Log.i(TAG, "*** NOTIFICATION *** onCharacteristicChanged - characteristic: ${characteristic?.uuid}")
            characteristic?.let { 
                if (it.uuid == BTM_FROMNUM_CHARACTER) {
                    Log.i(TAG, "*** FROMNUM NOTIFICATION *** fromNum changed, setting flag")
                    // Just set a flag - we'll read in a coroutine like the official app
                    fromNumChanged = true
                    
                    // Launch coroutine to handle the read
                    scope.launch {
                        if (fromNumChanged && !isReading) {
                            fromNumChanged = false
                            Log.i(TAG, "fromNum changed, reading new messages")
                            readFromRadio()
                        }
                    }
                }
            }
        }
        
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            // Clear the writing flag regardless of success/failure
            isWriting = false
            
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Write successful")
                
                // Write successful
                
                // After first write, start reading (like reference app)
                if (isFirstSend) {
                    isFirstSend = false
                    Log.i(TAG, "First write complete, starting to read messages")
                    readFromRadio()
                }
                
                // Continue processing the write queue
                scope.launch {
                    processWriteQueueSafe()
                }
            } else {
                Log.w(TAG, "Write failed with status: $status")
                // Continue with next packet - simpler approach
                scope.launch {
                    processWriteQueueSafe()
                }
            }
        }
        
        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Descriptor write successful")
            } else {
                Log.w(TAG, "Descriptor write failed with status: $status")
            }
            // Continue regardless of status - some devices work without proper descriptor writes
            onNotificationSetupComplete()
        }
    }
    
    private var isPairingReceiverRegistered = false
    private var reconnectAttempts = 0
    private val maxReconnectionAttempts = 6
    private var isExplicitlyDisconnecting = false
    
    fun connect() {
        _connectionState.value = ConnectionState.CONNECTING
        
        // Reset for new connection
        notificationRetryCount = 0
        
        // Reset first send flag on new connection (like Meshtastic-Android)
        isFirstSend = true
        
        // Reset MTU flag for new connection
        shouldSetMtu = true
        
        // Check if device is ESP32 based on address pattern (ESP32 devices often need service refresh)
        // ESP32 addresses often start with specific patterns, but we'll be conservative and refresh for all
        needsServiceRefresh = !deviceAddress.startsWith("FD:10:04") // NRF52 devices don't need refresh
        
        Log.i(TAG, "Starting connection attempt")
        
        // Register broadcast receiver for pairing events if not already registered
        if (!isPairingReceiverRegistered) {
            try {
                val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                context.registerReceiver(pairingReceiver, filter)
                isPairingReceiverRegistered = true
                Log.d(TAG, "Registered pairing broadcast receiver")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register pairing receiver", e)
            }
        }
        
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
        
        // Start with auto-connect=false for faster initial connection (like Meshtastic-Android)
        // Will fall back to auto-connect=true if this fails
        Log.i(TAG, "Device $deviceAddress bond state: ${device.bondState}")
        bluetoothGatt = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }
    
    private suspend fun scheduleReconnect(reason: String) {
        if (isExplicitlyDisconnecting) {
            Log.i(TAG, "Not reconnecting - explicit disconnect requested")
            return
        }
        
        if (reconnectAttempts >= maxReconnectionAttempts) {
            Log.e(TAG, "Max reconnection attempts reached, giving up")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        
        val backoffMillis = (1000 * (1 shl reconnectAttempts.coerceAtMost(6))).toLong()
        reconnectAttempts++
        
        Log.w(TAG, "Scheduling reconnect due to $reason. Attempt $reconnectAttempts/$maxReconnectionAttempts, waiting ${backoffMillis}ms")
        
        // Clean up current connection
        bluetoothGatt?.close()
        bluetoothGatt = null
        toRadioCharacteristic = null
        fromRadioCharacteristic = null
        fromNumCharacteristic = null
        
        kotlinx.coroutines.delay(backoffMillis)
        
        if (bluetoothGatt == null && !isExplicitlyDisconnecting) { // Check if we haven't been closed
            // Use auto-connect for reconnection attempts
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
            bluetoothGatt = device.connectGatt(context, true, gattCallback)
        }
    }
    
    /**
     * Request the current RSSI reading from the connected device
     */
    fun requestRssi(): Boolean {
        val gatt = bluetoothGatt
        return if (gatt != null && (connectionState.value == ConnectionState.CONNECTED || connectionState.value == ConnectionState.CONFIGURED)) {
            val result = gatt.readRemoteRssi()
            if (result) {
                Log.d(TAG, "RSSI read requested successfully")
            } else {
                Log.w(TAG, "Failed to request RSSI read")
            }
            result
        } else {
            Log.w(TAG, "Cannot request RSSI - not connected")
            false
        }
    }
    
    fun disconnect() {
        Log.i(TAG, "Explicitly disconnecting from device")
        // Set flag to indicate this is an explicit disconnect
        isExplicitlyDisconnecting = true
        // Reset retry count for fresh connection attempts later
        reconnectAttempts = 0
        
        bluetoothGatt?.disconnect()
        cleanup()
    }
    
    private fun cleanup() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        toRadioCharacteristic = null
        fromRadioCharacteristic = null
        fromNumCharacteristic = null
        
        // Unregister broadcast receiver only when explicitly disconnecting
        if (isPairingReceiverRegistered) {
            try {
                context.unregisterReceiver(pairingReceiver)
                isPairingReceiverRegistered = false
                Log.d(TAG, "Unregistered pairing broadcast receiver")
            } catch (e: IllegalArgumentException) {
                Log.d(TAG, "Pairing receiver was not registered")
                isPairingReceiverRegistered = false
            }
        }
        
        scope.cancel()
    }
    
    /**
     * Handle service refresh (ESP32 workaround) and discovery
     */
    private fun handleServiceRefreshAndDiscovery(gatt: BluetoothGatt?) {
        scope.launch {
            try {
                if (needsServiceRefresh) {
                    Log.i(TAG, "Performing service refresh for ESP32 compatibility")
                    try {
                        // Use reflection to call the hidden refresh() method
                        val refreshMethod = gatt?.javaClass?.getMethod("refresh")
                        refreshMethod?.invoke(gatt)
                        // Give time for refresh to complete
                        kotlinx.coroutines.delay(500)
                    } catch (e: Exception) {
                        Log.w(TAG, "Service refresh failed, continuing anyway", e)
                    }
                }
                
                Log.i(TAG, "Starting service discovery")
                gatt?.discoverServices()
            } catch (e: Exception) {
                Log.e(TAG, "Error in service discovery", e)
                scheduleReconnect("Service discovery error: ${e.message}")
            }
        }
    }
    
    private fun setupCharacteristics(gatt: BluetoothGatt?) {
        scope.launch {
            try {
                // Add delay like reference app to allow BLE stack to stabilize
                kotlinx.coroutines.delay(1000)
                
                Log.i(TAG, "setupCharacteristics: Looking for service $BTM_SERVICE_UUID")
                val service = gatt?.getService(BTM_SERVICE_UUID)
                if (service == null) {
                    Log.e(TAG, "Meshtastic service not found! Available services:")
                    gatt?.services?.forEach { s ->
                        Log.e(TAG, "  Service: ${s.uuid}")
                    }
                    scheduleReconnect("Service not found")
                    return@launch
                }
                
                Log.i(TAG, "Meshtastic service found, getting characteristics")
                toRadioCharacteristic = service.getCharacteristic(BTM_TORADIO_CHARACTER)
                fromRadioCharacteristic = service.getCharacteristic(BTM_FROMRADIO_CHARACTER)
                fromNumCharacteristic = service.getCharacteristic(BTM_FROMNUM_CHARACTER)
                
                Log.i(TAG, "Characteristics found:")
                Log.i(TAG, "  toRadio: ${toRadioCharacteristic != null}")
                Log.i(TAG, "  fromRadio: ${fromRadioCharacteristic != null}")  
                Log.i(TAG, "  fromNum: ${fromNumCharacteristic != null}")
                
                if (toRadioCharacteristic == null || fromRadioCharacteristic == null || fromNumCharacteristic == null) {
                    Log.e(TAG, "Required characteristics missing")
                    scheduleReconnect("Missing characteristics")
                    return@launch
                }
                
                // DON'T enable notifications yet - will be done after initial read completes
                // This matches the official app behavior
                
                // Only set to CONNECTED if we're not already CONFIGURED
                // (notifications might have already been set up)
                if (_connectionState.value != ConnectionState.CONFIGURED) {
                    _connectionState.value = ConnectionState.CONNECTED
                }
                
                // Notify callback that we're connected and can start config
                callback.onConnect()
                
                // Start initial read to drain any pending messages (like official app)
                Log.i(TAG, "Starting initial read to drain pending messages")
                isDoingInitialRead = true
                readFromRadio()
                
                // Start write queue processor
                processWriteQueueSafe()
            } catch (e: Exception) {
                Log.e(TAG, "Error in setupCharacteristics", e)
                scheduleReconnect("Setup error: ${e.message}")
            }
        }
    }
    
    private var notificationRetryCount = 0
    
    private fun enableNotifications() {
        // NOTE: We enable notifications BEFORE initial read, but they won't fire until
        // after we've drained the queue (matching official app behavior)
        fromNumCharacteristic?.let { characteristic ->
            Log.i(TAG, "Enabling notifications for fromNum: ${characteristic.uuid}")
            
            val notifyEnabled = bluetoothGatt?.setCharacteristicNotification(characteristic, true) ?: false
            Log.i(TAG, "setCharacteristicNotification result: $notifyEnabled")
            
            if (notifyEnabled) {
                val descriptor = characteristic.getDescriptor(DESCRIPTOR_CCC)
                if (descriptor != null) {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    val success = bluetoothGatt?.writeDescriptor(descriptor) ?: false
                    Log.i(TAG, "Write notification descriptor result: $success")
                    // Don't wait for callback - continue with initial read
                } else {
                    Log.w(TAG, "No CCC descriptor found for fromNum")
                }
            } else {
                Log.w(TAG, "Failed to enable notifications for fromNum")
            }
        } ?: run {
            Log.e(TAG, "fromNumCharacteristic is null")
        }
    }
    
    private fun onNotificationSetupComplete() {
        _connectionState.value = ConnectionState.CONFIGURED
        Log.i(TAG, "Notification setup complete, ready for communication")
    }
    
    private fun handleCharacteristicData(characteristic: BluetoothGattCharacteristic) {
        Log.i(TAG, "handleCharacteristicData for ${characteristic.uuid}")
        when (characteristic.uuid) {
            BTM_FROMRADIO_CHARACTER -> {
                val data = characteristic.value
                Log.i(TAG, "FromRadio characteristic data: ${data?.size ?: 0} bytes")
                if (data != null && data.isNotEmpty()) {
                    Log.i(TAG, "Processing FromRadio data: ${data.size} bytes")
                    callback.handleFromRadio(data)
                    
                    // Continue reading until queue is empty
                    readFromRadio()
                } else {
                    Log.d(TAG, "FromRadio queue is empty")
                    
                    if (isDoingInitialRead) {
                        // Initial read complete - NOW enable notifications (like official app)
                        isDoingInitialRead = false
                        Log.i(TAG, "Initial read complete, enabling fromNum notifications")
                        enableNotifications()
                        //_connectionState.value = ConnectionState.CONFIGURED
                    }
                }
            }
            BTM_FROMNUM_CHARACTER -> {
                val data = characteristic.value
                Log.i(TAG, "FromNum characteristic data: ${data?.size ?: 0} bytes")
                if (data != null && data.size >= 4) {
                    val numMessages = data.toInt()
                    Log.i(TAG, "FromNum value: $numMessages messages available")
                    
                    // Start reading messages when notified
                    if (numMessages > 0) {
                        Log.i(TAG, "FromNum indicates $numMessages messages available")
                        readFromRadio()
                    }
                }
            }
        }
    }
    
    private fun readFromRadio() {
        // Allow reading during initial setup (not just CONFIGURED state)
        val canRead = _connectionState.value == ConnectionState.CONFIGURED || isDoingInitialRead
        
        if (isReading || !canRead) {
            Log.d(TAG, "Skipping read - isReading: $isReading, state: ${_connectionState.value}, initialRead: $isDoingInitialRead")
            return
        }
        
        fromRadioCharacteristic?.let { characteristic ->
            isReading = true
            Log.d(TAG, "Reading from radio")
            val success = bluetoothGatt?.readCharacteristic(characteristic) == true
            if (!success) {
                Log.w(TAG, "Failed to initiate read from radio")
                isReading = false
            }
        } ?: run {
            Log.w(TAG, "fromRadioCharacteristic is null")
        }
    }
    
    
    fun sendToRadio(data: ByteArray) {
        if (_connectionState.value != ConnectionState.CONFIGURED) {
            Log.w(TAG, "Not ready to send, state: ${_connectionState.value}")
            return
        }
        
        val queued = writeQueue.offer(data)
        if (!queued) {
            Log.w(TAG, "Write queue full, dropping packet")
            return
        }
        
        scope.launch {
            processWriteQueueSafe()
        }
    }
    
    // Alias for sendToRadio to match the interface expected by MeshtasticManager
    fun sendData(data: ByteArray) = sendToRadio(data)
    
    private suspend fun processWriteQueueSafe() {
        writeMutex.withLock {
            // Allow writes in both CONNECTED and CONFIGURED states to support configuration
            if (isWriting || writeQueue.isEmpty() || 
                (_connectionState.value != ConnectionState.CONFIGURED && _connectionState.value != ConnectionState.CONNECTED)) {
                return@withLock
            }
            
            val data = writeQueue.poll() ?: return@withLock
            
            toRadioCharacteristic?.let { characteristic ->
                isWriting = true
                characteristic.value = data
                val success = bluetoothGatt?.writeCharacteristic(characteristic) == true
                if (success) {
                    Log.d(TAG, "Write initiated for ${data.size} bytes")
                } else {
                    Log.w(TAG, "Write failed, dropping packet")
                    isWriting = false
                }
            } ?: run {
                Log.w(TAG, "toRadioCharacteristic is null")
                isWriting = false
            }
        }
    }
    
    
    private fun ByteArray.toInt(): Int {
        return if (size >= 4) {
            (this[0].toInt() and 0xFF) or
            ((this[1].toInt() and 0xFF) shl 8) or
            ((this[2].toInt() and 0xFF) shl 16) or
            ((this[3].toInt() and 0xFF) shl 24)
        } else {
            0
        }
    }
}