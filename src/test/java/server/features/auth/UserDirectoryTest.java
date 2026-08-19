package server.features.auth;

import common.dto.auth.CourseRef;
import common.dto.auth.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * {@link InMemoryUserDirectory}, {@link UserRecord} and {@link LoginThrottle} —
 * the pieces {@link AuthService} is built from (E5.1).
 *
 * <p>The fixture's contents are asserted on purpose: {@code docs/DEMO_ACCOUNTS.md}
 * is a document a human follows during a demo, and a silent rename here would
 * turn it into a lie.
 */
class UserDirectoryTest {

    private static final InMemoryUserDirectory DIRECTORY = new InMemoryUserDirectory();

    @Nested
    @DisplayName("InMemoryUserDirectory")
    class Fixture {

        @Test
        @DisplayName("holds exactly the five users DEMO_ACCOUNTS.md documents")
        void theFiveDocumentedUsers() {
            assertThat(DIRECTORY.size()).isEqualTo(5);
            assertThat(DIRECTORY.all()).extracting(UserRecord::username)
                    .containsExactly("dana.cohen", "rina.barak", "maya.levi",
                            "noam.peretz", "principal.avia");
            assertThat(DIRECTORY.all()).extracting(UserRecord::role)
                    .containsExactly(Role.TEACHER, Role.COORDINATOR, Role.STUDENT,
                            Role.STUDENT, Role.PRINCIPAL);
        }

        @Test
        @DisplayName("stores BCrypt hashes, never the plaintext (S-38)")
        void hashesAreRealBcrypt() {
            for (UserRecord user : DIRECTORY.all()) {
                assertThat(user.passwordHash())
                        .as("%s is BCrypt-hashed, never plaintext", user.username())
                        .startsWith("$2")
                        .doesNotContain(InMemoryUserDirectory.DEV_PASSWORD);
            }
        }

        @Test
        @DisplayName("courses match the seed dataset's codes")
        void coursesCarryCodes() {
            UserRecord maya = DIRECTORY.findByUsername("maya.levi").orElseThrow();

            assertThat(maya.courses()).extracting(CourseRef::code)
                    .containsExactly("11", "21", "22");
            assertThat(DIRECTORY.findByUsername("principal.avia").orElseThrow().courses()).isEmpty();
        }

        @Test
        @DisplayName("lookup is case- and whitespace-insensitive")
        void lookupIsNormalised() {
            assertThat(DIRECTORY.findByUsername("  Dana.COHEN  ")).isPresent();
            assertThat(DIRECTORY.findByUsername("dana.cohen")).isPresent();
        }

        @Test
        @DisplayName("an unknown or null username is empty, never an exception")
        void unknownUserIsEmpty() {
            assertThat(DIRECTORY.findByUsername("nobody")).isEmpty();
            assertThat(DIRECTORY.findByUsername(null)).isEmpty();
            assertThat(DIRECTORY.findByUsername("")).isEmpty();
        }
    }

    @Nested
    @DisplayName("UserRecord")
    class Record {

        @Test
        @DisplayName("never prints its hash")
        void toStringHidesTheHash() {
            UserRecord user = new UserRecord(1, "u", "$2a$10$hash", "U", Role.STUDENT, List.of());

            assertThat(user.toString()).doesNotContain("$2a$10$hash").contains("***");
        }

        @Test
        @DisplayName("copies its course list defensively and tolerates a null one")
        void coursesAreImmutable() {
            List<CourseRef> mutable = new java.util.ArrayList<>(List.of(new CourseRef("11", "Algebra")));
            UserRecord user = new UserRecord(1, "u", "h", "U", Role.STUDENT, mutable);

            mutable.clear();

            assertThat(user.courses()).hasSize(1);
            assertThat(new UserRecord(1, "u", "h", "U", Role.STUDENT, null).courses()).isEmpty();
        }

