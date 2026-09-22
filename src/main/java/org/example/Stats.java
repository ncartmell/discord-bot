package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.awt.Color;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Historical stats: what you played, how it went, and what you killed it with.
 *
 * <p>All of this reads with the API key alone — no OAuth — which is worth knowing because
 * it means these work for any account whose privacy settings allow it, not just a linked
 * one. They are wired to the linked account here purely because that is whose id the bot
 * already knows.
 *
 * <p>Most of these aggregate across all three characters rather than reporting the one the
 * bot happens to be pointed at. "My best weapon" is an account-level question, and getting
 * an answer that silently excluded two characters would be worse than useless.
 */
final class Stats {

    private static final Color ACCENT = new Color(0x00A8E1);

    private final BungieClient client;
    private final Manifest manifest;
    private final Store store;

    Stats(BungieClient client, Manifest manifest, Store store) {
        this.client = client;
        this.manifest = manifest;
        this.store = store;
    }

    // ---------------------------------------------------------------- recent activities

    /** The last few activities across every character, newest first. */
    MessageEmbed recent(String discordId, int count) throws IOException {
        Store.User user = store.requireLinked(discordId);

        List<JsonObject> all = new ArrayList<>();
        for (String characterId : characters(user)) {
            for (JsonElement element : client.activityHistory(user.membershipType, user.membershipId,
                    characterId, Math.min(count, 25), 0)) {
                all.add(element.getAsJsonObject());
            }
        }
        if (all.isEmpty()) {
            return error("No recent activities. Either nothing has been played or the account's"
                    + " privacy settings hide it.");
        }

        all.sort(Comparator.comparing((JsonObject a) -> period(a)).reversed());

        List<String> lines = new ArrayList<>();
        for (JsonObject activity : all.subList(0, Math.min(count, all.size()))) {
            JsonObject details = activity.getAsJsonObject("activityDetails");
            long hash = details.get("referenceId").getAsLong();
            boolean completed = "Yes".equalsIgnoreCase(display(activity, "completed"));

            lines.add((completed ? "✓" : "✗") + " **" + manifest.activityName(hash) + "**"
                    + "\n   " + ago(period(activity))
                    + " · " + display(activity, "activityDurationSeconds")
                    + " · " + display(activity, "kills") + " kills"
                    + " · `" + details.get("instanceId").getAsString() + "`");
        }

        return new EmbedBuilder()
                .setTitle("Recent activities")
                .setColor(ACCENT)
                .setDescription(String.join("\n", lines))
                .setFooter("The code after each one is its instance id — pass it to /pgcr")
                .build();
    }

    // ---------------------------------------------------------------- carnage report

    /**
     * The full breakdown of one activity.
     *
     * @param instanceId the activity to report on, or null for the most recent one
     */
    MessageEmbed pgcr(String discordId, String instanceId) throws IOException {
        Store.User user = store.requireLinked(discordId);

        String target = instanceId;
        if (target == null || target.isBlank()) {
            target = mostRecentInstanceId(user);
            if (target == null) {
                return error("No recent activity to report on.");
            }
        }

        JsonObject report = client.postGameCarnageReport(target.trim());
        JsonObject details = report.getAsJsonObject("activityDetails");
        long hash = details == null ? 0 : details.get("referenceId").getAsLong();

        JsonArray entries = report.getAsJsonArray("entries");
        if (entries == null || entries.isEmpty()) {
            return error("That report has no players in it.");
        }

        // Highest kills first — it is the first thing anyone looks for.
        List<JsonObject> players = new ArrayList<>();
        for (JsonElement element : entries) {
            players.add(element.getAsJsonObject());
        }
        players.sort(Comparator.comparingDouble((JsonObject p) -> value(p, "kills")).reversed());

        List<String> lines = new ArrayList<>();
        for (JsonObject player : players) {
            JsonObject info = player.getAsJsonObject("player");
            JsonObject userInfo = info == null ? null : info.getAsJsonObject("destinyUserInfo");
            String name = userInfo != null && userInfo.has("bungieGlobalDisplayName")
                    ? userInfo.get("bungieGlobalDisplayName").getAsString()
                    : "Unknown";
            String code = userInfo != null && userInfo.has("bungieGlobalDisplayNameCode")
                    ? "#" + userInfo.get("bungieGlobalDisplayNameCode").getAsString() : "";

            lines.add("**" + name + code + "** — "
                    + (int) value(player, "kills") + "/"
                    + (int) value(player, "deaths") + "/"
                    + (int) value(player, "assists")
                    + (info != null && info.has("lightLevel")
                       ? " · " + info.get("lightLevel").getAsInt() : "")
                    + " · " + display(player, "timePlayedSeconds"));
        }

        String when = report.has("period") ? report.get("period").getAsString() : null;
        boolean fromStart = report.has("activityWasStartedFromBeginning")
                && report.get("activityWasStartedFromBeginning").getAsBoolean();

        return new EmbedBuilder()
                .setTitle(hash == 0 ? "Activity report" : manifest.activityName(hash))
                .setColor(ACCENT)
                .setDescription((when == null ? "" : ago(Instant.parse(when)) + " · ")
                        + players.size() + (players.size() == 1 ? " player" : " players")
                        + (fromStart ? " · full run" : " · started from a checkpoint"))
                .addField("Kills / deaths / assists", String.join("\n", lines), false)
                .setFooter("Instance " + target)
                .build();
    }

