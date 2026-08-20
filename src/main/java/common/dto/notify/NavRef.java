package common.dto.notify;

import java.io.Serializable;

/**
 * Where clicking a notification takes you (Common tier, E17.1).
 *
 * <p>A notification that cannot be acted on is half a feature, so every one
 * carries a reference: a client route id plus the id of the thing on it. The
 * panel hands both to {@code Navigator}, which is the only component that knows
 * what a route id means.
 *
 * <p>Deliberately loose, in two ways that matter:
 * <ul>
 *   <li><b>a route id, not a screen class.</b> The server names a destination the
 *       client already publishes in {@code client.core.Routes}; it never imports
 *       client code, and a client one version behind simply finds no such route
 *       and renders the row as plain text instead of navigating.</li>
 *   <li><b>no foreign key.</b> Notifications outlive what they point at. A
 *       dangling reference that opens a "not found" screen is better than a
 *       delete that fails because something once mentioned the row.</li>
 * </ul>
 *
 * <p>Persistence: {@link #route()} is stored in {@code notifications.ref_type}
 * and {@link #entityId()} in {@code ref_id} (both nullable — see
 * {@link #none()}).
 *
 * @param route    a client route id, or {@code null} for a notification with
 *                 nowhere to go
 * @param entityId the entity on that route, or {@code null} when the route needs
 *                 no argument
 */
public record NavRef(String route, Long entityId) implements Serializable {

    private static final long serialVersionUID = 1L;

    public NavRef {
        route = route == null || route.isBlank() ? null : route.trim();
    }

    /** @return a reference to one entity on a route. */
    public static NavRef to(String route, long entityId) {
        return new NavRef(route, entityId);
    }

    /** @return a reference to a route that needs no entity id. */
    public static NavRef to(String route) {
        return new NavRef(route, null);
    }

    /** @return the reference for a notification that is purely informational. */
    public static NavRef none() {
        return new NavRef(null, null);
    }

    /** @return {@code true} when this reference names somewhere to navigate. */
    public boolean isNavigable() {
        return route != null;
    }
}
