package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * A thin client for the Bungie.net API.
 *
 * <p>Most of this works with an API key alone, including everything the bot reads: profiles,
 * characters, equipment, the vault and the current activity. Only the {@code /Destiny2/Actions/}
 * endpoints act on a player's behalf, and those take an additional bearer token — see
 * {@link OAuth}. Bungie wants both headers on an authenticated call, not just the bearer.
 */
public class BungieClient {

    private static final String BASE = "https://www.bungie.net/Platform";
    /**
     * Post-game carnage reports are only served from this host.
     *
     * <p>The same path on {@code www.bungie.net} answers 301, and HttpURLConnection will not
     * follow a redirect to a different host, so a report fetched from the usual base comes
     * back as an empty body rather than an error.
     */
    private static final String STATS_BASE = "https://stats.bungie.net/Platform";
    private static final String API_KEY = Config.require("BUNGIE_API_KEY");

    /** Bungie returns HTTP 200 with an error code in the body, so success must be checked explicitly. */
    private static final int SUCCESS = 1;

    // ---------------------------------------------------------------- transport

    private JsonObject send(String method, String path, String body, String bearer) throws IOException {
        return send(BASE, method, path, body, bearer);
    }

    private JsonObject send(String base, String method, String path, String body, String bearer)
            throws IOException {
        HttpURLConnection con;
        try {
            con = (HttpURLConnection) new URI(base + path).toURL().openConnection();
        } catch (Exception e) {
            throw new IOException("Bad request URI: " + path, e);
        }

        con.setRequestMethod(method);
        con.setRequestProperty("X-API-KEY", API_KEY);
        con.setRequestProperty("Accept", "application/json");
        if (bearer != null) {
            con.setRequestProperty("Authorization", "Bearer " + bearer);
        }
        con.setConnectTimeout(10_000);
        con.setReadTimeout(15_000);

        if (body != null) {
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = con.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }

        int status = con.getResponseCode();
        // Read the error stream on failure — Bungie puts a useful message in there.
        InputStream stream = status >= 400 ? con.getErrorStream() : con.getInputStream();
        if (stream == null) {
            throw new IOException("Bungie API returned HTTP " + status + " with no body for " + path);
        }

        String raw;
        try (InputStream in = stream) {
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
        int errorCode = json.has("ErrorCode") ? json.get("ErrorCode").getAsInt() : -1;
        if (errorCode != SUCCESS) {
            String message = json.has("Message") ? json.get("Message").getAsString() : "unknown error";
            throw new BungieException(errorCode, message);
        }
        return json;
    }

    JsonObject get(String path) throws IOException {
        return send("GET", path, null, null);
    }

    JsonObject post(String path, String body) throws IOException {
        return send("POST", path, body, null);
    }

    /** A POST made as the linked user. Required for anything under {@code /Destiny2/Actions/}. */
    JsonObject postAs(String path, String body, String accessToken) throws IOException {
        return send("POST", path, body, accessToken);
    }

    /**
     * Extracts the {@code Response} payload, treating its absence as a failure.
     *
     * <p>Bungie answers a lookup for a hash that no longer exists with {@code ErrorCode 1,
     * "Ok"} and no {@code Response} at all, rather than an error. Callers would otherwise
     * see a null-shaped crash instead of "not found".
     */
    private JsonObject response(String path) throws IOException {
        JsonObject json = get(path);
        if (!json.has("Response") || json.get("Response").isJsonNull()) {
            throw new IOException("Bungie returned no data for " + path
                    + " — the identifier is probably stale.");
        }
        return json.getAsJsonObject("Response");
    }

    // ---------------------------------------------------------------- public endpoints

    /** Looks up a single manifest definition by hash. Prefer {@link ManifestCache} for repeat lookups. */
    JsonObject entityDefinition(String entityType, long hash) throws IOException {
        return response("/Destiny2/Manifest/" + entityType + "/" + hash + "/");
    }

    /** Currently active public milestones, keyed by milestone hash. */
    JsonObject publicMilestones() throws IOException {
        return response("/Destiny2/Milestones/");
    }

    /** Recent Bungie.net news articles. */
    JsonArray newsArticles() throws IOException {
        return response("/Content/Rss/NewsArticles/0/").getAsJsonArray("NewsArticles");
    }

    /**
     * Finds Destiny accounts for a Bungie name.
     *
     * @param name the part before the hash, e.g. {@code Guardian}
     * @param code the four digits after it, e.g. {@code 1234}
     */
    JsonArray searchPlayer(String name, int code) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("displayName", name);
        body.addProperty("displayNameCode", code);
        // membershipType -1 means "search every platform"
        return post("/Destiny2/SearchDestinyPlayerByBungieName/-1/", body.toString())
                .getAsJsonArray("Response");
    }

