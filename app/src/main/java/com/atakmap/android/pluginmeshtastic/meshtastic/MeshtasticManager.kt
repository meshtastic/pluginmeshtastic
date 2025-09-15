package com.atakmap.android.pluginmeshtastic.meshtastic

import android.content.Context
import android.util.Log
import com.atakmap.android.preference.AtakPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import com.geeksville.mesh.MeshProtos as GeneratedMeshProtos
import com.geeksville.mesh.AdminProtos
import com.geeksville.mesh.ConfigProtos
import com.geeksville.mesh.Portnums

/**
 * Result of a message acknowledgment
 */
data class AckResult(
    val success: Boolean,
    val errorCode: Int? = null,
    val errorReason: String? = null
)

/**
 * Manages Meshtastic communication without requiring a separate service
 * This runs within the plugin's context to avoid cross-app service issues
 */
class MeshtasticManager(private val context: Context) : RadioCallback {
    companion object {
        private const val TAG = "MeshtasticManager"
        private const val HEARTBEAT_INTERVAL_MILLIS = 1 * 60 * 1000L // 1 minute
        
        // AtakPreferences key for UI settings (must match UI implementation)
        private const val PREF_CHANNEL_PASSWORD = "plugin.meshtastic.channel_password"
        
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
    
    private var bluetoothInterface: BluetoothInterface? = null
    private var usbInterface: UsbSerialInterface? = null
    private var radioReadyTimeoutJob: Job? = null
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Message handlers for ATAK integration
    private val atakMessageHandlers = mutableListOf<AtakMessageHandler>()
    
    // Node database
    private val nodeDatabase = ConcurrentHashMap<String, MeshProtos.NodeInfo>()
    private var myNodeInfo: MeshProtos.MyNodeInfo? = null
    private var deviceMetadata: com.geeksville.mesh.MeshProtos.DeviceMetadata? = null
    
    // Configuration storage - Config types
    private var deviceConfig: com.geeksville.mesh.ConfigProtos.Config.DeviceConfig? = null
    private var positionConfig: com.geeksville.mesh.ConfigProtos.Config.PositionConfig? = null
    private var powerConfig: com.geeksville.mesh.ConfigProtos.Config.PowerConfig? = null
    private var networkConfig: com.geeksville.mesh.ConfigProtos.Config.NetworkConfig? = null
    private var displayConfig: com.geeksville.mesh.ConfigProtos.Config.DisplayConfig? = null
    private var loraConfig: com.geeksville.mesh.ConfigProtos.Config.LoRaConfig? = null
    private var bluetoothConfig: com.geeksville.mesh.ConfigProtos.Config.BluetoothConfig? = null
    private var securityConfig: com.geeksville.mesh.ConfigProtos.Config.SecurityConfig? = null
    private var sessionkeyConfig: com.geeksville.mesh.ConfigProtos.Config.SessionkeyConfig? = null
    
    // Configuration storage - ModuleConfig types
    private var mqttConfig: com.geeksville.mesh.ModuleConfigProtos.ModuleConfig.MQTTConfig? = null
    private var serialConfig: com.geeksville.mesh.ModuleConfigProtos.ModuleConfig.SerialConfig? = null
    private var externalNotificationConfig: com.geeksville.mesh.ModuleConfigProtos.ModuleConfig.ExternalNotificationConfig? = null
    private var storeForwardConfig: com.geeksville.mesh.ModuleConfigProtos.ModuleConfig.StoreForwardConfig? = null
    private var rangeTestConfig: com.geeksville.mesh.ModuleConfigProtos.ModuleConfig.RangeTestConfig? = null
    private var telemetryConfig: com.geeksville.mesh.ModuleConfigProtos.ModuleConfig.TelemetryConfig? = null
    private var cannedMessageConfig: com.geeksville.mesh.ModuleConfigProtos.ModuleConfig.CannedMessageConfig? = null
    private var audioConfig: com.geeksville.mesh.ModuleConfigProtos.ModuleConfig.AudioConfig? = null
    private var remoteHardwareConfig: com.geeksville.mesh.ModuleConfigProtos.ModuleConfig.RemoteHardwareConfig? = null
    private var neighborInfoConfig: com.geeksville.mesh.ModuleConfigProtos.ModuleConfig.NeighborInfoConfig? = null
    private var ambientLightingConfig: com.geeksville.mesh.ModuleConfigProtos.ModuleConfig.AmbientLightingConfig? = null
    private var detectionSensorConfig: com.geeksville.mesh.ModuleConfigProtos.ModuleConfig.DetectionSensorConfig? = null
    private var paxcounterConfig: com.geeksville.mesh.ModuleConfigProtos.ModuleConfig.PaxcounterConfig? = null
    
    // Channel storage
    private val channelDatabase = ConcurrentHashMap<Int, com.geeksville.mesh.ChannelProtos.Channel>()
    
    // Configuration manager
    private var configManager: MeshtasticConfigManager? = null
    
    
    // Packet tracking for acknowledgments
    data class PendingPacket(
        val packetId: Int,
        val timestamp: Long = System.currentTimeMillis(),
        val callback: ((Boolean) -> Unit)? = null,
        val enhancedCallback: ((AckResult) -> Unit)? = null
    )
    
    private val pendingPackets = ConcurrentHashMap<Int, PendingPacket>()
    private val PACKET_TIMEOUT = 60000L // 60 seconds timeout
    
    // Heartbeat tracking
    private var lastHeartbeatMillis = 0L
    private var heartbeatJob: Job? = null
    
    // Message polling
    private var messagePollingJob: Job? = null
    
    // Configuration request rate limiting
    private var lastConfigRequestTime = 0L
    private val MIN_CONFIG_REQUEST_INTERVAL = 60000L // 60 seconds minimum between requests
    private var isConfigurationReceived = false
    
    // Connection state
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState
    
    // Message queues
    private val incomingMessages = Channel<MeshProtos.FromRadio>(Channel.UNLIMITED)
    private val outgoingMessages = Channel<MeshProtos.ToRadio>(Channel.UNLIMITED)
    
    // Queue management
    data class QueueStatus(
        val free: UInt,
        val maxlen: UInt,
        val res: Int = 0,
        val meshPacketId: UInt = 0u
    )
    
    private val _currentQueueStatus = MutableStateFlow<QueueStatus?>(null)
    val currentQueueStatus: StateFlow<QueueStatus?> = _currentQueueStatus
    
    // Chunk management with queue awareness
    data class ChunkInfo(
        val chunkId: String,
        val packetId: Int,
        val chunkIndex: Int,
        val totalChunks: Int,
        val requestId: String,
        val timestamp: Long = System.currentTimeMillis(),
        val data: ByteArray,
        val destNode: UInt? = null
    )
    
    private val pendingChunks = ConcurrentHashMap<String, ChunkInfo>()
    private val chunkAckCallbacks = ConcurrentHashMap<String, (AckResult) -> Unit>()
    private val _availableQueueSlots = MutableStateFlow(0u)
    val availableQueueSlots: StateFlow<UInt> = _availableQueueSlots
    
    // Statistics for monitoring
    private var messagesReceived = 0
    private var lastStatsLog = 0L
    
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        CONFIGURED
    }
    
    enum class TransportType {
        BLUETOOTH,
        USB_SERIAL
    }
    
    interface AtakMessageHandler {
        fun onAtakPluginMessage(data: ByteArray, fromNode: String?)
        fun onAtakForwarderMessage(data: ByteArray, fromNode: String?)
    }
    
    
    init {
        startMessageProcessing()
    }
    
    /**
     * Connect to Meshtastic device via Bluetooth
     */
    fun connectBluetooth(deviceAddress: String) {
        Log.i(TAG, "Connecting to Bluetooth device: $deviceAddress")
        setConnectionState(ConnectionState.CONNECTING)
        
        disconnect() // Disconnect any existing connection
        
        bluetoothInterface = BluetoothInterface(context, deviceAddress, this)
        bluetoothInterface?.connect()
        
        // Observe BluetoothInterface connection state
        scope.launch {
            bluetoothInterface?.connectionState?.collect { state ->
                Log.i(TAG, "Bluetooth interface state changed: $state")
                when (state) {
                    BluetoothInterface.ConnectionState.DISCONNECTED -> {
                        setConnectionState(ConnectionState.DISCONNECTED)
                    }
                    BluetoothInterface.ConnectionState.CONNECTING -> {
                        setConnectionState(ConnectionState.CONNECTING)
                    }
                    BluetoothInterface.ConnectionState.CONNECTED -> {
                        setConnectionState(ConnectionState.CONNECTED)
                    }
                    BluetoothInterface.ConnectionState.CONFIGURED -> {
                        setConnectionState(ConnectionState.CONFIGURED)
                    }
                    else -> {
                        // Handle other states if needed
                    }
                }
            }
        }
    }
    
    /**
     * Connect to Meshtastic device via USB Serial
     */
    fun connectUsb(devicePath: String) {
        Log.i(TAG, "Connecting to USB device: $devicePath")
        setConnectionState(ConnectionState.CONNECTING)
        
        disconnect() // Disconnect any existing connection
        
        usbInterface = UsbSerialInterface(context, devicePath, this)
        usbInterface?.connect()
    }
    
