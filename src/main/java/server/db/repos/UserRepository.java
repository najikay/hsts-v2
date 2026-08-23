package server.db.repos;

import org.hibernate.Session;
import server.db.entities.User;
import server.db.entities.UserRole;
import server.db.projections.PersonRef;

import java.util.List;
import java.util.Optional;

/**
 * Reads over {@code users} and the membership tables (E2.11).
 */
public final class UserRepository {

    /**
     * Finds a user by login name, compared case-insensitively.
     *
     * <p>Case-insensitivity is required by {@code UserDirectory}: the login throttle keys on
     * the normalised name, so a directory that compared case-sensitively would let
     * {@code DANA.COHEN} walk past a lockout on {@code dana.cohen}. Production collation
     * ({@code utf8mb4_unicode_ci}) already compares this way, but H2 does not reproduce it,
     * so the query lowercases both sides rather than trusting the engine underneath.
     *
     * <p>Consumers: E5 login through {@code RepositoryUserDirectory}; the E2.15 seed loader.
     *
     * @param session  the current session
     * @param username the login name as typed
     * @return the user, or empty when there is none
     */
    public Optional<User> findByUsername(Session session, String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return session.createQuery(
                        "from User where lower(username) = lower(:username)", User.class)
                .setParameter("username", username.trim())
                .uniqueResultOptional();
    }

    /**
     * Finds a user by internal id.
     *
     * <p>{@code session.get} rather than a query: the id is the primary key, so this is a
     * first-level cache hit inside a transaction that already touched the row, and one
     * indexed lookup otherwise. It is a hot path — every edit-lock acquisition on a held
     * entity resolves the holder's name through it (E18).
     *
     * <p>Consumer: {@code RepositoryUserDirectory.findById}, which is what puts a real name
     * in the lock banner instead of {@code "Another user"}.
     *
     * @param session the current session
     * @param userId  the user's internal id
     * @return the user, or empty when there is no such id
     */
    public Optional<User> findById(Session session, long userId) {
        return Optional.ofNullable(session.get(User.class, userId));
    }

    /**
     * The subjects this user coordinates.
     *
     * <p>Coordinator-ness is per-subject state, never a stored role (§5), so this is how a
     * wire {@code Role.COORDINATOR} is derived at login. Returns the subject codes rather
     * than a boolean because E6 onward needs to know <em>which</em> subjects.
     *
     * <p>Consumer: {@code RepositoryUserDirectory}.
     *
     * @param session the current session
     * @param userId  the user's internal id
     * @return the coordinated subject codes, empty when the user coordinates nothing
     */
    public List<String> findCoordinatedSubjects(Session session, long userId) {
        return session.createQuery("""
                        select c.subjectCode from Coordinator c
                        where c.teacherId = :userId
                        order by c.subjectCode
                        """, String.class)
                .setParameter("userId", userId)
                .getResultList();
    }

    /**
     * Everyone holding one stored role, by name (E15.3 - F9.4, spec 7.3.1).
     *
     * <p>Added under TEAM_SPLIT rule 5. The principal's report needs a list of teachers to run
     * a "same teacher" comparison about and a list of students to run a "same student" one,
     * and that list is school-wide: F9.3 gives her the whole school to read, so there is
     * nothing to scope this by.
     *
     * <p><b>Projected, not loaded.</b> It returns {@link PersonRef} rather than {@link User}
     * because a subject picker needs a name and a handle, and there is no reason for a report
     * screen's query to bring a password hash and a national id back with it (S-6).
     *
     * <p>{@code COORDINATOR} is not a value here, and that is not a gap: coordinator-ness is a
     * row in {@code coordinators} rather than a stored role (section 5), so a coordinator is
     * returned by {@code TEACHER} - which is right, because she writes exams like any other
     * teacher and a report about her exams is a report about a teacher's.
     *
     * <p>Consumer: E15.3's {@code JpaReportStore}, for the BY_TEACHER and BY_STUDENT pickers.
     *
     * @param session the current session
     * @param role    the stored role to list
     * @return the users holding it, by display name then id; empty when there are none
     */
    public List<PersonRef> findByRole(Session session, UserRole role) {
        if (role == null) {
            return List.of();
        }
        return session.createQuery("""
                        select new server.db.projections.PersonRef(u.id, u.fullName, u.username)
                        from User u where u.role = :role order by u.fullName, u.id
                        """, PersonRef.class)
                .setParameter("role", role)
                .getResultList();
    }

    /**
     * One user as a report names her (E15.3).
     *
     * <p>The single-subject sibling of {@link #findByRole}, used to resolve the subject a
     * report was asked about. Same reason for being a projection: resolving a label should not
     * drag a credential row across the session.
     *
     * @param session the current session
     * @param userId  the user
     * @param role    the role she must hold for this to answer
     * @return her reference, or empty when there is no such user <b>or</b> she holds another
     *         role - so a report about "student 2" cannot be answered with a teacher's name
     */
    public Optional<PersonRef> findRefByRole(Session session, long userId, UserRole role) {
        if (role == null) {
            return Optional.empty();
        }
        return session.createQuery("""
                        select new server.db.projections.PersonRef(u.id, u.fullName, u.username)
                        from User u where u.id = :userId and u.role = :role
                        """, PersonRef.class)
                .setParameter("userId", userId)
                .setParameter("role", role)
                .uniqueResultOptional();
    }
}
