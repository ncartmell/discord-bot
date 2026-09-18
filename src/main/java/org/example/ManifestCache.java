package org.example;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory cache of Destiny manifest definitions, keyed by entity type and hash.
 *
 * <p>Most Bungie API responses identify things by hash rather than by name — a milestone
 * comes back as {@code 3312774044} rather than "Nightfall". Turning those into something
 * readable means a lookup per hash, and the same handful of hashes are requested over and
 * over: the weekly milestones barely change between resets, and popular items get searched
 * repeatedly.
 *
 * <p>The full manifest is a very large download, which is overkill for a bot that needs a
 * few dozen definitions. Caching individual lookups gets most of the benefit for none of
 * the complexity. Definitions only change when the game patches, so entries are kept for
 * the lifetime of the process.
 */
final class ManifestCache {

    private final BungieClient client;
    private final Map<String, JsonObject> cache = new ConcurrentHashMap<>();

    ManifestCache(BungieClient client) {
        this.client = client;
    }

    /**
     * Returns the definition for {@code hash}, fetching it on first use.
     *
     * @param entityType a definition contract name, e.g. {@code DestinyMilestoneDefinition}
     */
    JsonObject definition(String entityType, long hash) throws IOException {
        String key = entityType + ":" + hash;

        JsonObject cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        // Deliberately not computeIfAbsent: that holds a lock on the map bin for the
        // duration of the mapping function, so a slow HTTP call would block unrelated
        // lookups. A duplicate fetch under a race is far cheaper than that.
        JsonObject fetched = client.entityDefinition(entityType, hash);
        cache.put(key, fetched);
        return fetched;
    }

    /**
     * Returns the display name for a definition, or {@code null} if it has none.
     *
     * <p>Some definitions legitimately have empty display properties — placeholder and
     * internal entries in particular — so callers should expect to skip them rather than
     * render an empty string.
     */
    String displayName(String entityType, long hash) throws IOException {
        JsonObject definition = definition(entityType, hash);
        if (!definition.has("displayProperties")) {
            return null;
        }
        JsonObject display = definition.getAsJsonObject("displayProperties");
        if (!display.has("name")) {
            return null;
        }
        String name = display.get("name").getAsString();
        return name.isBlank() ? null : name;
    }

    /** Number of definitions currently held, for logging and the {@code /cache} command. */
    int size() {
        return cache.size();
    }

    void clear() {
        cache.clear();
    }
}
