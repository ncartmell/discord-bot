package org.example;

import java.time.Duration;
import java.time.Instant;

/**
 * Announces the daily and weekly resets.
 *
 * <p>Destiny resets at 17:00 UTC: every day for vendor bounties and the lost sector
 * rotation, and additionally on Tuesday for the whole week's content. That schedule is
 * fixed and has been for years, so this watches the clock rather than trying to infer a
 * reset from the data — which was the previous approach, and which could not tell a reset
 * apart from Bungie editing the milestone feed mid-week.
 *
 * <p>Announcements wait a few minutes past the boundary. The public milestone feed does not
 * update the instant the reset lands, so posting at 17:00:00 exactly would sometimes
 * describe the week that just ended.
 *
 * <p>The first pass records where in the schedule the bot started without posting, so a
 * restart on Wednesday afternoon does not announce Tuesday's reset.
 */
public class BackgroundThread extends Thread {

    private static final Duration POLL_INTERVAL = Duration.ofMinutes(5);

    /** How long after a reset to wait before believing what Bungie reports. */
    private static final Duration SETTLE = Duration.ofMinutes(10);

    private final Announcer announcer;
    private final Weekly weekly;

    /** The reset most recently accounted for. Null until the first pass sets a baseline. */
    private Instant lastSeen = null;

    BackgroundThread(Announcer announcer, Weekly weekly) {
        super("reset-watcher");
        setDaemon(true);
        this.announcer = announcer;
        this.weekly = weekly;
    }

    @Override
    public void run() {
        while (!isInterrupted()) {
            try {
                poll();
            } catch (Exception e) {
                // Bungie takes the API down for maintenance around the weekly reset, which
                // is exactly when this runs. The next pass picks it up.
                System.err.println("Reset announcement failed: " + e.getMessage());
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
        Instant now = Instant.now();
        Instant reset = Reset.last(now);

        // Inside the settling window the feed may still describe the previous period, so
        // treat the reset as not having happened yet rather than announcing stale content.
        if (now.isBefore(reset.plus(SETTLE))) {
            return;
        }

        boolean firstRun = lastSeen == null;
        if (firstRun || !reset.isAfter(lastSeen)) {
            lastSeen = reset;
            return;
        }
        lastSeen = reset;

        if (!announcer.isWatching()) {
            return;
        }
        announcer.send(Reset.isWeekly(reset) ? weekly.rotators() : weekly.daily());
    }
}
