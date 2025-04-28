package com.iptv.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.Event;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

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
//                                                new OptionData(OptionType.STRING, "playlist", "URL to IPTV playlist", true),
                                                new OptionData(OptionType.STRING, "channel", "Channel name to stream", true)),
                                Commands.slash("stop", "Stop the current IPTV stream"),
                                Commands.slash("list", "List available channels in the playlist")
                                        .addOptions(
                                                new OptionData(OptionType.STRING, "playlist", "URL to IPTV playlist", true)),
                                Commands.slash("cleanup", "Clean up all old sources from OBS"),
                                Commands.slash("streaminfo", "Display information about the current stream"),
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
            case "start":
                handleStartCommand(event);
                break;
            case "stop":
                handleStopCommand(event);
                break;
            case "list":
                handleListCommand(event);
                break;
            case "cleanup":
                handleCleanupCommand(event);
                break;
            case "ping":
                handlePingCommand(event);
                break;
            case "streaminfo"   :
                handleStreamInfoCommand(event);
                break;
            default:
                event.reply("Unknown command: " + command).setEphemeral(true).queue();
                break;
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

            boolean isStreamActive = ffmpegController.isRunning();

            Map<String, String> streamMetadata = collectStreamMetadata(streamUrl);

            StringBuilder infoBuilder = new StringBuilder();
            infoBuilder.append("📺 **TV Stream Information**\n\n");
            infoBuilder.append("**Channel:** ").append(channelName).append("\n");
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

                // Reply with the stream information
                event.getHook().sendMessage(infoBuilder.toString()).queue();
            }
        }catch (Exception e){
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

//        String playlistUrl = event.getOption("playlist").getAsString();
        String playlistUrl = config.getProperty("playlist.url");
        String channelName = event.getOption("channel").getAsString();
        String guildId = event.getGuild().getId();

        // Check if we already have an active stream for this guild
        if (activeStreams.containsKey(guildId)) {
            event.getHook().sendMessage("A stream is already active in this server. Stop it first with /stop").queue();
            return;
        }

        try {
            // Parse the playlist
            iptvParser.parsePlaylistFromUrl(playlistUrl);

            // Find the channel
            String streamUrl = iptvParser.getStreamUrl(channelName);

            if (streamUrl == null) {
                event.getHook().sendMessage("Channel not found: " + channelName).queue();
                return;
            }

            // Setup OBS
            String localStreamUrl = "udp://127.0.0.1:1234";

            // Start FFmpeg to capture the stream and send it to local UDP
            if (!ffmpegController.startStreaming(streamUrl, localStreamUrl)) {
                event.getHook().sendMessage("Failed to start FFmpeg streaming").queue();
                return;
            }

            // Configure OBS to display the stream
            if (!obsController.setupStream(localStreamUrl, channelName)) {
                ffmpegController.stopStreaming();
                event.getHook().sendMessage("Failed to configure OBS").queue();
                return;
            }

            // Store active stream info
            StreamInfo streamInfo = new StreamInfo(playlistUrl, channelName, streamUrl, event.getChannel().getId());
            activeStreams.put(guildId, streamInfo);

            // Success message with instructions
            event.getHook().sendMessage("IPTV Stream started for channel: " + channelName + "\n\n" +
                    "**How to view the stream:**\n" +
                    "1. Join a voice channel\n" +
                    "2. Share your screen and select OBS Virtual Camera\n" +
                    "3. Everyone in the voice channel will see the stream").queue();

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

    private void handleListCommand(SlashCommandInteractionEvent event) {
        // Defer reply to allow for longer processing time
        event.deferReply().queue();

        String playlistUrl = event.getOption("playlist").getAsString();

        try {
            // Parse the playlist
            iptvParser.parsePlaylistFromUrl(playlistUrl);

            // Get available channels
            List<String> channels = iptvParser.getAvailableChannels();

            if (channels.isEmpty()) {
                event.getHook().sendMessage("No channels found in the playlist").queue();
                return;
            }

            // Build paginated response
            StringBuilder response = new StringBuilder();
            response.append("Found ").append(channels.size()).append(" channels:\n\n");

            // Only list first 20 channels to avoid message limit
            int displayCount = Math.min(channels.size(), 20);
            for (int i = 0; i < displayCount; i++) {
                response.append("- ").append(channels.get(i)).append("\n");
            }

            if (channels.size() > 20) {
                response.append("\n... and ").append(channels.size() - 20).append(" more channels.");
            }

            event.getHook().sendMessage(response.toString()).queue();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error parsing playlist", e);
            event.getHook().sendMessage("Error: " + e.getMessage()).queue();
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
        // Stop all active streams
        for (StreamInfo streamInfo : activeStreams.values()) {
            ffmpegController.stopStreaming();
        }
        activeStreams.clear();

        // Disconnect from OBS
        obsController.disconnect();

        // Shutdown FFmpeg controller
        ffmpegController.shutdown();

        // Shutdown JDA
        jda.shutdown();
    }

    private static class StreamInfo {
        final String playlistUrl;
        final String channelName;
        final String streamUrl;
        final String channelId;
        final long startTime;

        public StreamInfo(String playlistUrl, String channelName, String streamUrl, String channelId) {
            this.playlistUrl = playlistUrl;
            this.channelName = channelName;
            this.streamUrl = streamUrl;
            this.channelId = channelId;
            this.startTime = System.currentTimeMillis();
        }
    }
}