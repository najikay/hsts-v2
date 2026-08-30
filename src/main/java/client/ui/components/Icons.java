package client.ui.components;

import javafx.scene.Node;
import javafx.scene.layout.Region;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Icon factory for the design system (Presentation tier, E4.10–E4.20).
 *
 * <p>Wraps Ikonli's Material 2 Outlined set. Two reasons this is a factory rather
 * than {@code new FontIcon(...)} at each call site:
 *
 * <ul>
 *   <li><b>colour comes from CSS</b>. Each icon gets a style class and no inline
 *       fill, so {@code .nav-item.active .nav-icon { -fx-fill: -hsts-accent; }}
 *       recolours it on a palette switch. An icon with a hard-coded colour is the
 *       one thing that would not follow a live theme change.</li>
 *   <li><b>an unknown literal must not crash a screen</b>. {@link NavItem}-style
 *       data-driven config carries icon names as strings; a typo yields a blank
 *       placeholder of the right size and a log line, not an exception in the
 *       middle of building the shell.</li>
 * </ul>
 *
 * <h2>That second reason is also why this class needed a test ⚑ (B-38)</h2>
 *
 * <p>Swallowing the resolver's exception is right for a data-driven literal and was quietly
 * wrong for the constants below, because a constant is not data — it is a claim that a glyph
 * exists, and the swallow turned three false claims into a {@code WARN} line and a hole in the
 * layout that nobody noticed for four epics ({@code MONITOR}, {@code LOGOUT},
 * {@code WARNING}; {@code BOT} was a fourth, caught by hand at the time).
 *
 * <p>{@code IconsTest.everyConstantResolvesInThePack} now scans every public {@code String}
 * constant here and resolves it the whole way — the handler <b>and</b> the glyph — because
 * {@code IkonResolver.resolve} answers on the {@code mdoal-}/{@code mdomz-} prefix alone and
 * says yes to a name the pack does not have. A new constant is checked by adding it.
 *
 * @see client.ui.shell.NavItem
 */
public final class Icons {

    private static final Logger log = LoggerFactory.getLogger(Icons.class);

    /** Default glyph size, on the 4px grid. */
    public static final int SIZE_DEFAULT = 16;

    /** Larger glyph for dialog discs and empty states. */
    public static final int SIZE_LARGE = 24;

    // --- Literals used across the design system. Material 2 Outlined (mdoal-/mdomz-). ---
    public static final String DASHBOARD = "mdoal-dashboard";
    public static final String BANK = "mdoal-library_books";
    public static final String EXAMS = "mdoal-assignment";
    public static final String APPROVALS = "mdoal-fact_check";
    public static final String RELEASE = "mdomz-schedule";
    // Not "mdomz-monitor" ⚑ (U-1): the material2 pack has no MONITOR, so Icons.of caught the
    // resolver's exception and rendered a blank spacer. It went unseen because the only thing
    // wearing it was a disabled rail item and an empty state nobody reached; enabling Live
    // Monitor put a rail item with no glyph in front of every teacher. Same class of bug as
    // the smart_toy note below, found the same way.
    public static final String MONITOR = "mdoal-desktop_windows";
    public static final String GRADING = "mdoal-grading";
    public static final String RESULTS = "mdoal-bar_chart";
    public static final String REPORTS = "mdoal-insert_chart_outlined";
    // Not smart_toy: that icon postdates the material2 pack (Ikonli 12.3.1 has no
    // SMART_TOY; it logged "unknown literal" and rendered blank). The brain reads
    // as "study bot" just as well and actually exists.
    public static final String BOT = "mdomz-psychology";
    public static final String SETTINGS = "mdomz-settings";
    public static final String BELL = "mdomz-notifications_none";
    public static final String CLOCK = "mdoal-access_time";
    public static final String CHECK = "mdoal-check_circle_outline";
    /**
     * The circled cross, and the outlined twin of {@link #CHECK} (2026-08-29, manual round 3,
     * U-32): a refusal, drawn at the same weight as the tick it sits beside in a list.
     *
     * <p>Not "mdoal-cancel", which is the same mark filled in: a solid disc beside an outlined
     * tick reads as two icon sets rather than as two answers to one question.
     */
    public static final String CROSS = "mdoal-highlight_off";
    /** A pencil: work still being written, which is what an exam DRAFT is (U-32). */
    public static final String EDIT = "mdoal-edit";
    public static final String ERROR = "mdoal-error_outline";
    // Not "mdomz-warning_amber" ⚑ (B-38): the pack has WARNING but no WARNING_AMBER, so every
    // warning chip and toast in the app drew a blank spacer where its glyph should be.
    public static final String WARNING = "mdomz-warning";
    public static final String INFO = "mdoal-info";
    public static final String INBOX = "mdoal-inbox";
    public static final String SEARCH = "mdomz-search";
    public static final String MENU = "mdomz-menu";
    public static final String CHEVRON_LEFT = "mdoal-chevron_left";
    public static final String CHEVRON_RIGHT = "mdoal-chevron_right";
    public static final String CLOUD_OFF = "mdoal-cloud_off";
    /**
     * The column chooser's glyph (2026-08-30, wave 6, U-36): sliders, the mark this pack has
     * for "adjust what you are looking at". The pack has no VIEW_COLUMN in either half.
     */
    public static final String COLUMNS = "mdomz-tune";
    // Not "mdoal-logout" ⚑ (B-38): the pack has LOGIN and EXIT_TO_APP but no LOGOUT, so the
    // profile menu's sign-out item has been drawing an invisible spacer since E5.
    public static final String LOGOUT = "mdoal-exit_to_app";
    /** A question's illustration (E6.6): the ImagePicker's empty state and its Choose button. */
    public static final String IMAGE = "mdoal-image";
    /** The same picture, struck through: the ImagePicker's removed state. */
    public static final String IMAGE_OFF = "mdoal-image_not_supported";
    public static final String UPLOAD = "mdoal-file_upload";
    public static final String DELETE = "mdoal-delete_outline";

    private Icons() {
    }

    /** @return a default-sized icon carrying no style class. */
    public static Node of(String literal) {
        return of(literal, SIZE_DEFAULT, null);
    }

    /** @return an icon of the given size carrying no style class. */
    public static Node of(String literal, int size) {
        return of(literal, size, null);
    }

    /**
     * Builds an icon.
     *
     * @param literal    Ikonli icon description (e.g. {@code "mdoal-dashboard"})
     * @param size       glyph size in px
     * @param styleClass CSS class that will supply {@code -fx-fill}; may be {@code null}
     * @return the icon node, or an invisible spacer of the same size if the
     *         literal cannot be resolved
     */
    public static Node of(String literal, int size, String styleClass) {
        Node node;
        try {
            FontIcon icon = new FontIcon(literal);
            icon.setIconSize(size);
            node = icon;
        } catch (RuntimeException e) {
            log.warn("Unknown icon literal '{}' - rendering a blank placeholder", literal);
            node = placeholder(size);
        }
        if (styleClass != null) {
            node.getStyleClass().add(styleClass);
        }
        return node;
    }

    /** Keeps layout stable when a glyph is missing, rather than collapsing the row. */
    private static Region placeholder(int size) {
        Region spacer = new Region();
        spacer.setMinSize(size, size);
        spacer.setPrefSize(size, size);
        spacer.setMaxSize(size, size);
        return spacer;
    }
}
