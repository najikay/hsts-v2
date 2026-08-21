package server.features.exam;

import common.dto.exam.ExamQuestion;
import server.db.projections.TakeExamQuestion;

import java.util.List;

/**
 * The one mapping from a stored paper to a student's wire (Logic tier, E10.2 ⚑ — F6.6).
 *
 * <p>Both sides of it are types with no field for a correct answer, so this class could not
 * leak one even by trying: {@link TakeExamQuestion} is built by a JPQL constructor expression
 * that never selects {@code correct_answer}, and {@link ExamQuestion} has nowhere to put one.
 * That is the whole v1 fix in one signature.
 *
 * <h2>Why it is a class of its own, and why E8 uses it</h2>
 *
 * <p>It began as a private method on {@link AttemptService}, which was right while exactly
 * one feature served a paper. E8 made that false: F4.1 requires a coordinator to see the exam
 * <b>exactly as a student will</b>, and the honest way to guarantee that is not a second
 * mapper written to the same specification — it is the same mapper. A copy would be
 * indistinguishable from the original on the day it was written and would drift the first
 * time either side changed, which is precisely how v1's coordinator ended up looking at
 * something that was not the exam.
 *
 * <p>So the approval preview and a live attempt are built from the same projection by this
 * method. "She sees what the student sees" is then a fact about the code path rather than a
 * promise about two screens.
 */
public final class ExamPaper {

    private ExamPaper() {
        // static helper — no instances
    }

    /**
     * @param question one question as the no-correctness projection returned it
     * @return the same question on the student's wire
     */
    public static ExamQuestion toWire(TakeExamQuestion question) {
        return new ExamQuestion(question.questionVersionId(), question.displayId(),
                question.ordinal(), question.points(), question.text(),
                question.answer1(), question.answer2(), question.answer3(), question.answer4(),
                question.image());
    }

    /**
     * @param questions a whole paper in exam order; {@code null} is an empty paper
     * @return the same paper on the student's wire, order preserved
     */
    public static List<ExamQuestion> toWire(List<TakeExamQuestion> questions) {
        return questions == null
                ? List.of()
                : questions.stream().map(ExamPaper::toWire).toList();
    }
}
