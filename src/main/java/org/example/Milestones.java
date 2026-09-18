package org.example;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Resolves the public milestone feed into readable names.
 *
 * <p>Shared by the {@code /weekly} command and the background watcher so both produce the
 * same list.
 */
final class Milestones {

    private static final String MILESTONE_DEFINITION = "DestinyMilestoneDefinition";

    private Milestones() {
    }

    /**
     * Returns the names of currently active milestones, sorted alphabetically.
     *
     * <p>The milestone feed identifies each entry only by hash, so every one needs a
     * manifest lookup — which is exactly the repeated, small, stable set of lookups the
     * {@link ManifestCache} exists for. After the first call this costs nothing.
     *
     * <p>Milestones with no display name are skipped: the feed includes internal entries
     * that are not meant to be shown to players.
     */
    static List<String> activeMilestoneNames(BungieClient client, ManifestCache manifest) throws IOException {
        JsonObject milestones = client.publicMilestones();
        List<String> names = new ArrayList<>();

        for (Map.Entry<String, JsonElement> entry : milestones.entrySet()) {
            JsonObject milestone = entry.getValue().getAsJsonObject();
            if (!milestone.has("milestoneHash")) {
                continue;
            }

            long hash = milestone.get("milestoneHash").getAsLong();
            String name;
            try {
                name = manifest.displayName(MILESTONE_DEFINITION, hash);
            } catch (IOException e) {
                // One unresolvable hash should not lose the whole list.
                continue;
            }

            if (name != null) {
                names.add(name);
            }
        }

        names.sort(String::compareToIgnoreCase);
        return names;
    }
}
