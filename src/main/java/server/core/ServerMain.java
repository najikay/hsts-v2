package server.core;

import server.db.DbBootstrap;

import java.io.IOException;

/**
 * Entry point for the HSTS Fat Server (Logic tier).
 *
 * <p>Migrates the database, instantiates {@link HSTSServer} on the default port and begins
 * listening. Run this before launching the JavaFX client.
 *
 * <h2>The ordering rule</h2>
 *
 * <p><b>{@code DbBootstrap.migrate()} must complete before {@code new HSTSServer(port)}.</b>
 * Constructing the server builds its production handler set, and that calls
 * {@code HibernateUtil.sessionFactory()}, which boots a HikariCP pool against
 * {@code hsts_db} on the spot. Do it the other way round and the pool opens against a
 * database Flyway has not created or migrated yet: on a clean machine that is a startup
 * failure, and on a half-migrated one it is worse, because the pool comes up and the first
 * query fails somewhere that looks like a repository bug.
 *
 * <p>It used to be the other way round, harmlessly, because the pre-E2 wiring was entirely
 * in memory and nothing in the constructor touched the database. That is no longer true, so
 * the two lines below are ordered rather than merely adjacent.
 */
public class ServerMain {

    /** Default OCSF listening port for the prototype. */
    private static final int DEFAULT_PORT = 5555;

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port '" + args[0] + "', using default " + DEFAULT_PORT);
            }
        }

        // E2.1: the schema is Flyway-managed - migrate BEFORE anything opens a pool
        // against it, which the HSTSServer constructor below does. See this class's
        // javadoc for why the order is a rule and not a preference.
        // A pre-E2 hsts_db (legacy prototype `Questions` table) must be dropped
        // and recreated empty once; see docs/PROBLEMS.md / E2 PR1 findings.
        DbBootstrap.migrate();

        HSTSServer server = new HSTSServer(port);
        try {
            server.listen();
            System.out.println("==================================================");
            System.out.println(" HSTS Fat Server is UP on port " + port);
            System.out.println(" Acting as the SECURE GATEKEEPER for all DB access.");
            System.out.println(" Clients never touch MySQL directly - every request");
            System.out.println(" is routed and validated here.");
            System.out.println("==================================================");
        } catch (IOException e) {
            System.err.println("Could not listen on port " + port + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
}
