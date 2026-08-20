package server.db.repos;

import org.hibernate.Session;
import server.db.entities.User;

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
}
