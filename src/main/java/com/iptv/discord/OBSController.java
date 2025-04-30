package com.iptv.discord;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class OBSController implements WebSocket.Listener {
    private static final Logger LOGGER = Logger.getLogger(OBSController.class.getName());

    private WebSocket webSocket;
    private final String serverAddress;
    private final String password;
    private boolean isConnected = false;
    private boolean isAuthenticated = false;
    private boolean connectionInProgress = false;

    // For tracking message responses
    private final AtomicInteger messageId = new AtomicInteger(1);
    private final ConcurrentMap<Integer, CompletableFuture<JSONObject>> pendingRequests = new ConcurrentHashMap<>();

    // Default WebSocket port for OBS v5
    private static final int DEFAULT_PORT = 4444;
    private int currentPort = DEFAULT_PORT;

    // Scene and source names
    private final String sceneName = "IPTV Scene";
    private final String sourceNamePrefix = "IPTV-Source-";
    private String currentSourceName;

    private final SimpleRateLimiter obsRequestLimiter = new SimpleRateLimiter(10.0);

    public OBSController() {
        this("localhost", "");
    }

    public OBSController(String serverAddress, String password) {
        this.serverAddress = serverAddress;
        this.password = password;
    }

    public boolean isConnected() {
        return isConnected;
    }

    private CompletableFuture<JSONObject> sendRateLimitedRequest(String requestType, JSONObject data) {
        // Wait for rate limiter permission
        obsRequestLimiter.acquire();

        // Then send the actual request
        return sendRequest(requestType, data);
    }

    public boolean connect() {
        // Try the configured port first
        if (connectToPort(DEFAULT_PORT)) {
            return true;
        }

        // If that fails, try common alternative ports
        int[] fallbackPorts = {4455, 4444, 4456};
        for (int port : fallbackPorts) {
            if (port != DEFAULT_PORT) {
                LOGGER.info("Trying alternate port: " + port);
                if (connectToPort(port)) {
                    return true;
                }
            }
        }

        LOGGER.severe("Failed to connect to OBS WebSocket on any port");
        return false;
    }

    private boolean connectToPort(int port) {
        String uri = "ws://" + serverAddress + ":" + port;

        try {
            // Reset connection state variables
            isConnected = false;
            isAuthenticated = false;
            connectionInProgress = true;
            currentPort = port;

            LOGGER.info("Connecting to OBS WebSocket server at: " + uri);

            HttpClient client = HttpClient.newHttpClient();
            CompletableFuture<WebSocket> future = client.newWebSocketBuilder()
                    .buildAsync(new URI(uri), this);

            try {
                webSocket = future.get(3, TimeUnit.SECONDS); // Shorter timeout for port probing
            } catch (Exception e) {
                LOGGER.warning("Error connecting to OBS WebSocket server on port " + port + ": " + e.getMessage());
                connectionInProgress = false;
                webSocket = null;
                return false;
            }

            // Wait for connection, hello message, and identification
            long startTime = System.currentTimeMillis();
            long timeout = 5000; // 5 seconds timeout (shorter for port probing)

            // First wait for connection
            while (!isConnected && System.currentTimeMillis() - startTime < timeout) {
                Thread.sleep(100);
            }

            if (!isConnected) {
                LOGGER.warning("Connection timed out on port " + port);
                connectionInProgress = false;
                if (webSocket != null) {
                    webSocket.abort();
                    webSocket = null;
                }
                return false;
            }

            LOGGER.info("Connected to OBS WebSocket server on port " + port);

            // Then wait for authentication/identification
            startTime = System.currentTimeMillis();
            while (!isAuthenticated && connectionInProgress && System.currentTimeMillis() - startTime < timeout) {
                Thread.sleep(100);
                LOGGER.fine("Waiting for authentication... isAuthenticated=" + isAuthenticated);
            }

            if (!isAuthenticated) {
                LOGGER.warning("Identification timed out or failed on port " + port);
                connectionInProgress = false;
                if (webSocket != null) {
                    webSocket.abort();
                    webSocket = null;
                }
                return false;
            }

            connectionInProgress = false;
            LOGGER.info("Successfully authenticated and identified with OBS WebSocket on port " + port);
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error connecting to OBS WebSocket server on port " + port + ": " + e.getMessage());
            connectionInProgress = false;
            if (webSocket != null) {
                webSocket.abort();
                webSocket = null;
            }
            return false;
        }
    }

    private boolean authenticate(JSONObject hello) {
        if (webSocket == null) {
            LOGGER.severe("Cannot authenticate - WebSocket is null");
            return false;
        }

        try {
            LOGGER.info("Processing authentication for OBS WebSocket v5");

            // Extract authentication details from hello message
            JSONObject d = hello.getJSONObject("d");
            boolean authRequired = false;
            String challenge = null;
            String salt = null;

            // Check if authentication is required
            if (d.has("authentication") && !d.isNull("authentication")) {
                authRequired = true;
                JSONObject auth = d.getJSONObject("authentication");
                LOGGER.info("Authentication required by OBS WebSocket");
                challenge = auth.getString("challenge");
                salt = auth.getString("salt");
            }

            // Create identity message
            JSONObject identify = new JSONObject();
            identify.put("op", 1); // Identify op code for OBS WebSocket v5

            JSONObject identifyData = new JSONObject();
            identifyData.put("rpcVersion", 1);

            // Add authentication if required
            if (authRequired && challenge != null && salt != null) {
                if (password == null || password.isEmpty()) {
                    LOGGER.warning("OBS requires authentication but no password was provided");
                    // Continue anyway, using empty password
                }

                String authResponse = calculateAuthResponse(salt, challenge, password);
                identifyData.put("authentication", authResponse);
                LOGGER.info("Added authentication token to identify message");
            } else {
                LOGGER.info("No authentication required by OBS WebSocket");
            }

            // Always subscribe to essential events (bitwise flags)
            identifyData.put("eventSubscriptions", 33);
            identify.put("d", identifyData);

            // Send identify request
            LOGGER.info("Sending identify request to OBS WebSocket");
            webSocket.sendText(identify.toString(), true);

            // For non-authenticated connections, set authenticated immediately
            if (!authRequired) {
                isAuthenticated = true;
            }

            return true;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Authentication error: " + e.getMessage(), e);
            return false;
        }
    }

    private String calculateAuthResponse(String salt, String challenge, String password) {
        try {
            if (password == null || password.isEmpty()) {
                LOGGER.warning("Password is empty, but authentication is required");
                // Use empty password anyway
                password = "";
            }

            LOGGER.info("Calculating authentication response for salt: " + salt);

            // First, generate the secret from the password and salt
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");

            // Decode salt from base64
            byte[] decodedSalt = Base64.getDecoder().decode(salt);

            // Combine password and salt
            byte[] passwordBytes = password.getBytes("UTF-8");
            byte[] combined = new byte[passwordBytes.length + decodedSalt.length];
            System.arraycopy(passwordBytes, 0, combined, 0, passwordBytes.length);
            System.arraycopy(decodedSalt, 0, combined, passwordBytes.length, decodedSalt.length);

            // Hash the combined password and salt
            byte[] secretBytes = sha256.digest(combined);

            // Now, use the secret with the challenge to create the authentication string
            Mac hmac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secretBytes, "HmacSHA256");
            hmac.init(keySpec);

            byte[] decodedChallenge = Base64.getDecoder().decode(challenge);
            byte[] authBytes = hmac.doFinal(decodedChallenge);

            // Encode the final result
            String result = Base64.getEncoder().encodeToString(authBytes);
            LOGGER.info("Authentication response calculated successfully");
            return result;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error calculating authentication response", e);
            return "";
        }
    }

    private CompletableFuture<JSONObject> sendRequest(String requestType, JSONObject data) {
        if (webSocket == null) {
            CompletableFuture<JSONObject> errorFuture = new CompletableFuture<>();
            JSONObject errorResponse = new JSONObject();
            errorResponse.put("requestStatus", false);
            errorResponse.put("responseData", new JSONObject().put("comment", "WebSocket is not connected"));
            errorFuture.complete(errorResponse);
            return errorFuture;
        }

        int id = messageId.getAndIncrement();

        JSONObject request = new JSONObject();
        request.put("op", 6); // RequestOp code in v5

        JSONObject requestData = new JSONObject();
        requestData.put("requestId", String.valueOf(id));
        requestData.put("requestType", requestType);

        if (data != null) {
            requestData.put("requestData", data);
        }

        request.put("d", requestData);

        CompletableFuture<JSONObject> responseFuture = new CompletableFuture<>();
        pendingRequests.put(id, responseFuture);

        LOGGER.fine("Sending OBS request: " + request.toString());
        webSocket.sendText(request.toString(), true);

        return responseFuture;
    }

    public boolean setupStream(String streamUrl, String channelName) {
        try {
            if (!isConnected || !isAuthenticated) {
                LOGGER.warning("Not connected to OBS or not authenticated");
                return false;
            }

            // Generate a unique source name based on channel name
            String safeName = channelName.replaceAll("[^a-zA-Z0-9]", "_");
            currentSourceName = sourceNamePrefix + safeName;
            LOGGER.info("Setting up stream with source name: " + currentSourceName);

            // Try to setup the stream with the standard name
            boolean success = attemptCreateSource(streamUrl, currentSourceName);

            // If failed, try with a timestamp appended name for uniqueness
            if (!success) {
                String timestampedName = currentSourceName + "_" + System.currentTimeMillis();
                LOGGER.info("First attempt failed, trying with timestamp: " + timestampedName);
                success = attemptCreateSource(streamUrl, timestampedName);
                if (success) {
                    currentSourceName = timestampedName;
                }
            }

            return success;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Comprehensive error in stream setup", e);
            return false;
        }
    }

    private boolean attemptCreateSource(String streamUrl, String sourceName) {
        try {
            // Check if scene exists, create if not
            LOGGER.info("Checking if scene exists: " + sceneName);
            if (!checkSceneExists(sceneName)) {
                LOGGER.info("Scene doesn't exist, creating: " + sceneName);
                createScene(sceneName);
            }

            // Force removal of any existing source with the same name
            forceSourceRemoval(sourceName);

            // Create media source
            LOGGER.info("Creating media source: " + sourceName + " with URL: " + streamUrl);
            createMediaSource(sourceName, streamUrl);

            // Start virtual camera if not already started
            LOGGER.info("Starting virtual camera");
            startVirtualCam();

            LOGGER.info("Stream setup completed successfully");
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to create source: " + e.getMessage(), e);
            return false;
        }
    }

    // In OBSController.java, add this utility method:
    private boolean forceSourceRemoval(String sourceName) {
        try {
            LOGGER.info("Force removing source: " + sourceName);

            // First, check if the source exists in the inputs list
            JSONObject getInputsData = new JSONObject();
            JSONObject getInputsResponse = sendRequest("GetInputList", getInputsData).get(5, TimeUnit.SECONDS);

            boolean inputExists = false;
            if (isRequestSuccessful(getInputsResponse)) {
                JSONObject responseData = getInputsResponse.getJSONObject("responseData");
                JSONArray inputs = responseData.getJSONArray("inputs");

                for (int i = 0; i < inputs.length(); i++) {
                    if (inputs.getJSONObject(i).getString("inputName").equals(sourceName)) {
                        inputExists = true;
                        break;
                    }
                }
            }

            if (inputExists) {
                // First try to get it from the scene
                JSONObject data = new JSONObject();
                data.put("sceneName", sceneName);
                JSONObject response = sendRequest("GetSceneItemList", data).get(5, TimeUnit.SECONDS);

                if (isRequestSuccessful(response)) {
                    JSONObject responseData = response.getJSONObject("responseData");
                    JSONArray sceneItems = responseData.getJSONArray("sceneItems");

                    for (int i = 0; i < sceneItems.length(); i++) {
                        JSONObject item = sceneItems.getJSONObject(i);
                        if (item.getString("sourceName").equals(sourceName)) {
                            int itemId = item.getInt("sceneItemId");

                            // Remove scene item
                            JSONObject removeData = new JSONObject();
                            removeData.put("sceneName", sceneName);
                            removeData.put("sceneItemId", itemId);

                            JSONObject removeResponse = sendRequest("RemoveSceneItem", removeData).get(5, TimeUnit.SECONDS);
                            LOGGER.info("RemoveSceneItem Response: " + removeResponse.toString());
                        }
                    }
                }

                // Then remove the input itself
                JSONObject removeData = new JSONObject();
                removeData.put("inputName", sourceName);

                JSONObject removeResponse = sendRequest("RemoveInput", removeData).get(5, TimeUnit.SECONDS);
                LOGGER.info("Remove Input Response: " + removeResponse.toString());

                // Add a small delay to allow OBS to fully release the resource
                Thread.sleep(500);

                return true;
            } else {
                LOGGER.info("Source not found, no need to remove: " + sourceName);
                return true;
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to remove source: " + e.getMessage());
            // Return true anyway to continue with source creation
            return true;
        }
    }

    private boolean checkSceneExists(String sceneName) throws Exception {
        JSONObject response = sendRequest("GetSceneList", null).get(5, TimeUnit.SECONDS);

        // Verify request status
        JSONObject requestStatus = response.getJSONObject("requestStatus");
        if (!requestStatus.getBoolean("result")) {
            String comment = requestStatus.has("comment") ?
                    requestStatus.getString("comment") : "Unknown error";
            throw new Exception("Failed to get scene list: " + comment);
        }

        // Extract scenes from response
        JSONObject responseData = response.getJSONObject("responseData");
        JSONArray scenes = responseData.getJSONArray("scenes");

        for (int i = 0; i < scenes.length(); i++) {
            JSONObject scene = scenes.getJSONObject(i);
            if (scene.getString("sceneName").equals(sceneName)) {
                return true;
            }
        }

        return false;
    }

    private String getErrorMessage(JSONObject response) {
        try {
            if (response.has("requestStatus")) {
                JSONObject requestStatus = response.getJSONObject("requestStatus");
                if (requestStatus.has("comment")) {
                    return requestStatus.getString("comment");
                }
            }
            return "Unknown error occurred";
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error extracting error message", e);
            return "Error parsing error message";
        }
    }

    private boolean isRequestSuccessful(JSONObject response) {
        try {
            // Log full response for debugging
            LOGGER.info("Full response: " + response.toString());

            // Check different possible response structures

            // Check if the response has a 'requestStatus' key at the top level
            if (response.has("requestStatus")) {
                JSONObject statusObj = response.getJSONObject("requestStatus");

                // Check for 'result' or 'code'
                if (statusObj.has("result")) {
                    return statusObj.getBoolean("result");
                }

                if (statusObj.has("code")) {
                    int code = statusObj.getInt("code");
                    // Typically, code 100 means success in OBS WebSocket
                    return code == 100;
                }
            }

            // Check if the response has a 'd' key with request details
            if (response.has("d")) {
                JSONObject details = response.getJSONObject("d");

                // Check for different possible status indicators
                if (details.has("requestStatus")) {
                    JSONObject statusJson = details.getJSONObject("requestStatus");

                    // Check for 'result' boolean
                    if (statusJson.has("result")) {
                        return statusJson.getBoolean("result");
                    }

                    // Check for 'code' (some OBS versions use a status code)
                    if (statusJson.has("code")) {
                        int code = statusJson.getInt("code");
                        // Typically, code 100 means success in OBS WebSocket
                        return code == 100;
                    }
                }
            }

            // Also check for direct responseData
            if (response.has("responseData")) {
                // If there's responseData and we got this far, assume success
                return true;
            }

            // If we have a direct code field
            if (response.has("code")) {
                int code = response.getInt("code");
                return code == 100;
            }

            // If no clear status found, log and return false
            LOGGER.warning("No clear request status found in response");
            return false;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error checking request status", e);
            return false;
        }
    }

    private String extractErrorMessage(JSONObject response) {
        try {
            // Check for error message in different possible locations
            if (response.has("requestStatus")) {
                JSONObject statusObj = response.getJSONObject("requestStatus");
                if (statusObj.has("comment")) {
                    return statusObj.getString("comment");
                }
            }

            if (response.has("d")) {
                JSONObject details = response.getJSONObject("d");

                if (details.has("requestStatus")) {
                    Object statusObj = details.get("requestStatus");

                    if (statusObj instanceof JSONObject) {
                        JSONObject statusJson = (JSONObject) statusObj;

                        // Check for various possible error message fields
                        if (statusJson.has("comment")) {
                            return statusJson.getString("comment");
                        }
                        if (statusJson.has("description")) {
                            return statusJson.getString("description");
                        }
                    }
                }
            }

            return "Unknown error occurred in OBS WebSocket request";
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error extracting error message", e);
            return "Error parsing error message";
        }
    }

    private void createScene(String sceneName) throws Exception {
        JSONObject data = new JSONObject();
        data.put("sceneName", sceneName);

        JSONObject response = sendRequest("CreateScene", data).get(5, TimeUnit.SECONDS);

        // Verify request status
        JSONObject requestStatus = response.getJSONObject("requestStatus");
        if (!requestStatus.getBoolean("result")) {
            String comment = requestStatus.has("comment") ?
                    requestStatus.getString("comment") : "Unknown error creating scene";
            throw new Exception("Failed to create scene: " + comment);
        }

        // Set as current scene
        data = new JSONObject();
        data.put("sceneName", sceneName);

        response = sendRequest("SetCurrentProgramScene", data).get(5, TimeUnit.SECONDS);

        // Verify request status again
        requestStatus = response.getJSONObject("requestStatus");
        if (!requestStatus.getBoolean("result")) {
            String comment = requestStatus.has("comment") ?
                    requestStatus.getString("comment") : "Unknown error setting current scene";
            throw new Exception("Failed to set current scene: " + comment);
        }
    }

    private boolean checkSourceExists(String sourceName) throws Exception {
        JSONObject data = new JSONObject();
        data.put("sceneName", sceneName);

        JSONObject response = sendRequest("GetSceneItemList", data).get(5, TimeUnit.SECONDS);

        // Verify request status
        JSONObject requestStatus = response.getJSONObject("requestStatus");
        if (!requestStatus.getBoolean("result")) {
            String comment = requestStatus.has("comment") ?
                    requestStatus.getString("comment") : "Unknown error";
            throw new Exception("Failed to get scene items: " + comment);
        }

        // Extract scene items from response
        JSONObject responseData = response.getJSONObject("responseData");
        JSONArray sceneItems = responseData.getJSONArray("sceneItems");

        for (int i = 0; i < sceneItems.length(); i++) {
            JSONObject item = sceneItems.getJSONObject(i);
            if (item.getString("sourceName").equals(sourceName)) {
                return true;
            }
        }

        return false;
    }

    private void removeSource(String sourceName) throws Exception {
        try {
            // First get the item ID
            JSONObject data = new JSONObject();
            data.put("sceneName", sceneName);

            JSONObject response = sendRequest("GetSceneItemList", data).get(5, TimeUnit.SECONDS);

            // Log full response for debugging
            LOGGER.info("GetSceneItemList Response: " + response.toString());

            // Extract scene items from response
            JSONObject responseData = response.getJSONObject("d").getJSONObject("responseData");
            JSONArray sceneItems = responseData.getJSONArray("sceneItems");

            Integer itemId = null;
            for (int i = 0; i < sceneItems.length(); i++) {
                JSONObject itemObj = sceneItems.getJSONObject(i);
                if (itemObj.getString("sourceName").equals(sourceName)) {
                    itemId = itemObj.getInt("sceneItemId");
                    break;
                }
            }

            if (itemId != null) {
                data = new JSONObject();
                data.put("sceneName", sceneName);
                data.put("sceneItemId", itemId);

                response = sendRequest("RemoveSceneItem", data).get(5, TimeUnit.SECONDS);

                // Log full response for debugging
                LOGGER.info("RemoveSceneItem Response: " + response.toString());

                // Check request status, but don't throw an exception if removal fails
                JSONObject requestStatus = response.getJSONObject("d").getJSONObject("requestStatus");
                if (!requestStatus.getBoolean("result")) {
                    String comment = requestStatus.has("comment") ?
                            requestStatus.getString("comment") : "Unknown error";
                    LOGGER.warning("Failed to remove source: " + comment);
                    // Log the error but continue execution
                }
            } else {
                LOGGER.info("Source not found in scene: " + sourceName);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error in removeSource", e);
            // Deliberately not throwing the exception to prevent stream setup failure
        }
    }

    private void createMediaSource(String sourceName, String url) throws Exception {
        try {
            LOGGER.info("Attempting to create media source");
            LOGGER.info("Source Name: " + sourceName);
            LOGGER.info("Stream URL: " + url);
            LOGGER.info("Target Scene Name: " + sceneName);

            // Create the source settings
            JSONObject sourceSettings = new JSONObject();
            sourceSettings.put("input", url);
            sourceSettings.put("is_local_file", false);
            sourceSettings.put("restart_on_activate", true);
            sourceSettings.put("buffering_mb", 5);
            sourceSettings.put("reconnect_delay_sec", 1);

            // Create input source
            JSONObject data = new JSONObject();
            data.put("inputName", sourceName);
            data.put("inputKind", "ffmpeg_source");
            data.put("inputSettings", sourceSettings);

            // Add scene details
            data.put("sceneName", sceneName);

            LOGGER.info("Full request data: " + data.toString());

            // Send request to create input
            JSONObject response = sendRequest("CreateInput", data).get(5, TimeUnit.SECONDS);

            // Log full response for debugging
            LOGGER.info("Full CreateInput response: " + response.toString());

            // Handle different response formats - OBS might return data in different structures
            boolean success = false;
            try {
                // First try the expected format with 'd' key
                if (response.has("d")) {
                    JSONObject responseDetails = response.getJSONObject("d");
                    JSONObject requestStatus = responseDetails.getJSONObject("requestStatus");
                    success = requestStatus.getBoolean("result");
                }
                // Then try direct format
                else if (response.has("requestStatus")) {
                    JSONObject requestStatus = response.getJSONObject("requestStatus");
                    success = requestStatus.getBoolean("result");
                }
                // Check for any code that indicates success (code 100)
                else if (response.has("code")) {
                    success = (response.getInt("code") == 100);
                }
            } catch (Exception e) {
                LOGGER.warning("Error parsing response structure: " + e.getMessage());
                // If we're here, we'll assume it failed
                success = false;
            }

            if (!success) {
                // Try to extract error details, but don't fail if we can't
                String errorMessage = "Unknown error";
                try {
                    if (response.has("d") && response.getJSONObject("d").has("requestStatus")) {
                        JSONObject requestStatus = response.getJSONObject("d").getJSONObject("requestStatus");
                        if (requestStatus.has("comment")) {
                            errorMessage = requestStatus.getString("comment");
                        } else if (requestStatus.has("code")) {
                            errorMessage = "Error code: " + requestStatus.getInt("code");
                        }
                    } else if (response.has("requestStatus")) {
                        JSONObject requestStatus = response.getJSONObject("requestStatus");
                        if (requestStatus.has("comment")) {
                            errorMessage = requestStatus.getString("comment");
                        } else if (requestStatus.has("code")) {
                            errorMessage = "Error code: " + requestStatus.getInt("code");
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warning("Error extracting error details: " + e.getMessage());
                }

                throw new Exception("Failed to create input source: " + errorMessage);
            }

            LOGGER.info("Successfully created media source: " + sourceName);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Comprehensive error in createMediaSource", e);
            throw e;
        }
    }

    private void addSourceToScene(String sourceName) throws Exception {
        try {
            JSONObject data = new JSONObject();
            data.put("sceneName", sceneName);
            data.put("sourceName", sourceName);

            JSONObject response = sendRequest("CreateSceneItem", data).get(5, TimeUnit.SECONDS);

            // Log full response for debugging
            LOGGER.info("CreateSceneItem Response: " + response.toString());

            // Extract and verify request status
            JSONObject responseDetails = response.getJSONObject("d");
            JSONObject requestStatus = responseDetails.getJSONObject("requestStatus");

            // Check result
            if (!requestStatus.getBoolean("result")) {
                int errorCode = requestStatus.getInt("code");
                String errorComment = requestStatus.has("comment") ?
                        requestStatus.getString("comment") : "No additional error details";

                LOGGER.warning("Failed to add source to scene");
                LOGGER.warning("Error Code: " + errorCode);
                LOGGER.warning("Error Comment: " + errorComment);

                // Not throwing an exception here as it might not be critical
            } else {
                LOGGER.info("Successfully added source to scene");
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error adding source to scene", e);
            // Again, not throwing an exception to prevent breaking the stream setup
        }
    }

    private void positionSource(int sceneItemId) throws Exception {
        // Create transform object
        JSONObject transform = new JSONObject();
        transform.put("alignment", 5); // Center alignment
        transform.put("x", 0.0);
        transform.put("y", 0.0);
        transform.put("width", 1920);
        transform.put("height", 1080);

        JSONObject data = new JSONObject();
        data.put("sceneName", sceneName);
        data.put("sceneItemId", sceneItemId);
        data.put("sceneItemTransform", transform);

        JSONObject response = sendRequest("SetSceneItemTransform", data).get(5, TimeUnit.SECONDS);

        if (!response.getBoolean("requestStatus")) {
            throw new Exception("Failed to position source: " + response.getJSONObject("responseData").getString("comment"));
        }
    }

    public boolean startVirtualCam() {
        if (!isConnected || !isAuthenticated) {
            LOGGER.warning("Not connected to OBS or not authenticated");
            return false;
        }

        try {
            // Check if VirtualCam is already running
            JSONObject response = sendRequest("GetVirtualCamStatus", null).get(5, TimeUnit.SECONDS);
            LOGGER.info("VirtualCam status response: " + response.toString());

            boolean isActive = false;

            // Try to extract outputActive from various response formats
            try {
                if (response.has("d") && response.getJSONObject("d").has("responseData")) {
                    isActive = response.getJSONObject("d").getJSONObject("responseData").getBoolean("outputActive");
                } else if (response.has("responseData")) {
                    isActive = response.getJSONObject("responseData").getBoolean("outputActive");
                }
            } catch (Exception e) {
                LOGGER.warning("Error parsing VirtualCam status: " + e.getMessage());
            }

            if (isActive) {
                LOGGER.info("VirtualCam is already running");
                return true;
            }

            // Start VirtualCam
            try {
                JSONObject startResponse = sendRequest("StartVirtualCam", null).get(5, TimeUnit.SECONDS);
                LOGGER.info("StartVirtualCam response: " + startResponse.toString());

                // Check response for success in various formats
                boolean success = false;
                try {
                    if (startResponse.has("d") && startResponse.getJSONObject("d").has("requestStatus")) {
                        success = startResponse.getJSONObject("d").getJSONObject("requestStatus").getBoolean("result");
                    } else if (startResponse.has("requestStatus")) {
                        success = startResponse.getJSONObject("requestStatus").getBoolean("result");
                    } else {
                        // Default to success if we can't determine failure
                        success = true;
                    }
                } catch (Exception e) {
                    LOGGER.warning("Error parsing StartVirtualCam response: " + e.getMessage());
                    // Default to success if we can't parse
                    success = true;
                }

                if (success) {
                    LOGGER.info("VirtualCam started successfully");
                    return true;
                } else {
                    // Try to extract error message
                    String errorMessage = "Unknown error";
                    try {
                        if (startResponse.has("d") && startResponse.getJSONObject("d").has("requestStatus")) {
                            JSONObject status = startResponse.getJSONObject("d").getJSONObject("requestStatus");
                            if (status.has("comment")) {
                                errorMessage = status.getString("comment");
                            }
                        } else if (startResponse.has("requestStatus") && startResponse.getJSONObject("requestStatus").has("comment")) {
                            errorMessage = startResponse.getJSONObject("requestStatus").getString("comment");
                        }
                    } catch (Exception e) {
                        // Ignore extraction errors
                    }

                    LOGGER.warning("Failed to start VirtualCam: " + errorMessage);

                    // Return true anyway - we don't want to fail the whole stream setup just because VirtualCam failed
                    return true;
                }
            } catch (Exception e) {
                LOGGER.warning("Error starting VirtualCam: " + e.getMessage());
                // Return true anyway - we don't want to fail the whole stream setup just because VirtualCam failed
                return true;
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error starting VirtualCam", e);
            // Return true anyway - we don't want to fail the whole stream setup just because VirtualCam failed
            return true;
        }
    }

    public int runCompleteCleanup() {
        if (!isConnected || !isAuthenticated) {
            LOGGER.warning("Not connected to OBS or not authenticated - can't clean up sources");
            return 0;
        }

        int totalRemoved = 0;

        try {
            LOGGER.info("Starting complete cleanup of all IPTV sources");

            // First get the list of all inputs
            JSONObject getInputsData = new JSONObject();
            JSONObject getInputsResponse = sendRequest("GetInputList", getInputsData).get(5, TimeUnit.SECONDS);

            if (!isRequestSuccessful(getInputsResponse)) {
                LOGGER.warning("Failed to get input list for cleanup");
                return 0;
            }

            // Get the inputs array
            JSONArray inputs;
            if (getInputsResponse.has("d") && getInputsResponse.getJSONObject("d").has("responseData")) {
                inputs = getInputsResponse.getJSONObject("d").getJSONObject("responseData").getJSONArray("inputs");
            } else if (getInputsResponse.has("responseData")) {
                inputs = getInputsResponse.getJSONObject("responseData").getJSONArray("inputs");
            } else {
                LOGGER.warning("Cannot find inputs in response: " + getInputsResponse.toString());
                return 0;
            }

            // Create a list of source names to remove (matching the IPTV-Source prefix pattern)
            List<String> sourcesToRemove = new ArrayList<>();
            for (int i = 0; i < inputs.length(); i++) {
                JSONObject input = inputs.getJSONObject(i);
                String inputName = input.getString("inputName");

                // If it starts with our prefix
                if (inputName.startsWith("IPTV-Source-")) {
                    sourcesToRemove.add(inputName);
                    LOGGER.info("Marked source for removal: " + inputName);
                }
            }

            // Now process the scene items to remove them from scenes first
            JSONObject sceneData = new JSONObject();
            sceneData.put("sceneName", sceneName);
            JSONObject sceneResponse = sendRequest("GetSceneItemList", sceneData).get(5, TimeUnit.SECONDS);

            if (isRequestSuccessful(sceneResponse)) {
                JSONArray sceneItems;
                if (sceneResponse.has("d") && sceneResponse.getJSONObject("d").has("responseData")) {
                    sceneItems = sceneResponse.getJSONObject("d").getJSONObject("responseData").getJSONArray("sceneItems");
                } else if (sceneResponse.has("responseData")) {
                    sceneItems = sceneResponse.getJSONObject("responseData").getJSONArray("sceneItems");
                } else {
                    LOGGER.warning("Cannot find scene items in response");
                    sceneItems = new JSONArray();
                }

                // First remove items from the scene
                for (int i = 0; i < sceneItems.length(); i++) {
                    JSONObject item = sceneItems.getJSONObject(i);
                    String itemName = item.getString("sourceName");

                    if (sourcesToRemove.contains(itemName)) {
                        int itemId = item.getInt("sceneItemId");

                        // Remove from scene
                        JSONObject removeData = new JSONObject();
                        removeData.put("sceneName", sceneName);
                        removeData.put("sceneItemId", itemId);

                        JSONObject removeResponse = sendRequest("RemoveSceneItem", removeData).get(5, TimeUnit.SECONDS);
                        if (isRequestSuccessful(removeResponse)) {
                            LOGGER.info("Removed scene item: " + itemName);
                        } else {
                            LOGGER.warning("Failed to remove scene item: " + itemName);
                        }
                    }
                }
            }

            // Finally, remove each input
            for (String sourceName : sourcesToRemove) {
                JSONObject removeData = new JSONObject();
                removeData.put("inputName", sourceName);

                JSONObject removeResponse = sendRequest("RemoveInput", removeData).get(5, TimeUnit.SECONDS);
                if (isRequestSuccessful(removeResponse)) {
                    LOGGER.info("Removed input source: " + sourceName);
                    totalRemoved++;
                } else {
                    LOGGER.warning("Failed to remove input: " + sourceName);
                }

                // Add a small delay between operations
                Thread.sleep(100);
            }

            LOGGER.info("Cleanup complete. Removed " + totalRemoved + " sources.");
            return totalRemoved;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error in runCompleteCleanup", e);
            return totalRemoved;
        }
    }

    public boolean stopVirtualCam() {
        if (!isConnected || !isAuthenticated) {
            LOGGER.warning("Not connected to OBS or not authenticated");
            return false;
        }

        try {
            // Check if VirtualCam is running
            JSONObject response = sendRequest("GetVirtualCamStatus", null).get(5, TimeUnit.SECONDS);

            if (!response.getBoolean("requestStatus")) {
                throw new Exception("Failed to get VirtualCam status: " + response.getJSONObject("responseData").getString("comment"));
            }

            boolean isActive = response.getJSONObject("responseData").getBoolean("outputActive");
            if (!isActive) {
                LOGGER.info("VirtualCam is not running");
                return true;
            }

            // Stop VirtualCam
            response = sendRequest("StopVirtualCam", null).get(5, TimeUnit.SECONDS);

            if (!response.getBoolean("requestStatus")) {
                throw new Exception("Failed to stop VirtualCam: " + response.getJSONObject("responseData").getString("comment"));
            }

            LOGGER.info("VirtualCam stopped successfully");
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error stopping VirtualCam", e);
            return false;
        }
    }

    public void disconnect() {
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Closing connection");
            webSocket = null;
        }
        isConnected = false;
        isAuthenticated = false;
        connectionInProgress = false;
        pendingRequests.clear();
    }

    // WebSocket.Listener implementation

    @Override
    public void onOpen(WebSocket webSocket) {
        LOGGER.info("WebSocket connection opened");
        isConnected = true;
        connectionInProgress = true;  // Set this flag here to ensure we process the Hello message
        WebSocket.Listener.super.onOpen(webSocket);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        if (!last) {
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        String message = data.toString();

        try {
            JSONObject response = new JSONObject(message);
            int opCode = response.optInt("op", -1);

            if (opCode == -1) {
                LOGGER.warning("Received message with no op code: " + message);
                return WebSocket.Listener.super.onText(webSocket, data, last);
            }

            LOGGER.fine("Received OBS WebSocket message with op code: " + opCode);

            switch (opCode) {
                case 0: // Hello message
                    LOGGER.info("Received Hello message from OBS WebSocket");
                    // Store the webSocket reference and authenticate
                    this.webSocket = webSocket;
                    boolean authSuccess = authenticate(response);
                    LOGGER.info("Authentication process initiated: " + (authSuccess ? "Success" : "Failed"));
                    break;

                case 2: // Identified message
                    LOGGER.info("Successfully identified with OBS WebSocket");
                    isAuthenticated = true;
                    connectionInProgress = false;
                    break;

                case 3: // Authentication failure
                    String error = response.has("d") && response.getJSONObject("d").has("error")
                            ? response.getJSONObject("d").getString("error")
                            : "Unknown error";

                    LOGGER.severe("OBS WebSocket authentication failed: " + error);
                    isAuthenticated = false;
                    connectionInProgress = false;
                    break;

                case 7: // RequestResponse message
                    handleRequestResponse(response);
                    break;

                case 5: // Event message
                    handleEvent(response);
                    break;

                default:
                    LOGGER.fine("Received unhandled op code: " + opCode);
                    break;
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing WebSocket message: " + message, e);
        }

        return WebSocket.Listener.super.onText(webSocket, data, last);
    }

    private void handleRequestResponse(JSONObject response) {
        try {
            LOGGER.info("Raw request response: " + response.toString());

            JSONObject d = response.getJSONObject("d");
            String requestId = d.getString("requestId");

            // Extensive logging
            LOGGER.fine("Request response details: " + d.toString());

            try {
                int id = Integer.parseInt(requestId);
                CompletableFuture<JSONObject> future = pendingRequests.remove(id);

                if (future != null) {
                    future.complete(d);
                }
            } catch (NumberFormatException e) {
                LOGGER.warning("Invalid request ID: " + requestId);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing request response", e);
        }
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        LOGGER.info("WebSocket closed: " + statusCode + " " + reason);
        isConnected = false;
        isAuthenticated = false;
        connectionInProgress = false;

        // Complete all pending requests with an error
        for (CompletableFuture<JSONObject> future : pendingRequests.values()) {
            JSONObject error = new JSONObject();
            error.put("requestStatus", false);
            error.put("comment", "WebSocket connection closed: " + reason);
            future.complete(error);
        }
        pendingRequests.clear();

        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        LOGGER.log(Level.SEVERE, "WebSocket error", error);
        connectionInProgress = false;
        WebSocket.Listener.super.onError(webSocket, error);
    }

    private void handleEvent(JSONObject event) {
        try {
            JSONObject eventData = event.getJSONObject("d");
            String eventType = eventData.getString("eventType");

            switch (eventType) {
                case "StreamStateChanged":
                    String streamState = eventData.getJSONObject("eventData").getString("outputState");
                    LOGGER.info("OBS stream state changed: " + streamState);
                    break;

                case "VirtualcamStateChanged":
                    String vcamState = eventData.getJSONObject("eventData").getString("outputState");
                    LOGGER.info("VirtualCam state changed: " + vcamState);
                    break;

                case "InputCreated":
                    String inputName = eventData.getJSONObject("eventData").getString("inputName");
                    LOGGER.info("Input created: " + inputName);
                    break;

                case "InputRemoved":
                    inputName = eventData.getJSONObject("eventData").getString("inputName");
                    LOGGER.info("Input removed: " + inputName);
                    break;

                case "SceneItemCreated":
                    int sceneItemId = eventData.getJSONObject("eventData").getInt("sceneItemId");
                    String sceneName = eventData.getJSONObject("eventData").getString("sceneName");
                    LOGGER.info("Scene item added to " + sceneName + " with ID: " + sceneItemId);
                    break;

                case "SceneItemRemoved":
                    sceneItemId = eventData.getJSONObject("eventData").getInt("sceneItemId");
                    sceneName = eventData.getJSONObject("eventData").getString("sceneName");
                    LOGGER.info("Scene item removed from " + sceneName + " with ID: " + sceneItemId);
                    break;

                default:
                    // Ignore other events
                    break;
            }
        } catch (Exception e) {
            LOGGER.warning("Error processing event: " + e.getMessage());
        }
    }

    public int cleanupSources(String sourceNamePrefix) {
        int removedCount = 0;

        if (!isConnected || !isAuthenticated) {
            LOGGER.warning("Not connected to OBS or not authenticated - can't clean up sources");
            return 0;
        }

        try {
            LOGGER.info("Starting cleanup of sources with prefix: " + sourceNamePrefix);

            // First get the list of all inputs
            JSONObject getInputsData = new JSONObject();
            JSONObject getInputsResponse = sendRequest("GetInputList", getInputsData).get(5, TimeUnit.SECONDS);

            if (!isRequestSuccessful(getInputsResponse)) {
                LOGGER.warning("Failed to get input list for cleanup");
                return 0;
            }

            // Get the inputs array
            JSONArray inputs;
            if (getInputsResponse.has("d") && getInputsResponse.getJSONObject("d").has("responseData")) {
                inputs = getInputsResponse.getJSONObject("d").getJSONObject("responseData").getJSONArray("inputs");
            } else if (getInputsResponse.has("responseData")) {
                inputs = getInputsResponse.getJSONObject("responseData").getJSONArray("inputs");
            } else {
                LOGGER.warning("Cannot find inputs in response: " + getInputsResponse.toString());
                return 0;
            }

            // Create a list of source names to remove (matching the prefix pattern)
            List<String> sourcesToRemove = new ArrayList<>();
            for (int i = 0; i < inputs.length(); i++) {
                JSONObject input = inputs.getJSONObject(i);
                String inputName = input.getString("inputName");

                // If it matches our prefix or is a timestamped variant
                if (inputName.equals(sourceNamePrefix) ||
                        (inputName.startsWith(sourceNamePrefix + "_") &&
                                inputName.substring(sourceNamePrefix.length() + 1).matches("\\d+"))) {

                    sourcesToRemove.add(inputName);
                    LOGGER.info("Marked source for removal: " + inputName);
                }
            }

            // Now process the scene items to remove them from scenes first
            JSONObject sceneData = new JSONObject();
            sceneData.put("sceneName", sceneName);
            JSONObject sceneResponse = sendRequest("GetSceneItemList", sceneData).get(5, TimeUnit.SECONDS);

            if (isRequestSuccessful(sceneResponse)) {
                JSONArray sceneItems;
                if (sceneResponse.has("d") && sceneResponse.getJSONObject("d").has("responseData")) {
                    sceneItems = sceneResponse.getJSONObject("d").getJSONObject("responseData").getJSONArray("sceneItems");
                } else if (sceneResponse.has("responseData")) {
                    sceneItems = sceneResponse.getJSONObject("responseData").getJSONArray("sceneItems");
                } else {
                    LOGGER.warning("Cannot find scene items in response");
                    sceneItems = new JSONArray();
                }

                // First remove items from the scene
                for (int i = 0; i < sceneItems.length(); i++) {
                    JSONObject item = sceneItems.getJSONObject(i);
                    String itemName = item.getString("sourceName");

                    if (sourcesToRemove.contains(itemName)) {
                        int itemId = item.getInt("sceneItemId");

                        // Remove from scene
                        JSONObject removeData = new JSONObject();
                        removeData.put("sceneName", sceneName);
                        removeData.put("sceneItemId", itemId);

                        JSONObject removeResponse = sendRequest("RemoveSceneItem", removeData).get(5, TimeUnit.SECONDS);
                        if (isRequestSuccessful(removeResponse)) {
                            LOGGER.info("Removed scene item: " + itemName);
                        } else {
                            LOGGER.warning("Failed to remove scene item: " + itemName);
                        }
                    }
                }
            }

            // Finally, remove each input
            for (String sourceName : sourcesToRemove) {
                JSONObject removeData = new JSONObject();
                removeData.put("inputName", sourceName);

                JSONObject removeResponse = sendRequest("RemoveInput", removeData).get(5, TimeUnit.SECONDS);
                if (isRequestSuccessful(removeResponse)) {
                    LOGGER.info("Removed input source: " + sourceName);
                    removedCount++;
                } else {
                    LOGGER.warning("Failed to remove input: " + sourceName);
                }

                // Add a small delay between operations
                Thread.sleep(100);
            }

            LOGGER.info("Cleanup complete. Removed " + removedCount + " sources.");
            return removedCount;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error in cleanupSources", e);
            return removedCount;
        }
    }
}