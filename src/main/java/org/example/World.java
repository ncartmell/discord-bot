package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What is live in the world right now, by destination.
 *
 * <p>There is no endpoint that answers "what is on the Moon this week". What there is
 * is {@code characterActivities}, which lists every activity currently available to a
 * character — around 280 of them — each with its recommended power, difficulty and active
 * modifiers. Cross-referencing that against each activity's {@code destinationHash} from
 * the manifest gives the per-destination view.
 *
 * <p>It is therefore character-scoped by construction: the list is what <em>you</em> can
 * launch, so it already accounts for campaign progress and season.
 */
final class World {

    private static final Color ACCENT = new Color(0x00A8E1);
    /** Discord allows 25 fields; leave headroom for the summary ones. */
    private static final int MAX_GROUPS = 8;
    private static final int MAX_PER_GROUP = 12;
    /** Enough to be useful without overflowing a single embed description. */
    private static final int MAX_DESTINATIONS = 22;

    private final BungieClient client;
    private final Manifest manifest;
    private final Store store;
    private final Ghost ghost;

    World(BungieClient client, Manifest manifest, Store store, Ghost ghost) {
        this.client = client;
        this.manifest = manifest;
        this.store = store;
        this.ghost = ghost;
    }

    /** Everything currently available on a named destination. */
    MessageEmbed destination(String discordId, String query) throws IOException {
        if (query == null || query.isBlank()) {
            return listDestinations(discordId);
        }
        Store.User user = store.requireLinked(discordId);

        List<Long> wanted = manifest.findDestinations(query);
        if (wanted.isEmpty()) {
            return Destiny.error("No destination matching `" + query + "`."
                    + (manifest.isReady() ? " Try `/destination` on its own for the list."
                                          : " The manifest is still loading — try again shortly."));
        }
        Set<Long> destinations = new java.util.HashSet<>(wanted);

        JsonArray available = availableActivities(user);
        if (available.isEmpty()) {
            return Destiny.error("Couldn't read what's available to your character.");
        }

        // Group by what kind of thing it is — Patrol, Strike, Raid — rather than listing
        // sixty activity names in one run.
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        int total = 0;
        for (JsonElement element : available) {
            JsonObject entry = element.getAsJsonObject();
            if (!entry.has("activityHash")) {
                continue;
            }
            long hash = entry.get("activityHash").getAsLong();
            if (!destinations.contains(manifest.activityDestination(hash))) {
                continue;
            }
            boolean visible = !entry.has("isVisible") || entry.get("isVisible").getAsBoolean();
            if (!visible) {
                continue;
            }

            total++;
            String type = manifest.activityType(hash);
            List<String> bucket = grouped.computeIfAbsent(type == null ? "Other" : type,
                    k -> new ArrayList<>());
            if (bucket.size() >= MAX_PER_GROUP) {
                continue;
            }

            StringBuilder line = new StringBuilder("**" + manifest.activityName(hash) + "**");
            if (entry.has("recommendedLight")) {
                int light = entry.get("recommendedLight").getAsInt();
                if (light > 0) {
                    line.append(" · ").append(light);
                }
            }
            if (entry.has("isCompleted") && entry.get("isCompleted").getAsBoolean()) {
                line.append(" ✓");
            }
            String modifiers = modifiers(entry);
            if (!modifiers.isEmpty()) {
                line.append("\n   ").append(modifiers);
            }
            bucket.add(line.toString());
        }

        String name = manifest.destinationNamesMatching(query).stream().findFirst().orElse(query);
        if (total == 0) {
            return new EmbedBuilder()
                    .setTitle(name)
                    .setColor(ACCENT)
                    .setDescription("Nothing available there right now."
                            + "\n\nSome destinations only open up during a season or after a"
                            + " campaign, and the list is what your character can actually launch.")
                    .build();
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(name)
                .setColor(ACCENT)
                .setDescription(total + " " + (total == 1 ? "activity" : "activities") + " available");

        int groups = 0;
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            if (groups++ == MAX_GROUPS) {
                embed.addField("…", "And " + (grouped.size() - MAX_GROUPS) + " more categories.",
                        false);
                break;
            }
            embed.addField(entry.getKey(), join(entry.getValue()), false);
        }
        return embed.setFooter("Power figures are recommended, not required").build();
    }

    /** The destinations that currently have anything on them, for when no name is given. */
    MessageEmbed listDestinations(String discordId) throws IOException {
        Store.User user = store.requireLinked(discordId);

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JsonElement element : availableActivities(user)) {
            JsonObject entry = element.getAsJsonObject();
            if (!entry.has("activityHash")) {
                continue;
            }
            long destination = manifest.activityDestination(entry.get("activityHash").getAsLong());
            if (destination == 0) {
                continue;
            }
            // Many destination hashes are internal groupings with no name; listing those as
            // "Destination 1119773799" is worse than leaving them out.
            String name = manifest.destinationNameOrNull(destination);
            if (name == null || name.isBlank()) {
                continue;
            }
            counts.merge(name, 1, Integer::sum);
        }

        if (counts.isEmpty()) {
            return Destiny.error("Couldn't read what's available to your character.");
        }

        // Busiest first — that is the useful ordering when the list is longer than the
        // message. Counts are already merged by name, since several hashes share one.
        List<String> lines = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(MAX_DESTINATIONS)
                .map(e -> "**" + e.getKey() + "** — " + e.getValue())
                .toList();

        return new EmbedBuilder()
                .setTitle("Destinations")
                .setColor(ACCENT)
                .setDescription(join(new ArrayList<>(lines)))
                .setFooter(counts.size() + " destinations with something on them"
                        + " · `/destination name:<one>` for detail")
                .build();
    }

    /** Active modifiers for an activity, named. */
    private String modifiers(JsonObject entry) {
        if (!entry.has("modifierHashes") || !entry.get("modifierHashes").isJsonArray()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (JsonElement element : entry.getAsJsonArray("modifierHashes")) {
            String name = manifest.modifierName(element.getAsLong());
            // Plenty of modifiers are internal scoring tweaks with no name worth printing.
            if (name != null && !name.isBlank() && !names.contains(name)) {
                names.add(name);
            }
            if (names.size() == 4) {
                break;
            }
        }
        return names.isEmpty() ? "" : "_" + String.join(", ", names) + "_";
    }

    /**
     * The activities currently available to the linked character.
     *
     * <p>Sent with a token when one is available, but not dependent on it:
     * {@code characterActivities} answers for a public profile, so a lapsed authorisation
     * degrades this to what anyone could see rather than breaking it.
     */
    private JsonArray availableActivities(Store.User user) throws IOException {
        JsonObject profile = client.profile(user.membershipType, user.membershipId, "204",
                ghost.accessTokenOrNull(user));
        JsonObject data = profile.has("characterActivities")
                ? profile.getAsJsonObject("characterActivities").getAsJsonObject("data") : null;
        if (data == null || !data.has(user.characterId)) {
            return new JsonArray();
        }
        JsonArray available = data.getAsJsonObject(user.characterId)
                .getAsJsonArray("availableActivities");
        return available == null ? new JsonArray() : available;
    }

    private static String join(List<String> values) {
        String joined = String.join("\n", values);
        return joined.length() > 1000 ? joined.substring(0, 997) + "..." : joined;
    }
}