    /**
     * Set connection state and handle side effects
     */
    private fun setConnectionState(newState: ConnectionState) {
        val oldState = _connectionState.value
        _connectionState.value = newState
        
        Log.i(TAG, "Connection state changed: $oldState -> $newState")
        
        when (newState) {
            ConnectionState.CONFIGURED -> {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastRequest = currentTime - lastConfigRequestTime
                
                Log.i(TAG, "Connection configured and ready for commands")
                
                // Delay configuration start slightly to ensure notification setup is complete
                // The BluetoothInterface needs a moment after setting CONFIGURED state
                scope.launch {
                    delay(200) // Small delay for notification setup to complete
                    // TAK configuration is now manual - user must click button in UI
                     if (configManager != null && configManager?.configState?.value == MeshtasticConfigManager.ConfigurationState.UNCONFIGURED) {
                         Log.i(TAG, "Starting TAK configuration after interface stabilization")
                         configManager?.startConfiguration()
                     }
                }
                
                // Start heartbeat timer to keep connection alive
                startHeartbeat()
            }
            ConnectionState.DISCONNECTED -> {
                Log.i(TAG, "Disconnected")
                // Cancel radio ready timeout
                radioReadyTimeoutJob?.cancel()
                radioReadyTimeoutJob = null
                // Stop heartbeat when disconnected
                stopHeartbeat()
                // Stop message polling
                stopMessagePolling()
                
                // Reset configuration flag after extended disconnection to ensure fresh config on reconnect
                scope.launch {
                    delay(2 * 60 * 1000L) // 2 minutes
                    if (_connectionState.value == ConnectionState.DISCONNECTED) {
                        Log.i(TAG, "Extended disconnection detected, will request fresh configuration on next connect")
                        isConfigurationReceived = false
                    }
                }
            }
            else -> {
                // No special handling for CONNECTING or CONNECTED states
            }
        }
    }

    /**
     * Disconnect from Meshtastic device
     */
    fun disconnect() {
        Log.i(TAG, "Disconnecting from Meshtastic device")
        
        bluetoothInterface?.disconnect()
        bluetoothInterface = null
        
        usbInterface?.disconnect()
        usbInterface = null
        
        setConnectionState(ConnectionState.DISCONNECTED)
        nodeDatabase.clear()
        // Don't clear myNodeInfo on disconnect - it should persist as device identity
        // myNodeInfo = null // REMOVED - keep the device identity info
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        radioReadyTimeoutJob?.cancel()
        radioReadyTimeoutJob = null
        stopHeartbeat()
        stopMessagePolling()
        configManager?.cleanup()
        configManager = null
        disconnect()
        scope.cancel()
    }
    
    /**
     * Send ATAK Plugin message (port 72)
     */
    fun sendAtakPluginMessage(data: ByteArray, destNode: UInt? = null, onAck: ((Boolean) -> Unit)? = null) {
        val fromNode = myNodeInfo?.myNodeNum
        val packet = MeshProtos.createAtakPluginPacket(data, destNode, fromNode)
        sendPacket(packet, onAck)
    }
    
    /**
     * Send ATAK Plugin message with enhanced callback including error details
     */
    fun sendAtakPluginMessageWithErrorDetails(data: ByteArray, destNode: UInt? = null, onResult: ((AckResult) -> Unit)? = null) {
        Log.d(TAG, "sendAtakPluginMessageWithErrorDetails: ${data.size} bytes to ${destNode ?: "broadcast"}")
        val fromNode = myNodeInfo?.myNodeNum
        val packet = MeshProtos.createAtakPluginPacket(data, destNode, fromNode)
        Log.d(TAG, "Created packet - id: ${packet.id?.let { "0x${it.toLong().and(0xFFFFFFFFL).toString(16)}" } ?: "null"}, from: ${packet.from?.let { "0x${it.toLong().and(0xFFFFFFFFL).toString(16)}" } ?: "null"}, to: ${if (packet.to == MeshProtos.ID_BROADCAST) "BROADCAST" else packet.to?.let { "0x${it.toLong().and(0xFFFFFFFFL).toString(16)}" } ?: "null"}, portNum: ${packet.portNum}, size: ${packet.bytes.size}")
        sendPacketWithErrorDetails(packet, onResult)
    }
    
    /**
     * Send ATAK Forwarder message (port 257) with acknowledgment callback
     */
    fun sendAtakForwarderMessage(data: ByteArray, destNode: UInt? = null, onAck: ((Boolean) -> Unit)? = null) {
        Log.d(TAG, "sendAtakForwarderMessage: ${data.size} bytes to ${destNode ?: "broadcast"}")
        val fromNode = myNodeInfo?.myNodeNum
        val packet = MeshProtos.createAtakForwarderPacket(data, destNode, fromNode)
        Log.d(TAG, "Created packet - id: ${packet.id?.let { "0x${it.toLong().and(0xFFFFFFFFL).toString(16)}" } ?: "null"}, from: ${packet.from?.let { "0x${it.toLong().and(0xFFFFFFFFL).toString(16)}" } ?: "null"}, to: ${if (packet.to == MeshProtos.ID_BROADCAST) "BROADCAST" else packet.to?.let { "0x${it.toLong().and(0xFFFFFFFFL).toString(16)}" } ?: "null"}, portNum: ${packet.portNum}, size: ${packet.bytes.size}")
        sendPacket(packet, onAck)
    }
    
    /**
     * Send ATAK Forwarder message with enhanced callback including error details
     */
    fun sendAtakForwarderMessageWithErrorDetails(data: ByteArray, destNode: UInt? = null, onResult: ((AckResult) -> Unit)? = null) {
        Log.d(TAG, "sendAtakForwarderMessageWithErrorDetails: ${data.size} bytes to ${destNode ?: "broadcast"}")
        val fromNode = myNodeInfo?.myNodeNum
        val packet = MeshProtos.createAtakForwarderPacket(data, destNode, fromNode)
        Log.d(TAG, "Created packet - id: ${packet.id?.let { "0x${it.toLong().and(0xFFFFFFFFL).toString(16)}" } ?: "null"}, from: ${packet.from?.let { "0x${it.toLong().and(0xFFFFFFFFL).toString(16)}" } ?: "null"}, to: ${if (packet.to == MeshProtos.ID_BROADCAST) "BROADCAST" else packet.to?.let { "0x${it.toLong().and(0xFFFFFFFFL).toString(16)}" } ?: "null"}, portNum: ${packet.portNum}, size: ${packet.bytes.size}")
        sendPacketWithErrorDetails(packet, onResult)
    }
    
    /**
     * Send a data packet with enhanced callback including error details
     */
    private fun sendPacketWithErrorDetails(packet: MeshProtos.DataPacket, onResult: ((AckResult) -> Unit)? = null) {
        if (_connectionState.value != ConnectionState.CONFIGURED) {
            Log.w(TAG, "Not ready to send, state: ${_connectionState.value}")
            onResult?.invoke(AckResult(success = false, errorReason = "Device not configured"))
            return
        }
        
        // Track packet if acknowledgment is requested
        if (packet.wantAck && onResult != null) {
            val packetIdInt = packet.id.toInt()
            Log.d(TAG, "Tracking packet 0x${packetIdInt.toLong().and(0xFFFFFFFFL).toString(16)} for acknowledgment")
            pendingPackets[packetIdInt] = PendingPacket(packetIdInt, enhancedCallback = onResult)
            
            // Schedule timeout check
            scope.launch {
                delay(PACKET_TIMEOUT)
                checkPacketTimeout(packetIdInt)
            }
        }
        
        val toRadio = MeshProtos.ToRadio(packet = packet)
        
        // Log the packet contents before sending
        logToRadioPacket(toRadio)
        
        scope.launch {
            outgoingMessages.send(toRadio)
        }
    }
    
    /**
     * Send a data packet with optional acknowledgment callback
     */
    private fun sendPacket(packet: MeshProtos.DataPacket, onAck: ((Boolean) -> Unit)? = null) {
        if (_connectionState.value != ConnectionState.CONFIGURED) {
            Log.w(TAG, "Not ready to send, state: ${_connectionState.value}")
            onAck?.invoke(false)
            return
        }
        
        // Track packet if acknowledgment is requested
        if (packet.wantAck && onAck != null) {
            val packetIdInt = packet.id.toInt()
            Log.d(TAG, "Tracking packet 0x${packetIdInt.toLong().and(0xFFFFFFFFL).toString(16)} for acknowledgment")
            pendingPackets[packetIdInt] = PendingPacket(packetIdInt, callback = onAck)
            
            // Schedule timeout check
            scope.launch {
                delay(PACKET_TIMEOUT)
                checkPacketTimeout(packetIdInt)
            }
        }
        
        val toRadio = MeshProtos.ToRadio(packet = packet)
        
        // Log the packet contents before sending
        logToRadioPacket(toRadio)
        
        scope.launch {
            outgoingMessages.send(toRadio)
        }
    }
    
    /**
     * Check if a packet has timed out
     */
    private fun checkPacketTimeout(packetId: Int) {
        val pending = pendingPackets.remove(packetId)
        if (pending != null) {
            Log.w(TAG, "Packet 0x${packetId.toLong().and(0xFFFFFFFFL).toString(16)} timed out")
            pending.callback?.invoke(false)
            pending.enhancedCallback?.invoke(AckResult(success = false, errorCode = 3, errorReason = "Message delivery timeout"))
        }
    }
    
