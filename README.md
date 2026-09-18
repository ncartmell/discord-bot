# discord-bot

A Discord bot written in Java with [JDA](https://github.com/discord-jda/JDA), plus a
small client for the [Bungie](https://bungie-net.github.io/) API. Written to learn the
JDA library and to have something to point Destiny 2 questions at.

## What's in it

- Slash commands registered at startup, including moderation commands gated behind
  Discord's own permission model (`BAN_MEMBERS` rather than a hand-rolled check)
- Button interactions with a confirm step, so destructive actions need two clicks
- A background thread for recurring work, kept off the event thread
- A Bungie API client that looks up item data from the Destiny manifest

## Configuration

No credentials are committed. Copy the example file and fill in your own:

```sh
cp .env.example .env
```

| Variable | Where to get it |
| --- | --- |
| `DISCORD_BOT_TOKEN` | Discord Developer Portal → your application → Bot → Token |
| `BUNGIE_API_KEY` | https://www.bungie.net/en/Application |

`.env` is gitignored. Real environment variables take precedence over it, so in a
deployed environment you can skip the file entirely.

Missing configuration fails at startup with a message naming the variable, rather
than failing later on first use.

## Running it

```sh
mvn compile exec:java -Dexec.mainClass=org.example.DiscordBot
```
