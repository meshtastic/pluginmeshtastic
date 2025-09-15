package com.atakmap.android.pluginmeshtastic.meshtastic

import android.content.Context
import android.widget.Toast
import com.atakmap.android.preference.AtakPreferences
import android.util.Log
import gov.tak.api.plugin.IServiceController
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import com.geeksville.mesh.ATAKProtos
import com.google.protobuf.InvalidProtocolBufferException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/**
 * Bridge between ATAK and Meshtastic for message exchange
 * Handles ATAK Plugin (port 72) and ATAK Forwarder (port 257) messages
 */
class AtakMeshtasticBridge(
    private val context: Context,
    private val serviceController: IServiceController?
) : MeshtasticManager.AtakMessageHandler {
    
    companion object {
        private const val TAG = "AtakMeshtasticBridge"
        
        // ATAK message types
        const val MSG_TYPE_COT = "CoT"  // Cursor on Target
        const val MSG_TYPE_CHAT = "Chat"
        const val MSG_TYPE_FILE = "File"
        const val MSG_TYPE_PLI = "PLI"
        
        // Preference keys for auto-reconnection with plugin prefix to avoid conflicts
        private const val PREF_PREFIX = "plugin.meshtastic."
        private const val PREF_LAST_CONNECTION_TYPE = PREF_PREFIX + "last_connection_type"
        private const val PREF_LAST_BT_ADDRESS = PREF_PREFIX + "last_bluetooth_address"
        private const val PREF_LAST_BT_NAME = PREF_PREFIX + "last_bluetooth_name"
        private const val PREF_LAST_USB_PATH = PREF_PREFIX + "last_usb_path"
        private const val PREF_AUTO_RECONNECT = PREF_PREFIX + "auto_reconnect_enabled"
        
        // Connection types
        private const val CONNECTION_TYPE_BLUETOOTH = "bluetooth"
        private const val CONNECTION_TYPE_USB = "usb"
        
        // Routing error messages for user display
        private val ROUTING_ERROR_MESSAGES = mapOf(
            0 to "Message delivered successfully", // NONE
            1 to "No route to destination", // NO_ROUTE
            2 to "Message was rejected", // GOT_NAK
            3 to "Message delivery timeout", // TIMEOUT
            4 to "No suitable interface found", // NO_INTERFACE
            5 to "Maximum retransmissions reached", // MAX_RETRANSMIT
            6 to "Channel not available", // NO_CHANNEL
            7 to "Message too large", // TOO_LARGE
            8 to "No response from destination", // NO_RESPONSE
            9 to "Duty cycle limit exceeded", // DUTY_CYCLE_LIMIT
            32 to "Invalid message format", // BAD_REQUEST
            33 to "Not authorized for this channel", // NOT_AUTHORIZED
            34 to "PKI encryption failed", // PKI_FAILED
            35 to "Unknown public key", // PKI_UNKNOWN_PUBKEY
            36 to "Invalid admin session key", // ADMIN_BAD_SESSION_KEY
            37 to "Admin key not authorized", // ADMIN_PUBLIC_KEY_UNAUTHORIZED
            38 to "Rate limit exceeded" // RATE_LIMIT_EXCEEDED
        )
        
        private fun getRoutingErrorMessage(errorCode: Int): String {
            return ROUTING_ERROR_MESSAGES[errorCode] ?: "Unknown error (code: $errorCode)"
        }
    }
    
    private var meshtasticManager: MeshtasticManager? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Preferences for storing connection information using ATAK's preference system
    private val preferences: AtakPreferences by lazy {
        AtakPreferences.getInstance(context)
    }
    
    // Chunk reassembly state - only one active session at a time
    data class ChunkSession(
        val totalChunks: Int,
        val totalSize: Int,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
        val startTime: Long = System.currentTimeMillis()
    )
    
    private var activeChunkSession: ChunkSession? = null
    private val CHUNK_SESSION_TIMEOUT = 60000L // 60 seconds timeout for chunk sessions
    
    // Message statistics
    private var messagesSentSuccessfully = 0
    private var messagesSentFailed = 0
    
    /**
     * Get message statistics for debugging
     */
    fun getMessageStats(): Pair<Int, Int> {
        return Pair(messagesSentSuccessfully, messagesSentFailed)
    }
    
    /**
     * Get message statistics including received count
     */
    fun getDetailedMessageStats(): Triple<Int, Int, Int> {
        val messagesReceived = meshtasticManager?.getMessagesReceivedCount() ?: 0
        return Triple(messagesSentSuccessfully, messagesSentFailed, messagesReceived)
    }
    
    // Message handlers - Using Java-compatible interfaces
    interface CoTHandler {
        fun handleCoT(data: ByteArray, fromNode: String?)
    }
    
    interface ChatHandler {
        fun handleChat(
            message: String,
            to: String?,
            toCallsign: String,
            contact: ATAKProtos.Contact,
            fromNode: String?
        )
    }
    
    interface PluginDataHandler {
        fun handlePluginData(data: ByteArray, fromNode: String?)
    }
    
    interface TakPacketHandler {
        fun handleTakPacket(packet: ATAKProtos.TAKPacket, fromNode: String?)
    }
    
    interface AtakForwarderHandler {
        fun handleAtakForwarder(data: ByteArray, fromNode: String?)
    }
    
    private val cotHandlers = mutableListOf<CoTHandler>()
    private val chatHandlers = mutableListOf<ChatHandler>()
    private val pluginHandlers = mutableListOf<PluginDataHandler>()
    private val takPacketHandlers = mutableListOf<TakPacketHandler>()
    private val atakForwarderHandlers = mutableListOf<AtakForwarderHandler>()
    
    // Connection settings
    private var autoConnectBluetooth = false
    private var bluetoothAddress: String? = null
    private var autoConnectUsb = false
    
    // Message cache to prevent duplicates
    private val messageCache = ConcurrentHashMap<String, Long>()
    private val MESSAGE_CACHE_TIMEOUT = 30000L // 30 seconds
    
    // Reference to DropDownReceiver for UI updates
    private var dropDownReceiver: Any? = null
    
    /**
     * Initialize the bridge and start the Meshtastic manager
     */
    fun initialize() {
        Log.i(TAG, "Initializing ATAK-Meshtastic bridge")
        
        // Create and initialize the manager
        meshtasticManager = MeshtasticManager(context)
        meshtasticManager?.registerAtakMessageHandler(this)
        
        // Set up callback to update device name when NodeInfo is received
        meshtasticManager?.setDeviceNameUpdateCallback { longName ->
            Log.i(TAG, "NodeInfo callback triggered with longName: '$longName'")
            updateStoredDeviceName(longName)
        }
        
        // Auto-connect if configured
        if (autoConnectBluetooth && bluetoothAddress != null) {
            connectBluetooth(bluetoothAddress!!)
        } else if (autoConnectUsb) {
            connectUsb()
        }
        
        // Monitor connection state
        monitorConnectionState()
    }
    
    /**
     * Store the last successful connection information
     */
    private fun saveConnectionInfo(type: String, address: String, name: String? = null) {
        preferences.set(PREF_LAST_CONNECTION_TYPE, type)
        when (type) {
            CONNECTION_TYPE_BLUETOOTH -> {
                preferences.set(PREF_LAST_BT_ADDRESS, address)
                preferences.set(PREF_LAST_BT_NAME, name ?: address)
            }
            CONNECTION_TYPE_USB -> {
                preferences.set(PREF_LAST_USB_PATH, address)
            }
        }
        preferences.set(PREF_AUTO_RECONNECT, true)
        Log.i(TAG, "Saved connection info: type=$type, address=$address")
    }
    
    /**
     * Set the DropDownReceiver reference for UI updates
     */
    fun setDropDownReceiver(receiver: Any) {
        dropDownReceiver = receiver
    }
    
    /**
     * Update stored device name when NodeInfo is received
     */
    private fun updateStoredDeviceName(longName: String) {
        Log.i(TAG, "Updating stored device name to: '$longName', bluetoothAddress: '$bluetoothAddress'")
        
        // Update the stored name for the current Bluetooth connection if available
        if (bluetoothAddress != null) {
            val prefKey = "plugin.meshtastic.last_device_name_$bluetoothAddress"
            preferences.set(prefKey, longName)
            Log.i(TAG, "Updated preference: $prefKey = $longName")
            
            // Notify the UI to refresh and show the updated name
            notifyDeviceNameUpdated()
        } else {
            Log.w(TAG, "bluetoothAddress is null, cannot update stored device name")
        }
    }
    
    /**
     * Notify the DropDownReceiver that the device name has been updated
     */
    private fun notifyDeviceNameUpdated() {
        dropDownReceiver?.let { receiver ->
            try {
                val method = receiver::class.java.getMethod("onDeviceNameUpdated")
                method.invoke(receiver)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to notify DropDownReceiver of device name update", e)
            }
        }
    }
    
    /**
     * Attempt to reconnect to the last known Meshtastic device
     */
    fun attemptAutoReconnect() {
        if (!preferences.get(PREF_AUTO_RECONNECT, false)) {
            Log.d(TAG, "Auto-reconnect is disabled")
            return
        }
        
        val lastConnectionType = preferences.get(PREF_LAST_CONNECTION_TYPE, null as String?)
        
        Log.i(TAG, "Attempting auto-reconnect to last device: $lastConnectionType")
        
        scope.launch {
            when (lastConnectionType) {
                CONNECTION_TYPE_BLUETOOTH -> {
                    val address = preferences.get(PREF_LAST_BT_ADDRESS, null as String?)
                    val name = preferences.get(PREF_LAST_BT_NAME, null as String?)
                    
                    if (address != null) {
                        Log.i(TAG, "Auto-reconnecting to Bluetooth device: $name ($address)")
                        try {
                            connectBluetooth(address)
                            Log.i(TAG, "Auto-reconnect to Bluetooth device initiated")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to auto-reconnect to Bluetooth device", e)
                            // Clear stored connection if auto-reconnect fails
                            clearStoredConnection()
                        }
                    } else {
                        Log.w(TAG, "No previous Bluetooth address found")
                    }
                }
                
                CONNECTION_TYPE_USB -> {
                    val usbPath = preferences.get(PREF_LAST_USB_PATH, null as String?)
                    
                    if (usbPath != null) {
                        Log.i(TAG, "Auto-reconnecting to USB device: $usbPath")
                        try {
                            connectUsb(usbPath)
                            Log.i(TAG, "Auto-reconnect to USB device initiated")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to auto-reconnect to USB device", e)
                        }
                    } else {
                        Log.w(TAG, "No previous USB path found")
                    }
                }
                
                else -> {
                    Log.i(TAG, "No previous connection found, skipping auto-reconnect")
                }
            }
        }
    }
    
    /**
     * Enable or disable auto-reconnection
     */
    fun setAutoReconnectEnabled(enabled: Boolean) {
        preferences.set(PREF_AUTO_RECONNECT, enabled)
        Log.i(TAG, "Auto-reconnect ${if (enabled) "enabled" else "disabled"}")
    }
    
    /**
     * Apply TAK optimized configuration to the device
     */
    fun applyTakConfiguration(callback: (Boolean) -> Unit) {
        if (meshtasticManager?.connectionState?.value != MeshtasticManager.ConnectionState.CONFIGURED) {
            Log.w(TAG, "Cannot apply TAK configuration - device not configured")
            callback(false)
            return
        }
        
        Log.i(TAG, "Applying TAK optimized configuration")
        meshtasticManager?.applyTakOptimizedConfiguration { success ->
            if (success) {
                Log.i(TAG, "TAK configuration applied successfully")
            } else {
                Log.e(TAG, "Failed to apply TAK configuration")
            }
            callback(success)
        } ?: callback(false)
    }
    
    /**
     * Get current configuration from the device
     */
    fun getCurrentConfiguration(callback: (String?) -> Unit) {
        if (meshtasticManager?.connectionState?.value != MeshtasticManager.ConnectionState.CONFIGURED) {
            Log.w(TAG, "Cannot get configuration - device not configured")
            callback(null)
            return
        }
        
        Log.i(TAG, "Requesting current device configuration")
        meshtasticManager?.getCurrentDeviceConfiguration { config ->
            callback(config)
        } ?: callback(null)
    }
    
    /**
     * Clear stored connection information
     */
    fun clearStoredConnection() {
        preferences.remove(PREF_LAST_CONNECTION_TYPE)
        preferences.remove(PREF_LAST_BT_ADDRESS)
        preferences.remove(PREF_LAST_BT_NAME)
        preferences.remove(PREF_LAST_USB_PATH)
        preferences.remove(PREF_AUTO_RECONNECT)
        Log.i(TAG, "Cleared stored connection information")
    }
    
    /**
     * Get information about the last connected device
     */
    fun getLastConnectionInfo(): String? {
        val type = preferences.get(PREF_LAST_CONNECTION_TYPE, null as String?)
        return when (type) {
            CONNECTION_TYPE_BLUETOOTH -> {
                val name = preferences.get(PREF_LAST_BT_NAME, null as String?)
                val address = preferences.get(PREF_LAST_BT_ADDRESS, null as String?)
                "Bluetooth: $name ($address)"
            }
            CONNECTION_TYPE_USB -> {
                val path = preferences.get(PREF_LAST_USB_PATH, null as String?)
                "USB: $path"
            }
            else -> null
        }
    }

    /**
     * Shutdown the bridge and cleanup
     */
    fun shutdown() {
        Log.i(TAG, "Shutting down ATAK-Meshtastic bridge")
        
        meshtasticManager?.unregisterAtakMessageHandler(this)
        meshtasticManager?.cleanup()
        meshtasticManager = null
        
        scope.cancel()
    }
    
    /**
     * Connect to Meshtastic device via Bluetooth
     */
    fun connectBluetooth(address: String) {
        bluetoothAddress = address
        autoConnectBluetooth = true
        autoConnectUsb = false
        
        meshtasticManager?.connectBluetooth(address)
    }
    
    /**
     * Connect to Meshtastic device via USB
     */
    fun connectUsb(devicePath: String = "") {
        autoConnectUsb = true
        autoConnectBluetooth = false
        
        meshtasticManager?.connectUsb(devicePath)
    }
    
    /**
     * Disconnect from Meshtastic device
     */
    fun disconnect() {
        autoConnectBluetooth = false
        autoConnectUsb = false
        meshtasticManager?.disconnect()
    }
    
    /**
     * Set the channel password (PSK) on the connected device
     */
    fun setChannelPassword(password: String) {
        meshtasticManager?.setChannelPassword(password)
    }
    
    /**
     * Check if a PSK is configured for secure communication
     */
    private fun isPskConfigured(): Boolean {
        val preferences = AtakPreferences.getInstance(context)
        val password = preferences.get("plugin.meshtastic.channel_password", "")
        return password.isNotEmpty()
    }
    
    /**
     * Send raw ATAK plugin message (protobuf format) with retry on failure
     */
    // Main Kotlin function
    fun sendAtakPluginMessage(data: ByteArray, destNode: UInt? = null, onAck: ((Boolean) -> Unit)? = null) {
        sendAtakPluginMessageHelper(data, destNode, onAck)
    }
    
    // Java-compatible overloads
    fun sendAtakPluginMessage(data: ByteArray, onAck: ((Boolean) -> Unit)?) {
        sendAtakPluginMessageHelper(data, null, onAck)
    }
    
    // Java-compatible 3-parameter overload (destNode as nullable Integer)
    fun sendAtakPluginMessage(data: ByteArray, destNodeInt: Int?, onAck: ((Boolean) -> Unit)?) {
        val destNode = destNodeInt?.toUInt()
        sendAtakPluginMessageHelper(data, destNode, onAck)
    }
    
    private fun sendAtakPluginMessageHelper(data: ByteArray, destNode: UInt?, onAck: ((Boolean) -> Unit)?) {
        // Check if PSK is configured before sending messages
        if (!isPskConfigured()) {
            val warningMessage = "Meshtastic node not ready: Please configure a channel password in Settings before sending messages"
            scope.launch(Dispatchers.Main) {
                Toast.makeText(context, warningMessage, Toast.LENGTH_LONG).show()
            }
            Log.w(TAG, "Attempted to send ATAK plugin message without PSK configured")
            onAck?.invoke(false)
            return
        }
        
        sendAtakPluginMessageInternal(data, destNode, onAck, isRetry = false)
    }
    
    private fun sendAtakPluginMessageInternal(data: ByteArray, destNode: UInt? = null, onAck: ((Boolean) -> Unit)? = null, isRetry: Boolean) {
        // Enhanced callback that handles both Toast notifications and original callback
        val enhancedCallback: (AckResult) -> Unit = { result ->
            if (!isRetry && !result.success && onAck != null) {
                // Only retry on first attempt
                Log.w(TAG, "ATAK Plugin message failed: ${result.errorReason}, retrying once...")
                messagesSentFailed++
                
                // Retry once after a delay
                scope.launch {
                    delay(1000) // Wait 1 second before retry
                    Log.i(TAG, "Retrying ATAK Plugin message...")
                    // Call internal method with isRetry=true to prevent infinite retries
                    sendAtakPluginMessageInternal(data, destNode, onAck, isRetry = true)
                }
            } else {
                // Final result (success or retry failure)
                if (result.success) {
                    Log.i(TAG, "ATAK Plugin message acknowledged ${if (isRetry) "on retry" else "successfully"}")
                    messagesSentSuccessfully++
                    onAck?.invoke(true)
                } else {
                    Log.e(TAG, "ATAK Plugin message failed ${if (isRetry) "permanently after retry" else ""}: ${result.errorReason}")
                    messagesSentFailed++
                    onAck?.invoke(false)
                }
            }
        }
        
        // Send directly on port 72 (ATAK_PLUGIN) using protobuf format with enhanced callback
        meshtasticManager?.sendAtakPluginMessageWithErrorDetails(data, destNode, enhancedCallback)
        
        // Log hex dump for debugging
        val hexString = data.joinToString("") { "%02x".format(it) }
        Log.d(TAG, "Sent ATAK protobuf message: ${data.size} bytes")
        Log.d(TAG, "Sent data hex: $hexString")
    }
    
    /**
     * Send raw ATAK forwarder message (for chunked/compressed data) with retry on failure
     */
    // Main Kotlin function  
    fun sendAtakForwarderMessage(data: ByteArray, destNode: UInt? = null, onAck: ((Boolean) -> Unit)? = null) {
        sendAtakForwarderMessageHelper(data, destNode, onAck, showToast = true)
    }
    
    // Java-compatible overloads
    fun sendAtakForwarderMessage(data: ByteArray, onAck: ((Boolean) -> Unit)?) {
        sendAtakForwarderMessageHelper(data, null, onAck, showToast = true)
    }
    
    // Java-compatible 3-parameter overload (destNode as nullable Integer)
    fun sendAtakForwarderMessage(data: ByteArray, destNodeInt: Int?, onAck: ((Boolean) -> Unit)?) {
        val destNode = destNodeInt?.toUInt()
        sendAtakForwarderMessageHelper(data, destNode, onAck, showToast = true)
    }
    
    // Overload with Toast control for chunked messages
    fun sendAtakForwarderMessage(data: ByteArray, destNode: UInt? = null, onAck: ((Boolean) -> Unit)? = null, showToast: Boolean = true) {
        sendAtakForwarderMessageHelper(data, destNode, onAck, showToast)
    }
    
    // Java-compatible method with Toast control
    fun sendAtakForwarderMessageNoToast(data: ByteArray, destNodeInt: Int?, onAck: ((Boolean) -> Unit)?) {
        val destNode = destNodeInt?.toUInt()
        sendAtakForwarderMessageHelper(data, destNode, onAck, showToast = false)
    }
    
    private fun sendAtakForwarderMessageHelper(data: ByteArray, destNode: UInt?, onAck: ((Boolean) -> Unit)?, showToast: Boolean = true) {
        // Check if PSK is configured before sending messages
        if (!isPskConfigured()) {
            val warningMessage = "Meshtastic node not ready: Please configure a channel password in Settings before sending messages"
            scope.launch(Dispatchers.Main) {
                Toast.makeText(context, warningMessage, Toast.LENGTH_LONG).show()
            }
            Log.w(TAG, "Attempted to send ATAK message without PSK configured")
            onAck?.invoke(false)
            return
        }
        
        sendAtakForwarderMessageInternal(data, destNode, onAck, isRetry = false, showToast = showToast)
    }
    
    /**
     * Show Toast notification for AtakForwarder message result
     */
    private fun showAtakForwarderToast(result: AckResult, dataSize: Int, destNode: UInt?) {
        val destination = destNode?.toString() ?: "broadcast"
        val message = if (result.success) {
            "ATAK message sent successfully ($dataSize bytes to $destination)"
        } else {
            "ATAK message failed: ${result.errorReason} ($dataSize bytes to $destination)"
        }
        
        scope.launch(Dispatchers.Main) {
            Toast.makeText(context, message, if (result.success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * Show Toast notification for chunked transmission result - Java accessible
     */
    fun showChunkedTransmissionToast(success: Boolean, totalChunks: Int, totalSize: Int, destNodeInt: Int?) {
        val destination = destNodeInt?.toString() ?: "broadcast"
        val message = if (success) {
            "Chunked ATAK message sent successfully ($totalChunks chunks, $totalSize bytes to $destination)"
        } else {
            "Chunked ATAK message failed ($totalChunks chunks, $totalSize bytes to $destination)"
        }
        
        scope.launch(Dispatchers.Main) {
            Toast.makeText(context, message, if (success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
        }
    }
    
    private fun sendAtakForwarderMessageInternal(data: ByteArray, destNode: UInt? = null, onAck: ((Boolean) -> Unit)? = null, isRetry: Boolean, showToast: Boolean = true) {
        // Enhanced callback that handles both Toast notifications and original callback
        val enhancedCallback: (AckResult) -> Unit = { result ->
            // Show Toast notification only if requested (not for individual chunks)
            if (showToast) {
                showAtakForwarderToast(result, data.size, destNode)
            }
            
            if (!isRetry && !result.success && onAck != null) {
                // Only retry on first attempt
                Log.w(TAG, "ATAK Forwarder message failed: ${result.errorReason}, retrying once...")
                messagesSentFailed++
                
                // Retry once after a delay
                scope.launch {
                    delay(1000) // Wait 1 second before retry
                    Log.i(TAG, "Retrying ATAK Forwarder message...")
                    // Call internal method with isRetry=true to prevent infinite retries
                    sendAtakForwarderMessageInternal(data, destNode, onAck, isRetry = true, showToast = showToast)
                }
            } else {
                // Final result (success or retry failure)
                if (result.success) {
                    Log.i(TAG, "ATAK Forwarder message acknowledged ${if (isRetry) "on retry" else "successfully"}")
                    messagesSentSuccessfully++
                    onAck?.invoke(true)
                } else {
                    Log.e(TAG, "ATAK Forwarder message failed ${if (isRetry) "permanently after retry" else ""}: ${result.errorReason}")
                    messagesSentFailed++
                    onAck?.invoke(false)
                }
            }
        }
        
        // Send on port 257 (ATAK_FORWARDER) for chunked/compressed data with enhanced callback
        meshtasticManager?.sendAtakForwarderMessageWithErrorDetails(data, destNode, enhancedCallback)
        
        Log.d(TAG, "Sent ATAK forwarder message: ${data.size} bytes")
    }
    
    /**
     * Send ATAK Forwarder message in chunks using queue awareness
     * This method chunks the data and uses queue status to send multiple chunks efficiently
     */
    fun sendAtakForwarderChunked(
        data: ByteArray,
        destNode: UInt? = null,
        chunkSize: Int = 180,
        requestId: String? = null,
        onProgress: ((Int, Int, String) -> Unit)? = null,
        onComplete: ((Boolean, String) -> Unit)? = null
    ): String {
        val actualRequestId = requestId ?: java.util.UUID.randomUUID().toString()
        
        Log.i(TAG, "Starting queue-aware chunked send: ${data.size} bytes, requestId: $actualRequestId")
        
        if (meshtasticManager?.connectionState?.value != MeshtasticManager.ConnectionState.CONFIGURED) {
            Log.w(TAG, "Cannot send chunks - device not configured")
            onComplete?.invoke(false, actualRequestId)
            return actualRequestId
        }
        
        // Divide data into raw chunks (no headers - Java side will add them)
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < data.size) {
            val remaining = data.size - offset
            val currentChunkSize = minOf(chunkSize, remaining)
            val chunk = ByteArray(currentChunkSize)
            System.arraycopy(data, offset, chunk, 0, currentChunkSize)
            
            chunks.add(chunk)  // Raw chunk data only
            offset += currentChunkSize
        }
        
        Log.i(TAG, "Created ${chunks.size} raw chunks for transmission")
        
        // Send using MeshtasticManager's queue-aware chunking
        meshtasticManager?.sendAtakForwarderChunked(
            chunks = chunks,
            destNode = destNode,
            requestId = actualRequestId,
            onChunkAck = { chunkIndex, totalChunks, success, reqId ->
                Log.i(TAG, "Chunk ${chunkIndex + 1}/$totalChunks ${if (success) "ACK'd" else "failed"} (requestId: $reqId)")
                onProgress?.invoke(chunkIndex + 1, totalChunks, reqId)
            },
            onComplete = { allSuccess, reqId ->
                Log.i(TAG, "Chunked transmission ${if (allSuccess) "completed successfully" else "failed"} (requestId: $reqId)")
                onComplete?.invoke(allSuccess, reqId)
            }
        )
        
        return actualRequestId
    }
    
    /**
     * Java-compatible version of sendAtakForwarderChunked
     */
    fun sendAtakForwarderChunkedJava(
        data: ByteArray,
        destNode: UInt?,
        chunkSize: Int,
        requestId: String?,
        onProgress: kotlin.jvm.functions.Function3<Int, Int, String, kotlin.Unit>?,
        onComplete: kotlin.jvm.functions.Function2<Boolean, String, kotlin.Unit>?
    ): String {
        return sendAtakForwarderChunked(
            data = data,
            destNode = destNode,
            chunkSize = chunkSize,
            requestId = requestId,
            onProgress = onProgress?.let { { index, total, reqId -> it.invoke(index, total, reqId) } },
            onComplete = onComplete?.let { { success, reqId -> it.invoke(success, reqId) } }
        )
    }
    
    /**
     * Get current queue status (free slots, total capacity)
     */
    fun getQueueStatus(): Pair<UInt, UInt> {
        return meshtasticManager?.getQueueInfo() ?: Pair(0u, 0u)
    }
    
    /**
     * Get available queue slots
     */
    fun getAvailableQueueSlots(): UInt {
        return meshtasticManager?.availableQueueSlots?.value ?: 0u
    }
    
    /**
     * Get available queue slots - Java compatible version
     */
    @JvmName("getAvailableQueueSlotsLong")
    fun getAvailableQueueSlotsLong(): Long {
        return (meshtasticManager?.availableQueueSlots?.value ?: 0u).toLong()
    }
    
    /**
     * Request current queue status from device
     */
    fun requestQueueStatus() {
        meshtasticManager?.requestQueueStatus()
    }
    
    /**
     * Register CoT message handler
     */
    fun registerCoTHandler(handler: CoTHandler) {
        cotHandlers.add(handler)
    }
    
    /**
     * Register chat message handler
     */
    fun registerChatHandler(handler: ChatHandler) {
        chatHandlers.add(handler)
    }
    
    /**
     * Register plugin message handler
     */
    fun registerPluginHandler(handler: PluginDataHandler) {
        pluginHandlers.add(handler)
    }
    
    /**
     * Register ATAK Forwarder message handler
     */
    fun registerAtakForwarderHandler(handler: AtakForwarderHandler) {
        atakForwarderHandlers.add(handler)
    }
    
    /**
     * Get connection state
     */
    fun getConnectionState(): MeshtasticManager.ConnectionState {
        return meshtasticManager?.connectionState?.value 
            ?: MeshtasticManager.ConnectionState.DISCONNECTED
    }
    
    /**
     * Get list of known nodes
     */
    fun getNodes(): List<MeshProtos.NodeInfo> {
        return meshtasticManager?.getNodes() ?: emptyList()
    }
    
    fun getDeviceMetadata(): com.geeksville.mesh.MeshProtos.DeviceMetadata? {
        return meshtasticManager?.getDeviceMetadata()
    }
    
    fun getMyNodeInfo(): MeshProtos.MyNodeInfo? {
        return meshtasticManager?.getMyNodeInfo()
    }
    
    fun getLoraConfig(): com.geeksville.mesh.ConfigProtos.Config.LoRaConfig? {
        return meshtasticManager?.getLoraConfig()
    }
    
    /**
     * Get current RSSI value from Bluetooth connection
     */
    fun getCurrentRssi(): Int {
        return meshtasticManager?.getCurrentRssi() ?: 0
    }
    
    /**
     * Request a fresh RSSI reading from the Bluetooth connection
     */
    fun requestRssi(): Boolean {
        return meshtasticManager?.requestRssi() ?: false
    }
    
    
    @JvmName("getMyNodeIdHex")
    fun getMyNodeIdHex(): String? {
        if (meshtasticManager == null) {
            return null
        }
        
        val nodeInfo = meshtasticManager?.getMyNodeInfo()
        
        return if (nodeInfo != null) {
            try {
                // Use the same format as the log message: 0x + hex (no leading zeros padding)
                val hexValue = "0x${nodeInfo.myNodeNum.toString(16)}"
                hexValue
            } catch (e: Exception) {
                Log.e(TAG, "Error formatting node ID: ${e.message}")
                null
            }
        } else {
            null
        }
    }
    
    /**
     * Get the current device's long name for display purposes
     * Note: This may return null if NodeInfo hasn't been received yet
     */
    fun getMyNodeLongName(): String? {
        return meshtasticManager?.let { manager ->
            val myNodeInfo = manager.getMyNodeInfo()
            if (myNodeInfo != null) {
                val nodeKey = myNodeInfo.myNodeNum.toString()
                // Get the current device's NodeInfo from the database using the myNodeNum
                val nodeInfo = manager.getNodeInfo(nodeKey)
                nodeInfo?.longName
            } else {
                null
            }
        }
    }
    
    /**
     * Handle incoming ATAK Plugin message (port 72)
     */
    override fun onAtakPluginMessage(data: ByteArray, fromNode: String?) {
        Log.i(TAG, "Received ATAK Plugin message from $fromNode: ${data.size} bytes")
        
        // Log hex dump for debugging
        val hexString = data.joinToString("") { "%02x".format(it) }
        Log.d(TAG, "Received data hex: $hexString")
        
        // Also log first few bytes as ASCII to check for any obvious issues
        val asciiPreview = data.take(50).map { 
            if (it in 32..126) it.toInt().toChar() else '.' 
        }.joinToString("")
        Log.d(TAG, "Received data ASCII preview: $asciiPreview")
        
        // Check first few bytes to see if this looks like a protobuf
        if (data.size >= 2) {
            val firstByte = data[0].toInt() and 0xFF
            val secondByte = data[1].toInt() and 0xFF
            Log.d(TAG, "First two bytes: 0x%02x 0x%02x".format(firstByte, secondByte))
            
            // Protobuf messages typically start with a field tag (field_number << 3 | wire_type)
            // For TAKPacket, common first fields would be:
            // - is_compressed (field 1, type bool/varint) = 0x08
            // - contact (field 2, type message) = 0x12
            // - group (field 3, type message) = 0x1a
            // - status (field 4, type message) = 0x22
            // - pli (field 5, type message) = 0x2a
            
            val expectedStarts = listOf(0x08, 0x12, 0x1a, 0x22, 0x2a, 0x32, 0x3a, 0x42)
            if (firstByte !in expectedStarts) {
                Log.w(TAG, "WARNING: First byte 0x%02x doesn't match expected TAKPacket field tags".format(firstByte))
                Log.w(TAG, "Expected one of: ${expectedStarts.joinToString { "0x%02x".format(it) }}")
            }
        }

        // Check for duplicate
        if (isDuplicateMessage(data, fromNode)) {
            Log.d(TAG, "Ignoring duplicate message")
            return
        }

        try {
            // Parse as ATAK protobuf TAKPacket
            val takPacket = ATAKProtos.TAKPacket.parseFrom(data)
            Log.d(TAG, "Successfully parsed TAKPacket")
            
            // Handle TAKPacket with specific handlers
            //takPacketHandlers.forEach { it.handleTakPacket(takPacket, fromNode) }

            // Handle specific payload types for backward compatibility
            when {
                takPacket.hasPli() -> {
                    Log.d(TAG, "Processing PLI (Position Location Information)")
                    handlePliMessage(
                        takPacket.pli, 
                        takPacket.contact,
                        if (takPacket.hasGroup()) takPacket.group else null,
                        if (takPacket.hasStatus()) takPacket.status else null,
                        fromNode
                    )
                }
                takPacket.hasChat() -> {
                    Log.d(TAG, "Processing GeoChat message")
                    val message = takPacket.chat.message
                    val to = takPacket.chat.to
                    val toCallsign = takPacket.chat.toCallsign

                    chatHandlers.forEach { it.handleChat(message, to, toCallsign, takPacket.contact, fromNode) }
                }
                !takPacket.detail.isEmpty -> {
                    Log.d(TAG, "Processing CoT detail XML")
                    val detailData = if (takPacket.isCompressed) {
                        // Decompress the detail payload
                        val decompressed = decompressData(takPacket.detail.toByteArray())
                        if (decompressed != null) {
                            Log.d(TAG, "Decompressed detail from ${takPacket.detail.size()} to ${decompressed.size} bytes")
                            decompressed
                        } else {
                            Log.w(TAG, "Failed to decompress detail, using raw data")
                            takPacket.detail.toByteArray()
                        }
                    } else {
                        takPacket.detail.toByteArray()
                    }
                    cotHandlers.forEach { it.handleCoT(detailData, fromNode) }
                }
                else -> {
                    Log.d(TAG, "TAKPacket has no recognizable payload")
                }
            }

        } catch (e: InvalidProtocolBufferException) {
            Log.w(TAG, "Failed to parse as TAKPacket protobuf", e)
        }
    }
    
    /**
     * Handle PLI (Position Location Information) from TAKPacket with Group and Status
     */
    private fun handlePliMessage(
        pli: ATAKProtos.PLI, 
        contact: ATAKProtos.Contact?, 
        group: ATAKProtos.Group?,
        status: ATAKProtos.Status?,
        fromNode: String?
    ) {
        Log.d(TAG, "PLI from ${contact?.callsign ?: fromNode}: lat=${pli.latitudeI / 1e7}, lon=${pli.longitudeI / 1e7}, alt=${pli.altitude}m")
        
        // Convert PLI to CoT XML format for ATAK integration
        val lat = pli.latitudeI / 1e7
        val lon = pli.longitudeI / 1e7
        val callsign = contact?.callsign ?: "Meshtastic-$fromNode"
        val uid = contact?.deviceCallsign ?: "UID-$fromNode"
        
        // Map team colors and roles from protobuf enums
        val teamName = group?.team?.name ?: "Cyan"
        val roleName = group?.role?.name ?: "Team Member"
        val batteryLevel = status?.battery ?: 100
        
        val cotXml = """<?xml version="1.0"?>
            <event version="2.0" uid="$uid" type="a-f-G-U-C" time="" start="" stale="" how="h-e">
                <point lat="$lat" lon="$lon" hae="${pli.altitude}" le="9999999.0" ce="9999999.0"/>
                <detail>
                    <contact endpoint="*:-1:stcp" phone="" callsign="$callsign"/>
                    <__group name="$teamName" role="$roleName"/>
                    <status battery="$batteryLevel"/>
                    <track speed="${pli.speed}" course="${pli.course}"/>
                </detail>
            </event>""".trimIndent()
        
        // Send to CoT handlers
        cotHandlers.forEach { it.handleCoT(cotXml.toByteArray(), fromNode) }
    }
    
    /**
     * Handle incoming ATAK Forwarder message (port 257) - Chunked CoTs
     */
    override fun onAtakForwarderMessage(data: ByteArray, fromNode: String?) {
        try {
            // Check if this is a text-based control message (CHK_, END) or binary chunk data
            // Control messages are small and start with ASCII characters
            val isControlMessage = data.size < 50 && 
                (data.size >= 4 && data[0] == 'C'.toByte() && data[1] == 'H'.toByte() && data[2] == 'K'.toByte() && data[3] == '_'.toByte()) ||
                (data.size == 3 && data[0] == 'E'.toByte() && data[1] == 'N'.toByte() && data[2] == 'D'.toByte())
            
            if (isControlMessage) {
                // Safe to convert control messages to string
                val message = String(data, StandardCharsets.UTF_8)
                Log.d(TAG, "ATAK Forwarder control message from $fromNode: $message")
                Log.i(TAG, "=== CHUNK DEBUG: Control message received ===")
                
                when {
                    // Initial chunk header: CHK_totalChunks_totalSize
                    message.startsWith("CHK_") -> {
                        Log.i(TAG, "=== CHUNK DEBUG: Processing CHK header: $message ===")
                        val parts = message.substring(4).split("_")
                        if (parts.size == 2) {
                            val totalChunks = parts[0].toInt()
                            val totalSize = parts[1].toInt()
                            
                            // Check and clean up old session if it exists
                            cleanupOldSession()
                            
                            // Create new chunk session
                            activeChunkSession = ChunkSession(totalChunks, totalSize)
                            
                            Log.i(TAG, "Started chunk session: $totalChunks chunks, $totalSize bytes total")
                        }
                    }
                    
                    // END marker
                    message == "END" -> {
                    Log.i(TAG, "=== CHUNK DEBUG: Processing END marker ===")
                    val session = activeChunkSession
                    
                    if (session != null) {
                        Log.i(TAG, "=== CHUNK DEBUG: Session exists with ${session.chunks.size}/${session.totalChunks} chunks ===")
                        // Verify we have all chunks (this might be redundant if auto-assembly already processed)
                        if (session.chunks.size == session.totalChunks) {
                            Log.i(TAG, "=== CHUNK DEBUG: All chunks received, reassembling via END marker ===")
                            // Reassemble the data
                            val reassembled = reassembleChunks(session)
                            
                            if (reassembled != null) {
                                Log.i(TAG, "=== CHUNK DEBUG: Successfully reassembled ${reassembled.size} bytes via END marker ===")
                                
                                // Decompress and process the CoT
                                processReassembledCoT(reassembled, fromNode)
                            } else {
                                Log.e(TAG, "=== CHUNK DEBUG: Reassembly failed ===")
                            }
                        } else {
                            Log.w(TAG, "=== CHUNK DEBUG: Session ended but only received ${session.chunks.size}/${session.totalChunks} chunks ===")
                        }
                        
                        // Clean up session
                        activeChunkSession = null
                    } else {
                        Log.i(TAG, "=== CHUNK DEBUG: Received END marker but no active session - likely already auto-assembled ===")
                    }
                }
                
                }
            } else {
                // Binary chunk data: index_data  
                // Parse the chunk index from the first bytes until underscore
                Log.d(TAG, "ATAK Forwarder binary chunk from $fromNode: ${data.size} bytes")
                Log.i(TAG, "=== CHUNK DEBUG: Binary chunk received, size: ${data.size} bytes ===")
                
                if (activeChunkSession != null) {
                    Log.i(TAG, "=== CHUNK DEBUG: Active session found, processing chunk ===")
                    // Find the underscore separator
                    var underscoreIndex = -1
                    for (i in 0 until minOf(10, data.size)) { // Check first 10 bytes max for underscore
                        if (data[i] == '_'.toByte()) {
                            underscoreIndex = i
                            break
                        }
                    }
                    
                    if (underscoreIndex > 0) {
                        try {
                            // Parse chunk index from ASCII bytes
                            val indexStr = String(data, 0, underscoreIndex, StandardCharsets.UTF_8)
                            val chunkIndex = indexStr.toInt()
                            
                            // Extract the binary data portion (everything after underscore)
                            val headerLength = underscoreIndex + 1
                            val chunkData = ByteArray(data.size - headerLength)
                            System.arraycopy(data, headerLength, chunkData, 0, chunkData.size)
                            
                            // Store the chunk
                            activeChunkSession!!.chunks[chunkIndex] = chunkData
                            
                            Log.d(TAG, "Received chunk $chunkIndex/${activeChunkSession!!.totalChunks} (${chunkData.size} bytes)")
                            Log.i(TAG, "=== CHUNK DEBUG: Stored chunk $chunkIndex, session now has ${activeChunkSession!!.chunks.size}/${activeChunkSession!!.totalChunks} chunks ===")
                            
                            // Check if we have all chunks - auto-assemble without waiting for END marker
                            if (activeChunkSession!!.chunks.size == activeChunkSession!!.totalChunks) {
                                Log.i(TAG, "=== CHUNK DEBUG: All chunks received! Auto-assembling without waiting for END marker ===")
                                
                                // Reassemble the data
                                val reassembled = reassembleChunks(activeChunkSession!!)
                                
                                if (reassembled != null) {
                                    Log.i(TAG, "=== CHUNK DEBUG: Successfully auto-reassembled ${reassembled.size} bytes ===")
                                    
                                    // Process the reassembled CoT immediately
                                    processReassembledCoT(reassembled, fromNode)
                                } else {
                                    Log.e(TAG, "=== CHUNK DEBUG: Auto-reassembly failed ===")
                                }
                                
                                // Clean up session immediately after processing
                                activeChunkSession = null
                                Log.i(TAG, "=== CHUNK DEBUG: Session cleaned up after auto-assembly ===")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse chunk header: $e")
                        }
                    } else {
                        Log.w(TAG, "Invalid chunk format - no underscore found")
                    }
                } else {
                    Log.w(TAG, "Received chunk data but no active session")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling ATAK Forwarder message", e)
        }
    }
    
    /**
     * Reassemble chunks into complete data
     */
    private fun reassembleChunks(session: ChunkSession): ByteArray? {
        return try {
            val outputStream = ByteArrayOutputStream()
            
            // Reassemble in order
            for (i in 0 until session.totalChunks) {
                val chunk = session.chunks[i]
                if (chunk != null) {
                    outputStream.write(chunk)
                } else {
                    Log.e(TAG, "Missing chunk $i in active session")
                    return null
                }
            }
            
            val result = outputStream.toByteArray()
            
            // Verify size matches expected
            if (result.size != session.totalSize) {
                Log.e(TAG, "Reassembled size ${result.size} doesn't match expected ${session.totalSize}")
                return null
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error reassembling chunks", e)
            null
        }
    }
    
    /**
     * Process reassembled and compressed CoT
     */
    private fun processReassembledCoT(compressedData: ByteArray, fromNode: String?) {
        try {
            // Decompress the data (GZIP)
            val decompressed = decompressGzip(compressedData)
            
            if (decompressed != null) {
                Log.i(TAG, "=== CHUNK DEBUG: Decompressed CoT from ${compressedData.size} to ${decompressed.size} bytes ===")
                
                // Validate it's valid XML by converting to string and checking for CoT markers
                val xmlString = String(decompressed, StandardCharsets.UTF_8)
                Log.d(TAG, "Decompressed CoT XML preview: ${xmlString.take(200)}...")
                
                if (xmlString.contains("<event") && xmlString.contains("</event>")) {
                    Log.i(TAG, "=== CHUNK DEBUG: Valid CoT XML detected, dispatching to ${atakForwarderHandlers.size} ATAK Forwarder handlers ===")
                    // Send to ATAK Forwarder handlers - they will handle any CoT type without PLI-specific processing
                    atakForwarderHandlers.forEach { it.handleAtakForwarder(decompressed, fromNode) }
                } else {
                    Log.e(TAG, "=== CHUNK DEBUG: Invalid CoT XML after decompression - missing event tags ===")
                    Log.e(TAG, "Full decompressed content: $xmlString")
                }
            } else {
                Log.e(TAG, "Failed to decompress CoT data")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing reassembled CoT", e)
        }
    }
    
    /**
     * Decompress GZIP data
     */
    private fun decompressGzip(compressed: ByteArray): ByteArray? {
        return try {
            val inputStream = GZIPInputStream(ByteArrayInputStream(compressed))
            val outputStream = ByteArrayOutputStream()
            
            val buffer = ByteArray(1024)
            var len: Int
            while (inputStream.read(buffer).also { len = it } != -1) {
                outputStream.write(buffer, 0, len)
            }
            
            inputStream.close()
            outputStream.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Error decompressing GZIP data", e)
            null
        }
    }
    
    /**
     * Clean up old chunk session
     */
    private fun cleanupOldSession() {
        val session = activeChunkSession
        if (session != null) {
            val now = System.currentTimeMillis()
            if (now - session.startTime > CHUNK_SESSION_TIMEOUT) {
                Log.w(TAG, "Removing timed-out chunk session")
                activeChunkSession = null
            }
        }
    }

    /**
     * Parse ATAK message to extract type and payload
     */
    private fun parseAtakMessage(data: ByteArray): Pair<String, ByteArray> {
        return try {
            val buffer = ByteBuffer.wrap(data)
            val typeLength = buffer.int
            
            if (typeLength > 0 && typeLength < 100) { // Sanity check
                val typeBytes = ByteArray(typeLength)
                buffer.get(typeBytes)
                val type = String(typeBytes, StandardCharsets.UTF_8)
                
                val payload = ByteArray(buffer.remaining())
                buffer.get(payload)
                
                Pair(type, payload)
            } else {
                // No type header, return as-is
                Pair("", data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ATAK message", e)
            Pair("", data)
        }
    }
    
    /**
     * Check if message is a duplicate
     */
    private fun isDuplicateMessage(data: ByteArray, fromNode: String?): Boolean {
        val messageHash = "${fromNode}_${data.contentHashCode()}"
        val currentTime = System.currentTimeMillis()
        
        // Clean old entries
        messageCache.entries.removeIf { 
            currentTime - it.value > MESSAGE_CACHE_TIMEOUT 
        }
        
        // Check if duplicate
        val lastSeen = messageCache[messageHash]
        if (lastSeen != null && currentTime - lastSeen < MESSAGE_CACHE_TIMEOUT) {
            return true
        }
        
        // Add to cache
        messageCache[messageHash] = currentTime
        return false
    }
    
    /**
     * Monitor connection state and handle reconnection
     */
    private fun monitorConnectionState() {
        scope.launch {
            meshtasticManager?.connectionState?.collect { state ->
                Log.i(TAG, "Connection state changed: $state")
                
                when (state) {
                    MeshtasticManager.ConnectionState.DISCONNECTED -> {
                        // Attempt reconnection after delay
                        delay(5000)
                        if (autoConnectBluetooth && bluetoothAddress != null) {
                            meshtasticManager?.connectBluetooth(bluetoothAddress!!)
                        } else if (autoConnectUsb) {
                            meshtasticManager?.connectUsb("")
                        }
                    }
                    MeshtasticManager.ConnectionState.CONFIGURED -> {
                        Log.i(TAG, "Meshtastic connection ready")
                        
                        // Save successful connection info
                        when {
                            autoConnectBluetooth && bluetoothAddress != null -> {
                                val deviceName = getMyNodeLongName() ?: "Meshtastic Device"
                                saveConnectionInfo(CONNECTION_TYPE_BLUETOOTH, bluetoothAddress!!, deviceName)
                            }
                            autoConnectUsb -> {
                                saveConnectionInfo(CONNECTION_TYPE_USB, "USB Device", "USB Serial")
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }
    
    /**
     * Decompress GZIP compressed data
     */
    private fun decompressData(compressedData: ByteArray): ByteArray? {
        return try {
            val inputStream = GZIPInputStream(ByteArrayInputStream(compressedData))
            val outputStream = ByteArrayOutputStream()
            
            val buffer = ByteArray(1024)
            var len: Int
            while (inputStream.read(buffer).also { len = it } != -1) {
                outputStream.write(buffer, 0, len)
            }
            
            inputStream.close()
            val result = outputStream.toByteArray()
            outputStream.close()
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decompress data", e)
            null
        }
    }
}