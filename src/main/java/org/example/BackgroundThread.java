package org.example;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.awt.Color;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Watches the public milestones and announces the weekly rotation when it changes.
 *
 * <p>Milestones turn over at the weekly reset, so this polls on a long interval and only
 * posts when the set of active milestones actually differs from the last one it saw. A
 * restart mid-week therefore does not re-announce, and a reset produces one message.
 */
public class BackgroundThread extends Thread {

    private static final Duration POLL_INTERVAL = Duration.ofMinutes(30);

    private final TextChannel channel;
    private final BungieClient client;
    private final ManifestCache manifest;

    /** Null until the first successful poll, so startup does not look like a change. */
    private Set<String> lastSeen = null;

    BackgroundThread(TextChannel channel, BungieClient client, ManifestCache manifest) {
        super("milestone-watcher");
        setDaemon(true);
        this.channel = channel;
        this.client = client;
        this.manifest = manifest;
    }

    @Override
    public void run() {
        while (!isInterrupted()) {
            try {
                poll();
            } catch (Exception e) {
                // A failed poll is not worth stopping for — Bungie has weekly maintenance
                // windows, and the next attempt will pick any change up.
                System.err.println("Milestone poll failed: " + e.getMessage());
            }

            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void poll() throws Exception {
        List<String> names = Milestones.activeMilestoneNames(client, manifest);
        if (names.isEmpty()) {
            return;
        }

        Set<String> current = new TreeSet<>(names);
        if (current.equals(lastSeen)) {
            return;
        }

        boolean firstRun = lastSeen == null;
        lastSeen = current;
        if (firstRun) {
            // Record the starting state without announcing it.
            return;
        }

        String body = names.stream().map(name -> "• " + name).reduce((a, b) -> a + "\n" + b).orElse("");

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Weekly reset")
                .setColor(new Color(0x00A8E1))
                .setDescription(body)
                .setFooter("Milestones now active");

        channel.sendMessageEmbeds(embed.build()).queue();
    }
}
