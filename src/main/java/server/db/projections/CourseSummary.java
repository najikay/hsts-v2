package server.db.projections;

/**
 * A course as menus and course pickers need it (E2.11).
 *
 * <p>Reference data only. Maps to {@code common.dto.auth.CourseRef} in the adapter that
 * builds a login result; kept separate so {@code server.db} does not depend on the wire
 * package.
 *
 * @param code the 2-character course code
 * @param name the display name
 */
public record CourseSummary(String code, String name) {
}
