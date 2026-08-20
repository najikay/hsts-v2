package common.dto.exam;

import java.io.Serializable;
import java.time.Duration;

/**
 * A teacher just added time, and the student is about to be told (Common tier, E11.1 —
 * F7.1 ⚑).
 *
 * <p>The {@code PUSH_TIMER_EXTENDED} payload, and the data behind the designed moment PRD
 * §4 calls <i>Time Extended</i>: the timer chip flashes green with a glow pulse, a
 * floating "+15:00" rises off it, and a toast says who did it and when the exam now ends.
 *
 * <p><b>Time added is never silent.</b> That is a product rule, not a nicety: a countdown
 * that grows without explanation reads as a bug to a student under pressure, and one that
 * grows without her noticing wastes the minutes she was given. So this record carries the
 * three things the sentence needs — {@link #teacherName}, {@link #extraMinutes} and, in
 * {@link #timing}, the new end time — and none of them is optional.
 *
 * @param executionId  the execution that was extended (S-20: never the stored exam)
 * @param examName     what she is sitting, for the toast
 * @param teacherName  who granted it, by display name
 * @param extraMinutes minutes added by this one grant, always positive
 * @param timing       the corrected clock: new deadline, new total, server's own now
 */
public record TimerExtended(long executionId,
                            String examName,
                            String teacherName,
                            int extraMinutes,
                            AttemptTiming timing) implements Serializable {

    private static final long serialVersionUID = 1L;

    public TimerExtended {
        examName = examName == null ? "" : examName;
        teacherName = teacherName == null || teacherName.isBlank() ? "Your teacher" : teacherName;
    }

    /** @return the added time, for the floating "+mm:ss" that rises off the chip. */
    public Duration gained() {
        return Duration.ofMinutes(extraMinutes);
    }
}
