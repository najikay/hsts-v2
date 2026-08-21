package server.features.bank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import server.db.projections.ReferencingExam;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The copy rules, enforced over every sentence the question bank says (PRD §4.1).
 *
 * <p>A scan rather than one assertion per constant, for the reason {@code ExamMessagesTest} and
 * {@code NotificationCatalogTest} give: a rule that checks only the strings somebody remembered
 * to list is a rule a new string walks past.
 *
 * <h2>One thing this does that its two siblings do not</h2>
 *
 * <p>{@link #allMessages()} scans public String <b>constants</b>, which is what the existing
 * catalogues check. But {@link BankMessages} also builds sentences in <b>methods</b>, because
 * three of its messages have to name a position, an editor or a list of exams. Those are just as
 * user-visible and were escaping the rules entirely, so {@link #allComposedMessages()} feeds
 * sample outputs of every one of them through the same checks.
 *
 * <p>That is a gap in the pattern rather than in this class: {@code ExamMessages.notJoinable} and
 * {@code BotMessages.lockedOut} are composed the same way and are checked only by hand-written
 * cases. Worth lifting into a shared scan later; noted rather than fixed here, because those two
 * files are not mine.
 */
class BankMessagesTest {

    private static final ReferencingExam MIDTERM =
            new ReferencingExam("101101", "Algebra Midterm");
    private static final ReferencingExam FINAL =
            new ReferencingExam("101102", "Algebra Final");

    /** Every public String constant on the catalogue, found by scanning rather than by list. */
    static List<String> allMessages() {
        List<String> messages = new ArrayList<>();
        for (Field field : BankMessages.class.getDeclaredFields()) {
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

    /** A representative output of every message-building method, held to the same rules. */
    static List<String> allComposedMessages() {
        return List.of(
                BankMessages.answerBlank(2),
                BankMessages.answersDuplicated(1, 3),
                BankMessages.lockedBy("Dana Cohen"),
                BankMessages.deleteBlocked(List.of(MIDTERM)),
                BankMessages.deleteBlocked(List.of(MIDTERM, FINAL)),
                BankMessages.deleteBlocked(List.of()),
                BankMessages.textTooLong(4000),
                BankMessages.answerTooLong(2, 500),
                BankMessages.topicTooLong(100));
    }

    /** Both sources at once, for the rules that apply to every sentence without exception. */
    static List<String> everySentence() {
        List<String> all = new ArrayList<>(allMessages());
        all.addAll(allComposedMessages());
        return all;
    }

    @Test
    @DisplayName("the scan really finds the catalogue, so a green run means something")
    void theScanHasTeeth() {
        assertThat(allMessages()).hasSizeGreaterThanOrEqualTo(11);
        assertThat(allComposedMessages()).hasSizeGreaterThanOrEqualTo(8);
    }

    @ParameterizedTest
    @MethodSource("everySentence")
    @DisplayName("no message contains an em dash (PRD §4.1)")
    void noEmDashes(String message) {
        assertThat(message).doesNotContain("—").doesNotContain("–");
    }

    @ParameterizedTest
    @MethodSource("everySentence")
    @DisplayName("no message shouts, and none is a bare word")
    void noShouting(String message) {
        assertThat(message).isNotBlank();
        assertThat(message).isNotEqualTo(message.toUpperCase(Locale.ROOT));
        assertThat(message.split("\\s+").length)
                .as("a one-word error tells nobody anything: %s", message)
                .isGreaterThan(3);
    }

    @ParameterizedTest
    @MethodSource("everySentence")
    @DisplayName("every message is a finished sentence")
    void everyMessageIsASentence(String message) {
        assertThat(message).endsWith(".");
        assertThat(message.charAt(0)).isUpperCase();
    }

    @ParameterizedTest
    @MethodSource("everySentence")
    @DisplayName("every message says what to do next (PRD §4.1)")
    void everyMessageSaysWhatToDoNext(String message) {
        // The imperative half of the sentence. A message with none of these is a dead end,
        // which is the thing the rule exists to stop: "invalid question" leaves a teacher
        // staring at a form with nine fields and no idea which one offended.
        List<String> actions = List.of("try again", "pick ", "ask ", "write ", "mark ",
                "change one", "reopen ", "save ", "remove it", "go back", "fill in",
                "becomes editable", "attach it again");
        assertThat(actions.stream()
                .anyMatch(action -> message.toLowerCase(Locale.ROOT).contains(action)))
                .as("no next step in: %s", message)
                .isTrue();
    }

    @Test
    @DisplayName("the validation refusals are all different sentences (T-2.2)")
    void validationRefusalsAreDistinct() {
        // T-2.2 saves three bad questions in a row and expects three different sentences.
        // Identical copy for different faults is exactly the v1 dead end §4.1 forbids.
        assertThat(List.of(BankMessages.TEXT_REQUIRED,
                        BankMessages.ANSWER_COUNT,
                        BankMessages.CORRECT_ANSWER_RANGE,
                        BankMessages.TOPIC_REQUIRED,
                        BankMessages.DIFFICULTY_REQUIRED,
                        BankMessages.answerBlank(1),
                        BankMessages.answersDuplicated(1, 2)))
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the duplicate-answer message names both positions, not just the fault")
    void duplicateMessageNamesBothPositions() {
        // With four boxes on screen, "two answers are the same" still leaves the teacher
        // comparing them by eye. This is the half of T-2.2 a generic message would fail.
        assertThat(BankMessages.answersDuplicated(2, 4)).contains("2").contains("4");
    }

    @Test
    @DisplayName("a blocked delete names the exams, singular and plural (T-2.7)")
    void blockedDeleteNamesTheExams() {
        assertThat(BankMessages.deleteBlocked(List.of(MIDTERM)))
                .contains("Algebra Midterm")
                .contains("101101")
                .contains("that exam");

        String many = BankMessages.deleteBlocked(List.of(MIDTERM, FINAL));
        assertThat(many)
                .contains("Algebra Midterm")
                .contains("Algebra Final")
                .contains("2 exams")
                .contains("those exams");
    }

    @Test
    @DisplayName("two exams sharing a name are still told apart, by id (T-2.7)")
    void sameNamedExamsAreDistinguishable() {
        // The audit's finding. findReferencingExams de-duplicates per exam so the dialog cannot
        // say "Algebra Midterm, Algebra Midterm" for one exam pinned in two versions. Two
        // genuinely different exams that share a name across terms would have reintroduced the
        // same unreadable sentence one layer up, because the old signature took names only.
        String message = BankMessages.deleteBlocked(List.of(
                new ReferencingExam("101101", "Algebra Midterm"),
                new ReferencingExam("101102", "Algebra Midterm")));

        assertThat(message).contains("101101").contains("101102");
    }

    @Test
    @DisplayName("an empty blocker list does not produce '0 exams use it: .'")
    void emptyBlockerListIsStillASentence() {
        // Unreachable while deleted=false implies a non-empty list, but that invariant lives in
        // a service that does not exist yet, and the guard is one line.
        assertThat(BankMessages.deleteBlocked(List.of()))
                .doesNotContain("0 exams")
                .doesNotContain(": .");
    }

    @Test
    @DisplayName("the lock message names the editor, which is the whole point of F10.0")
    void lockMessageNamesTheEditor() {
        assertThat(BankMessages.lockedBy("Rina Barak")).contains("Rina Barak");
    }

    @Test
    @DisplayName("the not-taught refusal does not leak who does teach the course")
    void courseRefusalDoesNotLeakTheRoster() {
        // A teacher who is not on a course has no business learning its roster from an error
        // message. Her next step is the same either way, so the sentence costs nothing.
        assertThat(BankMessages.COURSE_NOT_TAUGHT)
                .doesNotContain("taught by")
                .contains("courses you teach");
    }
}
