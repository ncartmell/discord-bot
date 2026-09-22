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
    private static final String ACTIVITY_COMPONENTS = "200,204";

    /** Bungie refuses the action because the player is in an activity. */
    private static final int AT_THIS_LOCATION = 1671;
    /** The character is not logged in, so the game cannot be told to do anything. */
    private static final int ONLY_IN_GAME = 1655;

    /** An access token is renewed this many seconds before it actually lapses. */
    private static final long RENEW_MARGIN = 120;

    private final BungieClient client;
    private final ManifestCache manifest;
    private final Store store;

    /** Discord user id to the state nonce issued by the most recent {@code /link}. */
    private final Map<String, String> pendingLinks = new ConcurrentHashMap<>();

    private static final SecureRandom RANDOM = new SecureRandom();

    Ghost(BungieClient client, ManifestCache manifest, Store store) {
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
        JsonObject characters = child(client.profile(user.membershipType, user.membershipId, "200"),
                "characters", "data");
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
    private String token(Store.User user) throws IOException {
        long now = System.currentTimeMillis() / 1000;
        if (user.accessToken != null && user.accessTokenExpiresAt - RENEW_MARGIN > now) {
            return user.accessToken;
        }
        if (user.refreshToken == null || user.refreshTokenExpiresAt <= now) {
            throw new IOException("Your Bungie authorisation has expired — run `/link` again.");
        }

        OAuth.Tokens tokens = OAuth.refresh(user.refreshToken);
        user.accessToken = tokens.accessToken;
        user.accessTokenExpiresAt = tokens.accessExpiresAt;
        user.refreshToken = tokens.refreshToken;
        user.refreshTokenExpiresAt = tokens.refreshExpiresAt;
        store.save();
        return user.accessToken;
    }

    // ---------------------------------------------------------------- activity

    /** The activity hash the character is currently in, or 0 if they are not in one. */
    long currentActivityHash(Store.User user) throws IOException {
        JsonObject activities = child(
                client.profile(user.membershipType, user.membershipId, ACTIVITY_COMPONENTS),
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
        try {
            String name = manifest.displayName(ACTIVITY_DEFINITION, hash);
            return name == null || name.isBlank() ? "Activity " + hash : name;
        } catch (Exception e) {
            return "Activity " + hash;
        }
    }

    // ---------------------------------------------------------------- loadouts

    /** Saves whatever the character is wearing right now, by item instance id. */
    MessageEmbed saveLoadout(String discordId, String rawName) throws IOException {
        Store.User user = requireLinked(discordId);
        String name = normalise(rawName);
        if (name.isEmpty()) {
            return Destiny.error("Give the loadout a name.");
        }

        JsonObject equipment = child(
                client.profile(user.membershipType, user.membershipId, "205"),
                "characterEquipment", "data");
        if (equipment == null || user.characterId == null || !equipment.has(user.characterId)) {
            return Destiny.error("Couldn't read your equipment. Is the character still on the account?");
        }

        List<Store.Item> items = new ArrayList<>();
        for (JsonElement element : equipment.getAsJsonObject(user.characterId).getAsJsonArray("items")) {
            JsonObject item = element.getAsJsonObject();
            // Only instanced items can be equipped back by id; emblems and the like still are.
            if (!item.has("itemInstanceId")) {
                continue;
            }
            items.add(new Store.Item(
                    item.get("itemInstanceId").getAsString(),
                    item.get("itemHash").getAsLong(),
                    item.has("bucketHash") ? item.get("bucketHash").getAsLong() : 0));
        }

        if (items.isEmpty()) {
            return Destiny.error("Nothing equipped that can be saved.");
        }

        boolean replaced = user.loadouts.containsKey(name);
        user.loadouts.put(name, items);
        store.save();

        return simple(replaced ? "Updated " + name : "Saved " + name,
                items.size() + " items stored by instance id."
                        + "\n\n`/equip name:" + name + "` to put it back on, or `/map loadout:" + name
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

    MessageEmbed showLoadout(String discordId, String rawName) {
        Store.User user = store.user(discordId);
        String name = normalise(rawName);
        List<Store.Item> items = user.loadouts.get(name);
        if (items == null) {
            return Destiny.error("No loadout called `" + name + "`.");
        }

        StringBuilder body = new StringBuilder();
        for (Store.Item item : items) {
            String label;
            try {
                label = manifest.displayName(ITEM_DEFINITION, item.itemHash);
            } catch (Exception e) {
                label = null;
            }
            body.append("• ").append(label == null || label.isBlank() ? "Item " + item.itemHash : label)
                    .append('\n');
        }

        return new EmbedBuilder()
                .setTitle(name)
                .setColor(ACCENT)
                .setDescription(body.toString().trim())
                .setFooter(items.size() + " items")
                .build();
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
            try {
                hash = Long.parseLong(explicitHash.trim());
            } catch (NumberFormatException e) {
                return Destiny.error("`" + explicitHash + "` isn't an activity hash.");
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

        JsonObject profile = client.profile(user.membershipType, user.membershipId, INVENTORY_COMPONENTS);
        Map<String, String> locations = locate(profile);

        List<String> missing = new ArrayList<>();
        List<String> moved = new ArrayList<>();

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
            try {
                if (!"vault".equals(where)) {
                    // Another character holds it, and everything routes through the vault.
                    client.transferItem(user.membershipType, where, item.instanceId, item.itemHash,
                            true, accessToken);
                }
                client.transferItem(user.membershipType, user.characterId, item.instanceId,
                        item.itemHash, false, accessToken);
                moved.add(itemName(item));
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

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(equipped == items.size() ? name + " equipped" : name + " partly equipped")
                .setColor(ACCENT)
                .setDescription(equipped + " of " + items.size() + " items on.");

        if (!moved.isEmpty()) {
            embed.addField("Pulled from storage", join(moved), false);
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
                client.profile(user.membershipType, user.membershipId, "205"),
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

    private Store.Item byInstance(List<Store.Item> items, String instanceId) {
        for (Store.Item item : items) {
            if (item.instanceId.equals(instanceId)) {
                return item;
            }
        }
        return null;
    }

    private String itemName(Store.Item item) {
        if (item == null) {
            return "an item";
        }
        try {
            String name = manifest.displayName(ITEM_DEFINITION, item.itemHash);
            return name == null || name.isBlank() ? "Item " + item.itemHash : name;
        } catch (Exception e) {
            return "Item " + item.itemHash;
        }
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
        Store.User user = store.user(discordId);
        if (!user.isLinked()) {
            throw new IOException("No Destiny account linked — run `/link` first.");
        }
        return user;
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
