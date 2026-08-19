package client.core;

import java.util.List;

/**
 * The client's route table (Presentation tier, E4.2).
 *
 * <p>Every destination is declared once, here, as a constant. Screens navigate
 * with {@code navigator().navigate(Routes.SETTINGS.id())} rather than with a
 * string literal, so a renamed route is a compile error instead of a runtime
 * "No route registered for id".
 *
 * <p>E4 ships the routes the design system itself needs; E5 onwards adds one
 * constant per screen from the PRD §4.2 inventory and registers a builder with
 * {@code ScreenFactory}. Nothing else has to change — that is the point of
 * keeping the table separate from the navigator.
 */
public final class Routes {

    /** Host/port entry, before any session exists (F1.5). Full-bleed, no shell. */
    public static final Route CONNECT = Route.standalone("connect", "Connect to server");

    /** Theme mode + accent palette (E4.9). Rendered inside the shell when one exists. */
    public static final Route SETTINGS = Route.standalone("settings", "Settings");

    /**
     * The E0 prototype question list, kept reachable so the app stays runnable
     * end-to-end (connect → questions) while E6 rewrites the real bank screens.
     */
    public static final Route QUESTIONS = Route.standalone("questions", "Question bank");

    private Routes() {
    }

    /** @return every route E4 defines, for bulk registration at startup. */
    public static List<Route> all() {
        return List.of(CONNECT, SETTINGS, QUESTIONS);
    }

    /** Registers all E4 routes on a navigator. */
    public static void registerAll(Navigator navigator) {
        all().forEach(navigator::register);
    }
}