    /** Lifetime historical stats for an account, merged across all of its characters. */
    JsonObject accountStats(int membershipType, String membershipId) throws IOException {
        return response("/Destiny2/" + membershipType + "/Account/" + membershipId + "/Stats/");
    }

    /**
     * The Destiny accounts attached to a Bungie.net membership.
     *
     * <p>The id OAuth hands back identifies the Bungie.net account, which is not the
     * membership Destiny endpoints want. This is the step that turns one into the other.
     */
    JsonObject linkedProfiles(String bungieMembershipId) throws IOException {
        return response("/Destiny2/254/Profile/" + bungieMembershipId
                + "/LinkedProfiles/?getAllMemberships=true");
    }

    /**
     * A profile with the requested components.
     *
     * @param components component numbers, e.g. 200 for characters, 205 for equipment
     */
    JsonObject profile(int membershipType, String membershipId, String components) throws IOException {
        return profile(membershipType, membershipId, components, null);
    }

    /**
     * A profile read as the linked user, which is the only way to see private components.
     *
     * <p>Characters, equipment and item sockets come back for anyone. The vault (102) and
     * character inventories (201) do not: they answer with {@code privacy: 2} and no data
     * unless the request carries the owner's token. Since those are exactly what transfers,
     * making room and the postmaster depend on, every read of a linked account goes out
     * authenticated.
     */
    JsonObject profile(int membershipType, String membershipId, String components,
                       String accessToken) throws IOException {
        String path = "/Destiny2/" + membershipType + "/Profile/" + membershipId
                + "/?components=" + components;
        JsonObject json = send("GET", path, null, accessToken);
        if (!json.has("Response") || json.get("Response").isJsonNull()) {
            throw new IOException("Bungie returned no data for " + path
                    + " — the identifier is probably stale.");
        }
        return json.getAsJsonObject("Response");
    }

    /**
     * Recent activities for a character, newest first.
     *
     * @param mode activity mode filter, 0 for everything
     */
    JsonArray activityHistory(int membershipType, String membershipId, String characterId,
                              int count, int mode) throws IOException {
        JsonObject response = response("/Destiny2/" + membershipType + "/Account/" + membershipId
                + "/Character/" + characterId + "/Stats/Activities/?count=" + count
                + "&mode=" + mode + "&page=0");
        JsonArray activities = response.getAsJsonArray("activities");
        return activities == null ? new JsonArray() : activities;
    }

    /** The full breakdown of one activity, including everyone who was in it. */
    JsonObject postGameCarnageReport(String instanceId) throws IOException {
        JsonObject json = send(STATS_BASE, "GET",
                "/Destiny2/Stats/PostGameCarnageReport/" + instanceId + "/", null, null);
        if (!json.has("Response") || json.get("Response").isJsonNull()) {
            throw new IOException("No report for activity " + instanceId
                    + " — reports are dropped after a while.");
        }
        return json.getAsJsonObject("Response");
    }

    /** Every activity the character has played, with completion counts and fastest times. */
    JsonArray aggregateActivityStats(int membershipType, String membershipId, String characterId)
            throws IOException {
        JsonObject response = response("/Destiny2/" + membershipType + "/Account/" + membershipId
                + "/Character/" + characterId + "/Stats/AggregateActivityStats/");
        JsonArray activities = response.getAsJsonArray("activities");
        return activities == null ? new JsonArray() : activities;
    }

