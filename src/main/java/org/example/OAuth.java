package org.example;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Bungie's OAuth2 authorization-code flow.
 *
 * <p>Only the {@code /Destiny2/Actions/} endpoints need this — reading a profile, its
 * characters, equipment and current activity all work with the API key alone. The one
 * thing that genuinely requires a user token is acting on their behalf: equipping.
 *
 * <p>Two things about Bungie's implementation are worth knowing because they are not
 * what you would guess from the RFC:
 * <ul>
 *   <li>The scope is fixed when you register the application. Sending a {@code scope}
 *       parameter in the authorize request is rejected, so this deliberately omits it.</li>
 *   <li>The token endpoint answers with a bare OAuth payload, not the usual Bungie
 *       {@code {ErrorCode, Response}} envelope, so it cannot share the normal transport.</li>
 * </ul>
 *
 * <p>Refresh tokens last 90 days and are only issued to confidential clients — ones with
 * a client secret. A public client would have to re-link every hour, which is why the
 * secret is required here rather than optional.
 */
final class OAuth {

    private static final String AUTHORIZE = "https://www.bungie.net/en/oauth/authorize";
    private static final String TOKEN = "https://www.bungie.net/platform/app/oauth/token/";

    // Read on use rather than in a static initialiser. A missing value should surface as
    // an ordinary exception the command handler can report, not an ExceptionInInitializerError
    // thrown past every catch block on first touch.
    private static String clientId() {
        return Config.require("BUNGIE_CLIENT_ID");
    }

    private static String clientSecret() {
        return Config.require("BUNGIE_CLIENT_SECRET");
    }

    private OAuth() {
    }

    /**
     * The URL the user visits to approve the bot.
     *
     * @param state opaque value echoed back on the redirect; ties the returned code to the
     *              Discord user who asked for it, and stops someone pasting their own code
     *              into somebody else's link command.
     */
    static String authorizeUrl(String state) {
        return AUTHORIZE
                + "?client_id=" + encode(clientId())
                + "&response_type=code"
                + "&state=" + encode(state);
    }

    /** Exchanges the one-time code from the redirect for a token pair. */
    static Tokens exchange(String code) throws IOException {
        return token("grant_type=authorization_code&code=" + encode(code));
    }

    /** Trades a refresh token for a fresh pair. The old refresh token stops working. */
    static Tokens refresh(String refreshToken) throws IOException {
        return token("grant_type=refresh_token&refresh_token=" + encode(refreshToken));
    }

    private static Tokens token(String form) throws IOException {
        HttpURLConnection con;
        try {
            con = (HttpURLConnection) URI.create(TOKEN).toURL().openConnection();
        } catch (Exception e) {
            throw new IOException("Could not open the Bungie token endpoint", e);
        }

        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        con.setRequestProperty("X-API-Key", Config.require("BUNGIE_API_KEY"));
        // Confidential clients authenticate with HTTP Basic rather than posting the secret.
        con.setRequestProperty("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString((clientId() + ":" + clientSecret()).getBytes(StandardCharsets.UTF_8)));
        con.setConnectTimeout(10_000);
        con.setReadTimeout(15_000);
        con.setDoOutput(true);

        try (OutputStream out = con.getOutputStream()) {
            out.write(form.getBytes(StandardCharsets.UTF_8));
        }

        int status = con.getResponseCode();
        InputStream stream = status >= 400 ? con.getErrorStream() : con.getInputStream();
        if (stream == null) {
            throw new IOException("Bungie token endpoint returned HTTP " + status + " with no body");
        }

        String raw;
        try (InputStream in = stream) {
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        JsonObject json;
        try {
            json = JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("Bungie token endpoint returned HTTP " + status
                    + " with an unreadable body", e);
        }

        if (status >= 400 || !json.has("access_token")) {
            // error_description is the useful one; "invalid_grant" on its own tells you nothing.
            String error = json.has("error_description") ? json.get("error_description").getAsString()
                    : json.has("error") ? json.get("error").getAsString()
                    : "HTTP " + status;
            throw new IOException(error);
        }

        long now = System.currentTimeMillis() / 1000;
        Tokens tokens = new Tokens();
        tokens.accessToken = json.get("access_token").getAsString();
        tokens.accessExpiresAt = now + json.get("expires_in").getAsLong();
        tokens.refreshToken = json.get("refresh_token").getAsString();
        tokens.refreshExpiresAt = now + json.get("refresh_expires_in").getAsLong();
        tokens.membershipId = json.has("membership_id") ? json.get("membership_id").getAsString() : null;
        return tokens;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** One token pair, with absolute expiry times in epoch seconds. */
    static final class Tokens {
        String accessToken;
        long accessExpiresAt;
        String refreshToken;
        long refreshExpiresAt;
        /** The Bungie.net membership id, which is not the same as a Destiny membership id. */
        String membershipId;
    }
}
