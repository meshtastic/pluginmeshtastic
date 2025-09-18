package com.atakmap.android.pluginmeshtastic;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TabHost;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.pluginmeshtastic.meshtastic.AtakMeshtasticBridge;
import com.atakmap.android.pluginmeshtastic.meshtastic.MeshtasticBleScanner;
import com.atakmap.android.pluginmeshtastic.meshtastic.MeshtasticManager;
import com.atakmap.android.pluginmeshtastic.plugin.R;
import com.atakmap.coremap.log.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;


public class MeshtasticDropDownReceiver extends DropDownReceiver implements DropDown.OnStateListener {

    public static final String TAG = "MeshtasticDropDown";
    public static final String SHOW_MESHTASTIC = "com.atakmap.android.pluginmeshtastic.SHOW_MESHTASTIC";
    
    private final View templateView;
    private final Context pluginContext;
    private final AtakMeshtasticBridge meshtasticBridge;
    private MeshtasticBleScanner bleScanner;
    
    private Button scanButton;
    private Button connectUsbButton;
    private Button disconnectButton;
    private ListView deviceList;
    private TextView statusText;
    private TextView connectionStatus;
    private ProgressBar scanProgress;
    private DeviceListAdapter deviceAdapter;
    
    // Status tab elements
    private TextView connectionType;
    private TextView deviceAddress;
    private TextView deviceName;
    private TextView signalStrength;
    private TextView messagesSent;
    private TextView messagesReceived;
    private TextView connectionTime;
    private View signalInfoCard;
    private View statsCard;
    
    // Tab host
    private TabHost tabHost;
    
    // Settings tab elements
    private EditText channelPasswordField;
    private Button savePasswordButton;
    
    // Device metadata elements
    private View deviceMetadataCard;
    private TextView deviceFirmwareVersion;
    private TextView deviceHardwareModel;
    private TextView deviceNodeId;
    private TextView deviceRegion;
    private TextView deviceHasGps;
    private TextView deviceRoles;
    private TextView deviceLastHeard;

    // Device metrics elements
    private View deviceMetricsCard;
    private TextView deviceBatteryLevel;
    private TextView deviceVoltage;
    private TextView deviceUptime;
    private TextView deviceChannelUtilization;
    private TextView deviceAirUtilTx;

    private Handler uiHandler;
    private boolean isScanning = false;
    
    // ATAK Preferences for storing settings (persistent across restarts)
    private static final String PREF_CHANNEL_PASSWORD = "plugin.meshtastic.channel_password";
    private AtakPreferences preferences;
    
    // Store current device info
    private String currentDeviceAddress;
    private String currentDeviceName;
    
    // Track if we've already sent the connection test message
    private boolean testMessageSent = false;
    
    // Track last connection state and metadata update status to avoid spam
    private MeshtasticManager.ConnectionState lastConnectionState = null;
    private boolean metadataUpdateNeeded = true;
    private boolean lastHadNodeInfo = false;
    private long lastDeviceInfoUpdate = 0;
    
    // Cache node ID to avoid repeated formatting calls
    private String cachedNodeIdHex = null;
    private long cachedNodeIdTimestamp = 0;
    
    // RSSI update throttling
    private long lastRssiRequest = 0;
    private static final long RSSI_REQUEST_INTERVAL_MS = 5000; // Request RSSI every 5 seconds

    public MeshtasticDropDownReceiver(MapView mapView, Context context, AtakMeshtasticBridge bridge) {
        super(mapView);
        this.pluginContext = context;
        this.meshtasticBridge = bridge;
        this.uiHandler = new Handler(Looper.getMainLooper());
        
        // Initialize AtakPreferences (use getInstance for proper persistence)
        this.preferences = AtakPreferences.getInstance(pluginContext);
        
        // Inflate the dropdown view
        LayoutInflater inflater = LayoutInflater.from(pluginContext);
        templateView = inflater.inflate(R.layout.meshtastic_layout, null);

        // Initialize BLE scanner
        bleScanner = new MeshtasticBleScanner(mapView.getContext());

        // Initialize UI components
        initializeUI();

        // Start observing scan results immediately to support autoconnect
        observeScanResults();
    }

