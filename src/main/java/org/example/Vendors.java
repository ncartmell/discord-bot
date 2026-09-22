package org.example;

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

/**
 * What vendors are selling.
 *
 * <p>Split in two by what Bungie will tell you without a token. Xûr's stock is the same for
 * everyone, so it comes back from the public vendors endpoint with an API key alone — which
 * is the useful half, since "is Xûr worth the trip" is the question people actually ask and
 * he is only around from Friday to Tuesday.
 *
 * <p>Every other vendor is per-character: inventories are filtered by what you have already
 * bought, your rank and your class, so {@code Character/.../Vendors/} refuses with error 12
 * without the owner's token.
 */
final class Vendors {

    private static final Color ACCENT = new Color(0x00A8E1);
    private static final long XUR = 2190858386L;

    private final BungieClient client;
    private final Manifest manifest;
    private final Store store;
    private final Ghost ghost;

    Vendors(BungieClient client, Manifest manifest, Store store, Ghost ghost) {
        this.client = client;
        this.manifest = manifest;
        this.store = store;
        this.ghost = ghost;
    }

    /** Xûr's stock. Needs no account and no token. */
    MessageEmbed xur() throws IOException {
        JsonObject response = client.publicVendors("400,402");
        JsonObject sales = child(response, "sales", "data");
        JsonObject block = sales == null || !sales.has(String.valueOf(XUR)) ? null
                : sales.getAsJsonObject(String.valueOf(XUR));

        if (block == null) {
            return new EmbedBuilder()
                    .setTitle("Xûr")
                    .setColor(ACCENT)
                    .setDescription("Not here. He arrives Friday and leaves at Tuesday's reset.")
                    .build();
        }

        Map<String, List<String>> grouped = group(block.getAsJsonObject("saleItems"));
        if (grouped.isEmpty()) {
            return new EmbedBuilder()
                    .setTitle("Xûr")
                    .setColor(ACCENT)
                    .setDescription("Here, but selling nothing the manifest recognises.")
                    .build();
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Xûr — Agent of the Nine")
                .setColor(ACCENT)
                .setDescription("Here until Tuesday's reset.");
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            embed.addField(entry.getKey(), join(entry.getValue()), false);
        }
        return embed.setFooter("Stock is the same for everyone").build();
    }

    /** Any other vendor, which needs the linked account because stock is per-character. */
    MessageEmbed vendor(String discordId, String query) throws IOException {
        if (query == null || query.isBlank()) {
            return Destiny.error("Name a vendor, e.g. `Banshee-44` or `Ada-1`. For Xûr use `/xur`.");
        }
        Store.User user = store.requireLinked(discordId);

        List<Long> matches = manifest.findVendors(query);
        if (matches.isEmpty()) {
            return Destiny.error("No vendor matching `" + query + "`."
                    + (manifest.isReady() ? "" : " The manifest is still loading — try again shortly."));
        }

        JsonObject response = client.characterVendors(user.membershipType, user.membershipId,
                user.characterId, "400,402", ghost.accessToken(user));
        JsonObject sales = child(response, "sales", "data");
        if (sales == null) {
            return Destiny.error("Bungie returned no vendor stock.");
        }

        // Several hashes can share a name; take whichever one is actually available today.
        for (Long hash : matches) {
            String key = String.valueOf(hash);
            if (!sales.has(key)) {
                continue;
            }
            Map<String, List<String>> grouped = group(sales.getAsJsonObject(key)
                    .getAsJsonObject("saleItems"));
            if (grouped.isEmpty()) {
                continue;
            }

            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle(manifest.vendorName(hash))
                    .setColor(ACCENT);
            for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
                embed.addField(entry.getKey(), join(entry.getValue()), false);
            }
            return embed.setFooter("Your stock — vendor inventories differ per character").build();
        }

        return Destiny.error("**" + String.join(", ", manifest.vendorNamesMatching(query))
                + "** isn't offering anything to this character right now."
                + "\n\nSome vendors only appear in certain seasons or destinations.");
    }

    /** Groups a vendor's stock by item type, exotics first, dropping the unnameable. */
    private Map<String, List<String>> group(JsonObject saleItems) {
        Map<String, List<String>> exotic = new LinkedHashMap<>();
        Map<String, List<String>> rest = new LinkedHashMap<>();
        if (saleItems == null) {
            return exotic;
        }

        for (Map.Entry<String, JsonElement> entry : saleItems.entrySet()) {
            JsonObject sale = entry.getValue().getAsJsonObject();
            if (!sale.has("itemHash")) {
                continue;
            }
            long hash = sale.get("itemHash").getAsLong();
            Manifest.Item item = manifest.item(hash);
            if (item == null || item.name().isBlank()) {
                continue;
            }
            // Vendors list category headers and placeholders as sale items; they have no
            // type, which is the only reliable way to tell them from real stock.
            String type = item.type();
            if (type == null || type.isBlank()) {
                continue;
            }

            boolean isExotic = "Exotic".equalsIgnoreCase(item.tier());
            String cost = cost(sale);
            (isExotic ? exotic : rest)
                    .computeIfAbsent(isExotic ? "Exotics" : type, k -> new ArrayList<>())
                    .add("**" + item.name() + "** — " + type + (cost.isEmpty() ? "" : " · " + cost));
        }

        exotic.putAll(rest);
        return exotic;
    }

    /**
     * What a sale item costs, as "41 Strange Coin".
     *
     * <p>Not everything has a price: category headers and free rewards carry an empty cost
     * array, which is one of the ways they give themselves away.
     */
    private String cost(JsonObject sale) {
        if (!sale.has("costs") || !sale.get("costs").isJsonArray()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (JsonElement element : sale.getAsJsonArray("costs")) {
            JsonObject entry = element.getAsJsonObject();
            if (!entry.has("itemHash") || !entry.has("quantity")) {
                continue;
            }
            int quantity = entry.get("quantity").getAsInt();
            if (quantity <= 0) {
                continue;
            }
            parts.add(String.format(java.util.Locale.UK, "%,d", quantity) + " "
                    + manifest.itemName(entry.get("itemHash").getAsLong()));
        }
        return String.join(" + ", parts);
    }

    private static String join(List<String> values) {
        String joined = String.join("\n", values);
        return joined.length() > 1000 ? joined.substring(0, 997) + "..." : joined;
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
}
