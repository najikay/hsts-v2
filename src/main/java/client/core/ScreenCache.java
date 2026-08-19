package client.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Route-id keyed cache of screen instances (Presentation tier, E4.2 — the
 * caching half of {@code ScreenFactory}).
 *
 * <p>Building a screen means loading FXML and constructing a node graph, which
 * is the single most expensive thing the client does per navigation. Caching by
 * route id turns every revisit into a field read; {@link ScreenLifecycle}'s
 * {@code onShow} then re-populates the reused instance with the new parameters,
 * which is why cached screens must keep their state in {@code onShow} rather
 * than in their constructor.
 *
 * <p>Generic and FX-free so the caching, eviction and lazy-construction rules
 * are unit-testable on their own; {@code client.ui.screen.ScreenFactory} is the
 * thin FX-aware wrapper.
 *
 * @param <T> the cached screen type
 */
public final class ScreenCache<T> {

    private static final Logger log = LoggerFactory.getLogger(ScreenCache.class);

    private final Map<String, Supplier<T>> builders = new LinkedHashMap<>();
    private final Map<String, T> instances = new LinkedHashMap<>();

    /**
     * Registers how to build the screen for a route id.
     *
     * @throws IllegalStateException on a duplicate id — two features claiming one route
     */
    public void register(String routeId, Supplier<T> builder) {
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(builder, "builder");
        if (builders.putIfAbsent(routeId, builder) != null) {
            throw new IllegalStateException("Screen builder already registered for route " + routeId);
        }
    }

    /** @return {@code true} when a builder exists for this route id. */
    public boolean isRegistered(String routeId) {
        return builders.containsKey(routeId);
    }

    /**
     * Returns the cached instance, building it on first request.
     *
     * @throws IllegalArgumentException when no builder was registered
     * @throws IllegalStateException    when the builder returned {@code null}
     */
    public T get(String routeId) {
        T cached = instances.get(routeId);
        if (cached != null) {
            return cached;
        }
        Supplier<T> builder = builders.get(routeId);
        if (builder == null) {
            throw new IllegalArgumentException("No screen registered for route '" + routeId
                    + "'. Known: " + builders.keySet());
        }
        T built = builder.get();
        if (built == null) {
            throw new IllegalStateException("Screen builder for route '" + routeId + "' returned null");
        }
        log.debug("built screen for route {}", routeId);
        instances.put(routeId, built);
        return built;
    }

    /** @return {@code true} when this route's screen has already been built. */
    public boolean isBuilt(String routeId) {
        return instances.containsKey(routeId);
    }

    /**
     * Drops the cached instance for a route, keeping its builder — the next visit
     * rebuilds from scratch. Used when a screen's data model changed so deeply
     * that refreshing in place would be more code than rebuilding (e.g. a role
     * change), and on logout to make sure nothing of the old session survives.
     *
     * @return {@code true} when an instance was actually dropped
     */
    public boolean evict(String routeId) {
        return instances.remove(routeId) != null;
    }

    /** Drops every cached instance, keeping all builders. Called on logout. */
    public void evictAll() {
        instances.clear();
    }

    /** @return route ids with a live cached instance. */
    public Set<String> cachedRouteIds() {
        return Collections.unmodifiableSet(instances.keySet());
    }

    /** @return route ids that have a registered builder. */
    public Set<String> registeredRouteIds() {
        return Collections.unmodifiableSet(builders.keySet());
    }

    /** @return how many screens are currently held in memory. */
    public int size() {
        return instances.size();
    }
}
