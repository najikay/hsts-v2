package common.dto.approval;

import common.dto.exam.ExamQuestion;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * The answer to {@code EXAM_PREVIEW_GET}: an exam version as a student will receive it, plus
 * the staff-only material (Common tier, E8.4 ⚑ — F4.1).
 *
 * <h2>This type is the v1 fix</h2>
 *
 * <p>v1's coordinator could not see the exam she was approving. The fix is not "a preview
 * screen"; a screen that renders its own idea of a paper would drift from the real one the
 * first time E10 changed a layout, and the drift would be invisible until a demo. The fix is
 * that {@link #questions} is {@code List<ExamQuestion>} — <b>the student's own wire type</b>,
 * carried here unchanged, produced by the same no-correctness projection
 * ({@code QuestionRepository.findForTakeExam}) that builds a real attempt. The coordinator's
 * screen then renders it with the same component the take-exam screen uses. "She sees exactly
 * what the student sees" is therefore a fact about the types, not a promise about the CSS.
 *
 * <p>{@link #studentText} is on the same footing: it is the general text F3.1 shows an
 * examinee, and it lives out here with the questions rather than in the teacher block,
 * because a student sees it.
 *
 * <h2>And the staff-only half</h2>
 *
 * <p>Everything a student must not see is fenced into {@link #teacherOnly} — the teacher
 * notes, the author's name and the answer key. Two blocks in one message rather than two
 * verbs, because the coordinator needs both at once to make one decision, and because a
 * second verb would be a second guard to keep in step with the first.
 *
 * <p>{@link #summary} carries the row this preview was opened from, refreshed. That is not a
 * convenience: it re-reads {@code state} and {@code lockVersion} at open time, so the
 * approve and reject buttons on the screen send back the version that was actually shown
 * rather than the one a stale list row remembered.
 *
 * @param summary     the version's metadata, re-read now
 * @param studentText the exam's instructions to examinees (F3.1); may be empty
 * @param questions   the paper, in order, in the student's own wire type, with no
 *                    correctness anywhere in it (F6.6)
 * @param teacherOnly the staff-only material: notes, author, answer key
 */
public record ExamPreview(ApprovalRow summary,
                          String studentText,
                          List<ExamQuestion> questions,
                          TeacherOnlyBlock teacherOnly) implements Serializable {

    private static final long serialVersionUID = 1L;

    public ExamPreview {
        Objects.requireNonNull(summary, "summary");
        studentText = studentText == null ? "" : studentText;
        questions = questions == null ? List.of() : List.copyOf(questions);
        teacherOnly = teacherOnly == null
                ? new TeacherOnlyBlock("", "", List.of())
                : teacherOnly;
    }

    /** @return {@code true} when this exam has instructions worth rendering a block for. */
    public boolean hasStudentText() {
        return !studentText.isBlank();
    }

    /** @return the paper's length. */
    public int questionCount() {
        return questions.size();
    }

    /** @return the points the paper adds up to; 100 for any exam E7 let through (S-11). */
    public int totalPoints() {
        return questions.stream().mapToInt(ExamQuestion::points).sum();
    }
}
