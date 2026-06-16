package client.ui;

import server.ServerMain;

/**
 * Non-JavaFX, single-click entry point for the Fat JAR (manifest Main-Class).
 *
 * <p>Two jobs:
 * <ol>
 *   <li><b>Bypass JavaFX module restrictions</b> — a shaded jar fails to launch
 *       when {@code main} lives directly on an {@link javafx.application.Application}
 *       subclass, so this plain class is the manifest entry point.</li>
 *   <li><b>Boot the whole prototype from one double-click</b> — it first starts
 *       the Fat Server ({@link ServerMain}) on a background daemon thread, waits
 *       briefly for the OCSF port to bind, then launches the JavaFX client
 *       ({@link ClientApp}). For the presentation there is no separate terminal
 *       step: double-click the jar → server + UI come up together.</li>
 * </ol>
 *
 * <p>NOTE: this in-process co-launch is a demo convenience. The architecture is
 * still a true Thin Client / Fat Server — the server can equally be run on its
 * own (e.g. {@code java -cp <jar> server.ServerMain}) and serve remote clients.
 */
public class Launcher {

    /** Time to let the server's OCSF socket bind before the client connects. */
    private static final long SERVER_BOOT_DELAY_MS = 1000L;

    public static void main(String[] args) {
        // 1) Start the Fat Server on a background daemon thread.
        Thread serverThread = new Thread(() -> ServerMain.main(new String[0]), "hsts-server");
        serverThread.setDaemon(true);
        serverThread.start();

        // 2) Give the server a moment to bind its listening port.
        try {
            Thread.sleep(SERVER_BOOT_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[Launcher] Interrupted while waiting for server: " + e.getMessage());
        }

        // 3) Launch the JavaFX client (blocks on the FX application thread).
        ClientApp.main(args);
    }
}