    /** Per-weapon kill counts for a character. */
    JsonArray uniqueWeapons(int membershipType, String membershipId, String characterId)
            throws IOException {
        JsonObject response = response("/Destiny2/" + membershipType + "/Account/" + membershipId
                + "/Character/" + characterId + "/Stats/UniqueWeapons/");
        JsonArray weapons = response.getAsJsonArray("weapons");
        return weapons == null ? new JsonArray() : weapons;
    }

    /**
     * Vendors whose stock is the same for everyone.
     *
     * <p>In practice this is Xûr, and usefully it needs no token — the per-character vendor
     * endpoint refuses with error 12 without one.
     */
    JsonObject publicVendors(String components) throws IOException {
        return response("/Destiny2/Vendors/?components=" + components);
    }

    /** Vendors available to one character, which is everything Xûr is not. */
    JsonObject characterVendors(int membershipType, String membershipId, String characterId,
                                String components, String accessToken) throws IOException {
        String path = "/Destiny2/" + membershipType + "/Profile/" + membershipId
                + "/Character/" + characterId + "/Vendors/?components=" + components;
        JsonObject json = send("GET", path, null, accessToken);
        if (!json.has("Response") || json.get("Response").isJsonNull()) {
            throw new IOException("Bungie returned no vendor data.");
        }
        return json.getAsJsonObject("Response");
    }

    /**
     * The clans a player belongs to.
     *
     * <p>Clans are groups, so this is under {@code /GroupV2/}, not {@code /Destiny2/}. The
     * trailing path values are the filter (0, meaning all) and the group type (1, clan).
     */
    JsonArray clansFor(int membershipType, String membershipId) throws IOException {
        JsonObject response = response("/GroupV2/User/" + membershipType + "/" + membershipId + "/0/1/");
        JsonArray results = response.getAsJsonArray("results");
        return results == null ? new JsonArray() : results;
    }

    /** Which of the clan's weekly engram rewards have been earned. */
    JsonObject clanWeeklyRewardState(String groupId) throws IOException {
        return response("/Destiny2/Clan/" + groupId + "/WeeklyRewardState/");
    }

    /** Locks or unlocks an item, which is what stops it being dismantled by accident. */
    void setLockState(int membershipType, String characterId, String instanceId, boolean locked,
                      String accessToken) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("state", locked);
        body.addProperty("itemId", Long.parseLong(instanceId));
        body.addProperty("characterId", Long.parseLong(characterId));
        body.addProperty("membershipType", membershipType);

