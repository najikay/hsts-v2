package client.features.exam;

import common.dto.exam.AttemptOutcome;
import common.dto.exam.AttemptState;
import common.dto.exam.AttemptTiming;
import common.dto.exam.TimerExtended;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The copy rules, over every sentence the take-exam screens say (PRD §4.1).
 *
 * <p>The client half of the same scan {@code ExamMessagesTest} runs on the server: no em
 * dashes anywhere in user-visible text, and nothing that reads like a dead end. A student
 * mid-exam is the least patient reader in the product.
 *
 * <p>{@link ExamClock} is tested alongside it because it produces user-visible text too:
 * the "new end 11:45" in the extension toast and the "handed in at 11:45" on the Submitted
 * screen come out of the same formatter, and an hour's difference between them would be a
 * real bug on a demo machine in a different zone.
 */
class ExamCopyTest {

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");
    private static final ZoneId JERUSALEM = ZoneId.of("Asia/Jerusalem");

    /** Every public String constant on the copy class, found by scanning rather than by list. */
    static List<String> allCopy() {
        List<String> copy = new ArrayList<>();
        for (Field field : ExamCopy.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers())
                    && field.getType() == String.class) {
                try {
                    copy.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("could not read " + field.getName(), e);
                }
            }
        }
        return copy;
    }

    @Test
    @DisplayName("the scan really finds the copy, so a green run means something")
    void theScanHasTeeth() {
        assertThat(allCopy()).hasSizeGreaterThanOrEqualTo(20);
    }

    @ParameterizedTest
    @MethodSource("allCopy")
    @DisplayName("no line contains an em dash (PRD §4.1)")
    void noEmDashes(String line) {
        assertThat(line).doesNotContain("—").doesNotContain("–");
    }

    @ParameterizedTest
    @MethodSource("allCopy")
    @DisplayName("no line is blank or shouting")
    void noBlanksOrShouting(String line) {
        assertThat(line).isNotBlank();
        assertThat(line).isNotEqualTo(line.toUpperCase(java.util.Locale.ROOT));
    }

    @Nested
    @DisplayName("composed sentences")
    class Composed {

        @Test
        @DisplayName("the progress line reads as a sentence, not as a fraction")
        void progressLine() {
            assertThat(ExamCopy.progress(7, 20)).isEqualTo("Answered 7 of 20");
            assertThat(ExamCopy.progress(0, 0)).isEqualTo("Answered 0 of 0");
        }

        @Test
        @DisplayName("the submit note changes with what is blank (F6.9)")
        void submitNote() {
            assertThat(ExamCopy.submitNote(0)).isEqualTo(ExamCopy.SUBMIT_COMPLETE_NOTE);
            assertThat(ExamCopy.submitNote(3)).isEqualTo(ExamCopy.SUBMIT_UNANSWERED_NOTE);
            assertThat(ExamCopy.submitNote(3)).contains("score 0");
        }

        @Test
        @DisplayName("the remaining-time note names the time she is giving up")
        void remainingNote() {
            assertThat(ExamCopy.remainingNote(Duration.ofMinutes(12)))
                    .isEqualTo("You still have 12 minutes left.");
            assertThat(ExamCopy.remainingNote(Duration.ofMinutes(1)))
                    .isEqualTo("You still have 1 minute left.");
            assertThat(ExamCopy.remainingNote(Duration.ofSeconds(20)))
                    .isEqualTo("You still have less than a minute left.");
        }

        @Test
        @DisplayName("the extension toast names the teacher and the new end time (F7.1 ⚑)")
        void extensionToast() {
            TimerExtended extension = new TimerExtended(5001, "Java Midterm", "Dana Cohen", 15,
                    new AttemptTiming(NOW, NOW.plus(Duration.ofMinutes(60)),
                            Duration.ofMinutes(60).toMillis(), Duration.ofMinutes(60).toMillis()));

            String toast = ExamCopy.extensionToast(extension);

            // The whole requirement in one line: it happened, who did it, when it now ends.
            assertThat(toast).contains("Dana Cohen").contains("15 minutes").contains("new end");
            assertThat(toast).doesNotContain("—");
        }

        @Test
        @DisplayName("the ending titles and subtitles differ by mood, not by layout")
        void endingCopy() {
            assertThat(ExamCopy.endingTitle(AttemptState.TIMED_OUT)).isEqualTo(ExamCopy.TIMED_OUT_TITLE);
            assertThat(ExamCopy.endingTitle(AttemptState.SUBMITTED)).isEqualTo(ExamCopy.SUBMITTED_TITLE);
            assertThat(ExamCopy.endingSubtitle(AttemptState.TIMED_OUT))
                    .isEqualTo(ExamCopy.TIMED_OUT_SUBTITLE);
            assertThat(ExamCopy.endingSubtitle(AttemptState.SUBMITTED))
                    .isEqualTo(ExamCopy.SUBMITTED_SUBTITLE);
        }

        @Test
        @DisplayName("the takeover explains that it already happened (F6.4)")
        void takeoverExplainsItself() {
            // No confirmation is asked because there is nothing left to confirm, so the
            // subtitle has to carry the whole explanation.
            assertThat(ExamCopy.TIMED_OUT_SUBTITLE)
                    .contains("automatically")
                    .contains("saved");
        }

        @Test
        @DisplayName("the outcome summary names the time, the minutes and the count")
        void outcomeSummary() {
            AttemptOutcome outcome = new AttemptOutcome(42, AttemptState.SUBMITTED, "Java Midterm",
                    NOW.plus(Duration.ofMinutes(43)), 43, 18, 20, List.of());

            String summary = ExamCopy.outcomeSummary(outcome);

            assertThat(summary).contains("43 minutes").contains("18 of 20 answered");
        }

        @Test
        @DisplayName("one minute never reads as '1 minutes'")
        void pluralisation() {
            assertThat(ExamCopy.minutes(1)).isEqualTo("1 minute");
            assertThat(ExamCopy.minutes(15)).isEqualTo("15 minutes");
            assertThat(ExamCopy.minutes(0)).isEqualTo("0 minutes");
        }
    }

    @Nested
    @DisplayName("server sentences win")
    class ServerSentences {

        @Test
        @DisplayName("the server's own text is preferred whenever there is any")
        void serverTextWins() {
            assertThat(ExamCopy.serverMessage(ErrorCode.NOT_FOUND,
                    "No exam is using that code.", "fallback"))
                    .isEqualTo("No exam is using that code.");
        }

        @Test
        @DisplayName("a refusal with no text falls back rather than showing nothing")
        void emptyTextFallsBack() {
            assertThat(ExamCopy.serverMessage(ErrorCode.NOT_FOUND, "  ", "fallback"))
                    .isEqualTo("fallback");
            assertThat(ExamCopy.serverMessage(ErrorCode.NOT_FOUND, null, "fallback"))
                    .isEqualTo("fallback");
        }

        @Test
        @DisplayName("no code at all means the round trip failed, not that the request was refused")
        void noCodeMeansOffline() {
            assertThat(ExamCopy.serverMessage(null, null, "fallback")).isEqualTo(ExamCopy.OFFLINE);
        }
    }

    @Nested
    @DisplayName("the clock's words")
    class Clock {

        @Test
        @DisplayName("an instant renders as local wall-clock time")
        void localTime() {
            // 09:00 UTC is 12:00 in Jerusalem in August. A student compares this against the
            // clock on the wall, so it has to be her time and not the server's.
            assertThat(ExamClock.localTime(NOW, JERUSALEM)).isEqualTo("12:00");
            assertThat(ExamClock.localTime(NOW, ZoneId.of("UTC"))).isEqualTo("09:00");
        }

        @Test
        @DisplayName("a missing instant renders as nothing, never as the word null")
        void nullInstant() {
            assertThat(ExamClock.localTime(null, JERUSALEM)).isEmpty();
            assertThat(ExamClock.localTime(null)).isEmpty();
        }

        @Test
        @DisplayName("durations read as words, rounded down")
        void durationsInWords() {
            assertThat(ExamClock.words(Duration.ofMinutes(12))).isEqualTo("12 minutes");
            assertThat(ExamClock.words(Duration.ofMinutes(1))).isEqualTo("1 minute");
            assertThat(ExamClock.words(Duration.ofSeconds(90))).isEqualTo("1 minute");
            assertThat(ExamClock.words(Duration.ofHours(1))).isEqualTo("1 hour");
            assertThat(ExamClock.words(Duration.ofMinutes(65))).isEqualTo("1 hour 5 minutes");
            assertThat(ExamClock.words(Duration.ofMinutes(125))).isEqualTo("2 hours 5 minutes");
        }

        @Test
        @DisplayName("under a minute says so rather than rounding to zero")
        void lessThanAMinute() {
            assertThat(ExamClock.words(Duration.ofSeconds(40))).isEqualTo("less than a minute");
            assertThat(ExamClock.words(Duration.ZERO)).isEqualTo("less than a minute");
            assertThat(ExamClock.words(Duration.ofMinutes(-5))).isEqualTo("less than a minute");
            assertThat(ExamClock.words(null)).isEqualTo("less than a minute");
        }

        @Test
        @DisplayName("the at() shortcut uses the reader's own zone")
        void atUsesTheDefaultZone() {
            assertThat(ExamCopy.at(NOW)).isEqualTo(ExamClock.localTime(NOW));
        }
    }
}
