package client.core;

import java.util.Objects;

/**
 * A named destination in the client's screen graph (Presentation tier, E4.2).
 *
 * <p>Screens are addressed by a stable string {@link #id()} rather than by class,
 * which is what lets {@link Navigator} stay free of every FX and feature type:
 * the navigator moves route ids around, and {@code ScreenFactory} is the only
 * thing that knows an id maps to a {@code Screen} implementation. A new feature
 * therefore adds a {@code Route} constant plus a factory registration, and no
 * existing navigation code changes (NFR-19).
 *
 * @param id            stable, unique, lowercase-with-dots identifier (e.g. {@code "bank.list"})
 * @param title         window / page-header title for this destination
 * @param breadcrumb    label used in the app-shell breadcrumb trail
 * @param requiresShell {@code true} when the screen renders inside the app shell
 *                      (navbar + side rail); {@code false} for full-bleed screens
 *                      such as Connect, Login and the exam takeover screens
 */
public record Route(String id, String title, String breadcrumb, boolean requiresShell) {

    public Route {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(breadcrumb, "breadcrumb");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Route id must not be blank");
        }
    }

    /** A full-bleed route (no app shell) whose breadcrumb equals its title. */
    public static Route standalone(String id, String title) {
        return new Route(id, title, title, false);
    }

    /** A route rendered inside the app shell, breadcrumb equal to its title. */
    public static Route shell(String id, String title) {
        return new Route(id, title, title, true);
    }

    /** A route rendered inside the app shell with a shorter breadcrumb label. */
    public static Route shell(String id, String title, String breadcrumb) {
        return new Route(id, title, breadcrumb, true);
    }
}
