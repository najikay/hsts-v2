package server.db.seed;

import org.hibernate.SessionFactory;
import server.db.DbBootstrap;
import server.db.HibernateUtil;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * The one command that loads the demo dataset (E2.15).
 *
 * <pre>
 *   java -cp hsts-server.jar server.db.seed.SeedMain
 *   java -cp hsts-server.jar server.db.seed.SeedMain --reseed
 *   java -cp hsts-server.jar server.db.seed.SeedMain --reseed --yes
 * </pre>
 *
 * <p>With no arguments it inserts only what is missing, which is safe to repeat and safe against
 * a database somebody is using. {@code --reseed} empties every table first and reloads, which is
 * the standard step before a demo because it re-resolves the relative execution windows: without
 * it, a machine seeded a fortnight ago presents a "live" exam whose window closed two weeks
 * earlier.
 *
 * <h2>The confirmation, and why {@code --yes} exists but is not the default</h2>
 *
 * <p>A reseed deletes everything, so it asks first. The prompt text comes from
 * {@link SeedLoader}, not from here, so this command and the E19.6 console button cannot
 * describe the same destructive action differently.
 *
 * <p>{@code --yes} skips the question, for a scripted rebuild where nobody is watching. It is a
 * flag rather than a default because the failure it guards against is not "the operator was
 * careless", it is "the operator was in the wrong terminal". A non-interactive run without
 * {@code --yes} <b>refuses</b> rather than assuming consent, which is the same reasoning
 * {@link Confirmation#preApproved()} carries.
 *
 * <h2>Migrations run first</h2>
 *
 * <p>{@link DbBootstrap#migrate()} is called before loading, so a fresh machine goes from no
 * database to a seeded one in a single command. The connection string carries
 * {@code createDatabaseIfNotExist=true}, so even the schema need not exist yet.
 */
public final class SeedMain {

    private static final String RESEED = "--reseed";
    private static final String ASSUME_YES = "--yes";

    private SeedMain() {
        // entry point only
    }

    /**
     * @param args {@code --reseed} to empty and reload, {@code --yes} to skip the confirmation
     */
    public static void main(String[] args) {
        SeedMode mode = modeFor(args);
        boolean assumeYes = assumesYes(args);

        DbBootstrap.migrate();

        SessionFactory factory = HibernateUtil.sessionFactory();
        SeedSummary summary = SeedLoader.standard(factory)
                .load(mode, assumeYes ? Confirmation.preApproved() : SeedMain::askOnConsole);

        System.out.println(summary.toText());
        if (summary.outcome() == SeedOutcome.CANCELLED) {
            System.exit(1);
        }
    }

    /**
     * Asks on the console, refusing rather than assuming when there is no console to ask.
     *
     * @param prompt what is about to happen, worded by the loader
     * @return whether the operator typed yes
     */
    private static boolean askOnConsole(String prompt) {
        System.out.println();
        System.out.println(prompt);
        System.out.print("Type 'yes' to continue: ");

        return readsYes(new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8)));
    }

    /**
     * Whether the operator answered yes.
     *
     * <p>Separated from {@link #askOnConsole} so the answers can be tested without a console.
     * That is a testability seam and is named as one, but it earns its place independently:
     * this is the only code between a stray {@code --reseed} and an empty database, and the
     * three ways it can be told "no" are worth pinning individually.
     *
     * <p><b>End of stream is a refusal, not a yes.</b> A null line means nothing is attached to
     * stdin, so nobody is present to consent. Reading that as agreement is how a scripted run
     * in the wrong terminal wipes a database; a caller that genuinely means it passes
     * {@code --yes} and never reaches here.
     *
     * @param console where the answer comes from
     * @return whether it was yes
     */
    static boolean readsYes(BufferedReader console) {
        try {
            String answer = console.readLine();
            if (answer == null) {
                System.out.println();
                System.out.println("No console to confirm on. Re-run with " + ASSUME_YES
                        + " if this is a scripted rebuild.");
                return false;
            }
            return answer.trim().equalsIgnoreCase("yes");
        } catch (java.io.IOException e) {
            System.out.println("Could not read the console, so nothing was changed.");
            return false;
        }
    }

    /**
     * The mode a command line asks for.
     *
     * @param args the raw arguments
     * @return RESEED only when {@code --reseed} is present
     */
    static SeedMode modeFor(String... args) {
        return flags(args).contains(RESEED) ? SeedMode.RESEED : SeedMode.LOAD_IF_MISSING;
    }

    /**
     * Whether the command line waives the confirmation.
     *
     * @param args the raw arguments
     * @return whether {@code --yes} is present
     */
    static boolean assumesYes(String... args) {
        return flags(args).contains(ASSUME_YES);
    }

    private static List<String> flags(String... args) {
        return List.of(args).stream()
                .map(flag -> flag.toLowerCase(Locale.ROOT))
                .toList();
    }
}
