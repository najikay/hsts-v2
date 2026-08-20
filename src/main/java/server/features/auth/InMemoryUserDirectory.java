package server.features.auth;

import at.favre.lib.crypto.bcrypt.BCrypt;
import common.dto.auth.CourseRef;
import common.dto.auth.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The development {@link UserDirectory}: five fixture users, hashed at
 * construction (Logic tier, E5.1).
 *
 * <p>It exists so the whole authentication path — BCrypt verification, the
 * throttle, single-session enforcement, the four role shells — is demonstrable
 * and testable <b>before</b> the database lands. The users mirror the seed
 * dataset (PRD §5) closely enough that the swap to the real directory in E2 PR3
 * changes nothing a demo script would notice.
 *
 * <p>Passwords are hashed with BCrypt at cost {@link #DEV_COST} rather than being
 * compared as text: the point of the fixture is to exercise the <i>real</i>
 * verification path, not to shortcut it. Cost 10 keeps five hashes at boot to a
 * few hundred milliseconds; production hashes come from the seed migration.
 *
 * <p>The shared password is a dev credential and is documented in
 * {@code docs/DEMO_ACCOUNTS.md}. Nothing here reaches a built server outside the
 * course demo: E2 PR3 replaces this class with the repository-backed adapter.
 */
public final class InMemoryUserDirectory implements UserDirectory {

    private static final Logger log = LoggerFactory.getLogger(InMemoryUserDirectory.class);

    /** BCrypt cost for the dev fixture — high enough to be real, low enough to boot fast. */
    public static final int DEV_COST = 10;

    /** The one password every fixture user shares (see {@code docs/DEMO_ACCOUNTS.md}). */
    public static final String DEV_PASSWORD = "demo123";

    // Course codes follow ARCHITECTURE §5 (courses.code2) and PRD §5's seed set.
    private static final CourseRef ALGEBRA_11 = new CourseRef("11", "Algebra 11");
    private static final CourseRef CALCULUS_12 = new CourseRef("12", "Calculus 12");
    private static final CourseRef JAVA_21 = new CourseRef("21", "Java Programming 21");
    private static final CourseRef DATABASES_22 = new CourseRef("22", "Databases 22");

    private final Map<String, UserRecord> byUsername = new LinkedHashMap<>();
    private final Map<Long, UserRecord> byId = new LinkedHashMap<>();

    /** Builds the fixture with {@link #DEV_PASSWORD} for every user. */
    public InMemoryUserDirectory() {
        this(DEV_PASSWORD);
    }

    /**
     * @param password the plaintext every fixture user gets; hashed once per user
     *                 here and never stored
     */
    public InMemoryUserDirectory(String password) {
        Objects.requireNonNull(password, "password");
        String hash = hash(password);

        add(new UserRecord(1001, "dana.cohen", hash, "Dana Cohen",
                Role.TEACHER, List.of(ALGEBRA_11, CALCULUS_12)));
        add(new UserRecord(1002, "rina.barak", hash, "Rina Barak",
                Role.COORDINATOR, List.of(CALCULUS_12)));
        add(new UserRecord(2001, "maya.levi", hash, "Maya Levi",
                Role.STUDENT, List.of(ALGEBRA_11, JAVA_21, DATABASES_22)));
        add(new UserRecord(2002, "noam.peretz", hash, "Noam Peretz",
                Role.STUDENT, List.of(CALCULUS_12, JAVA_21)));
        add(new UserRecord(3001, "principal.avia", hash, "Avia Shalev",
                Role.PRINCIPAL, List.of()));

        log.info("In-memory user directory ready with {} dev users (replaced by the seeded DB in E2)",
                byUsername.size());
    }

    @Override
    public Optional<UserRecord> findByUsername(String username) {
        return Optional.ofNullable(byUsername.get(normalize(username)));
    }

    /**
     * The id lookup E18's lock banner needs. A second map rather than a scan:
     * every lock acquisition on a held entity resolves a name, so this is a hot
     * path even in a five-user fixture.
     */
    @Override
    public Optional<UserRecord> findById(long userId) {
        return Optional.ofNullable(byId.get(userId));
    }

    /** @return every fixture user, in declaration order (console listings, tests). */
    public List<UserRecord> all() {
        return List.copyOf(byUsername.values());
    }

    /** @return how many users the fixture holds. */
    public int size() {
        return byUsername.size();
    }

    private void add(UserRecord user) {
        byUsername.put(normalize(user.username()), user);
        byId.put(user.id(), user);
    }

    /** @return a BCrypt hash at {@link #DEV_COST}. */
    public static String hash(String plaintext) {
        return BCrypt.withDefaults().hashToString(DEV_COST, plaintext.toCharArray());
    }

    /**
     * Usernames are case- and whitespace-insensitive. This must agree with
     * {@link LoginThrottle}'s key, or {@code DANA.COHEN} would get a fresh set of
     * attempts after {@code dana.cohen} was locked out.
     */
    static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
