package server.features.exam;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The in-memory index of who is sitting what (E10.7 — C-4).
 *
 * <p>Small, and worth its own tests because it is the whole answer the study bot gets: a
 * wrong answer here either locks a student out of a bot she is entitled to, or leaves the
 * exam's own bot open during that exam, which is the one thing C-4 forbids outright.
 */
class AttemptRegistryTest {

    private static final Instant T0 = Instant.parse("2026-08-20T09:00:00Z");
    private static final long MAYA = 2001L;
    private static final long NOAM = 2002L;

    private AttemptRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AttemptRegistry();
    }

    @Nested
    @DisplayName("tracking sittings")
    class Sittings {

        @Test
        @DisplayName("a started sitting is found by student and by course")
        void findsTheSitting() {
            registry.started(sitting(1, MAYA, "21", "Java Midterm"));

            assertThat(registry.coursesInProgressFor(MAYA)).containsExactly("21");
            assertThat(registry.activeAttemptFor(MAYA, "21")).isPresent();
            assertThat(registry.byId(1)).isPresent();
            assertThat(registry.activeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("the course match is case-insensitive and trims, as codes get typed")
        void courseMatchIsForgiving() {
            registry.started(sitting(1, MAYA, "2A", "Java Midterm"));

            assertThat(registry.activeAttemptFor(MAYA, " 2a ")).isPresent();
        }

        @Test
        @DisplayName("another course answers empty, which is the allowed branch of C-4")
        void otherCourseIsNotLocked() {
            registry.started(sitting(1, MAYA, "21", "Java Midterm"));

            assertThat(registry.activeAttemptFor(MAYA, "11")).isEmpty();
        }

        @Test
        @DisplayName("a student sitting nothing has nothing")
        void nothingInProgress() {
            assertThat(registry.coursesInProgressFor(MAYA)).isEmpty();
            assertThat(registry.activeAttemptsFor(MAYA)).isEmpty();
            assertThat(registry.activeAttemptFor(MAYA, "21")).isEmpty();
        }

        @Test
        @DisplayName("two live sittings are both reported, oldest first")
        void twoSittings() {
            // Nothing in the product forbids two live executions, and a bot that assumed
            // one would pick the wrong lockout message the day it happened.
            registry.started(sitting(1, MAYA, "21", "Java Midterm"));
            registry.started(new ActiveAttempt(2, 5002, MAYA, "11", "Algebra 11",
                    "Algebra Midterm", 1001, T0.plus(Duration.ofMinutes(5))));

            assertThat(registry.activeAttemptsFor(MAYA))
                    .extracting(ActiveAttempt::attemptId).containsExactly(1L, 2L);
            assertThat(registry.coursesInProgressFor(MAYA)).containsExactlyInAnyOrder("21", "11");
        }

        @Test
        @DisplayName("students do not see each other's sittings")
        void studentsAreSeparate() {
            registry.started(sitting(1, MAYA, "21", "Java Midterm"));

            assertThat(registry.activeAttemptsFor(NOAM)).isEmpty();
        }

        @Test
        @DisplayName("finishing unlocks the course and forgets the sitting")
        void finishing() {
            registry.started(sitting(1, MAYA, "21", "Java Midterm"));

            assertThat(registry.finished(1)).isPresent();

            assertThat(registry.coursesInProgressFor(MAYA)).isEmpty();
            assertThat(registry.activeCount()).isZero();
            assertThat(registry.byId(1)).isEmpty();
        }

        @Test
        @DisplayName("finishing something untracked answers empty rather than throwing")
        void finishingUnknown() {
            assertThat(registry.finished(99)).isEmpty();
        }

        @Test
        @DisplayName("re-registering the same sitting is idempotent and raises no second start")
        void reRegisteringIsQuiet() {
            List<String> heard = new ArrayList<>();
            registry.addListener(listener(heard));

            assertThat(registry.started(sitting(1, MAYA, "21", "Java Midterm"))).isTrue();
            assertThat(registry.started(sitting(1, MAYA, "21", "Java Midterm"))).isFalse();

            // A reconnect must not re-lock a bot chat the student already has open.
            assertThat(heard).containsExactly("started:1");
            assertThat(registry.activeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a null sitting is rejected at the boundary")
        void nullSittingRejected() {
            assertThatNullPointerException().isThrownBy(() -> registry.started(null));
        }
    }

    @Nested
    @DisplayName("integrity flags")
    class Flags {

        @Test
        @DisplayName("a flag records the course and the moment")
        void flagsAnAttempt() {
            assertThat(registry.flag(1, "11", "Algebra 11", T0)).isTrue();

            assertThat(registry.flagOf(1)).isPresent();
            assertThat(registry.flagOf(1).get().courseName()).isEqualTo("Algebra 11");
            assertThat(registry.flagOf(1).get().at()).isEqualTo(T0);
            assertThat(registry.flagOf(1).get().label()).isEqualTo("used Algebra 11 bot");
            assertThat(registry.flagCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a second report keeps the first time, which is the fact a teacher acts on")
        void firstReportWins() {
            registry.flag(1, "11", "Algebra 11", T0);

            assertThat(registry.flag(1, "11", "Algebra 11", T0.plus(Duration.ofMinutes(9)))).isFalse();

            assertThat(registry.flagOf(1).get().at()).isEqualTo(T0);
        }

        @Test
        @DisplayName("a flag outlives the sitting, so the monitor still shows it afterwards")
        void flagOutlivesTheSitting() {
            registry.started(sitting(1, MAYA, "21", "Java Midterm"));
            registry.flag(1, "11", "Algebra 11", T0);

            registry.finished(1);

            assertThat(registry.flagOf(1)).isPresent();
        }

        @Test
        @DisplayName("an unflagged attempt has none")
        void noFlag() {
            assertThat(registry.flagOf(1)).isEmpty();
        }

        @Test
        @DisplayName("flags can be cleared wholesale when an execution is archived")
        void clearFlags() {
            registry.flag(1, "11", "Algebra 11", T0);

            registry.clearFlags();

            assertThat(registry.flagCount()).isZero();
        }
    }

    @Nested
    @DisplayName("listeners")
    class Listeners {

        @Test
        @DisplayName("both ends of a sitting are announced")
        void bothEnds() {
            List<String> heard = new ArrayList<>();
            registry.addListener(listener(heard));

            registry.started(sitting(1, MAYA, "21", "Java Midterm"));
            registry.finished(1);

            assertThat(heard).containsExactly("started:1", "finished:1");
        }

        @Test
        @DisplayName("a listener that throws does not stop an exam starting or ending ⚑")
        void aBrokenListenerIsIsolated() {
            List<String> heard = new ArrayList<>();
            registry.addListener(new AttemptTracker.Listener() {
                @Override
                public void attemptStarted(ActiveAttempt attempt) {
                    throw new IllegalStateException("bot service is down");
                }
            });
            registry.addListener(listener(heard));

            registry.started(sitting(1, MAYA, "21", "Java Midterm"));

            assertThat(heard).containsExactly("started:1");
            assertThat(registry.activeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("the default listener methods do nothing rather than requiring both")
        void defaultsAreNoOps() {
            registry.addListener(new AttemptTracker.Listener() { });

            registry.started(sitting(1, MAYA, "21", "Java Midterm"));
            registry.finished(1);

            assertThat(registry.activeCount()).isZero();
        }

        @Test
        @DisplayName("a null listener is rejected at the boundary")
        void nullListenerRejected() {
            assertThatNullPointerException().isThrownBy(() -> registry.addListener(null));
        }
    }

    @Nested
    @DisplayName("the sitting record itself")
    class Record {

        @Test
        @DisplayName("a blank course name falls back to the code, so a row is always labelled")
        void blankNameFallsBack() {
            ActiveAttempt attempt = new ActiveAttempt(1, 5001, MAYA, "21", "  ",
                    "Java Midterm", 1001, T0);

            assertThat(attempt.courseName()).isEqualTo("21");
        }

        @Test
        @DisplayName("nulls normalise rather than travelling")
        void nullsNormalise() {
            ActiveAttempt attempt = new ActiveAttempt(1, 5001, MAYA, null, null, null, 1001, T0);

            assertThat(attempt.courseCode()).isEmpty();
            assertThat(attempt.examName()).isEmpty();
            assertThat(attempt.isSameCourseAs(null)).isFalse();
        }
    }

    private static ActiveAttempt sitting(long attemptId, long studentId, String course, String exam) {
        return new ActiveAttempt(attemptId, 5000 + attemptId, studentId, course,
                "Course " + course, exam, 1001, T0);
    }

    private static AttemptTracker.Listener listener(List<String> heard) {
        return new AttemptTracker.Listener() {
            @Override
            public void attemptStarted(ActiveAttempt attempt) {
                heard.add("started:" + attempt.attemptId());
            }

            @Override
            public void attemptFinished(ActiveAttempt attempt) {
                heard.add("finished:" + attempt.attemptId());
            }
        };
    }
}
