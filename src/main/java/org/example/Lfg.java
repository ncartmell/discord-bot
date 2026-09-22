package org.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A fireteam board that lives in Discord.
 *
 * <p>Bungie publishes no way in. The in-game Fireteam Finder has manifest definitions but no
 * endpoints, and the old bungie.net LFG is retired — its settings flags read
 * {@code ClanFireteams=False}, and its endpoints answer {@code SystemDisabled}. So the
 * listing itself is Discord's job.
 *
 * <p>What the bot adds is the part a general-purpose LFG bot cannot: because members have
 * linked their accounts, each name carries their actual power and how many times they have
 * cleared the thing being run. Those are read once when someone joins and then kept on the
 * listing, so redrawing the post costs nothing.
 */
final class Lfg {

    private static final Color ACCENT = new Color(0x00A8E1);
    private static final Path FILE = Paths.get(
            System.getenv().getOrDefault("BOT_LFG_FILE", "lfg.json"));
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Listings older than this are treated as dead when someone interacts with them. */
    private static final Duration LIFETIME = Duration.ofHours(12);

    /** One person on a listing, with whatever the bot could learn about them. */
    static final class Member {
        String discordId;
        String name;
        /** Highest power across their characters, or 0 when unknown. */
        int power;
        /** Completions of this activity, or -1 when unknown. */
        int clears = -1;
    }

    /** One fireteam post. */
    static final class Listing {
        String id;
        String channelId;
        String messageId;
        String guildId;
        /** The private voice channel opened for this run, if one could be made. */
        String voiceChannelId;
        String ownerId;
        String activity;
        /** The activity hashes the clear counts were measured against. */
        List<Long> activityHashes = new ArrayList<>();
        int size;
        String note;
        long createdAt;
        boolean closed;
        List<Member> members = new ArrayList<>();

        boolean isFull() {
            return members.size() >= size;
        }

        boolean has(String discordId) {
            return members.stream().anyMatch(m -> m.discordId.equals(discordId));
        }

        boolean isStale() {
            return Instant.ofEpochMilli(createdAt).plus(LIFETIME).isBefore(Instant.now());
        }
    }

    private final Manifest manifest;
    private final Store store;
    private final Stats stats;
    private final Map<String, Listing> listings = new ConcurrentHashMap<>();

    Lfg(Manifest manifest, Store store, Stats stats) {
        this.manifest = manifest;
        this.store = store;
        this.stats = stats;
        load();
    }

    // ---------------------------------------------------------------- actions

    /**
     * Creates a listing with its owner already on it.
     *
     * <p>The activity is matched against the manifest so clear counts mean something, but a
     * name that matches nothing is still allowed — "sherpa run" is a legitimate thing to post
     * and refusing it would be worse than losing the enrichment.
     */
    Listing create(String discordId, String memberName, String activityQuery, int size, String note) {
        Listing listing = new Listing();
        byte[] id = new byte[9];
        RANDOM.nextBytes(id);
        listing.id = Base64.getUrlEncoder().withoutPadding().encodeToString(id);
        listing.ownerId = discordId;
        listing.size = Math.max(2, Math.min(12, size));
        listing.note = note == null || note.isBlank() ? null : note.trim();
        listing.createdAt = System.currentTimeMillis();

        List<Long> hashes = manifest.findActivities(activityQuery);
        List<String> names = manifest.activityNamesMatching(activityQuery);
        listing.activityHashes = hashes;
        listing.activity = names.isEmpty() ? activityQuery.trim() : shortest(names);

        listing.members.add(describe(discordId, memberName, listing));
        listings.put(listing.id, listing);
        save();
        return listing;
    }

    /** Adds someone, or reports why not. */
    String join(String id, String discordId, String memberName) {
        Listing listing = listings.get(id);
        if (listing == null) {
            return "That listing is gone.";
        }
        if (listing.closed) {
            return "That one's closed.";
        }
        if (listing.isStale()) {
            listing.closed = true;
            save();
            return "That listing expired.";
        }
        if (listing.has(discordId)) {
            return "You're already on it.";
        }
        if (listing.isFull()) {
            return "It's full.";
        }
        listing.members.add(describe(discordId, memberName, listing));
        save();
        return null;
    }

    /** Removes someone. The owner leaving closes the listing, since it was their run. */
    String leave(String id, String discordId) {
        Listing listing = listings.get(id);
        if (listing == null) {
            return "That listing is gone.";
        }
        if (!listing.has(discordId)) {
            return "You're not on it.";
        }
        listing.members.removeIf(m -> m.discordId.equals(discordId));
        if (discordId.equals(listing.ownerId)) {
            listing.closed = true;
        }
        save();
        return null;
    }

    String close(String id, String discordId) {
        Listing listing = listings.get(id);
        if (listing == null) {
            return "That listing is gone.";
        }
        if (!discordId.equals(listing.ownerId)) {
            return "Only whoever posted it can close it.";
        }
        listing.closed = true;
        save();
        return null;
    }

