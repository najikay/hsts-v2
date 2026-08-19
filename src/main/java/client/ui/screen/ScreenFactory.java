package client.ui.screen;

import client.core.ScreenCache;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Builds and caches screens by route id (Presentation tier, E4.2).
 *
 * <p>The FX-facing face of {@link ScreenCache}: registration is lazy suppliers,
 * so declaring the whole route table at startup costs nothing until a route is
 * first visited, and every later visit reuses the built node graph.
 *
 * <p>Two rules worth stating because they shape how screens are written:
 * <ul>
 *   <li>a screen instance is <b>reused</b>, so its per-visit work belongs in
 *       {@code onShow}, not in its constructor or {@code build()};</li>
 *   <li>{@link #evictAll()} on logout drops every instance, so nothing of the
 *       previous user's session can survive into the next one.</li>
 * </ul>
 */
public final class ScreenFactory {

    private final ScreenCache<AbstractScreen> cache = new ScreenCache<>();

    /**
     * Declares how to build the screen for a route.
     *
     * @throws IllegalStateException on a duplicate route id
     */
    public ScreenFactory register(String routeId, Supplier<AbstractScreen> builder) {
        cache.register(routeId, builder);
        return this;
    }

    /**
     * @return the screen for this route, built on first request
     * @throws IllegalArgumentException when nothing is registered for the id
     */
    public AbstractScreen get(String routeId) {
        return cache.get(routeId);
    }

    /** @return {@code true} when a builder exists for this route id. */
    public boolean isRegistered(String routeId) {
        return cache.isRegistered(routeId);
    }

    /** @return {@code true} when this route's screen has already been built. */
    public boolean isBuilt(String routeId) {
        return cache.isBuilt(routeId);
    }

    /** Drops one cached screen; the next visit rebuilds it. */
    public boolean evict(String routeId) {
        return cache.evict(routeId);
    }

    /** Drops every cached screen. Called on logout. */
    public void evictAll() {
        cache.evictAll();
    }

    /** @return route ids with a registered builder. */
    public Set<String> registeredRouteIds() {
        return cache.registeredRouteIds();
    }

    /** @return how many screens are currently held in memory. */
    public int builtCount() {
        return cache.size();
    }
}
