package com.atakmap.android.pluginmeshtastic.meshtastic

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

class UsbSerialInterface(
    private val context: Context,
    private val devicePath: String,
    private val callback: RadioCallback
) : SerialInputOutputManager.Listener {
    
    companion object {
        private const val TAG = "UsbSerialInterface"
        private const val BAUD_RATE = 921600
        private const val DATA_BITS = 8
        private const val STOP_BITS = UsbSerialPort.STOPBITS_1
        private const val PARITY = UsbSerialPort.PARITY_NONE
        
        // Protocol framing
        private const val START1 = 0x94.toByte()
        private const val START2 = 0xC3.toByte()
        private const val MAX_PACKET_SIZE = 512
    }
    
    private var usbManager: UsbManager? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    
    private val _connectionState = MutableStateFlow(BluetoothInterface.ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BluetoothInterface.ConnectionState> = _connectionState
    
    private val writeQueue = LinkedBlockingQueue<ByteArray>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Buffer for assembling incoming packets
    private val receiveBuffer = ByteArrayOutputStream()
    private var expectingPacket = false
    private var expectedPacketLength = 0
    
    fun connect() {
        Log.i(TAG, "Connecting to USB device: $devicePath")
        _connectionState.value = BluetoothInterface.ConnectionState.CONNECTING
        
        try {
            usbManager = context.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
            
            // Find the USB device
            val device = findUsbDevice()
            if (device == null) {
                Log.e(TAG, "USB device not found")
                _connectionState.value = BluetoothInterface.ConnectionState.DISCONNECTED
                return
            }
            
            // Get driver for the device
            val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            if (availableDrivers.isEmpty()) {
                Log.e(TAG, "No USB serial drivers found")
                _connectionState.value = BluetoothInterface.ConnectionState.DISCONNECTED
                return
            }
            
            val driver = availableDrivers[0]
            usbConnection = usbManager?.openDevice(driver.device)
            
            if (usbConnection == null) {
                Log.e(TAG, "Failed to open USB connection - permission may be required")
                _connectionState.value = BluetoothInterface.ConnectionState.DISCONNECTED
                return
            }
            
            serialPort = driver.ports[0]
            serialPort?.open(usbConnection)
            serialPort?.setParameters(BAUD_RATE, DATA_BITS, STOP_BITS, PARITY)
            
            // Start I/O manager
            ioManager = SerialInputOutputManager(serialPort, this)
            ioManager?.start()
            
            _connectionState.value = BluetoothInterface.ConnectionState.CONNECTED
            
            // State will transition to CONFIGURED when MeshtasticManager receives myNodeInfo
            // or configCompleteId, just like BluetoothInterface
            
            // Start write queue processor
            startWriteProcessor()
            
            Log.i(TAG, "USB serial connection established")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to USB device", e)
            _connectionState.value = BluetoothInterface.ConnectionState.DISCONNECTED
            cleanup()
        }
    }
    
    fun disconnect() {
        Log.i(TAG, "Disconnecting from USB device")
        _connectionState.value = BluetoothInterface.ConnectionState.DISCONNECTED
        cleanup()
    }
    
    private fun cleanup() {
        try {
            ioManager?.stop()
            ioManager = null
            
            serialPort?.close()
            serialPort = null
            
            usbConnection?.close()
            usbConnection = null
            
            scope.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
    
    private fun findUsbDevice(): UsbDevice? {
        val deviceList = usbManager?.deviceList ?: return null
        
        // Look for common Meshtastic USB vendor/product IDs
        for (device in deviceList.values) {
            // Common CP2102/CH340 serial chips used in ESP32 boards
            if (device.vendorId == 0x10C4 || // CP2102
                device.vendorId == 0x1A86 || // CH340
                device.vendorId == 0x0403) { // FTDI
                return device
            }
        }
        
        // If no specific device found, return first available
        return deviceList.values.firstOrNull()
    }
    
    override fun onNewData(data: ByteArray?) {
        if (data == null || data.isEmpty()) return
        
        // Add to receive buffer
        receiveBuffer.write(data)
        
        // Process buffer for complete packets
        processReceiveBuffer()
    }
    
    override fun onRunError(e: Exception?) {
        Log.e(TAG, "Serial run error", e)
        _connectionState.value = BluetoothInterface.ConnectionState.DISCONNECTED
        cleanup()
    }
    
    private fun processReceiveBuffer() {
        val buffer = receiveBuffer.toByteArray()
        
        var processed = 0
        var i = 0
        
        while (i < buffer.size - 1) {
            // Look for start markers
            if (buffer[i] == START1 && buffer[i + 1] == START2) {
                // Found start of packet
                if (i + 4 <= buffer.size) {
                    // Read length field (16-bit, little endian)
                    val length = (buffer[i + 2].toInt() and 0xFF) or 
                                ((buffer[i + 3].toInt() and 0xFF) shl 8)
                    
                    val totalPacketSize = 4 + length // START1 + START2 + length field + data
                    
                    if (i + totalPacketSize <= buffer.size) {
                        // Complete packet available
                        val packet = buffer.copyOfRange(i, i + totalPacketSize)
                        
                        // Process the packet
                        processPacket(packet)
                        
                        // Move past this packet
                        i += totalPacketSize
                        processed = i
                    } else {
                        // Incomplete packet, wait for more data
                        break
                    }
                } else {
                    // Not enough data for length field
                    break
                }
            } else {
                i++
                processed = i
            }
        }
        
        // Remove processed data from buffer
        if (processed > 0) {
            val remaining = buffer.copyOfRange(processed, buffer.size)
            receiveBuffer.reset()
            receiveBuffer.write(remaining)
        }
        
        // Prevent buffer overflow
        if (receiveBuffer.size() > MAX_PACKET_SIZE * 2) {
            Log.w(TAG, "Receive buffer overflow, resetting")
            receiveBuffer.reset()
        }
    }
    
    private fun processPacket(packet: ByteArray) {
        Log.d(TAG, "Processing packet: ${packet.size} bytes")
        
        // Remove framing and pass to callback
        if (packet.size > 4) {
            val data = packet.copyOfRange(4, packet.size)
            callback.handleFromRadio(data)
        }
    }
    
    fun sendToRadio(data: ByteArray) {
        if (_connectionState.value != BluetoothInterface.ConnectionState.CONFIGURED) {
            Log.w(TAG, "Not ready to send, state: ${_connectionState.value}")
            return
        }
        
        // Add framing if not already present
        val framedData = if (data.size >= 2 && data[0] == START1 && data[1] == START2) {
            data
        } else {
            addFraming(data)
        }
        
        writeQueue.offer(framedData)
    }
    
    // Alias for sendToRadio to match the interface expected by MeshtasticManager  
    fun sendData(data: ByteArray) = sendToRadio(data)
    
    private fun addFraming(data: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(data.size + 4)
        buffer.put(START1)
        buffer.put(START2)
        buffer.putShort(data.size.toShort())
        buffer.put(data)
        return buffer.array()
    }
    
    private fun startWriteProcessor() {
        scope.launch {
            while (isActive) {
                try {
                    val data = withContext(Dispatchers.IO) {
                        writeQueue.poll()
                    }
                    
                    if (data != null) {
                        serialPort?.write(data, 1000)
                        Log.d(TAG, "Sent ${data.size} bytes to USB")
                    } else {
                        delay(10) // No data, wait a bit
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing to USB", e)
                }
            }
        }
    }
}