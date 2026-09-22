package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.awt.Color;
import java.io.IOException;

/**
 * The linked account's clan.
 *
 * <p>Clans are groups, so they live under {@code /GroupV2/} rather than {@code /Destiny2/} —
 * which is why nothing in the Destiny endpoint listing hints at them. None of the reads used
 * here need a token: membership, group detail and the weekly reward state are all public.
 */
final class Clan {

    private static final Color ACCENT = new Color(0x00A8E1);

    private final BungieClient client;
    private final Store store;

    Clan(BungieClient client, Store store) {
        this.client = client;
        this.store = store;
    }

    MessageEmbed clan(String discordId) throws IOException {
        Store.User user = store.requireLinked(discordId);

        JsonArray results = client.clansFor(user.membershipType, user.membershipId);
        if (results == null || results.isEmpty()) {
            return Destiny.error("You're not in a clan.");
        }

        JsonObject group = results.get(0).getAsJsonObject().getAsJsonObject("group");
        if (group == null) {
            return Destiny.error("Bungie returned a clan with no detail.");
        }

        String groupId = group.get("groupId").getAsString();
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(string(group, "name"))
                .setColor(ACCENT);

        String motto = string(group, "motto");
        String about = string(group, "about");
        if (!motto.isBlank()) {
            embed.setDescription("_" + motto + "_");
        } else if (!about.isBlank()) {
            embed.setDescription(trim(about, 300));
        }

        if (group.has("memberCount")) {
            embed.addField("Members", group.get("memberCount").getAsString(), true);
        }
        if (group.has("clanInfo")) {
            JsonObject info = group.getAsJsonObject("clanInfo");
            if (info.has("clanCallsign")) {
                String callsign = info.get("clanCallsign").getAsString();
                if (!callsign.isBlank()) {
                    embed.addField("Callsign", "[" + callsign + "]", true);
                }
            }
        }
        if (group.has("creationDate")) {
            embed.addField("Founded", group.get("creationDate").getAsString().substring(0, 10), true);
        }

        // The weekly reward state is a separate call and often the only bit that changes.
        try {
            JsonObject rewards = client.clanWeeklyRewardState(groupId);
            int earned = 0;
            int total = 0;
            for (JsonElement categoryElement : rewards.getAsJsonArray("rewards")) {
                JsonArray entries = categoryElement.getAsJsonObject().getAsJsonArray("entries");
                if (entries == null) {
                    continue;
                }
                for (JsonElement entryElement : entries) {
                    JsonObject entry = entryElement.getAsJsonObject();
                    total++;
                    if (entry.has("earned") && entry.get("earned").getAsBoolean()) {
                        earned++;
                    }
                }
            }
            if (total > 0) {
                embed.addField("Weekly engrams", earned + " of " + total + " earned", true);
            }
        } catch (IOException e) {
            // Not worth failing the whole command over; the clan detail is the main event.
            embed.addField("Weekly engrams", "Couldn't read them right now.", true);
        }

        if (!about.isBlank() && !motto.isBlank()) {
            embed.addField("About", trim(about, 500), false);
        }
        return embed.setFooter("Clan " + groupId).build();
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static String trim(String value, int limit) {
        String single = value.replace("\n", " ").trim();
        return single.length() <= limit ? single : single.substring(0, limit - 1) + "…";
    }
}
