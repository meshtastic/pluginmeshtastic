package com.atakmap.android.pluginmeshtastic.meshtastic

import com.atakmap.android.maps.MapView
import com.google.protobuf.ByteString
import com.geeksville.mesh.MeshProtos
import com.geeksville.mesh.Portnums
import kotlin.random.Random

/**
 * Simplified Meshtastic protocol implementation for ATAK integration
 * This handles the core message structures needed for ATAK communication
 */
object MeshProtos {
    
    // Port numbers for ATAK integration
    const val PORTNUMS_ATAK_PLUGIN = 72
    const val PORTNUMS_ATAK_FORWARDER = 257
    const val PORTNUMS_ADMIN = 6  // Admin app for configuration
    const val PORTNUMS_ROUTING_APP = 5  // Routing app for routing messages
    
    // Special node addresses
    const val NODENUM_BROADCAST = 0xFFFFFFFF.toInt()
    val ID_BROADCAST = NODENUM_BROADCAST.toUInt() // For backward compatibility
    
    // Message types
    enum class MessageType(val value: Int) {
        PACKET(1),
        MY_INFO(3),
        NODE_INFO(4),
        CONFIG(5),
        LOG_RECORD(6),
        FROM_RADIO(7),
        TO_RADIO(8)
    }
    
    /**
     * Data packet for mesh communication
     */
    data class DataPacket(
        val to: UInt? = NODENUM_BROADCAST.toUInt(),
        val from: UInt? = null,
        val id: UInt = generatePacketId().toUInt(),
        val bytes: ByteArray,
        val portNum: Int,
        val channel: Int = 0,
        val wantAck: Boolean = true,
        val hopLimit: Int = 3,
        val wantResponse: Boolean = false
    ) {
        companion object {
            private var packetIdCounter = 0x10000000 +
                    (Random(MapView.getMapView().selfMarker.uid.hashCode() +
                            System.currentTimeMillis()).nextInt())
            private val idLock = Any()
            
            @Synchronized
            fun generatePacketId(): Int {
                synchronized(idLock) {
                    // Increment and wrap around if we exceed max int
                    // Start from 0x10000000 to avoid low IDs that might conflict
                    val currentId = packetIdCounter
                    packetIdCounter = if (packetIdCounter == Int.MAX_VALUE) {
                        0x10000000 // Reset to initial value to avoid overflow
                    } else {
                        packetIdCounter + 1
                    }
                    return currentId
                }
            }
        }
        
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            
            other as DataPacket
            
            if (to != other.to) return false
            if (from != other.from) return false
            if (id != other.id) return false
            if (!bytes.contentEquals(other.bytes)) return false
            if (portNum != other.portNum) return false
            if (channel != other.channel) return false
            if (wantAck != other.wantAck) return false
            if (hopLimit != other.hopLimit) return false
            if (wantResponse != other.wantResponse) return false
            
            return true
        }
        
