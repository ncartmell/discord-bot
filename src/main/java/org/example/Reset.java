package org.example;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/**
 * Destiny's reset schedule.
 *
 * <p>17:00 UTC every day for vendor bounties and the lost sector rotation, and additionally
 * on Tuesday for the whole week's content. That schedule is fixed and has been for years,
 * which is why it is encoded here rather than inferred: the previous approach watched the
 * set of active milestones for changes, and that cannot tell a reset apart from Bungie
 * editing the feed mid-week.
 *
 * <p>Shared by the watcher, which announces on the boundary, and by the daily digest, which
 * needs to know where the week ends so it does not claim the week's content as today's.
 */
final class Reset {

    private static final int HOUR_UTC = 17;

    private Reset() {
    }

    /** The most recent 17:00 UTC boundary at or before {@code now}. */
    static Instant last(Instant now) {
        ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
        ZonedDateTime today = utc.truncatedTo(ChronoUnit.DAYS).withHour(HOUR_UTC);
        return (utc.isBefore(today) ? today.minusDays(1) : today).toInstant();
    }

    /** Tuesday's reset is the one that turns the week over. */
    static boolean isWeekly(Instant reset) {
        return reset.atZone(ZoneOffset.UTC).getDayOfWeek() == DayOfWeek.TUESDAY;
    }

    /**
     * The next Tuesday 17:00 UTC strictly after {@code now}.
     *
     * <p>Strictly after, so that asking on Tuesday morning gives today's reset and asking
     * on Tuesday evening gives next week's — which is what "when does this week end" means
     * in both cases.
     */
    static Instant nextWeekly(Instant now) {
        ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
        ZonedDateTime candidate = utc.truncatedTo(ChronoUnit.DAYS)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.TUESDAY))
                .withHour(HOUR_UTC);
        return (candidate.isAfter(utc) ? candidate : candidate.plusWeeks(1)).toInstant();
    }
}
