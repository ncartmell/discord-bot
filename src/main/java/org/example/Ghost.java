package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.awt.Color;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Destiny assistant: linking an account, reading what you are doing, and putting
 * gear on when the game will allow it.
 *
 * <p>Two constraints from the API shape almost every decision here, and both are worth
 * stating because they are not obvious until something fails:
 *
 * <ol>
 *   <li><strong>You cannot equip while in an activity.</strong> The equip endpoints require
 *       being "in a social space, in orbit, or offline", and refuse with error 1671 otherwise.
 *       So a command that reads your activity and dresses you for it is self-defeating — by
 *       the time the bot can see King's Fall, the game has stopped accepting equips. Named
 *       loadout commands therefore queue when blocked, and apply the moment you reach orbit.</li>
 *   <li><strong>Loadout slots cannot be filled remotely.</strong> The only write path into one
 *       of the game's 20 slots is {@code SnapshotLoadout}, which captures what you are already
 *       wearing. There is no endpoint that puts chosen items into a slot, so a set cannot be
 *       staged in-game ahead of time. The bot keeps its own sets, by item instance id, with
 *       no limit on how many.</li>
 * </ol>
 */
final class Ghost {

    private static final Color ACCENT = new Color(0x00A8E1);
    private static final String ITEM_DEFINITION = "DestinyInventoryItemDefinition";
    private static final String ACTIVITY_DEFINITION = "DestinyActivityDefinition";

    /** Profile components: vault, characters, character inventories, equipment, activities. */
    private static final String INVENTORY_COMPONENTS = "102,200,201,205";
    /** Equipment plus the socket state that holds perks, mods, shaders and subclass choices. */
    private static final String EQUIPMENT_COMPONENTS = "205,305";
    /** The subclass bucket, called out separately when printing a set. */
    private static final long SUBCLASS_BUCKET = 3284755031L;
    /** The postmaster, which the game calls "Lost Items". 21 slots, per character. */
    private static final long POSTMASTER_BUCKET = 215593132L;
    /** Where bounties and quest steps live. The game calls it Quests. */
    private static final long QUESTS_BUCKET = 1345459588L;
    /** Warn from here up, since the postmaster starts dropping things once it overflows. */
    private static final int POSTMASTER_WARN_AT = 17;
    /** A ceiling on plug writes per equip, so a wildly stale set cannot run away. */
    private static final int MAX_PLUG_WRITES = 60;
    private static final String ACTIVITY_COMPONENTS = "200,204";

    /** Bungie refuses the action because the player is in an activity. */
    private static final int AT_THIS_LOCATION = 1671;
    /** The character is not logged in, so the game cannot be told to do anything. */
    private static final int ONLY_IN_GAME = 1655;

    /** An access token is renewed this many seconds before it actually lapses. */
    private static final long RENEW_MARGIN = 120;

    private final BungieClient client;
    private final Manifest manifest;
    private final Store store;

    /** Discord user id to the state nonce issued by the most recent {@code /link}. */
    private final Map<String, String> pendingLinks = new ConcurrentHashMap<>();

    private static final SecureRandom RANDOM = new SecureRandom();

    Ghost(BungieClient client, Manifest manifest, Store store) {
        this.client = client;
        this.manifest = manifest;
        this.store = store;
    }

    // ---------------------------------------------------------------- linking

    /** Issues an authorization URL, remembering the state so the callback can be tied back. */
    MessageEmbed beginLink(String discordId) {
        byte[] nonce = new byte[24];
        RANDOM.nextBytes(nonce);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        pendingLinks.put(discordId, state);

        return new EmbedBuilder()
                .setTitle("Link your Destiny account")
                .setColor(ACCENT)
                .setDescription("[Approve the bot on Bungie.net](" + OAuth.authorizeUrl(state) + ")"
                        + "\n\nYou'll land on a page showing a code. Run `/link code:<the code>`"
                        + " to finish.\n\nThe code is single-use and expires quickly, so do it"
                        + " straight away.")
                .setFooter("Only you can see this message")
                .build();
    }

    /** Exchanges the pasted code and resolves which Destiny account it belongs to. */
    MessageEmbed completeLink(String discordId, String code) throws IOException {
        if (pendingLinks.remove(discordId) == null) {
            return Destiny.error("Run `/link` first to get an authorisation link.");
        }

        OAuth.Tokens tokens = OAuth.exchange(code.trim());
        if (tokens.membershipId == null) {
            return Destiny.error("Bungie didn't say which account that was. Try `/link` again.");
        }

        JsonObject linked = client.linkedProfiles(tokens.membershipId);
        JsonArray profiles = linked.getAsJsonArray("profiles");
        if (profiles == null || profiles.isEmpty()) {
            return Destiny.error("That Bungie account has no Destiny 2 characters attached.");
        }

        // Pick whichever profile played most recently — that is the one they mean.
        JsonObject best = null;
        String bestPlayed = "";
        for (JsonElement element : profiles) {
            JsonObject profile = element.getAsJsonObject();
            String played = profile.has("dateLastPlayed") ? profile.get("dateLastPlayed").getAsString() : "";
            if (best == null || played.compareTo(bestPlayed) > 0) {
                best = profile;
                bestPlayed = played;
            }
        }

        Store.User user = store.user(discordId);
        user.membershipType = best.get("membershipType").getAsInt();
        user.membershipId = best.get("membershipId").getAsString();
        user.accessToken = tokens.accessToken;
        user.accessTokenExpiresAt = tokens.accessExpiresAt;
        user.refreshToken = tokens.refreshToken;
        user.refreshTokenExpiresAt = tokens.refreshExpiresAt;

        // Default to the character played most recently, which is almost always the one wanted.
        JsonObject characters = child(client.profile(user.membershipType, user.membershipId, "200",
                tokens.accessToken), "characters", "data");
        String chosen = null;
        String chosenPlayed = "";
        for (String characterId : characters.keySet()) {
            JsonObject character = characters.getAsJsonObject(characterId);
            String played = string(character, "dateLastPlayed");
            if (chosen == null || played.compareTo(chosenPlayed) > 0) {
                chosen = characterId;
                chosenPlayed = played;
            }
        }
        user.characterId = chosen;
        store.save();

        String name = best.has("displayName") ? best.get("displayName").getAsString() : "your account";
        return new EmbedBuilder()
                .setTitle("Linked")
                .setColor(ACCENT)
                .setDescription("Connected to **" + name + "** on " + platform(user.membershipType) + ".")
                .addField("Character", characterSummary(characters, user.characterId), false)
                .addField("Next", "`/loadout save <name>` while wearing a set you want to keep.", false)
                .build();
    }

    MessageEmbed unlink(String discordId) {
        Store.User user = store.user(discordId);
        if (!user.isLinked()) {
            return Destiny.error("Nothing linked.");
        }
        // Loadouts are kept — they are just item ids, and relinking should not lose them.
        user.accessToken = null;
        user.refreshToken = null;
        user.membershipId = null;
        user.characterId = null;
        user.pendingLoadout = null;
        store.save();
        return simple("Unlinked", "Tokens removed. Your saved loadouts are still here.");
    }

    // ---------------------------------------------------------------- tokens

    /**
     * Returns a usable access token, refreshing it first if it is close to lapsing.
     *
     * <p>Access tokens last an hour and refresh tokens 90 days, so in practice this
     * refreshes on most calls and the user re-links roughly never.
     */
    /** A valid access token for a linked user, for the features that live outside this class. */
    String accessToken(Store.User user) throws IOException {
        return token(user);
    }

