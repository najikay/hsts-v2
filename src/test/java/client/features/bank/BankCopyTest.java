package client.features.bank;

import common.dto.bank.BankQuestionRow;
import common.dto.bank.BlockingExam;
import common.dto.bank.Difficulty;
import common.dto.bank.QuestionDetail;
import common.dto.bank.QuestionVersionDetail;
import common.dto.lock.LockHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BankCopy} — every sentence the bank screen prints, checked without a toolkit.
 *
 * <p>The point of a Copy class is that the screen's words are testable. So the tests that matter
 * here are the ones about <b>what a sentence has to contain to be usable</b>: the exams a
 * refusal names (T-2.7), the version numbers an indicator names (F2.3), and the difference
 * between the two empty panels.
 */
class BankCopyTest {

    private static final Instant WHEN = Instant.parse("2026-03-10T07:00:00Z");

    private static QuestionDetail detail(int versionNo, int latest) {
        return new QuestionDetail("11005", "11", "אלגברה", versionNo, latest, "Read the diagram",
                List.of("One", "Two", "Three", "Four"), 2, "Geometry", Difficulty.HARD, false,
                "דנה כהן", WHEN);
    }

    private static QuestionVersionDetail version(int no, String text, List<String> answers,
                                                 int correct, String topic,
                                                 Difficulty difficulty, boolean hasImage) {
        return new QuestionVersionDetail(no, text, answers, correct, topic, difficulty, hasImage,
                "דנה כהן", WHEN);
    }

    private static final List<String> FOUR = List.of("One", "Two", "Three", "Four");

    // ===================== The list =======================================

    @Nested
    @DisplayName("the count line")
    class CountLine {

        @Test
        @DisplayName("says both numbers whenever the page is not the whole result")
        void bothNumbers() {
            assertThat(BankCopy.countLine(40, 137, false))
                    .isEqualTo("Showing 40 of 137 questions");
        }

        @Test
        @DisplayName("says one number when the page is the whole result")
        void oneNumber() {
            assertThat(BankCopy.countLine(3, 3, false)).isEqualTo("3 questions");
        }

        @Test
        @DisplayName("says 'match' only when something is narrowing the list")
        void filteredSaysMatch() {
            assertThat(BankCopy.countLine(3, 3, true)).isEqualTo("3 questions match");
            assertThat(BankCopy.countLine(3, 3, false)).doesNotContain("match");
        }

        @Test
        @DisplayName("one question is singular")
        void singular() {
            assertThat(BankCopy.countLine(1, 1, false)).isEqualTo("1 question");
        }

        @Test
        @DisplayName("nothing at all reads differently depending on why")
        void zero() {
            assertThat(BankCopy.countLine(0, 0, false)).isEqualTo("No questions yet");
            assertThat(BankCopy.countLine(0, 0, true)).isEqualTo("No questions match");
        }
    }

    @Test
    @DisplayName("the page line counts from one, because the wire counting from zero is ours")
    void pageLineIsOneBased() {
        assertThat(BankCopy.pageLine(0, 3)).isEqualTo("Page 1 of 3");
        assertThat(BankCopy.pageLine(2, 3)).isEqualTo("Page 3 of 3");
    }

    @Test
    @DisplayName("an empty bank still has a page, so the line never reads 'of 0'")
    void pageLineOnAnEmptyBank() {
        assertThat(BankCopy.pageLine(0, 0)).isEqualTo("Page 1 of 1");
    }

    @Test
    @DisplayName("the two empty panels say different things and both say what would fix them")
    void emptyPanelsDiffer() {
        assertThat(BankCopy.NO_QUESTIONS.title()).isNotEqualTo(BankCopy.NO_MATCHES.title());
        assertThat(BankCopy.NO_MATCHES.hint()).contains("Clear");
        assertThat(BankCopy.NO_QUESTIONS.hint()).contains("appear here");
    }

    @Test
    @DisplayName("a question with no topic still renders as something")
    void topicFallback() {
        assertThat(BankCopy.topic(null)).isEqualTo("No topic");
        assertThat(BankCopy.topic("  ")).isEqualTo("No topic");
        assertThat(BankCopy.topic("Geometry")).isEqualTo("Geometry");
    }

    @ParameterizedTest
    @EnumSource(Difficulty.class)
    @DisplayName("difficulty reads the same word here as the chip on the row")
    void difficultyMatchesTheChip(Difficulty difficulty) {
        String label = BankCopy.difficulty(difficulty);

        assertThat(label)
                .as("the picker entry and the chip both come from ChipCatalog, so they cannot "
                        + "disagree about what MEDIUM is called")
                .isEqualTo(client.ui.components.logic.ChipCatalog
                        .forDifficulty(difficulty.name()).label());
        assertThat(label).isNotBlank();
    }

