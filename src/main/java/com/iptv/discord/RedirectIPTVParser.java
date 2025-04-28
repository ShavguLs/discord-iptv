package com.iptv.discord;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class RedirectIPTVParser {
    private static final Logger LOGGER = Logger.getLogger(RedirectIPTVParser.class.getName());

    // Basic M3U tags
    private static final String EXTM3U_HEADER = "#EXTM3U";
    private static final String EXTINF_PREFIX = "#EXTINF:";

    // Additional tags that might be in some playlists
    private static final String EXTVLCOPT_PREFIX = "#EXTVLCOPT:";
    private static final String KODIPROP_PREFIX = "#KODIPROP:";

    // URL pattern with more protocols supported
    private static final Pattern URL_PATTERN = Pattern.compile(
            "^(https?|rtmp|rtsp|udp|rtp|mms|ftp|rtmpe|rtmps|rtmpt|rtmpte|rtmpts)://.*",
            Pattern.CASE_INSENSITIVE);

    // Pattern to extract channel name from EXTINF
    private static final Pattern CHANNEL_NAME_PATTERN = Pattern.compile(
            ".*,\\s*([^,]+)$");

    // For group titles extraction
    private static final Pattern GROUP_TITLE_PATTERN = Pattern.compile(
            "group-title=\"([^\"]+)\"");

    // Maximum number of redirects to follow
    private static final int MAX_REDIRECTS = 10;

    // Timeout settings
    private static final int CONNECTION_TIMEOUT = 15000; // 15 seconds
    private static final int READ_TIMEOUT = 15000; // 15 seconds

    // Enhanced stream fallback support
    private static final int CHECK_STREAM_TIMEOUT = 5000; // 5 seconds for quick stream checking
    private static final ExecutorService streamCheckExecutor = Executors.newFixedThreadPool(5);

    // Channel maps with enhanced fallback support
    private final Map<String, List<String>> channelUrlsMap = new HashMap<>();
    private final Map<String, String> primaryChannelUrlMap = new HashMap<>();
    private final Map<String, String> channelGroupMap = new HashMap<>();
    private final Map<String, Integer> channelFailureCount = new ConcurrentHashMap<>();

    // Cache for verified URLs
    private final Map<String, Boolean> verifiedUrlCache = new ConcurrentHashMap<>();

    private String playlistContent;
    private long lastPlaylistRefreshTime = 0;
    private static final long PLAYLIST_REFRESH_INTERVAL = 3600000; // 1 hour in milliseconds

    public void parsePlaylistFromUrl(String playlistUrl) throws IOException {
        LOGGER.info("Parsing playlist from URL: " + playlistUrl);

        // Check if we need to refresh the playlist based on time
        long currentTime = System.currentTimeMillis();
        if (playlistContent != null && !playlistContent.isEmpty() &&
                (currentTime - lastPlaylistRefreshTime < PLAYLIST_REFRESH_INTERVAL)) {
            LOGGER.info("Using cached playlist (age: " +
                    ((currentTime - lastPlaylistRefreshTime) / 1000) + " seconds)");
            return;
        }

        // Download the playlist content with enhanced error handling
        playlistContent = downloadWithRetryAndFallback(playlistUrl);
        lastPlaylistRefreshTime = currentTime;

        if (playlistContent != null && !playlistContent.isEmpty()) {
            LOGGER.info("Playlist loaded successfully, size: " + playlistContent.length() + " bytes");

            // Preview the content
            int previewLength = Math.min(playlistContent.length(), 500);
            LOGGER.info("Playlist content preview (first " + previewLength + " chars):\n" +
                    playlistContent.substring(0, previewLength));

            parsePlaylistContent();
        } else {
            LOGGER.severe("Failed to download playlist content");
            throw new IOException("Failed to download playlist content");
        }
    }

    private String downloadWithRetryAndFallback(String urlString) throws IOException {
        // Try multiple times with different fallback strategies
        List<IOException> exceptions = new ArrayList<>();

        // First try with normal download + redirects
        try {
            String content = downloadWithRedirects(urlString);
            if (content != null && !content.isEmpty()) {
                return content;
            }
        } catch (IOException e) {
            LOGGER.warning("Primary download method failed: " + e.getMessage());
            exceptions.add(e);
        }

        // Fallback: Try with different User-Agent strings
        String[] userAgents = {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.0 Safari/605.1.15",
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/92.0.4515.107 Safari/537.36"
        };

        for (String userAgent : userAgents) {
            try {
                String content = downloadWithUserAgent(urlString, userAgent);
                if (content != null && !content.isEmpty()) {
                    LOGGER.info("Successfully downloaded with alternative User-Agent: " + userAgent);
                    return content;
                }
            } catch (IOException e) {
                LOGGER.warning("Download with User-Agent '" + userAgent + "' failed: " + e.getMessage());
                exceptions.add(e);
            }
        }

        // If all downloads failed, throw a combined exception
        if (!exceptions.isEmpty()) {
            IOException finalException = new IOException("All download methods failed");
            for (IOException e : exceptions) {
                finalException.addSuppressed(e);
            }
            throw finalException;
        }

        return null;
    }

    private String downloadWithUserAgent(String urlString, String userAgent) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        // Set properties
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        connection.setConnectTimeout(CONNECTION_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);

        // Connect and check response
        connection.connect();
        int responseCode = connection.getResponseCode();

        if (responseCode == HttpURLConnection.HTTP_OK) {
            // Determine charset
            String contentType = connection.getContentType();
            String charset = StandardCharsets.UTF_8.name();

            if (contentType != null && contentType.contains("charset=")) {
                charset = contentType.substring(contentType.indexOf("charset=") + 8).trim();
            }

            // Read content
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), charset))) {
                StringBuilder content = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }

                return content.toString();
            } finally {
                connection.disconnect();
            }
        } else {
            connection.disconnect();
            throw new IOException("HTTP error: " + responseCode);
        }
    }

    private String downloadWithRedirects(String urlString) throws IOException {
        String currentUrl = urlString;
        int redirectCount = 0;

        while (redirectCount < MAX_REDIRECTS) {
            LOGGER.info("Attempting to download from: " + currentUrl);

            URL url = new URL(currentUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            // Important: Allow redirects explicitly
            connection.setInstanceFollowRedirects(true);

            // Set proper headers to avoid being blocked
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            connection.setConnectTimeout(CONNECTION_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);

            // Connect and check the response code
            try {
                connection.connect();
            } catch (SocketTimeoutException e) {
                LOGGER.warning("Connection timeout for URL: " + currentUrl);
                connection.disconnect();
                throw new IOException("Connection timeout: " + e.getMessage(), e);
            }

            int responseCode;
            try {
                responseCode = connection.getResponseCode();
                LOGGER.info("HTTP Response Code: " + responseCode);
            } catch (SocketTimeoutException e) {
                LOGGER.warning("Read timeout while getting response code for URL: " + currentUrl);
                connection.disconnect();
                throw new IOException("Read timeout: " + e.getMessage(), e);
            }

            // Check if it's a redirect
            if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == HttpURLConnection.HTTP_SEE_OTHER ||
                    responseCode == 307 || responseCode == 308) {

                // Get the redirect URL
                String newUrl = connection.getHeaderField("Location");

                // Validate the redirect URL
                if (newUrl == null || newUrl.trim().isEmpty()) {
                    LOGGER.warning("Empty redirect URL for: " + currentUrl);
                    connection.disconnect();
                    throw new IOException("Empty redirect URL");
                }

                // Check if redirect URL is relative
                if (!newUrl.startsWith("http")) {
                    if (newUrl.startsWith("/")) {
                        // Absolute path - construct with host
                        String protocol = url.getProtocol();
                        String host = url.getHost();
                        newUrl = protocol + "://" + host + newUrl;
                    } else {
                        // Relative path - construct with current path
                        String base = currentUrl.substring(0, currentUrl.lastIndexOf('/') + 1);
                        newUrl = base + newUrl;
                    }
                }

                LOGGER.info("Redirected to: " + newUrl);

                // Update current URL and continue
                currentUrl = newUrl;
                redirectCount++;

                // Close the connection before starting a new one
                connection.disconnect();

                continue;
            }

            // If we got here, it's not a redirect, check if successful
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // Try to determine charset from content type
                String contentType = connection.getContentType();
                String charset = StandardCharsets.UTF_8.name(); // Default to UTF-8

                if (contentType != null && contentType.contains("charset=")) {
                    charset = contentType.substring(contentType.indexOf("charset=") + 8).trim();
                }

                LOGGER.info("Using charset: " + charset);

                // Read the content
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), charset))) {
                    StringBuilder content = new StringBuilder();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        content.append(line).append("\n");
                    }

                    return content.toString();
                } catch (SocketTimeoutException e) {
                    LOGGER.warning("Read timeout while downloading content: " + currentUrl);
                    throw new IOException("Read timeout during content download: " + e.getMessage(), e);
                } finally {
                    connection.disconnect();
                }
            } else {
                // Not a success or redirect response
                LOGGER.warning("HTTP Error: " + responseCode);
                connection.disconnect();
                throw new IOException("Failed to access playlist URL. HTTP Error: " + responseCode);
            }
        }

        // Too many redirects
        LOGGER.severe("Too many redirects (max: " + MAX_REDIRECTS + ")");
        throw new IOException("Too many redirects");
    }

    public void parsePlaylistFromFile(String filePath) throws IOException {
        LOGGER.info("Parsing playlist from file: " + filePath);
        playlistContent = Files.readString(Path.of(filePath));
        LOGGER.info("Playlist loaded successfully, size: " + playlistContent.length() + " bytes");
        parsePlaylistContent();
    }

    private void parsePlaylistContent() {
        if (playlistContent == null || playlistContent.isEmpty()) {
            LOGGER.warning("Playlist content is empty");
            return;
        }

        // Check for HTML content which indicates an error or redirect issue
        if (playlistContent.trim().startsWith("<html") || playlistContent.trim().startsWith("<!DOCTYPE")) {
            LOGGER.severe("Received HTML content instead of M3U/M3U8 playlist. Possible access issue or wrong URL.");
            return;
        }

        // Check if valid format with flexibility
        boolean hasValidHeader = playlistContent.trim().startsWith(EXTM3U_HEADER);
        if (!hasValidHeader) {
            LOGGER.warning("Warning: Playlist doesn't start with #EXTM3U header - may be non-standard format");
        }

        // Clear previous data
        channelUrlsMap.clear();
        primaryChannelUrlMap.clear();
        channelGroupMap.clear();

        String[] lines = playlistContent.split("\n");

        LOGGER.info("Parsing " + lines.length + " lines from playlist");

        String currentChannelName = null;
        String currentGroupTitle = null;
        StringBuilder extinfLine = new StringBuilder();
        boolean processingExtinf = false;

        // Process each line
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            if (line.isEmpty()) continue;

            // Process EXTINF lines
            if (line.startsWith(EXTINF_PREFIX)) {
                extinfLine = new StringBuilder(line);
                processingExtinf = true;

                // Try to extract channel name
                currentChannelName = extractChannelName(line);

                // Try to extract group title
                currentGroupTitle = extractGroupTitle(line);

                LOGGER.fine("Found EXTINF at line " + i + ": Name=" + currentChannelName +
                        ", Group=" + currentGroupTitle);
            }
            // Handle multi-line EXTINF (some playlists format this way)
            else if (processingExtinf && line.startsWith("#") && !line.startsWith(EXTVLCOPT_PREFIX) &&
                    !line.startsWith(KODIPROP_PREFIX)) {
                extinfLine.append(" ").append(line);

                // Re-extract after appending
                currentChannelName = extractChannelName(extinfLine.toString());
                currentGroupTitle = extractGroupTitle(extinfLine.toString());
            }
            // Handle URL lines
            else if (isValidStreamUrl(line)) {
                processingExtinf = false;

                if (currentChannelName == null || currentChannelName.isEmpty()) {
                    // Try to derive name from URL if not available
                    currentChannelName = "Channel-" + (channelUrlsMap.size() + 1);
                }

                // Add to channel maps
                String normalizedName = currentChannelName.toLowerCase();

                // Store the group if available
                if (currentGroupTitle != null && !currentGroupTitle.isEmpty()) {
                    channelGroupMap.put(normalizedName, currentGroupTitle);
                }

                // Store the URL with fallback support
                List<String> urls = channelUrlsMap.computeIfAbsent(normalizedName, k -> new ArrayList<>());
                urls.add(line);

                // Also store as primary URL if it's the first one
                if (!primaryChannelUrlMap.containsKey(normalizedName)) {
                    primaryChannelUrlMap.put(normalizedName, line);
                }

                LOGGER.fine("Added channel at line " + i + ": " + currentChannelName);

                // Reset for next channel
                currentChannelName = null;
                currentGroupTitle = null;
            }
            // Handle additional properties (might be used by some players)
            else if (line.startsWith(EXTVLCOPT_PREFIX) || line.startsWith(KODIPROP_PREFIX)) {
                // Skip these lines for now, could store them as metadata if needed
                continue;
            }
        }

        LOGGER.info("Parsing complete. Found " + primaryChannelUrlMap.size() + " channels.");

        // Log a few channel names for debugging if available
        if (!primaryChannelUrlMap.isEmpty()) {
            LOGGER.info("Sample channel names:");
            int count = 0;
            for (String name : primaryChannelUrlMap.keySet()) {
                LOGGER.info(" - " + name);
                count++;
                if (count >= 5) break;
            }
        }

        // Pre-verify some common channels to improve startup time
        preVerifyPopularChannels();
    }

    /**
     * Pre-verify the most commonly used channels to speed up initial selection
     */
    private void preVerifyPopularChannels() {
        if (primaryChannelUrlMap.isEmpty()) return;

        List<String> channelNames = new ArrayList<>(primaryChannelUrlMap.keySet());
        // Only take up to 10 channels to avoid long startup times
        int channelsToVerify = Math.min(10, channelNames.size());

        LOGGER.info("Pre-verifying " + channelsToVerify + " popular channels");

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < channelsToVerify; i++) {
            String channelName = channelNames.get(i);
            String url = primaryChannelUrlMap.get(channelName);

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    verifyStreamUrl(url);
                } catch (Exception e) {
                    // Just log, don't fail the whole process
                    LOGGER.fine("Pre-verification failed for " + channelName + ": " + e.getMessage());
                }
            }, streamCheckExecutor);

            futures.add(future);
        }

        // Wait for all verifications to complete, but limit wait time
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.info("Pre-verification timed out, continuing anyway");
        }
    }

    /**
     * Gets a stream URL with fallback support - will try alternative streams if available
     */
    public String getStreamUrl(String channelName) {
        if (primaryChannelUrlMap.isEmpty()) {
            LOGGER.warning("Channel map is empty. Parse a playlist first.");
            return null;
        }

        // Normalize the name for case-insensitive lookup
        String normalizedName = channelName.toLowerCase();

        // Try exact match first
        String url = primaryChannelUrlMap.get(normalizedName);

        // If not found, try partial match
        if (url == null) {
            LOGGER.info("Exact match not found for '" + channelName + "', trying partial matches...");

            for (Map.Entry<String, String> entry : primaryChannelUrlMap.entrySet()) {
                if (entry.getKey().contains(normalizedName) || normalizedName.contains(entry.getKey())) {
                    LOGGER.info("Found partial match: '" + entry.getKey() + "' for query: '" + channelName + "'");
                    url = entry.getValue();
                    normalizedName = entry.getKey(); // Update name to the actual match
                    break;
                }
            }

            if (url == null) {
                LOGGER.warning("Channel not found: " + channelName);
                return null;
            }
        }

        // Check if this URL is known to be good
        if (isVerifiedStream(url)) {
            LOGGER.info("Using verified stream URL for " + channelName);
            return url;
        }

        // Check if this channel has a history of failures
        int failures = channelFailureCount.getOrDefault(normalizedName, 0);
        if (failures > 0) {
            LOGGER.info("Channel " + channelName + " has " + failures + " previous failures");

            // Try to find alternative URLs for this channel
            List<String> alternativeUrls = getAlternativeUrls(normalizedName);
            if (!alternativeUrls.isEmpty()) {
                // Try to verify these alternatives
                for (String altUrl : alternativeUrls) {
                    if (verifyStreamUrl(altUrl)) {
                        LOGGER.info("Found working alternative URL for " + channelName);
                        return altUrl;
                    }
                }
            }
        }

        // Return the original URL as fallback
        return url;
    }

    /**
     * Mark a stream URL as failed for future reference
     */
    public void markStreamAsFailed(String channelName) {
        if (channelName == null) return;

        String normalizedName = channelName.toLowerCase();

        // Increment failure count for this channel
        AtomicInteger count = new AtomicInteger(channelFailureCount.getOrDefault(normalizedName, 0));
        channelFailureCount.put(normalizedName, count.incrementAndGet());

        LOGGER.info("Marked channel as failed: " + channelName + " (count: " + count.get() + ")");
    }

    /**
     * Get a list of alternative URLs for a channel
     */
    public List<String> getAlternativeUrls(String channelName) {
        if (channelName == null || channelUrlsMap.isEmpty()) return Collections.emptyList();

        String normalizedName = channelName.toLowerCase();
        List<String> urls = channelUrlsMap.get(normalizedName);

        if (urls == null || urls.size() <= 1) {
            // Try to find similar channels in the same group
            String group = channelGroupMap.get(normalizedName);

            if (group != null && !group.isEmpty()) {
                List<String> alternatives = new ArrayList<>();

                // Look for other channels in the same group with "backup" or similar in the name
                for (Map.Entry<String, String> entry : channelGroupMap.entrySet()) {
                    if (!entry.getKey().equals(normalizedName) &&
                            entry.getValue().equals(group) &&
                            (entry.getKey().contains(normalizedName) ||
                                    entry.getKey().contains("backup") ||
                                    entry.getKey().contains("alternative"))) {

                        String alternativeUrl = primaryChannelUrlMap.get(entry.getKey());
                        if (alternativeUrl != null) {
                            alternatives.add(alternativeUrl);
                        }
                    }
                }

                return alternatives;
            }

            return Collections.emptyList();
        }

        // Remove the primary URL to only return alternatives
        List<String> alternatives = new ArrayList<>(urls);
        String primaryUrl = primaryChannelUrlMap.get(normalizedName);
        if (primaryUrl != null) {
            alternatives.remove(primaryUrl);
        }

        return alternatives;
    }

    /**
     * Check if a stream URL is verified as working
     */
    private boolean isVerifiedStream(String url) {
        return verifiedUrlCache.getOrDefault(url, false);
    }

    /**
     * Verify if a stream URL is valid by attempting to connect to it
     */
    private boolean verifyStreamUrl(String url) {
        // Check cache first
        if (verifiedUrlCache.containsKey(url)) {
            return verifiedUrlCache.get(url);
        }

        // Don't try to verify non-HTTP URLs (like rtmp://)
        if (!url.startsWith("http")) {
            // Assume it works
            verifiedUrlCache.put(url, true);
            return true;
        }

        boolean isValid = false;

        try {
            LOGGER.fine("Verifying stream URL: " + url);

            URL streamUrl = new URL(url);
            HttpURLConnection connection = (HttpURLConnection) streamUrl.openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(CHECK_STREAM_TIMEOUT);
            connection.setReadTimeout(CHECK_STREAM_TIMEOUT);
            connection.setRequestProperty("User-Agent", "VLC/3.0.16 LibVLC/3.0.16");

            int responseCode = connection.getResponseCode();

            // Consider it valid if we get a successful response
            isValid = (responseCode >= 200 && responseCode < 300) ||
                    responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == HttpURLConnection.HTTP_MOVED_PERM;

            connection.disconnect();

            LOGGER.fine("Stream URL " + url + " verification result: " + isValid +
                    " (response code: " + responseCode + ")");

        } catch (Exception e) {
            LOGGER.fine("Failed to verify stream URL: " + url + " - " + e.getMessage());
            isValid = false;
        }

        // Store in cache
        verifiedUrlCache.put(url, isValid);
        return isValid;
    }

    private boolean isValidStreamUrl(String line) {
        if (URL_PATTERN.matcher(line).matches()) {
            return true;
        }

        // Some playlists might use relative URLs (though less common)
        if (line.endsWith(".m3u8") || line.endsWith(".ts") ||
                line.endsWith(".mp4") || line.endsWith(".mpd")) {
            return true;
        }

        return false;
    }

    private String extractChannelName(String extinfLine) {
        // Most common format is: #EXTINF:-1 [...attributes...],Channel Name
        Matcher matcher = CHANNEL_NAME_PATTERN.matcher(extinfLine);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        // Try tvg-name attribute as fallback
        Pattern tvgNamePattern = Pattern.compile("tvg-name=\"([^\"]+)\"");
        matcher = tvgNamePattern.matcher(extinfLine);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        // If no name found, generate a placeholder
        return "Channel-" + Math.abs(extinfLine.hashCode() % 10000);
    }

    private String extractGroupTitle(String extinfLine) {
        Matcher matcher = GROUP_TITLE_PATTERN.matcher(extinfLine);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    /**
     * Get alternative stream URL for a channel when the primary URL fails
     */
    public String getFallbackStreamUrl(String channelName) {
        if (channelName == null) return null;

        String normalizedName = channelName.toLowerCase();
        List<String> alternatives = getAlternativeUrls(normalizedName);

        if (alternatives.isEmpty()) {
            return null;
        }

        // Try each alternative
        for (String altUrl : alternatives) {
            if (verifyStreamUrl(altUrl)) {
                LOGGER.info("Using fallback URL for " + channelName);
                return altUrl;
            }
        }

        // If none verified, just return the first alternative
        return alternatives.get(0);
    }

    public List<String> getAvailableChannels() {
        return new ArrayList<>(primaryChannelUrlMap.keySet());
    }

    public int getChannelCount() {
        return primaryChannelUrlMap.size();
    }

    /**
     * Get the channel group for a specific channel
     */
    public String getChannelGroup(String channelName) {
        if (channelName == null) return null;
        return channelGroupMap.get(channelName.toLowerCase());
    }

    /**
     * Find other channels in the same group
     */
    public List<String> getSimilarChannels(String channelName) {
        if (channelName == null) return Collections.emptyList();

        String normalizedName = channelName.toLowerCase();
        String group = channelGroupMap.get(normalizedName);

        if (group == null || group.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> similarChannels = new ArrayList<>();
        for (Map.Entry<String, String> entry : channelGroupMap.entrySet()) {
            if (!entry.getKey().equals(normalizedName) && entry.getValue().equals(group)) {
                similarChannels.add(entry.getKey());
            }
        }

        return similarChannels;
    }

    public String getDebugInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("--- IPTV Parser Debug Info ---\n");
        sb.append("Total channels: ").append(primaryChannelUrlMap.size()).append("\n\n");

        if (primaryChannelUrlMap.isEmpty()) {
            if (playlistContent != null && !playlistContent.isEmpty()) {
                sb.append("Playlist content exists but no channels were parsed.\n");
                sb.append("Content snippet (first 100 chars):\n");
                sb.append(playlistContent.substring(0, Math.min(playlistContent.length(), 100)));
                sb.append("\n...\n");
            } else {
                sb.append("Playlist content is empty or not loaded.\n");
            }
            return sb.toString();
        }

        // Sample channels
        sb.append("Sample channels (max 10):\n");
        int count = 0;
        for (Map.Entry<String, String> entry : primaryChannelUrlMap.entrySet()) {
            sb.append(count + 1).append(". ").append(entry.getKey());

            // Add group info if available
            String group = channelGroupMap.get(entry.getKey());
            if (group != null && !group.isEmpty()) {
                sb.append(" [Group: ").append(group).append("]");
            }

            sb.append("\n   URL: ").append(entry.getValue()).append("\n");

            // Add alternative URLs if available
            List<String> alternatives = getAlternativeUrls(entry.getKey());
            if (!alternatives.isEmpty()) {
                sb.append("   Alternatives: ").append(alternatives.size()).append("\n");
            }

            count++;
            if (count >= 10) break;
        }

        // Add verification stats
        sb.append("\nVerified URLs: ").append(verifiedUrlCache.size()).append("\n");
        int workingCount = 0;
        for (boolean isWorking : verifiedUrlCache.values()) {
            if (isWorking) workingCount++;
        }
        sb.append("Working URLs: ").append(workingCount).append("\n");
        sb.append("Failed URLs: ").append(verifiedUrlCache.size() - workingCount).append("\n");

        return sb.toString();
    }

    /**
     * Cleanup method to release resources when done
     */
    public void shutdown() {
        // Shutdown executor
        streamCheckExecutor.shutdown();
        try {
            if (!streamCheckExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                streamCheckExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            streamCheckExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}