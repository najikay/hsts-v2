package server.console;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.core.SessionManager;

import java.time.Clock;
import java.util.Objects;

/**
 * Opens the server console window (Presentation, E19.2 / F13.1).
 *
 * <p>{@code Application.launch} constructs its class reflectively and hands it
 * nothing, so the wiring the console needs is placed in {@link #prepare} before
 * the launch and read back here. A static handover is a cost; the alternatives
 * are worse. Starting the JavaFX toolkit by hand
 * ({@code Platform.startup}) leaves the server without the lifecycle callbacks
 * that stop the refresh timer, and threading the wiring through a system property
 * is the same static in a worse disguise.
 *
 * <p>The handover is cleared as soon as it is read, so a second launch cannot
 * quietly attach to the first server's session map.
 *
 * <h2>Closing the window does not stop the server</h2>
 *
 * <p>{@code Platform.setImplicitExit(false)} and an explicit
 * {@link System#exit(int)} on close. The default JavaFX behaviour, exiting the
 * toolkit when the last window closes, would leave the process alive with no
 * window and no way to get one back, which is the worst of both. Closing the
 * console is the operator saying "stop the server", so it says so, and the
 * confirmation is the window's own close prompt.
 */
public final class ServerConsoleApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(ServerConsoleApp.class);

    /** Everything the console needs, handed over before {@code launch}. */
    public record Wiring(ConsoleSession session, ConsoleClients clients,
                         SessionManager sessions, LogTailModel logTail, Clock clock) {

        public Wiring {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(clients, "clients");
            Objects.requireNonNull(sessions, "sessions");
            Objects.requireNonNull(logTail, "logTail");
            Objects.requireNonNull(clock, "clock");
        }
    }

    private static volatile Wiring pending;

    private ConsoleView view;

    /** Stores the wiring the next {@link #launch(String...)} will pick up. */
    public static void prepare(Wiring wiring) {
        pending = Objects.requireNonNull(wiring, "wiring");
    }

    /** @return the stored wiring, clearing it so it is used exactly once. */
    static Wiring take() {
        Wiring wiring = pending;
        pending = null;
        return wiring;
    }

    /**
     * Opens the window and blocks until it closes.
     *
     * <p>Called from {@code ServerMain} on the main thread after
     * {@link #prepare}. Returns only when the console is closed.
     */
    public static void open() {
        Application.launch(ServerConsoleApp.class);
    }

    @Override
    public void start(Stage stage) {
        Wiring wiring = take();
        if (wiring == null) {
            // Somebody launched the console class directly. Say so rather than
            // opening an empty window that looks like a broken server.
            throw new IllegalStateException(
                    "The server console was launched without its wiring. "
                            + "Start the server with ServerMain, not this class.");
        }
        view = new ConsoleView(wiring.session(), wiring.clients(), wiring.sessions(),
                wiring.logTail(), wiring.clock());
        Region root = view.build();
        Scene scene = new Scene(root, 1280, 820);
        ConsoleTheme.apply(scene);

        stage.setTitle("HSTS server console · " + wiring.session().model().headerText());
        stage.setScene(scene);
        stage.setMinWidth(1100);
        stage.setMinHeight(700);
        Platform.setImplicitExit(false);
        stage.setOnCloseRequest(event -> shutdown());
        stage.show();
        view.start();
        log.info("Server console open. Clients should connect to {}",
                wiring.session().model().headerText());
    }

    @Override
    public void stop() {
        if (view != null) {
            view.stop();
        }
    }

    private void shutdown() {
        log.info("Server console closed, stopping the server.");
        if (view != null) {
            view.stop();
        }
        Platform.exit();
        // The OCSF listener runs on non-daemon threads that outlive Platform.exit;
        // without this the process would linger with no window.
        System.exit(0);
    }
}
