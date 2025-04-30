package com.iptv.discord;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.SocketTimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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

    private final Map<String, List<String>> searchResultsCache = new ConcurrentHashMap<>();
    private final Map<String, Long> searchCacheTimestamps = new ConcurrentHashMap<>();
    private static final long SEARCH_CACHE_TTL = 300000;

    private final Map<String, Long> cacheTimestamps = new ConcurrentHashMap<>();

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

        // Fast check for content validity
        if (playlistContent.trim().startsWith("<html") || playlistContent.trim().startsWith("<!DOCTYPE")) {
            LOGGER.severe("Received HTML content instead of M3U/M3U8 playlist. Possible access issue or wrong URL.");
            return;
        }

        // Measure parsing performance
        long startTime = System.currentTimeMillis();

        // Clear previous data
        channelUrlsMap.clear();
        primaryChannelUrlMap.clear();
        channelGroupMap.clear();
        verifiedUrlCache.clear();

        // Use BufferedReader for more efficient line-by-line processing
        try (BufferedReader reader = new BufferedReader(new StringReader(playlistContent))) {
            String line;
            String currentChannelName = null;
            String currentGroupTitle = null;
            StringBuilder extinfLine = new StringBuilder();
            boolean processingExtinf = false;
            int lineCount = 0;
            int channelCount = 0;

            while ((line = reader.readLine()) != null) {
                lineCount++;
                line = line.trim();
                if (line.isEmpty()) continue;

                // Process EXTINF lines
                if (line.startsWith(EXTINF_PREFIX)) {
                    extinfLine = new StringBuilder(line);
                    processingExtinf = true;

                    // Extract information
                    currentChannelName = extractChannelName(line);
                    currentGroupTitle = extractGroupTitle(line);

                    if (lineCount % 5000 == 0) {
                        LOGGER.info("Parsing progress: " + lineCount + " lines...");
                    }
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
                    List<String> urls = channelUrlsMap.computeIfAbsent(normalizedName, k -> new ArrayList<>(2)); // Most channels only have 1-2 URLs
                    urls.add(line);

                    // Also store as primary URL if it's the first one
                    if (!primaryChannelUrlMap.containsKey(normalizedName)) {
                        primaryChannelUrlMap.put(normalizedName, line);
                        channelCount++;

                        if (channelCount % 1000 == 0) {
                            LOGGER.info("Found " + channelCount + " channels so far...");
                        }
                    }

                    // Reset for next channel
                    currentChannelName = null;
                    currentGroupTitle = null;
                }
            }

            long endTime = System.currentTimeMillis();

            LOGGER.info("Parsing complete. Found " + primaryChannelUrlMap.size() +
                    " channels in " + (endTime - startTime) + "ms");
            clearSearchCache();
            asyncPreVerifyPopularChannels();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error parsing playlist content", e);
        }
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
            cacheTimestamps.put(url, System.currentTimeMillis());
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

        // Store in cache with timestamp
        verifiedUrlCache.put(url, isValid);
        cacheTimestamps.put(url, System.currentTimeMillis());
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

    public List<String> searchChannels(String query, String category) {
        if (primaryChannelUrlMap.isEmpty()) {
            LOGGER.warning("Channel map is empty. Parse a playlist first.");
            return Collections.emptyList();
        }

        // Create a cache key based on query and category
        String cacheKey = (query + "_" + (category != null ? category : "")).toLowerCase();

        // Check if we have a cached result that's still valid
        if (searchResultsCache.containsKey(cacheKey)) {
            long cacheTime = searchCacheTimestamps.getOrDefault(cacheKey, 0L);
            if (System.currentTimeMillis() - cacheTime < SEARCH_CACHE_TTL) {
                LOGGER.fine("Using cached search results for: " + cacheKey);
                return new ArrayList<>(searchResultsCache.get(cacheKey)); // Return a copy to prevent modification
            }
        }

        // Proceed with search as before
        String normalizedQuery = query.toLowerCase().trim();
        List<String> results = new ArrayList<>();
        boolean categoryOnlySearch = normalizedQuery.isEmpty() && category != null && !category.isEmpty();

        // Optimize search for performance when dealing with large playlists
        if (primaryChannelUrlMap.size() > 500) {
            // For large playlists, use parallel processing
            results = primaryChannelUrlMap.keySet().parallelStream()
                    .filter(channelName -> {
                        if (category != null && !category.isEmpty()) {
                            String channelGroup = channelGroupMap.get(channelName.toLowerCase());
                            if (channelGroup == null || !channelGroup.toLowerCase().contains(category.toLowerCase())) {
                                return false;
                            }
                        }

                        if (categoryOnlySearch) {
                            return true;
                        }

                        return channelName.toLowerCase().contains(normalizedQuery);
                    })
                    .collect(Collectors.toList());
        } else {
            // For smaller playlists, use the original approach
            for (String channelName : primaryChannelUrlMap.keySet()) {
                String channelGroup = channelGroupMap.get(channelName.toLowerCase());

                if (category != null && !category.isEmpty()) {
                    if (channelGroup == null || !channelGroup.toLowerCase().contains(category.toLowerCase())) {
                        continue;
                    }
                }

                if (categoryOnlySearch) {
                    results.add(channelName);
                    continue;
                }

                if (channelName.toLowerCase().contains(normalizedQuery)) {
                    results.add(channelName);
                }
            }
        }

        // Sort results (only if necessary - skip for category-only searches as they could be large)
        if (!categoryOnlySearch || results.size() < 1000) {
            results.sort(String.CASE_INSENSITIVE_ORDER);
        }

        // Cache the results
        searchResultsCache.put(cacheKey, new ArrayList<>(results));
        searchCacheTimestamps.put(cacheKey, System.currentTimeMillis());

        LOGGER.info("Search for \"" + query + "\" returned " + results.size() + " results");
        return results;
    }

    public Map<String, Integer> getChannelCategories() {
        if (channelGroupMap.isEmpty()) {
            LOGGER.warning("Channel group map is empty. Parse a playlist first.");
            return Collections.emptyMap();
        }

        // Create a map to count channels per category
        Map<String, Integer> categories = new HashMap<>();

        // Count channels in each category
        for (String group : channelGroupMap.values()) {
            if (group != null && !group.isEmpty()) {
                categories.put(group, categories.getOrDefault(group, 0) + 1);
            }
        }

        LOGGER.info("Found " + categories.size() + " distinct categories");
        return categories;
    }

    private void clearSearchCache() {
        searchResultsCache.clear();
        searchCacheTimestamps.clear();
        LOGGER.fine("Search cache cleared");
    }

    private int levenshteinDistance(String s1, String s2) {
        int[] costs = new int[s2.length() + 1];
        for (int i = 0; i <= s1.length(); i++) {
            int lastValue = i;
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) {
                    costs[j] = j;
                } else if (j > 0) {
                    int newValue = costs[j - 1];
                    if (s1.charAt(i - 1) != s2.charAt(j - 1)) {
                        newValue = Math.min(Math.min(newValue, lastValue), costs[j]) + 1;
                    }
                    costs[j - 1] = lastValue;
                    lastValue = newValue;
                }
            }
            if (i > 0) {
                costs[s2.length()] = lastValue;
            }
        }
        return costs[s2.length()];
    }

    private boolean fuzzyMatch(String text, String query, double threshold) {
        if (text.toLowerCase().contains(query.toLowerCase())) {
            return true; // Exact substring match
        }

        // For very short queries (1-2 chars), only use exact matching
        if (query.length() <= 2) {
            return false;
        }

        int distance = levenshteinDistance(text.toLowerCase(), query.toLowerCase());
        int maxLength = Math.max(text.length(), query.length());
        double similarity = 1.0 - ((double) distance / maxLength);

        return similarity >= threshold;
    }

    public List<String> fuzzySearchChannels(String query, String category, double threshold) {
        if (primaryChannelUrlMap.isEmpty()) {
            return Collections.emptyList();
        }

        String normalizedQuery = query.toLowerCase().trim();
        List<String> results = new ArrayList<>();

        // Use different thresholds based on query length
        // Shorter queries need higher similarity to avoid too many false positives
        double adjustedThreshold = threshold;
        if (normalizedQuery.length() <= 3) {
            adjustedThreshold = Math.max(0.8, threshold); // Higher threshold for short queries
        } else if (normalizedQuery.length() >= 6) {
            adjustedThreshold = Math.min(0.6, threshold); // Lower threshold for longer queries
        }

        for (String channelName : primaryChannelUrlMap.keySet()) {
            // Check category filter first
            if (category != null && !category.isEmpty()) {
                String channelGroup = channelGroupMap.get(channelName.toLowerCase());
                if (channelGroup == null || !channelGroup.toLowerCase().contains(category.toLowerCase())) {
                    continue;
                }
            }

            // Skip fuzzy matching if query is empty
            if (normalizedQuery.isEmpty()) {
                results.add(channelName);
                continue;
            }

            // Apply fuzzy matching
            if (fuzzyMatch(channelName, normalizedQuery, adjustedThreshold)) {
                results.add(channelName);
            }
        }

        // Sort results by relevance (exact matches first, then by similarity)
        results.sort((a, b) -> {
            boolean aContains = a.toLowerCase().contains(normalizedQuery);
            boolean bContains = b.toLowerCase().contains(normalizedQuery);

            if (aContains && !bContains) return -1;
            if (!aContains && bContains) return 1;

            int aDistance = levenshteinDistance(a.toLowerCase(), normalizedQuery);
            int bDistance = levenshteinDistance(b.toLowerCase(), normalizedQuery);

            return Integer.compare(aDistance, bDistance);
        });

        return results;
    }

    private void performSelfHealing() {
        try {
            LOGGER.info("Running self-healing routine");

            // Check if maps are inconsistent
            if (primaryChannelUrlMap.size() != channelUrlsMap.size()) {
                LOGGER.warning("Detected inconsistency in channel maps, attempting repair");

                // Rebuild primary map from the backup map
                for (Map.Entry<String, List<String>> entry : channelUrlsMap.entrySet()) {
                    if (!entry.getValue().isEmpty() && !primaryChannelUrlMap.containsKey(entry.getKey())) {
                        primaryChannelUrlMap.put(entry.getKey(), entry.getValue().get(0));
                    }
                }
            }

            // Check for broken references
            int cleanedUp = 0;
            for (Iterator<Map.Entry<String, String>> it = primaryChannelUrlMap.entrySet().iterator(); it.hasNext();) {
                Map.Entry<String, String> entry = it.next();
                if (entry.getValue() == null || entry.getValue().isEmpty()) {
                    it.remove();
                    cleanedUp++;
                }
            }

            if (cleanedUp > 0) {
                LOGGER.info("Cleaned up " + cleanedUp + " invalid channel entries");
            }

            // Check verification cache health
            long staleEntries = verifiedUrlCache.entrySet().stream()
                    .filter(e -> cacheTimestamps.getOrDefault(e.getKey(), 0L) <
                            System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24))
                    .count();

            if (staleEntries > 100) {
                LOGGER.info("Cleaning up " + staleEntries + " stale verification cache entries");
                clearVerificationCache();
            }

            LOGGER.info("Self-healing completed successfully");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error during self-healing", e);
        }
    }

    private void clearVerificationCache() {
        LOGGER.info("Clearing URL verification cache with " + verifiedUrlCache.size() + " entries");
        verifiedUrlCache.clear();
        cacheTimestamps.clear();
    }

    private void checkMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        double memoryUsagePercent = (double) usedMemory / maxMemory * 100;

        if (memoryUsagePercent > 75) {
            LOGGER.warning("High memory usage detected: " + String.format("%.2f%%", memoryUsagePercent));
            LOGGER.info("Suggesting garbage collection...");
            System.gc();
        }
    }

    private void optimizeMemoryAfterParsing() {
        // Check if the playlist is large (over certain size threshold)
        if (playlistContent != null && playlistContent.length() > 10_000_000) { // 10MB
            // Clear the original content to free memory
            LOGGER.info("Clearing original playlist content to free memory");
            playlistContent = null;

            // Suggest garbage collection
            checkMemoryUsage();
        }
    }

    private void asyncPreVerifyPopularChannels() {
        if (primaryChannelUrlMap.isEmpty()) return;

        CompletableFuture.runAsync(() -> {
            try {
                List<String> channelNames = new ArrayList<>(primaryChannelUrlMap.keySet());
                // Sort by potential popularity - prefer shorter names which are often major channels
                channelNames.sort(Comparator.comparingInt(String::length));

                // Only take up to 20 channels to avoid long verification times
                int channelsToVerify = Math.min(20, channelNames.size());

                LOGGER.info("Pre-verifying " + channelsToVerify + " popular channels asynchronously");

                for (int i = 0; i < channelsToVerify; i++) {
                    String channelName = channelNames.get(i);
                    String url = primaryChannelUrlMap.get(channelName);

                    try {
                        verifyStreamUrl(url);
                        // Brief pause between verifications
                        Thread.sleep(100);
                    } catch (Exception e) {
                        // Just log, don't fail the whole process
                        LOGGER.fine("Pre-verification failed for " + channelName + ": " + e.getMessage());
                    }
                }

                LOGGER.info("Pre-verification complete");
            } catch (Exception e) {
                LOGGER.warning("Error in async pre-verification: " + e.getMessage());
            }
        }, streamCheckExecutor);
    }

    private List<String> emergencyFallbackSearch(String query, String category) {
        List<String> results = new ArrayList<>();
        String normalizedQuery = query.toLowerCase();

        for (String channelName : primaryChannelUrlMap.keySet()) {
            if ((normalizedQuery.isEmpty() || channelName.toLowerCase().contains(normalizedQuery)) &&
                    (category == null || category.isEmpty() ||
                            (channelGroupMap.containsKey(channelName.toLowerCase()) &&
                                    channelGroupMap.get(channelName.toLowerCase()).toLowerCase().contains(category.toLowerCase())))) {
                results.add(channelName);
            }
        }

        return results;
    }

    public List<String> searchChannelsWithRecovery(String query, String category) {
        try {
            // Try regular search first
            return searchChannels(query, category);
        } catch (Exception e) {
            LOGGER.warning("Error in primary search method: " + e.getMessage());

            // Fallback to a simpler, more robust search algorithm
            try {
                LOGGER.info("Trying fallback search method");
                return emergencyFallbackSearch(query, category);
            } catch (Exception e2) {
                LOGGER.severe("Even fallback search failed: " + e2.getMessage());
                return Collections.emptyList();
            }
        }
    }

    public List<String> getPopularChannels(int limit) {
        if (primaryChannelUrlMap.isEmpty()) {
            return Collections.emptyList();
        }

        // Create a list with all channel names
        List<String> allChannels = new ArrayList<>(primaryChannelUrlMap.keySet());

        // Apply heuristics to find potentially popular channels
        // 1. Shorter names are often major networks (CNN, BBC, etc.)
        // 2. Channels with verified URLs are more likely to be popular

        // Sort by verified status and name length
        allChannels.sort((a, b) -> {
            String urlA = primaryChannelUrlMap.get(a);
            String urlB = primaryChannelUrlMap.get(b);

            // First sort by verified status
            boolean aVerified = verifiedUrlCache.getOrDefault(urlA, false);
            boolean bVerified = verifiedUrlCache.getOrDefault(urlB, false);

            if (aVerified && !bVerified) return -1;
            if (!aVerified && bVerified) return 1;

            // Then by name length (shorter names first)
            return Integer.compare(a.length(), b.length());
        });

        // Return the top channels up to the limit
        return allChannels.subList(0, Math.min(limit, allChannels.size()));
    }

    public boolean validateStream(String streamUrl) {
        // Skip validation for non-HTTP URLs
        if (!streamUrl.startsWith("http")) {
            return true;
        }

        try {
            URL url = new URL(streamUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);

            int responseCode = connection.getResponseCode();
            return responseCode >= 200 && responseCode < 400;
        } catch (Exception e) {
            LOGGER.fine("Stream validation failed: " + e.getMessage());
            return false;
        }
    }

    private void cleanupAfterParsing() {
        // Free memory by clearing the raw playlist content after parsing
        if (playlistContent != null && playlistContent.length() > 5_000_000) { // 5MB
            LOGGER.info("Clearing original playlist content to free memory");
            playlistContent = null;
            System.gc(); // Suggest garbage collection
        }
    }
}