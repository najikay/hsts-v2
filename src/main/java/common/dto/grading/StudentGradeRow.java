package common.dto.grading;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * One student's grade, as every grading screen needs it (Common tier, E12/E13).
 *
 * <p>The single row shape of the whole contract: the teacher's execution table, the header of
 * a review, the student's own list and the header of a checked form are all built from this
 * record. One shape means one place a grade is serialized and one set of rules about what it
 * may say.
 *
 * <h2>The effective score is carried, not derived</h2>
 *
 * <p>{@code effectiveScore} is {@code finalScore != null ? finalScore : autoScore}, computed
 * once by the server. It is on the wire explicitly because three screens would otherwise
 * re-derive it, and the first one that got the null check backwards would show a student the
 * machine's score after a teacher had corrected it.
 *
 * <h2>What is null, and when</h2>
 *
 * <ul>
 *   <li>{@code finalScore} — null until a teacher overrides. Its presence is what
 *       {@code effectiveScore} switches on.</li>
 *   <li>{@code overrideReason} — null unless a teacher overrode the score, and
 *       <b>always</b> null on the student wire: the justification is teacher and audit
 *       material, and {@link MyGrades} strips it structurally. The student sees
 *       {@code teacherComment}.</li>
 *   <li>{@code teacherComment} — optional, and the only free text a student reads.</li>
 *   <li>{@code approvedAt} — null while {@link GradeState#AUTO}; UTC (ADR-010) once
 *       approved.</li>
 *   <li>{@code examName} / {@code courseCode} — see the amendment below.</li>
 * </ul>
 *
 * <h2>Amendment v1.1 — {@code examName} and {@code courseCode}</h2>
 *
 * <p>Additive, agreed 2026-08-20 out of the E13 review, and asymmetric on purpose. They are
 * <b>populated on the student paths</b> ({@code MY_GRADES_GET}, {@code CHECKED_FORM_GET}),
 * where every row in the list is a different exam and T-9.1 needs the label to mean anything,
 * and left <b>null on the teacher paths</b>, where {@link ExecutionGradingSummary} already
 * carries them once for the whole execution and repeating them per row would be the same two
 * strings thirty times over.
 *
 * <p>The ten-component constructor is retained for the teacher-path callers written against
 * v1.0 — it delegates with both fields null, which is exactly what those paths want. Retaining
 * it is not politeness to old code: it means a teacher path cannot start populating per-row
 * exam labels without somebody deliberately changing the call.
 *
 * <p>Range validation (scores 0..100) is not done here — see the package javadoc: it belongs
 * to E12's handlers, which can answer {@code VALIDATION} with a sentence instead of throwing
 * inside a socket read thread.
 *
 * @param gradeId        the {@code grades} row id, the handle every grading verb takes
 * @param studentId      the student this grade belongs to; never trusted from a client
 *                       payload, always resolved server-side
 * @param studentName    full name, for the teacher's table
 * @param autoScore      what the machine computed, kept even after an override so the change
 *                       stays visible
 * @param finalScore     the teacher's score, or {@code null} while none was set
 * @param effectiveScore the score that counts, computed by the server
 * @param state          {@code AUTO} or {@code APPROVED}; never {@code null}
 * @param overrideReason why the teacher changed it, or {@code null}; never on the student wire
 * @param teacherComment optional note for the student, or {@code null}
 * @param approvedAt     when it was approved, UTC, or {@code null} while unapproved
 * @param examName       the exam this grade is for, or {@code null} on the teacher paths (v1.1)
 * @param courseCode     its 2-character course code, or {@code null} on the teacher paths (v1.1)
 */
public record StudentGradeRow(long gradeId,
                              long studentId,
                              String studentName,
                              int autoScore,
                              Integer finalScore,
                              int effectiveScore,
                              GradeState state,
                              String overrideReason,
                              String teacherComment,
                              Instant approvedAt,
                              String examName,
                              String courseCode) implements Serializable {

    private static final long serialVersionUID = 2L;

    public StudentGradeRow {
        Objects.requireNonNull(studentName, "studentName");
        Objects.requireNonNull(state, "state");
    }

    /**
     * The v1.0 shape: a row with no exam label, which is what every teacher path wants.
     *
     * @param gradeId        the {@code grades} row id
     * @param studentId      whose grade it is
     * @param studentName    full name, for the teacher's table
     * @param autoScore      what the machine computed
     * @param finalScore     the teacher's score, or {@code null}
     * @param effectiveScore the score that counts
     * @param state          {@code AUTO} or {@code APPROVED}
     * @param overrideReason why the teacher changed it, or {@code null}
     * @param teacherComment optional note for the student, or {@code null}
     * @param approvedAt     when it was approved, or {@code null}
     */
    public StudentGradeRow(long gradeId,
                           long studentId,
                           String studentName,
                           int autoScore,
                           Integer finalScore,
                           int effectiveScore,
                           GradeState state,
                           String overrideReason,
                           String teacherComment,
                           Instant approvedAt) {
        this(gradeId, studentId, studentName, autoScore, finalScore, effectiveScore, state,
                overrideReason, teacherComment, approvedAt, null, null);
    }

    /**
     * The same row with {@code overrideReason} removed, for the student wire.
     *
     * <p>Both student-facing containers ({@link MyGrades} and {@link CheckedForm}) apply this
     * in their compact constructors, so the "no justification reaches a student" rule is
     * structural in every path rather than a rule two handlers each have to remember.
     *
     * @return this row when it carries no justification, otherwise a stripped copy
     */
    public StudentGradeRow withoutJustification() {
        if (overrideReason == null) {
            return this;
        }
        return new StudentGradeRow(gradeId, studentId, studentName, autoScore, finalScore,
                effectiveScore, state, null, teacherComment, approvedAt, examName, courseCode);
    }

    /**
     * The same row labelled with the exam it belongs to (v1.1, student paths only).
     *
     * <p>A copy rather than a setter because the record is the wire type, and immutability is
     * what makes it safe to hand one instance to both a push and a response. The labels are
     * applied by the caller that already knows the execution, which is the only place that
     * knows them without a second read.
     *
     * @param examName   the exam's name as its author wrote it
     * @param courseCode its 2-character course code
     * @return a copy carrying both labels
     */
    public StudentGradeRow withExam(String examName, String courseCode) {
        return new StudentGradeRow(gradeId, studentId, studentName, autoScore, finalScore,
                effectiveScore, state, overrideReason, teacherComment, approvedAt,
                examName, courseCode);
    }
}
