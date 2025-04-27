# IPTV Discord Bot

A powerful Discord bot that enables streaming IPTV channels to Discord voice channels through OBS Studio and FFmpeg.

![Discord Bot Status](https://img.shields.io/badge/Discord-Bot-blue)
![Java](https://img.shields.io/badge/Java-11%2B-orange)

## Features

- **Stream IPTV Channels** directly into Discord voice channels
- **Support for M3U/M3U8 Playlists** with robust redirect handling
- **Channel Search** to easily find content across large playlists
- **OBS Integration** with automatic Virtual Camera setup
- **Auto-Recovery** for stream interruptions
- **User Authorization** system to control access
- **Server-Specific** configuration

## How It Works

The bot works as a bridge between IPTV sources and Discord:

1. Parses IPTV playlists to extract channel information
2. Uses FFmpeg to capture the selected stream
3. Controls OBS Studio through WebSocket API to configure and display the stream
4. Sets up OBS Virtual Camera for broadcasting
5. Allows users to share the stream to everyone in a Discord voice channel

## Requirements

- Java 11 or higher
- FFmpeg installed and available in system PATH
- OBS Studio with WebSocket plugin enabled
- Discord bot token
- Authorized Discord server and users

## Commands

| Command | Description |
|---------|-------------|
| `/start [playlist] [channel]` | Start streaming a channel from the specified playlist |
| `/stop` | Stop the current stream |
| `/list [playlist]` | List available channels in the playlist |
| `/cleanup` | Clean up all old sources from OBS |

## Setup

1. Download the latest release or build from source
2. Create a `config.properties` file with the following:
   ```properties
   discord.token=YOUR_BOT_TOKEN_HERE
   discord.authorized_guilds=GUILD_ID_1,GUILD_ID_2
   discord.authorized_users=USER_ID_1,USER_ID_2
   ```
3. Make sure OBS Studio is running with WebSocket plugin enabled
4. Run the bot with `java -jar iptv-discord-bot.jar`
5. Invite the bot to your server with appropriate permissions
6. Use the commands in an authorized channel

## Streaming Guide

1. Use `/list [playlist_url]` to see available channels
2. Join a Discord voice channel
3. Use `/start [playlist_url] [channel_name]` to begin streaming
4. Share your screen in Discord and select "OBS Virtual Camera"
5. Everyone in the voice channel will now see the stream
6. When finished, use `/stop` to end the stream

## Building from Source

```bash
git clone https://github.com/yourusername/iptv-discord-bot.git
cd iptv-discord-bot
mvn clean package
```

The output JAR file will be in the `target` directory.

## Troubleshooting

- Ensure OBS Studio is running before starting the bot
- Verify FFmpeg is properly installed and in your system PATH
- Check that the WebSocket plugin is correctly configured in OBS
- Make sure the Discord bot has appropriate permissions

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Disclaimer

This tool is designed for personal use with legally obtained IPTV sources. The developers do not condone piracy or illegal streaming. Users are responsible for ensuring compliance with applicable laws and regulations regarding content access and distribution.
