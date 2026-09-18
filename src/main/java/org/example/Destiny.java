package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.awt.Color;
import java.io.IOException;
import java.util.List;

/**
 * Builds the Discord embeds for the Destiny commands.
 *
 * <p>Kept apart from {@link DiscordBot} so the command handlers stay about Discord and the
 * formatting stays about Destiny.
 */
final class Destiny {

    private static final String ITEM_DEFINITION = "DestinyInventoryItemDefinition";
    private static final String BUNGIE_ROOT = "https://www.bungie.net";
    private static final Color ACCENT = new Color(0x00A8E1);

    private Destiny() {
    }

    // ---------------------------------------------------------------- /item

    /**
     * Shows a single item, looked up by its manifest hash.
     *
     * <p>Bungie documents a name-search endpoint at {@code /Destiny2/Armory/Search/}, but it
     * returns NotFound for every query — it appears to have been retired without the docs
     * being updated. Searching by name would otherwise mean downloading the item definition
     * component, which is roughly 200 MB, so this takes a hash instead. Hashes are visible in
     * the URL on light.gg and on Bungie's own Armory pages.
     */
    static MessageEmbed item(BungieClient client, ManifestCache manifest, String rawHash) throws IOException {
        long hash;
        try {
            hash = Long.parseLong(rawHash.trim());
        } catch (NumberFormatException e) {
            return error("That isn't an item hash. Hashes are numbers — you'll find one in the "
                    + "URL of an item's page on light.gg or Bungie's Armory.");
        }

        JsonObject definition;
        try {
            definition = manifest.definition(ITEM_DEFINITION, hash);
        } catch (IOException e) {
            return error("No item in the manifest has the hash `" + hash + "`.");
        }

        JsonObject display = definition.has("displayProperties")
                ? definition.getAsJsonObject("displayProperties") : null;

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(ACCENT)
                .setTitle(string(display, "name", "Unnamed item"));

        String description = string(display, "description", "");
        if (!description.isBlank()) {
            embed.setDescription(description);
        }

        String icon = string(display, "icon", "");
        if (!icon.isBlank()) {
            embed.setThumbnail(BUNGIE_ROOT + icon);
        }

        String type = string(definition.has("itemTypeDisplayName") ? definition : null,
                "itemTypeDisplayName", "");
        if (!type.isBlank()) {
            embed.addField("Type", type, true);
        }

        embed.addField("Armory", "[View on Bungie.net](" + BUNGIE_ROOT
                + "/en/Armory/Detail?itemHash=" + hash + ")", false);
        embed.setFooter("Hash " + hash);
        return embed.build();
    }

    // ---------------------------------------------------------------- /weekly

    static MessageEmbed weekly(BungieClient client, ManifestCache manifest) throws IOException {
        List<String> names = Milestones.activeMilestoneNames(client, manifest);
        if (names.isEmpty()) {
            return new EmbedBuilder()
                    .setTitle("No active milestones")
                    .setColor(Color.GRAY)
                    .setDescription("Bungie returned nothing — this usually means maintenance.")
                    .build();
        }

        String body = String.join("\n", names.stream().map(name -> "• " + name).toList());
        return new EmbedBuilder()
                .setTitle("This week")
                .setColor(ACCENT)
                .setDescription(body)
                .setFooter(names.size() + " active milestones")
                .build();
    }

    // ---------------------------------------------------------------- /news

    static MessageEmbed news(BungieClient client) throws IOException {
        JsonArray articles = client.newsArticles();
        if (articles.isEmpty()) {
            return new EmbedBuilder()
                    .setTitle("No articles")
                    .setColor(Color.GRAY)
                    .build();
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Latest from Bungie")
                .setColor(ACCENT);

        int shown = Math.min(5, articles.size());
        for (int i = 0; i < shown; i++) {
            JsonObject article = articles.get(i).getAsJsonObject();
            String title = string(article, "Title", "Untitled");
            String link = string(article, "Link", "");
            String date = string(article, "PubDate", "");

            String value = link.isBlank()
                    ? date
                    : "[Read it](" + BUNGIE_ROOT + link + ")" + (date.isBlank() ? "" : " — " + date);
            embed.addField(title, value, false);
        }
        return embed.build();
    }

    // ---------------------------------------------------------------- /profile

    static MessageEmbed profile(BungieClient client, String bungieName) throws IOException {
        int hash = bungieName.lastIndexOf('#');
        if (hash < 1 || hash == bungieName.length() - 1) {
            return error("That does not look like a Bungie name. They are written as `Guardian#1234`.");
        }

        String name = bungieName.substring(0, hash);
        int code;
        try {
            code = Integer.parseInt(bungieName.substring(hash + 1).trim());
        } catch (NumberFormatException e) {
            return error("The part after `#` should be four digits, for example `Guardian#1234`.");
        }

        JsonArray matches = client.searchPlayer(name, code);
        if (matches.isEmpty()) {
            return error("No Destiny account found for `" + bungieName + "`.");
        }

        JsonObject account = matches.get(0).getAsJsonObject();
        int membershipType = account.get("membershipType").getAsInt();
        String membershipId = account.get("membershipId").getAsString();
        String displayName = string(account, "bungieGlobalDisplayName", name);
        int displayCode = account.has("bungieGlobalDisplayNameCode")
                ? account.get("bungieGlobalDisplayNameCode").getAsInt() : code;

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(ACCENT)
                .setTitle(displayName + "#" + String.format("%04d", displayCode))
                .setFooter(platform(membershipType));

        String icon = string(account, "iconPath", "");
        if (!icon.isBlank()) {
            embed.setThumbnail(BUNGIE_ROOT + icon);
        }

        JsonObject pve = mode(client.accountStats(membershipType, membershipId), "allPvE");
        if (pve == null) {
            embed.setDescription("This account has no public stats, or its privacy settings hide them.");
            return embed.build();
        }

        addStat(embed, pve, "secondsPlayed", "Time played");
        addStat(embed, pve, "activitiesCleared", "Activities cleared");
        addStat(embed, pve, "kills", "Kills");
        addStat(embed, pve, "deaths", "Deaths");
        addStat(embed, pve, "killsDeathsRatio", "K/D");
        return embed.build();
    }

    /** Digs out one mode's lifetime stats, tolerating every level being absent. */
    private static JsonObject mode(JsonObject stats, String modeName) {
        JsonObject merged = child(stats, "mergedAllCharacters");
        JsonObject results = child(merged, "results");
        JsonObject theMode = child(results, modeName);
        return child(theMode, "allTime");
    }

    private static void addStat(EmbedBuilder embed, JsonObject allTime, String key, String label) {
        JsonObject stat = child(allTime, key);
        JsonObject basic = child(stat, "basic");
        if (basic == null || !basic.has("displayValue")) {
            return;
        }
        embed.addField(label, basic.get("displayValue").getAsString(), true);
    }

    private static String platform(int membershipType) {
        return switch (membershipType) {
            case 1 -> "Xbox";
            case 2 -> "PlayStation";
            case 3 -> "Steam";
            case 4 -> "Battle.net";
            case 5 -> "Stadia";
            case 6 -> "Epic Games";
            default -> "Cross Save";
        };
    }

    // ---------------------------------------------------------------- helpers

    static MessageEmbed error(String message) {
        return new EmbedBuilder().setColor(Color.GRAY).setDescription(message).build();
    }

    private static JsonObject child(JsonObject parent, String key) {
        if (parent == null || !parent.has(key)) {
            return null;
        }
        JsonElement element = parent.get(key);
        return element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String string(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        return object.get(key).getAsString();
    }
}
