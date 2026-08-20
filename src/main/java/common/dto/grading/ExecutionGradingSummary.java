package common.dto.grading;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * One execution as it appears in a teacher's grading queue (Common tier, E12.1).
 *
 * <p>Everything a teacher needs to decide what to open next, and nothing that would need a
 * second round trip: which exam, in which course, which run of it, when it closed, and how
 * far the marking has got. The three counts are what turn the queue into a progress list
 * rather than a list of links — {@code participants} minus {@code gradedCount} is what is
 * still being computed, {@code gradedCount} minus {@code approvedCount} is what is waiting
 * for this teacher.
 *
 * <p>The same summary is echoed back inside {@link ExecutionGrades}, so opening an execution
 * does not lose the header the teacher just clicked.
 *
 * @param executionId   the {@code exam_executions} row
 * @param examName      the exam's name, for the queue row
 * @param courseCode    the course this execution belongs to
 * @param code4         the four-digit execution code students used to enter it
 * @param closedAt      when the execution closed, UTC (ADR-010)
 * @param participants  how many students sat it
 * @param gradedCount   how many of them have a grade row at all
 * @param approvedCount how many of those grades are {@link GradeState#APPROVED}
 */
public record ExecutionGradingSummary(long executionId,
                                      String examName,
                                      String courseCode,
                                      String code4,
                                      Instant closedAt,
                                      int participants,
                                      int gradedCount,
                                      int approvedCount) implements Serializable {

    private static final long serialVersionUID = 1L;

    public ExecutionGradingSummary {
        Objects.requireNonNull(examName, "examName");
        Objects.requireNonNull(courseCode, "courseCode");
        Objects.requireNonNull(code4, "code4");
    }

    /** @return {@code true} when every grade of this execution has been approved (E12.4). */
    public boolean isFullyApproved() {
        return participants > 0 && approvedCount == participants;
    }
}