    @Test
    @DisplayName("no difficulty is the picker's 'any' entry")
    void difficultyNullIsAny() {
        assertThat(BankCopy.difficulty(null)).isEqualTo(BankCopy.ALL_DIFFICULTIES);
    }

    @Test
    @DisplayName("the id is shown the way the teacher reads it")
    void questionId() {
        BankQuestionRow row = new BankQuestionRow("11005", "11", "אלגברה", "text", "Geometry",
                Difficulty.HARD, 702L, 2, false, WHEN);

        assertThat(BankCopy.questionId(row)).isEqualTo("#11005");
        assertThat(BankCopy.questionId(null)).isEmpty();
    }

    @Test
    @DisplayName("a missing instant prints nothing rather than the epoch")
    void nullDates() {
        assertThat(BankCopy.rowDate(null)).isEmpty();
        assertThat(BankCopy.stamp(null)).isEmpty();
        assertThat(BankCopy.rowDate(WHEN)).isNotBlank();
    }

    // ===================== The detail pane ================================

    @Test
    @DisplayName("the version line says when she is not on the newest version (F2.3)")
    void versionLineWarnsAboutOldVersions() {
        assertThat(BankCopy.versionLine(detail(3, 3)))
                .isEqualTo("Version 3, the newest");
        assertThat(BankCopy.versionLine(detail(2, 3)))
                .as("editing an old version is how a teacher quietly reverts the newest, so she "
                        + "has to be told before she does it")
                .isEqualTo("Version 2 of 3, not the newest");
    }

    @Test
    @DisplayName("answers are labelled one-based, the same numbering as the wire (C-8)")
    void answerLabelsAreOneBased() {
        assertThat(BankCopy.answerLabel(1)).isEqualTo("Answer 1");
        assertThat(BankCopy.answerLabel(4)).isEqualTo("Answer 4");
    }

    @Test
    @DisplayName("an author with no name still produces a sentence")
    void authorFallback() {
        QuestionDetail anonymous = new QuestionDetail("11005", "11", "אלגברה", 1, 1, "text",
                FOUR, 1, "Geometry", Difficulty.HARD, false, "", WHEN);

        assertThat(BankCopy.writtenBy(anonymous)).contains("an unnamed author");
        assertThat(BankCopy.writtenBy(detail(1, 1))).contains("דנה כהן");
    }

    // ===================== Version history (E6.12) ========================

    @Nested
    @DisplayName("the change summary")
    class ChangeSummary {

        private final QuestionVersionDetail base =
                version(1, "Read the diagram", FOUR, 2, "Geometry", Difficulty.HARD, false);

        @Test
        @DisplayName("the oldest version is named as the first, not as an empty diff")
        void firstVersion() {
            assertThat(BankCopy.changeSummary(base, null)).isEqualTo("The first version.");
        }

        @Test
        @DisplayName("each field that moved is named")
        void namesEachField() {
            QuestionVersionDetail newer = version(2, "Read the new diagram",
                    List.of("A", "B", "C", "D"), 3, "Shapes", Difficulty.MEDIUM, true);

            String summary = BankCopy.changeSummary(newer, base);

            assertThat(summary).contains("the question", "the answers",
                    "which answer is correct", "the topic", "the difficulty",
                    "an illustration was added");
        }

        @Test
        @DisplayName("only what moved is named")
        void namesOnlyWhatMoved() {
            QuestionVersionDetail newer =
                    version(2, "Read the diagram", FOUR, 3, "Geometry", Difficulty.HARD, false);

            String summary = BankCopy.changeSummary(newer, base);

            assertThat(summary).isEqualTo("Changed: which answer is correct.");
        }

        @Test
        @DisplayName("a removed illustration reads differently from an added one")
        void removalIsItsOwnPhrase() {
            QuestionVersionDetail had =
                    version(1, "Read the diagram", FOUR, 2, "Geometry", Difficulty.HARD, true);
            QuestionVersionDetail lost =
                    version(2, "Read the diagram", FOUR, 2, "Geometry", Difficulty.HARD, false);

            assertThat(BankCopy.changeSummary(lost, had))
                    .contains("the illustration was removed");
        }

        @Test
        @DisplayName("a re-save that changed nothing says so rather than listing nothing")
        void nothingChanged() {
            QuestionVersionDetail same =
                    version(2, "Read the diagram", FOUR, 2, "Geometry", Difficulty.HARD, false);

            assertThat(BankCopy.changeSummary(same, base))
                    .isEqualTo("Nothing on the question itself changed.");
        }

