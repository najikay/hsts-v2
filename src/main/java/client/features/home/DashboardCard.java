package client.features.home;

import client.ui.components.logic.ChipSpec;
import client.ui.components.logic.ChipTone;

import java.util.Objects;
import java.util.Optional;

/**
 * One card on a role dashboard, as a value (Presentation tier, UI wave 1 — F-10;
 * restyled in UI wave 2).
 *
 * <p>The dashboards' sessions answer in these rather than poking at nodes, which
 * is what keeps them FX-free and therefore testable with no toolkit booted
 * (TEAM_SPLIT §3.2). A test asserts on the card a session produced; the view is
 * then a loop that turns cards into nodes and has nothing left to get wrong.
 *
 * <h2>What wave 2 added, and why it is here rather than in the view</h2>
 *
 * <p>The remodel gives every card a <b>kicker</b> (the small uppercase label
 * above the number), an optional <b>chip</b>, and a <b>link line</b> naming the
 * screen it opens. All three are content, not styling: what a card's kicker says
 * and whether it carries a "1 live" chip are decisions made from the data the
 * session read. Putting them in the view would have moved a third of the
 * dashboard's decisions into the one class on the coverage exclusion list.
 *
 * @param kicker   the 11.5px uppercase label above the value ("LIVE NOW")
 * @param title    the card's heading
 * @param value    the big line: a count, a score, or one of
 *                 {@link DashboardCopy#LOADING} / {@link DashboardCopy#UNAVAILABLE}
 * @param hint     the line under the value, already chosen between the normal
 *                 hint and the empty-state sentence
 * @param linkText the accent line at the bottom ("Open releases"); the arrow is
 *                 the view's, because an arrow is a glyph and not a sentence
 * @param chip     the status pill, or {@code null} when the card has no state
 *                 worth naming
 * @param routeId  where clicking the card goes
 * @param state    whether this card is waiting, has something, has nothing, or failed
 */
public record DashboardCard(String kicker, String title, String value, String hint,
                            String linkText, ChipSpec chip, String routeId, State state) {

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
        Objects.requireNonNull(kicker, "kicker");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(hint, "hint");
        Objects.requireNonNull(linkText, "linkText");
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(state, "state");
    }

    /** @return the chip, when this card has one. */
    public Optional<ChipSpec> statusChip() {
        return Optional.ofNullable(chip);
    }

    /** @return a copy of this card carrying {@code spec} as its chip. */
    public DashboardCard withChip(ChipSpec spec) {
        return new DashboardCard(kicker, title, value, hint, linkText, spec, routeId, state);
    }

    /**
     * A card whose answer has not arrived.
     *
     * <p>No chip: a card that is still loading has no state to name, and a
     * "loading" pill would be a second spinner next to the first.
     */
    public static DashboardCard loading(String kicker, String title, String hint, String linkText,
                                        String routeId) {
        return new DashboardCard(kicker, title, DashboardCopy.LOADING, hint, linkText, null,
                routeId, State.LOADING);
    }

    /**
     * A card whose read failed: no number, and a line saying so.
     *
     * <p>The chip is {@link ChipTone#DANGER} rather than absent, because the
     * whole point of the state is that it is visibly different from a zero, and
     * on a restyled card the number is no longer the only thing carrying that.
     */
    public static DashboardCard failed(String kicker, String title, String linkText,
                                       String routeId) {
        return new DashboardCard(kicker, title, DashboardCopy.UNAVAILABLE,
                DashboardCopy.LOAD_FAILED, linkText,
                ChipSpec.of(DashboardCopy.CHIP_OFFLINE, ChipTone.DANGER), routeId, State.FAILED);
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
    public static DashboardCard counted(String kicker, String title, int count, String hint,
                                        String emptyHint, String linkText, String routeId) {
        boolean nothing = count <= 0;
        return new DashboardCard(kicker, title, Integer.toString(Math.max(count, 0)),
                nothing ? emptyHint : hint, linkText, null, routeId,
                nothing ? State.EMPTY : State.READY);
    }
}
