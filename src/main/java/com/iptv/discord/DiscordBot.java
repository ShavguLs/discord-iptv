package com.iptv.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.Event;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class DiscordBot extends ListenerAdapter {
    private static final Logger LOGGER = Logger.getLogger(DiscordBot.class.getName());

    private final JDA jda;
    private final String botToken;
    private final List<String> authorizedGuildIds;
    private final List<String> authorizedUserIds;
    private final String obsPassword;

    private final RedirectIPTVParser iptvParser;
    private final FFmpegController ffmpegController;
    private final OBSController obsController;

    // Track active streams per guild
    private final ConcurrentMap<String, StreamInfo> activeStreams = new ConcurrentHashMap<>();

    private final Map<String, ScheduledExecutorService> qualityMonitors = new ConcurrentHashMap<>();

    public DiscordBot(String botToken, List<String> authorizedGuildIds, List<String> authorizedUserIds) throws Exception {
        this(botToken, authorizedGuildIds, authorizedUserIds, "");
    }

    public DiscordBot(String botToken, List<String> authorizedGuildIds, List<String> authorizedUserIds, String obsPassword) throws Exception {
        this.botToken = botToken;
        this.authorizedGuildIds = authorizedGuildIds;
        this.authorizedUserIds = authorizedUserIds;
        this.obsPassword = obsPassword;

        this.iptvParser = new RedirectIPTVParser();
        this.ffmpegController = new FFmpegController();
        this.obsController = new OBSController("localhost", obsPassword);

        // Initialize JDA
        jda = JDABuilder.createDefault(botToken)
                .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(this)
                .build();

        jda.awaitReady();
    }

    @Override
    public void onReady(ReadyEvent event) {
        LOGGER.info("Bot is ready: " + jda.getSelfUser().getName());

        // Register slash commands for authorized guilds
        for (String guildId : authorizedGuildIds) {
            Guild guild = jda.getGuildById(guildId);
            if (guild != null) {
                guild.updateCommands()
                        .addCommands(
                                Commands.slash("start", "Start streaming an IPTV channel")
                                        .addOptions(
                                                new OptionData(OptionType.STRING, "channel", "Channel name to stream", true),
                                                new OptionData(OptionType.STRING, "quality", "Stream quality", false)
                                                        .addChoice("Auto (Detect optimal)", "auto")
                                                        .addChoice("Original", "original")
                                                        .addChoice("High (1080p)", "high")
                                                        .addChoice("Medium (720p)", "medium")
                                                        .addChoice("Low (480p)", "low")
                                                        .addChoice("Audio Only", "audio-only")),
                                Commands.slash("stop", "Stop the current IPTV stream"),
                                Commands.slash("cleanup", "Clean up all old sources from OBS"),
                                Commands.slash("search", "Search for channels by name or category")
                                        .addOptions(
                                                new OptionData(OptionType.STRING, "query", "Search term", true)
                                                    .setAutoComplete(true),
                                                new OptionData(OptionType.STRING, "category", "Filter by category", false)
                                                    .setAutoComplete(true)),
                                Commands.slash("categories", "List all available channel categories"),
                                Commands.slash("list", "List available channels in the playlist")
                                        .addOptions(
                                                new OptionData(OptionType.STRING, "playlist", "URL to IPTV playlist", false),
                                                new OptionData(OptionType.STRING, "category", "Filter by category", false)),
                                Commands.slash("streaminfo", "Display information about the current stream"),
                                Commands.slash("healthcheck", "Check stream health and connection status"),
                                Commands.slash("ping", "Test command to test bot respond")
                        ).queue();

                LOGGER.info("Registered commands for guild: " + guild.getName());
            } else {
                LOGGER.warning("Could not find guild with ID: " + guildId);
            }
        }

        // Connect to OBS
        if (!obsController.connect()) {
            LOGGER.warning("Failed to connect to OBS. Please make sure OBS is running with WebSocket plugin enabled.");
        }
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        try {
            // Check if user is authorized
            if (!authorizedUserIds.contains(event.getUser().getId())) {
                event.reply("You are not authorized to use this bot.").setEphemeral(true).queue();
                return;
            }

            // Check if guild is authorized
            if (event.getGuild() == null || !authorizedGuildIds.contains(event.getGuild().getId())) {
                event.reply("This bot is not authorized for use in this server.").setEphemeral(true).queue();
                return;
            }

            String command = event.getName();

            switch (command) {
                // Your existing switch cases
                case "start":
                    handleStartCommand(event);
                    break;
                case "stop":
                    handleStopCommand(event);
                    break;
                case "list":
                    handleListCommand(event);
                    break;
                case "search":
                    handleSearchCommand(event);
                    break;
                case "categories":
                    handleCategoriesCommand(event);
                    break;
                case "cleanup":
                    handleCleanupCommand(event);
                    break;
                case "ping":
                    handlePingCommand(event);
                    break;
                case "streaminfo":
                    handleStreamInfoCommand(event);
                    break;
                case "healthcheck":
                    handleHealthCheckCommand(event);
                    break;
                default:
                    event.reply("Unknown command: " + command).setEphemeral(true).queue();
                    break;
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error handling slash command: " + event.getName(), e);
            try {
                // If we haven't replied yet, reply with error
                if (!event.isAcknowledged()) {
                    event.reply("An error occurred while processing your command. Please try again.").setEphemeral(true).queue();
                } else {
                    // If we've already replied (using deferReply), use the hook
                    event.getHook().sendMessage("An error occurred while processing your command. Please try again.").queue();
                }
            } catch (Exception ex) {
                // If even the error message fails, just log it
                LOGGER.log(Level.SEVERE, "Failed to send error message", ex);
            }
        }
    }

    private void handleStreamInfoCommand(SlashCommandInteractionEvent event) {
        String guildId = event.getGuild().getId();

        if (!activeStreams.containsKey(guildId)){
            event.reply("There is no active stream. Start one with `/start`.");
            return;
        }

        StreamInfo streamInfo = activeStreams.get(guildId);

        // Defer reply to allow for data collection
        event.deferReply().queue();

        try {
            String streamUrl = streamInfo.streamUrl;
            String channelName = streamInfo.channelName;
            String quality = streamInfo.quality;

            boolean isStreamActive = ffmpegController.isRunning();

            Map<String, String> streamMetadata = collectStreamMetadata(streamUrl);

            StringBuilder infoBuilder = new StringBuilder();
            infoBuilder.append("📺 **TV Stream Information**\n\n");
            infoBuilder.append("**Channel:** ").append(channelName).append("\n");
            infoBuilder.append("**Quality:** ").append(getQualityDisplayName(quality)).append("\n");
            infoBuilder.append("**Stream Status:** ").append(isStreamActive ? "✅ Active" : "❌ Inactive").append("\n");
            infoBuilder.append("**Started At:** <t:").append(streamInfo.startTime / 1000).append(":f>\n");
            infoBuilder.append("**Uptime:** ").append(formatUptime(System.currentTimeMillis() - streamInfo.startTime)).append("\n");

            if (!streamMetadata.isEmpty()){
                infoBuilder.append("\n**Stream Details:**\n");

                // Add video info if it is available
                if (streamMetadata.containsKey("resolution")){
                    infoBuilder.append("📹 **Video:** ").append(streamMetadata.get("resolution"));
                    if (streamMetadata.containsKey("video_bitrate")) {
                        infoBuilder.append(" at ").append(streamMetadata.get("video_bitrate"));
                    }
                    infoBuilder.append("\n");
                }

                // Add audio info if it is available
                if (streamMetadata.containsKey("audio_codec")) {
                    infoBuilder.append("🔊 **Audio:** ").append(streamMetadata.get("audio_codec"));
                    if (streamMetadata.containsKey("audio_bitrate")) {
                        infoBuilder.append(" at ").append(streamMetadata.get("audio_bitrate"));
                    }
                    infoBuilder.append("\n");
                }
            }

            // Reply with the stream information
            event.getHook().sendMessage(infoBuilder.toString()).queue();
        } catch (Exception e){
            LOGGER.log(Level.SEVERE, "Error getting stream info", e);
            event.getHook().sendMessage("Error retrieving stream info: " + e.getMessage()).queue();
        }
    }

    private String formatUptime(long milliseconds) {
        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        return String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60);
    }

    private Map<String, String> collectStreamMetadata(String streamUrl) {
        Map<String, String> metadata = new HashMap<>();

        try {
            LOGGER.info("Collecting metadata for stream URL: " + streamUrl);

            // Run FFprobe to get stream information
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "ffprobe",
                    "-v", "error",
                    "-show_format",
                    "-show_streams",
                    "-print_format", "json",
                    streamUrl
            );

            LOGGER.info("Running command: " + String.join(" ", processBuilder.command()));

            // Set a shorter timeout for probe
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            // Only wait a few seconds to avoid blocking the bot
            if (process.waitFor(5, TimeUnit.SECONDS)) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    StringBuilder output = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line);
                    }

                    String jsonOutput = output.toString();
                    LOGGER.fine("FFprobe output: " + jsonOutput);

                    if (jsonOutput.isEmpty()) {
                        LOGGER.warning("Empty output from FFprobe");
                        return metadata;
                    }

                    // Parse the JSON output
                    JSONObject json = new JSONObject(jsonOutput);

                    // Get stream information from the JSON
                    if (json.has("streams") && json.getJSONArray("streams").length() > 0) {
                        JSONArray streams = json.getJSONArray("streams");
                        LOGGER.info("Found " + streams.length() + " streams");

                        // Process video and audio streams
                        for (int i = 0; i < streams.length(); i++) {
                            JSONObject stream = streams.getJSONObject(i);
                            String codecType = stream.getString("codec_type");

                            LOGGER.fine("Processing stream " + i + " of type: " + codecType);

                            if ("video".equals(codecType)) {
                                if (stream.has("width") && stream.has("height")) {
                                    int width = stream.getInt("width");
                                    int height = stream.getInt("height"); // Fixed property name
                                    metadata.put("resolution", width + "x" + height);
                                    LOGGER.fine("Found resolution: " + width + "x" + height);
                                }

                                if (stream.has("bit_rate")) {
                                    int bitrate = stream.getInt("bit_rate") / 1000;
                                    metadata.put("video_bitrate", bitrate + " Kbps");
                                    LOGGER.fine("Found video bitrate: " + bitrate + " Kbps");
                                }

                                if (stream.has("codec_name")) {
                                    metadata.put("video_codec", stream.getString("codec_name"));
                                    LOGGER.fine("Found video codec: " + stream.getString("codec_name"));
                                }
                            } else if ("audio".equals(codecType)) {
                                if (stream.has("codec_name")) {
                                    metadata.put("audio_codec", stream.getString("codec_name"));
                                    LOGGER.fine("Found audio codec: " + stream.getString("codec_name"));
                                }

                                if (stream.has("bit_rate")) {
                                    int bitrate = stream.getInt("bit_rate") / 1000;
                                    metadata.put("audio_bitrate", bitrate + " Kbps");
                                    LOGGER.fine("Found audio bitrate: " + bitrate + " Kbps");
                                }
                            }
                        }
                    } else {
                        LOGGER.warning("No streams found in the FFprobe output");
                    }

                    // Get format information
                    if (json.has("format")) {
                        JSONObject format = json.getJSONObject("format");

                        if (format.has("bit_rate")) {
                            int totalBitrate = format.getInt("bit_rate") / 1000;
                            metadata.put("total_bitrate", totalBitrate + " Kbps");
                            LOGGER.fine("Found total bitrate: " + totalBitrate + " Kbps");
                        }
                    }
                }
            } else {
                // Process didn't finish in time, kill it
                process.destroyForcibly();
                LOGGER.warning("FFprobe timeout while collecting stream metadata");
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error collecting stream metadata", e);
            e.printStackTrace(); // Add this to get more details about the error
        }

        LOGGER.info("Collected metadata: " + metadata);
        return metadata;
    }

    private void handlePingCommand(SlashCommandInteractionEvent event){
        try {
            event.reply("Pong").queue();
        }catch (Exception e){
            event.reply("Something went wrong!").queue();
        }
    }

    private void handleStartCommand(SlashCommandInteractionEvent event) {
        // Defer reply to allow for longer processing time
        event.deferReply().queue();

        Properties config = Main.loadConfig();

        String playlistUrl = config.getProperty("playlist.url");
        String channelName = event.getOption("channel").getAsString();

        // Get quality option if specified
        String quality = "original"; // Default to original quality
        if (event.getOption("quality") != null) {
            quality = event.getOption("quality").getAsString();
        }

        String guildId = event.getGuild().getId();

        // Check if we already have an active stream for this guild
        if (activeStreams.containsKey(guildId)) {
            event.getHook().sendMessage("A stream is already active in this server. Stop it first with `/stop`").queue();
            return;
        }

        try {
            // Parse the playlist - will use cached playlist if recently parsed
            iptvParser.parsePlaylistFromUrl(playlistUrl);

            // Find the channel
            String streamUrl = iptvParser.getStreamUrl(channelName);

            if (streamUrl == null) {
                event.getHook().sendMessage("Channel not found: `" + channelName +
                        "`\n\nUse `/list` to see available channels.").queue();
                return;
            }

            // Setup OBS
            String localStreamUrl = "udp://127.0.0.1:1234";

            // If quality is auto, detect the optimal quality
            if (quality.equals("auto")) {
                String detectedQuality = detectOptimalQuality();
                LOGGER.info("Auto-detected quality: " + detectedQuality);
                quality = detectedQuality;

                // Inform the user about the auto-detected quality
                event.getHook().sendMessage("Auto-detected optimal quality based on network conditions: " +
                        getQualityDisplayName(quality)).queue();
            }

            // Define quality settings based on selected quality
            String qualityOptions = "";
            if (!quality.equals("original")) {
                switch (quality) {
                    case "high":
                        qualityOptions = "-vf scale=1920:-1 -c:v libx264 -preset medium -b:v 4M -maxrate 4M -bufsize 8M -c:a aac -b:a 192k";
                        break;
                    case "medium":
                        qualityOptions = "-vf scale=1280:-1 -c:v libx264 -preset medium -b:v 2M -maxrate 2M -bufsize 4M -c:a aac -b:a 128k";
                        break;
                    case "low":
                        qualityOptions = "-vf scale=854:-1 -c:v libx264 -preset fast -b:v 800k -maxrate 800k -bufsize 1600k -c:a aac -b:a 96k";
                        break;
                    case "audio-only":
                        qualityOptions = "-vn -c:a aac -b:a 128k";
                        break;
                }
            }

            // Start FFmpeg with quality options if specified
            boolean success;
            if (quality.equals("original")) {
                // Use the original method for original quality to maintain compatibility
                success = ffmpegController.startStreaming(streamUrl, localStreamUrl);
            } else {
                // Use the new quality-aware method
                success = ffmpegController.startStreamingWithQuality(streamUrl, localStreamUrl, qualityOptions);
            }

            if (!success) {
                // Try a fallback URL if available
                LOGGER.info("Primary stream failed, attempting fallback");
                String fallbackUrl = iptvParser.getFallbackStreamUrl(channelName);

                if (fallbackUrl != null && !fallbackUrl.equals(streamUrl)) {
                    LOGGER.info("Using fallback URL: " + fallbackUrl);

                    // Inform the user we're trying a fallback
                    event.getHook().sendMessage("Primary stream source failed, trying backup source...").queue();

                    // Try the fallback with the same quality settings
                    if (quality.equals("original")) {
                        success = ffmpegController.startStreaming(fallbackUrl, localStreamUrl);
                    } else {
                        success = ffmpegController.startStreamingWithQuality(fallbackUrl, localStreamUrl, qualityOptions);
                    }

                    if (!success) {
                        // Enhanced error reporting
                        handleStreamFailure(event, channelName, ffmpegController.getLastErrorMessage());
                        // Mark the channel as problematic
                        iptvParser.markStreamAsFailed(channelName);
                        return;
                    }

                    // Update the stream URL to the fallback that worked
                    streamUrl = fallbackUrl;
                } else {
                    // No fallback available or fallback is the same URL
                    handleStreamFailure(event, channelName, ffmpegController.getLastErrorMessage());
                    iptvParser.markStreamAsFailed(channelName);
                    return;
                }
            }

            // Configure OBS to display the stream
            if (!obsController.setupStream(localStreamUrl, channelName)) {
                ffmpegController.stopStreaming();
                event.getHook().sendMessage("Failed to configure OBS. Please ensure OBS is running with WebSocket plugin enabled.").queue();
                return;
            }

            // Store active stream info with enhanced details and quality setting
            StreamInfo streamInfo = new StreamInfo(playlistUrl, channelName, streamUrl, event.getChannel().getId(), quality);
            activeStreams.put(guildId, streamInfo);

            // Get additional channel info if available
            String channelGroup = iptvParser.getChannelGroup(channelName);
            List<String> similarChannels = iptvParser.getSimilarChannels(channelName);

            // Build success message with additional information
            StringBuilder successMessage = new StringBuilder();
            successMessage.append("📺 **IPTV Stream started**\n\n");
            successMessage.append("**Channel:** ").append(channelName).append("\n");

            // Include quality information
            successMessage.append("**Quality:** ").append(getQualityDisplayName(quality)).append("\n");

            if (channelGroup != null && !channelGroup.isEmpty()) {
                successMessage.append("**Category:** ").append(channelGroup).append("\n");
            }

            successMessage.append("\n**How to view the stream:**\n");
            successMessage.append("1. Join a voice channel\n");
            successMessage.append("2. Share your screen and select OBS Virtual Camera\n");
            successMessage.append("3. Everyone in the voice channel will see the stream\n\n");

            // Add similar channels if available
            if (!similarChannels.isEmpty()) {
                successMessage.append("**Similar channels in this category:**\n");
                int count = 0;
                for (String similar : similarChannels) {
                    successMessage.append("• `").append(similar).append("`\n");
                    count++;
                    if (count >= 5) break; // Limit to 5 suggestions
                }
                successMessage.append("\n");
            }

            successMessage.append("Use `/stop` to end the stream, or `/streaminfo` for details.");

            event.getHook().sendMessage(successMessage.toString()).queue();

            // Start monitoring thread for this stream
            startStreamMonitoring(guildId, event.getChannel());

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error parsing playlist", e);
            event.getHook().sendMessage("Error: " + e.getMessage()).queue();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error", e);
            event.getHook().sendMessage("Unexpected error: " + e.getMessage()).queue();

            // Cleanup
            ffmpegController.stopStreaming();
        }
    }

    private String getQualityDisplayName(String quality) {
        switch (quality) {
            case "high":
                return "High (1080p)";
            case "medium":
                return "Medium (720p)";
            case "low":
                return "Low (480p)";
            case "audio-only":
                return "Audio Only";
            default:
                return "Original Source";
        }
    }

    private final Map<String, ScheduledExecutorService> streamMonitors = new ConcurrentHashMap<>();

    private void startStreamMonitoring(String guildId, MessageChannel channel) {
        // Stop any existing monitor for this guild
        stopStreamMonitoring(guildId);

        // Create a new scheduled executor
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        streamMonitors.put(guildId, executor);

        // Schedule regular checks
        executor.scheduleAtFixedRate(() -> {
            try {
                // Only proceed if we still have an active stream for this guild
                if (!activeStreams.containsKey(guildId)) {
                    stopStreamMonitoring(guildId);
                    return;
                }

                StreamInfo streamInfo = activeStreams.get(guildId);

                // Check if FFmpeg is still running
                if (!ffmpegController.isRunning()) {
                    // Stream has died
                    if (ffmpegController.hasCriticalError()) {
                        // Critical error - notify and stop the stream
                        String errorMessage = "⚠️ **Stream Error**\n\n" +
                                "The stream for channel **" + streamInfo.channelName + "** has stopped due to a critical error: " +
                                ffmpegController.getLastErrorMessage() + "\n\n" +
                                "The stream has been stopped. Try a different channel or try again later.";

                        channel.sendMessage(errorMessage).queue();

                        // Clean up this stream
                        handleCleanStreamStop(guildId);
                    } else if (ffmpegController.getLastErrorType() != FFmpegController.ErrorType.NONE) {
                        // Non-critical error - just notify
                        String warningMessage = "⚠️ **Stream Warning**\n\n" +
                                "The stream for channel **" + streamInfo.channelName + "** is experiencing issues: " +
                                ffmpegController.getLastErrorMessage() + "\n\n" +
                                "Attempting to recover automatically...";

                        channel.sendMessage(warningMessage).queue();
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in stream monitoring", e);
            }
        }, 30, 30, TimeUnit.SECONDS); // Check every 30 seconds
    }

    private void stopStreamMonitoring(String guildId) {
        ScheduledExecutorService executor = streamMonitors.remove(guildId);
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }

    private void handleCleanStreamStop(String guildId) {
        // Stop monitoring
        stopStreamMonitoring(guildId);

        // Only proceed if we have an active stream for this guild
        if (!activeStreams.containsKey(guildId)) {
            return;
        }

        // Get stream info before removing it
        StreamInfo streamInfo = activeStreams.get(guildId);

        // Stop FFmpeg
        ffmpegController.stopStreaming();

        // Remove from tracking
        activeStreams.remove(guildId);

        // Clean up OBS sources
        try {
            String safeName = streamInfo.channelName.replaceAll("[^a-zA-Z0-9]", "_");
            String sourceName = "IPTV-Source-" + safeName;
            obsController.cleanupSources(sourceName);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error cleaning up OBS sources", e);
        }
    }

    private void handleStopCommand(SlashCommandInteractionEvent event) {
        String guildId = event.getGuild().getId();

        // Check if we have an active stream for this guild
        if (!activeStreams.containsKey(guildId)) {
            event.reply("No active stream to stop").setEphemeral(true).queue();
            return;
        }

        try {
            // First, defer the reply to give time for the operations
            event.deferReply().queue();

            // Get stream info before removing it
            StreamInfo removedStream = activeStreams.get(guildId);
            String channelName = removedStream.channelName;

            // Stop FFmpeg first
            LOGGER.info("Stopping FFmpeg for channel: " + channelName);
            ffmpegController.stopStreaming();

            // Remove the stream from our tracking
            activeStreams.remove(guildId);

            // Allow a short delay for FFmpeg to fully stop
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Clean up OBS sources
            try {
                // Get a sanitized version of the channel name that matches what we used to create it
                String safeName = channelName.replaceAll("[^a-zA-Z0-9]", "_");
                String sourceName = "IPTV-Source-" + safeName;

                // Try to clean up any sources with this name pattern
                // This will include both the base name and any timestamped variants
                obsController.cleanupSources(sourceName);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error cleaning up OBS sources", e);
                // Continue anyway as we've already stopped FFmpeg
            }

            event.getHook().sendMessage("Stopped streaming: " + channelName).queue();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error stopping stream", e);
            event.getHook().sendMessage("Error stopping stream: " + e.getMessage()).queue();
        }
    }

    private void handleCleanupCommand(SlashCommandInteractionEvent event) {
        // Check if user is authorized
        if (!authorizedUserIds.contains(event.getUser().getId())) {
            event.reply("You are not authorized to use this command.").setEphemeral(true).queue();
            return;
        }

        // Defer reply since this might take some time
        event.deferReply().queue();

        try {
            LOGGER.info("Running OBS cleanup command");
            int removed = obsController.runCompleteCleanup();

            if (removed > 0) {
                event.getHook().sendMessage("Cleanup completed successfully. Removed " + removed + " old sources from OBS.").queue();
            } else {
                event.getHook().sendMessage("No sources were found that needed cleanup.").queue();
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error during cleanup", e);
            event.getHook().sendMessage("Error during cleanup: " + e.getMessage()).queue();
        }
    }

    public void shutdown() {
        // Stop all stream monitoring
        for (String guildId : new ArrayList<>(streamMonitors.keySet())) {
            stopStreamMonitoring(guildId);
        }

        // Stop all active streams
        for (StreamInfo streamInfo : activeStreams.values()) {
            ffmpegController.stopStreaming();
        }
        activeStreams.clear();

        // Disconnect from OBS
        obsController.disconnect();

        // Shutdown FFmpeg controller
        ffmpegController.shutdown();

        // Shutdown the IPTV parser
        iptvParser.shutdown();

        // Shutdown JDA
        jda.shutdown();
    }

    private void handleHealthCheckCommand(SlashCommandInteractionEvent event) {
        // Always acknowledge the interaction immediately
        event.deferReply().queue();

        String guildId = event.getGuild().getId();

        if (!activeStreams.containsKey(guildId)) {
            // Use getHook() instead of reply() since we already deferred
            event.getHook().sendMessage("No active stream to check").queue();
            return;
        }

        StreamInfo streamInfo = activeStreams.get(guildId);
        boolean isStreamRunning = ffmpegController.isRunning();

        StringBuilder healthReport = new StringBuilder();
        healthReport.append("📊 **Stream Health Report**\n\n");
        healthReport.append("**Channel:** ").append(streamInfo.channelName).append("\n");
        healthReport.append("**Status:** ");

        if (isStreamRunning) {
            healthReport.append("✅ Running\n");
            healthReport.append("**Uptime:** ").append(formatUptime(System.currentTimeMillis() - streamInfo.startTime)).append("\n");
        } else {
            healthReport.append("❌ Not running\n");
            healthReport.append("**Last Error:** ").append(ffmpegController.getLastErrorType()).append("\n");
            healthReport.append("**Error Details:** ").append(ffmpegController.getLastErrorMessage()).append("\n");
        }

        // Add error statistics if there have been issues
        if (ffmpegController.getLastErrorType() != FFmpegController.ErrorType.NONE) {
            healthReport.append("\n**Error Summary:**\n");
            healthReport.append(ffmpegController.getErrorSummary()).append("\n");
        }

        // Use getHook() instead of reply() since we already deferred
        event.getHook().sendMessage(healthReport.toString()).queue();
    }

    private static class StreamInfo {
        final String playlistUrl;
        final String channelName;
        final String streamUrl;
        final String channelId;
        final long startTime;
        final String quality;
        final String initialQuality; // Store original quality selection

        public StreamInfo(String playlistUrl, String channelName, String streamUrl, String channelId) {
            this(playlistUrl, channelName, streamUrl, channelId, "original", "original", System.currentTimeMillis());
        }

        public StreamInfo(String playlistUrl, String channelName, String streamUrl, String channelId, String quality) {
            this(playlistUrl, channelName, streamUrl, channelId, quality, quality, System.currentTimeMillis());
        }

        public StreamInfo(String playlistUrl, String channelName, String streamUrl, String channelId,
                          String quality, String initialQuality, long startTime) {
            this.playlistUrl = playlistUrl;
            this.channelName = channelName;
            this.streamUrl = streamUrl;
            this.channelId = channelId;
            this.startTime = startTime;
            this.quality = quality;
            this.initialQuality = initialQuality;
        }
    }

    private void handleStreamFailure(SlashCommandInteractionEvent event, String channelName, String errorMessage) {
        // Enhanced error reporting method
        StringBuilder errorResponse = new StringBuilder();
        errorResponse.append("❌ **Stream Error**\n\n");
        errorResponse.append("Failed to start stream for channel: **").append(channelName).append("**\n\n");

        // Add specific error information
        if (errorMessage != null && !errorMessage.isEmpty()) {
            errorResponse.append("**Error details:**\n");
            errorResponse.append("`").append(errorMessage).append("`\n\n");
        }

        // Add troubleshooting suggestions based on error type
        if (ffmpegController.getLastErrorType() != null) {
            errorResponse.append("**Troubleshooting suggestions:**\n");

            switch (ffmpegController.getLastErrorType()) {
                case CONNECTION_REFUSED:
                    errorResponse.append("• The stream server appears to be offline or rejecting connections\n");
                    errorResponse.append("• Try again later when the server might be available\n");
                    break;
                case CONNECTION_TIMEOUT:
                    errorResponse.append("• The stream server is not responding in time\n");
                    errorResponse.append("• This could be due to network congestion or server issues\n");
                    errorResponse.append("• Try again in a few minutes\n");
                    break;
                case SERVER_ERROR:
                    errorResponse.append("• The stream server reported an error\n");
                    errorResponse.append("• The channel may be temporarily unavailable\n");
                    errorResponse.append("• Try again later or try a different channel\n");
                    break;
                case INVALID_DATA:
                    errorResponse.append("• Received corrupt or invalid stream data\n");
                    errorResponse.append("• The channel may be broadcasting incorrectly\n");
                    errorResponse.append("• Try a different channel\n");
                    break;
                case STREAM_STALLED:
                    errorResponse.append("• The stream started but then stopped sending data\n");
                    errorResponse.append("• This could be due to network issues or server problems\n");
                    errorResponse.append("• Try again or try a different channel\n");
                    break;
                default:
                    errorResponse.append("• Try again later or try a different channel\n");
                    errorResponse.append("• Check if the IPTV service is working properly\n");
            }
        }

        // Send the response
        event.getHook().sendMessage(errorResponse.toString()).queue();
    }

    /**
     * Handles the /search slash command to find channels by name or category
     */
    private void handleSearchCommand(SlashCommandInteractionEvent event) {
        // Get the search query
        String query = event.getOption("query").getAsString();

        // Get the optional category filter (may be null)
        String category = null;
        if (event.getOption("category") != null) {
            category = event.getOption("category").getAsString();
        }

        // Defer reply to allow for longer processing time
        event.deferReply().queue();

        Properties config = Main.loadConfig();
        String playlistUrl = config.getProperty("playlist.url");

        try {
            // Make sure playlist is parsed
            iptvParser.parsePlaylistFromUrl(playlistUrl);

            // Search for channels
            List<String> results = iptvParser.searchChannels(query, category);

            if (results.isEmpty()) {
                StringBuilder noResultsMsg = new StringBuilder("No channels found matching \"" + query + "\"");
                if (category != null && !category.isEmpty()) {
                    noResultsMsg.append(" in category \"").append(category).append("\"");
                }

                event.getHook().sendMessage(noResultsMsg.toString()).queue();
                return;
            }

            // Build the response
            StringBuilder response = new StringBuilder();
            response.append("📺 **Found ").append(results.size()).append(" channels matching \"")
                    .append(query).append("\"");

            if (category != null && !category.isEmpty()) {
                response.append(" in category \"").append(category).append("\"");
            }

            response.append(":**\n\n");

            // Only display up to 20 channels to avoid message size limits
            int displayCount = Math.min(results.size(), 20);
            for (int i = 0; i < displayCount; i++) {
                String channelName = results.get(i);
                String group = iptvParser.getChannelGroup(channelName);

                response.append("• `").append(channelName).append("`");
                if (group != null && !group.isEmpty()) {
                    response.append(" (").append(group).append(")");
                }
                response.append("\n");
            }

            // If there are more results than we're showing
            if (results.size() > 20) {
                response.append("\n...and ").append(results.size() - 20).append(" more channels.");
            }

            // Add hint for starting a stream
            response.append("\n\nUse `/start channel:[channel name]` to stream a channel");

            event.getHook().sendMessage(response.toString()).queue();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error searching channels", e);
            event.getHook().sendMessage("Error: " + e.getMessage()).queue();
        }
    }

    /**
     * Handles the /categories slash command to list all channel categories
     */
    private void handleCategoriesCommand(SlashCommandInteractionEvent event) {
        // Defer reply to allow for longer processing time
        event.deferReply().queue();

        Properties config = Main.loadConfig();
        String playlistUrl = config.getProperty("playlist.url");

        try {
            // Make sure playlist is parsed
            iptvParser.parsePlaylistFromUrl(playlistUrl);

            // Get all categories and their channel counts
            Map<String, Integer> categories = iptvParser.getChannelCategories();

            if (categories.isEmpty()) {
                event.getHook().sendMessage("No categories found in the playlist. Your IPTV playlist may not include category information.").queue();
                return;
            }

            // Convert to list and sort by name
            List<Map.Entry<String, Integer>> sortedCategories = new ArrayList<>(categories.entrySet());
            sortedCategories.sort(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER));

            // Build the response
            StringBuilder response = new StringBuilder();
            response.append("📺 **Channel Categories (").append(categories.size()).append("):**\n\n");

            for (Map.Entry<String, Integer> entry : sortedCategories) {
                if (!entry.getKey().isEmpty()) {
                    response.append("• **").append(entry.getKey()).append("** (")
                            .append(entry.getValue()).append(" channels)\n");
                }
            }

            // Add hint for using categories in search
            response.append("\nUse `/search query:[search term] category:[category name]` to search for channels in a specific category");

            event.getHook().sendMessage(response.toString()).queue();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error retrieving categories", e);
            event.getHook().sendMessage("Error: " + e.getMessage()).queue();
        }
    }

    private void handleListCommand(SlashCommandInteractionEvent event) {
        // Defer reply to allow for longer processing time
        event.deferReply().queue();

        // Get optional parameters
        String category = null;
        String playlistUrl = null;

        if (event.getOption("category") != null) {
            category = event.getOption("category").getAsString();
        }

        if (event.getOption("playlist") != null) {
            playlistUrl = event.getOption("playlist").getAsString();
        } else {
            // Use default from config
            Properties config = Main.loadConfig();
            playlistUrl = config.getProperty("playlist.url");
        }

        try {
            // Parse the playlist
            iptvParser.parsePlaylistFromUrl(playlistUrl);

            // Get available channels, possibly filtered by category
            List<String> channels;
            if (category != null && !category.isEmpty()) {
                channels = iptvParser.searchChannels("", category); // Empty query means get all in category
            } else {
                channels = iptvParser.getAvailableChannels();
            }

            if (channels.isEmpty()) {
                if (category != null && !category.isEmpty()) {
                    event.getHook().sendMessage("No channels found in category: " + category).queue();
                } else {
                    event.getHook().sendMessage("No channels found in the playlist").queue();
                }
                return;
            }

            // Sort channels alphabetically for better readability
            channels.sort(String.CASE_INSENSITIVE_ORDER);

            // Build paginated response
            StringBuilder response = new StringBuilder();
            if (category != null && !category.isEmpty()) {
                response.append("📺 **Channels in category \"").append(category).append("\" (")
                        .append(channels.size()).append("):**\n\n");
            } else {
                response.append("📺 **Available Channels (").append(channels.size()).append("):**\n\n");
            }

            // Only list first 20 channels to avoid message limit
            int displayCount = Math.min(channels.size(), 20);
            for (int i = 0; i < displayCount; i++) {
                String channelName = channels.get(i);
                String group = iptvParser.getChannelGroup(channelName);

                response.append("• `").append(channelName).append("`");
                if (group != null && !group.isEmpty() && category == null) {
                    response.append(" (").append(group).append(")");
                }
                response.append("\n");
            }

            if (channels.size() > 20) {
                response.append("\n... and ").append(channels.size() - 20).append(" more channels.");
                response.append("\nUse `/search` to find specific channels.");
            }

            event.getHook().sendMessage(response.toString()).queue();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error parsing playlist", e);
            event.getHook().sendMessage("Error: " + e.getMessage()).queue();
        }
    }

    private String detectOptimalQuality() {
        try {
            // Run multiple speed tests for greater accuracy
            List<Double> speeds = new ArrayList<>();

            // Test multiple endpoints
            String[] testEndpoints = {
                    "https://www.google.com/favicon.ico",
                    "https://www.microsoft.com/favicon.ico",
                    "https://www.amazon.com/favicon.ico"
            };

            for (String endpoint : testEndpoints) {
                Double speed = measureConnectionSpeed(endpoint);
                if (speed != null) {
                    speeds.add(speed);
                }
            }

            if (speeds.isEmpty()) {
                LOGGER.warning("All speed tests failed, defaulting to medium quality");
                return "medium";
            }

            // Sort speeds and take median for robustness
            Collections.sort(speeds);
            double medianSpeed;

            if (speeds.size() % 2 == 0) {
                medianSpeed = (speeds.get(speeds.size() / 2 - 1) + speeds.get(speeds.size() / 2)) / 2;
            } else {
                medianSpeed = speeds.get(speeds.size() / 2);
            }

            LOGGER.info("Median network speed: " + medianSpeed + " Kbps");

            // Also check for jitter and packet loss if possible
            boolean highJitter = detectNetworkJitter();

            // Adjust quality recommendation based on speed and network stability
            if (highJitter) {
                // Reduce quality one step if network is unstable
                if (medianSpeed > 8000) return "medium"; // Would normally be high
                if (medianSpeed > 3000) return "low";    // Would normally be medium
                return "audio-only";
            } else {
                // Normal quality selection based on speed
                if (medianSpeed > 8000) return "high";      // >8 Mbps for high quality
                if (medianSpeed > 3000) return "medium";    // >3 Mbps for medium
                if (medianSpeed > 1000) return "low";       // >1 Mbps for low
                return "audio-only";                        // <1 Mbps for audio only
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error in enhanced quality detection", e);
            return "medium"; // Default to medium if detection fails
        }
    }

    private String detectOptimalQualityConsideringSystemLoad() {
        // First check system resources
        double cpuLoad = getSystemCpuLoad();
        long availableMemory = getAvailableSystemMemory();

        LOGGER.fine("System stats - CPU: " + cpuLoad + "%, Available memory: " +
                (availableMemory / (1024*1024)) + "MB");

        // If system is under heavy load, reduce quality
        if (cpuLoad > 90 || availableMemory < 100 * 1024 * 1024) { // 90% CPU or <100MB RAM
            LOGGER.info("System under heavy load, reducing quality");
            return "low";
        }

        // Otherwise use network-based quality detection
        return detectOptimalQuality();
    }

    /**
     * Get current system CPU load
     */
    private double getSystemCpuLoad() {
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOsBean =
                        (com.sun.management.OperatingSystemMXBean) osBean;
                return sunOsBean.getSystemCpuLoad() * 100;
            }
            return -1; // Not available
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Get available system memory in bytes
     */
    private long getAvailableSystemMemory() {
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOsBean =
                        (com.sun.management.OperatingSystemMXBean) osBean;
                return sunOsBean.getFreePhysicalMemorySize();
            }
            return -1; // Not available
        } catch (Exception e) {
            return -1;
        }
    }

    private Double measureConnectionSpeed(String endpoint) {
        try {
            long startTime = System.currentTimeMillis();
            URL speedTestUrl = new URL(endpoint);
            HttpURLConnection connection = (HttpURLConnection) speedTestUrl.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);

            try (InputStream inputStream = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                int totalBytes = 0;

                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    totalBytes += bytesRead;
                }

                long endTime = System.currentTimeMillis();
                long duration = endTime - startTime;

                if (duration == 0) return null; // Avoid division by zero

                // Calculate download speed in Kbps
                return (totalBytes * 8.0) / (duration / 1000.0);
            }
        } catch (Exception e) {
            LOGGER.fine("Speed test failed for " + endpoint + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Detect network jitter by measuring ping variance
     */
    private boolean detectNetworkJitter() {
        try {
            // Perform simple ping tests
            List<Long> pingTimes = new ArrayList<>();
            String host = "8.8.8.8"; // Google DNS

            for (int i = 0; i < 5; i++) {
                long start = System.currentTimeMillis();
                boolean reachable = InetAddress.getByName(host).isReachable(1000);
                long end = System.currentTimeMillis();

                if (reachable) {
                    pingTimes.add(end - start);
                }

                Thread.sleep(200); // Wait between pings
            }

            if (pingTimes.size() < 3) {
                return true; // Consider unstable if we couldn't get enough samples
            }

            // Calculate average and standard deviation
            double avg = pingTimes.stream().mapToLong(Long::longValue).average().orElse(0);
            double variance = pingTimes.stream()
                    .mapToDouble(time -> Math.pow(time - avg, 2))
                    .average()
                    .orElse(0);
            double stdDev = Math.sqrt(variance);

            // Calculate jitter as coefficient of variation
            double jitter = stdDev / avg;

            LOGGER.fine("Network jitter coefficient: " + jitter);

            // Consider high jitter if variation is >30%
            return jitter > 0.3;
        } catch (Exception e) {
            LOGGER.fine("Jitter detection failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        if (!authorizedUserIds.contains(event.getUser().getId())) {
            return; // Silently ignore unauthorized users
        }

        if (event.getName().equals("search")) {
            // Handle autocomplete for search command
            String option = event.getFocusedOption().getName();
            String value = event.getFocusedOption().getValue();

            if (option.equals("query")) {
                // Autocomplete channel name
                List<String> suggestions = getSuggestedChannels(value, 25);
                event.replyChoices(suggestions.stream()
                                .map(name -> new Command.Choice(name, name))
                                .collect(Collectors.toList()))
                        .queue();
            } else if (option.equals("category")) {
                // Autocomplete category
                List<String> categories = getSuggestedCategories(value, 25);
                event.replyChoices(categories.stream()
                                .map(name -> new Command.Choice(name, name))
                                .collect(Collectors.toList()))
                        .queue();
            }
        }
    }

    private List<String> getSuggestedChannels(String query, int limit) {
        try {
            if (query.trim().isEmpty()) {
                // Return most popular channels if no query
                return iptvParser.getPopularChannels(limit);
            } else {
                // Use the fuzzy search with a strict threshold for faster responses
                List<String> results = iptvParser.fuzzySearchChannels(query, null, 0.7);
                if (results.size() > limit) {
                    results = results.subList(0, limit);
                }
                return results;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error getting channel suggestions", e);
            return Collections.emptyList();
        }
    }

    private List<String> getSuggestedCategories(String query, int limit) {
        try {
            Map<String, Integer> categories = iptvParser.getChannelCategories();
            List<String> results = new ArrayList<>();

            if (query.trim().isEmpty()) {
                // Return categories sorted by number of channels
                results = categories.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toList());
            } else {
                // Filter categories by query
                String lowerQuery = query.toLowerCase();
                results = categories.keySet().stream()
                        .filter(cat -> cat.toLowerCase().contains(lowerQuery))
                        .sorted((a, b) -> categories.get(b) - categories.get(a))
                        .collect(Collectors.toList());
            }

            if (results.size() > limit) {
                results = results.subList(0, limit);
            }
            return results;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error getting category suggestions", e);
            return Collections.emptyList();
        }
    }

    private void startQualityMonitoring(String guildId, MessageChannel channel) {
        // Stop any existing monitor
        ScheduledExecutorService existingMonitor = qualityMonitors.remove(guildId);
        if (existingMonitor != null) {
            existingMonitor.shutdownNow();
        }

        // Only monitor if using auto quality
        StreamInfo streamInfo = activeStreams.get(guildId);
        if (streamInfo == null || !"auto".equals(streamInfo.initialQuality)) {
            return;
        }

        LOGGER.info("Starting quality monitoring for guild: " + guildId);

        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
        qualityMonitors.put(guildId, monitor);

        // Check every 2 minutes
        monitor.scheduleAtFixedRate(() -> {
            try {
                if (!activeStreams.containsKey(guildId)) {
                    stopQualityMonitoring(guildId);
                    return;
                }

                // Detect current optimal quality
                String newQuality = detectOptimalQuality();
                StreamInfo currentStream = activeStreams.get(guildId);

                // Only switch if quality changes significantly
                if (shouldSwitchQuality(currentStream.quality, newQuality)) {
                    LOGGER.info("Quality change detected: " + currentStream.quality + " -> " + newQuality);

                    // Notify users about quality change
                    channel.sendMessage("Stream quality changing from " +
                            getQualityDisplayName(currentStream.quality) + " to " +
                            getQualityDisplayName(newQuality) + " due to network conditions").queue();

                    // Restart stream with new quality
                    switchStreamQuality(guildId, newQuality, channel);
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in quality monitoring", e);
            }
        }, 2, 2, TimeUnit.MINUTES);
    }

    /**
     * Determine if quality switch is warranted
     */
    private boolean shouldSwitchQuality(String currentQuality, String newQuality) {
        // Define quality levels
        Map<String, Integer> qualityLevels = new HashMap<>();
        qualityLevels.put("audio-only", 0);
        qualityLevels.put("low", 1);
        qualityLevels.put("medium", 2);
        qualityLevels.put("high", 3);
        qualityLevels.put("original", 4);

        // Get numeric levels
        int currentLevel = qualityLevels.getOrDefault(currentQuality, 2);
        int newLevel = qualityLevels.getOrDefault(newQuality, 2);

        // Only switch if level difference is significant
        return Math.abs(currentLevel - newLevel) >= 2;
    }

    /**
     * Switch stream quality without fully stopping the stream
     */
    private void switchStreamQuality(String guildId, String newQuality, MessageChannel channel) {
        try {
            StreamInfo currentStream = activeStreams.get(guildId);
            if (currentStream == null) return;

            // Keep track of current stream info
            String channelName = currentStream.channelName;
            String streamUrl = currentStream.streamUrl;
            String localStreamUrl = "udp://127.0.0.1:1234";

            // Define quality settings based on selected quality
            String qualityOptions = "";
            switch (newQuality) {
                case "high":
                    qualityOptions = "-vf scale=1920:-1 -c:v libx264 -preset medium -b:v 4M -maxrate 4M -bufsize 8M -c:a aac -b:a 192k";
                    break;
                case "medium":
                    qualityOptions = "-vf scale=1280:-1 -c:v libx264 -preset medium -b:v 2M -maxrate 2M -bufsize 4M -c:a aac -b:a 128k";
                    break;
                case "low":
                    qualityOptions = "-vf scale=854:-1 -c:v libx264 -preset fast -b:v 800k -maxrate 800k -bufsize 1600k -c:a aac -b:a 96k";
                    break;
                case "audio-only":
                    qualityOptions = "-vn -c:a aac -b:a 128k";
                    break;
            }

            // Start new FFmpeg process with new quality
            boolean success;
            if (newQuality.equals("original")) {
                success = ffmpegController.startStreaming(streamUrl, localStreamUrl);
            } else {
                success = ffmpegController.startStreamingWithQuality(streamUrl, localStreamUrl, qualityOptions);
            }

            if (success) {
                // Update stream info with new quality
                StreamInfo updatedStream = new StreamInfo(
                        currentStream.playlistUrl, channelName, streamUrl,
                        currentStream.channelId, newQuality, currentStream.initialQuality,
                        currentStream.startTime
                );

                activeStreams.put(guildId, updatedStream);

                LOGGER.info("Successfully switched quality to: " + newQuality);
            } else {
                LOGGER.warning("Failed to switch quality, keeping current settings");
                channel.sendMessage("Failed to change stream quality. The stream will continue with the current quality settings.").queue();
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error switching quality", e);
        }
    }

    private void checkAndRepairMonitoring() {
        LOGGER.info("Checking monitoring systems");

        // Check if we have active streams without monitors
        for (String guildId : activeStreams.keySet()) {
            if (!streamMonitors.containsKey(guildId)) {
                LOGGER.warning("Found active stream without monitor for guild: " + guildId);
                LOGGER.info("Repairing by starting monitoring");

                StreamInfo info = activeStreams.get(guildId);
                MessageChannel channel = jda.getTextChannelById(info.channelId);
                if (channel != null) {
                    startStreamMonitoring(guildId, channel);
                }
            }
        }

        // Check for orphaned monitors (no active stream but monitor exists)
        for (String guildId : new ArrayList<>(streamMonitors.keySet())) {
            if (!activeStreams.containsKey(guildId)) {
                LOGGER.warning("Found orphaned monitor for guild: " + guildId);
                LOGGER.info("Repairing by stopping monitoring");
                stopStreamMonitoring(guildId);
            }
        }

        // Check OBS connection
        if (!obsController.isConnected()) {
            LOGGER.warning("OBS connection lost, attempting to reconnect");
            obsController.connect();
        }
    }

    private void stopQualityMonitoring(String guildId) {
        ScheduledExecutorService executor = qualityMonitors.remove(guildId);
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }

    private String createSafeSourceName(String channelName) {
        // Create a more condensed safe name
        String safeName = channelName.replaceAll("[^a-zA-Z0-9]", "_")  // Replace non-alphanumeric with underscore
                .replaceAll("_+", "_")              // Replace multiple underscores with one
                .replaceAll("^_|_$", "");           // Remove leading/trailing underscores

        // Limit the length to prevent overly long names
        int maxLength = 40;
        if (safeName.length() > maxLength) {
            safeName = safeName.substring(0, maxLength);
        }

        return "IPTV-Source-" + safeName;
    }
}