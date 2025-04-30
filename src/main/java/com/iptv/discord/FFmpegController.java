package com.iptv.discord;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

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

    // Enhanced error tracking
    private final Map<ErrorType, AtomicInteger> errorCounts = new HashMap<>();
    private ErrorType lastErrorType = ErrorType.NONE;
    private String lastErrorMessage = "";
    private final AtomicBoolean criticalErrorDetected = new AtomicBoolean(false);

    // Pattern to detect common FFmpeg error messages
    private static final Pattern ERROR_PATTERN = Pattern.compile(
            "(Connection refused|Connection timed out|Server returned|Invalid data|Failed to|Error|No such file)",
            Pattern.CASE_INSENSITIVE);

    // Error types for better recovery strategies
    public enum ErrorType {
        NONE,
        CONNECTION_REFUSED,
        CONNECTION_TIMEOUT,
        SERVER_ERROR,
        INVALID_DATA,
        STREAM_STALLED,
        UNEXPECTED_EXIT,
        UNKNOWN
    }

    // Initialize error counters
    {
        for (ErrorType type : ErrorType.values()) {
            errorCounts.put(type, new AtomicInteger(0));
        }
    }

    public boolean startStreaming(String streamUrl, String outputUrl) {
        if (isRunning) {
            LOGGER.info("FFmpeg is already running. Stopping current stream first.");
            stopStreaming();
        }

        this.streamUrl = streamUrl;
        this.outputUrl = outputUrl;
        resetErrorState();
        return startFFmpegProcess();
    }

    private void resetErrorState() {
        restartAttempts = 0;
        lastProgressUpdate = 0;
        lastErrorType = ErrorType.NONE;
        lastErrorMessage = "";
        criticalErrorDetected.set(false);

        // Reset all error counters
        for (AtomicInteger counter : errorCounts.values()) {
            counter.set(0);
        }
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
                        processFFmpegOutput(line);
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
            lastErrorType = ErrorType.UNKNOWN;
            lastErrorMessage = e.getMessage();
            return false;
        }
    }

    private void processFFmpegOutput(String line) {
        // Process FFmpeg output line for better error detection

        // Check for error messages
        Matcher errorMatcher = ERROR_PATTERN.matcher(line);
        if (line.contains("Error") || line.contains("error") || errorMatcher.find()) {
            LOGGER.warning("FFmpeg error: " + line);

            // Categorize the error
            ErrorType detectedType = categorizeError(line);
            errorCounts.get(detectedType).incrementAndGet();
            lastErrorType = detectedType;
            lastErrorMessage = line;

            // Check if this is a critical error that means we should give up
            if (isCriticalError(line)) {
                criticalErrorDetected.set(true);
                LOGGER.severe("Critical stream error detected: " + line);
            }
        }
        // Check for progress information
        else if (line.contains("speed=") || line.contains("frame=")) {
            lastProgressUpdate = System.currentTimeMillis();

            // Extract more detailed progress information if needed
            extractProgressInfo(line);

            // Regular FFmpeg progress output, log at finest level
            LOGGER.finest(line);
        } else {
            LOGGER.fine(line);
        }
    }

    private ErrorType categorizeError(String errorLine) {
        errorLine = errorLine.toLowerCase();

        if (errorLine.contains("connection refused")) {
            return ErrorType.CONNECTION_REFUSED;
        } else if (errorLine.contains("connection timed out") || errorLine.contains("timeout")) {
            return ErrorType.CONNECTION_TIMEOUT;
        } else if (errorLine.contains("server returned") || errorLine.contains("server error") ||
                errorLine.contains("403") || errorLine.contains("404") || errorLine.contains("500")) {
            return ErrorType.SERVER_ERROR;
        } else if (errorLine.contains("invalid data") || errorLine.contains("corrupt") ||
                errorLine.contains("malformed") || errorLine.contains("error while decoding")) {
            return ErrorType.INVALID_DATA;
        } else {
            return ErrorType.UNKNOWN;
        }
    }

    private boolean isCriticalError(String errorLine) {
        errorLine = errorLine.toLowerCase();

        // Errors that indicate the stream is permanently unavailable
        return errorLine.contains("403 forbidden") ||
                errorLine.contains("404 not found") ||
                errorLine.contains("access denied") ||
                errorLine.contains("authentication failed") ||
                errorLine.contains("no such file or directory");
    }

    // Extract and potentially store progress information
    private void extractProgressInfo(String progressLine) {
        // Could extract frame rate, speed, etc. if needed for more detailed monitoring
        // For now we just update the timestamp
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

        // Enhanced reconnect options
        command.add("-reconnect");
        command.add("1");
        command.add("-reconnect_at_eof");
        command.add("1");
        command.add("-reconnect_streamed");
        command.add("1");
        command.add("-reconnect_delay_max");
        command.add("30"); // Increased from 5 to 30 for better recovery

        // Error handling options
        command.add("-err_detect");
        command.add("ignore_err"); // More tolerant of stream errors

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
                    errorCounts.get(ErrorType.UNEXPECTED_EXIT).incrementAndGet();
                    lastErrorType = ErrorType.UNEXPECTED_EXIT;
                    handleFFmpegRestart();
                } else if (isRunning) {
                    // Check for output stall (no progress for too long)
                    if (lastProgressUpdate > 0 &&
                            System.currentTimeMillis() - lastProgressUpdate > 30000) {
                        LOGGER.warning("FFmpeg output stalled for 30 seconds, restarting");
                        errorCounts.get(ErrorType.STREAM_STALLED).incrementAndGet();
                        lastErrorType = ErrorType.STREAM_STALLED;
                        stopStreaming();
                        handleFFmpegRestart();
                    }
                }
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private void handleFFmpegRestart() {
        // Don't attempt to restart if a critical error was detected
        if (criticalErrorDetected.get()) {
            LOGGER.severe("Not attempting restart due to critical error: " + lastErrorMessage);
            isRunning = false;
            return;
        }

        // Check if we've exceeded the maximum number of attempts for this specific error type
        int maxAttemptsForErrorType = getMaxAttemptsForErrorType(lastErrorType);
        int currentErrorTypeCount = errorCounts.get(lastErrorType).get();

        if (restartAttempts < MAX_RESTART_ATTEMPTS && currentErrorTypeCount <= maxAttemptsForErrorType) {
            restartAttempts++;
            LOGGER.info("Attempting to restart FFmpeg (Attempt " +
                    restartAttempts + "/" + MAX_RESTART_ATTEMPTS + ", Error type: " + lastErrorType + ")");

            // Wait with dynamic backoff based on error type
            long backoffTime = calculateBackoffTime(lastErrorType, restartAttempts);
            try {
                Thread.sleep(backoffTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            startFFmpegProcess();
        } else {
            LOGGER.severe("Maximum restart attempts reached. Giving up on restarting FFmpeg.");
            LOGGER.info("Error summary: " + getErrorSummary());
            isRunning = false;
        }
    }

    private int getMaxAttemptsForErrorType(ErrorType errorType) {
        // Different max attempts based on error type
        switch (errorType) {
            case CONNECTION_REFUSED:
            case CONNECTION_TIMEOUT:
                return 6; // More attempts for connection issues that might be temporary
            case SERVER_ERROR:
                return 4; // Server errors might resolve with time
            case INVALID_DATA:
                return 3; // Data corruption issues less likely to resolve
            case STREAM_STALLED:
                return 5; // Stalls might be temporary network congestion
            default:
                return MAX_RESTART_ATTEMPTS;
        }
    }

    private long calculateBackoffTime(ErrorType errorType, int attempt) {
        // Base backoff time (ms)
        long baseTime = 1000;

        // Exponential backoff factor
        double factor = Math.pow(1.5, attempt - 1);

        // Adjust based on error type
        double typeMultiplier = 1.0;
        switch (errorType) {
            case CONNECTION_TIMEOUT:
                typeMultiplier = 1.5; // Wait longer for timeouts
                break;
            case SERVER_ERROR:
                typeMultiplier = 2.0; // Wait even longer for server errors
                break;
            case STREAM_STALLED:
                typeMultiplier = 0.8; // Shorter for stalls
                break;
            default:
                typeMultiplier = 1.0;
        }

        // Calculate and apply some randomness to prevent thundering herd
        long backoff = (long)(baseTime * factor * typeMultiplier);
        backoff += (long)(Math.random() * 500); // Add up to 500ms of randomness

        return backoff;
    }

    public String getErrorSummary() {
        StringBuilder summary = new StringBuilder("Stream error summary:\n");
        for (Map.Entry<ErrorType, AtomicInteger> entry : errorCounts.entrySet()) {
            if (entry.getValue().get() > 0) {
                summary.append(" - ").append(entry.getKey()).append(": ")
                        .append(entry.getValue().get()).append(" occurrences\n");
            }
        }
        summary.append("Last error: ").append(lastErrorType)
                .append(" - ").append(lastErrorMessage);
        return summary.toString();
    }

    public ErrorType getLastErrorType() {
        return lastErrorType;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public boolean hasCriticalError() {
        return criticalErrorDetected.get();
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

    /**
     * Start streaming with custom quality options
     * @param streamUrl The source stream URL
     * @param outputUrl The destination stream URL (usually UDP)
     * @param qualityOptions FFmpeg options to control quality
     * @return True if started successfully
     */
    public boolean startStreamingWithQuality(String streamUrl, String outputUrl, String qualityOptions) {
        if (isRunning) {
            LOGGER.info("FFmpeg is already running. Stopping current stream first.");
            stopStreaming();
        }

        this.streamUrl = streamUrl;
        this.outputUrl = outputUrl;
        resetErrorState();

        return startFFmpegProcessWithOptions(qualityOptions);
    }

    private boolean startFFmpegProcessWithOptions(String qualityOptions) {
        List<String> command = buildFFmpegCommandWithQuality(qualityOptions);

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
                        processFFmpegOutput(line);
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
            lastErrorType = ErrorType.UNKNOWN;
            lastErrorMessage = e.getMessage();
            return false;
        }
    }

    private List<String> buildFFmpegCommandWithQuality(String qualityOptions) {
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

        // Enhanced reconnect options
        command.add("-reconnect");
        command.add("1");
        command.add("-reconnect_at_eof");
        command.add("1");
        command.add("-reconnect_streamed");
        command.add("1");
        command.add("-reconnect_delay_max");
        command.add("30");

        // Error handling options
        command.add("-err_detect");
        command.add("ignore_err");

        // Add custom quality options if provided
        if (qualityOptions != null && !qualityOptions.isEmpty()) {
            // Parse and add each option
            String[] options = qualityOptions.split(" ");
            for (String option : options) {
                if (!option.trim().isEmpty()) {
                    command.add(option.trim());
                }
            }
        } else {
            // Default options when no quality specified
            // Just copy codecs without re-encoding
            command.add("-vcodec");
            command.add("copy");
            command.add("-acodec");
            command.add("copy");
        }

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

    /**
     * Improved FFmpeg controller method for smoother quality transitions
     */
    public boolean switchQualityWithoutRestart(String streamUrl, String outputUrl, String qualityOptions) {
        try {
            // Create a new FFmpeg process with the new quality settings
            List<String> command = buildFFmpegCommandWithQuality(qualityOptions);
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);

            LOGGER.info("Starting new FFmpeg process with updated quality: " + String.join(" ", command));

            Process newProcess = processBuilder.start();

            // Start a thread to monitor the new process
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(newProcess.getInputStream()))) {
                    String line;
                    boolean startedSuccessfully = false;
                    int linesRead = 0;

                    // Read a few lines to see if the process started successfully
                    while ((line = reader.readLine()) != null && linesRead < 20) {
                        processFFmpegOutput(line);
                        linesRead++;

                        if (line.contains("Output #0") || line.contains("frame=")) {
                            startedSuccessfully = true;
                            break;
                        }
                    }

                    if (startedSuccessfully) {
                        LOGGER.info("New FFmpeg process started successfully, switching...");

                        // Store reference to old process
                        Process oldProcess = ffmpegProcess;

                        // Update process reference
                        ffmpegProcess = newProcess;

                        // Allow a small overlap to prevent stream interruption
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }

                        // Terminate old process gracefully
                        if (oldProcess != null && oldProcess.isAlive()) {
                            oldProcess.destroy();
                            try {
                                if (!oldProcess.waitFor(5, TimeUnit.SECONDS)) {
                                    oldProcess.destroyForcibly();
                                }
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                oldProcess.destroyForcibly();
                            }
                        }

                        // Continue reading output from new process
                        while ((line = reader.readLine()) != null) {
                            processFFmpegOutput(line);
                        }
                    } else {
                        LOGGER.warning("New FFmpeg process failed to start properly");
                        newProcess.destroy();
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "Error reading new FFmpeg process output", e);
                }
            }).start();

            return true;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error switching quality", e);
            return false;
        }
    }

    public void cleanupAllProcesses() {
        stopStreaming();

        // Find and kill any orphaned FFmpeg processes
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                Runtime.getRuntime().exec("taskkill /F /IM ffmpeg.exe");
            } else {
                Runtime.getRuntime().exec("pkill -9 ffmpeg");
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to clean up FFmpeg processes", e);
        }
    }

    private int findAvailableUdpPort() {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            LOGGER.warning("Error finding available UDP port: " + e.getMessage());
            return 1234; // Default fallback
        }
    }
}