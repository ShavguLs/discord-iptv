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
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private final Map<String, String> channelMap = new HashMap<>();
    private String playlistContent;

    public void parsePlaylistFromUrl(String playlistUrl) throws IOException {
        LOGGER.info("Parsing playlist from URL: " + playlistUrl);

        // Download the playlist content with redirect handling
        playlistContent = downloadWithRedirects(playlistUrl);

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
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);

            // Connect and check the response code
            connection.connect();
            int responseCode = connection.getResponseCode();

            LOGGER.info("HTTP Response Code: " + responseCode);

            // Check if it's a redirect
            if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == HttpURLConnection.HTTP_SEE_OTHER ||
                    responseCode == 307 || responseCode == 308) {

                // Get the redirect URL
                String newUrl = connection.getHeaderField("Location");

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

        channelMap.clear();
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
                    currentChannelName = "Channel-" + (channelMap.size() + 1);
                }

                // Add to channel map
                channelMap.put(currentChannelName.toLowerCase(), line);

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

        LOGGER.info("Parsing complete. Found " + channelMap.size() + " channels.");

        // Log a few channel names for debugging if available
        if (!channelMap.isEmpty()) {
            LOGGER.info("Sample channel names:");
            int count = 0;
            for (String name : channelMap.keySet()) {
                LOGGER.info(" - " + name);
                count++;
                if (count >= 5) break;
            }
        }
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

    public String getStreamUrl(String channelName) {
        if (channelMap.isEmpty()) {
            LOGGER.warning("Channel map is empty. Parse a playlist first.");
            return null;
        }

        // Try exact match first (case insensitive)
        String url = channelMap.get(channelName.toLowerCase());

        // If not found, try partial match
        if (url == null) {
            LOGGER.info("Exact match not found for '" + channelName + "', trying partial matches...");

            for (Map.Entry<String, String> entry : channelMap.entrySet()) {
                if (entry.getKey().toLowerCase().contains(channelName.toLowerCase())) {
                    LOGGER.info("Found partial match: '" + entry.getKey() + "' for query: '" + channelName + "'");
                    return entry.getValue();
                }
            }

            LOGGER.warning("Channel not found: " + channelName);
            return null;
        }

        return url;
    }

    public List<String> getAvailableChannels() {
        return new ArrayList<>(channelMap.keySet());
    }

    public int getChannelCount() {
        return channelMap.size();
    }

    public String getDebugInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("--- IPTV Parser Debug Info ---\n");
        sb.append("Total channels: ").append(channelMap.size()).append("\n\n");

        if (channelMap.isEmpty()) {
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
        for (Map.Entry<String, String> entry : channelMap.entrySet()) {
            sb.append(count + 1).append(". ").append(entry.getKey());
            sb.append("\n   URL: ").append(entry.getValue()).append("\n");

            count++;
            if (count >= 10) break;
        }

        return sb.toString();
    }
}