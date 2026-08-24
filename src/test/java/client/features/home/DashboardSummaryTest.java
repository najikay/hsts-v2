package client.features.home;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DashboardSummary} — the one live sentence under every greeting (UI wave 2).
 *
 * <p>This is the first thing four roles read after signing in and the first thing a
 * visitor reads during a demo, and it is assembled from numbers rather than
 * chosen from a list. Assembly is where sentences go wrong in ways nobody
 * demonstrates: "1 sittings are live", "and 0 papers are waiting", and a
 * confident summary of zeros published a second before the reads land.
 *
 * <p>Every test below is one of those. None of them boots a toolkit and none of
 * them reads a clock — the time of day is {@link HomeGreeting}'s job, and the
 * two are paired only in the header they share, which is asserted at the bottom.
 */
class DashboardSummaryTest {

    @Nested
    @DisplayName("Counting reads as prose, not as a table cell")
    class Counting {

        @ParameterizedTest
        @CsvSource({
                "1, One sitting",
                "0, 0 sittings",
                "2, 2 sittings",
                "17, 17 sittings"
        })
        @DisplayName("one is spelled out; everything else stays a numeral")
        void oneIsAWord(int howMany, String expected) {
            assertThat(DashboardSummary.count(howMany, "sitting", "sittings")).isEqualTo(expected);
        }

        @Test
        @DisplayName("⚑ the verb agrees with the number")
        void theVerbAgrees() {
            // "1 sittings are live" is the sentence a format string produces, and
            // it is on screen for every teacher with exactly one exam running,
            // which is the normal case rather than the edge one.
            assertThat(DashboardSummary.teacher(1, 0, false, false))
                    .isEqualTo("One sitting is live right now.");
            assertThat(DashboardSummary.teacher(2, 0, false, false))
                    .isEqualTo("2 sittings are live right now.");
        }
    }

    @Nested
    @DisplayName("Joining")
    class Joining {

        @Test
        @DisplayName("two clauses become one sentence, capitalised and stopped")
        void twoClausesJoin() {
            assertThat(DashboardSummary.sentence(List.of("one thing happened", "another did")))
                    .isEqualTo("One thing happened, and another did.");
        }

        @Test
        @DisplayName("one clause is a sentence on its own")
        void oneClauseStandsAlone() {
            assertThat(DashboardSummary.sentence(List.of("one thing happened")))
                    .isEqualTo("One thing happened.");
        }

        @Test
        @DisplayName("⚑ a clause with nothing in it is dropped, never rendered as a zero")
        void emptyClausesAreDropped() {
            // A teacher with a live sitting and an empty grading queue reads one
            // clause, not two with a nought in the second.
            assertThat(DashboardSummary.teacher(1, 0, false, false))
                    .doesNotContain("0")
                    .doesNotContain("marking");
        }

        @Test
        @DisplayName("nothing at all is an empty string, not a stray full stop")
        void nothingIsNothing() {
            assertThat(DashboardSummary.sentence(List.of("", "  "))).isEmpty();
        }

        @Test
        @DisplayName("a summary caps at two clauses; a third would be a list")
        void twoClausesIsTheCap() {
            assertThat(DashboardSummary.sentence(List.of("a happened", "b happened",
                    "c happened"))).isEqualTo("A happened, and b happened.");
        }
    }

    @Nested
    @DisplayName("The states that are not a number")
    class NotYetANumber {

        @Test
        @DisplayName("⚑ nothing loaded yet says so, rather than publishing zeros")
        void loadingIsNotZero() {
            assertThat(DashboardSummary.teacher(0, 0, true, false))
                    .isEqualTo(DashboardCopy.SUMMARY_LOADING);
            assertThat(DashboardSummary.coordinator(0, 0, true, false))
                    .isEqualTo(DashboardCopy.SUMMARY_LOADING);
            assertThat(DashboardSummary.student(0, null, true, false))
                    .isEqualTo(DashboardCopy.SUMMARY_LOADING);
            assertThat(DashboardSummary.principal(0, 0, true, false))
                    .isEqualTo(DashboardCopy.SUMMARY_LOADING);
        }

        @Test
        @DisplayName("⚑ every read failing says so; it is the prose form of 'not available'")
        void failureIsNotZeroInProseEither() {
            // The same lie DashboardCard.State.FAILED exists to prevent, told in
            // a sentence instead of in a number. A coordinator who reads "no
            // exams are waiting" when the server is unreachable stops checking.
            assertThat(DashboardSummary.coordinator(0, 0, false, true))
                    .isEqualTo(DashboardCopy.SUMMARY_UNAVAILABLE)
                    .isNotEqualTo(DashboardCopy.SUMMARY_COORDINATOR_QUIET);
        }

