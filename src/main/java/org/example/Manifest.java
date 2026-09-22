package org.example;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * The parts of the Destiny manifest worth holding in memory, loaded once at startup.
 *
 * <p>Almost everything the API returns is a hash, so without this a loadout with twelve
 * items and sixty plugs would mean seventy-odd HTTP lookups to print one message. That was
 * tolerable for a milestone list and is not tolerable here.
 *
 * <p>The item table is about 190 MB of JSON across roughly 39,000 entries, which is far too
 * much to hold as a parsed tree. It does not need to be: the names alone are under a
 * megabyte, so this streams the download and keeps only the handful of fields that get
 * displayed, discarding each entry's remaining hundred-odd properties as it goes. Repeated
 * strings — there are only a few dozen distinct item types and tiers — are shared rather
 * than duplicated 39,000 times.
 *
 * <p>Loading happens on a background thread so the bot answers commands immediately, and
 * every lookup falls back to a single-hash HTTP call until the tables arrive.
 */
final class Manifest {

    /** What gets kept for each item: enough to say "Gjallarhorn — Exotic Rocket Launcher". */
    record Item(String name, String type, String tier, long bucketHash, boolean pullHasSideEffects) {

        /** The name with its type, e.g. {@code Gjallarhorn — Exotic Rocket Launcher}. */
        String describe() {
            String detail = ((tier == null ? "" : tier) + " " + (type == null ? "" : type)).trim();
            return detail.isEmpty() ? name : name + " — " + detail;
        }
    }

    private static final String ITEM_TABLE = "DestinyInventoryItemDefinition";
    private static final String ACTIVITY_TABLE = "DestinyActivityDefinition";
    private static final String BUCKET_TABLE = "DestinyInventoryBucketDefinition";
    private static final String VENDOR_TABLE = "DestinyVendorDefinition";
    private static final String DESTINATION_TABLE = "DestinyDestinationDefinition";
    private static final String OBJECTIVE_TABLE = "DestinyObjectiveDefinition";

    private final BungieClient client;
    private final ManifestCache fallback;

    // Replaced wholesale once loaded, so readers never see a half-built map.
    private volatile Map<Long, Item> items = Map.of();
    private volatile Map<Long, String> activities = Map.of();
    private volatile Map<Long, Integer> bucketCapacity = Map.of();
    private volatile Map<Long, String> bucketNames = Map.of();
    private volatile Map<Long, String> vendors = Map.of();
    private volatile Map<Long, String> destinations = Map.of();
    private volatile Map<Long, Objective> objectives = Map.of();
    private volatile String version = null;

    Manifest(BungieClient client, ManifestCache fallback) {
        this.client = client;
        this.fallback = fallback;
    }

    /** Kicks off the download. Returns immediately; lookups fall back until it finishes. */
    void loadInBackground() {
        Thread.ofVirtual().name("manifest-loader").start(() -> {
            try {
                load();
            } catch (Exception e) {
                System.err.println("Manifest load failed, falling back to per-hash lookups: "
                        + e.getMessage());
            }
        });
    }

    void load() throws IOException {
        JsonObject manifest = client.get("/Destiny2/Manifest/").getAsJsonObject("Response");
        String newVersion = manifest.get("version").getAsString();
        if (newVersion.equals(version)) {
            return;
        }

        JsonObject paths = manifest.getAsJsonObject("jsonWorldComponentContentPaths")
                .getAsJsonObject("en");

        long started = System.currentTimeMillis();
        Map<Long, Item> loadedItems = readItems(paths.get(ITEM_TABLE).getAsString());
        Map<Long, String> loadedActivities = readNames(paths.get(ACTIVITY_TABLE).getAsString());
        Map<Long, Integer> capacities = new HashMap<>();
        Map<Long, String> bucketLabels = new HashMap<>();
        readBuckets(paths.get(BUCKET_TABLE).getAsString(), capacities, bucketLabels);
        Map<Long, String> loadedVendors = readNames(paths.get(VENDOR_TABLE).getAsString());
        Map<Long, String> loadedDestinations = readNames(paths.get(DESTINATION_TABLE).getAsString());
        Map<Long, Objective> loadedObjectives = readObjectives(paths.get(OBJECTIVE_TABLE).getAsString());

        items = loadedItems;
        activities = loadedActivities;
        bucketCapacity = capacities;
        bucketNames = bucketLabels;
        vendors = loadedVendors;
        destinations = loadedDestinations;
        objectives = loadedObjectives;
        version = newVersion;

        System.out.println("Manifest " + newVersion + " loaded in "
                + (System.currentTimeMillis() - started) + "ms: "
                + loadedItems.size() + " items, " + loadedActivities.size() + " activities, "
                + loadedVendors.size() + " vendors, " + loadedObjectives.size() + " objectives");
    }

