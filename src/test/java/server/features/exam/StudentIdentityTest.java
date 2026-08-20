package server.features.exam;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The S-18 identity check, and the two small types beside it (E10.1).
 *
 * <p>Tiny, and worth its own file because the comparison is the whole of S-18: a student
 * confirms she is who the session already says she is, and getting it slightly wrong means
 * either locking a student out of her own exam or letting a mistyped number through.
 */
class StudentIdentityTest {

    private static final StudentIdentity MAYA =
            new StudentIdentity(2001, "Maya Levi", "374301851");

    @Nested
    @DisplayName("matching")
    class Matching {

        @Test
        @DisplayName("her own number matches")
        void ownNumberMatches() {
            assertThat(MAYA.matches("374301851")).isTrue();
        }

        @Test
        @DisplayName("spaces and dashes are forgiven, because people type them")
        void whitespaceAndDashesAreForgiven() {
            // Somebody reading a number off a card groups the digits, and somebody pasting
            // from a spreadsheet brings the spaces with them.
            assertThat(MAYA.matches(" 374-301 851 ")).isTrue();
            assertThat(MAYA.matches("374\t301851")).isTrue();
        }

        @Test
        @DisplayName("somebody else's number does not")
        void anotherNumberDoesNot() {
            assertThat(MAYA.matches("301548202")).isFalse();
        }

        @Test
        @DisplayName("a prefix or a suffix is not a match")
        void partialsDoNotMatch() {
            assertThat(MAYA.matches("37430185")).isFalse();
            assertThat(MAYA.matches("3743018510")).isFalse();
        }

        @Test
        @DisplayName("nothing typed matches nobody, including a student with no number stored")
        void emptyMatchesNothing() {
            assertThat(MAYA.matches("")).isFalse();
            assertThat(MAYA.matches("   ")).isFalse();
            assertThat(MAYA.matches(null)).isFalse();
            assertThat(new StudentIdentity(1, "Nobody", "").matches("")).isFalse();
        }

        @Test
        @DisplayName("case folding costs nothing and removes a class of demo-day surprise")
        void caseIsIgnored() {
            assertThat(new StudentIdentity(1, "Ext", "ab123").matches("AB123")).isTrue();
        }
    }

    @Nested
    @DisplayName("as a value")
    class AsAValue {

        @Test
        @DisplayName("nulls normalise rather than travelling")
        void nullsNormalise() {
            StudentIdentity blank = new StudentIdentity(7, null, null);

            assertThat(blank.fullName()).isEmpty();
            assertThat(blank.nationalId()).isEmpty();
        }

        @Test
        @DisplayName("a row can always be labelled, even with no name stored")
        void displayNameAlwaysHasSomething() {
            assertThat(MAYA.displayName()).isEqualTo("Maya Levi");
            assertThat(new StudentIdentity(7, "  ", "1").displayName()).isEqualTo("Student 7");
        }

        @Test
        @DisplayName("it compares by value")
        void valueEquality() {
            assertThat(MAYA).isEqualTo(new StudentIdentity(2001, "Maya Levi", "374301851"));
            assertThat(MAYA).hasSameHashCodeAs(new StudentIdentity(2001, "Maya Levi", "374301851"));
            assertThat(MAYA).isNotEqualTo(new StudentIdentity(2002, "Maya Levi", "374301851"));
            assertThat(MAYA).isNotEqualTo("Maya Levi");
            assertThat(MAYA).isEqualTo(MAYA);
        }

        @Test
        @DisplayName("its toString keeps the national id out of the log")
        void toStringHidesPersonalData() {
            // This record travels through log-adjacent code, and a national id in a log file
            // is personal data nobody decided to store there.
            assertThat(MAYA.toString())
                    .contains("Maya Levi")
                    .contains("***")
                    .doesNotContain("374301851");
        }
    }

    @Nested
    @DisplayName("the duplicate-start signal")
    class Duplicate {

        @Test
        @DisplayName("it names both halves of the unique key it hit")
        void carriesTheKey() {
            IllegalStateException cause = new IllegalStateException("uq_exam_attempts_student");
            DuplicateAttemptException failure = new DuplicateAttemptException(5001, 2001, cause);

            assertThat(failure.executionId()).isEqualTo(5001);
            assertThat(failure.studentId()).isEqualTo(2001);
            assertThat(failure.getCause()).isSameAs(cause);
            assertThat(failure.getMessage()).contains("5001").contains("2001");
        }
    }

    @Nested
    @DisplayName("the bot's lifecycle seam")
    class TrackerListener {

        @Test
        @DisplayName("both callbacks default to doing nothing, so a subscriber overrides one")
        void defaultsAreNoOps() {
            AttemptTracker.Listener listener = new AttemptTracker.Listener() { };
            ActiveAttempt sitting = new ActiveAttempt(1, 5001, 2001, "21", "Java",
                    "Java Midterm", 1001, java.time.Instant.parse("2026-08-20T09:00:00Z"));

            listener.attemptStarted(sitting);
            listener.attemptFinished(sitting);

            assertThat(sitting.isSameCourseAs("21")).isTrue();
        }
    }

    @Nested
    @DisplayName("the client's debounce seam")
    class Debounce {

        @Test
        @DisplayName("the immediate runner runs at once, for tests that do not care")
        void immediateRunsNow() {
            boolean[] ran = {false};

            client.features.exam.DelayedRunner.immediate()
                    .runAfter(java.time.Duration.ofSeconds(30), () -> ran[0] = true);

            assertThat(ran[0]).isTrue();
        }

        @Test
        @DisplayName("the shared runner really runs the task, owning no threads")
        void sharedRunsEventually() throws Exception {
            java.util.concurrent.CountDownLatch ran = new java.util.concurrent.CountDownLatch(1);

            client.features.exam.DelayedRunner.shared()
                    .runAfter(java.time.Duration.ZERO, ran::countDown);

            assertThat(ran.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }

        @Test
        @DisplayName("a negative delay is clamped rather than rejected")
        void negativeDelayIsClamped() throws Exception {
            java.util.concurrent.CountDownLatch ran = new java.util.concurrent.CountDownLatch(1);

            client.features.exam.DelayedRunner.shared()
                    .runAfter(java.time.Duration.ofMinutes(-5), ran::countDown);

            assertThat(ran.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }
}