        postAs("/Destiny2/Actions/Items/SetLockState/", body.toString(), accessToken);
    }

    // ---------------------------------------------------------------- actions (OAuth)

    /**
     * Equips a set of items by instance id.
     *
     * <p>Two behaviours from the endpoint's own documentation shape how callers must use
     * this: it only works when the player is "in a social space, in orbit, or offline",
     * and "any items not found on your character will be ignored" — so anything sitting
     * in the vault is silently skipped rather than reported as an error.
     *
     * @return the per-item results, each with an {@code itemInstanceId} and {@code equipStatus}
     */
    JsonArray equipItems(int membershipType, String characterId, java.util.List<String> instanceIds,
                         String accessToken) throws IOException {
        JsonArray ids = new JsonArray();
        for (String id : instanceIds) {
            ids.add(Long.parseLong(id));
        }

        JsonObject body = new JsonObject();
        body.add("itemIds", ids);
        body.addProperty("characterId", Long.parseLong(characterId));
        body.addProperty("membershipType", membershipType);

        JsonObject json = postAs("/Destiny2/Actions/Items/EquipItems/", body.toString(), accessToken);
        if (!json.has("Response") || json.get("Response").isJsonNull()) {
            throw new IOException("Bungie accepted the equip but said nothing about what happened.");
        }
        JsonArray results = json.getAsJsonObject("Response").getAsJsonArray("equipResults");
        return results == null ? new JsonArray() : results;
    }

    /** Moves one item between a character and the vault. */
    void transferItem(int membershipType, String characterId, String instanceId, long itemHash,
                      boolean toVault, String accessToken) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("itemReferenceHash", itemHash);
        body.addProperty("stackSize", 1);
        body.addProperty("transferToVault", toVault);
        body.addProperty("itemId", Long.parseLong(instanceId));
        body.addProperty("characterId", Long.parseLong(characterId));
        body.addProperty("membershipType", membershipType);

        postAs("/Destiny2/Actions/Items/TransferItem/", body.toString(), accessToken);
    }

    /**
     * Pulls an item out of the postmaster onto the character.
     *
     * <p>Bungie's own wording is "with whatever implications that may entail" — some pulls
     * are destructive, which the item definition flags as
     * {@code doesPostmasterPullHaveSideEffects}. Instanced items need both the reference
     * hash and the instance id; stacked ones need the hash and a stack size.
     */
    void pullFromPostmaster(int membershipType, String characterId, long itemHash,
                            String instanceId, int stackSize, String accessToken) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("itemReferenceHash", itemHash);
        body.addProperty("stackSize", Math.max(1, stackSize));
        if (instanceId != null) {
            body.addProperty("itemId", Long.parseLong(instanceId));
        }
        body.addProperty("characterId", Long.parseLong(characterId));
        body.addProperty("membershipType", membershipType);

        postAs("/Destiny2/Actions/Items/PullFromPostmaster/", body.toString(), accessToken);
    }

    /**
     * Inserts a plug into one of an item's sockets: a perk, armour mod, shader, ornament,
     * or any of a subclass's abilities, aspects and fragments.
     *
     * <p>Uses the "free" variant, which Bungie documents as available to third-party
     * applications for "free and reversible" socket actions and which needs only the scope
     * this bot already holds. The other variant, {@code InsertSocketPlug}, covers plugs with
     * side effects and requires {@code AdvancedWriteActions} — a scope Bungie grants case by
     * case, so it is out of reach here.
     *
     * <p>Carries the same location restriction as equipping: social space, orbit or offline.
     *
     * @param socketIndex index into the item's default socket array
     * @param plugItemHash the plug to put in it
     */
    void insertPlugFree(int membershipType, String characterId, String itemInstanceId,
                        int socketIndex, long plugItemHash, String accessToken) throws IOException {
        JsonObject plug = new JsonObject();
        plug.addProperty("socketIndex", socketIndex);
        // 0 selects the default socket array rather than the separate intrinsic one.
        plug.addProperty("socketArrayType", 0);
        plug.addProperty("plugItemHash", plugItemHash);

        JsonObject body = new JsonObject();
        body.add("plug", plug);
        body.addProperty("itemId", Long.parseLong(itemInstanceId));
        body.addProperty("characterId", Long.parseLong(characterId));
        body.addProperty("membershipType", membershipType);

        postAs("/Destiny2/Actions/Items/InsertSocketPlugFree/", body.toString(), accessToken);
    }

    /**
     * Saves whatever the character is currently wearing into one of the 20 in-game loadout slots.
     *
     * <p>Unlike the equip endpoints this carries no documented restriction on where the
     * player is, so it works mid-activity. It can only capture the current equipment
     * though — there is no endpoint that writes arbitrary items into a slot.
     */
    void snapshotLoadout(int membershipType, String characterId, int loadoutIndex,
                         String accessToken) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("loadoutIndex", loadoutIndex);
        body.addProperty("characterId", Long.parseLong(characterId));
        body.addProperty("membershipType", membershipType);

        postAs("/Destiny2/Actions/Loadouts/SnapshotLoadout/", body.toString(), accessToken);
    }

    /** A Bungie error code and message, kept apart so callers can react to specific codes. */
    static final class BungieException extends IOException {
        private static final long serialVersionUID = 1L;

        final int code;

        BungieException(int code, String message) {
            super("Bungie API error " + code + ": " + message);
            this.code = code;
        }
    }
}
