package common.dto.grading;

import common.dto.exam.AttemptState;

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
 *   <li>{@code teacherName} — <b>never null</b>. Empty when the path does not know a teacher
 *       or the row has gone; see amendment v1.3.</li>
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
 * <h2>Amendment v1.2 — {@code attemptStatus} and {@code actualMinutes} (B-16)</h2>
 *
 * <p>Additive, 2026-08-26. T-10.2 asks the teacher's results table for "score, submitted vs
 * timed out, solving time" and only the score reached the wire — a shape fact, not a null:
 * this record had twelve components and none of them was either. On the seed, {@code
 * omer.katz}'s timed-out 45 read exactly like the seven submitted papers, so the one attempt
 * in the dataset that distinguishes "did not finish" from "did badly" was invisible on the
 * screen built to show it.
 *
 * <p><b>The same two facts the student's checked form already carries</b>, in the same types
 * ({@link AttemptState}, and a boxed {@code Integer} because "not recorded" is a different
 * fact from "took zero minutes"). {@link CheckedForm} shows one student her own paper and this
 * shows a teacher the whole room; carrying them differently would be two answers to one
 * question.
 *
 * <p><b>Populated on the teacher results path</b> ({@code RESULTS_EXECUTION_GET}), where the
 * table renders them as two columns. Null on every other path, and null is honest there: the
 * grading queue and the student list are about grades, {@code GradeRepository.findResultRows}
 * is the only read that joins the attempt, and the twelve-component constructor is retained so
 * every existing caller keeps compiling and keeps meaning what it meant.
 *
 * <h2>Amendment v1.3 — {@code teacherName} (A7)</h2>
 *
 * <p>2026-08-29, out of the lead's manual round. The student's grade cards named the exam, the
 * course, the score and the date, and never said whose exam it was — the same gap A6 closed one
 * screen deeper on {@link CheckedForm}, still open on the list that leads to it. A shape fact,
 * not a null: this record had fourteen components and none of them was a teacher.
 *
 * <p><b>Which teacher.</b> The <b>releasing</b> one — {@code exam_executions.created_by}, the
 * same definition A6 uses — so the list and the paper it opens name the same person. It is
 * deliberately not {@code grades.approved_by}: an approval by a colleague would change the name
 * under an exam title without anything about the paper having changed.
 *
 * <p><b>Never null, empty when unresolvable.</b> The compact constructor normalises null to
 * {@code ""}, so a screen has one absence to test rather than two and the word "null" cannot
 * reach a card. The client drops the line rather than drawing a label with nothing after it.
 *
 * <p><b>Placed after {@code courseCode} rather than appended last</b>, on A6's reasoning: it is
 * a header label and it sits with the other two the screen prints one under the other. Both jars
 * ship from one build, so nothing is bought by putting a label at the end of the record, and a
 * call site that misses the change fails to compile on the arity rather than silently passing a
 * course code as a teacher's name. For the same reason the retained label constructor
 * <b>carries it</b> (thirteen components, below): a path that carries exam labels and no teacher
 * name is now a deliberate call rather than an omission.
 *
 * <p><b>Populated where a teacher is at hand.</b> The student list and the checked form's header
 * resolve it; the grade review carries it because the review already holds the execution. The
 * grading queue, the approval push and the teacher's results table pass {@code ""} — the first
 * two hold no {@code UserRepository} and the third reads through a store, and none of the three
 * screens shows a teacher her own name above her own table.
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
 * @param teacherName    the teacher who released the sitting this grade belongs to, or
 *                       {@code ""} when the path does not know one (v1.3, A7); never
 *                       {@code null}
 * @param attemptStatus  how the attempt ended — {@code SUBMITTED} when she handed in,
 *                       {@code TIMED_OUT} when the server did it for her — or {@code null} on
 *                       every path but the teacher's results table (v1.2)
 * @param actualMinutes  recorded solving time (S-19), or {@code null} when none was recorded
 *                       or the path does not carry it (v1.2)
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
                              String courseCode,
                              String teacherName,
                              AttemptState attemptStatus,
                              Integer actualMinutes) implements Serializable {

    private static final long serialVersionUID = 4L;

    public StudentGradeRow {
        Objects.requireNonNull(studentName, "studentName");
        Objects.requireNonNull(state, "state");
        // A7: one absence, not two. A screen asking "is there a teacher" tests for blank and
        // never for null, and the word "null" cannot reach a card through this record.
        teacherName = teacherName == null ? "" : teacherName;
    }

    /**
     * The labelled shape: a row that says nothing about how the attempt ended (v1.1, plus A7's
     * teacher).
     *
     * <p>Retained for the grading queue, the review header and both student containers, none
     * of which joins the attempt. It delegates with both attempt components null, which is what
     * those paths mean, and keeping it means none of them can start carrying an attempt status
     * without somebody deliberately changing the call.
     *
     * <p>It <b>carries {@code teacherName}</b> rather than defaulting it, which is why A7 is a
     * compile error at every one of these call sites instead of a silent blank on the one screen
     * the amendment exists for. The three labels travel together because they are the three the
     * student's screens print together.
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
     * @param examName       the exam this grade is for, or {@code null}
     * @param courseCode     its 2-character course code, or {@code null}
     * @param teacherName    who released the sitting, or {@code ""} (A7)
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
                           Instant approvedAt,
                           String examName,
                           String courseCode,
                           String teacherName) {
        this(gradeId, studentId, studentName, autoScore, finalScore, effectiveScore, state,
                overrideReason, teacherComment, approvedAt, examName, courseCode, teacherName,
                null, null);
    }

    /**
     * The v1.0 shape: a row with no exam label, which is what every teacher path wants.
     *
     * <p>No labels means no teacher name either, and {@code ""} is the honest value: a row this
     * shape is read under a header that already says whose execution it is. A teacher path that
     * wants to name somebody calls a longer constructor, deliberately (A7).
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
                overrideReason, teacherComment, approvedAt, null, null, "");
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
                effectiveScore, state, null, teacherComment, approvedAt, examName, courseCode,
                teacherName, attemptStatus, actualMinutes);
    }

    /**
     * The same row labelled with the exam it belongs to and the teacher who released it
     * (v1.1, plus A7's name; student paths only).
     *
     * <p>A copy rather than a setter because the record is the wire type, and immutability is
     * what makes it safe to hand one instance to both a push and a response. The labels are
     * applied by the caller that already knows the execution, which is the only place that
     * knows them without a second read.
     *
     * <p>All three labels in one call, and no two-argument overload beside it. An overload that
     * left the name alone would be indistinguishable at the call site from one that blanked it,
     * and the whole of A7 is one screen having been left blank without anybody noticing.
     *
     * @param examName    the exam's name as its author wrote it
     * @param courseCode  its 2-character course code
     * @param teacherName who released the sitting, or {@code ""} when it did not resolve
     * @return a copy carrying all three labels
     */
    public StudentGradeRow withExam(String examName, String courseCode, String teacherName) {
        return new StudentGradeRow(gradeId, studentId, studentName, autoScore, finalScore,
                effectiveScore, state, overrideReason, teacherComment, approvedAt,
                examName, courseCode, teacherName, attemptStatus, actualMinutes);
    }
}
