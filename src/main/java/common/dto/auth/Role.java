package common.dto.auth;

/**
 * The four HSTS user roles (Common tier, PRD §3 T-1).
 *
 * <p>A user has exactly one role; it is decided at seed time (S-4: user
 * management is external to the app) and travels to the client inside
 * {@link LoginResult}. The server never trusts the client's copy — every guard
 * in {@code server.core.Authorization} reads the role from the session bound to
 * the connection.
 */
public enum Role {

    /** Takes exams, sees own grades, talks to the course bot. */
    STUDENT,

    /** Authors questions/exams, releases executions, approves grades for own courses. */
    TEACHER,

    /** A teacher who additionally coordinates one subject: approves that subject's exams. */
    COORDINATOR,

    /** School-wide read access: reports and statistics across all subjects. */
    PRINCIPAL
}
