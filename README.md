IPTV Discord Bot
A Discord bot that allows streaming IPTV channels to Discord voice channels using OBS Studio and FFmpeg.
Features

Stream IPTV channels directly to Discord voice channels
Supports M3U/M3U8 playlists with proper redirect handling
Easy channel selection with search functionality
Automatic OBS Virtual Camera integration
Robust stream management with auto-recovery

How It Works
The bot uses FFmpeg to capture IPTV streams, pipes them through OBS Studio using WebSocket controls, and then makes them available through OBS Virtual Camera. Users can then share their screen in Discord to broadcast the stream to everyone in a voice channel.
Requirements

Java 11 or higher
FFmpeg installed and in system PATH
OBS Studio with WebSocket plugin
Discord bot token

Commands

/start [playlist] [channel] - Start streaming a channel
/stop - Stop the current stream
/list [playlist] - List available channels
/cleanup - Clean up OBS sources

Setup

Edit the config.properties file with your Discord bot token and authorized users/servers
Make sure OBS Studio is running with WebSocket enabled
Run the bot with java -jar iptv-discord-bot.jar
Invite the bot to your server and use the commands in an authorized channel

License
MIT License
