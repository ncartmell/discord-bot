package org.example;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The titles an account has earned, shown as the medals they are.
 *
 * <p>Seals are not a definition type of their own, which is the part that makes them awkward
 * to find. A seal is a presentation node that has a completion record attached, and that
 * record awards a title — so intersecting "nodes with a completion record" against "records
 * that award a title" identifies every seal in the game without carrying a list that would
 * need updating each expansion.
 *
 * <p>Completion comes from component 900. Records live in two places: account-wide ones
 * under {@code profileRecords} and per-character ones under {@code characterRecords}, and
 * seals appear in both depending on the seal, so both are merged before anything is decided.
 */
final class Seals {

    private static final Color GOLD = new Color(0xC8A657);

    /** DestinyRecordState bit 4. Set while the objectives are still outstanding. */
    private static final int OBJECTIVE_NOT_COMPLETED = 4;
    /** Bits 8 and 16: the game is deliberately hiding this one, so neither are we. */
    private static final int OBSCURED = 8;
    private static final int INVISIBLE = 16;

    private final BungieClient client;
    private final Manifest manifest;
    private final Store store;
    private final Ghost ghost;

    Seals(BungieClient client, Manifest manifest, Store store, Ghost ghost) {
        this.client = client;
        this.manifest = manifest;
        this.store = store;
        this.ghost = ghost;
    }

    MessageEmbed seals(String discordId) throws IOException {
        Store.User user = store.requireLinked(discordId);

        Map<Long, Long> seals = manifest.seals();
        if (seals.isEmpty()) {
            return Destiny.error(manifest.isReady()
                    ? "Couldn't find any seals in the manifest."
                    : "The manifest is still loading — try again shortly.");
        }

        JsonObject profile = client.profile(user.membershipType, user.membershipId, "200,900",
                ghost.accessTokenOrNull(user));
        JsonObject records = merged(profile);
        if (records == null) {
            return Destiny.error("Couldn't read your triumphs. Bungie hides these on profiles"
                    + " with privacy turned up.");
        }

        // Counted by title rather than by node. A gilded seal is a separate presentation
        // node pointing at the same title — Conqueror alone is four of them — so counting
        // nodes would claim the account is further off than it is.
        Set<String> seen = new LinkedHashSet<>();
        Set<String> earnedTitles = new LinkedHashSet<>();
        for (Map.Entry<Long, Long> seal : seals.entrySet()) {
            String title = manifest.title(seal.getValue());
            if (title == null) {
                continue;
            }
            String key = String.valueOf(seal.getValue());
            if (!records.has(key)) {
                continue;
            }
            int state = state(records.getAsJsonObject(key));
            if ((state & (OBSCURED | INVISIBLE)) != 0) {
                continue;
            }
            seen.add(title);
            if ((state & OBJECTIVE_NOT_COMPLETED) == 0) {
                earnedTitles.add(title);
            }
        }

        int available = seen.size();
        if (earnedTitles.isEmpty()) {
            return new EmbedBuilder()
                    .setTitle("No seals yet")
                    .setColor(Color.GRAY)
                    .setDescription("Nothing completed out of **" + available + "** seals available.")
                    .build();
        }

        List<String> earned = new ArrayList<>(earnedTitles);
        earned.sort(String::compareToIgnoreCase);
        String equipped = equippedTitle(profile, user);

        List<String> medals = new ArrayList<>();
        for (String title : earned) {
            boolean worn = title.equalsIgnoreCase(equipped);
            medals.add("🏅 " + (worn ? "**" + title + "**" : title));
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Seals")
                .setColor(GOLD)
                .setDescription("**" + earned.size() + "** of " + available + " earned")
                .addField("Medals", join(medals), false);
        if (equipped != null) {
            embed.addField("Worn", equipped, true);
        }
        return embed.setFooter("Gilding isn't shown — the API records it separately from the seal")
                .build();
    }

    /**
     * Account and character records in one map.
     *
     * <p>Some seals are tracked per character and some account-wide, and which is which is
     * not something the caller should have to know. Character records are merged in after
     * the profile ones so a character that has finished a seal wins over one that has not.
     */
    private static JsonObject merged(JsonObject profile) {
        JsonObject out = new JsonObject();
        JsonObject profileRecords = child(profile, "profileRecords", "data", "records");
        if (profileRecords != null) {
            for (Map.Entry<String, JsonElement> entry : profileRecords.entrySet()) {
                out.add(entry.getKey(), entry.getValue());
            }
        }

        JsonObject characterRecords = child(profile, "characterRecords", "data");
        if (characterRecords != null) {
            for (String characterId : characterRecords.keySet()) {
                JsonObject records = child(characterRecords, characterId, "records");
                if (records == null) {
                    continue;
                }
                for (Map.Entry<String, JsonElement> entry : records.entrySet()) {
                    JsonObject existing = out.has(entry.getKey())
                            ? out.getAsJsonObject(entry.getKey()) : null;
                    JsonObject candidate = entry.getValue().getAsJsonObject();
                    // Keep whichever character is further along.
                    boolean existingIncomplete = existing != null
                            && (state(existing) & OBJECTIVE_NOT_COMPLETED) != 0;
                    boolean candidateComplete =
                            (state(candidate) & OBJECTIVE_NOT_COMPLETED) == 0;
                    if (existing == null || (existingIncomplete && candidateComplete)) {
                        out.add(entry.getKey(), candidate);
                    }
                }
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** The title currently worn, taken from whichever character is active. */
    private String equippedTitle(JsonObject profile, Store.User user) {
        List<Characters.Character> all = Characters.of(child(profile, "characters", "data"));
        Characters.Character active = Characters.active(all, user.pinnedCharacterId);
        if (active == null || active.titleRecordHash() == 0) {
            return null;
        }
        return manifest.title(active.titleRecordHash());
    }

    private static int state(JsonObject record) {
        return record.has("state") ? record.get("state").getAsInt() : 0;
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
