package server.console;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.db.seed.Confirmation;
import server.db.seed.SeedMode;
import server.db.seed.SeedOutcome;
import server.db.seed.SeedSummary;

import java.io.IOException;
import java.util.Objects;

/**
 * Every button on the server console, as methods that return sentences (Logic
 * tier, E19.2 / E19.6 / E19.8).
 *
 * <p>The view builds nodes and calls one of these; nothing here knows that JavaFX
 * exists. Each action answers an {@link Outcome}, which is a success flag and the
 * text to show, so what the console says after a click is asserted in a unit test
 * rather than read off a screenshot. Every failure sentence names the next step,
 * per the house copy rule.
 *
 * <h2>Start and stop mean listener, not process</h2>
 *
 * <p>Stopping closes the OCSF listening socket and leaves everything else running:
 * the process, the database pool, the timers, and every exam already in progress.
 * A student mid-attempt keeps their attempt and their deadline, because the server
 * owns both and neither is in the socket. What stops is <em>new</em> clients being
 * able to connect.
 *
 * <p><b>Closes</b> is load-bearing, and until B-49 it was not true. OCSF's
 * {@code stopListening()} raised a flag and left the socket bound, so the
 * operating system kept completing handshakes into the accept backlog and a
 * client dialling a stopped server sat on {@code Connecting...} rather than
 * being refused. The sentence this button returns has always claimed the
 * refusal; {@code AbstractServer.stopListening} now performs it.
 *
 * <p>That is the useful meaning during a demo ("watch what happens when the server
 * goes away") and it is also the only safe one: a button on a window that could
 * discard live attempts is a button somebody will eventually press by accident.
 * {@link ConsoleModel#listenStatusText()} says all of this on screen, because an
 * operator should not have to have read this javadoc.
 *
 * <h2>The seed button</h2>
 *
 * <p>{@link #reseed} does not implement reseeding. It hands
 * {@code SeedLoader.load} a {@link Confirmation} and renders the
 * {@link SeedSummary} that comes back. The prompt the operator reads comes from
 * the loader, deliberately, so the console button and the command line cannot
 * describe the same destructive action differently.
 */
public final class ConsoleSession {

    private static final Logger log = LoggerFactory.getLogger(ConsoleSession.class);

    /**
     * What an action did, in a form the console can show.
     *
     * @param ok      whether it worked
     * @param message what to tell the operator, never blank
     */
    public record Outcome(boolean ok, String message) {

        public Outcome {
            Objects.requireNonNull(message, "message");
        }

        static Outcome ok(String message) {
            return new Outcome(true, message);
        }

        static Outcome failed(String message) {
            return new Outcome(false, message);
        }
    }

    /** The OCSF listener, as the two verbs the console needs (E19.2). */
    public interface ServerControl {

        /** Opens the listening socket. */
        void startListening() throws IOException;

        /** Closes the listening socket, keeping the process and its state. */
        void stopListening() throws IOException;

        boolean isListening();
    }

    /** The seed loader's entry point (E19.6). */
    @FunctionalInterface
    public interface SeedRunner {

        /** @see server.db.seed.SeedLoader#load(SeedMode, Confirmation) */
        SeedSummary load(SeedMode mode, Confirmation confirmation);
    }

    /** The discovery responder's on/off switch (E19.8). */
    public interface DiscoveryControl {

        /** A control for a server started with {@code --no-discovery}. */
        DiscoveryControl DISABLED = new DiscoveryControl() {
            @Override
            public boolean enable() {
                return false;
            }

            @Override
            public boolean disable() {
                return false;
            }

            @Override
            public boolean isRunning() {
                return false;
            }
        };

        /** @return {@code true} when this call started it */
        boolean enable();

        /** @return {@code true} when this call stopped it */
        boolean disable();

        boolean isRunning();
    }

    private final ConsoleModel model;
    private final ServerControl server;
    private final SeedRunner seed;
    private final ConsoleHealth health;
    private final DiscoveryControl discovery;

    public ConsoleSession(ConsoleModel model, ServerControl server, SeedRunner seed,
                          ConsoleHealth health, DiscoveryControl discovery) {
        this.model = Objects.requireNonNull(model, "model");
        this.server = Objects.requireNonNull(server, "server");
        this.seed = Objects.requireNonNull(seed, "seed");
        this.health = Objects.requireNonNull(health, "health");
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.model.setListening(server.isListening());
        this.model.setDiscoveryEnabled(discovery.isRunning());
    }

    public ConsoleModel model() {
        return model;
    }

    // ===================== Listener ======================================

