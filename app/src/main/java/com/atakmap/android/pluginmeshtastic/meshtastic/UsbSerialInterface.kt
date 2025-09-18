package com.atakmap.android.pluginmeshtastic.meshtastic

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
        private const val BAUD_RATE = 115200  // Match official Meshtastic-Android
        private const val DATA_BITS = 8
        private const val STOP_BITS = UsbSerialPort.STOPBITS_1
        private const val PARITY = UsbSerialPort.PARITY_NONE

        // Protocol framing - match official implementation
        private const val START1 = 0x94.toByte()
        private const val START2 = 0xc3.toByte()  // lowercase 'c' to match reference
        private const val MAX_PACKET_SIZE = 512

        // USB permission
        private const val ACTION_USB_PERMISSION = "com.atakmap.android.pluginmeshtastic.USB_PERMISSION"
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
            Log.d(TAG, "Getting application context...")
            val appContext = context.applicationContext
            val contextToUse = if (appContext != null) {
                Log.d(TAG, "Using application context: ${appContext.javaClass.simpleName}")
                appContext
            } else {
                Log.w(TAG, "Application context is null, using provided context: ${context.javaClass.simpleName}")
                context
            }

            Log.d(TAG, "Getting USB system service...")
            usbManager = contextToUse.getSystemService(Context.USB_SERVICE) as UsbManager
            
            // Find the USB device
            val foundDevice = findUsbDevice()
            if (foundDevice == null) {
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
            val targetDevice = driver.device

            // Check if we have permission to access this device
            if (usbManager?.hasPermission(targetDevice) != true) {
                Log.i(TAG, "USB permission not granted for device ${targetDevice.deviceName}, requesting permission")
                requestUsbPermission(targetDevice)
                return
            }

            Log.d(TAG, "USB permission already granted, attempting to open connection")
            usbConnection = usbManager?.openDevice(targetDevice)

            if (usbConnection == null) {
                Log.e(TAG, "Failed to open USB connection despite having permission")
                _connectionState.value = BluetoothInterface.ConnectionState.DISCONNECTED
                return
            }
            
            serialPort = driver.ports[0]
            serialPort?.open(usbConnection)
            serialPort?.setParameters(BAUD_RATE, DATA_BITS, STOP_BITS, PARITY)
            // Set DTR and RTS like reference implementation
            serialPort?.dtr = true
            serialPort?.rts = true

            // Start I/O manager
            ioManager = SerialInputOutputManager(serialPort, this)
            ioManager?.start()
            
            _connectionState.value = BluetoothInterface.ConnectionState.CONNECTED

            // State will transition to CONFIGURED when MeshtasticManager receives myNodeInfo
            // or configCompleteId, just like BluetoothInterface

            // Start write queue processor
            startWriteProcessor()

            // Send wake bytes before notifying connection - matches reference implementation
            sendWakeSequence()

            Log.i(TAG, "USB serial connection established")

            // Notify callback that connection is ready - this is critical!
            callback.onConnect()
            
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
            Log.d(TAG, "Starting cleanup")

            // Stop I/O manager first to prevent it from trying to use closing connections
            ioManager?.stop()
            ioManager = null

            // Small delay to let I/O manager finish
            Thread.sleep(100)

            // Reset DTR/RTS before closing like reference implementation
            try {
                serialPort?.dtr = false
                serialPort?.rts = false
            } catch (e: Exception) {
                Log.d(TAG, "Error resetting DTR/RTS", e)
            }

            serialPort?.close()
            serialPort = null

            usbConnection?.close()
            usbConnection = null

            Log.d(TAG, "USB cleanup completed")
            scope.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    /**
     * Send wake sequence to sleeping device - matches reference implementation
     */
    private fun sendWakeSequence() {
        try {
            val wakeBytes = byteArrayOf(START1, START1, START1, START1)
            serialPort?.write(wakeBytes, 1000)
            Log.d(TAG, "Sent wake sequence: 4 START1 bytes")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send wake sequence", e)
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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Write processor cancelled - connection shutting down")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing to USB", e)
                    // Continue processing other messages
                }
            }
        }
    }

    /**
     * Request USB permission from the user
     */
    private fun requestUsbPermission(device: UsbDevice) {
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Register broadcast receiver to handle permission result
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        val permissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (ACTION_USB_PERMISSION == intent.action) {
                    synchronized(this) {
                        val usbDevice = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        }

                        val permissionGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        Log.d(TAG, "USB permission result: granted=$permissionGranted, device=${usbDevice?.deviceName ?: "null"}")

                        if (permissionGranted) {
                            if (usbDevice != null) {
                                Log.i(TAG, "USB permission granted for device ${usbDevice.deviceName}")
                                // Connect directly to this specific device
                                connectToDevice(usbDevice)
                            } else {
                                Log.e(TAG, "USB permission granted but device is null")
                                _connectionState.value = BluetoothInterface.ConnectionState.DISCONNECTED
                            }
                        } else {
                            Log.w(TAG, "USB permission denied for device ${usbDevice?.deviceName ?: "unknown"}")
                            _connectionState.value = BluetoothInterface.ConnectionState.DISCONNECTED
                        }
                    }
                    // Unregister receiver after use
                    try {
                        context.unregisterReceiver(this)
                    } catch (e: Exception) {
                        Log.d(TAG, "Permission receiver already unregistered")
                    }
                }
            }
        }

        context.registerReceiver(permissionReceiver, filter)
        usbManager?.requestPermission(device, permissionIntent)

        Log.i(TAG, "USB permission request sent for device ${device.deviceName}")
    }

    /**
     * Connect directly to a specific USB device (used after permission is granted)
     */
    private fun connectToDevice(device: UsbDevice) {
        try {
            Log.d(TAG, "Connecting directly to USB device: ${device.deviceName}")

            // Get driver for the specific device
            val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            val driver = availableDrivers.find { it.device == device }

            if (driver == null) {
                Log.e(TAG, "No USB serial driver found for device ${device.deviceName}")
                _connectionState.value = BluetoothInterface.ConnectionState.DISCONNECTED
                return
            }

            Log.d(TAG, "Found driver for device, attempting to open connection")
            usbConnection = usbManager?.openDevice(device)

            if (usbConnection == null) {
                Log.e(TAG, "Failed to open USB connection to device ${device.deviceName}")
                _connectionState.value = BluetoothInterface.ConnectionState.DISCONNECTED
                return
            }

            serialPort = driver.ports[0]
            serialPort?.open(usbConnection)
            serialPort?.setParameters(BAUD_RATE, DATA_BITS, STOP_BITS, PARITY)
            // Set DTR and RTS like reference implementation
            serialPort?.dtr = true
            serialPort?.rts = true

            Log.i(TAG, "USB serial port configured: ${BAUD_RATE} baud")

            // Start I/O manager
            ioManager = SerialInputOutputManager(serialPort, this)
            ioManager?.start()

            // Start write queue processor
            startWriteProcessor()

            _connectionState.value = BluetoothInterface.ConnectionState.CONNECTED

            // Send wake bytes before notifying connection - matches reference implementation
            sendWakeSequence()

            Log.i(TAG, "USB connection established successfully")

            // Notify callback that connection is ready
            callback.onConnect()

        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to USB device", e)
            _connectionState.value = BluetoothInterface.ConnectionState.DISCONNECTED
        }
    }
}