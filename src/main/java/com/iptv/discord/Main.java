package com.iptv.discord;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.*;

public class Main {
    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());

    private static DiscordBot discordBot;

    public static void main(String[] args) {
        try {
            // Setup logging
            setupLogging();

            // Load configuration
            Properties config = loadConfig();

            // Get Discord bot token
            String botToken = config.getProperty("discord.token");
            if (botToken == null || botToken.isEmpty()) {
                LOGGER.severe("Discord bot token not found in config.properties");
                return;
            }

            // Get authorized guild IDs
            String guildIdsString = config.getProperty("discord.authorized_guilds", "");
            List<String> authorizedGuildIds = Arrays.asList(guildIdsString.split(","));

            // Get authorized user IDs
            String userIdsString = config.getProperty("discord.authorized_users", "");
            List<String> authorizedUserIds = Arrays.asList(userIdsString.split(","));

            // Check for FFmpeg installation
            if (!checkFFmpegInstallation()) {
                LOGGER.severe("FFmpeg not found. Please install FFmpeg and add it to your PATH.");
                return;
            }

            // Check for OBS Studio installation
            if (!checkOBSInstallation()) {
                LOGGER.warning("OBS Studio installation not detected. Please make sure OBS Studio is installed with WebSocket plugin.");
            }

            // Start the bot
            LOGGER.info("Starting IPTV Discord bot...");
            discordBot = new DiscordBot(botToken, authorizedGuildIds, authorizedUserIds);

            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOGGER.info("Shutting down...");
                if (discordBot != null) {
                    discordBot.shutdown();
                }
            }));

            // Keep the application running
            LOGGER.info("Bot is running. Press Enter to stop.");
            new Scanner(System.in).nextLine();

            // Exit cleanly
            LOGGER.info("Stopping bot...");
            discordBot.shutdown();
            LOGGER.info("Bot stopped.");

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Fatal error", e);
            System.exit(1);
        }
    }

    private static void setupLogging() {
        try {
            // Check if logging properties file exists
            Path loggingPropsPath = Paths.get("logging.properties");
            if (Files.exists(loggingPropsPath)) {
                LogManager.getLogManager().readConfiguration(Files.newInputStream(loggingPropsPath));
            } else {
                // Use default logging configuration
                System.setProperty("java.util.logging.SimpleFormatter.format",
                        "[%1$tF %1$tT] [%4$-7s] %5$s %n");
            }
        } catch (IOException e) {
            System.err.println("Error setting up logging: " + e.getMessage());
        }
    }

    protected static Properties loadConfig() {
        Properties config = new Properties();

        // Try to load from config.properties
        Path configPath = Paths.get("config.properties");
        if (Files.exists(configPath)) {
            try (FileInputStream fis = new FileInputStream(configPath.toFile())) {
                config.load(fis);
                LOGGER.info("Loaded configuration from config.properties");
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error loading config.properties", e);
            }
        } else {
            LOGGER.info("config.properties not found, creating template");
            createConfigTemplate();
        }

        return config;
    }

    private static void createConfigTemplate() {
        Properties template = new Properties();
        template.setProperty("discord.token", "YOUR_BOT_TOKEN_HERE");
        template.setProperty("discord.authorized_guilds", "GUILD_ID_1,GUILD_ID_2");
        template.setProperty("discord.authorized_users", "USER_ID_1,USER_ID_2");

        Path configPath = Paths.get("config.properties");
        try {
            template.store(Files.newOutputStream(configPath), "IPTV Discord Bot Configuration");
            LOGGER.info("Created template config.properties. Please edit it and restart the application.");

            // Exit since we need the user to fill in the config
            System.exit(0);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to create config template", e);
        }
    }

    private static boolean checkFFmpegInstallation() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version").start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static boolean checkOBSInstallation() {
        // This is a basic check that might need adjustment based on OS
        Path obsPath = null;

        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            obsPath = Paths.get("C:/Program Files/obs-studio");
        } else if (os.contains("mac")) {
            obsPath = Paths.get("/Applications/OBS.app");
        } else if (os.contains("nix") || os.contains("nux")) {
            obsPath = Paths.get("/usr/bin/obs");
        }

        return obsPath != null && Files.exists(obsPath);
    }

    // Add to Main class
    /**
     * Set up enhanced logging with rotation and filtering
     */
    private static void setupEnhancedLogging() {
        try {
            // Create log directory if it doesn't exist
            Path logDir = Paths.get("logs");
            if (!Files.exists(logDir)) {
                Files.createDirectory(logDir);
            }

            // Set up log rotation
            FileHandler fileHandler = new FileHandler("logs/iptv_%g.log", 5 * 1024 * 1024, 5, true);
            fileHandler.setFormatter(new SimpleFormatter() {
                @Override
                public String format(LogRecord record) {
                    ZonedDateTime zdt = ZonedDateTime.ofInstant(
                            Instant.ofEpochMilli(record.getMillis()),
                            ZoneId.systemDefault());

                    return String.format("[%s] [%s] [%s] %s%n",
                            zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")),
                            record.getLevel(),
                            record.getLoggerName(),
                            record.getMessage());
                }
            });

            // Set up console handler with filtering to reduce noise
            ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setFilter(record -> {
                // Filter out excessive FFmpeg output in console
                if (record.getMessage().contains("frame=") ||
                        record.getMessage().contains("speed=")) {
                    return false;
                }
                return true;
            });

            // Get root logger and clean existing handlers
            Logger rootLogger = Logger.getLogger("");
            Handler[] handlers = rootLogger.getHandlers();
            for (Handler handler : handlers) {
                rootLogger.removeHandler(handler);
            }

            // Add our custom handlers
            rootLogger.addHandler(fileHandler);
            rootLogger.addHandler(consoleHandler);

            LOGGER.info("Enhanced logging setup complete");
        } catch (Exception e) {
            System.err.println("Error setting up enhanced logging: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static final ExecutorService GENERAL_POOL = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() - 1),
            new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "iptv-worker-" + threadNumber.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            }
    );

    // Dedicated pools for specific tasks
    private static final ScheduledExecutorService MONITORING_POOL = Executors.newScheduledThreadPool(2);
    private static final ExecutorService STREAM_VERIFICATION_POOL = Executors.newFixedThreadPool(3);

    // Shutdown hook to clean up threads
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shutdownPool(GENERAL_POOL, "General Pool");
            shutdownPool(MONITORING_POOL, "Monitoring Pool");
            shutdownPool(STREAM_VERIFICATION_POOL, "Stream Verification Pool");
        }));
    }

    private static void shutdownPool(ExecutorService pool, String poolName) {
        try {
            LOGGER.info("Shutting down " + poolName);
            pool.shutdown();
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warning(poolName + " did not terminate in time, forcing shutdown");
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            LOGGER.warning("Interrupted while shutting down " + poolName);
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // Methods to submit tasks to appropriate pools
    public static Future<?> submitTask(Runnable task) {
        return GENERAL_POOL.submit(task);
    }

    public static ScheduledFuture<?> scheduleTask(Runnable task, long delay, TimeUnit unit) {
        return MONITORING_POOL.schedule(task, delay, unit);
    }

    public static ScheduledFuture<?> scheduleRepeatingTask(Runnable task, long initialDelay, long period, TimeUnit unit) {
        return MONITORING_POOL.scheduleAtFixedRate(task, initialDelay, period, unit);
    }

    public static Future<?> submitStreamVerification(Runnable task) {
        return STREAM_VERIFICATION_POOL.submit(task);
    }
}