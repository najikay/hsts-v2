package client.features.exambuild;

import common.dto.authoring.ExamCreateRequest;
import common.dto.authoring.QuestionPin;
import common.dto.authoring.Shortfall;
import common.dto.bank.Difficulty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExamBuildCopy} — every sentence the builder shows, checked without a window.
 *
 * <p>The point of a copy class is that its wording is testable. The failures below are the ones a
 * screenshot review would have to catch by eye: "1 questions", a limit that has drifted from the
 * contract, a mode whose title says the wrong thing, and the house rule on em dashes.
 */
class ExamBuildCopyTest {

    private static ExamBuilderSession.Line line(String displayId, String topic,
                                                Difficulty difficulty, int points) {
        return ExamBuilderSession.Line.fromServer(9001L, displayId, "What is recursion?", topic,
                difficulty, false, 1, 1, 9001L, points);
    }

    // ===================== The house rule =================================

    // ===================== The infeasibility report =======================

    /**
     * F3.3's acceptance artefact, asserted rather than typed correctly by luck on the day ⚑.
     *
     * <p>Ruling 4 kept this sentence on the client and {@code Shortfall} structural, and named the
     * copy test as Member A's to write with the screen. §7.1 gives four shapes and the PRD gives
     * the exact wording of one of them; both are pinned here, because a report whose sentences
     * are almost right is a report the teacher cannot act on.
     */
    @Nested
    @DisplayName("the infeasibility report's sentences (§7.1, F3.3, ruling 4)")
    class Infeasibility {

        /**
         * The PRD's own example, character for character.
         *
         * <p>PRD F3.3 writes {@code Topic 'Algebra': requested 5 Hard, bank has 2}. §7.1's table
         * renders the same shape with double quotes and a trailing full stop. Where a contract
         * table and the PRD disagree about an acceptance artefact the PRD wins, and
         * {@code AutoComposeResultTest} and {@code AutoComposerTest} already quote this form.
         */
        @Test
        @DisplayName("⚑ the PRD's own sentence, exactly")
        void thePrdSentence() {
            assertThat(ExamBuildCopy.shortfallLine(
                    new Shortfall("Algebra", Difficulty.HARD, 5, 2)))
                    .isEqualTo("Topic 'Algebra': requested 5 Hard, bank has 2");
        }

        @Test
        @DisplayName("a topic short of one difficulty names both the topic and the grade")
        void topicAndGrade() {
            assertThat(ExamBuildCopy.shortfallLine(
                    new Shortfall("Recursion", Difficulty.HARD, 1, 0)))
                    .isEqualTo("Topic 'Recursion': requested 1 Hard, bank has 0");
        }

        @Test
        @DisplayName("a topic short overall says questions, because no grade was asked for")
        void topicWithoutGrade() {
            assertThat(ExamBuildCopy.shortfallLine(
                    new Shortfall("Recursion", null, 3, 2)))
                    .isEqualTo("Topic 'Recursion': requested 3 questions, bank has 2");
        }

        @Test
        @DisplayName("a course-wide shortfall names no topic, and still starts as a sentence does")
        void courseWideWithGrade() {
            assertThat(ExamBuildCopy.shortfallLine(new Shortfall(null, Difficulty.HARD, 10, 4)))
                    .isEqualTo("Requested 10 Hard, bank has 4");
        }

        @Test
        @DisplayName("the course-wide any bucket is the fourth shape")
        void courseWideWithoutGrade() {
            assertThat(ExamBuildCopy.shortfallLine(new Shortfall(null, null, 40, 31)))
                    .isEqualTo("Requested 40 questions, bank has 31");
        }

        /**
         * Every grade renders as the PRD writes it, not as the enum spells it.
         *
         * <p>{@code Difficulty.HARD.toString()} is {@code "HARD"}, and a sentence reading
         * "requested 5 HARD" is the shape of defect that ships because nobody read the string
         * out loud. Parameterised over the enum so a fourth difficulty cannot arrive untested.
         */
        @ParameterizedTest
        @EnumSource(Difficulty.class)
        @DisplayName("⚑ every grade is title case in the sentence, never the enum's own spelling")
        void gradesAreTitleCase(Difficulty difficulty) {
            String line = ExamBuildCopy.shortfallLine(new Shortfall("Algebra", difficulty, 5, 2));

            assertThat(line).doesNotContain(difficulty.name());
            assertThat(line).contains(difficulty.name().charAt(0)
                    + difficulty.name().substring(1).toLowerCase(java.util.Locale.ROOT));
        }
    }

    @Nested
    @DisplayName("PRD §4.1: no em dashes in user-visible text")
    class HouseRule {

