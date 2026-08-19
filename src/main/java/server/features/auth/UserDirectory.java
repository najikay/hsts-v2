package server.features.auth;

import java.util.Optional;

/**
 * Where {@link AuthService} looks users up (Logic tier, E5.1).
 *
 * <p><b>This interface is the seam for E2.</b> Authentication needs exactly one
 * thing from persistence — "give me the user with this username, hash and all" —
 * so that one thing is declared here and nothing else in the auth feature knows
 * whether the answer came from a HashMap or from MySQL.
 *
 * <p>Concretely, the swap looks like this:
 * <ul>
 *   <li><b>today (E5):</b> {@link InMemoryUserDirectory} — five BCrypt-hashed dev
 *       users, documented in {@code docs/DEMO_ACCOUNTS.md};</li>
 *   <li><b>E2 PR2/PR3:</b> a {@code RepositoryUserDirectory} adapter of about
 *       fifteen lines that calls {@code UserRepository.findByUsername} and maps the
 *       entity (plus its {@code course_teachers} / {@code enrollments} rows) into a
 *       {@link UserRecord}. {@code HSTSServer} constructs that one instead. No
 *       other line of the auth feature — and no test of {@link AuthService} —
 *       changes, because every rule the service enforces is expressed against this
 *       interface.</li>
 * </ul>
 *
 * <p>Implementations must be safe to call from many OCSF read threads at once and
 * must treat usernames case-insensitively (the throttle keys on the normalised
 * name, so a directory that did not would let {@code DANA.COHEN} bypass the
 * lockout on {@code dana.cohen}).
 */
@FunctionalInterface
public interface UserDirectory {

    /**
     * @param username the login name as typed; implementations trim it and
     *                 compare case-insensitively
     * @return the user, or empty when no such user exists. Never throws for an
     *         unknown user — "no such user" and "wrong password" must be
     *         indistinguishable to the caller (F1.1)
     */
    Optional<UserRecord> findByUsername(String username);
}