    private void initializeUI() {
        // Set up TabHost
        tabHost = templateView.findViewById(android.R.id.tabhost);
        tabHost.setup();
        
        // Create tabs
        TabHost.TabSpec connectionTab = tabHost.newTabSpec("connection")
                .setIndicator("Connection")
                .setContent(R.id.tab_connection);
        tabHost.addTab(connectionTab);
        
        TabHost.TabSpec statusTab = tabHost.newTabSpec("status")
                .setIndicator("Status")
                .setContent(R.id.tab_status);
        tabHost.addTab(statusTab);
        
        TabHost.TabSpec settingsTab = tabHost.newTabSpec("settings")
                .setIndicator("Settings")
                .setContent(R.id.tab_settings);
        tabHost.addTab(settingsTab);
        
        // Set up tab change listener to refresh settings tab when viewed
        tabHost.setOnTabChangedListener(tabId -> {
            if ("settings".equals(tabId)) {
                refreshSettingsTab();
            } else if ("status".equals(tabId)) {
                // Force refresh status tab when switching to it
                updateConnectionStatus();
                updateDeviceInfoForced();
            }
        });
        
        // Initialize connection tab elements
        scanButton = templateView.findViewById(R.id.btn_scan);
        connectUsbButton = templateView.findViewById(R.id.btn_connect_usb);
        disconnectButton = templateView.findViewById(R.id.btn_disconnect);
        deviceList = templateView.findViewById(R.id.device_list);
        statusText = templateView.findViewById(R.id.status_text);
        connectionStatus = templateView.findViewById(R.id.connection_status);
        scanProgress = templateView.findViewById(R.id.scan_progress);
        
        // Initialize status tab elements
        connectionType = templateView.findViewById(R.id.connection_type);
        deviceAddress = templateView.findViewById(R.id.device_address);
        deviceName = templateView.findViewById(R.id.device_name);
        signalStrength = templateView.findViewById(R.id.signal_strength);
        messagesSent = templateView.findViewById(R.id.messages_sent);
        messagesReceived = templateView.findViewById(R.id.messages_received);
        connectionTime = templateView.findViewById(R.id.connection_time);
        signalInfoCard = templateView.findViewById(R.id.signal_info_card);
        statsCard = templateView.findViewById(R.id.stats_card);
        
        // Initialize settings tab elements
        channelPasswordField = templateView.findViewById(R.id.channel_password);
        savePasswordButton = templateView.findViewById(R.id.btn_save_password);
        
        // Initialize device metadata elements
        deviceMetadataCard = templateView.findViewById(R.id.device_metadata_card);
        deviceFirmwareVersion = templateView.findViewById(R.id.device_firmware_version);
        deviceHardwareModel = templateView.findViewById(R.id.device_hardware_model);
        deviceNodeId = templateView.findViewById(R.id.device_node_id);
        deviceRegion = templateView.findViewById(R.id.device_region);
        deviceHasGps = templateView.findViewById(R.id.device_has_gps);
        deviceRoles = templateView.findViewById(R.id.device_roles);
        deviceLastHeard = templateView.findViewById(R.id.device_last_heard);

        // Initialize device metrics elements
        deviceMetricsCard = templateView.findViewById(R.id.device_metrics_card);
        deviceBatteryLevel = templateView.findViewById(R.id.device_battery_level);
        deviceVoltage = templateView.findViewById(R.id.device_voltage);
        deviceUptime = templateView.findViewById(R.id.device_uptime);
        deviceChannelUtilization = templateView.findViewById(R.id.device_channel_utilization);
        deviceAirUtilTx = templateView.findViewById(R.id.device_air_util_tx);

        // Set up device list adapter
        deviceAdapter = new DeviceListAdapter();
        deviceList.setAdapter(deviceAdapter);
        
        // Set up scan button
        scanButton.setOnClickListener(v -> {
            if (isScanning) {
                stopScan();
            } else {
                startScan();
            }
        });

        // Set up USB connect button
        connectUsbButton.setOnClickListener(v -> {
            connectUsb();
        });

        // Set up disconnect button
        disconnectButton.setOnClickListener(v -> {
            meshtasticBridge.disconnect();
            updateConnectionStatus();
        });
        
        // Set up settings tab
        setupSettingsTab();
        
        // Set up device list item click
        deviceList.setOnItemClickListener((parent, view, position, id) -> {
            MeshtasticBleScanner.MeshtasticDevice device = deviceAdapter.getItem(position);
            if (device != null) {
                connectToDevice(device);
            }
        });
        
        updateConnectionStatus();
    }
    
    private void setupSettingsTab() {
        // Load saved password from AtakPreferences
        String savedPassword = preferences.get(PREF_CHANNEL_PASSWORD, "");
        Log.d(TAG, "Loading saved password from AtakPreferences: " + (savedPassword.isEmpty() ? "empty" : savedPassword.length() + " chars"));
        if (channelPasswordField != null) {
            channelPasswordField.setText(savedPassword);
        }
        
        // Set up save password button
        if (savePasswordButton != null) {
            savePasswordButton.setOnClickListener(v -> {
                saveChannelPassword();
            });
        }
        
    }
    
    private void refreshSettingsTab() {
        Log.d(TAG, "Refreshing settings tab");
        
        // Refresh channel password field with latest saved value
        String savedPassword = preferences.get(PREF_CHANNEL_PASSWORD, "");
        if (channelPasswordField != null) {
            channelPasswordField.setText(savedPassword);
        }
        
        // Force update device info when manually switching to settings tab
        updateDeviceInfoForced();
        
        // Note: Don't call updateDeviceMetadata() here as it has its own update logic
        // and is already called by updateConnectionStatus() with anti-spam protection
        
        Log.d(TAG, "Settings tab refreshed - password field updated, device info updated");
    }
    
    private void updateDeviceInfoForced() {
        // Reset throttle timer to force update
        lastDeviceInfoUpdate = 0;
        updateDeviceInfo();
    }
    
    private void updateDeviceInfo() {
        // Throttle updates to prevent spam (max once per 1 second, less aggressive than before)
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastDeviceInfoUpdate < 1000) {
            Log.d(TAG, "Throttling device info update - too soon since last update");
            return;
        }
        lastDeviceInfoUpdate = currentTime;
        
        Log.d(TAG, "Updating device info fields (callback-driven)");
        
        // Update Node ID - only when connected & configured to avoid excessive calls
        if (deviceNodeId != null) {
            MeshtasticManager.ConnectionState state = meshtasticBridge.getConnectionState();
            String nodeIdHex = getCachedNodeIdHex(state);
            
            if (nodeIdHex != null) {
                deviceNodeId.setText("Node ID: " + nodeIdHex);
                Log.d(TAG, "Displayed Node ID: " + nodeIdHex);
            } else if (currentDeviceAddress != null) {
                deviceNodeId.setText("Node ID: " + currentDeviceAddress + " (BT)");
                Log.d(TAG, "Displayed BT address as fallback: " + currentDeviceAddress);
            } else {
                deviceNodeId.setText("Node ID: Not available");
                Log.d(TAG, "No Node ID available to display");
            }
        }
        
