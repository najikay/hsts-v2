package client.features.release;

import common.dto.exam.MonitorCounts;
import common.dto.release.ReleaseRow;
import common.dto.release.ReleaseState;
import common.protocol.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every sentence the Release Manager says, checked against the copy rules (PRD §4.1).
 *
 * <p>A scan rather than a list, for the reason {@code ExamCopyTest} is one: a rule that only
 * checks the strings somebody remembered to enumerate is a rule a new string walks past.
 *
 * <p>The two confirmation explanations get their own tests, because they are the sentences a
 * teacher reads before doing something irreversible to other people's exams, and each has to
 * say what will actually happen rather than that it cannot be undone.
 */
class ReleaseCopyTest {

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");
    private static final ZoneId UTC = ZoneId.of("UTC");

    /** Every public String constant on the catalogue, found by scanning. */
    static List<String> allMessages() {
        List<String> messages = new ArrayList<>();
        for (Field field : ReleaseCopy.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers())
                    && field.getType() == String.class) {
                try {
                    messages.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("could not read " + field.getName(), e);
                }
            }
        }
        return messages;
    }

    @Test
    @DisplayName("the scan really finds the catalogue, so a green run means something")
    void theScanHasTeeth() {
        assertThat(allMessages()).hasSizeGreaterThanOrEqualTo(20);
    }

    @ParameterizedTest
    @MethodSource("allMessages")
    @DisplayName("no message contains an em dash (PRD §4.1)")
    void noEmDashes(String message) {
        assertThat(message).doesNotContain("—").doesNotContain("–");
    }

    @ParameterizedTest
    @MethodSource("allMessages")
    @DisplayName("nothing shouts")
    void noShouting(String message) {
        assertThat(message).isNotBlank();
        assertThat(message).isNotEqualTo(message.toUpperCase(Locale.ROOT));
    }

    @ParameterizedTest
    @MethodSource("allMessages")
    @DisplayName("sentence case: a label starts with a capital and is not Title Case")
    void sentenceCase(String message) {
        assertThat(message.charAt(0)).isUpperCase();
    }

    @Nested
    @DisplayName("the two confirmations (F5.5)")
    class Confirmations {

        @Test
        @DisplayName("cancelling says nothing is lost, because nothing is")
        void cancelExplanationIsProportionate() {
            String explanation = ReleaseCopy.cancelExplanation(row(ReleaseState.SCHEDULED, 0, 0, 0));

            assertThat(explanation)
                    .contains("will not open")
                    .contains("nothing is lost")
                    .contains("reports");
            assertThat(explanation).doesNotContain("—");
        }

        @Test
        @DisplayName("⚑ closing early states F5.5's behaviour in the students' own terms")
        void closeExplanationStatesTheBehaviour() {
            String explanation = ReleaseCopy.closeExplanation(row(ReleaseState.LIVE, 12, 4, 0));

            // The behaviour statement is F5.5's own sentence, and it is what the teacher is
            // actually deciding about: not "the exam ends", but "eight papers are handed in".
            assertThat(explanation)
                    .contains("8 students are working")
                    .contains("handed in immediately")
                    .contains("as if her time had run out")
                    .contains("cannot be undone");
        }

        @Test
        @DisplayName("one student working does not read as '1 students are'")
        void singularStudent() {
            assertThat(ReleaseCopy.closeExplanation(row(ReleaseState.LIVE, 1, 0, 0)))
                    .contains("1 student is working");
        }

        @Test
        @DisplayName("nobody working is said plainly rather than as a zero")
        void nobodyWorking() {
            assertThat(ReleaseCopy.closeExplanation(row(ReleaseState.LIVE, 3, 3, 0)))
                    .startsWith("Nobody is working on it right now.");
        }

        @Test
        @DisplayName("the close-early title names the exam, so there is no doubt which one")
        void closeTitleNamesTheExam() {
            assertThat(ReleaseCopy.closeTitle(row(ReleaseState.LIVE, 1, 0, 0)))
                    .isEqualTo("Close Midterm now?");
        }
    }

    @Nested
    @DisplayName("the line under each row")
    class StatusLine {

        @Test
        @DisplayName("a scheduled release counts down to its opening")
        void scheduled() {
            ReleaseRow row = row(ReleaseState.SCHEDULED, 0, 0, 0);

            assertThat(ReleaseCopy.status(row, NOW, UTC))
                    .startsWith("Opens in 1 hour")
                    .contains("10:00");
        }

        @Test
        @DisplayName("a live release says how long is left and how many are still working")
        void live() {
            ReleaseRow row = row(ReleaseState.LIVE, 12, 4, 1);

            assertThat(ReleaseCopy.status(row, NOW.plus(Duration.ofMinutes(70)), UTC))
                    .isEqualTo("50 minutes left, 7 still working");
        }

        @Test
        @DisplayName("a closed release reports what happened, in three numbers")
        void closed() {
            ReleaseRow row = row(ReleaseState.CLOSED, 12, 8, 4);

            assertThat(ReleaseCopy.status(row, NOW, UTC))
                    .isEqualTo("12 sat it, 8 handed in, 4 ran out of time");
        }

        @Test
        @DisplayName("a closed release nobody sat says so rather than printing three zeros")
        void closedAndEmpty() {
            assertThat(ReleaseCopy.status(row(ReleaseState.CLOSED, 0, 0, 0), NOW, UTC))
                    .isEqualTo("Nobody sat this one");
        }

        @Test
        @DisplayName("a cancelled release says why it will not appear in reports (PRD §6)")
        void cancelled() {
            assertThat(ReleaseCopy.status(row(ReleaseState.CANCELLED, 0, 0, 0), NOW, UTC))
                    .contains("Cancelled").contains("left out of reports");
        }
    }

    @Nested
    @DisplayName("durations in the words a person uses")
    class Durations {

        @Test
        @DisplayName("days, hours and minutes, each singular when it is one")
        void plurals() {
            assertThat(ReleaseCopy.humanDuration(Duration.ofDays(2))).isEqualTo("2 days");
            assertThat(ReleaseCopy.humanDuration(Duration.ofDays(1))).isEqualTo("1 day");
            assertThat(ReleaseCopy.humanDuration(Duration.ofHours(3))).isEqualTo("3 hours");
            assertThat(ReleaseCopy.humanDuration(Duration.ofHours(1))).isEqualTo("1 hour");
            assertThat(ReleaseCopy.humanDuration(Duration.ofMinutes(25))).isEqualTo("25 minutes");
            assertThat(ReleaseCopy.humanDuration(Duration.ofMinutes(1))).isEqualTo("1 minute");
        }

        @Test
        @DisplayName("anything under a minute, and anything already past, reads as 'moments'")
        void momentsRatherThanZero() {
            assertThat(ReleaseCopy.humanDuration(Duration.ofSeconds(20))).isEqualTo("moments");
            assertThat(ReleaseCopy.humanDuration(Duration.ofMinutes(-5))).isEqualTo("moments");
            assertThat(ReleaseCopy.humanDuration(null)).isEqualTo("moments");
        }
    }

    @Nested
    @DisplayName("times and windows")
    class Times {

        @Test
        @DisplayName("the window reads as one line in the viewer's own zone")
        void windowLine() {
            assertThat(ReleaseCopy.window(row(ReleaseState.SCHEDULED, 0, 0, 0), UTC))
                    .isEqualTo("20 Aug, 10:00 to 11:00");
        }

        @Test
        @DisplayName("a missing instant renders as nothing, never as the word null")
        void nullInstants() {
            assertThat(ReleaseCopy.localMoment(null, UTC)).isEmpty();
            assertThat(ReleaseCopy.localTime(null, UTC)).isEmpty();
        }
    }

    @Test
    @DisplayName("⚑ the server's own sentence wins over anything this tier could invent")
    void serverSentenceWins() {
        assertThat(ReleaseCopy.serverMessage(ErrorCode.CONFLICT, "Use close early to end it now.",
                ReleaseCopy.OFFLINE)).isEqualTo("Use close early to end it now.");
        // Two copies of one refusal in two tiers is how one of them ends up wrong.
        assertThat(ReleaseCopy.serverMessage(ErrorCode.INTERNAL, "  ", ReleaseCopy.OFFLINE))
                .isEqualTo(ReleaseCopy.OFFLINE);
        assertThat(ReleaseCopy.serverMessage(null, null, ReleaseCopy.OFFLINE))
                .isEqualTo(ReleaseCopy.OFFLINE);
    }

    @Test
    @DisplayName("the code panel says why it exists: students never see this code (S-17)")
    void codeCopyExplainsItself() {
        assertThat(ReleaseCopy.CODE_BODY)
                .contains("never see this code")
                .contains("say it out loud");
    }

    @Test
    @DisplayName("the two empty pickers send the teacher to two different places")
    void emptyPickersDiffer() {
        assertThat(ReleaseCopy.NONE_APPROVED).contains("coordinator");
        assertThat(ReleaseCopy.NONE_WRITTEN).contains("Build one first");
        assertThat(ReleaseCopy.NONE_APPROVED).isNotEqualTo(ReleaseCopy.NONE_WRITTEN);
    }

    private static ReleaseRow row(ReleaseState state, long started, long finished, long timedOut) {
        return new ReleaseRow(5001, 7001, "Midterm", "11", "Algebra 11", "4B7Q",
                NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(2)), 0, 45,
                state, new MonitorCounts(started, finished, timedOut));
    }
}
