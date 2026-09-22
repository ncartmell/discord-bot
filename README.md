# discord-bot

A Discord bot written in Java with [JDA](https://github.com/discord-jda/JDA), plus a
small client for the [Bungie](https://bungie-net.github.io/) API. Written to learn the
JDA library and to have something to point Destiny 2 questions at — and, latterly, to
act as a ghost: link an account, see what you're playing, and put gear on.

## Commands

### Lookups — API key only

| Command | What it does |
| --- | --- |
| `/item <name>` | Look up a Destiny item by name (or hash) |
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
| `/loadout save <name>` | Store what you're wearing: items, perks, mods, subclass config |
| `/loadout show <name>` | Print the set in full — every item, type and plug |
| `/loadout list` / `delete` | Manage saved sets |
| `/artifact` | Your seasonal artifact and which perks are active (read-only) |
| `/equip <name>` | Put a set on — or queue it until you're next in orbit |
| `!kingsfall` | The same thing, if prefix commands are enabled |
| `!loadout` / `!loadout <name>` | List sets, or print one |
| `!set <name>` | Save what you're wearing |
| `!equip [name]` | Equip by name, or from the activity when unnamed |
| `!map <name>`, `!activity`, `!artifact` | The prefix forms of the above |
| `/map <loadout> [activity]` | Bind a set to the activity you're in, or one named outright |
| `/unmap` | Remove that binding |
| `/activityloadout` | Equip whatever is mapped to the activity you're in |
| `/autoequip on\|off` | Whether the bot acts between activities |
| `/snapshot <1-20>` | Save current gear into an in-game loadout slot |

### Moderation

`/ban`, `/say`, `/leave`, `/prune`.

## What a set covers

A saved set is a list of **item instance ids** plus the state of every visible socket on
each one, so it restores more than which guns you had:

- **Weapons and armour**, by specific instance — the exact roll, not whichever copy the
  game picks.
- **The subclass**, which is an ordinary instanced item and so was always covered.
- **Subclass configuration** — super, class ability, movement, melee, grenade, aspects and
  fragments. These are sockets on the subclass item.
- **Armour mods, weapon perks, shaders and ornaments** — also sockets.
- **Ghost, sparrow, ship and emblem**, as plain equips.

Restoring only writes sockets whose current plug differs from the saved one, so the number
of calls is proportional to what actually changed rather than to the size of the set. It
also means unchangeable sockets are skipped for free: one you cannot alter already matches.

**The artifact is read-only.** `/artifact` shows the seasonal artifact, your power bonus and
which perks are active, but it cannot set them. There is no action endpoint for the artifact
anywhere in the API, and its perks are progression state rather than sockets on an instanced
item, so the plug endpoints have nothing to address. That has to be done in game.

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

**Sockets are writable, within limits.** `InsertSocketPlugFree` needs only
`MoveEquipDestinyItems` — the scope the bot already has — and is documented as available to
third-party apps for "free and reversible" socket actions: perks, armour mods, shaders,
ornaments. Subclass abilities, aspects and fragments are sockets too, so they come along
with it. The other variant, `InsertSocketPlug`, covers plugs with side effects and needs
`AdvancedWriteActions`, which Bungie grants case by case — out of reach here.

It takes one plug per call and carries the same location restriction as equipping.

**Skip sockets marked `isVisible: false`.** On armour those hold stat rolls and other
internals that are not player-changeable, so capturing them would only produce writes
guaranteed to fail.

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

**A transfer in needs a free slot.** Each character gear bucket is ten slots
(`DestinyInventoryBucketDefinition.itemCount`), and that ten *includes* the equipped item
— so a full kinetic slot is nine in the inventory plus the one in your hands. Transferring
into a full bucket is refused with `1642 DestinyNoRoomInDestination`, so the bot counts
occupancy from `characterInventories` plus `characterEquipment` first and pushes something
to the vault when a bucket is already at capacity. It only evicts from the inventory, never
what is equipped, and never anything belonging to the set being applied. Whatever it moves
is named in the reply.

The vault is a bucket too (`138197802`, 1300 slots), so that is checked before evicting —
otherwise making room would just fail one step later.

**The OAuth membership id is not the Destiny one.** The token response carries a
Bungie.net membership; `GetLinkedProfiles` turns it into the platform membership the
Destiny endpoints want.

**There is no working item search, so the bot builds its own index.** Bungie documents a
search endpoint at `/Destiny2/Armory/Search/{type}/{term}/`, but it returns `NotFound` for
every query and appears to have been retired without the docs catching up.

The manifest fills the gap. Its tables are published as per-language JSON files listed under
`jsonWorldComponentContentPaths`, so you can take just the one you need rather than the whole
thing. `DestinyInventoryItemDefinition` is about 190 MB uncompressed across ~39,000 entries —
far too much to hold as a parsed tree, which is why an earlier version of this README said it
was out of reach. It is not: the names alone are under a megabyte. `Manifest` streams the
download and keeps only the fields that get displayed, discarding each entry's remaining
hundred-odd properties as it goes.

Measured on a cold start: **1.4 seconds, about 10 MB retained**, for 36,687 items and 3,744
activities. It loads on a background thread, so commands work immediately and fall back to
single-hash HTTP lookups until it arrives.

That is what makes names usable throughout — `/item Gjallarhorn`, `/map kingsfall` — and it
is what makes the socket features practical at all. A set with twelve items and sixty plugs
would otherwise mean seventy-odd HTTP round trips to print one message.

**Binding by name binds every variant.** A raid exists as several activity hashes — King's
Fall resolves to five, across Standard, Normal, Master and Expert — and binding a set to the
name covers all of them, which is almost always what is meant.

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
