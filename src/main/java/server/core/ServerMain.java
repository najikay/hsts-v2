package server.core;

import common.dto.discovery.ServerAnnouncement;
import server.console.ConsoleClients;
import server.console.ConsoleHealth;
import server.console.ConsoleModel;
import server.console.ConsoleSession;
import server.console.LogTailModel;
import server.console.NetworkAddress;
import server.console.NetworkDetector;
import server.console.RingBufferAppender;
import server.console.ServerConsoleApp;
import server.db.DbBootstrap;
import server.db.seed.SeedLoader;
import server.discovery.DiscoveryResponder;
import server.discovery.DiscoveryTransport;
import server.discovery.ServerFingerprint;
import server.features.auth.UserRecord;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.List;

/**
 * Entry point for the HSTS Fat Server (Logic tier, E3.1 / E19).
 *
 * <p>One sequence: parse the switches, migrate the database, construct the
 * server, start listening, start the discovery responder, and open the console
 * window unless {@code --headless} was given.
 *
 * <h2>The ordering rule</h2>
 *
 * <p><b>{@code DbBootstrap.migrate()} must complete before {@code new
 * HSTSServer(port)}.</b> Constructing the server builds its production handler
 * set, and that calls {@code HibernateUtil.sessionFactory()}, which boots a
 * HikariCP pool against {@code hsts_db} on the spot. Do it the other way round and
 * the pool opens against a database Flyway has not created or migrated yet: on a
 * clean machine that is a startup failure, and on a half-migrated one it is worse,
 * because the pool comes up and the first query fails somewhere that looks like a
 * repository bug.
 *
 * <p>It used to be the other way round, harmlessly, because the pre-E2 wiring was
 * entirely in memory and nothing in the constructor touched the database. That is
 * no longer true, so the two lines below are ordered rather than merely adjacent.
 *
 * <p>E19 added a third position to that rule: <b>the console opens after the
 * migration, never before.</b> A console that opened first would have to render a
 * server that might still fail to exist, and a migration failure would then land
 * in a window that had already promised the operator an address.
 *
 * <h2>A migration failure is a sentence, not a stack trace</h2>
 *
 * <p>{@link StartupMessages#migrationFailed} turns the exception into one line
 * saying what to check. The trace still goes to the log, because somebody may need it,
 * but what the operator reads first is a sentence. In the console that sentence is
 * the window; headless, it is stderr. This matters at exactly one moment, which is
 * the first boot on a machine nobody has run MySQL on, and it is the moment a wall
 * of Flyway stack frames helps least.
 *
 * <h2>--headless</h2>
 *
 * <p>Runs exactly as the pre-E19 server ran: migrate, listen, log, and block. No
 * toolkit is started, no window is opened, and nothing in the path below reaches
 * JavaFX. The switch is parsed by {@link ServerArgs}, which is tested; this class
 * is transport glue and is excluded from the coverage gate.
 */
public class ServerMain {

    /** How long a discovery socket read blocks before the stop flag is re-checked. */
    private static final int DISCOVERY_POLL_MILLIS = 500;

    public static void main(String[] args) {
        ServerArgs parsed = ServerArgs.parse(args);
        parsed.warnings().forEach(warning -> System.err.println("[ServerMain] " + warning));

        // E2.1: the schema is Flyway-managed - migrate BEFORE anything opens a pool
        // against it, which the HSTSServer constructor below does. See this class's
        // javadoc for why the order is a rule and not a preference.
        try {
            DbBootstrap.migrate();
        } catch (RuntimeException failure) {
            fail(StartupMessages.migrationFailed(failure), failure);
            return;
        }

        HSTSServer server = new HSTSServer(parsed.port());
        try {
            server.listen();
            banner(parsed.port());
        } catch (IOException e) {
            fail("Could not listen on port " + parsed.port() + ": " + e.getMessage()
                    + ". Another program may already be using that port. Close it, or start "
                    + "the server with --port and a different number.", e);
            return;
        }

        ServerFingerprint.Identity identity = ServerFingerprint.in(configDirectory()).loadOrCreate();
        ConsoleModel model = new ConsoleModel(detectAddresses(), parsed.port(), identity.fingerprint());
        DiscoveryResponder responder = startDiscovery(parsed, model, identity);

        if (parsed.headless()) {
            System.out.println(" Running headless. Clients should connect to " + model.headerText());
            System.out.println(" Discovery id: " + model.shortFingerprint());
            return;
        }
        openConsole(server, model, responder);
    }

    // ===================== Console =======================================

    /**
     * Assembles the console's collaborators and opens the window.
     *
     * <p>This method is the only place in the server that mentions JavaFX, and it
     * is unreachable under {@code --headless}. Everything it hands over is a seam:
     * the listener control, the seed runner, the health probes and the name lookup
     * are all interfaces, which is why the console's behaviour is unit-tested
     * while this assembly is glue.
     */
    private static void openConsole(HSTSServer server, ConsoleModel model,
                                    DiscoveryResponder responder) {
        Clock clock = Clock.systemUTC();
        ConsoleSession session = new ConsoleSession(model,
                listenerControl(server),
                seedRunner(server),
                ConsoleHealth.of(server.sessionFactory(), server.sessions(),
                        server.providerChain(), clock),
                discoveryControl(responder));

        ConsoleClients.UserNames names = server.userDirectory() == null
                ? ConsoleClients.UserNames.NONE
                : userId -> server.userDirectory().findById(userId).map(UserRecord::displayName);

        ServerConsoleApp.prepare(new ServerConsoleApp.Wiring(session,
                new ConsoleClients(names), server.sessions(),
                new LogTailModel(RingBufferAppender.buffer()), clock));
        ServerConsoleApp.open();
    }