        @Test
        @DisplayName("refuses to exist without the fields authentication needs")
        void requiredFields() {
            assertThatNullPointerException().isThrownBy(() ->
                    new UserRecord(1, null, "h", "U", Role.STUDENT, List.of()));
            assertThatNullPointerException().isThrownBy(() ->
                    new UserRecord(1, "u", null, "U", Role.STUDENT, List.of()));
            assertThatNullPointerException().isThrownBy(() ->
                    new UserRecord(1, "u", "h", null, Role.STUDENT, List.of()));
            assertThatNullPointerException().isThrownBy(() ->
                    new UserRecord(1, "u", "h", "U", null, List.of()));
        }
    }

    @Nested
    @DisplayName("LoginThrottle")
    class Throttle {

        private final MutableClock clock = new MutableClock(Instant.parse("2026-08-19T09:00:00Z"));
        private final LoginThrottle throttle = new LoginThrottle(clock);

        @Test
        @DisplayName("counts failures and locks on the fifth")
        void locksOnTheFifth() {
            for (int i = 1; i < LoginThrottle.MAX_FAILURES; i++) {
                assertThat(throttle.recordFailure("u")).as("failure %d does not lock", i).isFalse();
            }
            assertThat(throttle.recordFailure("u")).isTrue();
            assertThat(throttle.isLocked("u")).isTrue();
            assertThat(throttle.failureCount("u")).isEqualTo(LoginThrottle.MAX_FAILURES);
        }

        @Test
        @DisplayName("the lock expires exactly after the lockout window")
        void expiryIsExact() {
            lockOut("u");

            clock.advance(LoginThrottle.LOCKOUT.minusMillis(1));
            assertThat(throttle.isLocked("u")).isTrue();

            clock.advance(Duration.ofMillis(1));
            assertThat(throttle.isLocked("u")).isFalse();
        }

        @Test
        @DisplayName("an expired lock hands back a clean slate")
        void expiryResetsTheCounter() {
            lockOut("u");
            clock.advance(LoginThrottle.LOCKOUT.plusSeconds(1));

            assertThat(throttle.isLocked("u")).isFalse();
            assertThat(throttle.failureCount("u")).isZero();
            assertThat(throttle.trackedUsernames()).isZero();
        }

        @Test
        @DisplayName("remaining lockout counts down and is empty when unlocked")
        void remainingLockout() {
            assertThat(throttle.remainingLockout("u")).isEmpty();
            lockOut("u");

            clock.advance(Duration.ofSeconds(20));
            assertThat(throttle.remainingLockout("u")).contains(Duration.ofSeconds(10));

            clock.advance(Duration.ofSeconds(10));
            assertThat(throttle.remainingLockout("u")).isEmpty();
        }

        @Test
        @DisplayName("success and clear forget everything")
        void successForgets() {
            throttle.recordFailure("u");
            throttle.recordSuccess("u");
            assertThat(throttle.failureCount("u")).isZero();

            lockOut("u");
            throttle.clear();
            assertThat(throttle.isLocked("u")).isFalse();
        }

        @Test
        @DisplayName("case and whitespace cannot dodge a lockout")
        void keysAreNormalised() {
            lockOut("dana.cohen");

            assertThat(throttle.isLocked("  DANA.Cohen ")).isTrue();
        }

        @Test
        @DisplayName("failures are counted per username, not globally")
        void perUsername() {
            lockOut("dana.cohen");

            assertThat(throttle.isLocked("maya.levi")).isFalse();
            assertThat(throttle.trackedUsernames()).isEqualTo(1);
        }

        @Test
        @DisplayName("a null username is a key like any other, not a crash")
        void nullUsernameIsSafe() {
            assertThat(throttle.isLocked(null)).isFalse();
            assertThat(throttle.failureCount(null)).isZero();
            throttle.recordFailure(null);
            assertThat(throttle.failureCount(null)).isEqualTo(1);
            throttle.recordSuccess(null);
        }

        private void lockOut(String username) {
            for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
                throttle.recordFailure(username);
            }
        }
    }

    /** A {@link Clock} the test moves by hand. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
