# discord-bot

A Discord bot written in Java with [JDA](https://github.com/discord-jda/JDA), plus a
small client for the [Bungie](https://bungie-net.github.io/) API. Written to learn the
JDA library and to have something to point Destiny 2 questions at.

## Commands

| Command | What it does |
| --- | --- |
| `/item <hash>` | Look up a Destiny item by its manifest hash |
| `/weekly` | The milestones currently active this week |
| `/news` | The latest articles from Bungie.net |
| `/profile <name>` | Lifetime PvE stats for a Bungie name, e.g. `Guardian#1234` |
| `/watch` | Announce the weekly rotation in this channel when it changes |
| `/ban`, `/say`, `/leave`, `/prune` | Moderation and utility commands |

## Notes on the Bungie API

**Everything here works with an API key alone.** Anything under `/Destiny2/Actions/` —
equipping items, transferring, loadouts — needs a full OAuth flow, which this does not
implement.

**Item lookup takes a hash rather than a name.** Bungie documents a search endpoint at
`/Destiny2/Armory/Search/{type}/{term}/`, but it returns `NotFound` for every query and
appears to have been retired without the docs catching up. The alternative is the item
definition component of the manifest, which is roughly 200 MB — too much for a bot this
size to hold. Item hashes are visible in the URL of any item page on light.gg or Bungie's
own Armory.

**Definitions are cached in memory.** Most of the API identifies things by hash, so
rendering a milestone list means a lookup per hash. `ManifestCache` keeps them for the
life of the process, since definitions only change when the game patches.

**Lookups can return success with no body.** A hash that no longer exists comes back as
`ErrorCode 1, "Ok"` with no `Response` at all rather than an error, so the client treats a
missing payload as a failure.

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
