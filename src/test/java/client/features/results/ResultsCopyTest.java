package client.features.results;

import common.dto.grading.GradeState;
import common.dto.grading.StudentGradeRow;
import common.dto.results.ExecutionResultRow;
import common.dto.results.ExecutionResults;
import common.dto.results.ExecutionState;
import common.dto.results.ResultStatistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResultsCopy} — the six stat cards and every sentence the results screen prints
 * (E14.2 — F9.2).
 *
 * <p>The numbers are the seeded execution 4821's, so a reader can check the cards against §9.1
 * of the seed document and against the acceptance table's scenario 10.3, which quotes
 * "mean 72.5, median 72.5, σ 17.5, pass rate 7/8" verbatim.
 *
 * <p>The copy rules PRD §4.1 asks for are asserted as rules, not spot-checked: no em dash
 * anywhere, and no card left without the line that says what its figure is.
 */
class ResultsCopyTest {

    private static final ZoneId JERUSALEM = ZoneId.of("Asia/Jerusalem");
    private static final Instant OPENED = Instant.parse("2026-08-07T06:00:00Z");
    private static final Instant CLOSED = Instant.parse("2026-08-07T08:00:00Z");

    private static ResultStatistics seeded() {
        return new ResultStatistics(8, 72.5, 72.5, 17.5, 45, 100, 7, 0.875,
                List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2));
    }

    private static ExecutionResultRow execution(int participants, boolean hasStats) {
        return new ExecutionResultRow(1, "4821", OPENED, CLOSED, ExecutionState.CLOSED,
                participants, participants, hasStats, false);
    }

    @Nested
    @DisplayName("stat cards")
    class Cards {

        @Test
        @DisplayName("F9.2's six cards appear in F9.2's order, always six")
        void sixCardsInOrder() {
            List<ResultsCopy.StatCard> cards = ResultsCopy.statCards(seeded());

            assertThat(cards).extracting(ResultsCopy.StatCard::label)
                    .containsExactly("Average", "Median", "Std deviation", "Min / max",
                            "Pass rate", "Participants");
        }

        @Test
        @DisplayName("the figures are the seeded ones, formatted as the chart formats them")
        void figuresMatchTheSeed() {
            List<ResultsCopy.StatCard> cards = ResultsCopy.statCards(seeded());

            assertThat(cards).extracting(ResultsCopy.StatCard::value)
                    .containsExactly("72.5", "72.5", "17.5", "45 to 100", "7 of 8 (87.5%)", "8");
        }

        @Test
        @DisplayName("every card says what its figure is, so no number stands unexplained")
        void everyCardHasAHint() {
            assertThat(ResultsCopy.statCards(seeded()))
                    .allSatisfy(card -> assertThat(card.hint()).isNotBlank());
        }

        @Test
        @DisplayName("the pass card names the mark the stored rate was frozen with")
        void passCardNamesTheMark() {
            ResultsCopy.StatCard pass = ResultsCopy.statCards(seeded()).get(4);

            assertThat(pass.hint()).isEqualTo("pass mark 55");
        }

        @Test
        @DisplayName("a whole average prints without a decimal, a half with one")
        void formattingMatchesTheChart() {
            assertThat(ResultsCopy.number(90.0)).isEqualTo("90");
            assertThat(ResultsCopy.number(72.5)).isEqualTo("72.5");
        }
    }

    @Nested
    @DisplayName("pass rate")
    class PassRate {

        @Test
        @DisplayName("⚑ reads as 7 of 8 (87.5%), both halves, from the stored numerator")
        void seededPassRate() {
            assertThat(ResultsCopy.passRateLabel(seeded())).isEqualTo("7 of 8 (87.5%)");
        }

        @Test
        @DisplayName("a class where everybody passed still shows both halves")
        void everybodyPassed() {
            ResultStatistics perfect = new ResultStatistics(4, 88, 90, 5, 80, 95, 4, 1.0,
                    List.of(0, 0, 0, 0, 0, 0, 0, 0, 3, 1));

            assertThat(ResultsCopy.passRateLabel(perfect)).isEqualTo("4 of 4 (100%)");
        }

        @Test
        @DisplayName("a class where nobody passed says so plainly rather than showing a blank")
        void nobodyPassed() {
            ResultStatistics failed = new ResultStatistics(2, 30, 30, 10, 20, 40, 0, 0.0,
                    List.of(0, 0, 1, 0, 1, 0, 0, 0, 0, 0));

            assertThat(ResultsCopy.passRateLabel(failed)).isEqualTo("0 of 2 (0%)");
        }
    }

    @Nested
    @DisplayName("headers and rows")
    class Rows {

        @Test
        @DisplayName("the marking gap is stated rather than left to be counted")
        void markedLabelStatesTheGap() {
            ExecutionResults partial = new ExecutionResults(execution(8, false), "Algebra",
                    "11", "אלגברה", List.of(row(60, null), row(70, null)), null);

            assertThat(ResultsCopy.markedLabel(partial)).isEqualTo("2 of 8 papers marked");
        }

        @Test
        @DisplayName("a fully marked sitting says so instead of repeating the number twice")
        void fullyMarked() {
            ExecutionResults done = new ExecutionResults(execution(8, true), "Algebra",
                    "11", "אלגברה", eightRows(), seeded());

            assertThat(ResultsCopy.markedLabel(done)).isEqualTo("All 8 papers marked");
        }

        @Test
        @DisplayName("one paper is a paper, not 'All 1 papers'")
        void singleParticipant() {
            ExecutionResults single = new ExecutionResults(execution(1, true), "Algebra",
                    "11", "אלגברה", List.of(row(60, null)), seeded());

            assertThat(ResultsCopy.markedLabel(single)).isEqualTo("1 paper marked");
        }

        @Test
        @DisplayName("a sitting nobody sat says that, rather than 0 of 0")
        void nobodySatIt() {
            ExecutionResults empty = new ExecutionResults(execution(0, false), "Algebra",
                    "11", "אלגברה", List.of(), null);

            assertThat(ResultsCopy.markedLabel(empty)).isEqualTo("Nobody sat this sitting");
        }

        @Test
        @DisplayName("the picker line carries the code, the window, the state and the turnout")
        void executionLabelIsSelfContained() {
            String label = ResultsCopy.executionLabel(execution(8, true), JERUSALEM);

            assertThat(label).isEqualTo("Code 4821 · 7 Aug 09:00 to 11:00 · closed · 8 sat");
        }

        @Test
        @DisplayName("a sitting nobody joined says so in the picker too")
        void executionLabelWithNoParticipants() {
            assertThat(ResultsCopy.executionLabel(execution(0, false), JERUSALEM))
                    .endsWith("nobody sat it");
        }

        @Test
        @DisplayName("every execution state has a word a teacher would use")
        void stateLabels() {
            assertThat(ResultsCopy.stateLabel(ExecutionState.SCHEDULED)).isEqualTo("scheduled");
            assertThat(ResultsCopy.stateLabel(ExecutionState.LIVE)).isEqualTo("live now");
            assertThat(ResultsCopy.stateLabel(ExecutionState.CLOSED)).isEqualTo("closed");
            assertThat(ResultsCopy.stateLabel(ExecutionState.CANCELLED)).isEqualTo("cancelled");
        }

        @Test
        @DisplayName("the adjusted marker appears only where a teacher changed the score (S-23)")
        void adjustedMarker() {
            assertThat(ResultsCopy.adjustedMarker(row(45, 55))).isEqualTo("Adjusted");
            assertThat(ResultsCopy.wasAdjusted(row(45, 55))).isTrue();
            assertThat(ResultsCopy.adjustedMarker(row(60, null)))
                    .as("an ordinary row leaves the column blank rather than showing a dash")
                    .isEmpty();
            assertThat(ResultsCopy.wasAdjusted(row(60, 60)))
                    .as("a teacher confirming the machine's score has not adjusted it")
                    .isFalse();
        }

        @Test
        @DisplayName("the state column says whether the student can see it yet (C-3)")
        void gradeStateLabels() {
            assertThat(ResultsCopy.gradeStateLabel(GradeState.APPROVED)).isEqualTo("Approved");
            assertThat(ResultsCopy.gradeStateLabel(GradeState.AUTO)).isEqualTo("Awaiting approval");
        }
    }

    @Test
    @DisplayName("no sentence on this screen uses an em dash (PRD §4.1)")
    void noEmDashes() {
        List<String> everySentence = List.of(ResultsCopy.LOAD_FAILED, ResultsCopy.EXECUTION_FAILED,
                ResultsCopy.NO_EXAMS_TITLE, ResultsCopy.NO_EXAMS_HINT,
                ResultsCopy.NEVER_RELEASED_TITLE, ResultsCopy.NEVER_RELEASED_HINT,
                ResultsCopy.NOBODY_SAT_TITLE, ResultsCopy.NOBODY_SAT_HINT,
                ResultsCopy.NOTHING_MARKED_TITLE, ResultsCopy.NOTHING_MARKED_HINT,
                ResultsCopy.GRADING_UNFINISHED_TITLE, ResultsCopy.GRADING_UNFINISHED_HINT,
                ResultsCopy.PRINT_EXIT, ResultsCopy.PRINT_EXIT_TARGET);

        assertThat(everySentence).allSatisfy(text ->
                assertThat(text).doesNotContain("—").doesNotContain("–").isNotBlank());
    }

    @Test
    @DisplayName("the four empty panels are four different facts, each with its own way out")
    void emptyPanelsAreDistinct() {
        List<ResultsCopy.EmptyPanel> panels = List.of(ResultsCopy.NO_EXAMS,
                ResultsCopy.NEVER_RELEASED, ResultsCopy.NOBODY_SAT, ResultsCopy.NOTHING_MARKED);

        assertThat(panels).extracting(ResultsCopy.EmptyPanel::title).doesNotHaveDuplicates();
        assertThat(panels).allSatisfy(panel ->
                assertThat(panel.hint()).isNotBlank().doesNotContain("—"));
    }

    @Test
    @DisplayName("the grading-unfinished copy explains rather than merely refusing")
    void unfinishedCopyExplains() {
        assertThat(ResultsCopy.GRADING_UNFINISHED_HINT)
                .contains("approved")
                .contains("marked so far");
    }

    private static List<StudentGradeRow> eightRows() {
        return List.of(row(45, null), row(55, null), row(60, null), row(70, null),
                row(75, null), row(85, null), row(90, null), row(100, null));
    }

    private static StudentGradeRow row(int auto, Integer finalScore) {
        return new StudentGradeRow(1, 2001, "Maya Levi", auto, finalScore,
                finalScore == null ? auto : finalScore, GradeState.APPROVED,
                finalScore == null ? null : "ניתן ניקוד חלקי.", null, CLOSED);
    }
}