        @Test
        @DisplayName("failure wins over loading: an unreachable server is the news")
        void failureBeatsLoading() {
            assertThat(DashboardSummary.teacher(0, 0, true, true))
                    .isEqualTo(DashboardCopy.SUMMARY_UNAVAILABLE);
        }

        @Test
        @DisplayName("a quiet day is a real answer, not a list of noughts")
        void quietIsAnAnswer() {
            assertThat(DashboardSummary.teacher(0, 0, false, false))
                    .isEqualTo(DashboardCopy.SUMMARY_TEACHER_QUIET);
            assertThat(DashboardSummary.coordinator(0, 0, false, false))
                    .isEqualTo(DashboardCopy.SUMMARY_COORDINATOR_QUIET);
            assertThat(DashboardSummary.student(0, null, false, false))
                    .isEqualTo(DashboardCopy.SUMMARY_STUDENT_QUIET);
            assertThat(DashboardSummary.principal(0, 0, false, false))
                    .isEqualTo(DashboardCopy.SUMMARY_PRINCIPAL_QUIET);
        }
    }

    @Nested
    @DisplayName("The four roles")
    class Roles {

        @Test
        @DisplayName("the teacher's sentence is the canvas's sentence")
        void theTeacherReadsTheCanvas() {
            assertThat(DashboardSummary.teacher(1, 8, false, false))
                    .isEqualTo("One sitting is live right now, and 8 sittings are waiting for "
                            + "your marking.");
        }

        @Test
        @DisplayName("the coordinator's names the queue and where it came from")
        void theCoordinatorReadsHerQueue() {
            assertThat(DashboardSummary.coordinator(3, 2, false, false))
                    .isEqualTo("3 exams are waiting for your approval, and they came from "
                            + "2 teachers.");
        }

        @Test
        @DisplayName("the student's names the newest exam when there is one")
        void theStudentReadsHerNewest() {
            assertThat(DashboardSummary.student(4, "Algebra midterm", false, false))
                    .isEqualTo("4 grades are published, and the newest is Algebra midterm.");
        }

        @Test
        @DisplayName("a grade with no exam name still makes a sentence")
        void anUnlabelledRowIsSurvivable() {
            assertThat(DashboardSummary.student(1, null, false, false))
                    .isEqualTo("One grade is published.");
            assertThat(DashboardSummary.student(1, "   ", false, false))
                    .isEqualTo("One grade is published.");
        }

        @Test
        @DisplayName("the principal's is about the school, not about her")
        void thePrincipalReadsTheSchool() {
            assertThat(DashboardSummary.principal(12, 5, false, false))
                    .isEqualTo("The school has 12 exams on file, and 5 sittings have been "
                            + "marked.");
        }
    }

    @Nested
    @DisplayName("Paired with the greeting")
    class WithTheGreeting {

        @Test
        @DisplayName("⚑ the greeting is the clock's and the summary is the data's")
        void theHeaderIsTwoIndependentDecisions() {
            // The header shows one line from an injected moment and one from the
            // cards. Neither knows about the other, which is what lets both be
            // tested with no toolkit: this asserts the pairing a screen builds.
            LocalDateTime morning = LocalDateTime.parse("2026-08-24T08:30:00");
            LocalDateTime evening = LocalDateTime.parse("2026-08-24T19:30:00");

            assertThat(HomeGreeting.greeting("Dana Cohen", morning))
                    .isEqualTo("Good morning, Dana");
            assertThat(HomeGreeting.greeting("Dana Cohen", evening))
                    .isEqualTo("Good evening, Dana");

            // The same summary regardless of the hour: what is happening is not a
            // function of what time it is.
            String summary = DashboardSummary.teacher(1, 0, false, false);
            assertThat(summary).isEqualTo("One sitting is live right now.");
        }
    }

    @Nested
    @DisplayName("House voice")
    class Voice {

        @Test
        @DisplayName("no composed sentence carries an em dash or shouts")
        void composedSentencesFollowTheHouseRules() {
            List<String> composed = List.of(
                    DashboardSummary.teacher(1, 8, false, false),
                    DashboardSummary.teacher(0, 0, false, false),
                    DashboardSummary.coordinator(3, 2, false, false),
                    DashboardSummary.student(4, "Algebra midterm", false, false),
                    DashboardSummary.principal(12, 5, false, false));

            assertThat(composed).allSatisfy(line -> {
                assertThat(line).doesNotContain("—").doesNotContain("–");
                assertThat(line).endsWith(".");
                // Sentence case means "no sentence starts lowercase", not "every
                // sentence starts with a letter": a count of three opens with the
                // numeral, which is the canvas's own wording ("8 papers are
                // waiting"), and capitalising a digit is not a thing.
                assertThat(Character.isLowerCase(line.charAt(0)))
                        .as("<%s> starts lowercase", line).isFalse();
                assertThat(line).isNotEqualTo(line.toUpperCase(java.util.Locale.ROOT));
            });
        }
    }
}