    /**
     * A token if one can be had, for reads that work either way.
     *
     * <p>Keeps a feature usable on public components when an authorisation has lapsed,
     * rather than failing on a token it did not strictly need.
     */
    String accessTokenOrNull(Store.User user) {
        return tokenOrNull(user);
    }

    private String token(Store.User user) throws IOException {
        long now = System.currentTimeMillis() / 1000;
        if (user.accessToken != null && user.accessTokenExpiresAt - RENEW_MARGIN > now) {
            return user.accessToken;
        }
        if (user.refreshToken == null || user.refreshTokenExpiresAt <= now) {
            throw new IOException("Your Bungie authorisation has expired — run `/link` again.");
        }

        OAuth.Tokens tokens;
        try {
            tokens = OAuth.refresh(user.refreshToken);
        } catch (IOException e) {
            // Bungie's own message here is about base-64 padding and similar, which tells
            // nobody anything useful. What matters is that the link needs redoing.
            throw new IOException("Couldn't renew your Bungie authorisation — run `/link` again."
                    + "\n\n_" + e.getMessage() + "_");
        }
        user.accessToken = tokens.accessToken;
        user.accessTokenExpiresAt = tokens.accessExpiresAt;
        user.refreshToken = tokens.refreshToken;
        user.refreshTokenExpiresAt = tokens.refreshExpiresAt;
        store.save();
        return user.accessToken;
    }

    /**
     * A token if one can be had, or null.
     *
     * <p>Used by the reads that work either way. Public components still come back without
     * it, so a lapsed authorisation degrades those to what anyone could see rather than
     * failing outright — which matters for the watcher, since it polls continuously.
     */
    private String tokenOrNull(Store.User user) {
        try {
            return token(user);
        } catch (IOException | RuntimeException e) {
            // RuntimeException matters as much as IOException here: a missing OAuth client id
            // surfaces as IllegalStateException from Config, and a read that does not need a
            // token should not fail because the bot has no OAuth configuration at all.
            return null;
        }
    }

    // ---------------------------------------------------------------- activity

    /** The activity hash the character is currently in, or 0 if they are not in one. */
    long currentActivityHash(Store.User user) throws IOException {
        JsonObject activities = child(
                client.profile(user.membershipType, user.membershipId, ACTIVITY_COMPONENTS,
                        tokenOrNull(user)),
                "characterActivities", "data");
        if (activities == null || user.characterId == null || !activities.has(user.characterId)) {
            return 0;
        }
        JsonObject mine = activities.getAsJsonObject(user.characterId);
        return mine.has("currentActivityHash") ? mine.get("currentActivityHash").getAsLong() : 0;
    }

    MessageEmbed activity(String discordId) throws IOException {
        Store.User user = requireLinked(discordId);
        long hash = currentActivityHash(user);
        if (hash == 0) {
            return simple("Not in an activity", "You're in orbit, on the character select, or offline."
                    + "\n\nThis is when gear can actually be changed.");
        }

        String name = activityName(hash);
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Currently playing")
                .setColor(ACCENT)
                .setDescription("**" + name + "**")
                .addField("Activity hash", "`" + hash + "`", true);

        String mapped = user.activityMap.get(String.valueOf(hash));
        embed.addField("Mapped loadout", mapped == null ? "none — `/map` to set one" : "`" + mapped + "`", true);
        return embed.build();
    }

    private String activityName(long hash) {
        return manifest.activityName(hash);
    }

    // ---------------------------------------------------------------- loadouts

    /** Saves whatever the character is wearing right now, by item instance id. */
    MessageEmbed saveLoadout(String discordId, String rawName) throws IOException {
        Store.User user = requireLinked(discordId);
        String name = normalise(rawName);
        if (name.isEmpty()) {
            return Destiny.error("Give the loadout a name.");
        }

        JsonObject profile = client.profile(user.membershipType, user.membershipId,
                EQUIPMENT_COMPONENTS, token(user));
        JsonObject equipment = child(profile, "characterEquipment", "data");
        if (equipment == null || user.characterId == null || !equipment.has(user.characterId)) {
            return Destiny.error("Couldn't read your equipment. Is the character still on the account?");
        }
        JsonObject socketData = child(profile, "itemComponents", "sockets", "data");

        List<Store.Item> items = new ArrayList<>();
        for (JsonElement element : equipment.getAsJsonObject(user.characterId).getAsJsonArray("items")) {
            JsonObject item = element.getAsJsonObject();
            // Only instanced items can be equipped back by id; emblems and the like still are.
            if (!item.has("itemInstanceId")) {
                continue;
            }
            String instanceId = item.get("itemInstanceId").getAsString();
            Store.Item saved = new Store.Item(
                    instanceId,
                    item.get("itemHash").getAsLong(),
                    item.has("bucketHash") ? item.get("bucketHash").getAsLong() : 0);
            saved.plugs = readPlugs(socketData, instanceId);
            items.add(saved);
        }

        if (items.isEmpty()) {
            return Destiny.error("Nothing equipped that can be saved.");
        }

        boolean replaced = user.loadouts.containsKey(name);
        user.loadouts.put(name, items);
        store.save();

        int sockets = 0;
        for (Store.Item item : items) {
            sockets += item.plugs == null ? 0 : item.plugs.size();
        }

        return simple(replaced ? "Updated " + name : "Saved " + name,
                items.size() + " items stored by instance id, with " + sockets
                        + " perks, mods and subclass choices."
                        + "\n\n`/loadout show name:" + name + "` to see it, `/equip name:" + name
                        + "` to put it back on, or `/map loadout:" + name
                        + "` while in an activity to bind it there.");
    }

