package common.dto.grading;

import java.io.Serializable;

/**
 * The {@code GRADE_OVERRIDE} payload (Common tier, E12.5 — S-23, S-22).
 *
 * <p>A teacher may move a score, and may never do it silently: {@code justification} is
 * <b>required and non-blank</b>, and it is what becomes {@code overrideReason} on the grade.
 * That is the whole audit trail for a hand-changed mark, which is why it is a field of the
 * request rather than an optional note added afterwards.
 *
 * <h2>The comment rides the adjustment (amendment A3, 2026-08-23)</h2>
 *
 * <p>{@code teacherComment} is the student's half of the same act and is <b>optional</b>. Two
 * different pieces of writing travel together because they are written together: the
 * justification explains the change to the record, the comment explains it to the girl whose
 * paper it is. Only the second one ever reaches her ({@link MyGrades} and {@link CheckedForm}
 * strip the first structurally).
 *
 * <p><b>Blank collapses to {@code null}</b> in the compact constructor, stripped rather than
 * trimmed, so a request built from an untouched text area and one built from a text area
 * holding two spaces are the same request. That matters more here than it does for a code or a
 * name, because null has a meaning the server acts on: see the next paragraph.
 *
 * <p><b>Null does not clear an existing comment.</b> {@code OverrideService} writes the comment
 * only when one was sent, so a teacher correcting a score for the second time does not silently
 * erase what she wrote to the student the first time. There is deliberately no way to clear a
 * comment on this wire; removing one is a v2 shape along with the standalone
 * {@code GRADE_COMMENT_SET} verb the lead declined for v1.
 *
 * <p>The three-component constructor is retained, delegating with no comment. Every call site
 * and test written before the amendment keeps compiling and keeps meaning what it meant — the
 * same move {@code ReleaseCreateRequest} made for its optional code.
 *
 * <h2>What the handler checks, and this record does not</h2>
 *
 * <p>Per the package javadoc, validation lives with E12's handler, not in the compact
 * constructor: a blank justification answers {@code VALIDATION} <em>before anything is
 * read</em>, and a {@code newScore} outside 0..100 answers {@code VALIDATION} too. Both are
 * sentences for a teacher, not exceptions thrown inside a deserialization on a socket read
 * thread — and a record that threw would turn a typo into a dropped connection.
 *
 * <p>The comment adds no third refusal. It has no required shape and, matching
 * {@code justification}, no maximum length on the wire: the column behind both is MySQL
 * {@code TEXT}, and a limit invented here would be a rule the audit trail does not have.
 *
 * <p>The handler also enforces the state rule the contract fixes: an override is allowed only
 * while the grade is {@link GradeState#AUTO}. Overriding an {@code APPROVED} grade answers
 * {@code CONFLICT} — <b>comment included</b>, since the comment travels on the override and is
 * refused with it. Re-opening an approved grade for a second override is a non-goal for v1.
 *
 * @param gradeId        the grade to change
 * @param newScore       the score the teacher wants, 0..100 (checked by the handler)
 * @param justification  why, non-blank (checked by the handler); stored as
 *                       {@code overrideReason} and never sent to the student
 * @param teacherComment the note for the student, or {@code null} when she wrote none. Blank
 *                       is {@code null}; {@code null} leaves any existing comment alone
 */
public record GradeOverrideRequest(long gradeId, int newScore, String justification,
                                   String teacherComment) implements Serializable {

    private static final long serialVersionUID = 2L;

    /** Lowest score the handler accepts (the contract's 0..100 range). */
    public static final int MIN_SCORE = 0;

    /** Highest score the handler accepts (the contract's 0..100 range). */
    public static final int MAX_SCORE = 100;

    /**
     * Normalises the comment on the way in: blank becomes {@code null}, anything else is
     * stripped.
     *
     * <p>Done here rather than at the call sites so that there is exactly one representation of
     * "she wrote nothing", and every later decision — the service's write, the amendment's
     * null-preserves rule — can be a null test.
     */
    public GradeOverrideRequest {
        teacherComment = blankToNull(teacherComment);
    }

    /**
     * The v1 shape: move the score, with the reason, and say nothing to the student.
     *
     * <p>Kept because it is a legitimate call — most overrides carry no comment — and because
     * every construction site written before the amendment keeps compiling.
     *
     * @param gradeId       the grade to change
     * @param newScore      the score the teacher wants
     * @param justification why, non-blank
     */
    public GradeOverrideRequest(long gradeId, int newScore, String justification) {
        this(gradeId, newScore, justification, null);
    }

    /** @return {@code true} when this override also carries something for the student to read. */
    public boolean hasComment() {
        return teacherComment != null;
    }

    /**
     * @param raw text as typed, or {@code null}
     * @return it stripped, or {@code null} when there was nothing but whitespace there.
     *         {@code strip} rather than {@code trim}: the house rule, and the one that knows
     *         about the whitespace a Hebrew keyboard can produce
     */
    private static String blankToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String stripped = raw.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
