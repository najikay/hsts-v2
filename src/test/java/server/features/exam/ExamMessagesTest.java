package server.features.exam;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import server.db.entities.ExecutionStatus;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The copy rules, enforced over every sentence take-exam says (PRD §4.1).
 *
 * <p>Written as a scan rather than as one assertion per constant, for the same reason
 * {@code NotificationCatalogTest} is: a rule that only checks the strings somebody
 * remembered to list is a rule that a new string can walk past. Anything added to
 * {@link ExamMessages} is checked the moment it is added.
 *
 * <p>The rules themselves come straight from the PRD: no em dashes anywhere in
 * user-visible text, sentence case rather than shouting, and — the one that matters most in
 * an exam hall — <b>every error says what the reader can do next</b>. A student refused at
 * the code screen with "invalid" loses minutes she cannot get back.
 */
class ExamMessagesTest {

    /** Every public String constant on the catalogue, found by scanning rather than by list. */
    static List<String> allMessages() {
        List<String> messages = new ArrayList<>();
        for (Field field : ExamMessages.class.getDeclaredFields()) {
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
        assertThat(allMessages()).hasSizeGreaterThanOrEqualTo(14);
    }

    @ParameterizedTest
    @MethodSource("allMessages")
    @DisplayName("no message contains an em dash (PRD §4.1)")
    void noEmDashes(String message) {
        assertThat(message).doesNotContain("—").doesNotContain("–");
    }

    @ParameterizedTest
    @MethodSource("allMessages")
    @DisplayName("no message shouts, and none is a bare word")
    void noShouting(String message) {
        assertThat(message).isNotBlank();
        assertThat(message).isNotEqualTo(message.toUpperCase(java.util.Locale.ROOT));
        assertThat(message.split("\\s+").length)
                .as("a one-word error tells nobody anything: %s", message)
                .isGreaterThan(3);
    }

    @ParameterizedTest
    @MethodSource("allMessages")
    @DisplayName("every message is a finished sentence")
    void everyMessageIsASentence(String message) {
        assertThat(message).endsWith(".");
        assertThat(message.charAt(0)).isUpperCase();
    }

    @ParameterizedTest
    @MethodSource("allMessages")
    @DisplayName("every message says what to do next (PRD §4.1)")
    void everyMessageSaysWhatToDoNext(String message) {
        // The imperative half of the sentence: "try again", "speak to your teacher",
        // "wait", "reload", "go back", "enter", "pick", "open", "ask", "check". A message
        // that has none of these is a dead end, which is the thing the rule exists to stop.
        List<String> actions = List.of("try again", "speak to", "wait for", "reload",
                "go back", "enter ", "pick ", "open ", "ask ", "check ", "will publish",
                "cannot be extended", "is not running", "was handed in", "see the current time");
        assertThat(actions.stream().anyMatch(action -> message.toLowerCase(java.util.Locale.ROOT)
                .contains(action)))
                .as("no next step in: %s", message)
                .isTrue();
    }

    @Test
    @DisplayName("the four entry refusals are four different sentences (PRD §6)")
    void entryRefusalsAreDistinct() {
        // The whole point of E10.9: a student in an exam hall must be told which of these
        // four is actually wrong, because the fix is different for each.
        assertThat(List.of(ExamMessages.CODE_UNKNOWN, ExamMessages.CODE_NOT_OPEN_YET,
                        ExamMessages.CODE_CLOSED, ExamMessages.NOT_ENROLLED,
                        ExamMessages.ID_MISMATCH))
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a scheduled execution is 'not open yet', a closed one is 'no longer open'")
    void notJoinablePicksTheRightSentence() {
        assertThat(ExamMessages.notJoinable(ExecutionStatus.SCHEDULED, false))
                .isEqualTo(ExamMessages.CODE_NOT_OPEN_YET);
        assertThat(ExamMessages.notJoinable(ExecutionStatus.SCHEDULED, true))
                .isEqualTo(ExamMessages.CODE_NOT_OPEN_YET);
        assertThat(ExamMessages.notJoinable(ExecutionStatus.CLOSED, true))
                .isEqualTo(ExamMessages.CODE_CLOSED);
        assertThat(ExamMessages.notJoinable(ExecutionStatus.CANCELLED, true))
                .isEqualTo(ExamMessages.CODE_CLOSED);
    }

    @Test
    @DisplayName("a live execution whose window has not opened is still 'not open yet'")
    void liveButNotOpenYet() {
        assertThat(ExamMessages.notJoinable(ExecutionStatus.LIVE, false))
                .isEqualTo(ExamMessages.CODE_NOT_OPEN_YET);
    }

    @Test
    @DisplayName("the time-up sentence tells her what the server did, not only that she failed")
    void timeUpExplainsTheOutcome() {
        assertThat(ExamMessages.TIME_IS_UP)
                .contains("handed in automatically")
                .contains("saved");
    }
}