    MessageEmbed listLoadouts(String discordId) {
        Store.User user = store.user(discordId);
        if (user.loadouts.isEmpty()) {
            return simple("No loadouts", "Wear a set and run `/loadout save <name>`.");
        }

        // Show which activities point at each one, so the mapping is visible in one place.
        Map<String, List<String>> reverse = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : user.activityMap.entrySet()) {
            reverse.computeIfAbsent(entry.getValue(), k -> new ArrayList<>())
                    .add(activityName(Long.parseLong(entry.getKey())));
        }

        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, List<Store.Item>> entry : user.loadouts.entrySet()) {
            body.append("**").append(entry.getKey()).append("** — ")
                    .append(entry.getValue().size()).append(" items");
            List<String> activities = reverse.get(entry.getKey());
            if (activities != null) {
                body.append("\n↳ ").append(String.join(", ", activities));
            }
            body.append('\n');
        }

        return new EmbedBuilder()
                .setTitle("Saved loadouts")
                .setColor(ACCENT)
                .setDescription(body.toString().trim())
                .setFooter(user.autoEquip ? "Auto-equip is on" : "Auto-equip is off")
                .build();
    }

    /**
     * Prints a set in full: every item with its type, and what is plugged into it.
     *
     * <p>Grouped by slot rather than listed flat, because a loadout is read by slot — and
     * the subclass is pulled out on its own, since for that item the plugs (super,
     * abilities, aspects, fragments) are the entire point rather than a detail.
     */
    MessageEmbed showLoadout(String discordId, String rawName) {
        Store.User user = store.user(discordId);
        String name = normalise(rawName);
        List<Store.Item> items = user.loadouts.get(name);
        if (items == null) {
            return Destiny.error("No loadout called `" + name + "`.");
        }

        int plugCount = 0;
        for (Store.Item item : items) {
            plugCount += item.plugs == null ? 0 : item.plugs.size();
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(name)
                .setColor(ACCENT)
                .setDescription(items.size() + " items" + (plugCount == 0 ? ""
                        : " · " + plugCount + " perks, mods and subclass choices"));

        // Subclass first: it is the one item whose configuration people actually read.
        for (Store.Item item : items) {
            if (item.bucketHash != SUBCLASS_BUCKET) {
                continue;
            }
            List<String> config = plugNames(item);
            embed.addField(describeItem(item),
                    config.isEmpty() ? "No configuration saved" : String.join("\n", config), false);
        }

        // Then the rest, in slot order, with anything plugged into them underneath.
        for (long bucket : SLOT_ORDER) {
            List<String> lines = new ArrayList<>();
            for (Store.Item item : items) {
                if (item.bucketHash != bucket) {
                    continue;
                }
                lines.add("**" + manifest.itemName(item.itemHash) + "** — " + itemType(item));
                List<String> plugs = plugNames(item);
                if (!plugs.isEmpty()) {
                    lines.add("↳ " + String.join(", ", plugs));
                }
            }
            if (!lines.isEmpty()) {
                embed.addField(manifest.bucketName(bucket), join(lines), false);
            }
        }

        // Anything in a slot not covered above — ghost, sparrow, ship, emblem.
        List<String> other = new ArrayList<>();
        for (Store.Item item : items) {
            if (item.bucketHash == SUBCLASS_BUCKET || contains(SLOT_ORDER, item.bucketHash)) {
                continue;
            }
            other.add("**" + manifest.itemName(item.itemHash) + "** — " + itemType(item));
        }
        if (!other.isEmpty()) {
            embed.addField("Other", join(other), false);
        }

        String boundTo = String.join(", ", activitiesUsing(user, name));
        embed.setFooter(boundTo.isEmpty() ? "Not bound to an activity" : "Bound to " + boundTo);
        return embed.build();
    }

    /** Weapons, then armour, in the order the game shows them. */
    private static final long[] SLOT_ORDER = {
            1498876634L,  // Kinetic
            2465295065L,  // Energy
            953998645L,   // Power
            3448274439L,  // Helmet
            3551918588L,  // Gauntlets
            14239492L,    // Chest
            20886954L,    // Legs
            1585787867L,  // Class item
    };

    /** The readable names of whatever is plugged into an item, minus the empty sockets. */
    private List<String> plugNames(Store.Item item) {
        List<String> names = new ArrayList<>();
        if (item.plugs == null) {
            return names;
        }
        for (Long plugHash : item.plugs.values()) {
            String plug = manifest.itemName(plugHash);
            // Empty fragment slots and default shaders are noise in a printed set.
            if (plug.startsWith("Empty") || plug.startsWith("Default")) {
                continue;
            }
            names.add(plug);
        }
        return names;
    }

    private String itemType(Store.Item item) {
        Manifest.Item definition = manifest.item(item.itemHash);
        if (definition == null) {
            return "unknown type";
        }
        String detail = ((definition.tier() == null ? "" : definition.tier()) + " "
                + (definition.type() == null ? "" : definition.type())).trim();
        return detail.isEmpty() ? "unknown type" : detail;
    }

    /** The activity names a set is bound to. */
    private List<String> activitiesUsing(Store.User user, String name) {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, String> entry : user.activityMap.entrySet()) {
            if (!entry.getValue().equals(name)) {
                continue;
            }
            String activity = activityName(Long.parseLong(entry.getKey()));
            if (!names.contains(activity)) {
                names.add(activity);
            }
        }
        return names;
    }

    private static boolean contains(long[] values, long value) {
        for (long candidate : values) {
            if (candidate == value) {
                return true;
            }
        }
        return false;
    }

    /**
     * Bounties and quest steps, with how far along each one is.
     *
     * <p>Needs the token twice over: the pursuits sit in character inventories (201) and
     * their progress is an item component (301), and neither returns data for a profile
     * that is not the caller's own.
     */
    MessageEmbed bounties(String discordId) throws IOException {
        return pursuits(discordId, true);
    }

    /**
     * Quest steps, which share the Quests bucket with bounties but behave differently.
     *
     * <p>Worth separating: bounties expire at reset and are meant to be churned through,
     * while a quest step sits there until you finish it. Mixed into one list the bounties
     * bury the quests, which are the ones you actually forget about.
     */
    MessageEmbed quests(String discordId) throws IOException {
        return pursuits(discordId, false);
    }

    private MessageEmbed pursuits(String discordId, boolean wantBounties) throws IOException {
        Store.User user = requireLinked(discordId);
        JsonObject profile = client.profile(user.membershipType, user.membershipId, "201,301",
                token(user));

        JsonObject inventories = child(profile, "characterInventories", "data");
        if (inventories == null || !inventories.has(user.characterId)) {
            return Destiny.error("Couldn't read your inventory.");
        }
        JsonObject objectives = child(profile, "itemComponents", "objectives", "data");

        List<String> done = new ArrayList<>();
        List<String> active = new ArrayList<>();

        for (JsonElement element : inventories.getAsJsonObject(user.characterId).getAsJsonArray("items")) {
            JsonObject item = element.getAsJsonObject();
            if (!item.has("bucketHash") || item.get("bucketHash").getAsLong() != QUESTS_BUCKET) {
                continue;
            }
            long itemHash = item.get("itemHash").getAsLong();
            Manifest.Item pursuit = manifest.item(itemHash);
            // DestinyItemType separates the two: 26 is a bounty, 12 and 13 are quest steps.
            // Anything unrecognised is treated as a quest, since that list is the shorter one
            // and an unexplained omission is worse than an unexpected entry.
            boolean isBounty = pursuit != null && pursuit.isBounty();
            if (isBounty != wantBounties) {
                continue;
            }

            String name = manifest.itemName(itemHash);
            String instanceId = item.has("itemInstanceId") ? item.get("itemInstanceId").getAsString() : null;

            List<String> steps = new ArrayList<>();
            boolean complete = true;
            if (instanceId != null && objectives != null && objectives.has(instanceId)) {
                JsonArray list = objectives.getAsJsonObject(instanceId).getAsJsonArray("objectives");
                if (list != null) {
                    for (JsonElement objectiveElement : list) {
                        JsonObject objective = objectiveElement.getAsJsonObject();
                        if (objective.has("visible") && !objective.get("visible").getAsBoolean()) {
                            continue;
                        }
                        boolean stepDone = objective.has("complete")
                                && objective.get("complete").getAsBoolean();
                        complete &= stepDone;

                        int progress = objective.has("progress") ? objective.get("progress").getAsInt() : 0;
                        int target = objective.has("completionValue")
                                ? objective.get("completionValue").getAsInt() : 0;
                        Manifest.Objective definition =
                                manifest.objective(objective.get("objectiveHash").getAsLong());
                        String label = definition == null || definition.description().isBlank()
                                ? "Progress" : definition.description();
                        steps.add("   " + (stepDone ? "✓ " : "") + label
                                + (target > 0 ? " — " + Math.min(progress, target) + "/" + target : ""));
                    }
                }
            }
            if (steps.isEmpty()) {
                complete = false;
            }

            String entry = "**" + name + "**" + (steps.isEmpty() ? "" : "\n" + String.join("\n", steps));
            (complete ? done : active).add(entry);
        }

        String label = wantBounties ? "Bounties" : "Quests";
        if (active.isEmpty() && done.isEmpty()) {
            return simple(label, wantBounties
                    ? "No bounties. Pick some up from a vendor."
                    : "No quest steps in progress.");
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(label)
                .setColor(ACCENT)
                .setDescription((active.size() + done.size()) + " tracked, of "
                        + Math.max(1, manifest.bucketCapacity(QUESTS_BUCKET))
                        + " slots shared with " + (wantBounties ? "quests" : "bounties"));
        if (!done.isEmpty()) {
            embed.addField("Ready to hand in (" + done.size() + ")", join(done), false);
        }
        if (!active.isEmpty()) {
            embed.addField("In progress (" + active.size() + ")", join(active), false);
        }
        return embed.build();
    }

    /**
     * Who you are playing with right now.
     *
     * <p>The transitory component only exists while you are actually in game — Bungie drops
     * it the moment you stop — so an empty answer here means "not playing", not "alone".
     */
    MessageEmbed fireteam(String discordId) throws IOException {
        Store.User user = requireLinked(discordId);
        JsonObject transitory = child(client.profile(user.membershipType, user.membershipId,
                "1000", token(user)), "profileTransitoryData", "data");

        if (transitory == null) {
            return simple("Fireteam", "Nothing to show — this only reports while you're in game.");
        }

        List<String> members = new ArrayList<>();
        JsonArray party = transitory.getAsJsonArray("partyMembers");
        if (party != null) {
            for (JsonElement element : party) {
                JsonObject member = element.getAsJsonObject();
                members.add("• " + string(member, "displayName"));
            }
        }

        EmbedBuilder embed = new EmbedBuilder().setTitle("Fireteam").setColor(ACCENT);

        JsonObject current = transitory.has("currentActivity")
                && transitory.get("currentActivity").isJsonObject()
                ? transitory.getAsJsonObject("currentActivity") : null;
        if (current != null && current.has("startTime")) {
            embed.setDescription("In an activity since " + string(current, "startTime"));
        }

        embed.addField(members.isEmpty() ? "Nobody with you" : "Members (" + members.size() + ")",
                members.isEmpty() ? "Playing solo, or not in game." : join(members), false);

        JsonObject joinability = transitory.has("joinability")
                && transitory.get("joinability").isJsonObject()
                ? transitory.getAsJsonObject("joinability") : null;
        if (joinability != null && joinability.has("openSlots")) {
            int open = joinability.get("openSlots").getAsInt();
            embed.addField("Open slots", open <= 0 ? "Full" : String.valueOf(open), true);
        }

        if (transitory.has("lastOrbitedDestinationHash")) {
            long destination = transitory.get("lastOrbitedDestinationHash").getAsLong();
            if (destination != 0) {
                embed.addField("Last orbited", manifest.destinationName(destination), true);
            }
        }
        return embed.build();
    }

    /** Glimmer and the rest of what the game counts as currency. */
    MessageEmbed currencies(String discordId) throws IOException {
        Store.User user = requireLinked(discordId);
        JsonObject currencies = child(client.profile(user.membershipType, user.membershipId,
                "103", token(user)), "profileCurrencies", "data");

        if (currencies == null || !currencies.has("items")) {
            return Destiny.error("Couldn't read your currencies.");
        }

        List<String> lines = new ArrayList<>();
        for (JsonElement element : currencies.getAsJsonArray("items")) {
            JsonObject item = element.getAsJsonObject();
            int quantity = item.has("quantity") ? item.get("quantity").getAsInt() : 0;
            if (quantity <= 0) {
                continue;
            }
            lines.add("**" + manifest.itemName(item.get("itemHash").getAsLong()) + "** — "
                    + String.format(java.util.Locale.UK, "%,d", quantity));
        }

        if (lines.isEmpty()) {
            return simple("Currencies", "Nothing to report.");
        }
        return new EmbedBuilder()
                .setTitle("Currencies")
                .setColor(ACCENT)
                .setDescription(join(lines))
                .build();
    }

    /**
     * Locks or unlocks every item in a saved set.
     *
     * <p>The natural companion to sets built from instance ids: a set names one specific
     * roll, and nothing otherwise stops that roll being dismantled in a tidying session.
     */
    MessageEmbed lockSet(String discordId, String rawName, boolean locked) throws IOException {
        Store.User user = requireLinked(discordId);
        String name = normalise(rawName);
        List<Store.Item> items = user.loadouts.get(name);
        if (items == null) {
            return Destiny.error("No loadout called `" + name + "`.");
        }

        String accessToken = token(user);
        int changed = 0;
        List<String> failed = new ArrayList<>();
        for (Store.Item item : items) {
            try {
                client.setLockState(user.membershipType, user.characterId, item.instanceId,
                        locked, accessToken);
                changed++;
            } catch (BungieClient.BungieException e) {
                // Subclasses and other non-lockable things refuse; that is not a failure
                // worth shouting about, but an unexpected code is.
                if (e.code != 1640 && e.code != 1623) {
                    failed.add(itemName(item) + " — " + reason(e.code));
                }
            } catch (IOException e) {
                failed.add(itemName(item) + " — " + e.getMessage());
            }
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle((locked ? "Locked " : "Unlocked ") + name)
                .setColor(ACCENT)
                .setDescription(changed + " of " + items.size() + " items "
                        + (locked ? "locked." : "unlocked.")
                        + (locked ? "\n\nThey can't be dismantled until you unlock them." : ""));
        if (!failed.isEmpty()) {
            embed.addField("Skipped", join(failed), false);
        }
        return embed.build();
    }

    /** One thing waiting in the postmaster, in a form the interface can offer back. */
    record PostmasterItem(long itemHash, String instanceId, int quantity, String label,
                          boolean sideEffects) {
    }

    /** The printed postmaster plus what can be pulled out of it. */
    record PostmasterView(MessageEmbed embed, List<PostmasterItem> items) {
    }

    /**
     * Reads the linked character's postmaster.
     *
     * <p>Worth having as its own command because the postmaster holds 21 items and silently
     * drops the oldest once it is full, so the useful thing is to see it filling up before
     * that happens rather than afterwards.
     */
    PostmasterView postmaster(String discordId) throws IOException {
        Store.User user = requireLinked(discordId);
        // The postmaster lives in characterInventories, which is private — this needs the token.
        JsonObject profile = client.profile(user.membershipType, user.membershipId, "200,201,300",
                token(user));

        JsonObject inventories = child(profile, "characterInventories", "data");
        if (inventories == null || !inventories.has(user.characterId)) {
            return new PostmasterView(Destiny.error("Couldn't read your inventory."), List.of());
        }
        JsonObject instances = child(profile, "itemComponents", "instances", "data");

        List<PostmasterItem> waiting = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (JsonElement element : inventories.getAsJsonObject(user.characterId).getAsJsonArray("items")) {
            JsonObject item = element.getAsJsonObject();
            if (!item.has("bucketHash") || item.get("bucketHash").getAsLong() != POSTMASTER_BUCKET) {
                continue;
            }

            long itemHash = item.get("itemHash").getAsLong();
            String instanceId = item.has("itemInstanceId") ? item.get("itemInstanceId").getAsString() : null;
            int quantity = item.has("quantity") ? item.get("quantity").getAsInt() : 1;

            Manifest.Item definition = manifest.item(itemHash);
            String name = manifest.itemName(itemHash);
            String type = definition == null ? "" : ((definition.tier() == null ? "" : definition.tier())
                    + " " + (definition.type() == null ? "" : definition.type())).trim();
            boolean sideEffects = definition != null && definition.pullHasSideEffects();

            StringBuilder line = new StringBuilder("**" + name + "**");
            if (!type.isEmpty()) {
                line.append(" — ").append(type);
            }
            // Stacked things carry a quantity; gear carries a power level instead.
            if (quantity > 1) {
                line.append(" ×").append(quantity);
            }
            if (instanceId != null && instances != null && instances.has(instanceId)) {
                JsonObject instance = instances.getAsJsonObject(instanceId);
                if (instance.has("primaryStat") && instance.get("primaryStat").isJsonObject()) {
                    JsonObject primary = instance.getAsJsonObject("primaryStat");
                    if (primary.has("value")) {
                        line.append(" · ").append(primary.get("value").getAsInt());
                    }
                }
            }
            if (sideEffects) {
                line.append("  ⚠");
            }
            lines.add(line.toString());
            waiting.add(new PostmasterItem(itemHash, instanceId, quantity,
                    name + (type.isEmpty() ? "" : " — " + type), sideEffects));
        }

        int capacity = manifest.bucketCapacity(POSTMASTER_BUCKET);
        if (capacity <= 0) {
            capacity = 21;
        }

        if (waiting.isEmpty()) {
            return new PostmasterView(
                    simple("Postmaster", "Empty. Nothing waiting on this character."), List.of());
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Postmaster")
                .setColor(ACCENT)
                .setDescription(waiting.size() + " of " + capacity + " slots used")
                .addField("Waiting", join(lines), false);

        if (waiting.stream().anyMatch(PostmasterItem::sideEffects)) {
            embed.addField("⚠ marked items",
                    "Bungie flags these as pulls that could destroy something. I'll ask again"
                            + " before touching one.", false);
        }
        if (waiting.size() >= POSTMASTER_WARN_AT) {
            embed.addField("Nearly full",
                    "At " + capacity + " the postmaster starts dropping the oldest items."
                            + " Clear it before your next activity.", false);
        }
        embed.setFooter("Pick something below to pull it out");
        return new PostmasterView(embed.build(), waiting);
    }

    /**
     * Pulls one item out of the postmaster.
     *
     * <p>The destination bucket has to have room, exactly as a vault transfer does, so this
     * makes room first rather than letting the pull fail — a failed pull on a full postmaster
     * is how items get dropped.
     */
    MessageEmbed pullFromPostmaster(String discordId, long itemHash, String instanceId,
                                    int quantity) throws IOException {
        Store.User user = requireLinked(discordId);
        String accessToken = token(user);

        Manifest.Item definition = manifest.item(itemHash);
        long bucket = definition == null ? 0 : definition.bucketHash();
        String moved = null;

        if (bucket != 0) {
            JsonObject profile = client.profile(user.membershipType, user.membershipId,
                    INVENTORY_COMPONENTS, accessToken);
            Map<Long, Integer> used = occupancy(profile, user.characterId);
            if (used.getOrDefault(bucket, 0) >= capacity(bucket)) {
                moved = makeRoom(user, profile, bucket, java.util.Set.of(), accessToken);
                if (moved == null) {
                    return Destiny.error("No room in your " + manifest.bucketName(bucket)
                            + ", and nothing safe to move out. Clear a slot and try again.");
                }
            }
        }

        try {
            client.pullFromPostmaster(user.membershipType, user.characterId, itemHash,
                    instanceId, quantity, accessToken);
        } catch (BungieClient.BungieException e) {
            return Destiny.error("Couldn't pull " + manifest.itemName(itemHash)
                    + " — " + reason(e.code) + ".");
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Pulled " + manifest.itemName(itemHash))
                .setColor(ACCENT)
                .setDescription("It's on your character now.");
        if (moved != null) {
            embed.addField("Moved to the vault to make room", moved, false);
        }
        return embed.build();
    }

    /**
     * Shows the seasonal artifact and which of its perks are switched on.
     *
     * <p>Read-only, and not by choice: the artifact has profile and character components but
     * no action endpoint anywhere in the API, and its perks are progression state rather
     * than sockets on an instanced item, so there is nothing for the plug endpoints to
     * address. Unlocking and slotting artifact perks has to happen in game.
     */
    MessageEmbed artifact(String discordId) throws IOException {
        Store.User user = requireLinked(discordId);
        JsonObject profile = client.profile(user.membershipType, user.membershipId, "104,202",
                tokenOrNull(user));

        JsonObject seasonal = child(profile, "profileProgression", "data", "seasonalArtifact");
        JsonObject character = child(profile, "characterProgressions", "data");
        JsonObject mine = character != null && character.has(user.characterId)
                ? character.getAsJsonObject(user.characterId) : null;
        JsonObject characterArtifact = mine != null && mine.has("seasonalArtifact")
                ? mine.getAsJsonObject("seasonalArtifact") : null;

        if (seasonal == null && characterArtifact == null) {
            return Destiny.error("Couldn't read your artifact.");
        }

        long artifactHash = seasonal != null && seasonal.has("artifactHash")
                ? seasonal.get("artifactHash").getAsLong()
                : characterArtifact.get("artifactHash").getAsLong();

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(manifest.artifactName(artifactHash))
                .setColor(ACCENT);

        if (seasonal != null) {
            embed.addField("Power bonus",
                    "+" + (seasonal.has("powerBonus") ? seasonal.get("powerBonus").getAsInt() : 0), true);
            embed.addField("Points earned",
                    String.valueOf(seasonal.has("pointsAcquired")
                            ? seasonal.get("pointsAcquired").getAsInt() : 0), true);
        }

        if (characterArtifact != null) {
            if (characterArtifact.has("pointsUsed")) {
                embed.addField("Points spent",
                        String.valueOf(characterArtifact.get("pointsUsed").getAsInt()), true);
            }
            List<String> active = new ArrayList<>();
            JsonArray tiers = characterArtifact.getAsJsonArray("tiers");
            if (tiers != null) {
                for (JsonElement tierElement : tiers) {
                    JsonArray tierItems = tierElement.getAsJsonObject().getAsJsonArray("items");
                    if (tierItems == null) {
                        continue;
                    }
                    for (JsonElement itemElement : tierItems) {
                        JsonObject perk = itemElement.getAsJsonObject();
                        if (perk.has("isActive") && perk.get("isActive").getAsBoolean()) {
                            active.add(manifest.itemName(perk.get("itemHash").getAsLong()));
                        }
                    }
                }
            }
            embed.addField("Active perks (" + active.size() + ")",
                    active.isEmpty() ? "None unlocked yet" : join(active), false);
        }

        embed.setFooter("Read-only — the API has no way to set artifact perks");
        return embed.build();
    }

    MessageEmbed deleteLoadout(String discordId, String rawName) {
        Store.User user = store.user(discordId);
        String name = normalise(rawName);
        if (user.loadouts.remove(name) == null) {
            return Destiny.error("No loadout called `" + name + "`.");
        }
        // Drop any activity mappings that now point at nothing.
        user.activityMap.values().removeIf(name::equals);
        if (name.equals(user.pendingLoadout)) {
            user.pendingLoadout = null;
        }
        store.save();
        return simple("Deleted " + name, "Any activity mappings to it were removed too.");
    }

    // ---------------------------------------------------------------- mapping

    /** Binds a loadout to an activity — by default whichever one you are in right now. */
    MessageEmbed mapActivity(String discordId, String rawName, String explicitHash) throws IOException {
        Store.User user = requireLinked(discordId);
        String name = normalise(rawName);
        if (!user.loadouts.containsKey(name)) {
            return Destiny.error("No loadout called `" + name + "`. Save it first.");
        }

        long hash;
        if (explicitHash != null && !explicitHash.isBlank()) {
            String activity = explicitHash.trim();
            if (activity.chars().allMatch(Character::isDigit)) {
                hash = Long.parseLong(activity);
            } else {
                // A name is far easier to type than a hash, and a raid is several hashes —
                // normal, master, rotator — so bind the set to every one that matches.
                List<Long> matches = manifest.findActivities(activity);
                if (matches.isEmpty()) {
                    return Destiny.error("No activity matching `" + activity + "`."
                            + (manifest.isReady() ? "" : " The manifest is still loading — try again shortly."));
                }
                for (Long match : matches) {
                    user.activityMap.put(String.valueOf(match), name);
                }
                store.save();
                return simple("Mapped", String.join(", ", manifest.activityNamesMatching(activity))
                        + " → `" + name + "`"
                        + "\n\n" + matches.size() + " activity "
                        + (matches.size() == 1 ? "version" : "versions") + " bound.");
            }
        } else {
            hash = currentActivityHash(user);
            if (hash == 0) {
                return Destiny.error("You're not in an activity, so there's nothing to map."
                        + "\n\nRun this while you're in the activity, or pass `activity:<hash>`.");
            }
        }

        user.activityMap.put(String.valueOf(hash), name);
        store.save();
        return simple("Mapped", "**" + activityName(hash) + "** → `" + name + "`"
                + "\n\n`/activityloadout` will use it, and auto-equip will offer it here.");
    }

    MessageEmbed unmapActivity(String discordId, String explicitHash) throws IOException {
        Store.User user = requireLinked(discordId);
        long hash = explicitHash == null || explicitHash.isBlank()
                ? currentActivityHash(user)
                : Long.parseLong(explicitHash.trim());
        if (hash == 0 || user.activityMap.remove(String.valueOf(hash)) == null) {
            return Destiny.error("Nothing mapped to that activity.");
        }
        store.save();
        return simple("Unmapped", activityName(hash) + " no longer has a loadout.");
    }

    MessageEmbed autoEquip(String discordId, boolean on) {
        Store.User user = store.user(discordId);
        user.autoEquip = on;
        if (!on) {
            user.pendingLoadout = null;
        }
        store.save();
        return simple("Auto-equip " + (on ? "on" : "off"), on
                ? "I'll apply anything queued as soon as you're back in orbit, and tell you when"
                  + " you're in a mapped activity without its set on."
                : "I'll only change gear when you ask.");
    }

    // ---------------------------------------------------------------- equipping

    /**
     * Equips a named loadout, queueing it if the game will not accept gear changes yet.
     *
     * @param queueIfBlocked whether a "you're in an activity" refusal should be remembered.
     *                       True for named commands — asking for King's Fall gear mid-strike
     *                       means you want it on before the raid. False for
     *                       {@code /activityloadout}, where the activity you're in is the whole
     *                       point and queueing it for later would be applying it after the fact.
     */
    MessageEmbed equipLoadout(String discordId, String rawName, boolean queueIfBlocked) throws IOException {
        Store.User user = requireLinked(discordId);
        String name = normalise(rawName);
        List<Store.Item> items = user.loadouts.get(name);
        if (items == null) {
            return Destiny.error("No loadout called `" + name + "`.");
        }
        return apply(user, name, items, queueIfBlocked);
    }

    /** Reads the current activity and equips whatever is mapped to it. Never queues. */
    MessageEmbed activityLoadout(String discordId) throws IOException {
        Store.User user = requireLinked(discordId);
        long hash = currentActivityHash(user);
        if (hash == 0) {
            return Destiny.error("You're not in an activity, so there's nothing to look up."
                    + "\n\nUse `/equip name:<loadout>` — in orbit it'll go on immediately.");
        }

        String name = user.activityMap.get(String.valueOf(hash));
        if (name == null) {
            return Destiny.error("Nothing mapped to **" + activityName(hash) + "**."
                    + "\n\nRun `/map loadout:<name>` here to bind one.");
        }

        List<Store.Item> items = user.loadouts.get(name);
        if (items == null) {
            return Destiny.error("`" + name + "` is mapped here but no longer exists.");
        }
        return apply(user, name, items, false);
    }

    /** Moves anything needed onto the character, equips, and reports what actually happened. */
    private MessageEmbed apply(Store.User user, String name, List<Store.Item> items,
                               boolean queueIfBlocked) throws IOException {
        String accessToken = token(user);

        JsonObject profile = client.profile(user.membershipType, user.membershipId,
                INVENTORY_COMPONENTS, accessToken);
        Map<String, String> locations = locate(profile);

        List<String> missing = new ArrayList<>();
        List<String> moved = new ArrayList<>();
        List<String> displaced = new ArrayList<>();

        // Character gear buckets hold ten slots each, counting the equipped item, so a
        // transfer into a full one is refused outright. Track occupancy as we go and push
        // something out first when a bucket is already at capacity.
        Map<Long, Integer> used = occupancy(profile, user.characterId);
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Store.Item item : items) {
            keep.add(item.instanceId);
        }

        for (Store.Item item : items) {
            String where = locations.get(item.instanceId);
            if (where == null) {
                // Dismantled, or on an account this character can no longer see.
                missing.add(itemName(item));
                continue;
            }
            if (where.equals(user.characterId)) {
                continue;
            }

            long bucket = destinationBucket(item);
            try {
                if (bucket != 0 && used.getOrDefault(bucket, 0) >= capacity(bucket)) {
                    String evicted = makeRoom(user, profile, bucket, keep, accessToken);
                    if (evicted == null) {
                        missing.add(itemName(item) + " (no room, nothing safe to move out)");
                        continue;
                    }
                    displaced.add(evicted);
                    used.merge(bucket, -1, Integer::sum);
                }

                if (!"vault".equals(where)) {
                    // Another character holds it, and everything routes through the vault.
                    client.transferItem(user.membershipType, where, item.instanceId, item.itemHash,
                            true, accessToken);
                }
                client.transferItem(user.membershipType, user.characterId, item.instanceId,
                        item.itemHash, false, accessToken);
                moved.add(itemName(item));
                if (bucket != 0) {
                    used.merge(bucket, 1, Integer::sum);
                }
            } catch (BungieClient.BungieException e) {
                if (e.code == AT_THIS_LOCATION) {
                    return blocked(user, name, queueIfBlocked, "Items need moving and the game"
                            + " won't allow transfers from where you are.");
                }
                missing.add(itemName(item) + " (" + reason(e.code) + ")");
            }
        }

        List<String> ids = new ArrayList<>();
        for (Store.Item item : items) {
            if (locations.containsKey(item.instanceId)) {
                ids.add(item.instanceId);
            }
        }
        if (ids.isEmpty()) {
            return Destiny.error("None of the items in `" + name + "` still exist on your account.");
        }

        JsonArray results;
        try {
            results = client.equipItems(user.membershipType, user.characterId, ids, accessToken);
        } catch (BungieClient.BungieException e) {
            if (e.code == AT_THIS_LOCATION) {
                return blocked(user, name, queueIfBlocked, null);
            }
            if (e.code == ONLY_IN_GAME) {
                return blocked(user, name, queueIfBlocked, "You're not in game.");
            }
            throw e;
        }

        int equipped = 0;
        List<String> refused = new ArrayList<>();
        for (JsonElement element : results) {
            JsonObject result = element.getAsJsonObject();
            int status = result.get("equipStatus").getAsInt();
            if (status == 1) {
                equipped++;
            } else {
                refused.add(itemName(byInstance(items, result.get("itemInstanceId").getAsString()))
                        + " — " + reason(status));
            }
        }

        if (equipped > 0 && user.pendingLoadout != null && name.equals(user.pendingLoadout)) {
            user.pendingLoadout = null;
            store.save();
        }

        // Sockets only settle once the items are actually equipped, so this comes last.
        PlugResult plugs = restorePlugs(user, items, accessToken);

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(equipped == items.size() ? name + " equipped" : name + " partly equipped")
                .setColor(ACCENT)
                .setDescription(equipped + " of " + items.size() + " items on."
                        + (plugs.applied() > 0
                           ? "\nRestored " + plugs.applied() + " perks, mods and subclass choices."
                           : ""));

        if (plugs.capped()) {
            embed.addField("Stopped early", "Hit the " + MAX_PLUG_WRITES
                    + " socket-write limit. Run it again to finish the rest.", false);
        }
        if (!plugs.failed().isEmpty()) {
            embed.addField("Couldn't set", join(plugs.failed()), false);
        }

        if (!moved.isEmpty()) {
            embed.addField("Pulled from storage", join(moved), false);
        }
        if (!displaced.isEmpty()) {
            embed.addField("Moved to the vault to make room", join(displaced), false);
        }
        if (!missing.isEmpty()) {
            embed.addField("Couldn't find", join(missing), false);
        }
        if (!refused.isEmpty()) {
            embed.addField("Refused", join(refused), false);
        }
        return embed.build();
    }

    /** Builds the response for a refusal caused by where the player is standing. */
    private MessageEmbed blocked(Store.User user, String name, boolean queue, String detail) {
        String because = detail == null
                ? "Destiny only accepts gear changes in orbit, in a social space, or while offline."
                : detail;

        if (!queue) {
            return Destiny.error("Can't equip `" + name + "` right now.\n\n" + because
                    + "\n\nUse `/equip name:" + name + "` instead — that one waits for orbit.");
        }

        user.pendingLoadout = name;
        store.save();
        return new EmbedBuilder()
                .setTitle("Queued " + name)
                .setColor(ACCENT)
                .setDescription(because + "\n\nI'll put it on the moment you're back in orbit.")
                .setFooter(user.autoEquip ? "Auto-equip is on" : "Turn auto-equip on so I can apply it")
                .build();
    }

    /**
     * Applies a queued loadout if one is waiting and the player can take it. Used by the watcher.
     *
     * @return a message to send, or null if there was nothing to do
     */
    MessageEmbed applyPending(String discordId) throws IOException {
        Store.User user = store.user(discordId);
        if (!user.autoEquip || user.pendingLoadout == null || !user.isLinked()) {
            return null;
        }
        if (currentActivityHash(user) != 0) {
            return null;
        }
        List<Store.Item> items = user.loadouts.get(user.pendingLoadout);
        if (items == null) {
            user.pendingLoadout = null;
            store.save();
            return null;
        }
        return apply(user, user.pendingLoadout, items, true);
    }

    /**
     * Saves the current equipment into one of the game's own loadout slots.
     *
     * <p>Included because it is the one gear action with no location restriction, so it
     * works mid-activity — handy for keeping what you ended a raid in.
     */
    MessageEmbed snapshot(String discordId, int slot) throws IOException {
        Store.User user = requireLinked(discordId);
        if (slot < 1 || slot > 20) {
            return Destiny.error("Loadout slots run 1 to 20.");
        }
        client.snapshotLoadout(user.membershipType, user.characterId, slot - 1, token(user));
        return simple("Snapshotted to slot " + slot,
                "Your current gear is now in that in-game loadout slot."
                        + "\n\nThis captures what you're wearing — the API has no way to put a"
                        + " different set into a slot.");
    }

    /**
     * Builds the "your set isn't on" notice for an activity you've just walked into.
     *
     * <p>Deliberately does not equip or queue. Equipping is refused in an activity, and
     * queueing would dress you for a raid you have already finished — so the only useful
     * thing left is to say something while you can still go back to orbit and fix it.
     *
     * @return the notice, or null if the loadout is already on and there is nothing to say
     */
    MessageEmbed readyNotice(Store.User user, long activityHash, String name) throws IOException {
        List<Store.Item> items = user.loadouts.get(name);
        if (items == null) {
            return null;
        }

        JsonObject equipment = child(
                client.profile(user.membershipType, user.membershipId, "205", tokenOrNull(user)),
                "characterEquipment", "data");
        if (equipment == null || user.characterId == null || !equipment.has(user.characterId)) {
            return null;
        }

        java.util.Set<String> worn = new java.util.HashSet<>();
        for (JsonElement element : equipment.getAsJsonObject(user.characterId).getAsJsonArray("items")) {
            JsonObject item = element.getAsJsonObject();
            if (item.has("itemInstanceId")) {
                worn.add(item.get("itemInstanceId").getAsString());
            }
        }

        List<String> absent = new ArrayList<>();
        for (Store.Item item : items) {
            if (!worn.contains(item.instanceId)) {
                absent.add(itemName(item));
            }
        }
        if (absent.isEmpty()) {
            return null;
        }

        return new EmbedBuilder()
                .setTitle("You're in " + activityName(activityHash))
                .setColor(ACCENT)
                .setDescription("`" + name + "` is mapped here but " + absent.size()
                        + " of its items aren't on.")
                .addField("Missing", join(absent), false)
                .addField("To fix it", "Destiny won't let me change gear during an activity."
                        + " Run `/equip name:" + name + "` and I'll put it on as soon as you're"
                        + " back in orbit.", false)
                .build();
    }

    // ---------------------------------------------------------------- helpers

    /** Maps every instanced item on the account to where it lives: a character id, or "vault". */
    private Map<String, String> locate(JsonObject profile) {
        Map<String, String> locations = new java.util.HashMap<>();

        JsonObject vault = child(profile, "profileInventory", "data");
        if (vault != null && vault.has("items")) {
            for (JsonElement element : vault.getAsJsonArray("items")) {
                JsonObject item = element.getAsJsonObject();
                if (item.has("itemInstanceId")) {
                    locations.put(item.get("itemInstanceId").getAsString(), "vault");
                }
            }
        }

        for (String component : new String[]{"characterInventories", "characterEquipment"}) {
            JsonObject data = child(profile, component, "data");
            if (data == null) {
                continue;
            }
            for (String characterId : data.keySet()) {
                for (JsonElement element : data.getAsJsonObject(characterId).getAsJsonArray("items")) {
                    JsonObject item = element.getAsJsonObject();
                    if (item.has("itemInstanceId")) {
                        locations.put(item.get("itemInstanceId").getAsString(), characterId);
                    }
                }
            }
        }
        return locations;
    }

    /**
     * The plugs sitting in an item's visible sockets, keyed by socket index.
     *
     * <p>Hidden sockets are skipped deliberately. On armour they hold the stat rolls and
     * other internals that are not player-changeable, so capturing them would only produce
     * writes that are guaranteed to fail.
     */
    private Map<Integer, Long> readPlugs(JsonObject socketData, String instanceId) {
        if (socketData == null || !socketData.has(instanceId)) {
            return null;
        }
        JsonArray sockets = socketData.getAsJsonObject(instanceId).getAsJsonArray("sockets");
        if (sockets == null) {
            return null;
        }

        Map<Integer, Long> plugs = new LinkedHashMap<>();
        for (int index = 0; index < sockets.size(); index++) {
            JsonObject socket = sockets.get(index).getAsJsonObject();
            boolean visible = socket.has("isVisible") && socket.get("isVisible").getAsBoolean();
            if (visible && socket.has("plugHash")) {
                plugs.put(index, socket.get("plugHash").getAsLong());
            }
        }
        return plugs.isEmpty() ? null : plugs;
    }

    /** What a socket restore managed to do, for reporting back. */
    private record PlugResult(int applied, List<String> failed, boolean capped) {
    }

    /**
     * Puts saved perks, mods, shaders and subclass choices back.
     *
     * <p>Only sockets whose current plug differs from the saved one are written. That keeps
     * the call count proportional to what actually changed rather than to the size of the
     * set — and it means fixed sockets are skipped for free, since a socket you cannot
     * change will already match.
     */
    private PlugResult restorePlugs(Store.User user, List<Store.Item> items, String accessToken) {
        List<String> failed = new ArrayList<>();
        int applied = 0;
        boolean capped = false;

        JsonObject socketData;
        try {
            socketData = child(client.profile(user.membershipType, user.membershipId,
                    EQUIPMENT_COMPONENTS, accessToken), "itemComponents", "sockets", "data");
        } catch (IOException e) {
            return new PlugResult(0, List.of("couldn't read current sockets"), false);
        }
        if (socketData == null) {
            return new PlugResult(0, List.of(), false);
        }

        outer:
        for (Store.Item item : items) {
            if (item.plugs == null || item.plugs.isEmpty()) {
                continue;
            }
            Map<Integer, Long> current = readPlugs(socketData, item.instanceId);
            if (current == null) {
                continue;
            }

            for (Map.Entry<Integer, Long> wanted : item.plugs.entrySet()) {
                Long now = current.get(wanted.getKey());
                if (now != null && now.equals(wanted.getValue())) {
                    continue;
                }
                if (applied + failed.size() >= MAX_PLUG_WRITES) {
                    capped = true;
                    break outer;
                }
                try {
                    client.insertPlugFree(user.membershipType, user.characterId, item.instanceId,
                            wanted.getKey(), wanted.getValue(), accessToken);
                    applied++;
                    // Pace the writes; a full subclass rebuild is a burst of small calls.
                    Thread.sleep(120);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break outer;
                } catch (IOException e) {
                    int code = e instanceof BungieClient.BungieException be ? be.code : 0;
                    failed.add(manifest.itemName(wanted.getValue()) + " — " + reason(code));
                }
            }
        }
        return new PlugResult(applied, failed, capped);
    }

    /** The vault's own bucket. Account-scope, 1300 slots. */
    private static final long VAULT_BUCKET = 138197802L;

    /** Character gear buckets are ten slots; used only if the manifest lookup fails. */
    private static final int ASSUMED_CAPACITY = 10;

    /** How many slots each of the character's buckets is currently using, equipped included. */
    private Map<Long, Integer> occupancy(JsonObject profile, String characterId) {
        Map<Long, Integer> used = new java.util.HashMap<>();
        for (String component : new String[]{"characterInventories", "characterEquipment"}) {
            JsonObject data = child(profile, component, "data");
            if (data == null || characterId == null || !data.has(characterId)) {
                continue;
            }
            for (JsonElement element : data.getAsJsonObject(characterId).getAsJsonArray("items")) {
                JsonObject item = element.getAsJsonObject();
                if (item.has("bucketHash")) {
                    used.merge(item.get("bucketHash").getAsLong(), 1, Integer::sum);
                }
            }
        }
        return used;
    }

    /** A bucket's slot count, from the manifest. Cached, so this is one lookup per bucket. */
    private int capacity(long bucketHash) {
        int known = manifest.bucketCapacity(bucketHash);
        // Assuming ten is far better than refusing to equip because a table has not loaded.
        return known > 0 ? known : ASSUMED_CAPACITY;
    }

    /**
     * Sends one item from a full bucket to the vault so a transfer in can land.
     *
     * <p>Picks from the character's inventory rather than what is equipped, and never
     * touches anything belonging to the loadout being applied — evicting an item we are
     * about to put on would be a slow way of achieving nothing.
     *
     * @return the name of whatever was moved, or null if there was nothing safe to move
     */
    private String makeRoom(Store.User user, JsonObject profile, long bucketHash,
                            java.util.Set<String> keep, String accessToken) throws IOException {
        if (vaultFull(profile)) {
            return null;
        }

        JsonObject inventories = child(profile, "characterInventories", "data");
        if (inventories == null || !inventories.has(user.characterId)) {
            return null;
        }

        for (JsonElement element : inventories.getAsJsonObject(user.characterId).getAsJsonArray("items")) {
            JsonObject item = element.getAsJsonObject();
            if (!item.has("itemInstanceId") || !item.has("bucketHash")
                    || item.get("bucketHash").getAsLong() != bucketHash) {
                continue;
            }
            String instanceId = item.get("itemInstanceId").getAsString();
            if (keep.contains(instanceId)) {
                continue;
            }
            long itemHash = item.get("itemHash").getAsLong();
            try {
                client.transferItem(user.membershipType, user.characterId, instanceId, itemHash,
                        true, accessToken);
            } catch (BungieClient.BungieException e) {
                // Quest items and the like refuse to move; try the next candidate.
                continue;
            }
            return itemName(new Store.Item(instanceId, itemHash, bucketHash));
        }
        return null;
    }

    /** Whether the vault has no room left, so pushing something out of a bucket would fail too. */
    private boolean vaultFull(JsonObject profile) {
        JsonObject vault = child(profile, "profileInventory", "data");
        if (vault == null || !vault.has("items")) {
            return false;
        }
        int count = 0;
        for (JsonElement element : vault.getAsJsonArray("items")) {
            JsonObject item = element.getAsJsonObject();
            if (item.has("bucketHash") && item.get("bucketHash").getAsLong() == VAULT_BUCKET) {
                count++;
            }
        }
        return count >= capacity(VAULT_BUCKET);
    }

    /** Where an item lands on a character. Falls back to the manifest if the set predates it. */
    private long destinationBucket(Store.Item item) {
        if (item.bucketHash != 0) {
            return item.bucketHash;
        }
        Manifest.Item definition = manifest.item(item.itemHash);
        return definition == null ? 0 : definition.bucketHash();
    }

    private Store.Item byInstance(List<Store.Item> items, String instanceId) {
        for (Store.Item item : items) {
            if (item.instanceId.equals(instanceId)) {
                return item;
            }
        }
        return null;
    }

    private String itemName(Store.Item item) {
        return item == null ? "an item" : manifest.itemName(item.itemHash);
    }

    /** The item's name with its type, e.g. {@code Gjallarhorn — Exotic Rocket Launcher}. */
    private String describeItem(Store.Item item) {
        return item == null ? "an item" : manifest.describeItem(item.itemHash);
    }

    /** Turns a platform error code into something worth reading in Discord. */
    private static String reason(int code) {
        return switch (code) {
            case AT_THIS_LOCATION -> "not allowed here";
            case ONLY_IN_GAME -> "you're not in game";
            case 1640 -> "can't be equipped";
            case 1641, 1648 -> "conflicts with another exotic";
            case 1623 -> "no longer exists";
            case 1642 -> "no room";
            case 1645 -> "transfer failed";
            case 1660 -> "can't be moved";
            default -> "error " + code;
        };
    }

    private Store.User requireLinked(String discordId) throws IOException {
        return store.requireLinked(discordId);
    }

    private static String characterSummary(JsonObject characters, String characterId) {
        if (characters == null || characterId == null || !characters.has(characterId)) {
            return "unknown";
        }
        JsonObject character = characters.getAsJsonObject(characterId);
        String light = character.has("light") ? character.get("light").getAsString() : "?";
        return "Power " + light + " · `" + characterId + "`";
    }

    private static String platform(int membershipType) {
        return switch (membershipType) {
            case 1 -> "Xbox";
            case 2 -> "PlayStation";
            case 3 -> "Steam";
            case 5 -> "Stadia";
            case 6 -> "Epic";
            default -> "platform " + membershipType;
        };
    }

    private static String join(List<String> values) {
        String joined = String.join("\n", values);
        // Discord rejects fields over 1024 characters, and a bad loadout can be long.
        return joined.length() > 1000 ? joined.substring(0, 997) + "..." : joined;
    }

    private static String normalise(String name) {
        return name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : "";
    }

    private static JsonObject child(JsonObject parent, String... keys) {
        JsonObject current = parent;
        for (String key : keys) {
            if (current == null || !current.has(key) || !current.get(key).isJsonObject()) {
                return null;
            }
            current = current.getAsJsonObject(key);
        }
        return current;
    }

    private static MessageEmbed simple(String title, String body) {
        return new EmbedBuilder().setTitle(title).setColor(ACCENT).setDescription(body).build();
    }
}
