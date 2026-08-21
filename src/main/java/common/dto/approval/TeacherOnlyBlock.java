package common.dto.approval;

import java.io.Serializable;
import java.util.List;

/**
 * Everything in a preview that a student must never see (Common tier, E8.4 — F4.1).
 *
 * <p>F4.1 asks for two things at once and they pull in opposite directions: the coordinator
 * must see the exam <b>exactly as a student will</b>, and she must also see the teacher-only
 * notes and the answer key. Putting the second set inside the student's types would defeat
 * the first; so the preview carries the student's own wire types untouched, and everything
 * staff-only is fenced into this one object beside them.
 *
 * <p>That fence is the reviewable line. A field added here is teacher-only by construction
 * and a reviewer can see which side of the wall it landed on; a field added to
 * {@code common.dto.exam.ExamQuestion} instead trips {@code ExamWireLeakGuardTest} and fails
 * the build. There is no third place for it to go.
 *
 * @param teacherText  the exam's instructions to whoever administers or marks it (F3.1);
 *                     empty when the author wrote none
 * @param authorName   who wrote this version, shown so the coordinator knows whom to talk to
 * @param answerKey    which option is right for each question, in exam order
 */
public record TeacherOnlyBlock(String teacherText, String authorName,
                               List<PreviewAnswerRow> answerKey) implements Serializable {

    private static final long serialVersionUID = 1L;

    public TeacherOnlyBlock {
        // The columns are nullable by decision (lead, E2 PR 1 review round 2): an exam may
        // carry instructions for neither audience, and a screen renders an absent block.
        teacherText = teacherText == null ? "" : teacherText;
        authorName = authorName == null ? "" : authorName;
        answerKey = answerKey == null ? List.of() : List.copyOf(answerKey);
    }

    /** @return {@code true} when there are notes worth rendering a block for. */
    public boolean hasTeacherText() {
        return !teacherText.isBlank();
    }

    /**
     * @param questionVersionId a question on the paper
     * @return the option marked correct for it, or {@code 0} when this preview carries no key
     *         for that question — which a panel renders as "not available", never as option 0
     */
    public int correctOptionOf(long questionVersionId) {
        for (PreviewAnswerRow row : answerKey) {
            if (row.questionVersionId() == questionVersionId) {
                return row.correctOption();
            }
        }
        return 0;
    }
}
