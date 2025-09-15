# Meshtastic ATAK Plugin

A comprehensive Android Team Awareness Kit (ATAK) plugin that seamlessly integrates Meshtastic mesh networking devices for tactical communications and situational awareness.

## Overview

This plugin bridges ATAK with Meshtastic devices, enabling secure, decentralized communications over long-range LoRa radio networks. It provides automatic device configuration, intuitive connection management, and robust message handling for tactical operations.

## Key Features

### 🚀 **One-Click Setup & Auto-Configuration**
- **Automatic TAK Optimization**: Intelligently configures your Meshtastic device for optimal ATAK operation
- **Tactical Settings**: Automatically applies covert, radio-silent settings including:
  - TAK role configuration
  - LOCAL_ONLY rebroadcast mode
  - SHORT_TURBO modem preset for fast data transmission
  - Disables position broadcasts, telemetry, and MQTT for stealth operations
  - Power-saving configuration with appropriate wake settings
- **Smart Channel Management**: Generates secure PSK from user-provided passwords
- **Minimal User Input Required**: Simply pair your device via Bluetooth and set a channel password

### 📡 **Advanced Connectivity**
- **Multi-Interface Support**: Bluetooth LE and USB Serial connections
- **Auto-Reconnection**: Automatically reconnects to previously paired devices
- **Connection Persistence**: Remembers device preferences across app restarts
- **Real-Time Status Monitoring**: Live RSSI readings and connection health indicators

### 🔧 **Comprehensive Device Management**
- **Device Discovery**: Intelligent Bluetooth scanning with Meshtastic device detection
- **Configuration Validation**: Compares current vs. optimal settings before applying changes
- **Selective Updates**: Only modifies settings that need changing to minimize device disruption
- **Post-Reboot Handling**: Manages device reboots from critical configuration changes

### 💬 **Robust Message Handling**
- **Dual Protocol Support**:
  - **ATAK Plugin Port (72)**: Direct protobuf message transmission
  - **ATAK Forwarder Port (257)**: Large message chunking and compression
- **Automatic Chunking**: Handles large messages by splitting into optimal-sized chunks
- **Queue-Aware Transmission**: Monitors device queue status for efficient sending
- **Message Acknowledgment**: Full ACK/NACK handling with automatic retries
- **Error Recovery**: Comprehensive error handling with user-friendly messages

## User Interface

The plugin features a clean, tabbed interface designed for tactical environments:

### 🔗 **Connection Tab**
- **Device Scanning**: One-click Bluetooth discovery of nearby Meshtastic devices
- **Connection Status**: Real-time display of connection state with color-coded indicators
- **Device List**: Sorted list showing device names, addresses, and signal strength (RSSI)
- **Quick Actions**: Scan, Connect, and Disconnect buttons with progress indicators
- **Auto-Detection**: Highlights confirmed Meshtastic devices in the scan results

### 📊 **Status Tab**
- **Connection Details**: Shows connection type, device address, and current name
- **Signal Quality**: Live RSSI readings and connection state monitoring
- **Statistics**: Real-time message counters (sent, received, failed)
- **Device Information**: Hardware model, firmware version, and region settings
- **Performance Metrics**: Connection uptime and data throughput

### ⚙️ **Settings Tab**
- **Channel Security**:
  - Password-based PSK generation
  - Secure channel configuration
  - Persistent password storage using ATAK preferences
- **Device Metadata**:
  - Comprehensive device information display
  - Node ID with hex formatting
  - GPS capability detection
  - Device role and region configuration

## Technical Architecture

### Core Components

- **MeshtasticManager**: Central communication handler with connection state management
- **MeshtasticConfigManager**: Intelligent device configuration with TAK optimization
- **BluetoothInterface**: BLE communication layer with Nordic BLE library integration
- **AtakMeshtasticBridge**: High-level message routing and protocol handling
- **MeshtasticBleScanner**: Advanced device discovery with filtering

### Message Flow