    /**
     * Handle incoming data from radio
     */
    override fun handleFromRadio(data: ByteArray) {
        scope.launch {
            Log.i(TAG, "Received data from radio: ${data.size} bytes")
            
            // Log raw data for debugging protobuf parsing
            val hexString = data.joinToString("") { "%02x".format(it) }
            Log.d(TAG, "Raw data hex: $hexString")
            
            // Log first few bytes to identify message type
            if (data.size >= 2) {
                val firstByte = data[0].toInt() and 0xFF
                val secondByte = data[1].toInt() and 0xFF
                Log.d(TAG, "First two bytes: 0x%02x 0x%02x".format(firstByte, secondByte))
            }
            
            try {
                Log.d(TAG, "Attempting to parse FromRadio protobuf...")
                
                // Parse with generated protobuf for logging
                val generatedFromRadio = GeneratedMeshProtos.FromRadio.parseFrom(data)
                Log.d(TAG, "Successfully parsed FromRadio protobuf")
                logGeneratedFromRadioPacket(generatedFromRadio)
                
                // Parse with our custom wrapper for the message processing pipeline
                Log.d(TAG, "Attempting to parse with custom MeshProtos wrapper...")
                val customFromRadio = MeshProtos.FromRadio.parseFrom(data)
                if (customFromRadio != null) {
                    Log.d(TAG, "Custom parser succeeded, sending to message pipeline")
                    incomingMessages.send(customFromRadio)
                } else {
                    Log.w(TAG, "Custom parser returned null - message will not be processed")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse FromRadio message: ${e.message}")
                Log.w(TAG, "Exception type: ${e.javaClass.simpleName}")
                if (data.size < 10) {
                    Log.w(TAG, "Data might be too short for valid protobuf (${data.size} bytes)")
                }
            }
        }
    }
    
    /**
     * Log ToRadio packet contents in JSON-like format for debugging
     */
    private fun logToRadioPacket(toRadio: MeshProtos.ToRadio) {
        try {
            val logBuilder = StringBuilder()
            logBuilder.append("ToRadio: {\n")
            
            // Log packet if present
            toRadio.packet?.let { packet ->
                logBuilder.append("  packet: {\n")
                logBuilder.append("    id: ${packet.id?.let { "0x${it.toLong().and(0xFFFFFFFFL).toString(16)}" } ?: "null"},\n")
                logBuilder.append("    to: ${if (packet.to == MeshProtos.ID_BROADCAST) "BROADCAST" else packet.to?.let { "0x${it.toLong().and(0xFFFFFFFFL).toString(16)}" } ?: "null"},\n")
                logBuilder.append("    from: ${packet.from?.let { "0x${it.toLong().and(0xFFFFFFFFL).toString(16)}" } ?: "null"},\n")
                logBuilder.append("    channel: ${packet.channel},\n")
                logBuilder.append("    hopLimit: ${packet.hopLimit},\n")
                logBuilder.append("    wantAck: ${packet.wantAck},\n")
                logBuilder.append("    portNum: ${packet.portNum},\n")
                logBuilder.append("    payloadSize: ${packet.bytes.size} bytes,\n")
                
                // Show payload preview (first 50 bytes as hex)
                val payloadPreview = packet.bytes.take(50).joinToString("") { "%02x".format(it) }
                logBuilder.append("    payloadPreview: \"$payloadPreview${if (packet.bytes.size > 50) "..." else ""}\"\n")
                logBuilder.append("  },\n")
            }
            
            // Log wantConfigId if present
            toRadio.wantConfigId?.let {
                logBuilder.append("  wantConfigId: $it,\n")
            }
            
            // Note if this is an empty message (for queue polling)
            if (toRadio.packet == null && toRadio.wantConfigId == null) {
                logBuilder.append("  // EMPTY MESSAGE FOR QUEUE POLLING\n")
            }
            
            logBuilder.append("}")
            
            Log.d(TAG, "SEND_TO_RADIO: $logBuilder")
        } catch (e: Exception) {
            Log.w(TAG, "Error logging ToRadio packet", e)
        }
    }
    
    /**
     * Log generated FromRadio packet contents in JSON-like format for debugging
     */
    private fun logGeneratedFromRadioPacket(fromRadio: GeneratedMeshProtos.FromRadio) {
        try {
            val logBuilder = StringBuilder()
            logBuilder.append("RECV_FROM_RADIO: FromRadio: {\n")
            
            // Log packet if present
            fromRadio.packet?.let { packet ->
                logBuilder.append("  packet: {\n")
                logBuilder.append("    id: ${packet.id?.let { "0x${it.toLong().and(0xFFFFFFFFL).toString(16)}" } ?: "null"},\n")
                logBuilder.append("    to: ${if (packet.to == 0xFFFFFFFF.toInt()) "BROADCAST" else "0x${packet.to.toLong().and(0xFFFFFFFFL).toString(16)}"},\n")
                logBuilder.append("    from: 0x${packet.from.toLong().and(0xFFFFFFFFL).toString(16)},\n")
                logBuilder.append("    channel: ${packet.channel},\n")
                logBuilder.append("    hopLimit: ${packet.hopLimit},\n")
                logBuilder.append("    wantAck: ${packet.wantAck},\n")
                
                // Log decoded data if present
                if (packet.hasDecoded()) {
                    val decoded = packet.decoded
                    logBuilder.append("    portNum: ${decoded.portnumValue},\n")
                    logBuilder.append("    payloadSize: ${decoded.payload.size()} bytes,\n")
                    
                    // Show hex preview of payload
                    val payloadBytes = decoded.payload.toByteArray()
                    val hexPreview = payloadBytes.take(50).joinToString("") { "%02x".format(it) }
                    logBuilder.append("    payloadPreview: \"$hexPreview${if (payloadBytes.size > 50) "..." else ""}\",\n")
                    
                    // Show ASCII preview
                    val asciiPreview = payloadBytes.take(50).map { 
                        if (it in 32..126) it.toInt().toChar() else '.' 
                    }.joinToString("")
                    logBuilder.append("    payloadAscii: \"$asciiPreview${if (payloadBytes.size > 50) "..." else ""}\"\n")
                } else {
                    logBuilder.append("    encrypted: true\n")
                }
                logBuilder.append("  },\n")
            }
            
            // Log other fields
            if (fromRadio.hasMyInfo()) {
                logBuilder.append("  myInfo: 0x${fromRadio.myInfo.myNodeNum.toLong().and(0xFFFFFFFFL).toString(16)},\n")
            }
            if (fromRadio.hasNodeInfo()) {
                val nodeInfo = fromRadio.nodeInfo
                logBuilder.append("  nodeInfo: {\n")
                logBuilder.append("    num: 0x${nodeInfo.num.toLong().and(0xFFFFFFFFL).toString(16)},\n")
                if (nodeInfo.hasUser()) {
                    logBuilder.append("    user: {\n")
                    logBuilder.append("      longName: \"${nodeInfo.user.longName}\",\n")
                    logBuilder.append("      shortName: \"${nodeInfo.user.shortName}\"\n")
                    logBuilder.append("    }\n")
                }
                logBuilder.append("  },\n")
            }
            if (fromRadio.hasConfigCompleteId()) {
                logBuilder.append("  configCompleteId: ${fromRadio.configCompleteId},\n")
            }
            if (fromRadio.hasLogRecord()) {
                logBuilder.append("  logRecord: \"${fromRadio.logRecord.message}\",\n")
            }
            // Note: ackId field doesn't exist in generated FromRadio protobuf
            
            logBuilder.append("}")
            Log.d(TAG, logBuilder.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log FromRadio packet: ${e.message}")
        }
    }

    /**
     * Log FromRadio packet contents in JSON-like format for debugging
     */
    private fun logFromRadioPacket(fromRadio: MeshProtos.FromRadio) {
        try {
            val logBuilder = StringBuilder()
            logBuilder.append("FromRadio: {\n")
            
            // Log packet if present
            fromRadio.packet?.let { packet ->
                logBuilder.append("  packet: {\n")
                logBuilder.append("    id: ${packet.id?.let { "0x${it.toLong().and(0xFFFFFFFFL).toString(16)}" } ?: "null"},\n")
                logBuilder.append("    to: ${if (packet.to == MeshProtos.ID_BROADCAST) "BROADCAST" else packet.to?.let { "0x${it.toLong().and(0xFFFFFFFFL).toString(16)}" } ?: "null"},\n")
                logBuilder.append("    from: ${packet.from?.let { "0x${it.toLong().and(0xFFFFFFFFL).toString(16)}" } ?: "null"},\n")
                logBuilder.append("    channel: ${packet.channel},\n")
                logBuilder.append("    hopLimit: ${packet.hopLimit},\n")
                logBuilder.append("    wantAck: ${packet.wantAck},\n")
                logBuilder.append("    portNum: ${packet.portNum},\n")
                logBuilder.append("    payloadSize: ${packet.bytes.size} bytes,\n")
                
                // Show payload preview (first 50 bytes as hex + ASCII if printable)
                val payloadPreview = packet.bytes.take(50).joinToString("") { "%02x".format(it) }
                val asciiPreview = packet.bytes.take(50).map { 
                    if (it in 32..126) it.toInt().toChar() else '.' 
                }.joinToString("")
                
                logBuilder.append("    payloadPreview: \"$payloadPreview${if (packet.bytes.size > 50) "..." else ""}\",\n")
                logBuilder.append("    payloadAscii: \"$asciiPreview${if (packet.bytes.size > 50) "..." else ""}\"\n")
                logBuilder.append("  },\n")
            }
            
            // Log other fields
            fromRadio.myInfo?.let {
                logBuilder.append("  myInfo: { myNodeNum: \"0x${it.myNodeNum.toLong().and(0xFFFFFFFFL).toString(16)}\", hasGps: false },\n")
            }
            
            fromRadio.nodeInfo?.let {
                logBuilder.append("  nodeInfo: { id: \"0x${it.id.toLong().and(0xFFFFFFFFL).toString(16)}\", longName: \"${it.longName}\", shortName: \"${it.shortName}\" },\n")
            }
            
            fromRadio.configCompleteId?.let {
                logBuilder.append("  configCompleteId: $it,\n")
            }
            
            fromRadio.logRecord?.let {
                logBuilder.append("  logRecord: \"$it\",\n")
            }
            
            fromRadio.ackId?.let {
                logBuilder.append("  ackId: 0x${it.toLong().and(0xFFFFFFFFL).toString(16)},\n")
            }
            
            fromRadio.routingError?.let {
                logBuilder.append("  routingError: { originalId: 0x${it.originalId.toLong().and(0xFFFFFFFFL).toString(16)}, reason: \"${it.errorReason}\" },\n")
            }
            
            logBuilder.append("}")
            
            Log.d(TAG, "RECV_FROM_RADIO: $logBuilder")
        } catch (e: Exception) {
            Log.w(TAG, "Error logging FromRadio packet", e)
        }
    }

    /**
     * Handle outgoing data to radio
     */
    fun handleSendToRadio(data: ByteArray) {
        bluetoothInterface?.sendToRadio(data)
            ?: usbInterface?.sendToRadio(data)
            ?: Log.w(TAG, "No interface available to send data")
    }
    
    /**
     * Send raw ToRadio message (used by ConfigManager)
     */
    fun sendRawToRadio(data: ByteArray) {
        handleSendToRadio(data)
    }
    
    /**
     * Set the channel password for PSK configuration
     */
    fun setChannelPassword(password: String) {
        Log.i(TAG, "Setting channel password for device configuration")
        configManager?.setChannelPassword(password)
    }
    
    /**
     * Get channel password from UI AtakPreferences
     */
    private fun getChannelPasswordFromUI(): String? {
        return try {
            val preferences = AtakPreferences.getInstance(context)
            val password = preferences.get(PREF_CHANNEL_PASSWORD, "")
            Log.d(TAG, "Retrieved password from AtakPreferences: ${if (password.isNullOrEmpty()) "empty" else "${password.length} chars"}")
            if (password.isNullOrEmpty()) null else password
        } catch (e: Exception) {
            Log.w(TAG, "Failed to retrieve channel password from UI", e)
            null
        }
    }
    
    /**
     * Handle admin message from device
     */
    private fun handleAdminMessage(packet: MeshProtos.DataPacket) {
        try {
            val adminMsg = AdminProtos.AdminMessage.parseFrom(packet.bytes)
            configManager?.handleAdminResponse(adminMsg)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse admin message", e)
        }
    }
    
    /**
     * Handle routing message from device (includes ACK packets)
     */
    private fun handleRoutingMessage(packet: MeshProtos.DataPacket) {
        Log.d(TAG, "Received routing message from 0x${packet.from?.let { it.toLong().and(0xFFFFFFFFL).toString(16) } ?: "null"}")
        Log.d(TAG, "Packet ID: 0x${packet.id.toLong().and(0xFFFFFFFFL).toString(16)}")
        Log.d(TAG, "Raw routing message bytes: ${packet.bytes.joinToString("") { "%02x".format(it) }}")
        
        // For now, just check if this could be an implicit ACK based on the packet structure
        // Many routing protocols send the original packet ID in the routing message
        
        // The most likely scenario is that ACKs come as simple empty routing messages
        // where the packet ID in the MeshPacket header corresponds to the original packet
        
        // Let's try a simple approach: treat any routing message as a potential ACK
        // and look up the packet ID to see if we're waiting for it
        
        val potentialAckId = packet.id.toInt()
        
        // Check if this is a chunk ACK first
        val chunkInfo = findChunkByPacketId(packet.id)
        if (chunkInfo != null) {
            Log.i(TAG, "Routing message is ACK for chunk ${chunkInfo.chunkIndex}/${chunkInfo.totalChunks} (requestId: ${chunkInfo.requestId})")
            pendingChunks.remove(chunkInfo.chunkId)
            chunkAckCallbacks.remove(chunkInfo.chunkId)?.invoke(AckResult(success = true, errorCode = 0))
            
            // Update available queue slots
            _availableQueueSlots.value = _availableQueueSlots.value + 1u
        }
        
        val pending = pendingPackets.remove(potentialAckId)
        
        if (pending != null) {
            Log.i(TAG, "Treating routing message as ACK for packet 0x${potentialAckId.toLong().and(0xFFFFFFFFL).toString(16)}")
            pending.callback?.invoke(true)
            pending.enhancedCallback?.invoke(AckResult(success = true, errorCode = 0))
        } else if (chunkInfo == null) {
            Log.d(TAG, "Routing message packet ID 0x${potentialAckId.toLong().and(0xFFFFFFFFL).toString(16)} does not match any pending packet")
            Log.d(TAG, "Current pending packets: ${pendingPackets.keys.map { "0x${it.toLong().and(0xFFFFFFFFL).toString(16)}" }}")
        }

        // Also notify config manager about potential admin ACK
        configManager?.handleAdminAck(packet.id)
    }
    
    /**
     * Register ATAK message handler
     */
    fun registerAtakMessageHandler(handler: AtakMessageHandler) {
        atakMessageHandlers.add(handler)
    }
    
    /**
     * Unregister ATAK message handler
     */
    fun unregisterAtakMessageHandler(handler: AtakMessageHandler) {
        atakMessageHandlers.remove(handler)
    }
    
    /**
     * Get list of known nodes
     */
    fun getNodes(): List<MeshProtos.NodeInfo> {
        return nodeDatabase.values.toList()
    }
    
    /**
     * Get current node info
     */
    fun getMyNodeInfo(): MeshProtos.MyNodeInfo? {
        return myNodeInfo
    }
    
    /**
     * Get NodeInfo by node ID
     */
    fun getNodeInfo(nodeId: String): MeshProtos.NodeInfo? {
        return nodeDatabase[nodeId]
    }
    
    /**
     * Get device metadata
     */
    fun getDeviceMetadata(): com.geeksville.mesh.MeshProtos.DeviceMetadata? {
        return deviceMetadata
    }
    
    /**
     * Get current RSSI value from Bluetooth connection
     */
    fun getCurrentRssi(): Int {
        return bluetoothInterface?.rssi?.value ?: 0
    }
    
    /**
     * Request a fresh RSSI reading from the Bluetooth connection
     */
    fun requestRssi(): Boolean {
        return bluetoothInterface?.requestRssi() ?: false
    }
    
    /**
     * Update the stored device name when we receive NodeInfo for our own device
     */
    private fun updateStoredDeviceName(longName: String) {
        // This will be called by AtakMeshtasticBridge if set
        deviceNameUpdateCallback?.invoke(longName)
    }
    
    // Callback for device name updates
    private var deviceNameUpdateCallback: ((String) -> Unit)? = null
    
    /**
     * Set callback for when device name is updated
     */
    fun setDeviceNameUpdateCallback(callback: (String) -> Unit) {
        deviceNameUpdateCallback = callback
    }
    
    /**
     * Check if sessionkey config is available (indicates admin operations are supported)
     */
    fun hasSessionkeyConfig(): Boolean {
        return sessionkeyConfig != null
    }
    
    /**
     * Get sessionkey config (empty config used for admin authentication)
     */
    fun getSessionkeyConfig(): com.geeksville.mesh.ConfigProtos.Config.SessionkeyConfig? {
        return sessionkeyConfig
    }
    
    /**
     * Get LoRa config (includes region information)
     */
    fun getLoraConfig(): com.geeksville.mesh.ConfigProtos.Config.LoRaConfig? {
        return loraConfig
    }
    
    /**
     * Parse and store Config response, logging details of each config type
     */
    private fun parseAndStoreConfig(config: com.geeksville.mesh.ConfigProtos.Config) {
        try {
            when {
                config.hasDevice() -> {
                    deviceConfig = config.device
                    Log.i(TAG, "📱 Device Config received:")
                    Log.i(TAG, "   Role: ${deviceConfig?.role}")
                    Log.i(TAG, "   Serial enabled: ${deviceConfig?.serialEnabled}")
                    Log.i(TAG, "   Button GPIO: ${deviceConfig?.buttonGpio}")
                    Log.i(TAG, "   Node info broadcast: ${deviceConfig?.nodeInfoBroadcastSecs}s")
                    Log.i(TAG, "   LED heartbeat: ${deviceConfig?.ledHeartbeatDisabled?.let { !it } ?: true}")
                    Log.i(TAG, "   Disable triple click: ${deviceConfig?.disableTripleClick}")
                }
                config.hasPosition() -> {
                    positionConfig = config.position
                    Log.i(TAG, "📍 Position Config received:")
                    Log.i(TAG, "   Position broadcast: ${positionConfig?.positionBroadcastSecs}s")
                    Log.i(TAG, "   Smart broadcast: ${positionConfig?.positionBroadcastSmartEnabled}")
                    Log.i(TAG, "   Fixed position: ${positionConfig?.fixedPosition}")
                    Log.i(TAG, "   GPS update interval: ${positionConfig?.gpsUpdateInterval}s")
                    Log.i(TAG, "   GPS attempt time: ${positionConfig?.gpsAttemptTime}s")
                    Log.i(TAG, "   Position flags: ${positionConfig?.positionFlags}")
                }
                config.hasPower() -> {
                    powerConfig = config.power
                    Log.i(TAG, "⚡ Power Config received:")
                    Log.i(TAG, "   Power saving: ${powerConfig?.isPowerSaving}")
                    Log.i(TAG, "   Battery shutdown: ${powerConfig?.onBatteryShutdownAfterSecs}s")
                    Log.i(TAG, "   ADC multiplier: ${powerConfig?.adcMultiplierOverride}")
                    Log.i(TAG, "   Wait BT: ${powerConfig?.waitBluetoothSecs}s")
                    Log.i(TAG, "   SDS time: ${powerConfig?.sdsSecs}s")
                    Log.i(TAG, "   LS time: ${powerConfig?.lsSecs}s")
                    Log.i(TAG, "   Min wake: ${powerConfig?.minWakeSecs}s")
                }
                config.hasNetwork() -> {
                    networkConfig = config.network
                    Log.i(TAG, "🌐 Network Config received:")
                    Log.i(TAG, "   WiFi enabled: ${networkConfig?.wifiEnabled}")
                    Log.i(TAG, "   WiFi SSID: '${networkConfig?.wifiSsid}'")
                    Log.i(TAG, "   Ethernet enabled: ${networkConfig?.ethEnabled}")
                    Log.i(TAG, "   NTP server: '${networkConfig?.ntpServer}'")
                    Log.i(TAG, "   IPv4 config: ${networkConfig?.ipv4Config}")
                }
                config.hasDisplay() -> {
                    displayConfig = config.display
                    Log.i(TAG, "🖥️ Display Config received:")
                    Log.i(TAG, "   Screen timeout: ${displayConfig?.screenOnSecs}s")
                    Log.i(TAG, "   Auto carousel: ${displayConfig?.autoScreenCarouselSecs}s")
                    Log.i(TAG, "   Compass north up: ${displayConfig?.compassNorthTop}")
                    Log.i(TAG, "   Flip screen: ${displayConfig?.flipScreen}")
                    Log.i(TAG, "   Units: ${displayConfig?.units}")
                    Log.i(TAG, "   Display mode: ${displayConfig?.displaymode}")
                }
                config.hasLora() -> {
                    loraConfig = config.lora
                    Log.i(TAG, "📡 LoRa Config received:")
                    Log.i(TAG, "   Use preset: ${loraConfig?.usePreset}")
                    Log.i(TAG, "   Modem preset: ${loraConfig?.modemPreset}")
                    Log.i(TAG, "   Region: ${loraConfig?.region}")
                    Log.i(TAG, "   Bandwidth: ${loraConfig?.bandwidth}")
                    Log.i(TAG, "   Spread factor: ${loraConfig?.spreadFactor}")
                    Log.i(TAG, "   Coding rate: ${loraConfig?.codingRate}")
                    Log.i(TAG, "   Frequency offset: ${loraConfig?.frequencyOffset}")
                    Log.i(TAG, "   Hop limit: ${loraConfig?.hopLimit}")
                    Log.i(TAG, "   TX enabled: ${loraConfig?.txEnabled}")
                    Log.i(TAG, "   TX power: ${loraConfig?.txPower}")
                }
                config.hasBluetooth() -> {
                    bluetoothConfig = config.bluetooth
                    Log.i(TAG, "🔵 Bluetooth Config received:")
                    Log.i(TAG, "   Enabled: ${bluetoothConfig?.enabled}")
                    Log.i(TAG, "   Mode: ${bluetoothConfig?.mode}")
                    Log.i(TAG, "   Fixed PIN: ${bluetoothConfig?.fixedPin}")
                }
                config.hasSecurity() -> {
                    securityConfig = config.security
                    Log.i(TAG, "🔒 Security Config received:")
                    Log.i(TAG, "   Public key size: ${securityConfig?.publicKey?.size()} bytes")
                    Log.i(TAG, "   Private key size: ${securityConfig?.privateKey?.size()} bytes")
                    Log.i(TAG, "   Serial enabled: ${securityConfig?.serialEnabled}")
                    Log.i(TAG, "   Debug log API enabled: ${securityConfig?.debugLogApiEnabled}")
                    Log.i(TAG, "   Is managed: ${securityConfig?.isManaged}")
                }
                config.hasSessionkey() -> {
                    sessionkeyConfig = config.sessionkey
                    Log.i(TAG, "🔑 Sessionkey Config received - Admin authentication enabled")
                    Log.w(TAG, "   ⚠️  Note: Sessionkey data is sensitive and handled internally by device")
                    Log.i(TAG, "   This indicates the device supports admin operations (reboot, factory reset, etc.)")
                }
                else -> {
                    Log.w(TAG, "❓ Unknown Config type received")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Config: ${e.message}", e)
        }
    }
    
    /**
     * Parse and store ModuleConfig response, logging details of each module config type
     */
    private fun parseAndStoreModuleConfig(moduleConfig: com.geeksville.mesh.ModuleConfigProtos.ModuleConfig) {
        try {
            when {
                moduleConfig.hasMqtt() -> {
                    mqttConfig = moduleConfig.mqtt
                    Log.i(TAG, "🔄 MQTT Config received:")
                    Log.i(TAG, "   Enabled: ${mqttConfig?.enabled}")
                    if (mqttConfig?.address?.isNotEmpty() == true) {
                        Log.i(TAG, "   Address: ${mqttConfig?.address}")
                    }
                    if (mqttConfig?.username?.isNotEmpty() == true) {
                        Log.i(TAG, "   Username: ${mqttConfig?.username}")
                    }
                    if (mqttConfig?.password?.isNotEmpty() == true) {
                        Log.i(TAG, "   Password: [REDACTED]")
                    }
                    Log.i(TAG, "   Encryption enabled: ${mqttConfig?.encryptionEnabled}")
                    Log.i(TAG, "   JSON enabled: ${mqttConfig?.jsonEnabled}")
                    if (mqttConfig?.root?.isNotEmpty() == true) {
                        Log.i(TAG, "   Root topic: ${mqttConfig?.root}")
                    }
                }
                moduleConfig.hasSerial() -> {
                    serialConfig = moduleConfig.serial
                    Log.i(TAG, "🔌 Serial Config received:")
                    Log.i(TAG, "   Enabled: ${serialConfig?.enabled}")
                    Log.i(TAG, "   Baud: ${serialConfig?.baud}")
                    Log.i(TAG, "   Timeout: ${serialConfig?.timeout}")
                    Log.i(TAG, "   Mode: ${serialConfig?.mode}")
                    Log.i(TAG, "   RX pin: ${serialConfig?.rxd}")
                    Log.i(TAG, "   TX pin: ${serialConfig?.txd}")
                }
                moduleConfig.hasExternalNotification() -> {
                    externalNotificationConfig = moduleConfig.externalNotification
                    Log.i(TAG, "🔔 External Notification Config received:")
                    Log.i(TAG, "   Enabled: ${externalNotificationConfig?.enabled}")
                    Log.i(TAG, "   Alert message: ${externalNotificationConfig?.alertMessage}")
                    Log.i(TAG, "   Alert bell: ${externalNotificationConfig?.alertBell}")
                    Log.i(TAG, "   Use PWM: ${externalNotificationConfig?.usePwm}")
                    Log.i(TAG, "   Output pin: ${externalNotificationConfig?.output}")
                    Log.i(TAG, "   Output duration: ${externalNotificationConfig?.outputMs}ms")
                }
                moduleConfig.hasStoreForward() -> {
                    storeForwardConfig = moduleConfig.storeForward
                    Log.i(TAG, "📦 Store Forward Config received:")
                    Log.i(TAG, "   Enabled: ${storeForwardConfig?.enabled}")
                    Log.i(TAG, "   Heartbeat: ${storeForwardConfig?.heartbeat}")
                    Log.i(TAG, "   Records: ${storeForwardConfig?.records}")
                    Log.i(TAG, "   History return max: ${storeForwardConfig?.historyReturnMax}")
                    Log.i(TAG, "   History return window: ${storeForwardConfig?.historyReturnWindow}")
                }
                moduleConfig.hasRangeTest() -> {
                    rangeTestConfig = moduleConfig.rangeTest
                    Log.i(TAG, "📏 Range Test Config received:")
                    Log.i(TAG, "   Enabled: ${rangeTestConfig?.enabled}")
                    Log.i(TAG, "   Sender: ${rangeTestConfig?.sender}")
                    Log.i(TAG, "   Save: ${rangeTestConfig?.save}")
                }
                moduleConfig.hasTelemetry() -> {
                    telemetryConfig = moduleConfig.telemetry
                    Log.i(TAG, "📊 Telemetry Config received:")
                    Log.i(TAG, "   Device update interval: ${telemetryConfig?.deviceUpdateInterval}s")
                    Log.i(TAG, "   Environment update interval: ${telemetryConfig?.environmentUpdateInterval}s")
                    Log.i(TAG, "   Environment measurement: ${telemetryConfig?.environmentMeasurementEnabled}")
                    Log.i(TAG, "   Environment screen: ${telemetryConfig?.environmentScreenEnabled}")
                    Log.i(TAG, "   Environment display Fahrenheit: ${telemetryConfig?.environmentDisplayFahrenheit}")
                }
                moduleConfig.hasCannedMessage() -> {
                    cannedMessageConfig = moduleConfig.cannedMessage
                    Log.i(TAG, "📝 Canned Message Config received:")
                    Log.i(TAG, "   Enabled: ${cannedMessageConfig?.enabled}")
                    Log.i(TAG, "   Allow input source: ${cannedMessageConfig?.allowInputSource}")
                    Log.i(TAG, "   Send bell: ${cannedMessageConfig?.sendBell}")
                    Log.i(TAG, "   Enabled: ${cannedMessageConfig?.enabled}")
                    Log.i(TAG, "   Allow input source: ${cannedMessageConfig?.allowInputSource}")
                }
                moduleConfig.hasAudio() -> {
                    audioConfig = moduleConfig.audio
                    Log.i(TAG, "🔊 Audio Config received:")
                    Log.i(TAG, "   Codec2 enabled: ${audioConfig?.codec2Enabled}")
                    Log.i(TAG, "   Bitrate: ${audioConfig?.bitrate}")
                    Log.i(TAG, "   I2S ws: ${audioConfig?.i2SWs}")
                    Log.i(TAG, "   I2S sd: ${audioConfig?.i2SSd}")
                    Log.i(TAG, "   I2S din: ${audioConfig?.i2SDin}")
                    Log.i(TAG, "   I2S sck: ${audioConfig?.i2SSck}")
                }
                moduleConfig.hasRemoteHardware() -> {
                    remoteHardwareConfig = moduleConfig.remoteHardware
                    Log.i(TAG, "🎛️ Remote Hardware Config received:")
                    Log.i(TAG, "   Enabled: ${remoteHardwareConfig?.enabled}")
                    Log.i(TAG, "   Allow undefined pin access: ${remoteHardwareConfig?.allowUndefinedPinAccess}")
                    Log.i(TAG, "   Available pins: ${remoteHardwareConfig?.availablePinsCount} items")
                }
                moduleConfig.hasNeighborInfo() -> {
                    neighborInfoConfig = moduleConfig.neighborInfo
                    Log.i(TAG, "👥 Neighbor Info Config received:")
                    Log.i(TAG, "   Enabled: ${neighborInfoConfig?.enabled}")
                    Log.i(TAG, "   Update interval: ${neighborInfoConfig?.updateInterval}s")
                }
                moduleConfig.hasAmbientLighting() -> {
                    ambientLightingConfig = moduleConfig.ambientLighting
                    Log.i(TAG, "💡 Ambient Lighting Config received:")
                    Log.i(TAG, "   LED state: ${ambientLightingConfig?.ledState}")
                    Log.i(TAG, "   Current: ${ambientLightingConfig?.current}mA")
                    Log.i(TAG, "   Red: ${ambientLightingConfig?.red}")
                    Log.i(TAG, "   Green: ${ambientLightingConfig?.green}")
                    Log.i(TAG, "   Blue: ${ambientLightingConfig?.blue}")
                }
                moduleConfig.hasDetectionSensor() -> {
                    detectionSensorConfig = moduleConfig.detectionSensor
                    Log.i(TAG, "👁️ Detection Sensor Config received:")
                    Log.i(TAG, "   Enabled: ${detectionSensorConfig?.enabled}")
                    Log.i(TAG, "   Minimum broadcast secs: ${detectionSensorConfig?.minimumBroadcastSecs}")
                    Log.i(TAG, "   State broadcast secs: ${detectionSensorConfig?.stateBroadcastSecs}")
                    Log.i(TAG, "   Send bell: ${detectionSensorConfig?.sendBell}")
                    Log.i(TAG, "   Name: ${detectionSensorConfig?.name}")
                    Log.i(TAG, "   Monitor pin: ${detectionSensorConfig?.monitorPin}")
                    Log.i(TAG, "   Use pullup: ${detectionSensorConfig?.usePullup}")
                }
                moduleConfig.hasPaxcounter() -> {
                    paxcounterConfig = moduleConfig.paxcounter
                    Log.i(TAG, "👤 Paxcounter Config received:")
                    Log.i(TAG, "   Enabled: ${paxcounterConfig?.enabled}")
                    Log.i(TAG, "   Update interval: ${paxcounterConfig?.paxcounterUpdateInterval}s")
                }
                else -> {
                    Log.w(TAG, "❓ Unknown ModuleConfig type received")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing ModuleConfig: ${e.message}", e)
        }
    }
    
    /**
     * Parse and store Channel response
     */
    private fun parseAndStoreChannel(channel: com.geeksville.mesh.ChannelProtos.Channel) {
        try {
            channelDatabase[channel.index] = channel
            Log.i(TAG, "📻 Channel Config received:")
            Log.i(TAG, "   Index: ${channel.index}")
            if (channel.hasSettings()) {
                val settings = channel.settings
                Log.i(TAG, "   Name: '${settings.name}'")
                if (settings.psk.size() > 0) {
                    Log.i(TAG, "   PSK: ${settings.psk.size()} bytes [ENCRYPTED]")
                } else {
                    Log.i(TAG, "   PSK: [NONE - Open channel]")
                }
                if (settings.hasModuleSettings()) {
                    Log.i(TAG, "   Module settings configured")
                } else {
                    Log.i(TAG, "   No module settings")
                }
                Log.i(TAG, "   Uplink enabled: ${settings.uplinkEnabled}")
                Log.i(TAG, "   Downlink enabled: ${settings.downlinkEnabled}")
            }
            Log.i(TAG, "   Role: ${channel.role}")
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Channel: ${e.message}", e)
        }
    }
    
    /**
     * Start processing incoming and outgoing messages
     */
    private fun startMessageProcessing() {
        // Process incoming messages
        scope.launch {
            for (fromRadio in incomingMessages) {
                processIncomingMessage(fromRadio)
            }
        }
        
        // Process outgoing messages
        scope.launch {
            for (toRadio in outgoingMessages) {
                // Log before converting to bytes and sending
                Log.v(TAG, "Processing outgoing ToRadio message...")
                handleSendToRadio(toRadio.toByteArray())
            }
        }
    }
    
    /**
     * Process incoming message from radio
     */
    private fun processIncomingMessage(fromRadio: MeshProtos.FromRadio) {
        when {
            fromRadio.queueStatus != null -> {
                val queueStatus = fromRadio.queueStatus
                val status = QueueStatus(
                    free = queueStatus.free,
                    maxlen = queueStatus.maxlen,
                    res = queueStatus.res,
                    meshPacketId = queueStatus.meshPacketId
                )
                _currentQueueStatus.value = status
                _availableQueueSlots.value = status.free
                
                Log.i(TAG, "Queue Status: ${status.free} free slots out of ${status.maxlen} total")
                
                // Process any pending chunks that can now be sent
                processPendingChunks()
            }
            
            fromRadio.packet != null -> {
                val packet = fromRadio.packet
                messagesReceived++
                Log.d(TAG, "Received packet - Port: ${packet.portNum}, From: ${packet.from?.let { "0x${it.toLong().and(0xFFFFFFFFL).toString(16)}" } ?: "null"}")
                
                // Filter for ATAK ports, admin messages, and routing messages
                when (packet.portNum) {
                    MeshProtos.PORTNUMS_ATAK_PLUGIN -> {
                        Log.i(TAG, "Received ATAK Plugin message")
                        notifyAtakPluginMessage(packet.bytes, packet.from?.toString())
                    }
                    MeshProtos.PORTNUMS_ATAK_FORWARDER -> {
                        Log.i(TAG, "Received ATAK Forwarder message")
                        notifyAtakForwarderMessage(packet.bytes, packet.from?.toString())
                    }
                    MeshProtos.PORTNUMS_ADMIN -> {
                        Log.i(TAG, "Received Admin message")
                        handleAdminMessage(packet)
                    }
                    MeshProtos.PORTNUMS_ROUTING_APP -> {
                        Log.d(TAG, "Received Routing message (potential ACK)")
                        handleRoutingMessage(packet)
                    }
                    else -> {
                        // Ignore other ports
                        Log.d(TAG, "Ignoring message on port ${packet.portNum}")
                    }
                }
            }
            
            fromRadio.myInfo != null -> {
                myNodeInfo = fromRadio.myInfo
                Log.i(TAG, "Received and stored MyNodeInfo: 0x${myNodeInfo?.myNodeNum?.toUInt()?.toString(16)}")

                // Process any queued admin messages now that we have the node ID
                configManager?.processPendingAdminMessages()
            }
            
            fromRadio.nodeInfo != null -> {
                val nodeInfo = fromRadio.nodeInfo
                val nodeKey = nodeInfo.id.toString()
                nodeDatabase[nodeKey] = nodeInfo
                Log.i(TAG, "Received NodeInfo: ${nodeInfo.longName} (0x${nodeInfo.id.toLong().and(0xFFFFFFFFL).toString(16)})")
                
                // Check if this is our own node and update the stored device name
                myNodeInfo?.let { myInfo ->
                    if (nodeInfo.id == myInfo.myNodeNum) {
                        Log.i(TAG, "Received NodeInfo for our own device: ${nodeInfo.longName}")
                        updateStoredDeviceName(nodeInfo.longName)
                    }
                }
            }
            
            fromRadio.metadata != null -> {
                Log.i(TAG, "Received DeviceMetadata from device")
                deviceMetadata = fromRadio.metadata


                configManager?.handleDeviceMetadata(fromRadio.metadata)
            }
            
            fromRadio.config != null -> {
                Log.i(TAG, "Received Config response from device")
                parseAndStoreConfig(fromRadio.config)
                configManager?.handleConfigResponse(fromRadio.config)
            }
            
            fromRadio.moduleConfig != null -> {
                Log.i(TAG, "Received ModuleConfig response from device")
                parseAndStoreModuleConfig(fromRadio.moduleConfig)
                configManager?.handleModuleConfigResponse(fromRadio.moduleConfig)
            }
            
            fromRadio.channel != null -> {
                Log.i(TAG, "Received Channel response from device")
                parseAndStoreChannel(fromRadio.channel)
                configManager?.handleChannelResponse(fromRadio.channel)
            }
            
            fromRadio.configCompleteId != null -> {
                Log.i(TAG, "Configuration complete: ${fromRadio.configCompleteId}")
                isConfigurationReceived = true // Mark that we have received configuration
                
                // Notify config manager if it's waiting for config complete
                configManager?.handleConfigComplete(fromRadio.configCompleteId)
                
                // Update connection state if appropriate
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    Log.i(TAG, "Radio is ready (received myInfo), device configured")
                    radioReadyTimeoutJob?.cancel() // Cancel timeout since we got myInfo
                    configManager?.analyzeTakConfigurationNeeds()
                   //_connectionState.value = ConnectionState.CONFIGURED
                }
            }
            
            fromRadio.logRecord != null -> {
                Log.d(TAG, "Device log: ${fromRadio.logRecord}")
            }
            
            fromRadio.routingError != null -> {
                // Handle routing error (packet failed to deliver)
                val routingError = fromRadio.routingError
                val packetId = routingError.originalId
                val errorReason = routingError.errorReason.toString()
                
                val pending = pendingPackets.remove(packetId)
                if (pending != null) {
                    Log.w(TAG, "Packet 0x${packetId.toLong().and(0xFFFFFFFFL).toString(16)} failed: routing error $errorReason")
                    pending.callback?.invoke(false)
                    pending.enhancedCallback?.invoke(AckResult(
                        success = false, 
                        errorReason = "Routing error: $errorReason"
                    ))
                } else {
                    Log.w(TAG, "Routing error for unknown packet ID: 0x${packetId.toLong().and(0xFFFFFFFFL).toString(16)}")
                }
            }
            
            fromRadio.ackId != null -> {
                // Handle acknowledgment
                val ackId = fromRadio.ackId
                
                Log.d(TAG, "Received ACK for packet 0x${ackId.toLong().and(0xFFFFFFFFL).toString(16)}")
                Log.d(TAG, "Current pending packets: ${pendingPackets.keys.map { "0x${it.toLong().and(0xFFFFFFFFL).toString(16)}" }}")
                
                // Check if this is a chunk ACK
                val chunkInfo = findChunkByPacketId(ackId.toUInt())
                if (chunkInfo != null) {
                    Log.i(TAG, "Chunk ${chunkInfo.chunkIndex}/${chunkInfo.totalChunks} acknowledged (requestId: ${chunkInfo.requestId})")
                    pendingChunks.remove(chunkInfo.chunkId)
                    chunkAckCallbacks.remove(chunkInfo.chunkId)?.invoke(AckResult(success = true, errorCode = 0))
                    
                    // Update available queue slots
                    _availableQueueSlots.value = _availableQueueSlots.value + 1u
                }
                
                val pending = pendingPackets.remove(ackId)
                if (pending != null) {
                    Log.d(TAG, "Packet 0x${ackId.toLong().and(0xFFFFFFFFL).toString(16)} acknowledged")
                    pending.callback?.invoke(true)
                    pending.enhancedCallback?.invoke(AckResult(success = true, errorCode = 0))
                } else if (chunkInfo == null) {
                    Log.w(TAG, "Received ACK for unknown packet 0x${ackId.toLong().and(0xFFFFFFFFL).toString(16)} - packet may have timed out or was never sent")
                }
            }
        }
    }
    
    /**
     * Notify handlers of ATAK Plugin message
     */
    private fun notifyAtakPluginMessage(data: ByteArray, fromNode: String?) {
        atakMessageHandlers.forEach { handler ->
            try {
                handler.onAtakPluginMessage(data, fromNode)
            } catch (e: Exception) {
                Log.e(TAG, "Error in ATAK Plugin message handler", e)
            }
        }
    }
    
    /**
     * Notify handlers of ATAK Forwarder message
     */
    private fun notifyAtakForwarderMessage(data: ByteArray, fromNode: String?) {
        atakMessageHandlers.forEach { handler ->
            try {
                handler.onAtakForwarderMessage(data, fromNode)
            } catch (e: Exception) {
                Log.e(TAG, "Error in ATAK Forwarder message handler", e)
            }
        }
    }
    
    /**
     * Called when BLE is connected and ready for configuration
     */
    override fun onConnect() {
        Log.i(TAG, "Bluetooth connected, checking radio state...")
        
        // Initialize configuration manager
        configManager = MeshtasticConfigManager(this)
        
        // Get saved channel password from UI and pass it to config manager
        val savedPassword = getChannelPasswordFromUI()
        if (!savedPassword.isNullOrEmpty()) {
            Log.i(TAG, "Found saved channel password, applying to configuration")
            configManager?.setChannelPassword(savedPassword)
        } else {
            Log.i(TAG, "No saved channel password found")
            // Show Toast warning that PSK is required for secure operation
            scope.launch(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    context,
                    "Meshtastic connected but not ready: Configure a channel password in Settings for secure communication",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
        
        // Monitor configuration state
        scope.launch {
            configManager?.configState?.collect { state ->
                when (state) {
                    MeshtasticConfigManager.ConfigurationState.CONFIGURED -> {
                        Log.i(TAG, "Device configured for TAK operation")
                        // Continue with normal config request
                        sendConfigRequest()
                    }
                    MeshtasticConfigManager.ConfigurationState.ERROR -> {
                        Log.e(TAG, "Device configuration error")
                        // Still request config to understand device state
                        sendConfigRequest()
                    }
                    else -> {
                        // Configuration in progress
                    }
                }
            }
        }
        
        // Configuration will be started when interface is fully ready
        // The region is already set manually on the device
        Log.i(TAG, "Waiting for interface to be ready for configuration...")
    }
    
    /**
     * Apply TAK optimized configuration to the device
     */
    fun applyTakOptimizedConfiguration(callback: (Boolean) -> Unit) {
        if (connectionState.value != ConnectionState.CONFIGURED || configManager == null) {
            Log.w(TAG, "Cannot apply TAK configuration - device not configured")
            callback(false)
            return
        }
        
        Log.i(TAG, "Applying TAK optimized configuration")
        configManager?.applyTakConfiguration { success ->
            callback(success)
        } ?: callback(false)
    }
    
    /**
     * Get current device configuration for display
     */
    fun getCurrentDeviceConfiguration(callback: (String?) -> Unit) {
        if (connectionState.value != ConnectionState.CONFIGURED || configManager == null) {
            Log.w(TAG, "Cannot get configuration - device not configured")
            callback(null)
            return
        }
        
        Log.i(TAG, "Requesting current device configuration")
        configManager?.getCurrentConfiguration { config ->
            callback(config)
        } ?: callback(null)
    }
    
    /**
     * Send emergency region configuration to ensure radio can function
     * This bypasses normal state checks because an unconfigured radio won't work otherwise
     */
    private fun sendEmergencyRegionConfig() {
        Log.i(TAG, "Sending emergency LoRa region config (US)")
        
        // Build minimal region config
        val regionConfig = ConfigProtos.Config.newBuilder()
            .setLora(
                ConfigProtos.Config.LoRaConfig.newBuilder()
                    .setRegion(ConfigProtos.Config.LoRaConfig.RegionCode.US)
                    .build()
            )
            .build()
        
        // Build admin message
        val adminMsg = AdminProtos.AdminMessage.newBuilder()
            .setSetConfig(regionConfig)
            .build()
        
        // Create data packet using the local wrapper
        val dataPacket = MeshProtos.DataPacket(
            to = MeshProtos.ID_BROADCAST,  // Broadcast to self
            from = 0u,  // Will be set by device
            id = (System.currentTimeMillis().toInt() and 0x7FFFFFFF).toUInt(),
            bytes = adminMsg.toByteArray(),
            portNum = Portnums.PortNum.ADMIN_APP_VALUE,
            channel = 0,  // Admin channel
            wantAck = false,
            hopLimit = 0  // Local only
        )
        
        // Send as ToRadio packet - force send even if not fully configured
        val toRadio = MeshProtos.ToRadio(
            packet = dataPacket,
            wantConfigId = null
        )
        
        // Force send directly to interface, bypassing state checks
        when {
            bluetoothInterface != null -> {
                Log.i(TAG, "Force-sending region config via Bluetooth")
                bluetoothInterface?.sendData(toRadio.toByteArray())
            }
            usbInterface != null -> {
                Log.i(TAG, "Force-sending region config via USB")
                usbInterface?.sendData(toRadio.toByteArray())
            }
            else -> {
                Log.w(TAG, "No interface available for emergency region config")
            }
        }
    }
    
    /**
     * Send ToRadio message to device
     */
    fun sendToRadio(toRadio: MeshProtos.ToRadio) {
        // Log the packet
        logToRadioPacket(toRadio)
        
        // Send directly to the interface for immediate transmission
        when {
            bluetoothInterface != null -> {
                bluetoothInterface?.sendData(toRadio.toByteArray())
            }
            usbInterface != null -> {
                usbInterface?.sendData(toRadio.toByteArray())
            }
            else -> {
                Log.w(TAG, "No interface available to send packet")
            }
        }
        
        // Also queue it for processing
        scope.launch {
            outgoingMessages.send(toRadio)
        }
    }
    
    private fun sendConfigRequest() {
        // Send wantConfig request to fetch device configuration
        // Using same approach as official Meshtastic-Android app
        val CONFIG_NONCE = 69420
        
        Log.i(TAG, "Requesting configuration with nonce: $CONFIG_NONCE")
        val toRadio = MeshProtos.ToRadio(wantConfigId = CONFIG_NONCE)
        
        sendToRadio(toRadio)
    }
    
    /**
     * Start periodic heartbeat to keep connection alive
     */
    private fun startHeartbeat() {
        stopHeartbeat() // Cancel any existing heartbeat job
        
        Log.i(TAG, "Starting heartbeat timer")
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MILLIS)
                if (_connectionState.value == ConnectionState.CONFIGURED) {
                    sendHeartbeat()
                } else {
                    Log.d(TAG, "Skipping heartbeat - not configured")
                }
            }
        }
    }
    
    /**
     * Stop heartbeat timer
     */
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        Log.i(TAG, "Stopped heartbeat timer")
    }
    
    /**
     * Send heartbeat message to keep connection alive
     */
    private fun sendHeartbeat() {
        val now = System.currentTimeMillis()
        
        // Check if it's time to send a heartbeat
        if (now - lastHeartbeatMillis >= HEARTBEAT_INTERVAL_MILLIS) {
            Log.i(TAG, "Sending ToRadio heartbeat")
            
            // Create heartbeat message using the local MeshProtos wrapper
            val heartbeatMessage = MeshProtos.ToRadio(
                packet = null,
                wantConfigId = null,
                heartbeat = true  // This is a heartbeat message
            )
            
            // Send the heartbeat directly to the interface
            when {
                bluetoothInterface != null -> {
                    bluetoothInterface?.sendData(heartbeatMessage.toByteArray())
                }
                usbInterface != null -> {
                    usbInterface?.sendData(heartbeatMessage.toByteArray())
                }
                else -> {
                    Log.w(TAG, "No interface available to send heartbeat")
                }
            }
            
            lastHeartbeatMillis = now
        }
    }
    
    /**
     * Start periodic message polling
     * This ensures we don't miss messages after the queue has been drained
     */
    private fun startMessagePolling() {
        // Disabled periodic polling - now relying on fromNum notifications
        Log.i(TAG, "Message polling disabled - using fromNum notifications")
    }
    
    /**
     * Stop message polling
     */
    private fun stopMessagePolling() {
        messagePollingJob?.cancel()
        messagePollingJob = null
        Log.i(TAG, "Stopped message polling timer")
    }
    
    /**
     * Poll for new messages by sending a lightweight request
     * This triggers the radio to check for new messages
     */
    private fun pollForMessages() {
        // Don't actually send anything - the periodic reads from BluetoothInterface
        // after queue drain already handle checking for new messages
        // Sending empty messages causes issues with the write queue
        Log.d(TAG, "Poll timer fired - relying on automatic read retries")
    }
    
    /**
     * Send ATAK Forwarder chunks with queue awareness
     * Queues up as many chunks as possible based on available queue slots
     * Returns a request ID for tracking
     */
    fun sendAtakForwarderChunked(
        chunks: List<ByteArray>,
        destNode: UInt? = null,
        requestId: String = java.util.UUID.randomUUID().toString(),
        onChunkAck: ((chunkIndex: Int, totalChunks: Int, success: Boolean, requestId: String) -> Unit)? = null,
        onComplete: ((allSuccess: Boolean, requestId: String) -> Unit)? = null
    ): String {
        Log.i(TAG, "Starting chunked send: ${chunks.size} chunks, requestId: $requestId")
        
        if (_connectionState.value != ConnectionState.CONFIGURED) {
            Log.w(TAG, "Not ready to send chunks, state: ${_connectionState.value}")
            onComplete?.invoke(false, requestId)
            return requestId
        }
        
        // Create chunk infos but don't send yet
        chunks.forEachIndexed { index, chunkData ->
            val chunkId = "${requestId}_$index"
            val packetId = (System.currentTimeMillis().toInt() and 0x7FFFFFFF) + index
            
            val chunkInfo = ChunkInfo(
                chunkId = chunkId,
                packetId = packetId,
                chunkIndex = index,
                totalChunks = chunks.size,
                requestId = requestId,
                data = chunkData,
                destNode = destNode
            )
            
            pendingChunks[chunkId] = chunkInfo
            
            // Set up callback for this chunk
            chunkAckCallbacks[chunkId] = { result ->
                onChunkAck?.invoke(index, chunks.size, result.success, requestId)
                
                // Check if all chunks are complete
                val remainingChunks = pendingChunks.values.filter { it.requestId == requestId }
                if (remainingChunks.isEmpty()) {
                    Log.i(TAG, "All chunks completed for requestId: $requestId")
                    onComplete?.invoke(true, requestId)
                }
            }
        }
        
        // Request current queue status to trigger chunk processing
        requestQueueStatus()
        
        return requestId
    }
    
    /**
     * Process pending chunks based on available queue slots
     */
    private fun processPendingChunks() {
        val availableSlots = _availableQueueSlots.value.toInt()
        if (availableSlots <= 0) {
            Log.d(TAG, "No available queue slots, waiting...")
            return
        }
        
        val chunksToSend = pendingChunks.values
            .sortedBy { it.timestamp }
            .take(availableSlots)
        
        if (chunksToSend.isEmpty()) {
            Log.d(TAG, "No pending chunks to send")
            return
        }
        
        Log.i(TAG, "Processing ${chunksToSend.size} chunks (${availableSlots} slots available)")
        
        chunksToSend.forEach { chunkInfo ->
            sendChunkInternal(chunkInfo)
            // Decrease available slots counter
            _availableQueueSlots.value = maxOf(0u, _availableQueueSlots.value - 1u)
        }
    }
    
    /**
     * Send individual chunk
     */
    private fun sendChunkInternal(chunkInfo: ChunkInfo) {
        val fromNode = myNodeInfo?.myNodeNum
        
        // Add chunk header in the format expected by receiver: "index_data"
        val chunkHeader = "${chunkInfo.chunkIndex}_".toByteArray(Charsets.UTF_8)
        val combinedData = ByteArray(chunkHeader.size + chunkInfo.data.size)
        System.arraycopy(chunkHeader, 0, combinedData, 0, chunkHeader.size)
        System.arraycopy(chunkInfo.data, 0, combinedData, chunkHeader.size, chunkInfo.data.size)
        
        val packet = MeshProtos.createAtakForwarderPacket(
            combinedData,  // Send header + data 
            chunkInfo.destNode, 
            fromNode,
            packetId = chunkInfo.packetId.toUInt()
        )
        
        Log.d(TAG, "Sending chunk ${chunkInfo.chunkIndex}/${chunkInfo.totalChunks} (requestId: ${chunkInfo.requestId}, packetId: 0x${chunkInfo.packetId.toLong().and(0xFFFFFFFFL).toString(16)})")
        
        // Track this packet for ACK handling
        pendingPackets[chunkInfo.packetId] = PendingPacket(
            chunkInfo.packetId,
            enhancedCallback = { result ->
                if (!result.success) {
                    // Handle failed chunk
                    pendingChunks.remove(chunkInfo.chunkId)
                    chunkAckCallbacks.remove(chunkInfo.chunkId)?.invoke(result)
                    // Don't increment available slots since the packet failed
                }
            }
        )
        
        // Schedule timeout check
        scope.launch {
            delay(PACKET_TIMEOUT)
            checkPacketTimeout(chunkInfo.packetId)
        }
        
        val toRadio = MeshProtos.ToRadio(packet = packet)
        logToRadioPacket(toRadio)
        
        scope.launch {
            outgoingMessages.send(toRadio)
        }
    }
    
    /**
     * Find chunk by packet ID
     */
    private fun findChunkByPacketId(packetId: UInt): ChunkInfo? {
        return pendingChunks.values.find { it.packetId == packetId.toInt() }
    }
    
    /**
     * Request queue status from device
     */
    fun requestQueueStatus() {
        Log.d(TAG, "Requesting queue status via heartbeat")
        sendHeartbeat()
    }
    
    /**
     * Get current queue status info
     */
    fun getQueueInfo(): Pair<UInt, UInt> {
        val status = _currentQueueStatus.value
        return Pair(status?.free ?: 0u, status?.maxlen ?: 0u)
    }
    
    /**
     * Get number of messages received from the radio
     */
    fun getMessagesReceivedCount(): Int {
        return messagesReceived
    }
}