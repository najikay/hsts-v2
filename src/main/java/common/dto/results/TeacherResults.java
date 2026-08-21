package common.dto.results;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * The answer to {@code RESULTS_EXAMS_GET}: everything the caller wrote (Common tier, E14.1).
 *
 * <p>One list, no pagination — school-sized data (§6), same decision the grading queue made.
 * The request payload is {@code null} on purpose: <b>which</b> exams these are is resolved
 * from the authenticated session, never from a field a client could set (P-5). There is
 * therefore no way to phrase "show me somebody else's results" in this protocol.
 *
 * @param exams her exams, by display id; never null, possibly empty
 */
public record TeacherResults(List<ExamResultRow> exams) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** A teacher who has written nothing yet. Shared so no handler builds a second one. */
    public static final TeacherResults EMPTY = new TeacherResults(List.of());

    public TeacherResults {
        exams = List.copyOf(Objects.requireNonNull(exams, "exams"));
    }

    /** @return {@code true} when this teacher has authored no exams at all. */
    public boolean isEmpty() {
        return exams.isEmpty();
    }

    /** @return how many sittings there are across every exam, for the screen's summary line. */
    public int totalExecutions() {
        int total = 0;
        for (ExamResultRow exam : exams) {
            total += exam.executionCount();
        }
        return total;
    }
}
