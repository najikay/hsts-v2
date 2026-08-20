package server.features.auth;

import common.dto.auth.CourseRef;
import common.dto.auth.Role;

import java.util.List;
import java.util.Objects;

/**
 * One user as the authentication feature needs it (Logic tier, E5.1).
 *
 * <p>Deliberately <b>not</b> a JPA entity and not a DTO: it is the shape
 * {@link AuthService} needs to answer "is this password right, and what does the
 * shell have to know afterwards?", and nothing more. That is what lets the
 * service be written and fully tested before the database exists (E2) — and what
 * keeps the eventual entity free to look however Hibernate wants it to.
 *
 * <p>The {@code passwordHash} never leaves the server: {@code AuthService} maps
 * this record to a {@link common.dto.auth.LoginResult} for the wire, and that DTO
 * has no hash field at all (X-SEC).
 *
 * @param id           internal user id (the value {@code SessionManager} binds to the socket)
 * @param username     login name, unique, compared case-insensitively
 * @param passwordHash BCrypt hash — never plaintext, never reversible (F1.1, S-38)
 * @param displayName  full name for the avatar chip and the dashboard greeting
 * @param role         the single role driving the shell layout (T-1)
 * @param courses      every course the user belongs to: taught AND enrolled, as a
 *                     union — a teacher enrolled in a colleague's course carries
 *                     both, and the shell's nav shows both (settled in the E2 PR 2b
 *                     review; RepositoryUserDirectory returns the union);
 *                     never {@code null}, defensively copied
 */
public record UserRecord(long id,
                         String username,
                         String passwordHash,
                         String displayName,
                         Role role,
                         List<CourseRef> courses) {

    public UserRecord {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(passwordHash, "passwordHash");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(role, "role");
        courses = courses == null ? List.of() : List.copyOf(courses);
    }

    /** Keeps the hash out of every log line this record could ever land in. */
    @Override
    public String toString() {
        return "UserRecord{id=" + id + ", username=" + username
                + ", role=" + role + ", courses=" + courses.size() + ", hash=***}";
    }
}
