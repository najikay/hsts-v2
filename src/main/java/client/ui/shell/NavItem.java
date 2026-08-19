package client.ui.shell;

import java.util.Objects;

/**
 * One entry in the app shell's side rail (Presentation tier, E4.10).
 *
 * <p>The shell is data-driven: a role hands it a {@code List<NavItem>} and the
 * rail builds itself. That is what makes "Student / Teacher / Coordinator /
 * Principal each get their own menu" (F1.2) a list of constants per role rather
 * than four hand-built rails to keep in sync — and what lets the Principal's
 * strictly read-only menu (F9.3) be proven by inspecting one list.
 *
 * @param routeId route this item navigates to; must be registered with the {@code Navigator}
 * @param label   text shown when the rail is expanded, and the tooltip when collapsed
 * @param icon    Ikonli icon literal (e.g. {@code "mdal-dashboard"})
 * @param badge   count shown as a pill on the item; {@code 0} hides it, negative means "dot only"
 */
public record NavItem(String routeId, String label, String icon, int badge) {

    /** Sentinel badge value meaning "show a dot, no number" (unread, unspecified). */
    public static final int BADGE_DOT = -1;

    /** Badge value meaning "no badge". */
    public static final int BADGE_NONE = 0;

    /** Above this the badge reads "9+" rather than stretching the rail. */
    public static final int BADGE_CAP = 9;

    public NavItem {
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(icon, "icon");
        if (routeId.isBlank()) {
            throw new IllegalArgumentException("routeId must not be blank");
        }
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
    }

    /** @return an item with no badge. */
    public static NavItem of(String routeId, String label, String icon) {
        return new NavItem(routeId, label, icon, BADGE_NONE);
    }

    /** @return this item with a numeric badge. */
    public NavItem withBadge(int count) {
        return new NavItem(routeId, label, icon, count);
    }

    /** @return this item with a dot badge (something new, count irrelevant). */
    public NavItem withDot() {
        return new NavItem(routeId, label, icon, BADGE_DOT);
    }

    /** @return {@code true} when any badge should be drawn. */
    public boolean hasBadge() {
        return badge != BADGE_NONE;
    }

    /** @return {@code true} when the badge is a bare dot rather than a number. */
    public boolean isDotBadge() {
        return badge < 0;
    }

    /**
     * @return the badge text: {@code ""} for none or dot, the number up to
     *         {@link #BADGE_CAP}, and {@code "9+"} beyond it
     */
    public String badgeText() {
        if (badge <= 0) {
            return "";
        }
        return badge > BADGE_CAP ? BADGE_CAP + "+" : Integer.toString(badge);
    }
}
