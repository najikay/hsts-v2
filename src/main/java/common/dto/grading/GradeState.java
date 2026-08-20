package common.dto.grading;

/**
 * Where a grade sits on the wire (Common tier, E12 — F8, C-3).
 *
 * <p>Mirrors the stored {@code server.db.entities.GradeStatus} one to one, and stays a
 * separate type for the reason every DTO in {@code common} does: the Common JAR ships to the
 * client and must not carry an entity enum whose values are a database concern.
 *
 * <p><b>"Overridden" is not a state.</b> A teacher who changes a score is still looking at an
 * {@link #AUTO} grade until it is approved; what makes it overridden is
 * {@code overrideReason != null} on the row. Modelling it as a third constant would let a row
 * be {@code OVERRIDDEN} with no justification, or {@code APPROVED} while hiding the fact that
 * a human moved the number, and every client would then have two fields to reconcile instead
 * of one to read.
 *
 * <p>Constants are serialized by name (Java serializes enums by name, never by ordinal), so
 * they may be added and must never be renamed.
 */
public enum GradeState {

    /** Scored by the machine, invisible to the student (S-24). */
    AUTO,

    /** Released by a teacher — the point at which the student may see it (C-3). */
    APPROVED
}