        @Test
        @DisplayName("two changes are joined with 'and', three with commas and an 'and'")
        void joins() {
            QuestionVersionDetail two =
                    version(2, "New text", FOUR, 2, "Shapes", Difficulty.HARD, false);

            assertThat(BankCopy.changeSummary(two, base))
                    .isEqualTo("Changed: the question and the topic.");
        }
    }

    @Test
    @DisplayName("the timeline marks the current version and nothing else")
    void historyEntryMarksCurrent() {
        QuestionVersionDetail newest =
                version(3, "text", FOUR, 1, "Geometry", Difficulty.HARD, false);
        QuestionVersionDetail older =
                version(2, "text", FOUR, 1, "Geometry", Difficulty.HARD, false);

        assertThat(BankCopy.historyEntry(newest, 3)).contains("Version 3", "(current)");
        assertThat(BankCopy.historyEntry(older, 3)).contains("Version 2")
                .doesNotContain("(current)");
    }

    // ===================== Delete (T-2.7) =================================

    @Nested
    @DisplayName("the delete dialogs")
    class DeleteDialogs {

        @Test
        @DisplayName("the refusal names every exam, by name and by id ⚑")
        void refusalNamesTheExams() {
            List<BlockingExam> exams = List.of(
                    new BlockingExam("101101", "מבחן אמצע: אלגברה"),
                    new BlockingExam("101102", "בוחן: משוואות"),
                    new BlockingExam("101103", "מבחן מסכם"));

            String body = BankCopy.deleteBlockedBody("11005", exams);

            assertThat(body)
                    .as("T-2.7's expected result is not that she is refused, it is that the "
                            + "refusal names the exams")
                    .contains("מבחן אמצע: אלגברה", "101101",
                            "בוחן: משוואות", "101102",
                            "מבחן מסכם", "101103");
            assertThat(body).contains("3 exams use");
        }

        @Test
        @DisplayName("one exam is singular")
        void singularExam() {
            assertThat(BankCopy.deleteBlockedBody("11005", List.of(new BlockingExam("101101", "Midterm"))))
                    .contains("1 exam uses");
        }

        @Test
        @DisplayName("it offers the way out, which is editing rather than deleting")
        void offersTheWayOut() {
            String body = BankCopy.deleteBlockedBody("11005", List.of(new BlockingExam("101101", "Mid")));

            assertThat(body)
                    .as("a refusal that names no alternative is a wall rather than a rule")
                    .contains("editing writes a new version");
        }

        @Test
        @DisplayName("a blocked outcome with no exams still renders a sentence")
        void defensiveEmpty() {
            assertThat(BankCopy.deleteBlockedBody("11005", List.of())).isNotBlank();
            assertThat(BankCopy.deleteBlockedBody(null, null)).isNotBlank();
        }

        @Test
        @DisplayName("the confirmation describes soft delete honestly, in both directions")
        void confirmationIsHonest() {
            String body = BankCopy.deleteConfirmBody(detail(1, 1));

            assertThat(body).contains("#11005");
            assertThat(body)
                    .as("what happens is that it leaves the bank and that nothing already using "
                            + "it changes. Saying only the first overstates it, saying only the "
                            + "second understates it.")
                    .contains("leaves the bank")
                    .contains("keep working")
                    .contains("never given to another question");
        }

        @Test
        @DisplayName("the toast names the question that went")
        void toast() {
            assertThat(BankCopy.deleted("11005")).isEqualTo("Question #11005 was deleted.");
        }
    }

    // ===================== The two branches the fixes added ===============

    @Test
    @DisplayName("a change of author is named, because a co-taught course is where it matters")
    void authorChangeIsNamed() {
        QuestionVersionDetail mine =
                version(1, "Read the diagram", FOUR, 2, "Geometry", Difficulty.HARD, false);
        QuestionVersionDetail hers = new QuestionVersionDetail(2, "Read the diagram", FOUR, 2,
                "Geometry", Difficulty.HARD, false, "אבי מזרחי", WHEN);

        assertThat(BankCopy.changeSummary(hers, mine)).contains("who wrote it");
    }

    @Test
    @DisplayName("two illustrated versions that differ nowhere else do not claim nothing changed")
    void replacedIllustrationIsNotCalledNoChange() {
        QuestionVersionDetail before =
                version(1, "Read the diagram", FOUR, 2, "Geometry", Difficulty.HARD, true);
        QuestionVersionDetail after =
                version(2, "Read the diagram", FOUR, 2, "Geometry", Difficulty.HARD, true);

        String summary = BankCopy.changeSummary(after, before);

        assertThat(summary)
                .as("the wire has KEEP, REPLACE and REMOVE and the DTO carries only hasImage, so "
                        + "a swapped diagram is invisible here. Saying 'nothing changed' would "
                        + "be a false sentence about the only reason the version exists.")
                .doesNotContain("Nothing on the question itself changed")
                .contains("not compared");
    }

