package org.example;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the bot posts things nobody asked for: the weekly reset, and Xûr turning up.
 *
 * <p>One channel per guild. The watchers used to hold a single {@code TextChannel} between
 * them, which meant a second server running {@code /watch} silently took the announcements
 * away from the first — a bug that only shows up once the bot is somewhere other than the
 * server it was built in.
 *
 * <p>Registrations are in memory, so a restart stops the announcements until someone runs
 * {@code /watch} again. That matches how this behaved before and keeps guild state out of a
 * file that otherwise holds only per-user credentials.
 */
final class Announcer {

    /** Guild id to the channel announcements go to. */
    private final Map<String, String> channels = new ConcurrentHashMap<>();

    private final JDA jda;

    Announcer(JDA jda) {
        this.jda = jda;
    }

    /** Points a guild's announcements at a channel, replacing any previous choice. */
    void watch(String guildId, String channelId) {
        channels.put(guildId, channelId);
    }

    /** @return true if the guild was being announced to */
    boolean unwatch(String guildId) {
        return channels.remove(guildId) != null;
    }

    boolean isWatching() {
        return !channels.isEmpty();
    }

    /**
     * Posts to every registered channel.
     *
     * <p>A channel that has been deleted, or that the bot has lost access to, is dropped
     * rather than retried forever.
     */
    void send(MessageEmbed embed) {
        for (Map.Entry<String, String> entry : channels.entrySet()) {
            TextChannel channel = jda.getTextChannelById(entry.getValue());
            if (channel == null) {
                channels.remove(entry.getKey());
                continue;
            }
            channel.sendMessageEmbeds(embed).queue(null, error -> {
                System.err.println("Dropping announcements for guild " + entry.getKey()
                        + ": " + error.getMessage());
                channels.remove(entry.getKey());
            });
        }
    }
}