1. **ATAK Plugin Messages (Port 72)**: Direct protobuf transmission for small, time-critical data
2. **ATAK Forwarder Messages (Port 257)**: Chunked transmission for large payloads with compression
3. **Automatic Protocol Selection**: Intelligently chooses optimal transmission method
4. **Queue Management**: Monitors device transmission queue to prevent overruns

### Configuration Intelligence

The plugin employs a sophisticated configuration strategy:

1. **Current State Analysis**: Reads existing device configuration before making changes
2. **Selective Modification**: Only updates settings that need optimization
3. **Phased Deployment**: Handles critical configs that require device reboots
4. **Validation**: Verifies configuration success and handles failures gracefully

## Installation

1. Install the plugin APK on your ATAK device
2. Enable the Meshtastic plugin in ATAK's plugin manager
3. Access via the plugin toolbar or dropdown menu

## Quick Start Guide

### First Time Setup

1. **Pair Your Device**:
   - Open the Meshtastic plugin
   - Go to the Connection tab
   - Tap "Scan for Devices"
   - Select your Meshtastic device from the list

2. **Configure Security**:
   - Switch to the Settings tab
   - Enter a secure channel password
   - Tap "Save Password"

3. **Auto-Configuration**:
   - The plugin automatically applies TAK-optimized settings
   - Device may reboot during configuration (normal behavior)
   - Status tab will show "Connected & Configured" when ready

### Daily Use

- Plugin automatically reconnects to your paired device
- Monitor connection status in the Status tab
- Send/receive ATAK messages transparently through the mesh network
- Check signal strength and message statistics as needed

## Advanced Features

### Tactical Configuration
- **Covert Operations**: Disables all unnecessary broadcasts and telemetry
- **Power Efficiency**: Optimizes battery life for extended field operations
- **Local-Only Mode**: Prevents message forwarding beyond immediate mesh
- **Custom PSK**: Uses AES-256 encryption with user-defined passwords

### Message Optimization
- **Adaptive Chunking**: Automatically splits large messages for reliable transmission
- **Compression Support**: GZIP compression for efficient bandwidth usage
- **Queue Awareness**: Monitors device queue status to prevent message loss
- **Retry Logic**: Intelligent retry mechanisms for failed transmissions

### Device Intelligence
- **Multi-Device Support**: Remembers multiple paired devices
- **Firmware Compatibility**: Works across different Meshtastic firmware versions
- **Region Awareness**: Automatically detects and displays device region settings
- **Hardware Detection**: Identifies device capabilities and limitations

## Troubleshooting

### Connection Issues
- Ensure Bluetooth is enabled on your Android device
- Grant location permissions (required for BLE scanning)
- Verify your Meshtastic device is powered on and in pairing mode
- Check that the device isn't connected to another application

### Configuration Problems
- Allow time for device reboot after configuration changes
- Verify the channel password matches across all devices in your network
- Check that your device supports the features being configured

### Message Delivery
- Monitor signal strength in the Status tab
- Ensure channel passwords match across all devices
- Check for duty cycle limitations in your region
- Verify message sizes are within Meshtastic limits

## Dependencies

- **ATAK-CIV 5.5.0** or later
- **Android SDK 21+** (Android 5.0+)
- **Bluetooth LE support** for device connectivity
- **Nordic BLE library** for reliable Bluetooth communication
- **Google Protobuf** for message serialization

## Development

### Build Requirements
- Android Studio with Kotlin support
- ATAK SDK 5.5.0
- Gradle 8.8.2+
- Kotlin 2.1.0+

### Key Libraries
- `no.nordicsemi.android:ble:2.6.1` - BLE communication
- `com.github.mik3y:usb-serial-for-android:3.5.1` - USB serial support
- `com.google.protobuf:protobuf-javalite:3.21.12` - Message serialization
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3` - Async operations

## License

GPLv3

## Support

For technical support and feature requests, please refer to the ATAK plugin documentation or contact your system administrator.

---