    @Test
    @DisplayName("the history line names who wrote the version, and survives a nameless one")
    void historyLineNamesTheAuthor() {
        QuestionVersionDetail named =
                version(2, "text", FOUR, 1, "Geometry", Difficulty.HARD, false);
        QuestionVersionDetail nameless = new QuestionVersionDetail(1, "text", FOUR, 1, "Geometry",
                Difficulty.HARD, false, "  ", WHEN);

        assertThat(BankCopy.historyEntry(named, 2)).contains("דנה כהן");
        assertThat(BankCopy.historyEntry(nameless, 2))
                .as("a blank author must not print a dangling comma")
                .contains("Version 1")
                .doesNotContain(", ,");
    }

    @Test
    @DisplayName("every sentence built from a question survives being handed nothing")
    void nullsProduceNothingRatherThanCrashing() {
        assertThat(BankCopy.writtenBy(null)).isEmpty();
        assertThat(BankCopy.versionLine(null)).isEmpty();
        assertThat(BankCopy.historyEntry(null, 1)).isEmpty();
        assertThat(BankCopy.deleteConfirmBody(null))
                .as("the dialog opens from a render, and a render can race a cleared selection")
                .isNotBlank();
    }

    // ===================== The house rule =================================

    @Test
    @DisplayName("no em dash anywhere in this screen's copy (PRD section 4.1)")
    void noEmDashes() throws IllegalAccessException {
        List<String> offenders = new ArrayList<>();

        for (Field field : BankCopy.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    || !Modifier.isPublic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(null);
            for (String text : textsOf(value)) {
                if (text.indexOf('—') >= 0 || text.indexOf('–') >= 0) {
                    offenders.add(field.getName());
                }
            }
        }

        assertThat(offenders)
                .as("PRD section 4.1 bans em dashes in user-visible text. Javadoc and comments "
                        + "are out of scope; these constants are not.")
                .isEmpty();
    }

    private static List<String> textsOf(Object value) {
        if (value instanceof String text) {
            return List.of(text);
        }
        if (value instanceof BankCopy.EmptyPanel panel) {
            return List.of(panel.title(), panel.hint());
        }
        return List.of();
    }

    @Nested
    @DisplayName("the Editing column (E6.14)")
    class EditingColumn {

        @Test
        @DisplayName("a colleague is named, because the name is the whole point of the column")
        void namesTheColleague() {
            assertThat(BankCopy.editing(new LockHolder(502L, "Ron Levi"), false))
                    .isEqualTo("Editing · Ron Levi");
        }

        @Test
        @DisplayName("her own lock says so instead of showing her her own name")
        void herOwnLockSaysYou() {
            assertThat(BankCopy.editing(new LockHolder(501L, "Dana Cohen"), true))
                    .as("a name against a row is the shape that means somebody else has it, so "
                            + "printing hers would read as being blocked from her own editor")
                    .isEqualTo("Editing · you");
        }

        @Test
        @DisplayName("a row nobody is editing says nothing at all")
        void freeRowsAreBlank() {
            assertThat(BankCopy.editing(null, false))
                    .as("a column saying 'free' on every row is a column of noise on the case "
                            + "that is almost always true")
                    .isEmpty();
            assertThat(BankCopy.editing(null, true)).isEmpty();
        }

        @Test
        @DisplayName("a holder the server could not name still reads as somebody")
        void anUnnamedHolderIsStillSomebody() {
            assertThat(BankCopy.editing(new LockHolder(502L, null), false))
                    .as("LockHolder falls back rather than carrying a blank, and the column must "
                            + "not turn that into a row that looks free")
                    .isEqualTo("Editing · " + LockHolder.UNKNOWN_NAME);
        }

        @Test
        @DisplayName("no em dash, per PRD section 4.1")
        void noEmDash() {
            assertThat(BankCopy.editing(new LockHolder(502L, "Ron Levi"), false))
                    .doesNotContain("—");
            assertThat(BankCopy.EDITING_COLUMN).doesNotContain("—");
        }
    }

    @Test
    @DisplayName("every sentence a teacher reads is non-blank")
    void nothingIsBlank() throws IllegalAccessException {
        for (Field field : BankCopy.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    || !Modifier.isPublic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            for (String text : textsOf(field.get(null))) {
                assertThat(text).as("%s", field.getName()).isNotBlank();
            }
        }
    }
}
