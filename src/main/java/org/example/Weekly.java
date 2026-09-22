package org.example;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.awt.Color;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * What is featured this week, and how long is left of it.
 *
 * <p>There is no "rotator" endpoint. The public milestone feed is the closest thing: each
 * entry carries the activities it covers and an end date, so resolving the hashes gives the
 * week's featured raids and dungeons together with the reset they expire at.
 *
 * <p>Reads with an API key alone — the feed is the same for everyone.
 */
final class Weekly {

    private static final Color ACCENT = new Color(0x00A8E1);

    private final BungieClient client;
    private final Manifest manifest;

    Weekly(BungieClient client, Manifest manifest) {
        this.client = client;
        this.manifest = manifest;
    }

    MessageEmbed rotators() throws IOException {
        JsonObject milestones = client.publicMilestones();

        record Entry(String name, List<String> activities, Instant ends, int order) {
        }
        List<Entry> entries = new ArrayList<>();
        Instant reset = null;

        for (Map.Entry<String, JsonElement> milestone : milestones.entrySet()) {
            JsonObject value = milestone.getValue().getAsJsonObject();
            if (!value.has("milestoneHash")) {
                continue;
            }
            String name = manifest.milestoneName(value.get("milestoneHash").getAsLong());
            if (name == null || name.isBlank()) {
                // The feed carries internal entries that are not meant to be shown.
                continue;
            }

            List<String> activities = new ArrayList<>();
            if (value.has("activities") && value.get("activities").isJsonArray()) {
                for (JsonElement element : value.getAsJsonArray("activities")) {
                    JsonObject activity = element.getAsJsonObject();
                    if (!activity.has("activityHash")) {
                        continue;
                    }
                    String activityName = manifest.activityName(activity.get("activityHash").getAsLong());
                    // A milestone lists its normal and master versions separately; the
                    // difficulty is the interesting part, not the repeated raid name.
                    if (!activities.contains(activityName)) {
                        activities.add(activityName);
                    }
                }
            }

            Instant ends = null;
            if (value.has("endDate")) {
                try {
                    ends = Instant.parse(value.get("endDate").getAsString());
                } catch (Exception e) {
                    ends = null;
                }
            }
            if (ends != null && (reset == null || ends.isBefore(reset))) {
                reset = ends;
            }

            entries.add(new Entry(name, activities,
                    ends, value.has("order") ? value.get("order").getAsInt() : 0));
        }

        if (entries.isEmpty()) {
            return new EmbedBuilder()
                    .setTitle("No active milestones")
                    .setColor(Color.GRAY)
                    .setDescription("Bungie returned nothing — this usually means maintenance.")
                    .build();
        }

        entries.sort(Comparator.comparingInt(Entry::order));

        // Things with an activity attached are the featured content; the rest are chores.
        List<String> featured = new ArrayList<>();
        List<String> other = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.activities().isEmpty()) {
                other.add("• " + entry.name());
            } else {
                featured.add("**" + entry.name() + "**\n   " + String.join(", ", entry.activities()));
            }
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("This week")
                .setColor(ACCENT)
                .setDescription(reset == null ? entries.size() + " active milestones"
                        : "Resets in " + until(reset));

        if (!featured.isEmpty()) {
            embed.addField("Featured (" + featured.size() + ")", join(featured), false);
        }
        if (!other.isEmpty()) {
            embed.addField("Also active", join(other), false);
        }
        return embed.build();
    }

    /** A rough "2 days, 4 hours" for how long is left. */
    private static String until(Instant when) {
        Duration left = Duration.between(Instant.now(), when);
        if (left.isNegative() || left.isZero()) {
            return "moments";
        }
        long days = left.toDays();
        long hours = left.toHoursPart();
        if (days > 0) {
            return days + (days == 1 ? " day" : " days")
                    + (hours > 0 ? ", " + hours + (hours == 1 ? " hour" : " hours") : "");
        }
        if (hours > 0) {
            return hours + (hours == 1 ? " hour" : " hours");
        }
        long minutes = Math.max(1, left.toMinutes());
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }

    private static String join(List<String> values) {
        String joined = String.join("\n", values);
        return joined.length() > 1000 ? joined.substring(0, 997) + "..." : joined;
    }
}
