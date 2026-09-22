package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.awt.Color;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Reputation ranks, the season pass, and what is still outstanding this week.
 *
 * <p>All three come out of component 202, {@code characterProgressions}, which the artifact
 * command was already fetching and reading one field of. The rest of that payload is the
 * answer to "where am I with Crucible" and "what have I not done yet this week", so these
 * cost no more requests than were already being made.
 *
 * <p>Ranks are found by asking the manifest which progressions have a rank icon rather than
 * by listing hashes. A character carries around a hundred progressions, most of them
 * internal counters; the ones a player would call a rank are exactly the ones Bungie gives
 * an icon to, and that holds across seasons in a way a hardcoded list would not.
 */
final class Progress {

    private static final Color ACCENT = new Color(0x00A8E1);

    /** Enough to be useful without turning the embed into a wall of vendor reputations. */
    private static final int MAX_RANKS = 10;

    private final BungieClient client;
    private final Manifest manifest;
    private final ManifestCache definitions;
    private final Store store;
    private final Ghost ghost;

    Progress(BungieClient client, Manifest manifest, ManifestCache definitions, Store store, Ghost ghost) {
        this.client = client;
        this.manifest = manifest;
        this.definitions = definitions;
        this.store = store;
        this.ghost = ghost;
    }

    // ---------------------------------------------------------------- ranks

    /** Reputation ranks and the season pass, for the character commands are pointed at. */
    MessageEmbed ranks(String discordId) throws IOException {
        Store.User user = store.requireLinked(discordId);
        JsonObject profile = client.profile(user.membershipType, user.membershipId, "100,200,202",
                ghost.accessTokenOrNull(user));
        // Component 200 rides along, so the active character costs no extra request.
        String characterId = ghost.activeCharacter(user, child(profile, "characters", "data"));
        JsonObject progressions = child(profile, "characterProgressions", "data", characterId);
        if (progressions == null || !progressions.has("progressions")) {
            return Destiny.error("Couldn't read your progress. Bungie hides this on profiles"
                    + " with privacy turned up.");
        }
        JsonObject tracks = progressions.getAsJsonObject("progressions");

        record Rank(String name, String step, int level, int cap, int resets, int progress) {
        }
        List<Rank> ranks = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : tracks.entrySet()) {
            long hash = Long.parseLong(entry.getKey());
            Manifest.Progression definition = manifest.progression(hash);
            if (definition == null || !definition.ranked() || definition.name().isBlank()) {
                continue;
            }
            JsonObject state = entry.getValue().getAsJsonObject();
            int level = integer(state, "level");
            int progress = integer(state, "currentProgress");
            if (level <= 0 && progress <= 0) {
                continue;
            }
            ranks.add(new Rank(definition.name(),
                    definition.stepName(integer(state, "stepIndex")),
                    level,
                    definition.rankCount(),
                    integer(state, "currentResetCount"),
                    progress));
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Ranks")
                .setColor(ACCENT);

        String pass = seasonPass(profile, tracks);
        if (pass != null) {
            embed.addField("Season pass", pass, false);
        }

        if (ranks.isEmpty()) {
            embed.setDescription("Nothing ranked up yet this season.");
            return embed.build();
        }

        ranks.sort(Comparator.comparingInt(Rank::progress).reversed());
        List<String> lines = new ArrayList<>();
        for (Rank rank : ranks.subList(0, Math.min(MAX_RANKS, ranks.size()))) {
            lines.add("**" + rank.name() + "** — "
                    + (rank.step() == null ? "rank " + rank.level() : rank.step())
                    + (rank.cap() > 0 ? " (" + rank.level() + "/" + rank.cap() + ")" : "")
                    + (rank.resets() > 0
                       ? " · " + rank.resets() + (rank.resets() == 1 ? " reset" : " resets") : ""));
        }
        embed.setDescription(String.join("\n", lines));
        if (ranks.size() > MAX_RANKS) {
            embed.setFooter((ranks.size() - MAX_RANKS) + " lower ranks not shown");
        }
        return embed.build();
    }

