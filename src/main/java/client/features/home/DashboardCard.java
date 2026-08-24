package client.features.home;

import java.util.Objects;

/**
 * One card on a role dashboard, as a value (Presentation tier, UI wave 1 — F-10).
 *
 * <p>The dashboards' sessions answer in these rather than poking at nodes, which
 * is what keeps them FX-free and therefore testable with no toolkit booted
 * (TEAM_SPLIT §3.2). A test asserts on the card a session produced; the view is
 * then a loop that turns cards into nodes and has nothing left to get wrong.
 *
 * @param title    the card's heading
 * @param value    the big line: a count, a score, or one of
 *                 {@link DashboardCopy#LOADING} / {@link DashboardCopy#UNAVAILABLE}
 * @param hint     the line under the value, already chosen between the normal
 *                 hint and the empty-state sentence
 * @param routeId  where clicking the card goes
 * @param state    whether this card is waiting, has something, has nothing, or failed
 */
public record DashboardCard(String title, String value, String hint, String routeId,
                            State state) {

    /**
     * The four situations a card can be in.
     *
     * <p>{@link #EMPTY} and {@link #FAILED} are kept apart deliberately. Both show
     * no number, and collapsing them is how a dashboard tells a coordinator her
     * approval queue is empty when the server merely could not be reached.
     */
    public enum State {
        /** The answer is still in flight. */
        LOADING,
        /** There is something to show. */
        READY,
        /** The read succeeded and the answer was nothing. */
        EMPTY,
        /** The read failed. Never rendered as a zero. */
        FAILED
    }

    public DashboardCard {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(hint, "hint");
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(state, "state");
    }

    /** A card whose answer has not arrived. */
    public static DashboardCard loading(String title, String hint, String routeId) {
        return new DashboardCard(title, DashboardCopy.LOADING, hint, routeId, State.LOADING);
    }

    /** A card whose read failed: no number, and a line saying so. */
    public static DashboardCard failed(String title, String routeId) {
        return new DashboardCard(title, DashboardCopy.UNAVAILABLE, DashboardCopy.LOAD_FAILED,
                routeId, State.FAILED);
    }

    /**
     * A counting card, which picks its own state from the count.
     *
     * <p>The value is the bare number and the sentence goes underneath, which is
     * the shape every other stat card in the app already uses. A zero is still
     * printed rather than blanked: "0" with a line saying when it will not be
     * zero is information, and an empty box is not.
     *
     * @param emptyHint what to say instead of {@code hint} when the count is zero
     */
    public static DashboardCard counted(String title, int count, String hint, String emptyHint,
                                        String routeId) {
        boolean nothing = count <= 0;
        return new DashboardCard(title, Integer.toString(Math.max(count, 0)),
                nothing ? emptyHint : hint, routeId,
                nothing ? State.EMPTY : State.READY);
    }
}
