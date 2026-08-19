package server.db.entities;

/**
 * Where a grade sits in the two-stage flow of C-3 — {@code grades.status} (V5, §5, F8).
 *
 * <p>The order is fixed and defence-relevant: the machine scores first, a teacher
 * approves second, and only then does the student see anything (S-24). A grade that is
 * still {@link #AUTO} must never reach a student DTO.
 */
public enum GradeStatus {

    /** Computed by {@code GradingService.autoGrade}; invisible to the student. */
    AUTO,

    /** Released by a teacher — the point at which the student may see it (C-3). */
    APPROVED
}