    /**
     * The season pass line, or null when it cannot be worked out.
     *
     * <p>Which progression is the season pass changes every season, so rather than carry a
     * hash that goes stale this walks there: the profile says which season is current, the
     * season definition names its pass, and the pass definition names the two progressions —
     * the ordinary one to 100 and the prestige one that carries on past it.
     */
    private String seasonPass(JsonObject profile, JsonObject tracks) {
        try {
            JsonObject profileData = child(profile, "profile", "data");
            if (profileData == null || !profileData.has("currentSeasonHash")) {
                return null;
            }
            JsonObject season = definitions.definition("DestinySeasonDefinition",
                    profileData.get("currentSeasonHash").getAsLong());
            if (!season.has("seasonPassHash")) {
                return null;
            }
            JsonObject seasonPass = definitions.definition("DestinySeasonPassDefinition",
                    season.get("seasonPassHash").getAsLong());

            int level = levelOf(tracks, seasonPass, "rewardProgressionHash");
            int prestige = levelOf(tracks, seasonPass, "prestigeProgressionHash");
            if (level <= 0 && prestige <= 0) {
                return null;
            }

            String name = child(season, "displayProperties") != null
                    && season.getAsJsonObject("displayProperties").has("name")
                    ? season.getAsJsonObject("displayProperties").get("name").getAsString()
                    : "This season";

            return "**" + name + "** — rank " + level
                    + (prestige > 0 ? " (+" + prestige + " prestige)" : "");
        } catch (Exception e) {
            // The season pass is a nice-to-have on this embed; the ranks below it are the point.
            return null;
        }
    }

    private static int levelOf(JsonObject tracks, JsonObject seasonPass, String key) {
        if (!seasonPass.has(key)) {
            return 0;
        }
        String hash = seasonPass.get(key).getAsString();
        return tracks.has(hash) ? integer(tracks.getAsJsonObject(hash), "level") : 0;
    }

    // ---------------------------------------------------------------- weekly checklist

    /**
     * What is left to do this week, with anything already finished knocked off the list.
     *
     * <p>A milestone does not carry a "done" flag, so completion is inferred from whichever
     * of three shapes it happens to use: earned reward entries, completed quests, or
     * completed challenge objectives. Anything with none of those has no completion state to
     * read at all, so it is listed separately rather than being claimed as outstanding.
     */
    MessageEmbed checklist(String discordId) throws IOException {
        Store.User user = store.requireLinked(discordId);
        JsonObject profile = client.profile(user.membershipType, user.membershipId, "200,202",
                ghost.accessTokenOrNull(user));
        String characterId = ghost.activeCharacter(user, child(profile, "characters", "data"));
        JsonObject progressions = child(profile, "characterProgressions", "data", characterId);
        if (progressions == null || !progressions.has("milestones")) {
            return Destiny.error("Couldn't read your milestones. Bungie hides these on profiles"
                    + " with privacy turned up.");
        }

        record Todo(String name, String detail, int order) {
        }
        List<Todo> todo = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        int done = 0;
        Instant soonest = null;

        for (Map.Entry<String, JsonElement> entry
                : progressions.getAsJsonObject("milestones").entrySet()) {
            JsonObject milestone = entry.getValue().getAsJsonObject();
            String name = manifest.milestoneName(Long.parseLong(entry.getKey()));
            if (name == null || name.isBlank()) {
                // The feed carries internal entries that are not meant to be shown.
                continue;
            }

            Instant ends = instant(milestone, "endDate");
            if (ends != null && (soonest == null || ends.isBefore(soonest))) {
                soonest = ends;
            }

            Boolean complete = complete(milestone);
            if (complete == null) {
                unknown.add(name);
                continue;
            }
            if (complete) {
                done++;
                continue;
            }
            todo.add(new Todo(name, progressOf(milestone), integer(milestone, "order")));
        }

        if (todo.isEmpty() && unknown.isEmpty()) {
            return new EmbedBuilder()
                    .setTitle("Nothing left")
                    .setColor(ACCENT)
                    .setDescription(done == 0
                            ? "No milestones are tracking against this character."
                            : "All **" + done + "** tracked milestones are done. Go outside.")
                    .build();
        }

        todo.sort(Comparator.comparingInt(Todo::order));
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Still to do")
                .setColor(ACCENT)
                .setDescription(soonest == null ? done + " already done"
                        : done + " already done · resets in " + until(soonest));

        if (!todo.isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (Todo item : todo) {
                lines.add("• **" + item.name() + "**"
                        + (item.detail() == null ? "" : " — " + item.detail()));
            }
            embed.addField("Outstanding (" + todo.size() + ")", join(lines), false);
        }
        if (!unknown.isEmpty()) {
            embed.addField("No progress to read (" + unknown.size() + ")",
                    join(unknown.stream().map(name -> "• " + name).toList()), false);
        }
        return embed.setFooter("For your " + className(user) + " · completed ones are hidden").build();
    }

