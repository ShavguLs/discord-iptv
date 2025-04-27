package com.iptv.discord;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FFmpegController {
    private static final Logger LOGGER = Logger.getLogger(FFmpegController.class.getName());

    private Process ffmpegProcess;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private String streamUrl;
    private String outputUrl;
    private boolean isRunning = false;
    private int restartAttempts = 0;
    private static final int MAX_RESTART_ATTEMPTS = 5;
    private long lastProgressUpdate = 0;

    public boolean startStreaming(String streamUrl, String outputUrl) {
        if (isRunning) {
            LOGGER.info("FFmpeg is already running. Stopping current stream first.");
            stopStreaming();
        }

        this.streamUrl = streamUrl;
        this.outputUrl = outputUrl;
        restartAttempts = 0;
        lastProgressUpdate = 0;

        return startFFmpegProcess();
    }

    private boolean startFFmpegProcess() {
        List<String> command = buildFFmpegCommand();

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);

            LOGGER.info("Starting FFmpeg with command: " + String.join(" ", command));
            ffmpegProcess = processBuilder.start();

            // Start a thread to read and log the FFmpeg output
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(ffmpegProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.contains("Error") || line.contains("error")) {
                            LOGGER.warning("FFmpeg error: " + line);
                        } else if (line.contains("speed=") || line.contains("frame=")) {
                            // Regular FFmpeg progress output, log at finest level
                            lastProgressUpdate = System.currentTimeMillis();
                            LOGGER.finest(line);
                        } else {
                            LOGGER.fine(line);
                        }
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "Error reading FFmpeg output", e);
                }
            }).start();

            // Set up watchdog to monitor the FFmpeg process
            setupWatchdog();

            isRunning = true;
            lastProgressUpdate = System.currentTimeMillis();
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to start FFmpeg", e);
            return false;
        }
    }

    private List<String> buildFFmpegCommand() {
        List<String> command = new ArrayList<>();

        command.add("ffmpeg");

        // Input options for faster stream analysis
        command.add("-probesize");
        command.add("42M");
        command.add("-analyzeduration");
        command.add("3M");

        // Input URL
        command.add("-i");
        command.add(streamUrl);

        // Reconnect options
        command.add("-reconnect");
        command.add("1");
        command.add("-reconnect_streamed");
        command.add("1");
        command.add("-reconnect_delay_max");
        command.add("5");

        // Video options - copy codec for better performance unless transcoding is needed
        command.add("-vcodec");
        command.add("copy");

        // Audio options - copy codec
        command.add("-acodec");
        command.add("copy");

        // Format
        command.add("-f");
        command.add("mpegts");

        // Output options
        if (outputUrl.startsWith("udp://")) {
            // UDP-specific options
            command.add("-bufsize");
            command.add("5000k");
            command.add("-flush_packets");
            command.add("1");
        }

        // Output URL
        command.add(outputUrl);

        return command;
    }

    private void setupWatchdog() {
        watchdog.scheduleAtFixedRate(() -> {
            if (ffmpegProcess != null) {
                // Check if process is alive
                if (!ffmpegProcess.isAlive() && isRunning) {
                    LOGGER.warning("FFmpeg process died unexpectedly");
                    handleFFmpegRestart();
                } else if (isRunning) {
                    // Check for output stall (no progress for too long)
                    if (lastProgressUpdate > 0 &&
                            System.currentTimeMillis() - lastProgressUpdate > 30000) {
                        LOGGER.warning("FFmpeg output stalled for 30 seconds, restarting");
                        stopStreaming();
                        handleFFmpegRestart();
                    }
                }
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private void handleFFmpegRestart() {
        if (restartAttempts < MAX_RESTART_ATTEMPTS) {
            restartAttempts++;
            LOGGER.info("Attempting to restart FFmpeg (Attempt " +
                    restartAttempts + "/" + MAX_RESTART_ATTEMPTS + ")");

            // Wait with exponential backoff
            try {
                Thread.sleep(1000 * (long)Math.pow(2, restartAttempts - 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            startFFmpegProcess();
        } else {
            LOGGER.severe("Maximum restart attempts reached. Giving up on restarting FFmpeg.");
            isRunning = false;
        }
    }

    public void stopStreaming() {
        isRunning = false;

        if (ffmpegProcess != null && ffmpegProcess.isAlive()) {
            LOGGER.info("Stopping FFmpeg process");

            try {
                // Send SIGTERM first for a graceful shutdown
                ffmpegProcess.destroy();

                // Wait for process to terminate gracefully
                if (!ffmpegProcess.waitFor(5, TimeUnit.SECONDS)) {
                    LOGGER.warning("FFmpeg did not terminate gracefully, forcing termination");
                    ffmpegProcess.destroyForcibly();

                    // Wait for forced termination
                    if (!ffmpegProcess.waitFor(2, TimeUnit.SECONDS)) {
                        LOGGER.severe("FFmpeg could not be terminated even with force!");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warning("Interrupted while waiting for FFmpeg to terminate");
                ffmpegProcess.destroyForcibly();
            } catch (Exception e) {
                LOGGER.severe("Error during FFmpeg shutdown: " + e.getMessage());
                ffmpegProcess.destroyForcibly();
            }
        }

        ffmpegProcess = null;

        // Additional cleanup to ensure UDP port is released
        if (outputUrl != null && outputUrl.startsWith("udp://")) {
            String port = "1234"; // Default port

            // Try to extract the actual port from the URL
            try {
                String[] parts = outputUrl.split(":");
                if (parts.length >= 3) {
                    port = parts[parts.length - 1];
                }
            } catch (Exception e) {
                LOGGER.fine("Could not extract port from URL, using default: " + port);
            }

            try {
                // On Windows, run a netsh command to release any UDP bindings
                if (System.getProperty("os.name").toLowerCase().contains("win")) {
                    LOGGER.info("Attempting to release UDP port " + port + " on Windows");
                    ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "netsh", "interface", "ipv4", "delete", "udpport", port);
                    pb.redirectErrorStream(true);
                    Process process = pb.start();
                    process.waitFor(3, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                LOGGER.warning("Failed to clean up UDP port: " + e.getMessage());
            }
        }
    }

    public boolean isRunning() {
        return isRunning && ffmpegProcess != null && ffmpegProcess.isAlive();
    }

    public void shutdown() {
        stopStreaming();
        watchdog.shutdown();
        try {
            if (!watchdog.awaitTermination(10, TimeUnit.SECONDS)) {
                watchdog.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            watchdog.shutdownNow();
        }
    }
}