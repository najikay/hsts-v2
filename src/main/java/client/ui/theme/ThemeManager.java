package client.ui.theme;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pushes {@link ThemeState} onto live JavaFX scenes (Presentation tier, E4.7).
 *
 * <p>Thin by design — every decision already happened in {@link ThemeState}; this
 * class only knows the three mechanical steps of applying one:
 * <ol>
 *   <li>{@link Application#setUserAgentStylesheet} with AtlantaFX
 *       {@code PrimerLight} / {@code PrimerDark} — the base control skin;</li>
 *   <li>the scene stylesheet list becomes exactly
 *       {@code [css/hsts.css, css/accent-&lt;palette&gt;.css]} — tokens then accent;</li>
 *   <li>the {@code dark} style class is added to / removed from the scene root,
 *       which is what activates every {@code .root.dark} token block.</li>
 * </ol>
 *
 * <p>Because all three are stylesheet-level operations, a mode or palette change
 * re-styles the running app with <b>no node rebuilt and no restart</b> (PRD §4.1)
 * — the CSS engine simply re-resolves the looked-up colours.
 *
 * <p>Attaching registers a {@link ThemeState} listener that re-applies on every
 * change, so callers wire a scene once and never think about theming again.
 * {@link #detach} exists for secondary windows (the notification popout, a print
 * preview) so closing one does not leave a listener holding its scene alive.
 */
public final class ThemeManager {

    private static final Logger log = LoggerFactory.getLogger(ThemeManager.class);

    /** The token layer; always first so accent files can override nothing but accents. */
    public static final String TOKENS_STYLESHEET = "/css/hsts.css";

    /** Style class toggled on the scene root to activate the dark token block. */
    public static final String DARK_STYLE_CLASS = "dark";

    private final ThemeState state;
    private final List<Scene> scenes = new ArrayList<>();

    public ThemeManager(ThemeState state) {
        this.state = Objects.requireNonNull(state, "state");
        state.addListener(event -> applyAll());
    }

    /** @return the decision layer — the settings screen mutates this, not the manager. */
    public ThemeState state() {
        return state;
    }

    /** Styles a scene now and keeps it styled for as long as it stays attached. */
    public void attach(Scene scene) {
        Objects.requireNonNull(scene, "scene");
        if (!scenes.contains(scene)) {
            scenes.add(scene);
        }
        applyTo(scene);
    }

    /** Stops maintaining a scene (a secondary window closing). */
    public void detach(Scene scene) {
        scenes.remove(scene);
    }

    /** @return how many scenes this manager keeps styled. */
    public int attachedSceneCount() {
        return scenes.size();
    }

    /** Re-applies the current state to every attached scene. */
    public void applyAll() {
        applyUserAgentStylesheet();
        for (Scene scene : List.copyOf(scenes)) {
            applyTo(scene);
        }
    }

    /**
     * Applies the current state to one scene: AtlantaFX base, our two
     * stylesheets, and the {@code dark} root class.
     */
    public void applyTo(Scene scene) {
        applyUserAgentStylesheet();
        List<String> wanted = List.of(
                resource(TOKENS_STYLESHEET),
                resource(state.palette().stylesheet()));
        if (!scene.getStylesheets().equals(wanted)) {
            scene.getStylesheets().setAll(wanted);
        }
        applyDarkClass(scene.getRoot());
    }

    /**
     * Toggles the {@code dark} style class on a root node.
     *
     * <p>Public because dialogs and popups own their own scene graphs that are
     * not children of the main scene root; they call this on their root so the
     * same token blocks apply.
     */
    public void applyDarkClass(Parent root) {
        if (root == null) {
            return;
        }
        boolean dark = state.isDark();
        boolean has = root.getStyleClass().contains(DARK_STYLE_CLASS);
        if (dark && !has) {
            root.getStyleClass().add(DARK_STYLE_CLASS);
        } else if (!dark && has) {
            root.getStyleClass().remove(DARK_STYLE_CLASS);
        }
    }

    private void applyUserAgentStylesheet() {
        String base = state.isDark()
                ? new PrimerDark().getUserAgentStylesheet()
                : new PrimerLight().getUserAgentStylesheet();
        if (!base.equals(Application.getUserAgentStylesheet())) {
            Application.setUserAgentStylesheet(base);
            log.debug("base theme → {}", state.effectiveMode());
        }
    }

    /**
     * @throws IllegalStateException when a stylesheet is missing from the JAR —
     *         a packaging error that must fail loudly at startup, not silently
     *         produce an unstyled app in front of the examiners
     */
    private String resource(String path) {
        URL url = ThemeManager.class.getResource(path);
        if (url == null) {
            throw new IllegalStateException("Missing stylesheet on the classpath: " + path);
        }
        return url.toExternalForm();
    }
}
