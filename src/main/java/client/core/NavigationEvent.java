package client.core;

import java.util.Objects;
import java.util.Optional;

/**
 * What the {@link Navigator} just did (Presentation tier, E4.2).
 *
 * <p>Published to navigator listeners so the pieces that must react to a route
 * change — {@code ScreenManager} (swap the scene root), the app shell (highlight
 * the active rail item, rebuild the breadcrumb), the window title — each
 * subscribe independently instead of being called explicitly from every
 * navigation site.
 *
 * @param from the entry being left, or {@code null} on the very first navigation
 * @param to   the entry being entered (never {@code null})
 * @param kind how the transition happened
 */
public record NavigationEvent(NavEntry from, NavEntry to, Kind kind) {

    /** How a navigation came about — drives the direction of screen transitions. */
    public enum Kind {
        /** Forward navigation; the previous entry was pushed onto the back-stack. */
        PUSH,
        /** Forward navigation that overwrote the current entry (no back-stack growth). */
        REPLACE,
        /** Backwards navigation, popping the back-stack. */
        BACK
    }

    public NavigationEvent {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(kind, "kind");
    }

    /** @return the entry being left, empty on first navigation. */
    public Optional<NavEntry> previous() {
        return Optional.ofNullable(from);
    }

    /** @return {@code true} when the user is moving backwards (transition slides right). */
    public boolean isBackwards() {
        return kind == Kind.BACK;
    }
}
