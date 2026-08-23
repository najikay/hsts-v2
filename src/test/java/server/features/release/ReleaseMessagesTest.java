package server.features.release;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import server.db.entities.ExecutionStatus;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The copy rules, enforced over every sentence the release manager says (PRD §4.1).
 *
 * <p>A scan rather than one assertion per constant, for the reason {@code ExamMessagesTest}
 * is one: a rule that only checks the strings somebody remembered to list is a rule a new
 * string can walk past. Anything added to {@link ReleaseMessages} is checked the moment it is
 * added.
 */
class ReleaseMessagesTest {

    /** Every public String constant on the catalogue, found by scanning rather than by list. */
    static List<String> allMessages() {
        List<String> messages = new ArrayList<>();
        for (Field field : ReleaseMessages.class.getDeclaredFields()) {
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
        assertThat(allMessages()).hasSizeGreaterThanOrEqualTo(8);
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
        assertThat(message).isNotEqualTo(message.toUpperCase(Locale.ROOT));
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
        // The imperative half of the sentence. A message with none of these is a dead end,
        // which is the thing the rule exists to stop.
        List<String> actions = List.of("try again", "ask ", "open ", "use close early",
                "cancel it instead", "pick ", "release it", "then release");
        assertThat(actions.stream().anyMatch(action ->
                message.toLowerCase(Locale.ROOT).contains(action)))
                .as("no next step in: %s", message)
                .isTrue();
    }

    @Test
    @DisplayName("⚑ the F5.1 refusal names the rule and the person who unblocks it")
    void unapprovedRefusalIsUseful() {
        assertThat(ReleaseMessages.VERSION_NOT_APPROVED)
                .contains("approved")
                .contains("coordinator");
    }

    @Test
    @DisplayName("the two impossible actions point at each other rather than saying no")
    void refusalsNameTheOtherButton() {
        assertThat(ReleaseMessages.CANCEL_NOT_SCHEDULED).contains("close early");
        assertThat(ReleaseMessages.CLOSE_NOT_LIVE).contains("Cancel it");
    }

    @Test
    @DisplayName("a live release is told to close early, a finished one that there is nothing to do")
    void cannotCancelPicksTheRightSentence() {
        assertThat(ReleaseMessages.cannotCancel(ExecutionStatus.LIVE))
                .isEqualTo(ReleaseMessages.CANCEL_NOT_SCHEDULED);
        assertThat(ReleaseMessages.cannotCancel(ExecutionStatus.CLOSED))
                .isEqualTo(ReleaseMessages.CANCEL_ALREADY_OVER);
        assertThat(ReleaseMessages.cannotCancel(ExecutionStatus.CANCELLED))
                .isEqualTo(ReleaseMessages.CANCEL_ALREADY_OVER);
    }

    @Test
    @DisplayName("the refusals are distinct sentences, because their fixes are distinct")
    void refusalsAreDistinct() {
        assertThat(List.of(ReleaseMessages.VERSION_NOT_APPROVED, ReleaseMessages.VERSION_UNKNOWN,
                        ReleaseMessages.RELEASE_UNKNOWN, ReleaseMessages.CANCEL_NOT_SCHEDULED,
                        ReleaseMessages.CANCEL_ALREADY_OVER, ReleaseMessages.CLOSE_NOT_LIVE))
                .doesNotHaveDuplicates();
    }
}