    /** Starts or stops listening, whichever the current state is not. */
    public Outcome toggleListening() {
        return model.isListening() ? stopListening() : startListening();
    }

    /** @return what to show after pressing Start listening. */
    public Outcome startListening() {
        if (server.isListening()) {
            model.setListening(true);
            return Outcome.ok("Already listening on port " + model.port() + ".");
        }
        try {
            server.startListening();
            model.setListening(true);
            log.info("Listener started from the console on port {}", model.port());
            return Outcome.ok("Listening on port " + model.port() + ". Clients can connect now.");
        } catch (IOException | RuntimeException e) {
            model.setListening(server.isListening());
            return Outcome.failed("Could not listen on port " + model.port() + ": "
                    + describe(e) + ". Another program may already be using that port. "
                    + "Close it, or restart the server with a different --port.");
        }
    }

    /** @return what to show after pressing Stop listening. */
    public Outcome stopListening() {
        if (!server.isListening()) {
            model.setListening(false);
            return Outcome.ok("The listener is already stopped.");
        }
        try {
            server.stopListening();
            model.setListening(false);
            log.info("Listener stopped from the console");
            return Outcome.ok("Stopped listening. New connections are refused right away. "
                    + "Exams already in progress keep running. "
                    + "Press Start listening to let clients connect again.");
        } catch (IOException | RuntimeException e) {
            model.setListening(server.isListening());
            return Outcome.failed("Could not stop the listener: " + describe(e)
                    + ". The server is still accepting clients. Close the window to stop it "
                    + "completely.");
        }
    }

    // ===================== Seed ==========================================

    /**
     * Loads any seed rows that are missing (E19.6, first-run button).
     *
     * <p>Destroys nothing, so it needs no confirmation and is safe on a database
     * somebody is using.
     */
    public Outcome loadSeedIfMissing() {
        // preApproved is sanctioned here and only here: the loader ignores the
        // confirmation entirely for LOAD_IF_MISSING, because that mode deletes
        // nothing. The destructive path below asks a human, every time.
        return runSeed(SeedMode.LOAD_IF_MISSING, Confirmation.preApproved());
    }

    /**
     * Empties the database and loads the demo dataset fresh (E19.6).
     *
     * @param confirmation the console's dialog. The prompt shown is the loader's
     *                     own text, handed to this confirmation, so the button and
     *                     the command line ask the same question
     */
    public Outcome reseed(Confirmation confirmation) {
        Objects.requireNonNull(confirmation, "confirmation");
        return runSeed(SeedMode.RESEED, confirmation);
    }

    private Outcome runSeed(SeedMode mode, Confirmation confirmation) {
        try {
            SeedSummary summary = seed.load(mode, confirmation);
            // The loader's own text, per table. The console adds nothing to it: two
            // places that both describe a seed run is how they come to disagree.
            return new Outcome(summary.outcome() != SeedOutcome.CANCELLED, summary.toText());
        } catch (RuntimeException e) {
            log.error("Seed run failed from the console", e);
            return Outcome.failed("The seed did not run: " + describe(e)
                    + ". Nothing was changed. Check the database is up on the card above, "
                    + "then try again.");
        }
    }

    // ===================== Discovery =====================================

    /** Turns the discovery responder on or off (E19.8). */
    public Outcome toggleDiscovery() {
        return model.isDiscoveryEnabled() ? disableDiscovery() : enableDiscovery();
    }

    public Outcome enableDiscovery() {
        boolean started = discovery.enable();
        model.setDiscoveryEnabled(discovery.isRunning());
        if (!model.isDiscoveryEnabled()) {
            return Outcome.failed("Discovery could not start. Clients need the address above "
                    + "typed in by hand. Check nothing else is using the discovery port.");
        }
        return Outcome.ok(started
                ? "Discovery is on. Clients on this network can find this server by themselves."
                : "Discovery was already on.");
    }

    public Outcome disableDiscovery() {
        discovery.disable();
        model.setDiscoveryEnabled(discovery.isRunning());
        return Outcome.ok("Discovery is off. Give students the address above to type in.");
    }

    // ===================== Cards =========================================

    /** @return a fresh reading of the four status cards. */
    public HealthSnapshot refreshHealth() {
        return health.probe();
    }

    /**
     * Applies a manual address override (E19.5).
     *
     * @return the rejection when the text is unusable, otherwise the new header
     */
    public Outcome selectAddress(String raw) {
        return model.selectAddress(raw)
                .map(Outcome::failed)
                .orElseGet(() -> Outcome.ok("Clients should now connect to " + model.headerText() + "."));
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message;
    }
}
