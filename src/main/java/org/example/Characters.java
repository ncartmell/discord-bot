package org.example;

import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The three characters on an account, and which one the bot should be talking about.
 *
 * <p>Every gear action in the API is character-scoped: equipping, transfers, the postmaster,
 * vendor stock and the artifact all take a character id. The bot used to hold one, chosen
 * once when the account was linked, which quietly made half its answers about the wrong
 * guardian as soon as someone switched class.
 *
 * <p>The default here is therefore to follow whoever was played most recently, which is
 * what {@code dateLastPlayed} is for and is almost always the character someone means.
 * Pinning with {@code /character} exists for the other case: preparing one character while
 * playing another.
 */
final class Characters {

    /** One character, reduced to what the bot displays and decides on. */
    record Character(String id, int classType, int light, Instant lastPlayed, String emblemPath,
                     long titleRecordHash) {

        String className() {
            return switch (classType) {
                case 0 -> "Titan";
                case 1 -> "Hunter";
                case 2 -> "Warlock";
                default -> "Guardian";
            };
        }
    }

    private Characters() {
    }

    /**
     * Reads the {@code characters} component into a list, most recently played first.
     *
     * @param data the {@code characters.data} object, or null
     */
    static List<Character> of(JsonObject data) {
        List<Character> out = new ArrayList<>();
        if (data == null) {
            return out;
        }
        for (String id : data.keySet()) {
            JsonObject character = data.getAsJsonObject(id);
            out.add(new Character(
                    id,
                    character.has("classType") ? character.get("classType").getAsInt() : -1,
                    character.has("light") ? character.get("light").getAsInt() : 0,
                    instant(character),
                    character.has("emblemPath") ? character.get("emblemPath").getAsString() : null,
                    character.has("titleRecordHash") ? character.get("titleRecordHash").getAsLong() : 0));
        }
        out.sort(Comparator.comparing(Character::lastPlayed).reversed());
        return out;
    }

    /**
     * Picks the character to act on: the pinned one if it still exists, otherwise the most
     * recently played.
     *
     * <p>A pin that no longer resolves — a deleted character — falls back rather than
     * failing, because the alternative is every gear command erroring until someone works
     * out that the pin is stale.
     */
    static Character active(List<Character> all, String pinnedId) {
        if (all.isEmpty()) {
            return null;
        }
        if (pinnedId != null) {
            for (Character character : all) {
                if (character.id().equals(pinnedId)) {
                    return character;
                }
            }
        }
        // Sorted most recent first, so the head is the one that was played last.
        return all.get(0);
    }

    /** Finds a character by class name, e.g. "hunter". Null when nothing matches. */
    static Character byClassName(List<Character> all, String name) {
        String wanted = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        for (Character character : all) {
            if (character.className().toLowerCase(Locale.ROOT).equals(wanted)) {
                return character;
            }
        }
        return null;
    }

    private static Instant instant(JsonObject character) {
        if (!character.has("dateLastPlayed")) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(character.get("dateLastPlayed").getAsString());
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }
}
