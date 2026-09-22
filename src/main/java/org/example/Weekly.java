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

    /** One milestone from the public feed, resolved into names. */
    private record Entry(String name, List<String> activities, Instant ends, int order) {
    }

    MessageEmbed rotators() throws IOException {
        List<Entry> entries = entries();
        Instant reset = soonest(entries);
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


    /**
     * The public milestone feed, resolved into names and end dates.
     *
     * <p>Shared by the weekly rotator listing and the daily announcement, which want the
     * same parse filtered two different ways.
     */
    private List<Entry> entries() throws IOException {
        JsonObject milestones = client.publicMilestones();
        List<Entry> entries = new ArrayList<>();
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
            entries.add(new Entry(name, activities, ends,
                    value.has("order") ? value.get("order").getAsInt() : 0));
        }
        return entries;
    }

    /** The nearest end date across the feed, which is the next reset. */
    private static Instant soonest(List<Entry> entries) {
        Instant soonest = null;
        for (Entry entry : entries) {
            if (entry.ends() != null && (soonest == null || entry.ends().isBefore(soonest))) {
                soonest = entry.ends();
            }
        }
        return soonest;
    }

    /**
     * The daily announcement: what expires in the next day, and how long the week has left.
     *
     * <p>Deliberately modest about what it claims. Daily reset mostly refreshes vendor
     * bounties and the lost sector rotation, and neither of those is exposed as a public
     * component — there is no endpoint that says "today's legend lost sector is X". What
     * the feed does give is end dates, so anything expiring within the day is genuinely
     * daily and everything else is correctly left to the weekly post.
     */
    MessageEmbed daily() throws IOException {
        Instant now = Instant.now();
        Instant weeklyReset = Reset.nextWeekly(now);
        // A day either side of the next daily reset, which is long enough to catch things
        // that expire today whether this runs just before or just after the boundary.
        Instant cutoff = now.plus(Duration.ofHours(26));

        List<String> expiring = new ArrayList<>();
        for (Entry entry : entries()) {
            if (entry.ends() == null || !entry.ends().isBefore(cutoff)) {
                continue;
            }
            // On Monday the weekly reset is inside the 26-hour window, which would sweep
            // every raid and weekly chore into a post about today. Anything that lives
            // until the week turns over belongs to the weekly announcement, not this one.
            if (!entry.ends().isBefore(weeklyReset)) {
                continue;
            }
            expiring.add("\u2022 **" + entry.name() + "**"
                    + (entry.activities().isEmpty() ? ""
                       : " \u2014 " + String.join(", ", entry.activities())));
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Daily reset")
                .setColor(ACCENT)
                .setDescription("Vendor bounties and the lost sector rotation have refreshed."
                        + "\n\nThe week resets in " + until(weeklyReset) + ".");
        if (!expiring.isEmpty()) {
            embed.addField("Gone tomorrow (" + expiring.size() + ")", join(expiring), false);
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