    // ---------------------------------------------------------------- lookups

    /** The item's name and type, falling back to a live lookup then the bare hash. */
    String describeItem(long hash) {
        Item item = items.get(hash);
        if (item != null) {
            return item.describe();
        }
        String name = viaFallback(ITEM_TABLE, hash);
        return name == null ? "Item " + hash : name;
    }

    /** Just the item's name. */
    String itemName(long hash) {
        Item item = items.get(hash);
        if (item != null) {
            return item.name();
        }
        String name = viaFallback(ITEM_TABLE, hash);
        return name == null ? "Item " + hash : name;
    }

    Item item(long hash) {
        return items.get(hash);
    }

    String activityName(long hash) {
        String name = activities.get(hash);
        if (name != null) {
            return name;
        }
        String live = viaFallback(ACTIVITY_TABLE, hash);
        return live == null ? "Activity " + hash : live;
    }

    String bucketName(long hash) {
        String name = bucketNames.get(hash);
        return name == null ? "Other" : name;
    }

    /** A bucket's slot count, or 0 if unknown so callers can decide what to assume. */
    int bucketCapacity(long hash) {
        return bucketCapacity.getOrDefault(hash, 0);
    }

    boolean isReady() {
        return version != null;
    }

    /**
     * Every activity whose name contains {@code query}, case-insensitively.
     *
     * <p>Returns all matches rather than one, because a raid exists as several hashes —
     * normal, master, and occasionally a rotator variant — and binding a loadout to "King's
     * Fall" almost always means all of them.
     */
    List<Long> findActivities(String query) {
        return matching(activities, query);
    }

