package client.core;

import server.core.ServerMain;

/**
 * <b>Dev-only</b> co-launcher: one JVM running the server and the client together
 * (Presentation tier, prototype-era convenience).
 *
 * <h2>This is NOT the client jar's entry point ⚑ (B-23)</h2>
 *
 * <p>This javadoc used to open <i>"Non-JavaFX, single-click entry point for the Fat
 * JAR (manifest Main-Class)"</i>, and {@code ARCHITECTURE.md} §9 said the same. Both
 * were wrong, and wrong in a way that costs whoever believes them a stack trace:
 * {@code pom.xml} sets {@code <mainClass>client.core.ClientLauncher</mainClass>} and
 * the built {@code G<Num>_Client.jar}'s manifest reads
 * {@code Main-Class: client.core.ClientLauncher}. Acceptance case 15.1 read that off
 * the artefact itself.
 *
 * <p><b>And this class does not work inside that jar.</b> It boots {@link ServerMain}
 * in-process, and the client jar deliberately ships no database libraries (F14.1,
 * verified in 15.1: zero HikariCP, Hibernate, Flyway or MySQL classes). Run for real:
 *
 * <pre>{@code
 * $ java -cp target/G13_Client.jar client.core.Launcher
 * Exception in thread "hsts-server" java.lang.NoClassDefFoundError: com/zaxxer/hikari/HikariConfig
 *         at server.db.DbBootstrap.dataSource(DbBootstrap.java:95)
 * }</pre>
 *
 * <p>The failure is on the daemon thread, so the GUI still comes up — which is the
 * worst of both: a red trace on the terminal and a client quietly talking to no
 * local server.
 *
 * <h2>What it is for</h2>
 *
 * <p>A development loop on the <b>full classpath</b> ({@code target/classes} or the
 * server jar), where one run gives you a server and a UI. It starts
 * {@link ServerMain} on a background daemon thread, waits briefly for the OCSF port
 * to bind, then launches {@link ClientApp}. It is a plain class rather than an
 * {@link javafx.application.Application} subclass for the same reason
 * {@code ClientLauncher} is — a shaded jar will not launch when {@code main} lives
 * directly on the {@code Application} — but that property is what makes it
 * launchable, not what makes it the launcher.
 *
 * <p>The architecture is unaffected: this is a co-launch, not a coupling. The server
 * runs on its own ({@code java -jar G<Num>_Server.jar}) and serves remote clients,
 * which is what the deliverable actually does.
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