        override fun hashCode(): Int {
            var result = to?.hashCode() ?: 0
            result = 31 * result + (from?.hashCode() ?: 0)
            result = 31 * result + id.hashCode()
            result = 31 * result + bytes.contentHashCode()
            result = 31 * result + portNum
            result = 31 * result + channel
            result = 31 * result + wantAck.hashCode()
            result = 31 * result + hopLimit
            result = 31 * result + wantResponse.hashCode()
            return result
        }
    }
    
    /**
     * Message sent to radio - uses Google protobuf serialization
     */
    data class ToRadio(
        val packet: DataPacket? = null,
        val wantConfigId: Int? = null,
        val heartbeat: Boolean = false // Simple heartbeat flag
    ) {
        fun toByteArray(): ByteArray {
            // Build protobuf ToRadio message
            val builder = MeshProtos.ToRadio.newBuilder()
            
            // Add packet if present
            packet?.let { pkt ->
                val meshPacket = MeshProtos.MeshPacket.newBuilder()
                    .setId(pkt.id.toInt())
                    .setChannel(pkt.channel)
                    .setHopLimit(pkt.hopLimit)
                    .setWantAck(pkt.wantAck)
                
                // Set source address if provided
                pkt.from?.let { fromNodeId ->
                    meshPacket.setFrom(fromNodeId.toInt())
                }
                
                // Set destination
                val toNodeId = pkt.to ?: NODENUM_BROADCAST.toUInt()
                meshPacket.setTo(toNodeId.toInt())
                
                // Set payload data
                val dataBuilder = MeshProtos.Data.newBuilder()
                    .setPortnum(Portnums.PortNum.forNumber(pkt.portNum))
                    .setPayload(ByteString.copyFrom(pkt.bytes))
                    .setWantResponse(pkt.wantResponse)
                
                meshPacket.decoded = dataBuilder.build()
                builder.packet = meshPacket.build()
            }
            
            // Add wantConfigId if present
            wantConfigId?.let {
                builder.wantConfigId = it
            }
            
            // Add heartbeat if set
            if (heartbeat) {
                builder.heartbeat = MeshProtos.Heartbeat.getDefaultInstance()
            }
            
            return builder.build().toByteArray()
        }
    }
    
    /**
     * Routing error information
     */
    data class RoutingError(
        val originalId: Int,
        val errorReason: String? = null
    )
    
    /**
     * Queue status from radio
     */
    data class QueueStatus(
        val free: UInt,
        val maxlen: UInt,
        val res: Int = 0,
        val meshPacketId: UInt = 0u
    )
    
    /**
     * Message received from radio
     */
    data class FromRadio(
        val packet: DataPacket? = null,
        val myInfo: MyNodeInfo? = null,
        val nodeInfo: NodeInfo? = null,
        val configCompleteId: Int? = null,
        val logRecord: String? = null,
        val routingError: RoutingError? = null,
        val ackId: Int? = null,
        val queueStatus: QueueStatus? = null,
        val metadata: com.geeksville.mesh.MeshProtos.DeviceMetadata? = null,
        val config: com.geeksville.mesh.ConfigProtos.Config? = null,
        val moduleConfig: com.geeksville.mesh.ModuleConfigProtos.ModuleConfig? = null,
        val channel: com.geeksville.mesh.ChannelProtos.Channel? = null
    ) {
        companion object {
            fun parseFrom(data: ByteArray): FromRadio? {
                return try {
                    // Parse using Google protobuf
                    val protoFromRadio = MeshProtos.FromRadio.parseFrom(data)

                    when {
                        protoFromRadio.hasPacket() -> {
                            val meshPacket = protoFromRadio.packet

                            // Check if this is an implicit ACK (a packet was successfully received)
                            // In Meshtastic, when we receive a packet with wantAck set and it was for us,
                            // we should treat it as acknowledged
                            if (meshPacket.from != 0 && meshPacket.id != 0) {
                                // Check if this packet has decoded data
                                if (meshPacket.hasDecoded()) {
                                    val decoded = meshPacket.decoded
                                    
                                    // Check if this is a routing acknowledgment
                                    if (decoded.portnumValue == Portnums.PortNum.ROUTING_APP_VALUE) {
                                        android.util.Log.d("MeshProtos", "Found port 5 (ROUTING_APP) packet - attempting to parse as routing message")
                                        android.util.Log.d("MeshProtos", "Payload bytes: ${decoded.payload.toByteArray().joinToString("") { "%02x".format(it) }}")
                                        // Parse the routing packet to determine if it's an ACK or error
                                        try {
                                            val routing = MeshProtos.Routing.parseFrom(decoded.payload)
                                            android.util.Log.d("MeshProtos", "Successfully parsed Routing message")
                                            android.util.Log.d("MeshProtos", "Routing hasErrorReason: ${routing.hasErrorReason()}")
                                            if (routing.hasErrorReason()) {
                                                android.util.Log.d("MeshProtos", "Routing errorReason: ${routing.errorReason}")
                                            }
                                            
                                            // The request_id field in the Data message should tell us which original packet this is about
                                            val originalPacketId = if (decoded.requestId != 0) decoded.requestId else meshPacket.id
                                            
                                            when {
                                                // If there's an error reason, this is a NACK/failure
                                                routing.hasErrorReason() && routing.errorReason != MeshProtos.Routing.Error.NONE -> {
                                                    // This is a routing error (NACK)
                                                    FromRadio(
                                                        routingError = RoutingError(
                                                            originalId = originalPacketId,
                                                            errorReason = routing.errorReason.name
                                                        )
                                                    )
                                                }
                                                // If it's a routing packet without an error, it's likely an implicit ACK
                                                // This happens when the packet was successfully delivered
                                                else -> {
                                                    // This is an ACK - the packet was successfully routed
                                                    FromRadio(ackId = originalPacketId)
                                                }
                                            }
                                        } catch (e: Exception) {
                                            // If we can't parse the routing packet, fall back to simple ACK handling
                                            val ackPacketId = if (decoded.requestId != 0) decoded.requestId else meshPacket.id
                                            android.util.Log.w("MeshProtos", "Failed to parse routing packet, treating as ACK: ${e.message}")
                                            FromRadio(ackId = ackPacketId)
                                        }
                                    } else {
                                        // Regular data packet
                                        FromRadio(
                                            packet = DataPacket(
                                                to = meshPacket.to.toUInt(),
                                                from = meshPacket.from.toUInt(),
                                                id = meshPacket.id.toUInt(),
                                                bytes = decoded.payload.toByteArray(),
                                                portNum = decoded.portnumValue,
                                                channel = meshPacket.channel,
                                                hopLimit = meshPacket.hopLimit,
                                                wantAck = meshPacket.wantAck
                                            )
                                        )
                                    }
                                } else {
                                    // Encrypted packet or other non-decoded packet
                                    null
                                }
                            } else {
                                null
                            }
                        }
                        
                        protoFromRadio.hasMyInfo() -> {
                            val myInfo = protoFromRadio.myInfo
                            FromRadio(
                                myInfo = MyNodeInfo(
                                    myNodeNum = myInfo.myNodeNum.toUInt(),
                                    hasGps = false // GPS status is not available in current protobuf
                                )
                            )
                        }
                        
                        protoFromRadio.hasNodeInfo() -> {
                            val nodeInfo = protoFromRadio.nodeInfo
                            FromRadio(
                                nodeInfo = NodeInfo(
                                    id = nodeInfo.num.toUInt(),
                                    longName = if (nodeInfo.hasUser()) nodeInfo.user.longName else "",
                                    shortName = if (nodeInfo.hasUser()) nodeInfo.user.shortName else ""
                                )
                            )
                        }
                        
                        protoFromRadio.hasConfigCompleteId() -> {
                            FromRadio(configCompleteId = protoFromRadio.configCompleteId)
                        }
                        
                        protoFromRadio.hasLogRecord() -> {
                            FromRadio(logRecord = protoFromRadio.logRecord.message)
                        }
                        
                        protoFromRadio.hasQueueStatus() -> {
                            val queueStatus = protoFromRadio.queueStatus
                            FromRadio(
                                queueStatus = QueueStatus(
                                    free = queueStatus.free.toUInt(),
                                    maxlen = queueStatus.maxlen.toUInt(),
                                    res = queueStatus.res,
                                    meshPacketId = queueStatus.meshPacketId.toUInt()
                                )
                            )
                        }
                        
                        protoFromRadio.hasMetadata() -> {
                            FromRadio(metadata = protoFromRadio.metadata)
                        }
                        
                        protoFromRadio.hasConfig() -> {
                            FromRadio(config = protoFromRadio.config)
                        }
                        
                        protoFromRadio.hasModuleConfig() -> {
                            FromRadio(moduleConfig = protoFromRadio.moduleConfig)
                        }
                        
                        protoFromRadio.hasChannel() -> {
                            FromRadio(channel = protoFromRadio.channel)
                        }
                        
                        else -> {
                            // Unknown or unhandled message type
                            null
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
        }
    }
    
    /**
     * Basic node information
     */
    data class NodeInfo(
        val id: UInt,
        val longName: String,
        val shortName: String = id.toString().takeLast(4),
        val hwModel: String = "UNKNOWN"
    )
    
    /**
     * Current node information
     */
    data class MyNodeInfo(
        val myNodeNum: UInt,
        val hasGps: Boolean = false,
        val maxChannels: Int = 8
    )
    
    /**
     * Helper functions for creating ATAK messages
     */
    
    fun createAtakForwarderPacket(
        data: ByteArray, 
        destNode: UInt? = null, 
        fromNode: UInt? = null,
        packetId: UInt? = null
    ): DataPacket {
        return DataPacket(
            to = destNode ?: NODENUM_BROADCAST.toUInt(),
            from = fromNode,
            id = packetId ?: DataPacket.generatePacketId().toUInt(),
            bytes = data,
            portNum = PORTNUMS_ATAK_FORWARDER,
            channel = 0,
            hopLimit = 3,
            wantAck = true,
            wantResponse = false  // ATAK devices don't send application-level responses
        )
    }
    
    fun createAtakPluginPacket(
        data: ByteArray, 
        destNode: UInt? = null, 
        fromNode: UInt? = null,
        packetId: UInt? = null
    ): DataPacket {
        return DataPacket(
            to = destNode ?: NODENUM_BROADCAST.toUInt(),
            from = fromNode,
            id = packetId ?: DataPacket.generatePacketId().toUInt(),
            bytes = data,
            portNum = PORTNUMS_ATAK_PLUGIN,
            channel = 0,
            hopLimit = 3,
            wantAck = true,
            wantResponse = false  // ATAK devices don't send application-level responses
        )
    }
}