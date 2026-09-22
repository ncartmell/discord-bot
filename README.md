# discord-bot

A Discord bot written in Java with [JDA](https://github.com/discord-jda/JDA), plus a
small client for the [Bungie](https://bungie-net.github.io/) API. Written to learn the
JDA library and to have something to point Destiny 2 questions at — and, latterly, to
act as a ghost: link an account, see what you're playing, and put gear on.

## Commands

### Lookups — API key only

| Command | What it does |
| --- | --- |
| `/item <hash>` | Look up a Destiny item by its manifest hash |
| `/weekly` | The milestones currently active this week |
| `/news` | The latest articles from Bungie.net |
| `/profile <name>` | Lifetime PvE stats for a Bungie name, e.g. `Guardian#1234` |
| `/watch` | Announce the weekly rotation in this channel when it changes |

### Your account — needs OAuth

| Command | What it does |
| --- | --- |
| `/link` | Start the Bungie authorisation, then `/link code:<code>` to finish |
| `/unlink` | Remove the stored tokens. Saved loadouts are kept |
| `/activity` | What you're in right now, and whether a set is mapped to it |
| `/loadout save <name>` | Store what you're wearing, by item instance id |
| `/loadout list` / `show` / `delete` | Manage saved sets |
| `/equip <name>` | Put a set on — or queue it until you're next in orbit |
| `!kingsfall` | The same thing, if prefix commands are enabled |
| `/map <loadout>` | Bind a set to the activity you're currently in |
| `/unmap` | Remove that binding |
| `/activityloadout` | Equip whatever is mapped to the activity you're in |
| `/autoequip on\|off` | Whether the bot acts between activities |
| `/snapshot <1-20>` | Save current gear into an in-game loadout slot |

### Moderation

`/ban`, `/say`, `/leave`, `/prune`.

## The two constraints that shape all of this

Both come straight from the endpoint documentation, and both are worth knowing before
designing anything around gear.

**You cannot equip while in an activity.** `EquipItem`, `EquipItems` and `EquipLoadout`
all require that you are "in a social space, in orbit, or offline", and refuse with error
`1671 DestinyCannotPerformActionAtThisLocation` otherwise.

This makes the obvious design — read the activity, dress for it — self-defeating: by the
time the bot can see King's Fall, the game has stopped accepting equips. So the split is:

- **`/equip <name>` and `!kingsfall` queue when refused.** Naming a set says what you are
  heading into, so the request survives and is applied the moment you reach orbit.
- **`/activityloadout` never queues.** The activity you are in is the entire input, and
  applying it on your return to orbit would dress you for a raid you have just finished.
  It equips if it can and explains itself if it cannot.
- **Entering a mapped activity without its set on gets you a DM.** The bot cannot equip
  and will not pretend to queue, so it tells you while going back to orbit is still cheap.

**Loadout slots cannot be filled remotely.** The only write path into one of the game's 20
slots is `SnapshotLoadout`, which captures what you are *already wearing*
(`DestinyLoadoutUpdateActionRequest` carries `colorHash`, `iconHash`, `nameHash` and an
index — no items). There is no endpoint that puts chosen items into a slot, so a set
cannot be staged in-game ahead of time, and to snapshot a set you must first equip it,
which brings back the constraint above.

That is why the bot keeps its own sets as lists of **item instance ids** rather than
driving the in-game slots. There is no limit on how many, and a set can name any specific
copy of an item — not just whichever roll the game would pick.

`SnapshotLoadout` is the one gear action with no documented location restriction, so
`/snapshot` does work mid-activity. Handy for keeping whatever you finished a raid in.

## Other notes on the Bungie API

**Reading needs no OAuth.** Characters, equipment, character inventories, the vault and
the current activity are all readable with the API key alone. Only `/Destiny2/Actions/`
acts on someone's behalf. Authenticated calls want `X-API-Key` *and* `Authorization:
Bearer` — the bearer alone is rejected.

**The OAuth scope is fixed at registration.** Bungie rejects a `scope` parameter in the
authorize request; the application is granted "Move or equip Destiny gear" on its
settings page instead. Register as a **confidential** client — only those are issued
refresh tokens, and a public client would mean re-linking every hour. Access tokens last
an hour, refresh tokens 90 days.

**Vault items are silently ignored.** `EquipItems` documents that "any items not found on
your character will be ignored" — not an error, just absent from the results. So the bot
locates every item first and transfers anything that is in the vault or on another
character before equipping. Moving between characters goes via the vault; there is no
direct transfer.

**The OAuth membership id is not the Destiny one.** The token response carries a
Bungie.net membership; `GetLinkedProfiles` turns it into the platform membership the
Destiny endpoints want.

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

**Checkpoints are not in the API.** There is no component exposing which encounter a
fireteam is on, so a loadout cannot be bound to one. Binding is per activity hash, and a
raid's normal and master versions are separate hashes — which is usually what you want.

## Configuration

No credentials are committed. Copy the example file and fill in your own:

```sh
cp .env.example .env
```

| Variable | Where to get it |
| --- | --- |
| `DISCORD_BOT_TOKEN` | Discord Developer Portal → your application → Bot → Token |
| `BUNGIE_API_KEY` | https://www.bungie.net/en/Application |
| `BUNGIE_CLIENT_ID` | Same page, once the application is registered as confidential |
| `BUNGIE_CLIENT_SECRET` | Same page. Required for refresh tokens |
| `ENABLE_PREFIX_COMMANDS` | Optional. `true` enables `!name`; needs the MESSAGE CONTENT intent |
| `BOT_STATE_FILE` | Optional. Defaults to `./state.json` |

`.env` is gitignored. Real environment variables take precedence over it, so in a
deployed environment you can skip the file entirely.

Missing configuration fails at startup with a message naming the variable, rather
than failing later on first use.

### The OAuth redirect

Bungie sends the browser to the redirect URL registered on the application, so the flow
needs a page there. It does not need a server: the page only has to show the user the
`code` from their own address bar so they can paste it back into `/link`. Any static host
will do — [`d2botkey.html`](https://ncartmell.co.uk/d2botkey) is the one this bot uses.

### State

`state.json` holds linked accounts, saved loadouts, activity mappings and **OAuth refresh
tokens in plain text**. It is gitignored, and it is a credential file: anyone who can read
it can act on the linked account for up to 90 days.

## Running it

```sh
mvn compile exec:java -Dexec.mainClass=org.example.DiscordBot
```
