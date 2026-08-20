package common.dto.exam;

import java.io.Serializable;

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
 */
public record ExamHeader(long executionId,
                         String examName,
                         String courseCode,
                         String courseName,
                         int durationMinutes,
                         String generalText,
                         int questionCount,
                         AttemptState attemptState) implements Serializable {

    private static final long serialVersionUID = 1L;

    public ExamHeader {
        examName = examName == null ? "" : examName;
        courseCode = courseCode == null ? "" : courseCode;
        courseName = courseName == null ? "" : courseName;
        // An exam may carry instructions for neither audience (the columns are nullable by
        // decision), and a screen should render an absent block, not the word "null".
        generalText = generalText == null ? "" : generalText;
        attemptState = attemptState == null ? AttemptState.NOT_STARTED : attemptState;
    }

    /** @return {@code true} when this exam has instructions worth rendering a block for. */
    public boolean hasGeneralText() {
        return !generalText.isBlank();
    }

    /** @return the course line the screens show, "21 · Java Programming". */
    public String courseLabel() {
        return courseName.isBlank() ? courseCode : courseCode + " · " + courseName;
    }
}
