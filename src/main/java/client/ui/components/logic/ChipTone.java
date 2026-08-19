package client.ui.components.logic;

/**
 * The visual tone of a status chip (Presentation tier, E4.15).
 *
 * <p>Domain states across the app — exam versions, executions, attempts, grades —
 * collapse onto this handful of tones, so "approved" reads the same green
 * wherever it appears and a reader learns the colour language once.
 * {@link #styleClass()} is the class name in {@code hsts.css}; nothing outside
 * that stylesheet knows the actual colours.
 */
public enum ChipTone {

    /** Inert / not-yet-started states: DRAFT, SCHEDULED, CANCELLED. */
    NEUTRAL("neutral"),

    /** Informational, accent-tinted: IN_PROGRESS, PENDING. */
    INFO("info"),

    /** Positive terminal states: APPROVED, SUBMITTED, PASSED. */
    OK("ok"),

    /** Needs attention but is not a failure: awaiting approval, low time. */
    WARN("warn"),

    /** Negative terminal states: REJECTED, TIMED_OUT, FAILED. */
    DANGER("danger"),

    /** Happening right now — solid fill plus a pulsing dot, readable across a room. */
    LIVE("live");

    private final String styleClass;

    ChipTone(String styleClass) {
        this.styleClass = styleClass;
    }

    /** @return the {@code hsts.css} modifier class for this tone. */
    public String styleClass() {
        return styleClass;
    }
}
