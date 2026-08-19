package client.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Route registry, current-route state and back-stack for the client
 * (Presentation tier, E4.2, ARCHITECTURE §6).
 *
 * <p>Deliberately contains <b>no JavaFX</b>. Navigation is the part of screen
 * management that has rules worth getting wrong — "back" from the root, a
 * back-stack that grows without bound during a long session, re-entering the
 * route you are already on, navigating to an id nobody registered — so it is
 * isolated here as plain Java and unit-tested exhaustively. The FX half
 * ({@code ScreenManager}) does nothing but listen and swap a scene root.
 *
 * <p>Not thread-safe by design: navigation is a JavaFX-Application-Thread
 * activity and adding locks would only hide a threading bug elsewhere.
 */
public final class Navigator {

    /**
     * Depth beyond which the oldest history entry is dropped. A grading session
     * can bounce between a queue and thirty students; remembering all of it buys
     * nothing and keeps every visited screen's params reachable forever.
     */
    public static final int DEFAULT_MAX_BACK_STACK = 32;

    private final Map<String, Route> routes = new LinkedHashMap<>();
    private final Deque<NavEntry> backStack = new ArrayDeque<>();
    private final List<Consumer<NavigationEvent>> listeners = new ArrayList<>();
    private final int maxBackStack;

    private NavEntry current;

    public Navigator() {
        this(DEFAULT_MAX_BACK_STACK);
    }

    public Navigator(int maxBackStack) {
        if (maxBackStack < 1) {
            throw new IllegalArgumentException("maxBackStack must be >= 1, was " + maxBackStack);
        }
        this.maxBackStack = maxBackStack;
    }

    // ---------------------------------------------------------------- registry

    /**
     * Registers a destination.
     *
     * @return {@code this}, for chaining at bootstrap
     * @throws IllegalStateException when the id is already taken — a duplicate id
     *         means two features silently fight over one destination
     */
    public Navigator register(Route route) {
        Objects.requireNonNull(route, "route");
        Route previous = routes.putIfAbsent(route.id(), route);
        if (previous != null) {
            throw new IllegalStateException("Route already registered: " + route.id());
        }
        return this;
    }

    /** Registers several destinations at once. */
    public Navigator registerAll(Route... routesToAdd) {
        for (Route route : routesToAdd) {
            register(route);
        }
        return this;
    }

    /** @return the registered route with this id, if any. */
    public Optional<Route> route(String id) {
        return Optional.ofNullable(routes.get(id));
    }

    /** @return {@code true} when a route with this id has been registered. */
    public boolean isRegistered(String id) {
        return routes.containsKey(id);
    }

    /** @return every registered route, in registration order. */
    public Collection<Route> routes() {
        return Collections.unmodifiableCollection(routes.values());
    }

    // -------------------------------------------------------------- navigation

    /** Navigates forward with no parameters. */
    public void navigate(String routeId) {
        navigate(routeId, NavParams.empty());
    }

    /**
     * Navigates forward, pushing the current entry onto the back-stack.
     *
     * @throws IllegalArgumentException when {@code routeId} is not registered
     */
    public void navigate(String routeId, NavParams params) {
        NavEntry target = entryFor(routeId, params);
        NavEntry previous = current;
        if (previous != null) {
            pushHistory(previous);
        }
        current = target;
        fire(new NavigationEvent(previous, target, NavigationEvent.Kind.PUSH));
    }

    /**
     * Navigates forward <b>without</b> growing the back-stack — the current entry
     * is discarded rather than remembered.
     *
     * <p>Used where going "back" would be wrong or dangerous: connect → login
     * (never re-show the connect screen behind a session), and submitting an exam
     * → the submitted screen (the attempt cannot be re-entered, F6.4/F6.10).
     */
    public void replace(String routeId, NavParams params) {
        NavEntry target = entryFor(routeId, params);
        NavEntry previous = current;
        current = target;
        fire(new NavigationEvent(previous, target, NavigationEvent.Kind.REPLACE));
    }

    /** Navigates forward with no parameters, without growing the back-stack. */
    public void replace(String routeId) {
        replace(routeId, NavParams.empty());
    }

    /**
     * Navigates forward, first clearing the whole history — the new destination
     * becomes the root. Used on login and on logout, so a session change can
     * never leave the previous user's screens reachable via back.
     */
    public void reset(String routeId, NavParams params) {
        backStack.clear();
        replace(routeId, params);
    }

    /** @see #reset(String, NavParams) */
    public void reset(String routeId) {
        reset(routeId, NavParams.empty());
    }

    /** @return {@code true} when there is history to pop. */
    public boolean canGoBack() {
        return !backStack.isEmpty();
    }

    /**
     * Pops one history entry and makes it current.
     *
     * @return {@code true} when a step back happened, {@code false} at the root
     *         (callers can then decide to do nothing, rather than crash)
     */
    public boolean back() {
        if (backStack.isEmpty()) {
            return false;
        }
        NavEntry previous = current;
        current = backStack.pop();
        fire(new NavigationEvent(previous, current, NavigationEvent.Kind.BACK));
        return true;
    }

    /** Forgets the history without changing the current entry. */
    public void clearHistory() {
        backStack.clear();
    }

    // ------------------------------------------------------------------- state

    /** @return the entry currently shown, empty before the first navigation. */
    public Optional<NavEntry> current() {
        return Optional.ofNullable(current);
    }

    /** @return the current route id, or {@code null} before the first navigation. */
    public String currentRouteId() {
        return current == null ? null : current.routeId();
    }

    /** @return {@code true} when the given route is the one currently shown. */
    public boolean isCurrent(String routeId) {
        return current != null && current.routeId().equals(routeId);
    }

    /** @return history ids, most recent first (index 0 is where {@link #back()} goes). */
    public List<String> backStackIds() {
        return backStack.stream().map(NavEntry::routeId).toList();
    }

    /** @return the number of remembered history entries. */
    public int backStackDepth() {
        return backStack.size();
    }

    // --------------------------------------------------------------- listeners

    /** Subscribes to navigation transitions. */
    public void addListener(Consumer<NavigationEvent> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Unsubscribes; safe when never subscribed. */
    public void removeListener(Consumer<NavigationEvent> listener) {
        listeners.remove(listener);
    }

    /** @return the number of active listeners (diagnostics / leak checks in tests). */
    public int listenerCount() {
        return listeners.size();
    }

    // ----------------------------------------------------------------- helpers

    private NavEntry entryFor(String routeId, NavParams params) {
        Objects.requireNonNull(params, "params");
        Route route = routes.get(routeId);
        if (route == null) {
            throw new IllegalArgumentException("No route registered for id '" + routeId
                    + "'. Known routes: " + routes.keySet());
        }
        return new NavEntry(route, params);
    }

    private void pushHistory(NavEntry entry) {
        backStack.push(entry);
        while (backStack.size() > maxBackStack) {
            backStack.removeLast();
        }
    }

    /**
     * Notifies listeners over a snapshot, so a listener that navigates again (a
     * redirect: "not logged in → login") cannot trigger a
     * {@link java.util.ConcurrentModificationException}.
     */
    private void fire(NavigationEvent event) {
        for (Consumer<NavigationEvent> listener : List.copyOf(listeners)) {
            listener.accept(event);
        }
    }
}
