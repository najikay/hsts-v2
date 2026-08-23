package server.db.projections;

/**
 * A user as a picker needs to name her (E15.3).
 *
 * <p>Reference data only, and deliberately three columns of a {@code users} row rather than the
 * entity: a report's subject picker needs a name to print and a handle to tell two people with
 * the same name apart, and it has no business holding a password hash while it does that.
 *
 * <p>The sibling of {@link CourseSummary} one table over, and it exists for the same reason:
 * {@code server.db} does not depend on the wire package, so the mapping onto
 * {@code common.dto.report.ReportSubject} happens in the feature that answers the verb.
 *
 * @param userId    the user's id
 * @param fullName  her display name
 * @param username  her login name, which is unique and is what distinguishes two people who
 *                  share a display name
 */
public record PersonRef(long userId, String fullName, String username) {
}