        @Test
        @DisplayName("no constant on this class contains an em dash or an en dash")
        void constantsAreClean() throws IllegalAccessException {
            List<String> offenders = new ArrayList<>();
            for (Field field : ExamBuildCopy.class.getDeclaredFields()) {
                if (!Modifier.isPublic(field.getModifiers())
                        || !Modifier.isStatic(field.getModifiers())
                        || field.getType() != String.class) {
                    continue;
                }
                String value = (String) field.get(null);
                if (value.indexOf('—') >= 0 || value.indexOf('–') >= 0) {
                    offenders.add(field.getName());
                }
            }

            assertThat(offenders).isEmpty();
        }

        @Test
        @DisplayName("guard against the guard: the reflection actually sees the constants")
        void theScanIsNotVacuous() {
            long visible = List.of(ExamBuildCopy.class.getDeclaredFields()).stream()
                    .filter(field -> Modifier.isPublic(field.getModifiers())
                            && Modifier.isStatic(field.getModifiers())
                            && field.getType() == String.class)
                    .count();

            assertThat(visible)
                    .as("if this is zero the scan above passes by finding nothing at all")
                    .isGreaterThan(10);
        }

        @Test
        @DisplayName("no derived sentence contains an em dash either")
        void derivedTextIsClean() {
            List<String> derived = List.of(
                    ExamBuildCopy.questions(1),
                    ExamBuildCopy.pointsIndicator(62),
                    ExamBuildCopy.nameHint(),
                    ExamBuildCopy.durationHint(),
                    ExamBuildCopy.pointsHint(),
                    ExamBuildCopy.textCounter(10, 4000),
                    ExamBuildCopy.questionSummary(line("11001", "Recursion",
                            Difficulty.MEDIUM, 50)),
                    ExamBuildCopy.title(ExamBuilderSession.Mode.EDIT),
                    ExamBuildCopy.courseLine("11", "Algebra 11"),
                    ExamBuildCopy.saveButton(ExamBuilderSession.Mode.CREATE));

            assertThat(derived).allSatisfy(sentence ->
                    assertThat(sentence).doesNotContain("—").doesNotContain("–"));
        }
    }

    // ===================== Limits come from the wire ======================

    /**
     * The numbers a teacher reads are the numbers the server enforces.
     *
     * <p>Asserted against the wire records rather than against literals. A literal here would be
     * the second home this design exists to avoid, and it would keep passing after the lead moved
     * a ceiling - which he has already done once, cutting the duration limit from 600 to 480.
     */
    @Nested
    @DisplayName("every limit is read off the contract, never typed")
    class Limits {

        @Test
        @DisplayName("⚑ the name hint carries the record's own maximum")
        void nameHint() {
            assertThat(ExamBuildCopy.nameHint())
                    .contains(String.valueOf(ExamCreateRequest.MAX_NAME_LENGTH));
        }

        @Test
        @DisplayName("⚑ the duration hint carries the record's own range")
        void durationHint() {
            assertThat(ExamBuildCopy.durationHint())
                    .contains(String.valueOf(ExamCreateRequest.MIN_DURATION_MINUTES))
                    .contains(String.valueOf(ExamCreateRequest.MAX_DURATION_MINUTES));
        }

        @Test
        @DisplayName("⚑ the points hint carries QuestionPin's own range")
        void pointsHint() {
            assertThat(ExamBuildCopy.pointsHint())
                    .contains(String.valueOf(QuestionPin.MIN_POINTS))
                    .contains(String.valueOf(QuestionPin.MAX_POINTS));
        }

        @Test
        @DisplayName("⚑ the points indicator names the target from the record")
        void indicatorTarget() {
            assertThat(ExamBuildCopy.pointsIndicator(62))
                    .isEqualTo("62 of " + ExamCreateRequest.POINTS_TOTAL + " points");
        }
    }

    // ===================== Modes ==========================================

    @Nested
    @DisplayName("the title and the save button follow the mode")
    class Modes {

        @ParameterizedTest
        @EnumSource(ExamBuilderSession.Mode.class)
        @DisplayName("every mode has its own title, and none of them is blank")
        void everyModeHasATitle(ExamBuilderSession.Mode mode) {
            assertThat(ExamBuildCopy.title(mode)).isNotBlank();
        }

        @Test
        @DisplayName("⚑ the three titles are distinct, or the screen lies about what it is doing")
        void titlesAreDistinct() {
            assertThat(List.of(
                    ExamBuildCopy.title(ExamBuilderSession.Mode.CREATE),
                    ExamBuildCopy.title(ExamBuilderSession.Mode.EDIT),
                    ExamBuildCopy.title(ExamBuilderSession.Mode.READ_ONLY)))
                    .doesNotHaveDuplicates();
        }

        /**
         * The button's word has to match the verb the mode will send.
         *
         * <p>"Save draft" on a screen that is about to call {@code EXAM_CREATE} is a teacher told
         * she is updating something when she is making a second one.
         */
        @Test
        @DisplayName("⚑ CREATE says create and the other two say save")
        void saveButtonFollowsTheVerb() {
            assertThat(ExamBuildCopy.saveButton(ExamBuilderSession.Mode.CREATE))
                    .isEqualTo(ExamBuildCopy.CREATE_BUTTON);
            assertThat(ExamBuildCopy.saveButton(ExamBuilderSession.Mode.EDIT))
                    .isEqualTo(ExamBuildCopy.SAVE_BUTTON);
        }

