package org.example;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

public class BungieClient {
    private static final String BUNGIE_API_KEY = Config.require("BUNGIE_API_KEY");
//    String BungieAPIPath = "https://www.bungie.net/Platform";
//    String base_auth_url = "https://www.bungie.net/en/oauth/authorize/";
//    String token_url = "https://www.bungie.net/platform/app/oauth/token/";
//    String redirect_url = "https://ncartmell.co.uk/d2botkey";

    public String sendRequest() throws IOException, URISyntaxException {
        // Endpoint for Gjallarhorn
        String urlString = "https://www.bungie.net/platform/Destiny/Manifest/InventoryItem/1274330687/";

        URI uri = new URI(urlString);
        HttpURLConnection con = (HttpURLConnection) uri.toURL().openConnection();

        con.setRequestMethod("GET");

        // Set header
        con.setRequestProperty("X-API-KEY", BUNGIE_API_KEY);

        int responseCode = con.getResponseCode();
        System.out.println("\nSending 'GET' request to Bungie.Net : " + urlString);
        System.out.println("Response Code : " + responseCode);

        BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
        String inputLine;
        String response = "";

        while ((inputLine = in.readLine()) != null) {
            response += inputLine;
        }

        in.close();

        JsonParser parser = new JsonParser();
        JsonObject json = (JsonObject) parser.parse(response);

        return json.getAsJsonObject("Response").getAsJsonObject("data").getAsJsonObject("inventoryItem").get("itemName").getAsString();
    }
}
