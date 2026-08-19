package server.db.entities;

/**
 * The role as the database stores it — three values, matching {@code users.role}
 * in {@code V1__core.sql} and ARCHITECTURE §5.
 *
 * <p><b>Deliberately not {@link common.dto.auth.Role}, which has four.</b> The wire
 * role carries {@code COORDINATOR}; the column does not, because coordination is a
 * <em>relationship</em> rather than a rank: §5 models it as a row in
 * {@code coordinators}, which is what lets one teacher coordinate Mathematics while
 * merely teaching Computer Science, and what makes S-1's "one coordinator per
 * subject" enforceable by a primary key.
 *
 * <p>Confirmed by the lead in the E2 PR 1 review: the stored role stays three values
 * and {@code COORDINATOR} is derived at login. The derivation lives in exactly one
 * place — {@code server.db.repos.RepositoryUserDirectory} — so nothing else in the
 * system has to remember that the two vocabularies differ.
 */
public enum UserRole {

    /** Takes exams, sees own grades, uses the course bot. */
    STUDENT,

    /**
     * Authors questions and exams, releases executions, grades submissions.
     * A teacher with a {@code coordinators} row reaches the client as
     * {@code Role.COORDINATOR}; the stored value is still {@code TEACHER}.
     */
    TEACHER,

    /** School-wide read access (S-7). */
    PRINCIPAL
}