        @Test
        @DisplayName("a null mode falls back to the new-exam wording rather than throwing")
        void nullMode() {
            assertThat(ExamBuildCopy.title(null)).isEqualTo(ExamBuildCopy.TITLE_NEW);
        }
    }

    // ===================== Derived text ===================================

    @Nested
    @DisplayName("derived text")
    class Derived {

        @Test
        @DisplayName("one is singular, everything else is plural")
        void pluralisation() {
            assertThat(ExamBuildCopy.questions(1)).isEqualTo("1 question");
            assertThat(ExamBuildCopy.questions(12)).isEqualTo("12 questions");
            assertThat(ExamBuildCopy.questions(0)).isEqualTo("0 questions");
        }

        @Test
        @DisplayName("a question's line names its id, topic and difficulty")
        void questionSummary() {
            assertThat(ExamBuildCopy.questionSummary(
                    line("11001", "Recursion", Difficulty.HARD, 50)))
                    .isEqualTo("11001 · Recursion · hard");
        }

        @Test
        @DisplayName("a question with no topic says so rather than showing a gap")
        void questionWithoutTopic() {
            assertThat(ExamBuildCopy.questionSummary(line("11001", null, Difficulty.EASY, 50)))
                    .isEqualTo("11001 · No topic · easy");
            assertThat(ExamBuildCopy.questionSummary(line("11001", "   ", Difficulty.EASY, 50)))
                    .contains("No topic");
        }

        @Test
        @DisplayName("nulls are empty strings, never the word null on a screen")
        void nullsAreEmpty() {
            assertThat(ExamBuildCopy.questionSummary(null)).isEmpty();
        }

        @Test
        @DisplayName("the text counter shows how far she is from the ceiling")
        void textCounter() {
            assertThat(ExamBuildCopy.textCounter(120, 4000)).isEqualTo("120 of 4000 characters.");
        }
    }

    // ===================== The course line (U-30) =========================

    /**
     * Which course the paper is for, in the builder's header.
     *
     * <p>The builder had no such line at all until 2026-08-29: the course is chosen in the exam
     * list's New exam menu and travels as a nav parameter, so the one screen that spends it -
     * the bank picker is scoped to it, {@code EXAM_CREATE} carries it - was the one screen that
     * never said which it was. The wording is pinned here rather than in the interaction test,
     * because a spelling is checkable without a window.
     */
    @Nested
    @DisplayName("⚑ U-30: the header's course line")
    class CourseLine {

        @Test
        @DisplayName("names the code and the name, in the house spelling")
        void codeAndName() {
            assertThat(ExamBuildCopy.courseLine("11", "Algebra 11"))
                    .isEqualTo("Course: 11 · Algebra 11");
        }

        /**
         * The separator is the one {@code ExamListCopy.courseLabel} uses, character for
         * character. Two spellings of one course across two screens is the defect this shares
         * a shape with rather than a bug of its own, so it is asserted rather than assumed.
         */
        @Test
        @DisplayName("⚑ spells the course exactly as the exam list spells it")
        void matchesTheExamList() {
            assertThat(ExamBuildCopy.courseLine("11", "Algebra 11"))
                    .endsWith(ExamListCopy.courseOption(
                            new common.dto.auth.CourseRef("11", "Algebra 11")));
        }

        @Test
        @DisplayName("a course whose name the client does not know still shows its code")
        void codeOnly() {
            assertThat(ExamBuildCopy.courseLine("11", "")).isEqualTo("Course: 11");
            assertThat(ExamBuildCopy.courseLine("11", null)).isEqualTo("Course: 11");
            assertThat(ExamBuildCopy.courseLine("11", "   ")).isEqualTo("Course: 11");
        }

        @Test
        @DisplayName("the prefix names itself, so three numbers in a row cannot be confused")
        void carriesThePrefix() {
            assertThat(ExamBuildCopy.courseLine("11", "Algebra 11"))
                    .startsWith(ExamBuildCopy.COURSE_PREFIX);
        }

        /**
         * Nothing known is an empty line rather than a dangling label.
         *
         * <p>The view hides the label on a blank string, so "Course:" with nothing after it is
         * a state that must not be reachable: it would appear for the half-second between
         * {@code build()} and the first render of a failed load.
         */
        @Test
        @DisplayName("⚑ knowing nothing produces nothing, never a bare Course:")
        void nothingKnown() {
            assertThat(ExamBuildCopy.courseLine(null, null)).isEmpty();
            assertThat(ExamBuildCopy.courseLine("", "")).isEmpty();
        }

        @Test
        @DisplayName("a name with no code is still worth saying")
        void nameOnly() {
            assertThat(ExamBuildCopy.courseLine(null, "Algebra 11"))
                    .isEqualTo("Course: Algebra 11");
        }
    }
}
