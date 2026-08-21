package server.console;

import atlantafx.base.theme.PrimerDark;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.Region;

import java.net.URL;
import java.util.List;

/**
 * Dresses the server console in the app's own design system (E19.7 ⚑).
 *
 * <p>The console is styled from the same token layer as the client, and dark by
 * default. Two reasons, and the second is the load-bearing one. It looks like one
 * product rather than like a debug window somebody left in the build, which is
 * what a defence panel sees. And it is a projector surface in a dim room, where
 * a white page at forty points is a wall of glare.
 *
 * <h2>Stylesheets, not components</h2>
 *
 * <p>The console reuses {@code hsts.css} and an accent file, and builds plain
 * JavaFX controls carrying the documented style classes, rather than importing
 * {@code client.ui.components.Buttons} and friends. That keeps the tier rule
 * intact (server code does not depend on client code) at the cost of a handful of
 * {@code getStyleClass().add} calls, and it means a change to a client component
 * cannot silently restyle the server's window in front of a room.
 *
 * <p>There is exactly one deliberate exception, in {@code ConsoleView}: the seed
 * button's confirmation is the design system's {@code WarnConfirm}. That dialog
 * is the product's one answer to "legal but unusual, read the consequence first",
 * and the console's most destructive button should not get a different one. The
 * exception is named here so it stays an exception rather than becoming a
 * precedent.
 *
 * <p>There is no theme switcher. The console has one look, so nothing here reads
 * or writes a preference, and a stylesheet missing from the JAR fails loudly at
 * startup exactly as {@code ThemeManager} makes it fail on the client: an
 * unstyled console in front of examiners is worse than a clear error in the
 * terminal.
 */
final class ConsoleTheme {

    /** The token layer, shared verbatim with the client. */
    static final String TOKENS = "/css/hsts.css";

    /** Indigo, the product default (PRD §4.1). */
    static final String ACCENT = "/css/accent-indigo.css";

    /** The style class that activates every {@code .root.dark} token block. */
    static final String DARK = "dark";

    private ConsoleTheme() {
    }

    /** Applies the base theme, both stylesheets and the dark root class. */
    static void apply(Scene scene) {
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
        scene.getStylesheets().setAll(List.of(resource(TOKENS), resource(ACCENT)));
        if (!scene.getRoot().getStyleClass().contains(DARK)) {
            scene.getRoot().getStyleClass().add(DARK);
        }
    }

    /** Adds style classes to a node in one call, for readability at the call site. */
    static <T extends Region> T styled(T node, String... classes) {
        node.getStyleClass().addAll(classes);
        return node;
    }

    /**
     * @throws IllegalStateException when a stylesheet is missing from the JAR, a
     *         packaging error that must be loud
     */
    private static String resource(String path) {
        URL url = ConsoleTheme.class.getResource(path);
        if (url == null) {
            throw new IllegalStateException("Missing stylesheet on the classpath: " + path
                    + ". The server JAR was built without its resources.");
        }
        return url.toExternalForm();
    }
}