    private static ConsoleSession.ServerControl listenerControl(HSTSServer server) {
        return new ConsoleSession.ServerControl() {
            @Override
            public void startListening() throws IOException {
                server.listen();
            }

            @Override
            public void stopListening() {
                // stopListening, never close: close() would also drop every client
                // already connected, and a student mid-exam is one of them. This
                // stops NEW connections being accepted and nothing else.
                server.stopListening();
            }

            @Override
            public boolean isListening() {
                return server.isListening();
            }
        };
    }

    /**
     * The console's seed button (E19.6).
     *
     * <p>Hands straight to {@code SeedLoader}: the mode, the confirmation seam and
     * the summary are all its, and the console reimplements none of it.
     */
    private static ConsoleSession.SeedRunner seedRunner(HSTSServer server) {
        return (mode, confirmation) ->
                SeedLoader.standard(server.sessionFactory()).load(mode, confirmation);
    }

    private static ConsoleSession.DiscoveryControl discoveryControl(DiscoveryResponder responder) {
        if (responder == null) {
            return ConsoleSession.DiscoveryControl.DISABLED;
        }
        return new ConsoleSession.DiscoveryControl() {
            @Override
            public boolean enable() {
                return responder.start();
            }

            @Override
            public boolean disable() {
                return responder.stop();
            }

            @Override
            public boolean isRunning() {
                return responder.isRunning();
            }
        };
    }

    // ===================== Discovery =====================================

    /**
     * Starts the UDP responder (E19.8).
     *
     * <p>The announcement is built per request from the console's model, so an
     * operator who overrides the address mid-demo changes what clients are told
     * without restarting anything.
     *
     * @return the responder, or {@code null} when discovery is off or its port
     *         could not be bound; either way the server itself is unaffected
     */
    private static DiscoveryResponder startDiscovery(ServerArgs args, ConsoleModel model,
                                                     ServerFingerprint.Identity identity) {
        if (!args.discoveryEnabled()) {
            model.setDiscoveryEnabled(false);
            return null;
        }
        try {
            DiscoveryTransport transport =
                    DiscoveryTransport.udp(args.discoveryPort(), DISCOVERY_POLL_MILLIS);
            DiscoveryResponder responder = new DiscoveryResponder(transport,
                    () -> new ServerAnnouncement(identity.name(),
                            model.selectedIp().orElse("127.0.0.1"), model.port(),
                            identity.fingerprint()),
                    Clock.systemUTC());
            responder.start();
            model.setDiscoveryEnabled(true);
            return responder;
        } catch (IOException e) {
            // Discovery is a convenience over typing an address. Losing it must
            // never cost the server, so this is a warning and a false flag.
            System.err.println("[ServerMain] Discovery is off: UDP port " + args.discoveryPort()
                    + " could not be opened (" + e.getMessage()
                    + "). Clients will need the address typed in by hand.");
            model.setDiscoveryEnabled(false);
            return null;
        }
    }

    // ===================== Helpers =======================================

    private static List<NetworkAddress> detectAddresses() {
        return NetworkDetector.system().all();
    }

    /**
     * Where {@code server.properties} lives, and therefore where the discovery id
     * is kept beside it (E19.9).
     */
    private static Path configDirectory() {
        try {
            Path codeSource = Paths.get(ServerMain.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path parent = java.nio.file.Files.isRegularFile(codeSource)
                    ? codeSource.getParent() : Paths.get("");
            return configDirectoryFor(parent == null ? Paths.get("") : parent);
        } catch (Exception ignored) {
            return Paths.get("");
        }
    }

    /**
     * The directory the server keeps its identity in, given where its jar sits.
     *
     * <p>Beside the jar, which is the deliverable's rule: the two jars and their
     * properties travel together and the id beside them survives every restart.
     * <b>Except under a Maven {@code target} directory</b> (2026-08-29, manual round 2,
     * U-22): on a dev machine the jar is {@code target/hsts-server.jar} and
     * {@code clean package} empties {@code target} twice a day, so the id was reborn on
     * every rebuild and every client that had pinned the old one warned that the server
     * "now identifies itself as" someone else. The tester read that as a network problem;
     * it was the build. Under {@code target} the id lives one level up, in the project
     * root, which a clean does not touch.
     *
     * @param jarDirectory the directory holding the running jar (or the working directory)
     * @return where {@code server-id.properties} belongs
     */
    static Path configDirectoryFor(Path jarDirectory) {
        Path name = jarDirectory.getFileName();
        if (name != null && "target".equals(name.toString()) && jarDirectory.getParent() != null) {
            return jarDirectory.getParent();
        }
        return jarDirectory;
    }

    /** Prints the sentence, then the trace, then leaves with a failure code. */
    private static void fail(String sentence, Throwable failure) {
        System.err.println();
        System.err.println("  " + sentence);
        System.err.println();
        failure.printStackTrace();
        System.exit(1);
    }

    private static void banner(int port) {
        System.out.println("==================================================");
        System.out.println(" HSTS Fat Server is UP on port " + port);
        System.out.println(" Acting as the SECURE GATEKEEPER for all DB access.");
        System.out.println(" Clients never touch MySQL directly - every request");
        System.out.println(" is routed and validated here.");
        System.out.println("==================================================");
    }
}
