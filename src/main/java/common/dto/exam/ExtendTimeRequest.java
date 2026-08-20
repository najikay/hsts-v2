package common.dto.exam;

import java.io.Serializable;

/**
 * "Give everyone sitting this execution another fifteen minutes" (Common tier, E11.1 —
 * F7.1, S-20).
 *
 * <p>The extension applies to the <b>execution</b>, never to the stored exam and never to
 * one student: S-20 is explicit, and modelling it any other way is how a demo exam ends up
 * permanently 15 minutes longer. Every live attempt at this execution gains the same
 * minutes and every deadline is recomputed from it.
 *
 * @param executionId  the live execution to extend
 * @param extraMinutes minutes to add; must be positive (§6: "extend by 0/negative →
 *                     validation")
 */
public record ExtendTimeRequest(long executionId, int extraMinutes) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** A guard against a fat-fingered dialog turning a 45-minute exam into a two-day one. */
    public static final int MAX_MINUTES = 480;

    /** @return {@code true} when this is an amount a teacher may actually grant. */
    public boolean isAmountLegal() {
        return extraMinutes > 0 && extraMinutes <= MAX_MINUTES;
    }
}
