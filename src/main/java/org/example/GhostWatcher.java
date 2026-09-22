package org.example;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Watches what linked players are doing and acts at the only moment the game allows it.
 *
 * <p>Destiny refuses gear changes outside orbit, a social space or being offline, so the
 * useful trick is not to react faster — it is to wait for the transition. This polls each
 * linked account and does two things when the activity changes:
 *
 * <ul>
 *   <li><strong>Back in orbit with something queued</strong> — equips it, which is the
 *       payoff for a {@code /equip} issued mid-activity.</li>
 *   <li><strong>Entering an activity with a loadout mapped to it</strong> — says so, if the
 *       set is not already on. It cannot equip here, and queueing would put the gear on
 *       after the activity rather than before, so the honest action is to tell you.</li>
 * </ul>
 *
 * <p>Both are gated on the per-user auto-equip toggle.
 */
final class GhostWatcher extends Thread {

    /** Short enough to catch the orbit transition, long enough to be polite to the API. */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(45);

    private final JDA jda;
    private final Ghost ghost;
    private final Store store;

    /** Discord id to the last activity hash seen, so only transitions are acted on. */
    private final Map<String, Long> lastActivity = new HashMap<>();

    GhostWatcher(JDA jda, Ghost ghost, Store store) {
        super("ghost-watcher");
        setDaemon(true);
        this.jda = jda;
        this.ghost = ghost;
        this.store = store;
    }

    @Override
    public void run() {
        while (!isInterrupted()) {
            for (Map.Entry<String, Store.User> entry : store.linkedUsers()) {
                if (!entry.getValue().autoEquip) {
                    continue;
                }
                try {
                    check(entry.getKey(), entry.getValue());
                } catch (Exception e) {
                    // One account failing should not take the loop down for the others.
                    System.err.println("Ghost check failed for " + entry.getKey() + ": " + e.getMessage());
                }
            }

            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void check(String discordId, Store.User user) throws Exception {
        long current = ghost.currentActivityHash(user);
        Long previous = lastActivity.put(discordId, current);

        // The first sighting establishes a baseline rather than announcing the status quo.
        if (previous == null || previous == current) {
            return;
        }

        if (current == 0) {
            MessageEmbed applied = ghost.applyPending(discordId);
            if (applied != null) {
                send(discordId, applied);
            }
            return;
        }

        String mapped = user.activityMap.get(String.valueOf(current));
        if (mapped != null) {
            MessageEmbed notice = ghost.readyNotice(user, current, mapped);
            if (notice != null) {
                send(discordId, notice);
            }
        }
    }

    private void send(String discordId, MessageEmbed embed) {
        jda.retrieveUserById(discordId).queue(
                user -> user.openPrivateChannel().queue(
                        channel -> channel.sendMessageEmbeds(embed).queue(
                                null,
                                error -> System.err.println("Could not DM " + discordId
                                        + ": " + error.getMessage())),
                        error -> System.err.println("Could not open a DM with " + discordId
                                + ": " + error.getMessage())),
                error -> System.err.println("Unknown Discord user " + discordId));
    }
}