    /** Distinct activity names matching a query, for telling someone what they could have meant. */
    List<String> activityNamesMatching(String query) {
        List<String> names = new ArrayList<>();
        for (Long hash : findActivities(query)) {
            String name = activities.get(hash);
            if (name != null && !names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Resolves an item by name, preferring an exact match.
     *
     * <p>Names are not unique — the same weapon exists as several hashes across seasons and
     * as vendor preview copies — so this prefers an equippable one with a real slot, which is
     * almost always the copy someone means.
     *
     * @return the hash, or -1 if nothing matched
     */
    long resolveItem(String query) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        long exact = -1;
        long partial = -1;
        for (Map.Entry<Long, Item> entry : items.entrySet()) {
            Item item = entry.getValue();
            String name = item.name().toLowerCase(Locale.ROOT);
            boolean equippable = item.bucketHash() != 0;
            if (name.equals(needle)) {
                if (equippable) {
                    return entry.getKey();
                }
                if (exact == -1) {
                    exact = entry.getKey();
                }
            } else if (partial == -1 && equippable && name.contains(needle)) {
                partial = entry.getKey();
            }
        }
        return exact != -1 ? exact : partial;
    }

    /**
     * The seasonal artifact's name.
     *
     * <p>Artifacts are in {@code DestinyArtifactDefinition}, not the item table, so looking
     * one up as an item yields nothing. There is only ever one in play, so a single cached
     * lookup is cheaper than preloading another table.
     */
    String artifactName(long hash) {
        String name = viaFallback("DestinyArtifactDefinition", hash);
        return name == null ? "Seasonal Artifact" : name;
    }

    /** What a bounty or quest step is asking for, and how much of it is needed. */
    record Objective(String description, int completionValue) {
    }

    String vendorName(long hash) {
        String name = vendors.get(hash);
        if (name != null) {
            return name;
        }
        String live = viaFallback(VENDOR_TABLE, hash);
        return live == null ? "Vendor " + hash : live;
    }

    String destinationName(long hash) {
        String name = destinations.get(hash);
        if (name != null) {
            return name;
        }
        String live = viaFallback(DESTINATION_TABLE, hash);
        return live == null ? "Destination " + hash : live;
    }

    Objective objective(long hash) {
        return objectives.get(hash);
    }

    /** Vendors whose name contains the query, exact matches first. */
    List<Long> findVendors(String query) {
        return matching(vendors, query);
    }

    List<Long> findDestinations(String query) {
        return matching(destinations, query);
    }

    /** Distinct names from a set of hashes, for offering alternatives. */
    List<String> namesOf(Map<Long, String> table, List<Long> hashes) {
        List<String> names = new ArrayList<>();
        for (Long hash : hashes) {
            String name = table.get(hash);
            if (name != null && !names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    List<String> vendorNamesMatching(String query) {
        return namesOf(vendors, findVendors(query));
    }

    List<String> destinationNamesMatching(String query) {
        return namesOf(destinations, findDestinations(query));
    }

    /**
     * Hashes whose name matches a query, preferring precision but never at the cost of
     * missing the thing people actually mean.
     *
     * <p>The subtlety is that a raid's real activities are named for their version — the
     * bare "Vault of Glass" is a container with no completions against it, while every clear
     * is recorded against "Vault of Glass: Standard". Returning only the exact match
     * therefore answers "never completed" for a raid cleared seventeen times.
     *
     * <p>So an exact match is returned <em>together with</em> its variants: names that
     * continue past the query with a separator. Only when neither exists does this fall back
     * to a loose contains, which stops "Vault of Glass" also dragging in anything that merely
     * mentions it.
     */
    private static List<Long> matching(Map<Long, String> table, String query) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<Long> direct = new ArrayList<>();
        List<Long> loose = new ArrayList<>();
        for (Map.Entry<Long, String> entry : table.entrySet()) {
            String name = entry.getValue().toLowerCase(Locale.ROOT);
            if (name.equals(needle) || isVariantOf(name, needle)) {
                direct.add(entry.getKey());
            } else if (name.contains(needle)) {
                loose.add(entry.getKey());
            }
        }
        return direct.isEmpty() ? loose : direct;
    }

    /** Whether {@code name} is the needle followed by a version, e.g. "…: Master". */
    private static boolean isVariantOf(String name, String needle) {
        if (!name.startsWith(needle) || name.length() <= needle.length()) {
            return false;
        }
        char next = name.charAt(needle.length());
        return next == ':' || next == ' ' || next == '-' || next == ',';
    }

    private Map<Long, Objective> readObjectives(String path) throws IOException {
        Map<Long, Objective> out = new HashMap<>();
        try (JsonReader reader = open(path)) {
            reader.beginObject();
            while (reader.hasNext()) {
                long hash = Long.parseLong(reader.nextName());
                String description = null;
                String fallbackName = null;
                int completion = 0;
                reader.beginObject();
                while (reader.hasNext()) {
                    switch (reader.nextName()) {
                        case "progressDescription" -> description = reader.nextString();
                        case "completionValue" -> completion = reader.nextInt();
                        case "displayProperties" -> {
                            reader.beginObject();
                            while (reader.hasNext()) {
                                if ("name".equals(reader.nextName())) {
                                    fallbackName = reader.nextString();
                                } else {
                                    reader.skipValue();
                                }
                            }
                            reader.endObject();
                        }
                        default -> reader.skipValue();
                    }
                }
                reader.endObject();
                // progressDescription is the bar's label and is often blank; the display name
                // is the next best thing to show for a step with no description of its own.
                String label = description != null && !description.isBlank() ? description
                        : (fallbackName == null ? "" : fallbackName);
                out.put(hash, new Objective(label, completion));
            }
            reader.endObject();
        }
        return out;
    }

    private String viaFallback(String table, long hash) {
        try {
            String name = fallback.displayName(table, hash);
            return name == null || name.isBlank() ? null : name;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- streaming readers

    private Map<Long, Item> readItems(String path) throws IOException {
        Map<Long, Item> out = new HashMap<>(50_000);
        // Item types and tiers repeat across tens of thousands of entries; share the strings.
        Map<String, String> pool = new HashMap<>();

        try (JsonReader reader = open(path)) {
            reader.beginObject();
            while (reader.hasNext()) {
                long hash = Long.parseLong(reader.nextName());
                String name = null;
                String type = null;
                String tier = null;
                long bucket = 0;
                boolean sideEffects = false;

                reader.beginObject();
                while (reader.hasNext()) {
                    switch (reader.nextName()) {
                        case "displayProperties" -> {
                            reader.beginObject();
                            while (reader.hasNext()) {
                                if ("name".equals(reader.nextName())) {
                                    name = reader.nextString();
                                } else {
                                    reader.skipValue();
                                }
                            }
                            reader.endObject();
                        }
                        case "itemTypeDisplayName" -> type = share(pool, reader.nextString());
                        // Bungie's own warning that pulling this from the postmaster could
                        // destroy something. Worth carrying so the bot can say so first.
                        case "doesPostmasterPullHaveSideEffects" -> sideEffects = reader.nextBoolean();
                        case "inventory" -> {
                            reader.beginObject();
                            while (reader.hasNext()) {
                                switch (reader.nextName()) {
                                    case "tierTypeName" -> tier = share(pool, reader.nextString());
                                    case "bucketTypeHash" -> bucket = reader.nextLong();
                                    default -> reader.skipValue();
                                }
                            }
                            reader.endObject();
                        }
                        // Everything else is stats, sockets, investment data — not needed here.
                        default -> reader.skipValue();
                    }
                }
                reader.endObject();

                if (name != null && !name.isBlank()) {
                    out.put(hash, new Item(name, type, tier, bucket, sideEffects));
                }
            }
            reader.endObject();
        }
        return out;
    }

    private Map<Long, String> readNames(String path) throws IOException {
        Map<Long, String> out = new HashMap<>();
        try (JsonReader reader = open(path)) {
            reader.beginObject();
            while (reader.hasNext()) {
                long hash = Long.parseLong(reader.nextName());
                String name = null;
                reader.beginObject();
                while (reader.hasNext()) {
                    if ("displayProperties".equals(reader.nextName())) {
                        reader.beginObject();
                        while (reader.hasNext()) {
                            if ("name".equals(reader.nextName())) {
                                name = reader.nextString();
                            } else {
                                reader.skipValue();
                            }
                        }
                        reader.endObject();
                    } else {
                        reader.skipValue();
                    }
                }
                reader.endObject();
                if (name != null && !name.isBlank()) {
                    out.put(hash, name);
                }
            }
            reader.endObject();
        }
        return out;
    }

    private void readBuckets(String path, Map<Long, Integer> capacities, Map<Long, String> names)
            throws IOException {
        try (JsonReader reader = open(path)) {
            reader.beginObject();
            while (reader.hasNext()) {
                long hash = Long.parseLong(reader.nextName());
                String name = null;
                int count = 0;
                reader.beginObject();
                while (reader.hasNext()) {
                    switch (reader.nextName()) {
                        case "displayProperties" -> {
                            reader.beginObject();
                            while (reader.hasNext()) {
                                if ("name".equals(reader.nextName())) {
                                    name = reader.nextString();
                                } else {
                                    reader.skipValue();
                                }
                            }
                            reader.endObject();
                        }
                        case "itemCount" -> count = reader.nextInt();
                        default -> reader.skipValue();
                    }
                }
                reader.endObject();
                if (count > 0) {
                    capacities.put(hash, count);
                }
                if (name != null && !name.isBlank()) {
                    names.put(hash, name);
                }
            }
            reader.endObject();
        }
    }

    private static String share(Map<String, String> pool, String value) {
        return pool.computeIfAbsent(value, v -> v);
    }

    /** Opens a manifest table for streaming. These are public CDN paths, so no API key. */
    private JsonReader open(String path) throws IOException {
        HttpURLConnection con;
        try {
            con = (HttpURLConnection) URI.create("https://www.bungie.net" + path).toURL()
                    .openConnection();
        } catch (Exception e) {
            throw new IOException("Bad manifest path: " + path, e);
        }
        con.setRequestProperty("Accept-Encoding", "gzip");
        con.setConnectTimeout(15_000);
        con.setReadTimeout(120_000);

        int status = con.getResponseCode();
        if (status != 200) {
            throw new IOException("Manifest table returned HTTP " + status + " for " + path);
        }

        InputStream stream = con.getInputStream();
        // Asking for gzip explicitly means it must be undone explicitly too.
        if ("gzip".equalsIgnoreCase(con.getContentEncoding())) {
            stream = new GZIPInputStream(stream, 65_536);
        }
        return new JsonReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
}
