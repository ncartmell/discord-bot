package org.example;

import io.github.cdimascio.dotenv.Dotenv;

/**
 * Configuration loaded from the environment, falling back to a local .env file.
 *
 * <p>No credentials are committed to this repository. Copy {@code .env.example} to
 * {@code .env} and fill in your own values, or set the variables in your environment.
 */
final class Config {

    private static final Dotenv DOTENV = Dotenv.configure().ignoreIfMissing().load();

    private Config() {
    }

    /**
     * Returns the value for {@code key}, preferring a real environment variable and
     * falling back to {@code .env}.
     *
     * @throws IllegalStateException if the value is missing, so the bot fails at startup
     *                               with a clear message rather than on first use.
     */
    static String require(String key) {
        String value = DOTENV.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing required configuration: " + key
                            + ". Copy .env.example to .env and set it, or export it in your environment.");
        }
        return value;
    }
}
