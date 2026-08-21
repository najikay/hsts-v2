package common.dto.results;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * One exam the calling teacher wrote, with every sitting of it (Common tier, E14.1 — S-35).
 *
 * <p>The unit of F9.2's first sentence: "results for all exams <b>she wrote</b>, even executed
 * by others". Scope is the exam's recorded author, resolved server-side from the {@code exams}
 * row, so an exam a colleague released still appears here and an exam somebody else wrote
 * never does — whatever the client asks for.
 *
 * <p>An exam that has never been released carries an <b>empty</b> execution list rather than
 * being dropped. A teacher looking for results of something she wrote last week deserves to
 * find it and be told it has not been run, instead of wondering whether the screen is broken.
 *
 * @param examId     the {@code exams} row
 * @param displayId  the six-digit display id, subject(2) + course(2) + serial(2) (S-10)
 * @param examName   the exam's name, from its most recent version
 * @param courseCode the two-character course code
 * @param courseName the course's display name
 * @param executions every non-cancelled sitting, most recently opened first; never null
 */
public record ExamResultRow(long examId,
                            String displayId,
                            String examName,
                            String courseCode,
                            String courseName,
                            List<ExecutionResultRow> executions) implements Serializable {

    private static final long serialVersionUID = 1L;

    public ExamResultRow {
        Objects.requireNonNull(displayId, "displayId");
        Objects.requireNonNull(examName, "examName");
        Objects.requireNonNull(courseCode, "courseCode");
        Objects.requireNonNull(courseName, "courseName");
        executions = List.copyOf(Objects.requireNonNull(executions, "executions"));
    }

    /** @return {@code true} when this exam has never been taken out of the drawer (S-2). */
    public boolean neverReleased() {
        return executions.isEmpty();
    }

    /** @return how many times it has been sat. */
    public int executionCount() {
        return executions.size();
    }
}