        // Show metadata card if we have any device info to display
        // Note: Card visibility will be managed by the callback system when data is actually received
    }
    
    private void saveChannelPassword() {
        if (channelPasswordField == null) return;
        
        String password = channelPasswordField.getText().toString().trim();
        
        // Save to AtakPreferences (persistent across restarts)
        preferences.set(PREF_CHANNEL_PASSWORD, password);
        Log.d(TAG, "Saving channel password to AtakPreferences: " + (password.isEmpty() ? "empty" : password.length() + " chars"));
        
        // Verify it was saved
        String savedPassword = preferences.get(PREF_CHANNEL_PASSWORD, "");
        Log.d(TAG, "Verification - saved password: " + (savedPassword.isEmpty() ? "empty" : savedPassword.length() + " chars"));
        
        // Show confirmation
        Toast.makeText(pluginContext, "Channel password saved", Toast.LENGTH_SHORT).show();
        
        // If connected, apply the password now
        if (meshtasticBridge.getConnectionState() == MeshtasticManager.ConnectionState.CONNECTED ||
            meshtasticBridge.getConnectionState() == MeshtasticManager.ConnectionState.CONFIGURED) {
            applyChannelPassword(password);
        }
    }
    
    private void updateDeviceMetadata() {
        MeshtasticManager.ConnectionState currentState = meshtasticBridge != null ? meshtasticBridge.getConnectionState() : null;
        Log.d(TAG, "updateDeviceMetadata called - Connection state: " + currentState);
        
        if (meshtasticBridge == null || 
            (currentState != MeshtasticManager.ConnectionState.CONNECTED &&
             currentState != MeshtasticManager.ConnectionState.CONFIGURED)) {
            // Hide metadata card when not connected
            Log.d(TAG, "Hiding metadata card - not connected (state: " + currentState + ")");
            if (deviceMetadataCard != null) {
                deviceMetadataCard.setVisibility(View.GONE);
            }
            return;
        }
        
        // Show metadata card when connected
        Log.d(TAG, "Showing metadata card - connected (state: " + currentState + ")");
        if (deviceMetadataCard != null) {
            deviceMetadataCard.setVisibility(View.VISIBLE);
        }
        
        // Get device metadata from the bridge
        try {
            com.geeksville.mesh.MeshProtos.DeviceMetadata metadata = meshtasticBridge.getDeviceMetadata();
            
            if (metadata != null) {
                Log.d(TAG, "Device metadata retrieved and displaying");
                
                // Node ID (only get from MyNodeInfo when configured to avoid excessive calls)
                if (deviceNodeId != null) {
                    String nodeIdHex = getCachedNodeIdHex(currentState);
                    if (nodeIdHex != null) {
                        deviceNodeId.setText("Node ID: " + nodeIdHex);
                    } else if (currentDeviceAddress != null) {
                        deviceNodeId.setText("Node ID: " + currentDeviceAddress + " (BT)");
                    } else {
                        deviceNodeId.setText("Node ID: Unknown");
                    }
                }
                
                // Firmware version
                if (deviceFirmwareVersion != null) {
                    String firmware = metadata.getFirmwareVersion();
                    deviceFirmwareVersion.setText("Firmware: " + (firmware.isEmpty() ? "Unknown" : firmware));
                }
                
                // Hardware model
                if (deviceHardwareModel != null) {
                    String hwModel = metadata.getHwModel().toString();
                    deviceHardwareModel.setText("Hardware: " + hwModel);
                }
                
                // Role
                if (deviceRoles != null) {
                    String role = metadata.getRole().toString();
                    deviceRoles.setText("Role: " + role);
                }
                
                // GPS capability (check position flags for GPS capability)
                if (deviceHasGps != null) {
                    boolean hasGps = metadata.getPositionFlags() > 0;
                    deviceHasGps.setText("GPS: " + (hasGps ? "Yes" : "No"));
                }
                
                // Region - get from LoRa config
                if (deviceRegion != null) {
                    try {
                        com.geeksville.mesh.ConfigProtos.Config.LoRaConfig loraConfig = meshtasticBridge.getLoraConfig();
                        if (loraConfig != null && loraConfig.getRegion() != null) {
                            String regionName = loraConfig.getRegion().toString();
                            deviceRegion.setText("Region: " + regionName);
                        } else {
                            deviceRegion.setText("Region: Not configured");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Error getting LoRa region: " + e.getMessage());
                        deviceRegion.setText("Region: N/A");
                    }
                }
                
                if (deviceLastHeard != null) {
                    deviceLastHeard.setText("Last Heard: Just now");
                }
                
            } else {
                Log.d(TAG, "Device metadata not available yet, showing retrieving status");
                // Show retrieving status when metadata is not yet available
                if (deviceNodeId != null) {
                    String nodeIdHex = getCachedNodeIdHex(currentState);
                    if (nodeIdHex != null) {
                        deviceNodeId.setText("Node ID: " + nodeIdHex);
                    } else if (currentDeviceAddress != null) {
                        deviceNodeId.setText("Node ID: " + currentDeviceAddress + " (BT)");
                    } else {
                        deviceNodeId.setText("Node ID: Retrieving...");
                    }
                }
                
                if (deviceFirmwareVersion != null) {
                    deviceFirmwareVersion.setText("Firmware: Retrieving...");
                }
                
                if (deviceHardwareModel != null) {
                    deviceHardwareModel.setText("Hardware: " + (currentDeviceName != null ? currentDeviceName : "Unknown"));
                }
                
                if (deviceRegion != null) {
                    // Try to get region from LoRa config even when metadata is not available
                    try {
                        com.geeksville.mesh.ConfigProtos.Config.LoRaConfig loraConfig = meshtasticBridge.getLoraConfig();
                        if (loraConfig != null && loraConfig.getRegion() != null) {
                            String regionName = loraConfig.getRegion().toString();
                            deviceRegion.setText("Region: " + regionName);
                        } else {
                            deviceRegion.setText("Region: Retrieving...");
                        }
                    } catch (Exception e) {
                        deviceRegion.setText("Region: Retrieving...");
                    }
                }
                
                if (deviceHasGps != null) {
                    deviceHasGps.setText("GPS: Checking...");
                }
                
                if (deviceRoles != null) {
                    deviceRoles.setText("Role: Retrieving...");
                }
                
                if (deviceLastHeard != null) {
                    deviceLastHeard.setText("Last Heard: Just now");
                }
            }
            
        } catch (Exception e) {
            Log.w(TAG, "Error updating device metadata: " + e.getMessage());
            // Set all fields to N/A on error
            if (deviceNodeId != null) deviceNodeId.setText("Node ID: N/A");
            if (deviceFirmwareVersion != null) deviceFirmwareVersion.setText("Firmware: N/A");
            if (deviceHardwareModel != null) deviceHardwareModel.setText("Hardware: N/A");
            if (deviceRegion != null) deviceRegion.setText("Region: N/A");
            if (deviceHasGps != null) deviceHasGps.setText("GPS: N/A");
            if (deviceRoles != null) deviceRoles.setText("Role: N/A");
            if (deviceLastHeard != null) deviceLastHeard.setText("Last Heard: N/A");
        }
    }

    private void updateDeviceMetrics() {
        MeshtasticManager.ConnectionState currentState = meshtasticBridge != null ? meshtasticBridge.getConnectionState() : null;

        if (meshtasticBridge == null ||
            (currentState != MeshtasticManager.ConnectionState.CONNECTED &&
             currentState != MeshtasticManager.ConnectionState.CONFIGURED)) {
            // Hide metrics card when not connected
            Log.d(TAG, "Hiding device metrics card - not connected (state: " + currentState + ")");
            if (deviceMetricsCard != null) {
                deviceMetricsCard.setVisibility(View.GONE);
            }
            return;
        }

        // Get device metrics from the bridge
        try {
            com.geeksville.mesh.TelemetryProtos.DeviceMetrics metrics = meshtasticBridge.getCurrentDeviceMetrics();

            if (metrics != null) {

                // Show metrics card when data is available
                if (deviceMetricsCard != null) {
                    deviceMetricsCard.setVisibility(View.VISIBLE);
                }

                // Update battery level
                if (deviceBatteryLevel != null) {
                    if (metrics.hasBatteryLevel()) {
                        int batteryLevel = metrics.getBatteryLevel();
                        String batteryText = "Battery: " + batteryLevel + "%";
                        if (batteryLevel > 100) {
                            batteryText += " (Powered)";
                        }
                        deviceBatteryLevel.setText(batteryText);
                    } else {
                        deviceBatteryLevel.setText("Battery: N/A");
                    }
                }

                // Update voltage
                if (deviceVoltage != null) {
                    if (metrics.hasVoltage()) {
                        float voltage = metrics.getVoltage();
                        deviceVoltage.setText(String.format("Voltage: %.2fV", voltage));
                    } else {
                        deviceVoltage.setText("Voltage: N/A");
                    }
                }

                // Update uptime
                if (deviceUptime != null) {
                    if (metrics.hasUptimeSeconds()) {
                        int uptimeSeconds = metrics.getUptimeSeconds();
                        String uptimeText = formatUptime(uptimeSeconds);
                        deviceUptime.setText("Uptime: " + uptimeText);
                    } else {
                        deviceUptime.setText("Uptime: N/A");
                    }
                }

                // Update channel utilization
                if (deviceChannelUtilization != null) {
                    if (metrics.hasChannelUtilization()) {
                        float channelUtil = metrics.getChannelUtilization();
                        deviceChannelUtilization.setText(String.format("Channel Utilization: %.1f%%", channelUtil));
                    } else {
                        deviceChannelUtilization.setText("Channel Utilization: N/A");
                    }
                }

                // Update air utilization TX
                if (deviceAirUtilTx != null) {
                    if (metrics.hasAirUtilTx()) {
                        float airUtilTx = metrics.getAirUtilTx();
                        deviceAirUtilTx.setText(String.format("Air Util TX: %.1f%%", airUtilTx));
                    } else {
                        deviceAirUtilTx.setText("Air Util TX: N/A");
                    }
                }
            } else {
                // Hide card if metrics are not available
                if (deviceMetricsCard != null) {
                    deviceMetricsCard.setVisibility(View.GONE);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error updating device metrics: " + e.getMessage());
            // Hide card on error
            if (deviceMetricsCard != null) {
                deviceMetricsCard.setVisibility(View.GONE);
            }
        }
    }

    private String formatUptime(int uptimeSeconds) {
        int days = uptimeSeconds / 86400;
        int hours = (uptimeSeconds % 86400) / 3600;
        int minutes = (uptimeSeconds % 3600) / 60;

        if (days > 0) {
            return String.format("%dd %dh %dm", days, hours, minutes);
        } else if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else {
            return String.format("%dm", minutes);
        }
    }

    private void applyChannelPassword(String password) {
        if (password.isEmpty()) {
            Log.d(TAG, "Channel password is empty, skipping PSK configuration");
            return;
        }
        
        Log.d(TAG, "Applying channel password to connected device");
        // This will be implemented when we add the method to MeshtasticManager
        meshtasticBridge.setChannelPassword(password);
    }
    
    public String getChannelPassword() {
        return preferences.get(PREF_CHANNEL_PASSWORD, "");
    }
    
    private void observeScanResults() {
        // This would normally use Kotlin coroutines, but since we're in Java,
        // we'll poll the state periodically
        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (bleScanner != null) {
                    // Update device list
                    Map<String, MeshtasticBleScanner.MeshtasticDevice> devices = 
                        bleScanner.getDiscoveredDevices().getValue();
                    deviceAdapter.updateDevices(devices);
                    
                    // Update scanning state
                    boolean scanning = bleScanner.getScanningState().getValue();
                    if (scanning != isScanning) {
                        isScanning = scanning;
                        updateScanButton();
                    }
                    
                    // Check for errors
                    String error = bleScanner.getScanError().getValue();
                    if (error != null) {
                        showError(error);
                    }
                }
                
                // Update connection status - this should always happen to support autoconnect
                updateConnectionStatus();
                
                // Continue polling - poll more frequently when visible and connected
                if (isVisible()) {
                    // Poll faster when connected to keep status fresh
                    MeshtasticManager.ConnectionState state = meshtasticBridge != null ? 
                        meshtasticBridge.getConnectionState() : MeshtasticManager.ConnectionState.DISCONNECTED;
                    
                    if (state == MeshtasticManager.ConnectionState.CONNECTED || 
                        state == MeshtasticManager.ConnectionState.CONFIGURED) {
                        uiHandler.postDelayed(this, 500); // Update every 500ms when connected and visible
                    } else {
                        uiHandler.postDelayed(this, 1000); // Update every second when visible but not connected
                    }
                } else {
                    // Keep polling at a slower rate when not visible to support autoconnect status updates
                    uiHandler.postDelayed(this, 5000); // Update every 5 seconds when not visible
                }
            }
        }, 1000);
    }
    
    /**
     * Get cached node ID hex to avoid repeated formatting calls.
     * Once retrieved for a configured session, the node ID should not change.
     */
    private String getCachedNodeIdHex(MeshtasticManager.ConnectionState currentState) {
        // Only get node ID when configured
        if (currentState != MeshtasticManager.ConnectionState.CONFIGURED) {
            cachedNodeIdHex = null;
            cachedNodeIdTimestamp = 0;
            return null;
        }
        
        // Return cached value if available - node ID doesn't change during a session
        if (cachedNodeIdHex != null) {
            return cachedNodeIdHex;
        }
        
        // Cache is empty, get fresh value (should only happen once per configured session)
        String nodeIdHex = meshtasticBridge.getMyNodeIdHex();
        if (nodeIdHex != null) {
            cachedNodeIdHex = nodeIdHex;
            cachedNodeIdTimestamp = System.currentTimeMillis();
            Log.d(TAG, "Cached Node ID for session: " + nodeIdHex);
        }
        
        return nodeIdHex;
    }
    
    private void startScan() {
        Log.d(TAG, "Starting BLE scan");
        
        // Check permissions
        if (!bleScanner.hasRequiredPermissions()) {
            showPermissionDialog();
            return;
        }
        
        // Check if Bluetooth is enabled
        if (!bleScanner.isBluetoothEnabled()) {
            showError("Please enable Bluetooth");
            return;
        }
        
        // Clear previous results
        deviceAdapter.clearDevices();
        statusText.setText("Scanning for Meshtastic devices...");
        scanProgress.setVisibility(View.VISIBLE);
        
        // Start scan
        bleScanner.startScan();
        isScanning = true;
        updateScanButton();
    }
    
    private void stopScan() {
        Log.d(TAG, "Stopping BLE scan");
        bleScanner.stopScan();
        isScanning = false;
        updateScanButton();
        scanProgress.setVisibility(View.GONE);
        statusText.setText("Scan stopped");
    }
    
    private void updateScanButton() {
        if (isScanning) {
            scanButton.setText("Stop Scan");
        } else {
            scanButton.setText("Scan for Devices");
        }
    }
    
    private void connectToDevice(MeshtasticBleScanner.MeshtasticDevice device) {
        Log.d(TAG, "Connecting to device: " + device.getName() + " (" + device.getAddress() + ")");

        // Store device info for status display
        currentDeviceAddress = device.getAddress();
        currentDeviceName = device.getName();

        // Also store in preferences for future auto-reconnection using ATAK preferences
        String prefKey = "plugin.meshtastic.last_device_name_" + device.getAddress();
        preferences.set(prefKey, device.getName());
        Log.d(TAG, "Stored device name for future auto-reconnection: " + device.getName());

        // Show connecting dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(getMapView().getContext());
        builder.setTitle("Connecting");
        builder.setMessage("Connecting to " + device.getName() + "...");
        builder.setCancelable(false);
        AlertDialog dialog = builder.create();
        dialog.show();

        // Stop scanning
        stopScan();

        // Connect to device
        meshtasticBridge.connectBluetooth(device.getAddress());

        // Dismiss dialog after a short delay
        uiHandler.postDelayed(() -> {
            dialog.dismiss();
            updateConnectionStatus();
        }, 2000);
    }

    private void connectUsb() {
        Log.d(TAG, "Attempting to connect via USB");

        // Check if bridge is properly initialized
        if (meshtasticBridge == null) {
            Log.e(TAG, "MeshtasticBridge is null, cannot connect via USB");
            showError("Plugin not properly initialized. Please restart ATAK.");
            return;
        }

        // Store device info for status display (USB doesn't have address/name like Bluetooth)
        currentDeviceAddress = "USB";
        currentDeviceName = "USB Device";

        // Show connecting dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(getMapView().getContext());
        builder.setTitle("Connecting");
        builder.setMessage("Connecting via USB...\n\nMake sure your device is connected via USB cable and you have granted USB permissions.");
        builder.setCancelable(false);
        AlertDialog dialog = builder.create();
        dialog.show();

        // Stop scanning if active
        if (isScanning) {
            stopScan();
        }

        // Update status to show connecting
        statusText.setText("Connecting via USB...");

        try {
            // Connect via USB (empty device path means auto-detect)
            meshtasticBridge.connectUsb("");
        } catch (Exception e) {
            Log.e(TAG, "Error connecting via USB: " + e.getMessage(), e);
            dialog.dismiss();
            showError("USB connection failed: " + e.getMessage());
            return;
        }

        // Dismiss dialog after a short delay
        uiHandler.postDelayed(() -> {
            dialog.dismiss();
            updateConnectionStatus();
        }, 3000); // Longer delay for USB connection
    }
    
    private void updateConnectionStatus() {
        MeshtasticManager.ConnectionState state = meshtasticBridge.getConnectionState();
        
        // Check if connection state has changed or if metadata update is needed
        boolean stateChanged = (lastConnectionState != state);
        // Only check for node info when connected & configured to avoid excessive calls
        boolean hasNodeInfo = false;
        if (state == MeshtasticManager.ConnectionState.CONFIGURED) {
            hasNodeInfo = (getCachedNodeIdHex(state) != null);
        }
        boolean nodeInfoChanged = (lastHadNodeInfo != hasNodeInfo);
        boolean shouldUpdateMetadata = stateChanged || metadataUpdateNeeded || nodeInfoChanged;
        
        String statusMessage;
        String connectionTypeText;
        int statusColor;
        
        // Determine connection type based on current device info
        boolean isUsbConnection = "USB".equals(currentDeviceAddress);
        String connType = isUsbConnection ? "USB Serial" : "Bluetooth LE";

        switch (state) {
            case CONNECTED:
                statusMessage = "Connected";
                connectionTypeText = connType;
                statusColor = 0xFF00FF00; // Green
                disconnectButton.setEnabled(true);

                // Show status tab cards
                if (signalInfoCard != null) {
                    // Show signal info for Bluetooth, hide for USB (no RSSI)
                    signalInfoCard.setVisibility(isUsbConnection ? View.GONE : View.VISIBLE);
                }
                if (statsCard != null) statsCard.setVisibility(View.VISIBLE);

                // Force device info update when newly connected
                if (stateChanged) {
                    Log.d(TAG, "State changed to CONNECTED, forcing UI updates");
                    updateDeviceInfoForced();
                    // Force metadata update on connection
                    metadataUpdateNeeded = true;
                }
                break;

            case CONNECTING:
                statusMessage = "Connecting...";
                connectionTypeText = connType + " (Connecting)";
                statusColor = 0xFFFFFF00; // Yellow
                disconnectButton.setEnabled(false);

                // Hide status tab cards while connecting
                if (signalInfoCard != null) signalInfoCard.setVisibility(View.GONE);
                if (statsCard != null) statsCard.setVisibility(View.GONE);
                break;

            case CONFIGURED:
                statusMessage = "Connected & Configured";
                connectionTypeText = connType + " (Ready)";
                statusColor = 0xFF00FF00; // Green
                disconnectButton.setEnabled(true);

                // Show status tab cards
                if (signalInfoCard != null) {
                    // Show signal info for Bluetooth, hide for USB (no RSSI)
                    signalInfoCard.setVisibility(isUsbConnection ? View.GONE : View.VISIBLE);
                }
                if (statsCard != null) statsCard.setVisibility(View.VISIBLE);

                // Force device info update when newly configured
                if (stateChanged) {
                    Log.d(TAG, "State changed to CONFIGURED, forcing UI updates");
                    updateDeviceInfoForced();
                    // Force metadata update on configuration
                    metadataUpdateNeeded = true;
                }
                break;
                
            case DISCONNECTED:
            default:
                statusMessage = "Disconnected";
                connectionTypeText = "Disconnected";
                statusColor = 0xFFFF0000; // Red
                disconnectButton.setEnabled(false);
                
                // Clear device info when disconnected
                currentDeviceAddress = null;
                currentDeviceName = null;
                
                // Clear cached node ID when disconnected
                cachedNodeIdHex = null;
                cachedNodeIdTimestamp = 0;
                
                // Reset test message flag for next connection
                testMessageSent = false;
                
                // Hide status tab cards and device metadata when disconnected
                if (signalInfoCard != null) signalInfoCard.setVisibility(View.GONE);
                if (statsCard != null) statsCard.setVisibility(View.GONE);
                
                // Hide metadata card when disconnected - but only if state actually changed
                if (stateChanged && deviceMetadataCard != null) {
                    deviceMetadataCard.setVisibility(View.GONE);
                    metadataUpdateNeeded = true; // Reset for next connection
                }
                break;
        }
        
        // Update connection tab status
        connectionStatus.setText("Status: " + statusMessage);
        connectionStatus.setTextColor(statusColor);
        
        // Update status tab details
        if (connectionType != null) {
            connectionType.setText("Type: " + connectionTypeText);
            connectionType.setTextColor(statusColor);
        }
        
        // For auto-reconnection, try to get device info from saved preferences
        if (currentDeviceAddress == null && (state == MeshtasticManager.ConnectionState.CONNECTED || 
                                             state == MeshtasticManager.ConnectionState.CONFIGURED)) {
            // Auto-connection happened, get device info from bridge or preferences
            String lastConnectionInfo = meshtasticBridge.getLastConnectionInfo();
            if (lastConnectionInfo != null && lastConnectionInfo.startsWith("Bluetooth:")) {
                // Parse the connection info: "Bluetooth: name (address)"
                try {
                    String[] parts = lastConnectionInfo.substring(10).trim().split("\\(");  // Remove "Bluetooth: "
                    if (parts.length == 2) {
                        currentDeviceName = parts[0].trim();
                        currentDeviceAddress = parts[1].replace(")", "").trim();
                        Log.d(TAG, "Auto-connection detected, restored device info from bridge: " + currentDeviceName + " (" + currentDeviceAddress + ")");
                        
                        // Force metadata update since this is a new connection with restored info
                        metadataUpdateNeeded = true;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to parse last connection info: " + lastConnectionInfo);
                }
            }
            
            // Alternative: try to get from stored preferences if bridge info is not available
            if (currentDeviceAddress == null) {
                // Try to get from ATAK preferences using the stored Bluetooth address
                String storedAddress = preferences.get("plugin.meshtastic.last_bluetooth_address", null);
                if (storedAddress != null) {
                    currentDeviceAddress = storedAddress;
                    String prefKey = "plugin.meshtastic.last_device_name_" + storedAddress;
                    currentDeviceName = preferences.get(prefKey, "Meshtastic Device");
                    Log.d(TAG, "Auto-connection detected, restored device info from preferences: " + currentDeviceName + " (" + currentDeviceAddress + ")");
                    
                    // Force metadata update since this is a new connection with restored info
                    metadataUpdateNeeded = true;
                }
            }
        }
        
        if (deviceAddress != null) {
            deviceAddress.setText("Address: " + (currentDeviceAddress != null ? currentDeviceAddress : "N/A"));
        }
        
        if (deviceName != null) {
            deviceName.setText("Name: " + (currentDeviceName != null ? currentDeviceName : "N/A"));
        }

        // Update signal strength if connected via Bluetooth - skip for USB
        if (signalStrength != null) {
            if ((state == MeshtasticManager.ConnectionState.CONNECTED ||
                 state == MeshtasticManager.ConnectionState.CONFIGURED) && !isUsbConnection) {
                try {
                    // Throttled RSSI requests to avoid spamming the Bluetooth interface
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastRssiRequest > RSSI_REQUEST_INTERVAL_MS) {
                        meshtasticBridge.requestRssi(); // Trigger a fresh RSSI reading
                        lastRssiRequest = currentTime;
                    }

                    // Always get current cached RSSI value
                    int rssiValue = meshtasticBridge.getCurrentRssi();

                    if (rssiValue < 0) { // RSSI values are negative (e.g., -60 dBm)
                        // Show actual RSSI value
                        signalStrength.setText("RSSI: " + rssiValue + " dBm");
                    } else if (rssiValue == 0) {
                        // RSSI not available yet
                        signalStrength.setText("RSSI: Reading...");
                    } else {
                        // Shouldn't happen, but handle positive values
                        signalStrength.setText("RSSI: " + rssiValue + " dBm");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to get RSSI: " + e.getMessage());
                    signalStrength.setText("RSSI: Unknown");
                }
            } else if (isUsbConnection) {
                signalStrength.setText("RSSI: N/A (USB)");
            } else {
                signalStrength.setText("RSSI: N/A");
            }
        }
        
        // Update connection time if connected
        if (connectionTime != null) {
            if (state == MeshtasticManager.ConnectionState.CONNECTED || 
                state == MeshtasticManager.ConnectionState.CONFIGURED) {
                // Show current status instead of hardcoded placeholder
                connectionTime.setText("Connected: Active");
            } else {
                connectionTime.setText("Connected: N/A");
            }
        }
        
        // Update message counters with actual stats from bridge
        if (messagesSent != null || messagesReceived != null) {
            try {
                // Get actual message statistics from the bridge including received count
                kotlin.Triple<Integer, Integer, Integer> stats = meshtasticBridge.getDetailedMessageStats();
                if (messagesSent != null) {
                    messagesSent.setText("Messages Sent: " + stats.component1());
                }
                if (messagesReceived != null) {
                    messagesReceived.setText("Messages Received: " + stats.component3());
                }
            } catch (Exception e) {
                // Fallback to default values
                if (messagesSent != null) {
                    messagesSent.setText("Messages Sent: 0");
                }
                if (messagesReceived != null) {
                    messagesReceived.setText("Messages Received: 0");
                }
            }
        }
        
        // Update device metadata if needed
        if (shouldUpdateMetadata) {
            Log.d(TAG, "Calling updateDeviceMetadata() - shouldUpdateMetadata is true");
            updateDeviceMetadata();
            metadataUpdateNeeded = false; // Reset flag after update
        }

        // Update device metrics (always try to update when connected)
        if (state == MeshtasticManager.ConnectionState.CONNECTED ||
            state == MeshtasticManager.ConnectionState.CONFIGURED) {
            updateDeviceMetrics();
        }

        // Update last known connection state and node info status
        lastConnectionState = state;
        lastHadNodeInfo = hasNodeInfo;
    }
    
    private void showError(String message) {
        Toast.makeText(pluginContext, message, Toast.LENGTH_LONG).show();
        statusText.setText("Error: " + message);
    }
    
    private void showPermissionDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getMapView().getContext());
        builder.setTitle("Permissions Required");
        builder.setMessage("Bluetooth permissions are required to scan for Meshtastic devices. Please grant the necessary permissions in Settings.");
        builder.setPositiveButton("OK", null);
        builder.show();
    }


    @Override
    protected void disposeImpl() {
        if (bleScanner != null) {
            bleScanner.cleanup();
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (SHOW_MESHTASTIC.equals(action)) {
            showDropDown(templateView, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH,
                    HALF_HEIGHT, false, this);
        }
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    public void onDropDownVisible(boolean v) {
        if (v) {
            // Immediately update status when dropdown becomes visible
            updateConnectionStatus();
            // Resume rapid updates when visible (observeScanResults is already running)
        } else {
            // Stop scanning when hidden, but keep status polling running
            if (isScanning) {
                stopScan();
            }
        }
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
    }

    @Override
    public void onDropDownClose() {
        if (isScanning) {
            stopScan();
        }
    }
    
    /**
     * Called when auto-reconnection completes successfully
     * This ensures UI is updated properly for auto-connections
     */
    public void onAutoReconnectionComplete() {
        Log.d(TAG, "Auto-reconnection completed, forcing complete UI refresh");
        uiHandler.post(() -> {
            // Clear cached device info to force fresh lookup
            currentDeviceAddress = null;
            currentDeviceName = null;
            metadataUpdateNeeded = true;
            
            // Force immediate status update
            updateConnectionStatus();
            updateDeviceInfoForced();
            
            Log.d(TAG, "Auto-reconnection UI refresh completed");
        });
    }
    
    /**
     * Called when device name is updated (e.g., when NodeInfo is received)
     * This refreshes the UI to show the updated device name
     */
    public void onDeviceNameUpdated() {
        Log.i(TAG, "Device name updated - refreshing UI");
        uiHandler.post(() -> {
            // Reload device name from preferences if we have a device address
            if (currentDeviceAddress != null) {
                String prefKey = "plugin.meshtastic.last_device_name_" + currentDeviceAddress;
                String updatedName = preferences.get(prefKey, "Meshtastic Device");
                Log.i(TAG, "Reloaded device name: " + prefKey + " = " + updatedName);
                currentDeviceName = updatedName;
            } else {
                Log.w(TAG, "Cannot reload device name - currentDeviceAddress is null");
                currentDeviceName = null;
            }
            
            // Force immediate status update to refresh the name display
            updateConnectionStatus();
            updateDeviceInfoForced();
            
            Log.i(TAG, "Device name UI refresh completed");
        });
    }
    
    /**
     * Get the intent filter for this receiver
     */
    public DocumentedIntentFilter getFilter() {
        DocumentedIntentFilter filter = new DocumentedIntentFilter();
        filter.addAction(SHOW_MESHTASTIC);
        return filter;
    }
    
    /**
     * Device list adapter for the ListView
     */
    private class DeviceListAdapter extends BaseAdapter {
        private List<MeshtasticBleScanner.MeshtasticDevice> devices = new ArrayList<>();
        
        public void updateDevices(Map<String, MeshtasticBleScanner.MeshtasticDevice> deviceMap) {
            devices.clear();
            devices.addAll(deviceMap.values());
            
            // Sort by RSSI (strongest signal first) and Meshtastic devices first
            Collections.sort(devices, (d1, d2) -> {
                // Meshtastic devices first
                if (d1.isMeshtastic() && !d2.isMeshtastic()) return -1;
                if (!d1.isMeshtastic() && d2.isMeshtastic()) return 1;
                // Then by RSSI
                return Integer.compare(d2.getRssi(), d1.getRssi());
            });
            
            notifyDataSetChanged();
        }
        
        public void clearDevices() {
            devices.clear();
            notifyDataSetChanged();
        }
        
        @Override
        public int getCount() {
            return devices.size();
        }
        
        @Override
        public MeshtasticBleScanner.MeshtasticDevice getItem(int position) {
            return devices.get(position);
        }
        
        @Override
        public long getItemId(int position) {
            return position;
        }
        
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                LayoutInflater inflater = LayoutInflater.from(pluginContext);
                view = inflater.inflate(R.layout.device_list_item, parent, false);
            }
            
            MeshtasticBleScanner.MeshtasticDevice device = getItem(position);
            
            TextView nameText = view.findViewById(R.id.device_name);
            TextView addressText = view.findViewById(R.id.device_address);
            TextView rssiText = view.findViewById(R.id.device_rssi);
            ImageView icon = view.findViewById(R.id.device_icon);
            
            nameText.setText(device.getName());
            addressText.setText(device.getAddress());
            rssiText.setText("RSSI: " + device.getRssi() + " dBm");
            
            // Highlight Meshtastic devices
            if (device.isMeshtastic()) {
                nameText.setTextColor(0xFF00AA00); // Green for Meshtastic
                icon.setImageResource(R.drawable.ic_meshtastic);
            } else {
                nameText.setTextColor(0xFFFFFFFF); // White for others
                icon.setImageResource(R.drawable.ic_bluetooth);
            }
            
            return view;
        }
    }
}