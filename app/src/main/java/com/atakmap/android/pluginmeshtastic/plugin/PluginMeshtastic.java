
package com.atakmap.android.pluginmeshtastic.plugin;

import android.content.Context;
import android.util.Log;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.pluginmeshtastic.meshtastic.AtakMeshtasticBridge;
import com.atakmap.android.pluginmeshtastic.meshtastic.MeshtasticManager;
import com.atakmap.android.pluginmeshtastic.MeshtasticDropDownReceiver;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Timer;
import java.util.TimerTask;
import java.util.zip.GZIPOutputStream;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;
import android.content.Intent;
import com.atakmap.android.pluginmeshtastic.plugin.R;
import com.atakmap.coremap.cot.event.CotPoint;
import com.atakmap.coremap.maps.time.CoordinatedTime;
import com.geeksville.mesh.ATAKProtos;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class PluginMeshtastic implements IPlugin,
        CommsMapComponent.PreSendProcessor {

    private static final String TAG = "MeshtasticPlugin";
    
    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane templatePane;
    
    // Meshtastic integration
    private AtakMeshtasticBridge meshtasticBridge;
    private MeshtasticDropDownReceiver dropDownReceiver;
    private MapView mapView;
    
    // Track recently sent messages to prevent duplicates
    private final java.util.Set<String> recentlySentMessages = new java.util.HashSet<>();
    private final long DUPLICATE_WINDOW_MS = 5000; // 5 second window for duplicate detection

    public PluginMeshtastic(IServiceController serviceController) {
        try {
            this.serviceController = serviceController;
            final PluginContextProvider ctxProvider = serviceController
                    .getService(PluginContextProvider.class);
            if (ctxProvider != null) {
                pluginContext = ctxProvider.getPluginContext();
                if (pluginContext != null) {
                    pluginContext.setTheme(R.style.ATAKPluginTheme);
                }
            }

            // obtain the UI service
            uiService = serviceController.getService(IHostUIService.class);
            
            // Get MapView - might be null during initialization
            mapView = MapView.getMapView();

            // initialize the toolbar button for the plugin
            if (pluginContext != null) {
                // create the button
                toolbarItem = new ToolbarItem.Builder(
                        pluginContext.getString(R.string.app_name),
                        MarshalManager.marshal(
                                pluginContext.getResources().getDrawable(R.drawable.ic_launcher),
                                android.graphics.drawable.Drawable.class,
                                gov.tak.api.commons.graphics.Bitmap.class))
                        .setListener(new ToolbarItemAdapter() {
                            @Override
                            public void onClick(ToolbarItem item) {
                                // Send intent to show dropdown instead of pane
                                Intent intent = new Intent(MeshtasticDropDownReceiver.SHOW_MESHTASTIC);
                                AtakBroadcast.getInstance().sendBroadcast(intent);
                            }
                        })
                        .build();
            }
                    
            // Initialize Meshtastic bridge - use pluginContext to share SharedPreferences with UI
            if (pluginContext != null && mapView != null) {
                meshtasticBridge = new AtakMeshtasticBridge(pluginContext, serviceController);
            }
            
            // Initialize dropdown receiver - defer to onStart when mapView is more likely to be available
            Log.d(TAG, "Constructor: mapView=" + (mapView != null ? "available" : "null") + 
                  ", pluginContext=" + (pluginContext != null ? "available" : "null") + 
                  ", meshtasticBridge=" + (meshtasticBridge != null ? "available" : "null"));
            
            // Register handlers for ATAK messages
            setupMeshtasticHandlers();
            
        } catch (Exception e) {
            Log.e(TAG, "Error initializing Meshtastic plugin", e);
        }
    }

    @Override
    public void onStart() {
        Log.i(TAG, "Meshtastic Plugin starting");
        
        // the plugin is starting, add the button to the toolbar
        if (uiService == null)
            return;

        uiService.addToolbarItem(toolbarItem);
        
        // Create dropdown receiver in onStart when mapView should be available
        if (dropDownReceiver == null && pluginContext != null && meshtasticBridge != null) {
            mapView = MapView.getMapView(); // Try to get mapView again
            if (mapView != null) {
                Log.i(TAG, "Creating dropdown receiver in onStart");
                dropDownReceiver = new MeshtasticDropDownReceiver(mapView, pluginContext, meshtasticBridge);
                
                // Set the receiver reference in the bridge for UI updates
                meshtasticBridge.setDropDownReceiver(dropDownReceiver);
            } else {
                Log.w(TAG, "MapView still null in onStart, cannot create dropdown receiver");
            }
        }
        
        // Register the dropdown receiver
        if (dropDownReceiver != null) {
            Log.i(TAG, "Registering dropdown receiver");
            AtakBroadcast.getInstance().registerReceiver(dropDownReceiver, 
                dropDownReceiver.getFilter());
        } else {
            Log.w(TAG, "Cannot register dropdown receiver - it's null");
        }
        
        // Start Meshtastic service
        if (meshtasticBridge != null) {
            meshtasticBridge.initialize();
            
            // Attempt to auto-reconnect to previously connected device
            meshtasticBridge.attemptAutoReconnect();

            CommsMapComponent.getInstance().registerPreSendProcessor(this);
        }
    }

    @Override
    public void onStop() {
        Log.i(TAG, "Meshtastic Plugin stopping");
        
        // the plugin is stopping, remove the button from the toolbar
        if (uiService == null)
            return;

        uiService.removeToolbarItem(toolbarItem);
        
        // Unregister the dropdown receiver
        if (dropDownReceiver != null) {
            AtakBroadcast.getInstance().unregisterReceiver(dropDownReceiver);
            dropDownReceiver.dispose();
        }
        
        // Stop Meshtastic service
        if (meshtasticBridge != null) {
            meshtasticBridge.shutdown();
        }

        CommsMapComponent.getInstance().registerPreSendProcessor(null);

    }

    private void showPane() {
        // instantiate the plugin view if necessary
        if(templatePane == null) {
            // Remember to use the PluginLayoutInflator if you are actually inflating a custom view
            // In this case, using it is not necessary - but I am putting it here to remind
            // developers to look at this Inflator

            templatePane = new PaneBuilder(PluginLayoutInflater.inflate(pluginContext,
                    R.layout.main_layout, null))
                    // relative location is set to default; pane will switch location dependent on
                    // current orientation of device screen
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    // pane will take up 50% of screen width in landscape mode
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    // pane will take up 50% of screen height in portrait mode
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }

        // if the plugin pane is not visible, show it!
        if(!uiService.isPaneVisible(templatePane)) {
            uiService.showPane(templatePane, null);
        }
    }
    
    /**
     * Setup handlers for Meshtastic messages
     */
    private void setupMeshtasticHandlers() {
        if (meshtasticBridge == null) return;
        
        // Register CoT message handler
        meshtasticBridge.registerCoTHandler(new AtakMeshtasticBridge.CoTHandler() {
            @Override
            public void handleCoT(byte[] data, String fromNode) {
                Log.i(TAG, "Received CoT from node: " + fromNode + ", size: " + data.length + " bytes");
                String cotXml = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                Log.d(TAG, "CoT XML to dispatch: " + cotXml.substring(0, Math.min(cotXml.length(), 200)) + "...");
                dispatchPliMessage(cotXml, fromNode);
            }
        });
        
        // Register chat message handler
        meshtasticBridge.registerChatHandler(new AtakMeshtasticBridge.ChatHandler() {
            @Override
            public void handleChat(String message, String to, String toCallsign, ATAKProtos.Contact contact,  String fromNode) {
                Log.i(TAG, "Received chat from node : " + fromNode + ", message: \"" + message + "\" to: " + to);
                dispatchChatMessage(message, to, toCallsign, contact, fromNode);
            }
        });
        
        // Register ATAK Forwarder handler for chunked CoT messages (port 257)  
        meshtasticBridge.registerAtakForwarderHandler(new AtakMeshtasticBridge.AtakForwarderHandler() {
            @Override
            public void handleAtakForwarder(byte[] data, String fromNode) {
                Log.i(TAG, "Received ATAK Forwarder CoT from node: " + fromNode + ", size: " + data.length + " bytes");
                String cotXml = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                Log.d(TAG, "ATAK Forwarder CoT XML to dispatch: " + cotXml.substring(0, Math.min(cotXml.length(), 200)) + "...");
                dispatchCotMessage(cotXml, fromNode);
            }
        });
    }

    /**
     * Process incoming PLI message
     */
    private void dispatchPliMessage(String cotXml, String fromNode) {
        try {
            Log.d(TAG, "Dispatching CoT message from " + fromNode);
            Log.d(TAG, "CoT XML length: " + cotXml.length() + " characters");
            
            // Parse the CoT XML
            CotEvent cotEvent = CotEvent.parse(cotXml);
            CoordinatedTime time = new CoordinatedTime();
            cotEvent.setTime(time);
            cotEvent.setStart(time);
            cotEvent.setStale(time.addMinutes(10));

            CotDetail cotDetail = cotEvent.getDetail();

            String role = cotDetail.getChild("__group").getAttribute("role");
            role = switch (role) {
                case "TeamMember" -> "Team Member";
                case "TeamLead" -> "Team Lead";
                case "ForwardObserver" -> "Forward Observer";
                default -> role;
            };
            cotDetail.getChild("__group").setAttribute("role", role);

            String team = cotDetail.getChild("__group").getAttribute("name");
            team = switch (team) {
                case "DarkBlue" -> "Dark Blue";
                case "DarkGreen" -> "Dark Green";
                default -> team;
            };
            cotDetail.getChild("__group").setAttribute("name", team);

            if (cotEvent.isValid()) {
                Log.i(TAG, "Valid CoT event parsed, type: " + cotEvent.getType() + ", UID: " + cotEvent.getUID());
                // Dispatch to ATAK's internal system
                CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                Log.i(TAG, "CoT event dispatched to ATAK successfully");
            } else {
                Log.e(TAG, "Invalid CoT event - failed to parse or validate");
                Log.e(TAG, "CoT XML that failed: " + cotXml);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error dispatching CoT message", e);
            Log.e(TAG, "Problematic CoT XML: " + cotXml);
        }
    }

    /**
     * Process incoming CoT message from ATAK Forwarder (any CoT type)
     */
    private void dispatchCotMessage(String cotXml, String fromNode) {
        try {
            Log.d(TAG, "Dispatching ATAK Forwarder CoT message from " + fromNode);
            Log.d(TAG, "CoT XML length: " + cotXml.length() + " characters");
            
            // Parse the CoT XML
            CotEvent cotEvent = CotEvent.parse(cotXml);
            CoordinatedTime time = new CoordinatedTime();
            cotEvent.setTime(time);
            cotEvent.setStart(time);
            cotEvent.setStale(time.addMinutes(10));

            if (cotEvent.isValid()) {
                Log.i(TAG, "Valid ATAK Forwarder CoT event parsed, type: " + cotEvent.getType() + ", UID: " + cotEvent.getUID());
                CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                Log.i(TAG, "ATAK Forwarder CoT event dispatched to ATAK successfully");
            } else {
                Log.e(TAG, "Invalid ATAK Forwarder CoT event - failed to parse or validate");
                Log.e(TAG, "CoT XML that failed: " + cotXml);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error dispatching ATAK Forwarder CoT message", e);
            Log.e(TAG, "Problematic CoT XML: " + cotXml);
        }
    }

    /**
     * Process incoming chat message
     */
    private void dispatchChatMessage(String message, String to, String toCallsign, ATAKProtos.Contact contact, String fromNode) {
        try {
            Log.d(TAG, "Dispatching Chat message from " + fromNode);
            
            // Check for duplicate incoming messages
            String messageKey = contact.getCallsign() + ":" + message + ":" + to;
            
            synchronized (recentlySentMessages) {
                if (recentlySentMessages.contains(messageKey)) {
                    Log.w(TAG, "Duplicate incoming GeoChat detected, ignoring: " + messageKey);
                    return;
                }
                
                // Add to recent messages and schedule removal
                recentlySentMessages.add(messageKey);
                
                new java.util.Timer().schedule(new java.util.TimerTask() {
                    @Override
                    public void run() {
                        synchronized (recentlySentMessages) {
                            recentlySentMessages.remove(messageKey);
                        }
                    }
                }, DUPLICATE_WINDOW_MS);
            }

            //TODO add this feature back in later
/*
            if (!to.equals("All Chat Rooms") && (!to.equals(mapView.getDeviceCallsign()))) {
                Log.i(TAG, "Chat message not for us (to=" + to + "), ignoring");
                return;
            }
*/
            CotEvent cotEvent = new CotEvent();
            CoordinatedTime time = new CoordinatedTime();
            cotEvent.setTime(time);
            cotEvent.setStart(time);
            cotEvent.setStale(time.addMinutes(10));

            String callsign = contact.getCallsign();
            String deviceCallsign = contact.getDeviceCallsign();
            String msgId = callsign + "-" + deviceCallsign + "-" + message.hashCode() + "-" + System.currentTimeMillis();

            CotDetail cotDetail = new CotDetail("detail");

            CotDetail chatDetail = new CotDetail("__chat");
            chatDetail.setAttribute("parent", "RootContactGroup");
            chatDetail.setAttribute("groupOwner", "false");
            chatDetail.setAttribute("messageId", msgId);
            chatDetail.setAttribute("chatroom", toCallsign);
            chatDetail.setAttribute("id", to);
            chatDetail.setAttribute("senderCallsign", callsign);
            cotDetail.addChild(chatDetail);

            CotDetail chatgrp = new CotDetail("chatgrp");
            chatgrp.setAttribute("uid0", deviceCallsign);
            chatgrp.setAttribute("uid1", to);
            chatgrp.setAttribute("id", to);
            chatDetail.addChild(chatgrp);

            CotDetail linkDetail = new CotDetail("link");
            linkDetail.setAttribute("uid", deviceCallsign);
            linkDetail.setAttribute("type", "a-f-G-U-C");
            linkDetail.setAttribute("relation", "p-p");
            cotDetail.addChild(linkDetail);

            CotDetail serverDestinationDetail = new CotDetail("__serverdestination");
            serverDestinationDetail.setAttribute("destination", "0.0.0.0:4242:tcp:" + deviceCallsign);
            cotDetail.addChild(serverDestinationDetail);

            CotDetail remarksDetail = new CotDetail("remarks");
            remarksDetail.setAttribute("source", String.format("BAO.F.ATAK.%s", deviceCallsign));
            remarksDetail.setAttribute("to", to);
            remarksDetail.setAttribute("time", time.toString());
            remarksDetail.setInnerText(message);
            cotDetail.addChild(remarksDetail);

            cotEvent.setDetail(cotDetail);
            cotEvent.setUID("GeoChat." + deviceCallsign +"."+ msgId);
            cotEvent.setType("b-t-f");
            cotEvent.setHow("h-g-i-g-o");

            CotPoint cotPoint = new CotPoint(0, 0, CotPoint.UNKNOWN,
                    CotPoint.UNKNOWN, CotPoint.UNKNOWN);
            cotEvent.setPoint(cotPoint);

            Log.d(TAG, "Constructed CoT XML: " + cotEvent.toString());


            if (cotEvent.isValid()) {
                Log.i(TAG, "Valid GeoChat event parsed, type: " + cotEvent.getType() + ", UID: " + cotEvent.getUID());
                // Dispatch to ATAK's internal system
                CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                Log.i(TAG, "GeoChat event dispatched to ATAK successfully");
            } else {
                Log.e(TAG, "Invalid GeoChat event - failed to parse or validate");
                Log.e(TAG, "GeoChat XML that failed");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error dispatching GeoChat message", e);
            Log.e(TAG, "Problematic GeoChat XML");
        }
    }

    @Override
    public void processCotEvent(CotEvent cotEvent, String[] strings) {
        if (cotEvent == null || meshtasticBridge == null) return;
        
        // Check if Bluetooth is connected and configured before processing
        MeshtasticManager.ConnectionState connectionState = 
            meshtasticBridge.getConnectionState();
        if (connectionState != MeshtasticManager.ConnectionState.CONFIGURED) {
            Log.w(TAG, "Bluetooth not connected/configured (state: " + connectionState + 
                  "), skipping CoT processing");
            return;
        }
        
        String cotType = cotEvent.getType();
        String cotUid = cotEvent.getUID();
        
        Log.d(TAG, "Processing outgoing CoT: type=" + cotType + ", uid=" + cotUid);
        
        // Check if this is a PLI (Position Location Information) - self position report
        if (mapView != null && mapView.getSelfMarker() != null) {
            String selfUid = mapView.getSelfMarker().getUID();
            if (cotUid != null && cotUid.equals(selfUid)) {
                Log.i(TAG, "Detected outgoing PLI for self marker");
                handleOutgoingPli(cotEvent);
                return;
            }
        }
        
        // Check if this is a GeoChat message
        // GeoChat messages typically have type "b-t-f" and contain chat details
        if (cotType != null && cotType.startsWith("b-t-f")) {
            Log.i(TAG, "Detected outgoing GeoChat message");
            handleOutgoingGeoChat(cotEvent, cotUid == null || !cotUid.contains("All Chat Rooms"));
            return;
        }

        // Handle all other CoT events using detail payload with EXIM compression
        Log.i(TAG, "Processing general CoT event with detail payload");
        handleOutgoingCotDetail(cotEvent);
    }
    
    /**
     * Handle outgoing PLI (Position Location Information)
     */
    private void handleOutgoingPli(CotEvent cotEvent) {
        try {
            // Convert CotEvent to XML string and parse values
            String cotXml = cotEvent.toString();
            Log.d(TAG, "Processing PLI CoT XML: " + cotXml);

            CotDetail cotDetail = cotEvent.getDetail();

            // extract all needed values
            String callsign = cotDetail.getChild("contact").getAttribute("callsign");
            String deviceCallsign = cotEvent.getUID(); // Use UID as device callsign
            String course = cotDetail.getChild("track").getAttribute("course");
            String speed = cotDetail.getChild("track").getAttribute("speed");
            String groupRole = cotDetail.getChild("__group").getAttribute("role");
            String groupName = cotDetail.getChild("__group").getAttribute("name");
            String battery = cotDetail.getChild("status").getAttribute("battery");
            
            // Extract location from CotEvent's CotPoint
            CotPoint cotPoint = cotEvent.getCotPoint();
            double latitude = cotPoint.getLat();
            double longitude = cotPoint.getLon();
            double altitude = cotPoint.getHae(); // Height above ellipsoid

            // Create PLI protobuf message
            com.geeksville.mesh.ATAKProtos.PLI.Builder pliBuilder = 
                com.geeksville.mesh.ATAKProtos.PLI.newBuilder()
                    .setLatitudeI((int)(latitude * 1e7))
                    .setLongitudeI((int)(longitude * 1e7))
                    .setAltitude((int)altitude)
                    .setCourse(Double.valueOf(course).intValue())
                    .setSpeed(Double.valueOf(speed).intValue());
                    
            // Create contact info from parsed XML
            com.geeksville.mesh.ATAKProtos.Contact contact = 
                com.geeksville.mesh.ATAKProtos.Contact.newBuilder()
                    .setCallsign(callsign)
                    .setDeviceCallsign(deviceCallsign)
                    .build();
            
            // Create Group info from parsed XML - map team name to enum
            com.geeksville.mesh.ATAKProtos.Team team = mapTeamNameToEnum(groupName.replace(" ", ""));
            com.geeksville.mesh.ATAKProtos.MemberRole role = mapRoleToEnum(groupRole.replace(" ", ""));
            
            com.geeksville.mesh.ATAKProtos.Group group = 
                com.geeksville.mesh.ATAKProtos.Group.newBuilder()
                    .setRole(role)
                    .setTeam(team)
                    .build();
            
            // Create Status info from parsed XML
            com.geeksville.mesh.ATAKProtos.Status status = 
                com.geeksville.mesh.ATAKProtos.Status.newBuilder()
                    .setBattery(Integer.parseInt(battery))
                    .build();
            
            // Create TAKPacket
            com.geeksville.mesh.ATAKProtos.TAKPacket takPacket = 
                com.geeksville.mesh.ATAKProtos.TAKPacket.newBuilder()
                    .setIsCompressed(false)
                    .setContact(contact)
                    .setGroup(group)
                    .setStatus(status)
                    .setPli(pliBuilder.build())
                    .build();
            
            // Send via mesh on port 72
            byte[] takPacketBytes = takPacket.toByteArray();
            
            // Log what we're sending for debugging
            StringBuilder hexDump = new StringBuilder();
            for (byte b : takPacketBytes) {
                hexDump.append(String.format("%02x", b & 0xFF));
            }
            Log.d(TAG, "Sending TAKPacket protobuf (" + takPacketBytes.length + " bytes): " + hexDump.toString());
            
            // Check first few bytes
            if (takPacketBytes.length >= 2) {
                Log.d(TAG, "First two bytes being sent: 0x" + String.format("%02x", takPacketBytes[0] & 0xFF) + 
                      " 0x" + String.format("%02x", takPacketBytes[1] & 0xFF));
            }
            
            meshtasticBridge.sendAtakPluginMessage(takPacketBytes, null, 
                new kotlin.jvm.functions.Function1<Boolean, kotlin.Unit>() {
                    @Override
                    public kotlin.Unit invoke(Boolean success) {
                        if (success) {
                            Log.i(TAG, "PLI acknowledged: lat=" + latitude + 
                                  ", lon=" + longitude + ", callsign=" + callsign);
                        } else {
                            Log.w(TAG, "PLI failed (will retry): lat=" + latitude + 
                                  ", lon=" + longitude + ", callsign=" + callsign);
                        }
                        return kotlin.Unit.INSTANCE;
                    }
                });
            
            Log.i(TAG, "Sent PLI via mesh: lat=" + latitude + 
                  ", lon=" + longitude + ", callsign=" + callsign);
                  
        } catch (Exception e) {
            Log.e(TAG, "Error processing outgoing PLI", e);
        }
    }
    
    /**
     * Handle outgoing GeoChat message
     */
    private void handleOutgoingGeoChat(CotEvent cotEvent, boolean isDirectMessage) {
        try {

            if (cotEvent.getType().equals("b-t-f-d") || cotEvent.getType().equals("b-t-f-r")) {
                Log.w(TAG, "Detected GeoChat read receipt, ignoring");
                return;
            }

            // Convert CotEvent to XML string and parse values
            String cotXml = cotEvent.toString();
            Log.d(TAG, "Processing GeoChat CoT XML: " + cotXml);

            CotDetail cotDetail = cotEvent.getDetail();

            String chatMessage = cotDetail.getChild("remarks").getInnerText();
            String recipientId = cotDetail.getChild("__chat").getAttribute("id");
            String senderCallsign = cotDetail.getChild("__chat").getAttribute("senderCallsign");
            String deviceCallsign = cotDetail.getChild("link").getAttribute("uid");
            String messageId = cotDetail.getChild("__chat").getAttribute("messageId");
            
            // Create a unique key for this message
            String messageKey = senderCallsign + ":" + chatMessage + ":" + recipientId;
            
            // Check if we've recently sent this exact message
            synchronized (recentlySentMessages) {
                if (recentlySentMessages.contains(messageKey)) {
                    Log.w(TAG, "Duplicate GeoChat detected, ignoring: " + messageKey);
                    return;
                }
                
                // Add to recent messages and schedule removal
                recentlySentMessages.add(messageKey);
                
                // Remove from set after the duplicate window expires
                new java.util.Timer().schedule(new java.util.TimerTask() {
                    @Override
                    public void run() {
                        synchronized (recentlySentMessages) {
                            recentlySentMessages.remove(messageKey);
                        }
                    }
                }, DUPLICATE_WINDOW_MS);
            }

            // Create GeoChat protobuf message
            com.geeksville.mesh.ATAKProtos.GeoChat.Builder chatBuilder = 
                com.geeksville.mesh.ATAKProtos.GeoChat.newBuilder()
                    .setMessage(chatMessage)
                    .setTo(recipientId.equals("All Chat Rooms") ? "All Chat Rooms" : recipientId)
                    .setToCallsign(recipientId);
            
            // Create contact info from parsed XML
            com.geeksville.mesh.ATAKProtos.Contact contact = 
                com.geeksville.mesh.ATAKProtos.Contact.newBuilder()
                    .setCallsign(senderCallsign)
                    .setDeviceCallsign(deviceCallsign)
                    .build();
            
            // Create TAKPacket - GeoChat only needs contact and chat, no group or status
            com.geeksville.mesh.ATAKProtos.TAKPacket takPacket = 
                com.geeksville.mesh.ATAKProtos.TAKPacket.newBuilder()
                    .setIsCompressed(false)
                    .setContact(contact)
                    .setChat(chatBuilder.build())
                    .build();
            
            // Send via mesh on port 72
            byte[] takPacketBytes = takPacket.toByteArray();
            
            // Log what we're sending for debugging
            StringBuilder hexDump = new StringBuilder();
            for (byte b : takPacketBytes) {
                hexDump.append(String.format("%02x", b & 0xFF));
            }
            Log.d(TAG, "Sending GeoChat TAKPacket protobuf (" + takPacketBytes.length + " bytes): " + hexDump.toString());
            
            // Check first few bytes
            if (takPacketBytes.length >= 2) {
                Log.d(TAG, "First two bytes being sent: 0x" + String.format("%02x", takPacketBytes[0] & 0xFF) + 
                      " 0x" + String.format("%02x", takPacketBytes[1] & 0xFF));
            }
            
            final String finalChatMessage = chatMessage;
            final String finalSenderCallsign = senderCallsign;
            final String finalRecipientId = recipientId;
            
            meshtasticBridge.sendAtakPluginMessage(takPacketBytes, null,
                new kotlin.jvm.functions.Function1<Boolean, kotlin.Unit>() {
                    @Override
                    public kotlin.Unit invoke(Boolean success) {
                        if (success) {
                            Log.i(TAG, "GeoChat acknowledged: \"" + finalChatMessage +
                                  "\" from " + finalSenderCallsign + " to " + finalRecipientId);
                        } else {
                            Log.w(TAG, "GeoChat failed (will retry): \"" + finalChatMessage +
                                  "\" from " + finalSenderCallsign + " to " + finalRecipientId);
                        }
                        return kotlin.Unit.INSTANCE;
                    }
                });
            
            Log.i(TAG, "Sent GeoChat via mesh: \"" + chatMessage +
                  "\" from " + senderCallsign + " to " + finalRecipientId);

                  
        } catch (Exception e) {
            Log.e(TAG, "Error processing outgoing GeoChat", e);
        }
    }
    
    /**
     * Handle general CoT event using detail payload with EXI compression
     */
    private void handleOutgoingCotDetail(CotEvent cotEvent) {
        try {
            // Convert CoT to XML string
            String cotXml = cotEvent.toString();
            if (cotXml == null || cotXml.isEmpty()) {
                Log.w(TAG, "CoT XML is empty, skipping");
                return;
            }
            
            // Compress the XML using Gzip
            byte[] compressedXml = compressXml(cotXml);
            if (compressedXml == null) {
                Log.e(TAG, "Failed to compress CoT XML with EXI");
                return;
            }
            
            Log.d(TAG, "Compressed CoT from " + cotXml.length() + " chars to " + compressedXml.length + " bytes");
            
             // Check if we need to chunk the message
            // Meshtastic has a max packet size of ~240 bytes for payload
            final int MAX_PACKET_SIZE = 220; // Leave room for protocol overhead
            
            if (compressedXml.length <= MAX_PACKET_SIZE) {
                // Small enough to send in a single packet via ATAK_FORWARDER
                final String cotType = cotEvent.getType();
                final String cotUid = cotEvent.getUID();
                final int dataSize = compressedXml.length;
                
                meshtasticBridge.sendAtakForwarderMessage(compressedXml, null,
                    new kotlin.jvm.functions.Function1<Boolean, kotlin.Unit>() {
                        @Override
                        public kotlin.Unit invoke(Boolean success) {
                            if (success) {
                                Log.i(TAG, "CoT detail acknowledged: type=" + cotType + 
                                      ", uid=" + cotUid + ", size=" + dataSize + " bytes");
                            } else {
                                Log.w(TAG, "CoT detail failed (will retry): type=" + cotType + 
                                      ", uid=" + cotUid + ", size=" + dataSize + " bytes");
                            }
                            return kotlin.Unit.INSTANCE;
                        }
                    });

                Log.i(TAG, "Sent CoT via mesh (single packet): type=" + cotEvent.getType() +
                      ", uid=" + cotEvent.getUID() + ", size=" + compressedXml.length + " bytes");
            } else {
                // Need to chunk the message
                Log.i(TAG, "CoT too large (" + compressedXml.length + " bytes), chunking required");
                sendQueueAwareChunkedCotDetail(compressedXml);
            }
                  
        } catch (Exception e) {
            Log.e(TAG, "Error processing outgoing CoT detail", e);
        }
    }
    
    /**
     * Send chunked CoT detail when message is too large for single packet
     */
    private void sendChunkedCotDetail(final byte[] compressedData) {
        new Thread(() -> {
            try {
                Log.d(TAG, "Starting chunked send for CoT using ATAK_FORWARDER, total size: " + compressedData.length + " bytes");
                
                // Meshtastic max payload is ~240 bytes, but we need to account for:
                // - Protobuf overhead (~10-15 bytes)
                // - Chunk header (e.g., "0_", "10_", "100_" = 2-4 bytes)
                // Using 180 bytes to account for protobuf overhead and ensure reliable transmission
                final int CHUNK_SIZE = 180;  // Optimized size that works reliably with auto-assembly
                List<byte[]> chunks = divideArray(compressedData, CHUNK_SIZE);
                int totalChunks = chunks.size();
                
                // Send initial header packet: CHK_totalChunks_totalSize
                String headerPacket = String.format("CHK_%d_%d", totalChunks, compressedData.length);
                byte[] headerBytes = headerPacket.getBytes("UTF-8");
                Log.i(TAG, "Sending chunk header via port 257: " + headerPacket + " (" + headerBytes.length + " bytes)");
                
                // Debug: log the header bytes
                StringBuilder hexDump = new StringBuilder();
                for (byte b : headerBytes) {
                    hexDump.append(String.format("%02x ", b & 0xFF));
                }
                Log.d(TAG, "Header bytes (hex): " + hexDump.toString());
                
                // Use acknowledgment callback instead of sleep
                final CountDownLatch headerLatch = new CountDownLatch(1);
                final AtomicBoolean headerSuccess = new AtomicBoolean(false);
                
                meshtasticBridge.sendAtakForwarderMessageNoToast(headerBytes, null, 
                    new kotlin.jvm.functions.Function1<Boolean, kotlin.Unit>() {
                        @Override
                        public kotlin.Unit invoke(Boolean success) {
                            headerSuccess.set(success);
                            headerLatch.countDown();
                            return kotlin.Unit.INSTANCE;
                        }
                    });
                
                // Wait for header acknowledgment (max 30 seconds - mesh networks can be slow)
                if (!headerLatch.await(30, TimeUnit.SECONDS)) {
                    Log.e(TAG, "Timeout waiting for chunk header acknowledgment after 30 seconds, aborting");
                    meshtasticBridge.showChunkedTransmissionToast(false, totalChunks, compressedData.length, null);
                    return;
                }
                
                if (!headerSuccess.get()) {
                    Log.e(TAG, "Chunk header was not acknowledged (failed/timed out), aborting");
                    meshtasticBridge.showChunkedTransmissionToast(false, totalChunks, compressedData.length, null);
                    return;
                }
                
                Log.i(TAG, "Chunk header acknowledged: " + headerPacket);
                
                Log.i(TAG, "Sending " + totalChunks + " chunks for CoT message via ATAK_FORWARDER");
                
                // Send each chunk with format: index_data
                for (int i = 0; i < chunks.size(); i++) {
                    byte[] chunk = chunks.get(i);
                    final int chunkIndex = i;
                    
                    // Create chunk header: index_
                    String chunkHeader = String.format("%d_", i);
                    byte[] chunkHeaderBytes = chunkHeader.getBytes("UTF-8");
                    
                    // Combine header and chunk data
                    byte[] combined = new byte[chunkHeaderBytes.length + chunk.length];
                    System.arraycopy(chunkHeaderBytes, 0, combined, 0, chunkHeaderBytes.length);
                    System.arraycopy(chunk, 0, combined, chunkHeaderBytes.length, chunk.length);
                    
                    Log.d(TAG, String.format("Sending chunk %d/%d via port 257: header=%s, total_size=%d bytes",
                        i + 1, totalChunks, chunkHeader, combined.length));
                    
                    // Use acknowledgment for each chunk
                    final CountDownLatch chunkLatch = new CountDownLatch(1);
                    final AtomicBoolean chunkSuccess = new AtomicBoolean(false);
                    
                    meshtasticBridge.sendAtakForwarderMessageNoToast(combined, null,
                        new kotlin.jvm.functions.Function1<Boolean, kotlin.Unit>() {
                            @Override
                            public kotlin.Unit invoke(Boolean success) {
                                chunkSuccess.set(success);
                                chunkLatch.countDown();
                                return kotlin.Unit.INSTANCE;
                            }
                        });
                    
                    // Wait for chunk acknowledgment (max 30 seconds per chunk - mesh can be slow)
                    if (!chunkLatch.await(30, TimeUnit.SECONDS)) {
                        Log.e(TAG, "Timeout waiting for chunk " + (chunkIndex + 1) + "/" + totalChunks + " acknowledgment after 30 seconds, aborting");
                        meshtasticBridge.showChunkedTransmissionToast(false, totalChunks, compressedData.length, null);
                        return;
                    }
                    
                    if (!chunkSuccess.get()) {
                        Log.e(TAG, "Chunk " + (chunkIndex + 1) + "/" + totalChunks + " was not acknowledged (failed/timed out), aborting");
                        meshtasticBridge.showChunkedTransmissionToast(false, totalChunks, compressedData.length, null);
                        return;
                    }
                    
                    Log.d(TAG, "Chunk " + (i + 1) + "/" + totalChunks + " acknowledged");
                }
                
                Log.i(TAG, "Completed chunked send - END marker not needed (auto-assembly on receive)");
                // Show final success Toast
                meshtasticBridge.showChunkedTransmissionToast(true, totalChunks, compressedData.length, null);
                
            } catch (Exception e) {
                Log.e(TAG, "Error sending chunked CoT detail via ATAK_FORWARDER", e);
                // Show final failure Toast - use 0 for chunk count as we don't know how many were created
                meshtasticBridge.showChunkedTransmissionToast(false, 0, compressedData.length, null);
            }
        }).start();
    }
    
    /**
     * Send chunked CoT detail using queue-aware transmission
     * This method leverages queue status to send chunks more efficiently
     */
    private void sendQueueAwareChunkedCotDetail(final byte[] compressedData) {
        new Thread(() -> {
            // Define chunks outside try block for catch block access
            final int CHUNK_SIZE = 180;  // Optimized size that works reliably with auto-assembly
            List<byte[]> chunks = divideArray(compressedData, CHUNK_SIZE);
            
            try {
                Log.d(TAG, "Starting queue-aware chunked send for CoT using ATAK_FORWARDER, total size: " + compressedData.length + " bytes");
                
                // Send initial header packet: CHK_totalChunks_totalSize first  
                int totalChunks = chunks.size();
                String headerPacket = String.format("CHK_%d_%d", totalChunks, compressedData.length);
                byte[] headerBytes = headerPacket.getBytes("UTF-8");
                Log.i(TAG, "Sending chunk header via port 257: " + headerPacket);
                
                final CountDownLatch headerLatch = new CountDownLatch(1);
                final AtomicBoolean headerSuccess = new AtomicBoolean(false);
                
                meshtasticBridge.sendAtakForwarderMessageNoToast(headerBytes, null, 
                    new kotlin.jvm.functions.Function1<Boolean, kotlin.Unit>() {
                        @Override
                        public kotlin.Unit invoke(Boolean success) {
                            headerSuccess.set(success);
                            headerLatch.countDown();
                            return kotlin.Unit.INSTANCE;
                        }
                    });
                
                // Wait for header acknowledgment
                if (!headerLatch.await(30, TimeUnit.SECONDS) || !headerSuccess.get()) {
                    Log.e(TAG, "Chunk header failed or timed out, aborting queue-aware transmission");
                    meshtasticBridge.showChunkedTransmissionToast(false, totalChunks, compressedData.length, null);
                    return;
                }
                
                Log.i(TAG, "Header acknowledged, starting queue-aware chunk transmission");
                
                // Request current queue status first
                meshtasticBridge.requestQueueStatus();
                
                // Give it a moment to get the queue status
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                // Check available queue slots
                long freeSlots = meshtasticBridge.getAvailableQueueSlotsLong();
                Log.i(TAG, "Queue status: " + freeSlots + " free slots available");
                
                if (freeSlots > 0) {
                    // Use queue-aware batch sending
                    Log.i(TAG, "Using queue-aware batch transmission for " + chunks.size() + " chunks");
                    
                    // Send chunks in batches based on available slots
                    sendChunksBatched(chunks, (int)Math.min(freeSlots, chunks.size()));
                } else {
                    Log.w(TAG, "No queue slots available, falling back to serial transmission");
                    sendChunksSerial(chunks);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in queue-aware chunk transmission", e);
                // Fallback to serial method
                sendChunksSerial(chunks);
            }
        }).start();
    }
    
    /**
     * Send chunks in batches using queue awareness
     */
    private void sendChunksBatched(List<byte[]> chunks, int maxBatch) {
        try {
            Log.i(TAG, "Sending " + chunks.size() + " chunks in batches (max " + maxBatch + " at a time)");
            
            final CountDownLatch batchLatch = new CountDownLatch(chunks.size());
            final AtomicInteger successCount = new AtomicInteger(0);
            final AtomicInteger failureCount = new AtomicInteger(0);
            
            // Send all chunks at once, up to queue limit
            int batchSize = Math.min(maxBatch, chunks.size());
            for (int i = 0; i < batchSize && i < chunks.size(); i++) {
                final int chunkIndex = i;
                final byte[] chunk = chunks.get(i);
                
                // Create chunk with header
                String chunkHeader = String.format("%d_", i);
                byte[] chunkHeaderBytes = chunkHeader.getBytes("UTF-8");
                byte[] combined = new byte[chunkHeaderBytes.length + chunk.length];
                System.arraycopy(chunkHeaderBytes, 0, combined, 0, chunkHeaderBytes.length);
                System.arraycopy(chunk, 0, combined, chunkHeaderBytes.length, chunk.length);
                
                Log.i(TAG, "Queuing chunk " + (i + 1) + "/" + chunks.size() + " (" + combined.length + " bytes)");
                
                // Send without waiting for individual ACK
                meshtasticBridge.sendAtakForwarderMessageNoToast(combined, null, 
                    new kotlin.jvm.functions.Function1<Boolean, kotlin.Unit>() {
                        @Override
                        public kotlin.Unit invoke(Boolean success) {
                            if (success) {
                                int count = successCount.incrementAndGet();
                                Log.i(TAG, "Chunk " + (chunkIndex + 1) + "/" + chunks.size() + " ACK'd (" + count + " successful)");
                            } else {
                                int count = failureCount.incrementAndGet();
                                Log.w(TAG, "Chunk " + (chunkIndex + 1) + "/" + chunks.size() + " FAILED (" + count + " failed)");
                            }
                            batchLatch.countDown();
                            return kotlin.Unit.INSTANCE;
                        }
                    });
                
                // Small delay between queue submissions (not ACK waits)
                if (i < batchSize - 1) {
                    Thread.sleep(100); // Just 100ms between queue submissions
                }
            }
            
            Log.i(TAG, "All " + batchSize + " chunks queued, waiting for ACKs...");
            
            // Wait for all ACKs with generous timeout
            if (batchLatch.await(60, TimeUnit.SECONDS)) {
                Log.i(TAG, "=== CHUNK DEBUG: Batch transmission completed: " + successCount.get() + " success, " + failureCount.get() + " failed ===");
                Log.i(TAG, "=== CHUNK DEBUG: Batch size: " + batchSize + ", Total chunks: " + chunks.size() + " ===");
                
                if (successCount.get() == batchSize) {
                    // All chunks in this batch successful - no END marker needed with auto-assembly
                    Log.i(TAG, "=== CHUNK DEBUG: All chunks successful - auto-assembly will handle completion ===");
                    meshtasticBridge.showChunkedTransmissionToast(true, chunks.size(), 0, null); // Note: 0 for size as it's not available in this context
                } else {
                    Log.e(TAG, "=== CHUNK DEBUG: Some chunks failed in batch transmission ===");
                    meshtasticBridge.showChunkedTransmissionToast(false, chunks.size(), 0, null);
                }
            } else {
                Log.e(TAG, "Timeout waiting for batch chunk ACKs");
                meshtasticBridge.showChunkedTransmissionToast(false, chunks.size(), 0, null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in batched chunk sending", e);
            meshtasticBridge.showChunkedTransmissionToast(false, chunks.size(), 0, null);
        }
    }
    
    
    /**
     * Fallback serial chunk sending method
     */
    private void sendChunksSerial(List<byte[]> chunks) {
        try {
            Log.i(TAG, "Using serial chunk transmission");
            boolean allChunksSuccess = true;
            final int numChunks = chunks.size();
            
            for (int i = 0; i < chunks.size(); i++) {
                    byte[] chunk = chunks.get(i);
                    final int chunkIndex = i;
                    
                    // Create chunk header: index_
                    String chunkHeader = String.format("%d_", i);
                    byte[] chunkHeaderBytes = chunkHeader.getBytes("UTF-8");
                    
                    // Combine header and chunk data
                    byte[] combined = new byte[chunkHeaderBytes.length + chunk.length];
                    System.arraycopy(chunkHeaderBytes, 0, combined, 0, chunkHeaderBytes.length);
                    System.arraycopy(chunk, 0, combined, chunkHeaderBytes.length, chunk.length);
                    
                    Log.i(TAG, String.format("Sending chunk %d/%d via port 257: header='%s', chunk_size=%d, total_size=%d bytes",
                        i + 1, numChunks, chunkHeader, chunk.length, combined.length));
                    
                    // Debug: log first few bytes of combined data
                    StringBuilder preview = new StringBuilder();
                    for (int j = 0; j < Math.min(20, combined.length); j++) {
                        if (combined[j] >= 32 && combined[j] <= 126) {
                            preview.append((char) combined[j]);
                        } else {
                            preview.append(String.format("\\x%02x", combined[j] & 0xFF));
                        }
                    }
                    Log.d(TAG, "Chunk " + (i + 1) + " preview: " + preview.toString());
                    
                    // Use acknowledgment for each chunk
                    final CountDownLatch chunkLatch = new CountDownLatch(1);
                    final AtomicBoolean chunkSuccess = new AtomicBoolean(false);
                    
                    meshtasticBridge.sendAtakForwarderMessageNoToast(combined, null,
                        new kotlin.jvm.functions.Function1<Boolean, kotlin.Unit>() {
                            @Override
                            public kotlin.Unit invoke(Boolean success) {
                                Log.i(TAG, String.format("Chunk %d/%d %s", 
                                    chunkIndex + 1, numChunks, success ? "ACK'd" : "FAILED"));
                                chunkSuccess.set(success);
                                chunkLatch.countDown();
                                return kotlin.Unit.INSTANCE;
                            }
                        });
                    
                    // Wait for chunk acknowledgment
                    if (!chunkLatch.await(30, TimeUnit.SECONDS)) {
                        Log.e(TAG, "Timeout waiting for chunk " + (chunkIndex + 1) + "/" + numChunks + " acknowledgment, aborting");
                        meshtasticBridge.showChunkedTransmissionToast(false, numChunks, 0, null);
                        allChunksSuccess = false;
                        break;
                    }
                    
                    if (!chunkSuccess.get()) {
                        Log.e(TAG, "Chunk " + (chunkIndex + 1) + "/" + numChunks + " was not acknowledged, aborting");
                        meshtasticBridge.showChunkedTransmissionToast(false, numChunks, 0, null);
                        allChunksSuccess = false;
                        break;
                    }
                    
                    Log.i(TAG, "Chunk " + (i + 1) + "/" + numChunks + " successfully sent and acknowledged");
                    
                    // Small delay between chunks to prevent mesh congestion
                    if (i < numChunks - 1) {  // Don't delay after the last chunk
                        try {
                            Thread.sleep(1000);  // 1 second delay
                            Log.d(TAG, "Paused 1s before next chunk");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            Log.w(TAG, "Chunk delay interrupted");
                        }
                    }
                }
                
                if (allChunksSuccess) {
                    Log.i(TAG, "Chunked send completed successfully - auto-assembly will handle completion");
                    meshtasticBridge.showChunkedTransmissionToast(true, numChunks, 0, null);
                } else {
                    Log.e(TAG, "Chunked send failed");
                    meshtasticBridge.showChunkedTransmissionToast(false, numChunks, 0, null);
                }
                
        } catch (Exception e) {
            Log.e(TAG, "Error in sendChunksSerial", e);
            meshtasticBridge.showChunkedTransmissionToast(false, 0, 0, null); // Don't know numChunks in exception context
        }
    }
    
    /**
     * Compress XML using GZIP compression
     */
    private byte[] compressXml(String xml) {
        if (xml == null || xml.isEmpty()) {
            return null;
        }
        
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            GZIPOutputStream gzipOut = new GZIPOutputStream(baos);
            
            gzipOut.write(xml.getBytes("UTF-8"));
            gzipOut.close();
            
            byte[] compressed = baos.toByteArray();
            baos.close();
            
            Log.d(TAG, "GZIP compressed XML from " + xml.length() + " chars to " + compressed.length + " bytes");
            return compressed;
            
        } catch (IOException e) {
            Log.e(TAG, "Failed to compress XML with GZIP", e);
            return null;
        }
    }
    
    /**
     * Divide byte array into chunks
     */
    private List<byte[]> divideArray(byte[] source, int chunkSize) {
        List<byte[]> result = new ArrayList<>();
        int start = 0;
        while (start < source.length) {
            int end = Math.min(source.length, start + chunkSize);
            byte[] chunk = new byte[end - start];
            System.arraycopy(source, start, chunk, 0, chunk.length);
            result.add(chunk);
            start += chunkSize;
        }
        return result;
    }
    
    /**
     * Extract XML attribute value from a tag string
     */
    private String extractAttribute(String xmlTag, String attributeName) {
        String pattern = attributeName + "=['\"]";
        int start = xmlTag.indexOf(pattern);
        if (start == -1) return "";
        
        start += pattern.length();
        char quote = xmlTag.charAt(start - 1);
        int end = xmlTag.indexOf(quote, start);
        if (end == -1) return "";
        
        return xmlTag.substring(start, end);
    }
    
    /**
     * Map team name string to protobuf enum
     */
    private com.geeksville.mesh.ATAKProtos.Team mapTeamNameToEnum(String teamName) {
        if (teamName == null || teamName.isEmpty()) {
            return com.geeksville.mesh.ATAKProtos.Team.Cyan;
        }
        
        switch (teamName.toLowerCase()) {
            case "white": return com.geeksville.mesh.ATAKProtos.Team.White;
            case "yellow": return com.geeksville.mesh.ATAKProtos.Team.Yellow;
            case "orange": return com.geeksville.mesh.ATAKProtos.Team.Orange;
            case "magenta": return com.geeksville.mesh.ATAKProtos.Team.Magenta;
            case "red": return com.geeksville.mesh.ATAKProtos.Team.Red;
            case "maroon": return com.geeksville.mesh.ATAKProtos.Team.Maroon;
            case "purple": return com.geeksville.mesh.ATAKProtos.Team.Purple;
            case "dark blue": case "darkblue": case "dark_blue": return com.geeksville.mesh.ATAKProtos.Team.Dark_Blue;
            case "blue": return com.geeksville.mesh.ATAKProtos.Team.Blue;
            case "cyan": return com.geeksville.mesh.ATAKProtos.Team.Cyan;
            case "teal": return com.geeksville.mesh.ATAKProtos.Team.Teal;
            case "green": return com.geeksville.mesh.ATAKProtos.Team.Green;
            case "dark green": case "darkgreen": case "dark_green": return com.geeksville.mesh.ATAKProtos.Team.Dark_Green;
            case "brown": return com.geeksville.mesh.ATAKProtos.Team.Brown;
            default: return com.geeksville.mesh.ATAKProtos.Team.Cyan;
        }
    }
    
    /**
     * Map role string to protobuf enum
     */
    private com.geeksville.mesh.ATAKProtos.MemberRole mapRoleToEnum(String roleString) {
        if (roleString == null || roleString.isEmpty()) {
            return com.geeksville.mesh.ATAKProtos.MemberRole.TeamMember;
        }
        
        switch (roleString.toLowerCase()) {
            case "team leader": case "teamleader": return com.geeksville.mesh.ATAKProtos.MemberRole.TeamLead;
            case "headquarters": case "hq": return com.geeksville.mesh.ATAKProtos.MemberRole.HQ;
            case "sniper": return com.geeksville.mesh.ATAKProtos.MemberRole.Sniper;
            case "medic": return com.geeksville.mesh.ATAKProtos.MemberRole.Medic;
            case "forward observer": case "forwardobserver": return com.geeksville.mesh.ATAKProtos.MemberRole.ForwardObserver;
            case "rto": return com.geeksville.mesh.ATAKProtos.MemberRole.RTO;
            case "k9": return com.geeksville.mesh.ATAKProtos.MemberRole.K9;
            case "team member": case "teammember": default: return com.geeksville.mesh.ATAKProtos.MemberRole.TeamMember;
        }
    }
}
