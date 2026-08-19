package server.core;

import server.db.DbBootstrap;

import java.io.IOException;

/**
 * Entry point for the HSTS Fat Server (Logic tier).
 *
 * <p>Instantiates {@link HSTSServer} on the default port and begins listening.
 * Run this before launching the JavaFX client.
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

        HSTSServer server = new HSTSServer(port);
        try {
            // E2.1: the schema is Flyway-managed - migrate BEFORE accepting clients.
            // A pre-E2 hsts_db (legacy prototype `Questions` table) must be dropped
            // and recreated empty once; see docs/PROBLEMS.md / E2 PR1 findings.
            DbBootstrap.migrate();
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
