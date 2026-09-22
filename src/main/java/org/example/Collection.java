package org.example;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What the account already owns, used only to answer "is this worth buying".
 *
 * <p>Collections are read here rather than surfaced as a command of their own. A list of
 * several thousand collectibles is not something anyone wants in a Discord embed; the useful
 * question is narrower — when Xûr is selling an exotic, have you got it already — and that
 * is a cross-reference against a vendor's stock, not a browse.
 *
 * <p>The link from a sale item to a collectible is {@code collectibleHash} on the item
 * definition, which {@link Manifest} keeps for exactly this. Items without one are not in
 * Collections at all, so nothing can be said about them either way.
 */
final class Collection {

    /** DestinyCollectibleState bit 0. Set while the account has never acquired it. */
    private static final int NOT_ACQUIRED = 1;

    private final BungieClient client;
    private final Ghost ghost;

    Collection(BungieClient client, Ghost ghost) {
        this.client = client;
        this.ghost = ghost;
    }

    /**
     * The collectible hashes this account has acquired.
     *
     * <p>Returns null rather than an empty set when the data cannot be read, because the two
     * mean opposite things: empty is "owns nothing", null is "do not claim anything". A
     * caller that confused them would tell someone every exotic Xûr has is new to them.
     */
    Set<Long> acquired(Store.User user) {
        if (user == null || !user.isLinked()) {
            return null;
        }
        try {
            JsonObject profile = client.profile(user.membershipType, user.membershipId, "800",
                    ghost.accessTokenOrNull(user));
            JsonObject collectibles = child(profile, "profileCollectibles", "data", "collectibles");
            if (collectibles == null) {
                return null;
            }

            Set<Long> owned = new HashSet<>();
            for (Map.Entry<String, JsonElement> entry : collectibles.entrySet()) {
                JsonObject state = entry.getValue().getAsJsonObject();
                int bits = state.has("state") ? state.get("state").getAsInt() : NOT_ACQUIRED;
                if ((bits & NOT_ACQUIRED) == 0) {
                    owned.add(Long.parseLong(entry.getKey()));
                }
            }
            return owned;
        } catch (Exception e) {
            // A vendor listing is still worth showing without the ownership marks.
            return null;
        }
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
