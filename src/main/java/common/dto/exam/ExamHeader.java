package common.dto.exam;

import java.io.Serializable;
import java.time.Instant;

/**
 * What a student is told <em>before</em> she identifies herself (Common tier, E10.9 — F6.1).
 *
 * <p>The answer to {@code EXAM_JOIN}: enough to recognise the exam she is about to sit and
 * to decide whether to go on, and <b>no questions</b>. That split is not cosmetic. The
 * identity check of S-18 is what starts the clock, so the questions must not exist on the
 * client before it: a student who typed a code and then walked away has seen a title, not
 * a paper.
 *
 * <p>{@link #durationMinutes} already includes any extension the teacher has granted to
 * this execution (S-20), because that is the number the student is about to be given. The
 * client never adds the two itself.
 *
 * <h2>Amendment A8 — {@code windowClosesAt} and {@code sittingMinutes} (B-14)</h2>
 *
 * <p>The two components that make the entry screen honest. {@code durationMinutes} is what
 * the paper is worth; {@code sittingMinutes} is what <em>this</em> sitting can actually
 * deliver, which is smaller whenever the execution's window shuts before the allotted time
 * is up. Both are carried rather than one, because the difference between them is the thing
 * the student has to be told: "the paper is 75 minutes and you have 26 of them" is a
 * sentence, and a lone corrected number is a mystery.
 *
 * <p>They are computed against the moment the clock starts — {@code now} on the join
 * answer, the attempt's own {@code startedAt} once it is running — so a header read before
 * and after a start says the same thing about the same sitting.
 *
 * @param executionId        the execution she matched, used by the next two verbs
 * @param examName           the exam's name as the teacher wrote it
 * @param courseCode         the 2-character course code
 * @param courseName         the course's display name, for the header line
 * @param durationMinutes    minutes she will get, extensions included (S-20)
 * @param generalText        the exam's instructions to examinees (F3.1); may be empty
 * @param questionCount      how many questions the paper has
 * @param attemptState       where her own attempt at this execution stands right now, so
 *                           the client knows whether to ask for an id, resume, or say
 *                           "already handed in" (F6.7)
 * @param windowClosesAt     when this execution closes and the server force-submits whoever
 *                           is still working, extensions included; {@code null} only on a
 *                           header built by code that predates the amendment
 * @param sittingMinutes     the minutes she really gets, {@code min(durationMinutes, time
 *                           until windowClosesAt)}. Equal to {@link #durationMinutes}
 *                           whenever the window is wide enough, which is the normal case
 */
public record ExamHeader(long executionId,
                         String examName,
                         String courseCode,
                         String courseName,
                         int durationMinutes,
                         String generalText,
                         int questionCount,
                         AttemptState attemptState,
                         Instant windowClosesAt,
                         int sittingMinutes) implements Serializable {

    private static final long serialVersionUID = 2L;

    public ExamHeader {
        examName = examName == null ? "" : examName;
        courseCode = courseCode == null ? "" : courseCode;
        courseName = courseName == null ? "" : courseName;
        // An exam may carry instructions for neither audience (the columns are nullable by
        // decision), and a screen should render an absent block, not the word "null".
        generalText = generalText == null ? "" : generalText;
        attemptState = attemptState == null ? AttemptState.NOT_STARTED : attemptState;
        // A header from a pre-B-14 caller promises the paper's own length, which is what it
        // always meant. Clamped rather than trusted, so no screen can be handed a negative.
        sittingMinutes = Math.max(0, sittingMinutes);
    }

    /**
     * The pre-B-14 shape: no window, and the sitting is the paper's own length.
     *
     * <p>Retained so every construction site written against v1 keeps compiling and keeps
     * meaning what it meant. A caller that wants the honest figure has to pass it, which is
     * the point: nothing can start promising a truncated sitting by accident.
     *
     * @param executionId     the execution she matched
     * @param examName        the exam's name
     * @param courseCode      the 2-character course code
     * @param courseName      the course's display name
     * @param durationMinutes minutes the paper is worth, extensions included
     * @param generalText     the instructions to examinees
     * @param questionCount   how many questions the paper has
     * @param attemptState    where her own attempt stands
     */
    public ExamHeader(long executionId, String examName, String courseCode, String courseName,
                      int durationMinutes, String generalText, int questionCount,
                      AttemptState attemptState) {
        this(executionId, examName, courseCode, courseName, durationMinutes, generalText,
                questionCount, attemptState, null, durationMinutes);
    }

    /** @return {@code true} when this exam has instructions worth rendering a block for. */
    public boolean hasGeneralText() {
        return !generalText.isBlank();
    }

    /**
     * @return {@code true} when the window closes before the paper's own time is up, so the
     *         entry screen owes her a sentence saying when and how long (B-14 ⚑)
     */
    public boolean isSittingShortened() {
        return windowClosesAt != null && sittingMinutes < durationMinutes;
    }

    /** @return the course line the screens show, "21 · Java Programming". */
    public String courseLabel() {
        return courseName.isBlank() ? courseCode : courseCode + " · " + courseName;
    }
}
