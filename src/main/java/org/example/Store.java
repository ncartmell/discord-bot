package org.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * On-disk state: linked accounts, OAuth tokens, saved loadouts and activity mappings.
 *
 * <p>A JSON file rather than a database, because this is a personal bot holding a handful
 * of records. Writes are whole-file and synchronised — at this size that is simpler and
 * safer than incremental updates, and the file is small enough that the cost is irrelevant.
 *
 * <p><strong>The file contains OAuth refresh tokens in plain text.</strong> It is gitignored,
 * and it should be treated like any other credential store: anyone who can read it can act
 * on the linked Destiny account for as long as the refresh token lives (90 days).
 */
final class Store {

    private static final Path FILE = Paths.get(
            System.getenv().getOrDefault("BOT_STATE_FILE", "state.json"));

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Everything the bot knows about one Discord user. */
    static final class User {
        int membershipType;
        String membershipId;
        /** The character loadouts are saved against and equipped on. */
        String characterId;

        String accessToken;
        long accessTokenExpiresAt;
        String refreshToken;
        long refreshTokenExpiresAt;

        boolean autoEquip = false;

        /** Loadout name (lowercased) to the items it equips. */
        Map<String, List<Item>> loadouts = new LinkedHashMap<>();

        /** Destiny activity hash to loadout name. */
        Map<String, String> activityMap = new LinkedHashMap<>();

        /** A loadout asked for while equipping was blocked, applied at the next opportunity. */
        String pendingLoadout;

        boolean isLinked() {
            return membershipId != null && refreshToken != null && characterId != null;
        }
    }

    /** One equippable item, identified by instance, with whatever is plugged into it. */
    static final class Item {
        String instanceId;
        long itemHash;
        long bucketHash;

        /**
         * Socket index to the plug hash sitting in it.
         *
         * <p>Only visible sockets are kept: the hidden ones carry armour stats and other
         * internals that are not player-changeable, and writing to them is neither possible
         * nor wanted. Null on sets saved before sockets were captured, which is why every
         * read of this is null-checked rather than assumed.
         */
        Map<Integer, Long> plugs;

        Item(String instanceId, long itemHash, long bucketHash) {
            this.instanceId = instanceId;
            this.itemHash = itemHash;
            this.bucketHash = bucketHash;
        }
    }

    private final Map<String, User> users = new ConcurrentHashMap<>();

    Store() {
        load();
    }

    private synchronized void load() {
        if (!Files.exists(FILE)) {
            return;
        }
        try {
            String json = Files.readString(FILE, StandardCharsets.UTF_8);
            Map<String, User> loaded = GSON.fromJson(
                    json, new TypeToken<Map<String, User>>() {}.getType());
            if (loaded != null) {
                users.putAll(loaded);
            }
        } catch (Exception e) {
            // Losing saved loadouts is bad, so refuse to start rather than silently
            // overwriting a file we could not understand.
            throw new IllegalStateException("Could not read " + FILE.toAbsolutePath()
                    + " — move it aside if you want to start fresh.", e);
        }
    }

    synchronized void save() {
        try {
            Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(users), StandardCharsets.UTF_8);
            // Write-then-rename so a crash mid-write cannot truncate the real file.
            Files.move(tmp, FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Could not save state: " + e.getMessage());
        }
    }

    /** Returns the record for a Discord user, creating an empty one if needed. */
    User user(String discordId) {
        return users.computeIfAbsent(discordId, id -> new User());
    }

    /**
     * Returns a fully linked user, or fails with a message worth showing.
     *
     * <p>Lives here rather than in one of the callers because several unrelated features
     * need the same check before they can do anything.
     */
    User requireLinked(String discordId) throws java.io.IOException {
        User user = user(discordId);
        if (!user.isLinked()) {
            throw new java.io.IOException("No Destiny account linked — run `/link` first.");
        }
        return user;
    }

    /** Returns the record for a Discord user, or null. Use when a lookup should not create one. */
    User peek(String discordId) {
        return users.get(discordId);
    }

    /** Returns only users who have completed linking — used by the auto-equip watcher. */
    List<Map.Entry<String, User>> linkedUsers() {
        List<Map.Entry<String, User>> out = new ArrayList<>();
        for (Map.Entry<String, User> e : users.entrySet()) {
            if (e.getValue().isLinked()) {
                out.add(e);
            }
        }
        return out;
    }

    static Path file() {
        return FILE.toAbsolutePath();
    }
}
