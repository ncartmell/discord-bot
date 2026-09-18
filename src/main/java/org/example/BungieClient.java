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
 * A thin client for the public parts of the Bungie.net API.
 *
 * <p>Everything here works with an API key alone. Anything under {@code /Destiny2/Actions/}
 * — equipping, transferring, loadouts — requires a full OAuth flow and is deliberately absent.
 */
public class BungieClient {

    private static final String BASE = "https://www.bungie.net/Platform";
    private static final String API_KEY = Config.require("BUNGIE_API_KEY");

    /** Bungie returns HTTP 200 with an error code in the body, so success must be checked explicitly. */
    private static final int SUCCESS = 1;

    // ---------------------------------------------------------------- transport

    private JsonObject send(String method, String path, String body) throws IOException {
        HttpURLConnection con;
        try {
            con = (HttpURLConnection) new URI(BASE + path).toURL().openConnection();
        } catch (Exception e) {
            throw new IOException("Bad request URI: " + path, e);
        }

        con.setRequestMethod(method);
        con.setRequestProperty("X-API-KEY", API_KEY);
        con.setRequestProperty("Accept", "application/json");
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
            throw new IOException("Bungie API error " + errorCode + ": " + message);
        }
        return json;
    }

    JsonObject get(String path) throws IOException {
        return send("GET", path, null);
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

    JsonObject post(String path, String body) throws IOException {
        return send("POST", path, body);
    }

    // ---------------------------------------------------------------- endpoints

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
}
