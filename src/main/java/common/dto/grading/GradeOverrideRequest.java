package common.dto.grading;

import java.io.Serializable;

/**
 * The {@code GRADE_OVERRIDE} payload (Common tier, E12.5 — S-23).
 *
 * <p>A teacher may move a score, and may never do it silently: {@code justification} is
 * <b>required and non-blank</b>, and it is what becomes {@code overrideReason} on the grade.
 * That is the whole audit trail for a hand-changed mark, which is why it is a field of the
 * request rather than an optional note added afterwards.
 *
 * <h2>What the handler checks, and this record does not</h2>
 *
 * <p>Per the package javadoc, validation lives with E12's handler, not in the compact
 * constructor: a blank justification answers {@code VALIDATION} <em>before anything is
 * read</em>, and a {@code newScore} outside 0..100 answers {@code VALIDATION} too. Both are
 * sentences for a teacher, not exceptions thrown inside a deserialization on a socket read
 * thread — and a record that threw would turn a typo into a dropped connection.
 *
 * <p>The handler also enforces the state rule the contract fixes: an override is allowed only
 * while the grade is {@link GradeState#AUTO}. Overriding an {@code APPROVED} grade answers
 * {@code CONFLICT}; re-opening an approved grade for a second override is a non-goal for v1.
 *
 * @param gradeId       the grade to change
 * @param newScore      the score the teacher wants, 0..100 (checked by the handler)
 * @param justification why, non-blank (checked by the handler); stored as
 *                      {@code overrideReason} and never sent to the student
 */
public record GradeOverrideRequest(long gradeId, int newScore, String justification) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Lowest score the handler accepts (the contract's 0..100 range). */
    public static final int MIN_SCORE = 0;

    /** Highest score the handler accepts (the contract's 0..100 range). */
    public static final int MAX_SCORE = 100;
}