    // ---------------------------------------------------------------- clears

    /** How many times an activity has been completed, and the fastest one. */
    MessageEmbed clears(String discordId, String query) throws IOException {
        Store.User user = store.requireLinked(discordId);

        List<Long> wanted = manifest.findActivities(query);
        if (wanted.isEmpty()) {
            return error("No activity matching `" + query + "`."
                    + (manifest.isReady() ? "" : " The manifest is still loading — try again shortly."));
        }

        int completions = 0;
        double fastestMs = 0;
        Map<String, Integer> byName = new HashMap<>();

        for (String characterId : characters(user)) {
            for (JsonElement element : client.aggregateActivityStats(user.membershipType,
                    user.membershipId, characterId)) {
                JsonObject activity = element.getAsJsonObject();
                long hash = activity.get("activityHash").getAsLong();
                if (!wanted.contains(hash)) {
                    continue;
                }
                int count = (int) value(activity, "activityCompletions");
                if (count == 0) {
                    continue;
                }
                completions += count;
                byName.merge(manifest.activityName(hash), count, Integer::sum);

                double best = value(activity, "fastestCompletionMsForActivity");
                if (best > 0 && (fastestMs == 0 || best < fastestMs)) {
                    fastestMs = best;
                }
            }
        }

        // Matching a raid returns every version of it, so trim back to the name people
        // actually use: "Vault of Glass", not a list ending in ": Challenge Mode", and not
        // whichever variant happens to have the shortest name.
        String name = manifest.activityNamesMatching(query).stream()
                .map(Stats::baseName)
                .min(Comparator.comparingInt(String::length))
                .orElse(query);
        if (completions == 0) {
            return new EmbedBuilder()
                    .setTitle(name)
                    .setColor(ACCENT)
                    .setDescription("Never completed.")
                    .setFooter("Across all characters")
                    .build();
        }

        List<String> breakdown = new ArrayList<>();
        byName.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> breakdown.add(e.getKey() + " — " + e.getValue()));

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(name)
                .setColor(ACCENT)
                .setDescription("**" + completions + "** " + (completions == 1 ? "clear" : "clears"))
                .setFooter("All characters \u00b7 fastest counts checkpoint runs, not just full clears");
        if (fastestMs > 0) {
            embed.addField("Fastest", formatMillis(fastestMs), true);
        }
        if (byName.size() > 1) {
            embed.addField("By version", String.join("\n", breakdown), false);
        }
        return embed.build();
    }

    /**
     * Total completions of a set of activity hashes, for enriching a listing.
     *
     * <p>Split out from {@link #clears} because the LFG board wants the number, not an
     * embed, and wants it for someone who is not the caller.
     *
     * @return the total, or -1 if it could not be read — which is different from zero
     */
    int clearCount(Store.User user, Set<Long> activityHashes) {
        if (activityHashes.isEmpty()) {
            return -1;
        }
        try {
            int total = 0;
            for (String characterId : characters(user)) {
                for (JsonElement element : client.aggregateActivityStats(user.membershipType,
                        user.membershipId, characterId)) {
                    JsonObject activity = element.getAsJsonObject();
                    if (activityHashes.contains(activity.get("activityHash").getAsLong())) {
                        total += (int) value(activity, "activityCompletions");
                    }
                }
            }
            return total;
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * The highest power level across the account's characters.
     *
     * @return the power, or 0 if it could not be read
     */
    int highestPower(Store.User user) {
        try {
            JsonObject profile = client.profile(user.membershipType, user.membershipId, "200");
            JsonObject characters = profile.has("characters")
                    ? profile.getAsJsonObject("characters").getAsJsonObject("data") : null;
            if (characters == null) {
                return 0;
            }
            int best = 0;
            for (String characterId : characters.keySet()) {
                JsonObject character = characters.getAsJsonObject(characterId);
                if (character.has("light")) {
                    best = Math.max(best, character.get("light").getAsInt());
                }
            }
            return best;
        } catch (IOException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- weapons

    /** Kills and precision kills with one named weapon. */
    MessageEmbed weapon(String discordId, String query) throws IOException {
        Store.User user = store.requireLinked(discordId);

        long wanted = manifest.resolveItem(query);
        if (wanted == -1) {
            return error("No weapon called `" + query + "`.");
        }

        double kills = 0;
        double precision = 0;
        boolean found = false;
        for (Map.Entry<Long, double[]> entry : weaponTotals(user).entrySet()) {
            if (entry.getKey() != wanted
                    && !manifest.itemName(entry.getKey()).equalsIgnoreCase(manifest.itemName(wanted))) {
                continue;
            }
            found = true;
            kills += entry.getValue()[0];
            precision += entry.getValue()[1];
        }

        if (!found) {
            return new EmbedBuilder()
                    .setTitle(manifest.describeItem(wanted))
                    .setColor(ACCENT)
                    .setDescription("No recorded kills with it.")
                    .setFooter("Stats only cover weapons you've used since they started tracking")
                    .build();
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(manifest.describeItem(wanted))
                .setColor(ACCENT)
                .addField("Kills", String.valueOf((long) kills), true)
                .addField("Precision", String.valueOf((long) precision), true);
        if (kills > 0) {
            embed.addField("Precision rate", Math.round(precision / kills * 100) + "%", true);
        }
        return embed.setFooter("Across all characters").build();
    }

    /** The weapons with the most kills, across every character. */
    MessageEmbed topWeapons(String discordId, int count) throws IOException {
        Store.User user = store.requireLinked(discordId);

        List<Map.Entry<Long, double[]>> ranked = new ArrayList<>(weaponTotals(user).entrySet());
        ranked.sort((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]));
        if (ranked.isEmpty()) {
            return error("No weapon stats on this account.");
        }

        List<String> lines = new ArrayList<>();
        int place = 1;
        for (Map.Entry<Long, double[]> entry : ranked.subList(0, Math.min(count, ranked.size()))) {
            double kills = entry.getValue()[0];
            double precision = entry.getValue()[1];
            lines.add(place++ + ". **" + manifest.itemName(entry.getKey()) + "** — "
                    + (long) kills + " kills"
                    + (kills > 0 && precision > 0
                       ? " (" + Math.round(precision / kills * 100) + "% precision)" : ""));
        }

        return new EmbedBuilder()
                .setTitle("Most used weapons")
                .setColor(ACCENT)
                .setDescription(String.join("\n", lines))
                .setFooter(ranked.size() + " weapons tracked across all characters")
                .build();
    }

    /** Kills and precision per weapon hash, summed over every character. */
    private Map<Long, double[]> weaponTotals(Store.User user) throws IOException {
        Map<Long, double[]> totals = new HashMap<>();
        for (String characterId : characters(user)) {
            for (JsonElement element : client.uniqueWeapons(user.membershipType, user.membershipId,
                    characterId)) {
                JsonObject weapon = element.getAsJsonObject();
                if (!weapon.has("referenceId")) {
                    continue;
                }
                long hash = weapon.get("referenceId").getAsLong();
                double[] sum = totals.computeIfAbsent(hash, h -> new double[2]);
                sum[0] += value(weapon, "uniqueWeaponKills");
                sum[1] += value(weapon, "uniqueWeaponPrecisionKills");
            }
        }
        totals.values().removeIf(v -> v[0] <= 0);
        return totals;
    }

    // ---------------------------------------------------------------- helpers

    /** Every character on the account, so stats are not silently limited to one. */
    private List<String> characters(Store.User user) throws IOException {
        JsonObject profile = client.profile(user.membershipType, user.membershipId, "200");
        JsonObject characters = profile.has("characters")
                ? profile.getAsJsonObject("characters").getAsJsonObject("data") : null;
        if (characters == null) {
            return user.characterId == null ? List.of() : List.of(user.characterId);
        }
        return new ArrayList<>(characters.keySet());
    }

    private String mostRecentInstanceId(Store.User user) throws IOException {
        JsonObject newest = null;
        for (String characterId : characters(user)) {
            JsonArray history = client.activityHistory(user.membershipType, user.membershipId,
                    characterId, 1, 0);
            if (history.isEmpty()) {
                continue;
            }
            JsonObject candidate = history.get(0).getAsJsonObject();
            if (newest == null || period(candidate).isAfter(period(newest))) {
                newest = candidate;
            }
        }
        return newest == null ? null
                : newest.getAsJsonObject("activityDetails").get("instanceId").getAsString();
    }

    private static Instant period(JsonObject activity) {
        try {
            return Instant.parse(activity.get("period").getAsString());
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }

    /** A stat's numeric value, from the {@code values.<key>.basic.value} shape everything uses. */
    private static double value(JsonObject holder, String key) {
        JsonObject values = holder.getAsJsonObject("values");
        if (values == null || !values.has(key)) {
            return 0;
        }
        JsonObject basic = values.getAsJsonObject(key).getAsJsonObject("basic");
        return basic != null && basic.has("value") ? basic.get("value").getAsDouble() : 0;
    }

    /** A stat's pre-formatted display value, which Bungie renders better than we would. */
    private static String display(JsonObject holder, String key) {
        JsonObject values = holder.getAsJsonObject("values");
        if (values == null || !values.has(key)) {
            return "-";
        }
        JsonObject basic = values.getAsJsonObject(key).getAsJsonObject("basic");
        return basic != null && basic.has("displayValue") ? basic.get("displayValue").getAsString() : "-";
    }

    /** An activity name with its version suffix removed: "King's Fall: Master" to "King's Fall". */
    private static String baseName(String name) {
        int colon = name.indexOf(':');
        return colon > 0 ? name.substring(0, colon).trim() : name;
    }

    private static String formatMillis(double millis) {
        Duration duration = Duration.ofMillis((long) millis);
        long minutes = duration.toMinutes();
        long seconds = duration.toSecondsPart();
        return minutes >= 60
                ? duration.toHours() + "h " + duration.toMinutesPart() + "m " + seconds + "s"
                : minutes + "m " + seconds + "s";
    }

    private static String ago(Instant when) {
        if (when.equals(Instant.EPOCH)) {
            return "unknown";
        }
        long days = ChronoUnit.DAYS.between(when, Instant.now());
        if (days > 0) {
            return days == 1 ? "yesterday" : days + " days ago";
        }
        long hours = ChronoUnit.HOURS.between(when, Instant.now());
        if (hours > 0) {
            return hours + (hours == 1 ? " hour ago" : " hours ago");
        }
        long minutes = Math.max(1, ChronoUnit.MINUTES.between(when, Instant.now()));
        return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
    }

    private static MessageEmbed error(String message) {
        return Destiny.error(message);
    }
}