    private String className(Store.User user) {
        try {
            for (Characters.Character character : ghost.characters(user)) {
                if (character.id().equals(user.characterId)) {
                    return character.className();
                }
            }
        } catch (IOException e) {
            // Falls through to the generic wording, which is still true.
        }
        return "active character";
    }

    /**
     * Whether a milestone is finished, or null when it carries no completion state.
     *
     * <p>Reward entries are checked first because they are the most reliable: a pinnacle
     * milestone is done when its reward is earned, regardless of how many of its activities
     * were played.
     */
    private static Boolean complete(JsonObject milestone) {
        JsonArray rewards = array(milestone, "rewards");
        if (rewards != null) {
            Boolean fromRewards = allTrue(rewards, reward -> {
                JsonArray entries = array(reward, "entries");
                return entries == null ? null : allTrue(entries, e -> bool(e, "earned"));
            });
            if (fromRewards != null) {
                return fromRewards;
            }
        }

        JsonArray quests = array(milestone, "availableQuests");
        if (quests != null) {
            Boolean fromQuests = allTrue(quests, quest -> {
                JsonObject status = quest.has("status") && quest.get("status").isJsonObject()
                        ? quest.getAsJsonObject("status") : null;
                return status == null ? null : bool(status, "completed");
            });
            if (fromQuests != null) {
                return fromQuests;
            }
        }

        JsonArray activities = array(milestone, "activities");
        if (activities != null) {
            return allTrue(activities, activity -> {
                JsonArray challenges = array(activity, "challenges");
                return challenges == null ? null : allTrue(challenges, challenge -> {
                    JsonObject objective = challenge.has("objective")
                            && challenge.get("objective").isJsonObject()
                            ? challenge.getAsJsonObject("objective") : null;
                    return objective == null ? null : bool(objective, "complete");
                });
            });
        }
        return null;
    }

    /** "2/5 challenges" for a milestone whose objectives report a count, else null. */
    private static String progressOf(JsonObject milestone) {
        JsonArray activities = array(milestone, "activities");
        if (activities == null) {
            return null;
        }
        int complete = 0;
        int total = 0;
        for (JsonElement element : activities) {
            JsonArray challenges = array(element.getAsJsonObject(), "challenges");
            if (challenges == null) {
                continue;
            }
            for (JsonElement challengeElement : challenges) {
                JsonObject challenge = challengeElement.getAsJsonObject();
                if (!challenge.has("objective") || !challenge.get("objective").isJsonObject()) {
                    continue;
                }
                total++;
                if (Boolean.TRUE.equals(bool(challenge.getAsJsonObject("objective"), "complete"))) {
                    complete++;
                }
            }
        }
        return total == 0 ? null
                : complete + "/" + total + (total == 1 ? " challenge" : " challenges");
    }

    /**
     * Whether a test holds for every element, propagating "cannot tell".
     *
     * <p>Null in, null out: an array that says nothing about completion must not be read as
     * "all of them passed", which is what a plain {@code allMatch} over an empty result
     * would give.
     */
    private static Boolean allTrue(JsonArray array, java.util.function.Function<JsonObject, Boolean> test) {
        boolean sawAnswer = false;
        boolean all = true;
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            Boolean result = test.apply(element.getAsJsonObject());
            if (result == null) {
                continue;
            }
            sawAnswer = true;
            all &= result;
        }
        return sawAnswer ? all : null;
    }

    // ---------------------------------------------------------------- helpers

    private static Boolean bool(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsBoolean() : null;
    }

    private static JsonArray array(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonArray()
                ? object.getAsJsonArray(key) : null;
    }

    private static int integer(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsInt() : 0;
    }

    private static Instant instant(JsonObject object, String key) {
        if (!object.has(key)) {
            return null;
        }
        try {
            return Instant.parse(object.get(key).getAsString());
        } catch (Exception e) {
            return null;
        }
    }

    private static String until(Instant when) {
        Duration left = Duration.between(Instant.now(), when);
        if (left.isNegative() || left.isZero()) {
            return "moments";
        }
        long days = left.toDays();
        long hours = left.toHoursPart();
        if (days > 0) {
            return days + (days == 1 ? " day" : " days")
                    + (hours > 0 ? ", " + hours + (hours == 1 ? " hour" : " hours") : "");
        }
        if (hours > 0) {
            return hours + (hours == 1 ? " hour" : " hours");
        }
        return Math.max(1, left.toMinutes()) + " minutes";
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
