package client.ui.shell;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The app shell's model: nav items, which one is active, badge counts and
 * whether the rail is collapsed (Presentation tier, E4.10).
 *
 * <p>Everything the shell knows lives here rather than in the node graph, which
 * matters for three reasons: the rail's auto-collapse rule (PRD §4.1 — collapse
 * below 1400px) is a numeric threshold worth testing; badge updates arrive as
 * server pushes (F11.1) and must find their item by route id, not by walking
 * children; and "which item is active" has to survive routes that are not in the
 * rail at all (a question detail screen keeps "Question bank" highlighted).
 */
public final class ShellState {

    /** Below this window width the rail collapses to icons (PRD §4.1). */
    public static final double COLLAPSE_WIDTH_THRESHOLD = 1400;

    private final Map<String, NavItem> items = new LinkedHashMap<>();
    private final Map<String, String> activeAliases = new LinkedHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();

    private String activeRouteId;
    private boolean collapsed;
    private boolean userPinnedExpansion;
    private int unreadNotifications;

    /** Replaces the rail contents (called on login, once the role is known). */
    public void setItems(List<NavItem> newItems) {
        Objects.requireNonNull(newItems, "items");
        items.clear();
        for (NavItem item : newItems) {
            items.put(item.routeId(), item);
        }
        notifyChanged();
    }

    /** @return the rail items, in order. */
    public List<NavItem> items() {
        return List.copyOf(items.values());
    }

    /** @return the item for a route id, if the rail has one. */
    public Optional<NavItem> item(String routeId) {
        return Optional.ofNullable(items.get(routeId));
    }

    /**
     * Declares that {@code childRouteId} should keep {@code railRouteId}
     * highlighted — e.g. {@code bank.detail} and {@code bank.editor} both belong
     * to the "Question bank" rail item.
     */
    public void alias(String childRouteId, String railRouteId) {
        activeAliases.put(Objects.requireNonNull(childRouteId, "childRouteId"),
                Objects.requireNonNull(railRouteId, "railRouteId"));
    }

    /** Records the route the user navigated to. */
    public void setActiveRoute(String routeId) {
        this.activeRouteId = routeId;
        notifyChanged();
    }

    /** @return the raw current route id, which may not be a rail item. */
    public String activeRouteId() {
        return activeRouteId;
    }

    /**
     * @return the rail item that should render as active — the current route, or
     *         the item it is aliased to, or empty when neither is in the rail
     */
    public Optional<NavItem> activeItem() {
        if (activeRouteId == null) {
            return Optional.empty();
        }
        NavItem direct = items.get(activeRouteId);
        if (direct != null) {
            return Optional.of(direct);
        }
        String alias = activeAliases.get(activeRouteId);
        return alias == null ? Optional.empty() : Optional.ofNullable(items.get(alias));
    }

    /** @return {@code true} when this rail item should carry the active styling. */
    public boolean isActive(NavItem item) {
        return activeItem().map(active -> active.routeId().equals(item.routeId())).orElse(false);
    }

    // ----------------------------------------------------------------- badges

    /**
     * Updates one item's badge in place (a push arrived: three new approvals
     * waiting). Unknown route ids are ignored — a push for a screen this role
     * cannot see is not an error.
     *
     * @return {@code true} when an item was actually updated
     */
    public boolean setBadge(String routeId, int count) {
        NavItem existing = items.get(routeId);
        if (existing == null || existing.badge() == count) {
            return false;
        }
        items.put(routeId, existing.withBadge(count));
        notifyChanged();
        return true;
    }

    /** Clears every badge (called after "mark all read"). */
    public void clearBadges() {
        boolean changed = false;
        for (Map.Entry<String, NavItem> entry : items.entrySet()) {
            if (entry.getValue().hasBadge()) {
                entry.setValue(entry.getValue().withBadge(NavItem.BADGE_NONE));
                changed = true;
            }
        }
        if (changed) {
            notifyChanged();
        }
    }

    /** Sets the navbar bell's unread count (F11.2). */
    public void setUnreadNotifications(int count) {
        int safe = Math.max(0, count);
        if (safe != unreadNotifications) {
            unreadNotifications = safe;
            notifyChanged();
        }
    }

    public int unreadNotifications() {
        return unreadNotifications;
    }

    /** @return the bell badge text: {@code ""}, a number, or {@code "9+"}. */
    public String unreadBadgeText() {
        if (unreadNotifications <= 0) {
            return "";
        }
        return unreadNotifications > NavItem.BADGE_CAP ? NavItem.BADGE_CAP + "+"
                : Integer.toString(unreadNotifications);
    }

    // -------------------------------------------------------------- rail size

    /** @return {@code true} when the rail is showing icons only. */
    public boolean isCollapsed() {
        return collapsed;
    }

    /**
     * User-driven toggle. Also pins the choice, so a later window resize does not
     * fight the user by re-expanding what they deliberately collapsed.
     */
    public void toggleCollapsed() {
        collapsed = !collapsed;
        userPinnedExpansion = true;
        notifyChanged();
    }

    /**
     * Applies the responsive rule for a new window width (PRD §4.1).
     *
     * <p>Does nothing once the user has toggled the rail by hand — an explicit
     * preference outranks the automatic rule for the rest of the session.
     *
     * @return {@code true} when the rail's state changed
     */
    public boolean applyWindowWidth(double width) {
        if (userPinnedExpansion) {
            return false;
        }
        boolean shouldCollapse = width < COLLAPSE_WIDTH_THRESHOLD;
        if (shouldCollapse == collapsed) {
            return false;
        }
        collapsed = shouldCollapse;
        notifyChanged();
        return true;
    }

    /** @return {@code true} when the user has overridden the responsive rule. */
    public boolean isExpansionPinned() {
        return userPinnedExpansion;
    }

    /** Forgets a manual toggle, handing control back to the responsive rule. */
    public void unpinExpansion() {
        userPinnedExpansion = false;
    }

    /** @return the rail's current width in pixels, for the collapse animation. */
    public double railWidth() {
        return collapsed ? 64 : 224;
    }

    // -------------------------------------------------------------- listeners

    /** Subscribes to "something in the shell model changed"; the view re-reads. */
    public void onChange(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** @return the number of subscribers (leak checks in tests). */
    public int listenerCount() {
        return listeners.size();
    }

    private void notifyChanged() {
        for (Runnable listener : List.copyOf(listeners)) {
            listener.run();
        }
    }
}
