package server.db.repos;

import common.dto.auth.CourseRef;
import common.dto.auth.Role;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import server.db.Transactions;
import server.db.entities.User;
import server.features.auth.UserDirectory;
import server.features.auth.UserRecord;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The database-backed {@code UserDirectory} that replaces E5's in-memory fixture (E2.11).
 *
 * <p>{@code UserDirectory}'s own javadoc describes this class as "about fifteen lines that
 * calls {@code UserRepository.findByUsername} and maps the entity into a {@code UserRecord}".
 * It is a little longer than that, because of the two mappings below that are not simple
 * copies.
 *
 * <h2>Role is derived here, and only here</h2>
 *
 * <p>The stored role has three values; the wire role has four (§5). {@code COORDINATOR} is
 * not stored anywhere: it is a stored {@code TEACHER} who also owns a {@code coordinators}
 * row. Keeping the derivation in this one adapter is what makes it impossible for the two to
 * drift, which is the whole reason the schema refuses to store it.
 *
 * <h2>Courses are taught and enrolled, not one or the other</h2>
 *
 * <p>{@code UserRecord}'s javadoc reads "courses taught (teacher/coordinator) <em>or</em>
 * enrolled in (student)", which sounds like a choice driven by role. A teacher enrolled in a
 * colleague's course has both, and the shell's nav has to show both, so
 * {@link CourseRepository#findForUser} returns the union. Flagged for the lead in the PR 2b
 * report: the wording is in his file, not this one.
 *
 * <p>Safe for concurrent use from OCSF read threads: it holds no mutable state and every
 * call runs in its own short transaction.
 */
public final class RepositoryUserDirectory implements UserDirectory {

    private final SessionFactory factory;
    private final UserRepository users;
    private final CourseRepository courses;

    /**
     * @param factory the session factory this directory reads through
     */
    public RepositoryUserDirectory(SessionFactory factory) {
        this(factory, new UserRepository(), new CourseRepository());
    }

    RepositoryUserDirectory(SessionFactory factory, UserRepository users, CourseRepository courses) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.users = users;
        this.courses = courses;
    }

    @Override
    public Optional<UserRecord> findByUsername(String username) {
        return Transactions.inTx(factory, session -> users.findByUsername(session, username)
                .map(user -> new UserRecord(
                        user.getId(),
                        user.getUsername(),
                        user.getPasswordHash(),
                        user.getFullName(),
                        wireRole(session, user),
                        courseRefs(session, user))));
    }

    private Role wireRole(Session session, User user) {
        return switch (user.getRole()) {
            case STUDENT -> Role.STUDENT;
            case PRINCIPAL -> Role.PRINCIPAL;
            case TEACHER -> users.findCoordinatedSubjects(session, user.getId()).isEmpty()
                    ? Role.TEACHER
                    : Role.COORDINATOR;
        };
    }

    private List<CourseRef> courseRefs(Session session, User user) {
        return courses.findForUser(session, user.getId()).stream()
                .map(course -> new CourseRef(course.code(), course.name()))
                .toList();
    }

}
