package client.core;

import java.util.Objects;

/**
 * One position in the navigation history: a {@link Route} plus the
 * {@link NavParams} it was entered with (Presentation tier, E4.2).
 *
 * <p>Carrying the params alongside the route is what makes "back" correct rather
 * than merely plausible — returning to {@code bank.detail} must return to the
 * question that was open, not to a blank detail screen.
 *
 * @param route  the destination
 * @param params the parameters the destination was entered with (never {@code null})
 */
public record NavEntry(Route route, NavParams params) {

    public NavEntry {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(params, "params");
    }

    /** @return an entry with no parameters. */
    public static NavEntry of(Route route) {
        return new NavEntry(route, NavParams.empty());
    }

    /** @return the route id — the key screens and factories are registered under. */
    public String routeId() {
        return route.id();
    }
}
