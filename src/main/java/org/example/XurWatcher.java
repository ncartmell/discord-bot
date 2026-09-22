package org.example;

import com.google.gson.JsonObject;

import java.time.Duration;

/**
 * Announces Xûr when he shows up.
 *
 * <p>He arrives on Friday and leaves at Tuesday's reset, and the difference between
 * remembering that and not is most of the value of the {@code /xur} command. Rather than
 * encode the schedule — which Bungie has moved before, and which would be wrong during an
 * outage anyway — this watches for the vendor appearing in the public feed and treats the
 * transition itself as the event.
 *
 * <p>The first poll after a restart only records whether he is currently around, so coming
 * back up on a Saturday does not announce an arrival that happened yesterday.
 */
final class XurWatcher extends Thread {

    /** He arrives on a schedule measured in days; ten minutes is plenty of resolution. */
    private static final Duration POLL_INTERVAL = Duration.ofMinutes(10);

    private static final long XUR = 2190858386L;

    private final BungieClient client;
    private final Vendors vendors;
    private final Announcer announcer;

    /** Null until the first successful poll, so startup is a baseline rather than an event. */
    private Boolean wasHere = null;

    XurWatcher(BungieClient client, Vendors vendors, Announcer announcer) {
        super("xur-watcher");
        setDaemon(true);
        this.client = client;
        this.vendors = vendors;
        this.announcer = announcer;
    }

    @Override
    public void run() {
        while (!isInterrupted()) {
            try {
                poll();
            } catch (Exception e) {
                // Bungie's weekly maintenance makes this fail on a schedule of its own.
                System.err.println("Xûr poll failed: " + e.getMessage());
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
        boolean here = here();
        boolean arrived = Boolean.FALSE.equals(wasHere) && here;
        wasHere = here;

        if (!arrived || !announcer.isWatching()) {
            return;
        }
        // Built without a caller, so the listing carries no ownership marks — an
        // announcement goes to a channel, and there is nobody in particular to compare
        // Collections against.
        announcer.send(vendors.xur(null));
    }

    private boolean here() throws Exception {
        JsonObject sales = child(client.publicVendors("400,402"), "sales", "data");
        return sales != null && sales.has(String.valueOf(XUR));
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
