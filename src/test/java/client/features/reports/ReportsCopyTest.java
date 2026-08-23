package client.features.reports;

import common.dto.report.ReportDimension;
import common.dto.report.ReportRow;
import common.dto.report.ReportSubject;
import common.dto.report.ReportSummary;
import common.dto.results.ResultStatistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReportsCopy} — every sentence and every figure the principal's screen prints (E15.4).
 *
 * <p>The house copy rules are checkable here rather than by reading the screen: no em dashes,
 * and every empty state says what would make it go away. The figures are checked against the
 * same seeded numbers the server tests use, so a rounding that disagreed with the teacher's
 * results screen would fail here.
 */
class ReportsCopyTest {

    private static final Instant OPENED = Instant.parse("2026-08-07T06:00:00Z");
    private static final ZoneId UTC = ZoneId.of("UTC");

    /** SEED_CONTENT section 9.1's frozen record. */
    private static ResultStatistics seeded() {
        return new ResultStatistics(8, 72.5, 72.5, 17.5, 45, 100, 7, 0.875,
                List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2));
    }

    private static ReportRow row() {
        return new ReportRow(1, "4821", "מבחן אמצע: אלגברה", "11", "אלגברה", OPENED,
                OPENED.plusSeconds(7200), 8, seeded());
    }

    private static ReportSummary twoSittings() {
        ResultStatistics quiet = new ResultStatistics(4, 65, 65, Math.sqrt(125), 50, 80, 3, 0.75,
                List.of(0, 0, 0, 0, 0, 1, 1, 1, 1, 0));
        return ReportSummary.across(List.of(row(),
                new ReportRow(2, "5150", "מבחן", "11", "אלגברה", OPENED,
                        OPENED.plusSeconds(7200), 4, quiet)));
    }

    // ===================== Rows ==========================================

    @Nested
    @DisplayName("row labels")
    class Rows {

        @Test
        @DisplayName("a row is named by its exam and its code, so two sittings differ on screen")
        void rowLabel() {
            assertThat(ReportsCopy.rowLabel(row())).isEqualTo("מבחן אמצע: אלגברה · 4821");
        }

        @Test
        @DisplayName("a row's date is a date: two sittings a report compares are months apart")
        void rowDate() {
            assertThat(ReportsCopy.rowDate(OPENED, UTC)).isEqualTo("7 Aug 2026");
        }

        @Test
        @DisplayName("the pass rate reads as both halves, exactly as E14's card does")
        void passRate() {
            assertThat(ReportsCopy.passRate(seeded())).isEqualTo("7 of 8 (87.5%)");
        }

        @Test
        @DisplayName("figures round the one way this application rounds")
        void numbers() {
            assertThat(ReportsCopy.number(72.5)).isEqualTo("72.5");
            assertThat(ReportsCopy.number(17.0)).isEqualTo("17");
            assertThat(ReportsCopy.number(16.072751)).isEqualTo("16.1");
        }
    }

    // ===================== Subjects and headings =========================

    @Nested
    @DisplayName("pickers and headings")
    class Pickers {

        @ParameterizedTest
        @EnumSource(ReportDimension.class)
        @DisplayName("the picker's prompt comes from the dimension, never from a switch here")
        void prompts(ReportDimension dimension) {
            assertThat(ReportsCopy.subjectPrompt(dimension))
                    .isEqualTo(dimension.subjectNoun())
                    .isNotBlank();
        }

        @Test
        @DisplayName("the heading names the subject and what it is being compared as")
        void heading() {
            assertThat(ReportsCopy.heading(ReportDimension.BY_COURSE,
                    new ReportSubject("11", "אלגברה", "Course 11", 2)))
                    .isEqualTo("אלגברה · by course");
        }

        @Test
        @DisplayName("a subject carries its count, so an empty one is visible before it is opened")
        void subjectLabels() {
            assertThat(ReportsCopy.subjectLabel(
                    new ReportSubject("2", "דנה כהן", "dana.cohen", 3)))
                    .isEqualTo("דנה כהן · 3 sittings");
            assertThat(ReportsCopy.subjectLabel(
                    new ReportSubject("2", "דנה כהן", "dana.cohen", 1)))
                    .isEqualTo("דנה כהן · 1 sitting");
            assertThat(ReportsCopy.subjectLabel(
                    new ReportSubject("3", "רינה ברק", "rina.barak", 0)))
                    .isEqualTo("רינה ברק · nothing to report");
        }
    }

    // ===================== The summary cards =============================

    @Nested
    @DisplayName("the summary cards")
    class SummaryCards {

        @Test
        @DisplayName("six cards, always six, so the row does not shift between subjects")
        void sixCards() {
            assertThat(ReportsCopy.summaryCards(twoSittings())).hasSize(6);
            assertThat(ReportsCopy.summaryCards(ReportSummary.EMPTY)).hasSize(6);
        }

        @Test
        @DisplayName("⚑ every card names its arithmetic, so a reader can check the right thing")
        void hintsNameTheArithmetic() {
            List<ReportsCopy.SummaryCard> cards = ReportsCopy.summaryCards(twoSittings());

            assertThat(cards).extracting(ReportsCopy.SummaryCard::label)
                    .containsExactly("Sittings", "Average", "Median", "Std deviation",
                            "Pass rate", "Results");
            assertThat(cards.get(1).hint())
                    .as("without this the reader checks it against the mean of the column and "
                            + "concludes the report is wrong")
                    .isEqualTo("weighted by participants");
            assertThat(cards.get(2).hint()).isEqualTo(ReportsCopy.MEDIAN_BAND_HINT);
            assertThat(cards.get(3).hint()).isEqualTo("pooled population sigma");
            assertThat(cards.get(4).hint()).isEqualTo("pass mark 55");
        }

        @Test
        @DisplayName("the figures on the cards are the hand-computed pooled ones")
        void cardValues() {
            List<ReportsCopy.SummaryCard> cards = ReportsCopy.summaryCards(twoSittings());

            assertThat(cards.get(0).value()).isEqualTo("2");
            assertThat(cards.get(1).value()).isEqualTo("70");
            assertThat(cards.get(2).value()).isEqualTo("70-79");
            assertThat(cards.get(3).value()).isEqualTo("16.1");
            assertThat(cards.get(4).value()).isEqualTo("10 of 12 (83.3%)");
            assertThat(cards.get(5).value()).isEqualTo("12");
        }

        @Test
        @DisplayName("⚑ the median is printed as a band, never as an invented point value")
        void medianIsABand() {
            assertThat(ReportsCopy.medianBand(twoSittings())).isEqualTo("70-79");
            assertThat(ReportsCopy.medianBand(ReportSummary.EMPTY)).isEqualTo("-");
        }

        @Test
        @DisplayName("one sitting says so rather than implying a trend through one point")
        void oneSittingIsCalledOut() {
            List<ReportsCopy.SummaryCard> cards =
                    ReportsCopy.summaryCards(ReportSummary.across(List.of(row())));

            assertThat(cards.get(0).hint()).isEqualTo("one sitting, no trend");
        }

        @Test
        @DisplayName("unmarked papers are named on the card rather than left to subtraction")
        void unmarkedIsStated() {
            ResultStatistics stats = seeded();
            ReportSummary withAGap = ReportSummary.across(List.of(
                    new ReportRow(1, "4821", "מבחן", "11", "אלגברה", OPENED,
                            OPENED.plusSeconds(7200), 10, stats)));

            assertThat(ReportsCopy.summaryCards(withAGap).get(5).hint()).isEqualTo("2 unmarked");
            assertThat(ReportsCopy.summaryCards(twoSittings()).get(5).hint())
                    .isEqualTo("every paper marked");
        }

        @Test
        @DisplayName("the line under the cards counts people and sittings, in the right plural")
        void participantsLine() {
            assertThat(ReportsCopy.participantsLine(twoSittings()))
                    .isEqualTo("12 students across 2 sittings");
            assertThat(ReportsCopy.participantsLine(ReportSummary.across(List.of(row()))))
                    .isEqualTo("8 students across 1 sitting");
            assertThat(ReportsCopy.participantsLine(ReportSummary.EMPTY)).isEmpty();
        }

        @Test
        @DisplayName("nothing scored prints a dash rather than a division by nobody")
        void nothingScored() {
            ReportSummary nothing = ReportSummary.across(List.of(
                    new ReportRow(1, "4821", "מבחן", "11", "אלגברה", OPENED,
                            OPENED.plusSeconds(7200), 5,
                            new ResultStatistics(0, 0, 0, 0, 0, 0, 0, 0,
                                    List.of(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))));

            assertThat(ReportsCopy.summaryPassRate(nothing)).isEqualTo("-");
            assertThat(ReportsCopy.medianBand(nothing)).isEqualTo("-");
        }
    }

    // ===================== The copy rules ================================

    @Nested
    @DisplayName("the house copy rules")
    class CopyRules {

        @Test
        @DisplayName("every empty state says what would make it go away")
        void emptyStatesExplain() {
            List<ReportsCopy.EmptyPanel> panels = List.of(ReportsCopy.NOTHING_PICKED,
                    ReportsCopy.NO_SUBJECTS, ReportsCopy.NO_EXECUTIONS);

            assertThat(panels).allSatisfy(panel -> {
                assertThat(panel.title()).isNotBlank();
                assertThat(panel.hint()).isNotBlank();
                assertThat(panel.hint().length())
                        .as("a hint that only restates the title is a dead end with two lines")
                        .isGreaterThan(panel.title().length());
            });
            assertThat(ReportsCopy.NO_EXECUTIONS.hint())
                    .contains("once its last grade is approved");
        }

        @Test
        @DisplayName("no sentence on this screen contains an em dash")
        void noEmDashes() {
            List<String> everySentence = List.of(ReportsCopy.TITLE, ReportsCopy.SUBTITLE,
                    ReportsCopy.SUBJECTS_FAILED, ReportsCopy.REPORT_FAILED,
                    ReportsCopy.ROWS_TABLE_TITLE, ReportsCopy.SCOPE_HINT,
                    ReportsCopy.MEDIAN_BAND_HINT,
                    ReportsCopy.NOTHING_PICKED.title(), ReportsCopy.NOTHING_PICKED.hint(),
                    ReportsCopy.NO_SUBJECTS.title(), ReportsCopy.NO_SUBJECTS.hint(),
                    ReportsCopy.NO_EXECUTIONS.title(), ReportsCopy.NO_EXECUTIONS.hint());

            assertThat(everySentence).allSatisfy(sentence ->
                    assertThat(sentence).doesNotContain("—"));
        }

        @Test
        @DisplayName("both failure sentences say what to try next")
        void failuresSayWhatToDo() {
            assertThat(ReportsCopy.SUBJECTS_FAILED).contains("try again");
            assertThat(ReportsCopy.REPORT_FAILED).contains("Choose the subject again");
        }

        @Test
        @DisplayName("the scope hint names the sittings a report leaves out, once")
        void scopeIsStated() {
            assertThat(ReportsCopy.SCOPE_HINT)
                    .contains("closed")
                    .contains("cancelled");
        }
    }
}