    Listing get(String id) {
        return listings.get(id);
    }

    /** Records the message a listing was posted as, so its buttons survive a restart. */
    void attach(String id, String guildId, String channelId, String messageId) {
        Listing listing = listings.get(id);
        if (listing != null) {
            listing.guildId = guildId;
            listing.channelId = channelId;
            listing.messageId = messageId;
            save();
        }
    }

    /** Records the voice channel opened for a listing, so it can be torn down later. */
    void attachVoice(String id, String voiceChannelId) {
        Listing listing = listings.get(id);
        if (listing != null) {
            listing.voiceChannelId = voiceChannelId;
            save();
        }
    }

    // ---------------------------------------------------------------- rendering

    MessageEmbed render(Listing listing) {
        StringBuilder roster = new StringBuilder();
        for (Member member : listing.members) {
            roster.append(member.discordId.equals(listing.ownerId) ? "👑 " : "• ")
                    .append("<@").append(member.discordId).append(">");

            List<String> detail = new ArrayList<>();
            if (member.power > 0) {
                detail.add(String.valueOf(member.power));
            }
            if (member.clears >= 0) {
                detail.add(member.clears + (member.clears == 1 ? " clear" : " clears"));
            }
            if (!detail.isEmpty()) {
                roster.append("  —  ").append(String.join(" · ", detail));
            }
            roster.append('\n');
        }
        for (int i = listing.members.size(); i < listing.size; i++) {
            roster.append("• _open_\n");
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(listing.activity)
                .setColor(listing.closed ? Color.GRAY : ACCENT)
                .setDescription(listing.closed ? "**Closed**"
                        : listing.members.size() + " of " + listing.size
                          + (listing.isFull() ? " — full" : " — " + slots(listing) + " open"))
                .addField("Fireteam", roster.toString().trim(), false);

        if (listing.note != null) {
            embed.addField("Note", listing.note, false);
        }
        if (listing.voiceChannelId != null && !listing.closed) {
            embed.addField("Voice", "<#" + listing.voiceChannelId + "> — joining the listing"
                    + " lets you in, leaving locks you out.", false);
        }
        embed.setFooter(listing.activityHashes.isEmpty()
                ? "Clear counts unavailable — activity not recognised"
                : "Power and clears are from linked accounts");
        return embed.build();
    }

    /** The buttons for a listing, or none once it is closed. */
    List<ActionRow> controls(Listing listing) {
        if (listing.closed) {
            return List.of();
        }
        return List.of(ActionRow.of(
                Button.success("lfg:" + listing.id + ":join", "Join")
                        .withDisabled(listing.isFull()),
                Button.secondary("lfg:" + listing.id + ":leave", "Leave"),
                Button.danger("lfg:" + listing.id + ":close", "Close")));
    }

    private int slots(Listing listing) {
        return Math.max(0, listing.size - listing.members.size());
    }

    /**
     * Looks up what the bot knows about someone, once.
     *
     * <p>Two Bungie calls per person, done on join rather than on every redraw — a six-person
     * raid post would otherwise re-read four profiles every time somebody clicked anything.
     * An unlinked member just appears without the extra detail.
     */
    private Member describe(String discordId, String memberName, Listing listing) {
        Member member = new Member();
        member.discordId = discordId;
        member.name = memberName;

        Store.User user = store.peek(discordId);
        if (user == null || !user.isLinked()) {
            return member;
        }
        member.power = stats.highestPower(user);
        member.clears = stats.clearCount(user, new HashSet<>(listing.activityHashes));
        return member;
    }

    private static String shortest(List<String> names) {
        String best = names.get(0);
        for (String name : names) {
            int colon = name.indexOf(':');
            String base = colon > 0 ? name.substring(0, colon).trim() : name;
            if (base.length() < best.length()) {
                best = base;
            }
        }
        int colon = best.indexOf(':');
        return colon > 0 ? best.substring(0, colon).trim() : best;
    }

    // ---------------------------------------------------------------- persistence

    private synchronized void load() {
        if (!Files.exists(FILE)) {
            return;
        }
        try {
            Map<String, Listing> loaded = GSON.fromJson(
                    Files.readString(FILE, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, Listing>>() {}.getType());
            if (loaded != null) {
                listings.putAll(loaded);
            }
        } catch (Exception e) {
            // A corrupt board is not worth refusing to start over, unlike saved loadouts.
            System.err.println("Could not read " + FILE.toAbsolutePath() + ": " + e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            // Drop anything long dead so the file does not grow without bound.
            listings.values().removeIf(l -> l.isStale() && l.closed);
            Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(listings), StandardCharsets.UTF_8);
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Could not save the LFG board: " + e.getMessage());
        }
    }

    /** Listings that are still open, for a board summary. */
    List<Listing> open() {
        List<Listing> out = new ArrayList<>();
        for (Listing listing : listings.values()) {
            if (!listing.closed && !listing.isStale()) {
                out.add(listing);
            }
        }
        out.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return out;
    }
}
