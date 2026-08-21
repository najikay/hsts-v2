package server.core;

import java.util.Locale;
import java.util.Optional;

/**
 * What the server says when it cannot start (Logic tier, E19.2).
 *
 * <p>Separate from {@link ServerMain} because these are decisions, and
 * {@code ServerMain} is glue excluded from the coverage gate. Which sentence a
 * given failure produces is a rule with branches, and the whole value of the rule
 * is that the sentence is right, so it is asserted here rather than discovered on
 * a demo machine.
 *
 * <h2>Why a sentence at all</h2>
 *
 * <p>The failure this exists for happens at one predictable moment: the first boot
 * on a machine nobody has run MySQL on, minutes before somebody needs it working.
 * The default behaviour, forty lines of Flyway and driver stack frames, contains
 * the answer and buries it. Every sentence below names the likely cause and the
 * next action, and the stack trace still follows for whoever wants it.
 *
 * <p>The classification is on message text, which is imprecise by nature: a driver
 * is free to reword its errors. That is why the fallback is a complete sentence
 * rather than a shrug, and why the original detail is appended to every branch. A
 * misclassified failure still tells the operator what happened.
 */
public final class StartupMessages {

    /** What is said when nothing more specific can be recognised. */
    public static final String GENERIC_DATABASE_PROBLEM =
            "The database could not be prepared, so the server did not start. "
                    + "Check MySQL is running and server.properties is right.";

    /** Wrong user or password in {@code server.properties}. */
    public static final String BAD_CREDENTIALS =
            "The database refused the login. Check db.user and db.password in "
                    + "server.properties match this machine's MySQL.";

    /** Nothing listening on 3306. */
    public static final String DATABASE_UNREACHABLE =
            "MySQL is not answering on this machine. Start the MySQL service, "
                    + "then start the server again.";

    /** Flyway found a migration that changed after it was applied. */
    public static final String SCHEMA_MISMATCH =
            "The database was migrated by a different build of this server. "
                    + "Drop the hsts_db database and start again to rebuild it.";

    private StartupMessages() {
    }

    /**
     * Turns a migration failure into the line an operator can act on.
     *
     * @param failure whatever Flyway, HikariCP or the driver threw
     * @return one sentence naming the likely cause and the next step, with the
     *         original detail appended
     */
    public static String migrationFailed(Throwable failure) {
        String detail = rootMessage(failure);
        String lower = detail.toLowerCase(Locale.ROOT);
        String headline;
        if (lower.contains("access denied")) {
            headline = BAD_CREDENTIALS;
        } else if (lower.contains("communications link")
                || lower.contains("connection refused")
                || lower.contains("could not create connection")) {
            headline = DATABASE_UNREACHABLE;
        } else if (lower.contains("checksum") || lower.contains("validate failed")) {
            headline = SCHEMA_MISMATCH;
        } else {
            headline = GENERIC_DATABASE_PROBLEM;
        }
        return headline + " Details: " + detail;
    }

    /**
     * @param failure any throwable, possibly wrapped several times
     * @return the innermost message, which is the one that says what actually went
     *         wrong; the outer layers say only which layer noticed
     */
    static String rootMessage(Throwable failure) {
        if (failure == null) {
            return "unknown failure";
        }
        Throwable current = failure;
        int guard = 0;
        // Bounded: a cause cycle is rare and a hang while reporting a startup
        // failure would be a worse bug than the one being reported.
        while (current.getCause() != null && current.getCause() != current && guard++ < 32) {
            current = current.getCause();
        }
        return Optional.ofNullable(current.getMessage())
                .filter(message -> !message.isBlank())
                .orElse(current.getClass().getSimpleName());
    }
}
