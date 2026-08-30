package client.features.data;

import common.dto.report.ReportRow;
import common.dto.results.ResultStatistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * {@link DataDetailCopy} — every sentence and every figure the principal's three detail screens
 * print (E15.2 — F9.3, U-44, the lead's ruling of 2026-08-30).
 *
 * <p>Measured here rather than eyeballed on screen, on {@code DataCopyTest}'s reasoning exactly:
 * the three views are renderers with no decisions in them, so this is where the decisions are.
 */
class DataDetailCopyTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Instant OPENED = Instant.parse("2026-03-10T07:00:00Z");
    private static final Instant CLOSED = Instant.parse("2026-03-10T09:00:00Z");

    /** SEED_CONTENT section 9.1's frozen record. */
    private static ResultStatistics seeded() {
        return new ResultStatistics(8, 72.5, 72.5, 17.5, 45, 100, 7, 0.875,
                List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2));
    }

    private static ReportRow sitting(int participants, ResultStatistics stats) {
        return new ReportRow(1, "4821", "Algebra midterm", "11", "Algebra", OPENED, CLOSED,
                participants, stats);
    }

    @Nested
    @DisplayName("the question screen")
    class Question {

        @Test
        @DisplayName("the heading spells the id the way the list column does")
        void headingMatchesTheListColumn() {
            assertThat(DataDetailCopy.questionHeading("11001")).isEqualTo("Question Q11001");
            assertThatNullPointerException()
                    .isThrownBy(() -> DataDetailCopy.questionHeading(null));
        }

        @Test
        @DisplayName("⚑ a failed timeline never claims the question failed")
        void theHistoryFailureIsAboutThePanel() {
            assertThat(DataDetailCopy.HISTORY_FAILED)
                    .contains("version history")
                    .contains("unaffected");
            assertThat(DataDetailCopy.HISTORY_FAILED)
                    .isNotEqualTo(DataDetailCopy.QUESTION_FAILED_HINT);
        }
    }

    @Nested
    @DisplayName("the sitting screen")
    class Sitting {

        @Test
        @DisplayName("the header names the course and the window the sitting ran in")
        void headerNamesCourseAndWindow() {
            assertThat(DataDetailCopy.sittingMeta(sitting(8, seeded()), UTC))
                    .isEqualTo("Algebra (11) · opened 10 Mar 2026, 07:00 "
                            + "· closed 10 Mar 2026, 09:00");
        }

        @Test
        @DisplayName("⚑ the two counts are printed apart, because they are allowed to disagree")
        void participantsAndMarkedAreBothPrinted() {
            assertThat(DataDetailCopy.participantsLine(sitting(8, seeded())))
                    .isEqualTo("8 sat it, 8 marked");
            assertThat(DataDetailCopy.participantsLine(sitting(9, seeded())))
                    .as("one paper has no grade behind the figures, and that is a fact a "
                            + "principal should be able to see rather than infer")
                    .isEqualTo("9 sat it, 8 marked (1 paper has no grade behind these figures)");
            assertThat(DataDetailCopy.participantsLine(sitting(10, seeded())))
                    .isEqualTo("10 sat it, 8 marked (2 papers have no grade behind these figures)");
        }

        @Test
        @DisplayName("the top decile is eleven wide, because that is how it was frozen")
        void theTopBucketIsEleventWide() {
            assertThat(DataDetailCopy.decileLabel(0)).isEqualTo("0 to 9");
            assertThat(DataDetailCopy.decileLabel(4)).isEqualTo("40 to 49");
            assertThat(DataDetailCopy.decileLabel(9))
                    .as("a perfect score lands in the top bucket rather than in an eleventh one")
                    .isEqualTo("90 to 100");
        }

        @Test
        @DisplayName("a bucket index outside the ten is a broken record, not a label")
        void anImpossibleBucketThrows() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DataDetailCopy.decileLabel(10));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DataDetailCopy.decileLabel(-1));
        }

        @Test
        @DisplayName("an empty band prints a bare zero, not a column of 0%")
        void anEmptyBandPrintsAZero() {
            assertThat(DataDetailCopy.decileShare(0, 8)).isEqualTo("0");
            assertThat(DataDetailCopy.decileShare(2, 8)).isEqualTo("2 (25%)");
            assertThat(DataDetailCopy.decileShare(1, 3))
                    .as("rounded the one way this application rounds")
                    .isEqualTo("1 (33.3%)");
            assertThat(DataDetailCopy.decileShare(3, 0))
                    .as("a total of zero cannot be divided by, and the count is still true")
                    .isEqualTo("3");
        }

        @Test
        @DisplayName("the distribution is ten rows, in the order the buckets were frozen")
        void theDistributionIsTenRows() {
            List<DataDetailCopy.DecileRow> rows = DataDetailCopy.distribution(seeded());

            assertThat(rows).hasSize(ResultStatistics.BUCKET_COUNT);
            assertThat(rows).extracting(DataDetailCopy.DecileRow::range)
                    .startsWith("0 to 9", "10 to 19")
                    .endsWith("90 to 100");
            assertThat(rows).extracting(DataDetailCopy.DecileRow::count)
                    .containsExactlyElementsOf(seeded().deciles());
            assertThat(rows.get(9).share()).isEqualTo("2 (25%)");
            assertThatNullPointerException()
                    .isThrownBy(() -> DataDetailCopy.distribution(null));
        }

        @Test
        @DisplayName("⚑ the screen says why it names no student")
        void theHintExplainsTheAbsentNames() {
            assertThat(DataDetailCopy.DISTRIBUTION_HINT)
                    .contains("frozen")
                    .contains("teacher's own results screen");
        }
    }

    @Nested
    @DisplayName("what every one of the three says out loud")
    class ReadOnly {

        @Test
        @DisplayName("⚑ S-7 is stated on each screen, not only on the list")
        void readOnlyIsSaidOnEachScreen() {
            assertThat(DataDetailCopy.READ_ONLY_NOTE)
                    .as("T-11.3 looks for a create, edit or delete control ANYWHERE in her "
                            + "shell, and a screen that simply has none is indistinguishable "
                            + "from one whose buttons are not built yet")
                    .contains("read only");
        }

        @Test
        @DisplayName("no user-visible sentence carries an em dash")
        void noEmDashes() {
            assertThat(List.of(DataDetailCopy.READ_ONLY_NOTE, DataDetailCopy.QUESTION_SUBTITLE,
                            DataDetailCopy.HISTORY_LOADING, DataDetailCopy.HISTORY_FAILED,
                            DataDetailCopy.QUESTION_FAILED_TITLE,
                            DataDetailCopy.QUESTION_FAILED_HINT, DataDetailCopy.EXAM_BANNER,
                            DataDetailCopy.EXAM_FAILED_TITLE, DataDetailCopy.EXAM_FAILED_HINT,
                            DataDetailCopy.EXAM_NOT_OPENABLE, DataDetailCopy.DISTRIBUTION_TITLE,
                            DataDetailCopy.DISTRIBUTION_HINT,
                            DataDetailCopy.SITTING_FAILED_TITLE,
                            DataDetailCopy.SITTING_FAILED_HINT))
                    .allSatisfy(sentence -> assertThat(sentence).doesNotContain("—"));
        }
    }
}
