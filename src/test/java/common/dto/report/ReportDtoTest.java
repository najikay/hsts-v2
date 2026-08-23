package common.dto.report;

import common.dto.results.ResultStatistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.within;

/**
 * The E15 report wire types, and above all the summary arithmetic (E15.3 ⚑ — F9.4).
 *
 * <p>The nested {@code SummaryArithmetic} class is the one that matters. Its fixture is twelve
 * scores whose every statistic is computable by hand, so each assertion below is checkable
 * against a calculator rather than against the implementation:
 *
 * <ul>
 *   <li><b>Row A</b> is the seeded execution 4821, verbatim from SEED_CONTENT section 9.1:
 *       finals 45, 55, 60, 70, 75, 85, 90, 100 — mean 72.5, median 72.5, population σ 17.5,
 *       min 45, max 100, 7 of 8 passed, deciles {@code [0,0,0,0,1,1,1,2,1,2]}.</li>
 *   <li><b>Row B</b> is a smaller, calmer sitting: 50, 60, 70, 80 — mean 65, population σ
 *       {@code √125}, min 50, max 80, 3 of 4 passed, deciles
 *       {@code [0,0,0,0,0,1,1,1,1,0]}.</li>
 * </ul>
 *
 * <p>Pooled, those twelve scores are 45, 50, 55, 60, 60, 70, 70, 75, 80, 85, 90, 100. Their
 * total is 840, so the mean is exactly <b>70</b>. Σ(x−70)² is 2500 + 600 = <b>3100</b>, so the
 * population variance is 3100/12 = 258.333… and σ is <b>16.0728</b>. Ten of the twelve reached
 * 55. The sixth-lowest score is 70, which is in the <b>70–79</b> bucket.
 *
 * <p><b>The mean of the means is 68.75</b>, and the first test below asserts the summary is not
 * that. It is the whole reason this record exists.
 */
class ReportDtoTest {

    private static final Instant OPENED = Instant.parse("2026-08-07T06:00:00Z");
    private static final Instant CLOSED = Instant.parse("2026-08-07T08:00:00Z");

    /** SEED_CONTENT section 9.1, verbatim. */
    private static ResultStatistics seededExecutionOne() {
        return new ResultStatistics(8, 72.5, 72.5, 17.5, 45, 100, 7, 0.875,
                List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2));
    }

    /** Scores 50, 60, 70, 80: mean 65, population sigma root-125, 3 of 4 at or above 55. */
    private static ResultStatistics quietSitting() {
        return new ResultStatistics(4, 65, 65, Math.sqrt(125), 50, 80, 3, 0.75,
                List.of(0, 0, 0, 0, 0, 1, 1, 1, 1, 0));
    }

    private static ReportRow row(long id, String code, int participants, ResultStatistics stats) {
        return new ReportRow(id, code, "מבחן אמצע: אלגברה", "11", "אלגברה", OPENED, CLOSED,
                participants, stats);
    }

    // ===================== The arithmetic ⚑ ==============================

    @Nested
    @DisplayName("the cross-row summary ⚑")
    class SummaryArithmetic {

        private final List<ReportRow> twoSittings =
                List.of(row(1, "4821", 8, seededExecutionOne()), row(2, "5150", 4, quietSitting()));

        @Test
        @DisplayName("⚑ the mean is participant-weighted, and is not the mean of the means")
        void meanIsWeighted() {
            ReportSummary summary = ReportSummary.across(twoSittings);

            assertThat(summary.mean())
                    .as("840 points over 12 papers is exactly 70")
                    .isEqualTo(70.0);
            assertThat(summary.mean())
                    .as("the mean of 72.5 and 65 is 68.75, and reporting that would be a lie "
                            + "about a class of twelve")
                    .isNotEqualTo(68.75);
        }

        @Test
        @DisplayName("⚑ sigma is the exact pooled population form, recovered from the stored ones")
        void sigmaIsPooled() {
            ReportSummary summary = ReportSummary.across(twoSittings);

            // Sum of (x - 70)^2 over all twelve scores is 3100; 3100 / 12 = 258.3333...
            assertThat(summary.standardDeviation())
                    .isCloseTo(Math.sqrt(3100.0 / 12), within(1e-9));
            assertThat(summary.standardDeviation())
                    .as("16.0728, hand-checkable, and not the average of 17.5 and 11.18")
                    .isCloseTo(16.072751, within(1e-6));
            assertThat(summary.standardDeviation())
                    .isNotEqualTo((17.5 + Math.sqrt(125)) / 2);
        }

        @Test
        @DisplayName("the sample divisor is not used anywhere: n, never n-1 (H14.4 ⚑)")
        void sigmaUsesThePopulationDivisor() {
            ReportSummary summary = ReportSummary.across(twoSittings);

            assertThat(summary.standardDeviation())
                    .as("the sample form over these twelve scores is 16.79, about 0.7 out")
                    .isNotCloseTo(Math.sqrt(3100.0 / 11), within(1e-3));
        }

        @Test
        @DisplayName("the deciles are pooled, and they sum to the population every figure used")
        void decilesArePooled() {
            ReportSummary summary = ReportSummary.across(twoSittings);

            assertThat(summary.deciles()).containsExactly(0, 0, 0, 0, 1, 2, 2, 3, 2, 2);
            assertThat(summary.deciles().stream().mapToInt(Integer::intValue).sum())
                    .as("the distribution accounts for exactly the papers the mean was over")
                    .isEqualTo(summary.scored());
        }

        @Test
        @DisplayName("⚑ the median is a band read off the pooled deciles, never an average of medians")
        void medianIsABand() {
            ReportSummary summary = ReportSummary.across(twoSittings);

            // Sorted: 45 50 55 60 60 70 70 75 80 85 90 100. The sixth is 70.
            assertThat(summary.medianBucket())
                    .as("the 70-79 bucket, index 7")
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("the pass count is the sum of the stored ones, never the mark applied again")
        void passRateIsSummed() {
            ReportSummary summary = ReportSummary.across(twoSittings);

            assertThat(summary.passCount()).isEqualTo(10);
            assertThat(summary.scored()).isEqualTo(12);
            assertThat(summary.passRate()).isCloseTo(10.0 / 12, within(1e-12));
            assertThat(summary.passPercent()).isCloseTo(83.3333, within(1e-3));
        }

        @Test
        @DisplayName("the extremes are the extremes of the extremes")
        void extremes() {
            ReportSummary summary = ReportSummary.across(twoSittings);

            assertThat(summary.min()).isEqualTo(45);
            assertThat(summary.max()).isEqualTo(100);
        }

        @Test
        @DisplayName("participants counts everyone who sat, which can exceed the papers marked")
        void participantsAreAttempts() {
            List<ReportRow> withAnUnmarkedPaper =
                    List.of(row(1, "4821", 9, seededExecutionOne()));

            ReportSummary summary = ReportSummary.across(withAnUnmarkedPaper);

            assertThat(summary.participants()).isEqualTo(9);
            assertThat(summary.scored()).isEqualTo(8);
            assertThat(summary.unmarked()).isEqualTo(1);
        }

        @Test
        @DisplayName("one sitting summarises to exactly that sitting's own figures")
        void oneSittingIsItself() {
            ReportSummary summary = ReportSummary.across(List.of(row(1, "4821", 8,
                    seededExecutionOne())));

            assertThat(summary.executions()).isEqualTo(1);
            assertThat(summary.isSingleExecution()).isTrue();
            assertThat(summary.mean()).isEqualTo(72.5);
            assertThat(summary.standardDeviation()).isCloseTo(17.5, within(1e-9));
            assertThat(summary.passCount()).isEqualTo(7);
            assertThat(summary.deciles()).containsExactly(0, 0, 0, 0, 1, 1, 1, 2, 1, 2);
        }

        @Test
        @DisplayName("identical sittings pool to identical figures, sigma included")
        void identicalSittingsDoNotDrift() {
            ReportSummary summary = ReportSummary.across(List.of(
                    row(1, "4821", 8, seededExecutionOne()),
                    row(2, "4822", 8, seededExecutionOne())));

            assertThat(summary.mean()).isEqualTo(72.5);
            assertThat(summary.standardDeviation())
                    .as("pooling two copies of one class must not widen its spread")
                    .isCloseTo(17.5, within(1e-9));
            assertThat(summary.scored()).isEqualTo(16);
        }

        @Test
        @DisplayName("a report of zero-variance sittings never produces a NaN sigma")
        void zeroVarianceIsClamped() {
            ResultStatistics everybodyGotSeventy = new ResultStatistics(3, 70, 70, 0, 70, 70,
                    3, 1.0, List.of(0, 0, 0, 0, 0, 0, 0, 3, 0, 0));

            ReportSummary summary = ReportSummary.across(List.of(
                    row(1, "1111", 3, everybodyGotSeventy),
                    row(2, "2222", 3, everybodyGotSeventy)));

            assertThat(summary.standardDeviation()).isEqualTo(0.0);
            assertThat(Double.isNaN(summary.standardDeviation())).isFalse();
        }

        @Test
        @DisplayName("no rows is EMPTY: ten zero buckets, no median band, and no invented mean")
        void emptyIsNotZeros() {
            ReportSummary summary = ReportSummary.across(List.of());

            assertThat(summary).isEqualTo(ReportSummary.EMPTY);
            assertThat(summary.isEmpty()).isTrue();
            assertThat(summary.deciles()).hasSize(10).containsOnly(0);
            assertThat(summary.medianBucket()).isEqualTo(ReportSummary.NO_MEDIAN_BUCKET);
        }

        @Test
        @DisplayName("sittings that nobody was marked in report their count and nothing else")
        void nothingScoredIsNotAZeroMean() {
            // Contrived: a stored record whose distribution accounts for nobody cannot reach
            // here through FrozenStatistics, but the arithmetic must not divide by it either
            // way, and asserting that is cheaper than proving it cannot happen.
            List<ReportRow> rows = new ArrayList<>();
            rows.add(row(1, "4821", 5, new ResultStatistics(0, 0, 0, 0, 0, 0, 0, 0,
                    List.of(0, 0, 0, 0, 0, 0, 0, 0, 0, 0))));

            ReportSummary summary = ReportSummary.across(rows);

            assertThat(summary.executions()).isEqualTo(1);
            assertThat(summary.participants()).isEqualTo(5);
            assertThat(summary.scored()).isZero();
            assertThat(summary.mean()).isZero();
            assertThat(summary.medianBucket()).isEqualTo(ReportSummary.NO_MEDIAN_BUCKET);
        }

        @Test
        @DisplayName("row order does not change any figure")
        void orderIsIrrelevant() {
            ReportSummary forwards = ReportSummary.across(twoSittings);
            ReportSummary backwards = ReportSummary.across(
                    List.of(twoSittings.get(1), twoSittings.get(0)));

            assertThat(backwards.mean()).isEqualTo(forwards.mean());
            assertThat(backwards.standardDeviation())
                    .isCloseTo(forwards.standardDeviation(), within(1e-12));
            assertThat(backwards.deciles()).isEqualTo(forwards.deciles());
            assertThat(backwards.medianBucket()).isEqualTo(forwards.medianBucket());
        }

        @Test
        @DisplayName("a null row list, or a null row, fails loudly rather than quietly")
        void nullsAreRejected() {
            List<ReportRow> withANull = new ArrayList<>();
            withANull.add(null);

            assertThatNullPointerException().isThrownBy(() -> ReportSummary.across(null));
            assertThatNullPointerException().isThrownBy(() -> ReportSummary.across(withANull));
        }

        @Test
        @DisplayName("a summary cannot be built with a distribution that is not ten buckets")
        void bucketWidthIsEnforced() {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new ReportSummary(1, 1, 1, 50, 0, 5, 50, 50, 1, 1, List.of(1)));
        }
    }

    // ===================== The rest of the shapes ========================

    @Nested
    @DisplayName("the wire shapes")
    class Shapes {

        @Test
        @DisplayName("a row cannot exist without the statistics it is there to compare")
        void rowNeedsStatistics() {
            assertThatNullPointerException().isThrownBy(() ->
                    new ReportRow(1, "4821", "e", "11", "c", OPENED, CLOSED, 8, null));
        }

        @Test
        @DisplayName("a row's unmarked count never goes negative")
        void unmarkedIsNeverNegative() {
            assertThat(row(1, "4821", 3, seededExecutionOne()).unmarked()).isZero();
            assertThat(row(1, "4821", 10, seededExecutionOne()).unmarked()).isEqualTo(2);
        }

        @Test
        @DisplayName("a subject needs an id, and a negative count is a server fault")
        void subjectValidation() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReportSubject(" ", "Dana", "dana.cohen", 1));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReportSubject("2", "Dana", "dana.cohen", -1));
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportSubject("2", null, "dana.cohen", 1));
            assertThat(new ReportSubject("2", "Dana", null, 0).detail()).isEmpty();
        }

        @Test
        @DisplayName("a subject with no sittings says so, rather than being hidden")
        void subjectWithNothingToReport() {
            assertThat(new ReportSubject("2", "Dana", "dana.cohen", 0).hasNothingToReport())
                    .isTrue();
            assertThat(new ReportSubject("2", "Dana", "dana.cohen", 3).hasNothingToReport())
                    .isFalse();
        }

        @Test
        @DisplayName("the picker opens on the first subject that has something to compare")
        void defaultSubjectSkipsTheEmptyOnes() {
            ReportSubjects subjects = new ReportSubjects(ReportDimension.BY_TEACHER, List.of(
                    new ReportSubject("1", "Avi", "avi", 0),
                    new ReportSubject("2", "Dana", "dana", 3)));

            assertThat(subjects.defaultSubject().id()).isEqualTo("2");
        }

        @Test
        @DisplayName("with every subject empty it opens on the first, rather than on nothing")
        void defaultSubjectFallsBackToTheFirst() {
            ReportSubjects subjects = new ReportSubjects(ReportDimension.BY_COURSE, List.of(
                    new ReportSubject("11", "Algebra", "Course 11", 0),
                    new ReportSubject("12", "Calculus", "Course 12", 0)));

            assertThat(subjects.defaultSubject().id()).isEqualTo("11");
            assertThat(new ReportSubjects(ReportDimension.BY_COURSE, List.of()).defaultSubject())
                    .isNull();
        }

        @Test
        @DisplayName("a request needs a subject to be about")
        void requestValidation() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReportRequest(ReportDimension.BY_COURSE, " "));
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportRequest(null, "11"));
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportSubjectsRequest(null));
        }

        @Test
        @DisplayName("every dimension carries the two labels the screen prints")
        void dimensionsCarryTheirLabels() {
            for (ReportDimension dimension : ReportDimension.values()) {
                assertThat(dimension.segment()).isNotBlank();
                assertThat(dimension.subjectNoun()).isNotBlank();
            }
            assertThat(ReportDimension.defaultDimension())
                    .isEqualTo(ReportDimension.BY_TEACHER);
            assertThat(ReportDimension.values()).hasSize(3);
        }

        @Test
        @DisplayName("the three dimension names are spelled as the contract spells them")
        void dimensionNamesAreTheWire() {
            // A dimension travels by name between two separately-shipped JARs, so valueOf is
            // the spelling assertion: referring to the constant would survive a rename.
            assertThat(ReportDimension.valueOf("BY_TEACHER"))
                    .isEqualTo(ReportDimension.BY_TEACHER);
            assertThat(ReportDimension.valueOf("BY_COURSE"))
                    .isEqualTo(ReportDimension.BY_COURSE);
            assertThat(ReportDimension.valueOf("BY_STUDENT"))
                    .isEqualTo(ReportDimension.BY_STUDENT);
        }

        @Test
        @DisplayName("a result with no rows is a present answer, not a missing one")
        void emptyResultIsAnAnswer() {
            ReportResult result = new ReportResult(ReportDimension.BY_TEACHER,
                    new ReportSubject("2", "Dana", "dana.cohen", 0), List.of(),
                    ReportSummary.EMPTY);

            assertThat(result.isEmpty()).isTrue();
            assertThat(result.summary()).isEqualTo(ReportSummary.EMPTY);
        }

        @Test
        @DisplayName("every payload survives a round trip through Java serialization")
        void serializable() throws Exception {
            ReportResult result = new ReportResult(ReportDimension.BY_COURSE,
                    new ReportSubject("11", "אלגברה", "Course 11", 1),
                    List.of(row(1, "4821", 8, seededExecutionOne())),
                    ReportSummary.across(List.of(row(1, "4821", 8, seededExecutionOne()))));

            ReportResult back = roundTrip(result);

            assertThat(back).isEqualTo(result);
            assertThat(back.rows().get(0).statistics().standardDeviation()).isEqualTo(17.5);
            assertThat(roundTrip(new ReportSubjects(ReportDimension.BY_STUDENT,
                    List.of(new ReportSubject("7", "Noa", "noa.friedman", 1)))))
                    .isEqualTo(new ReportSubjects(ReportDimension.BY_STUDENT,
                            List.of(new ReportSubject("7", "Noa", "noa.friedman", 1))));
            assertThat(roundTrip(new ReportRequest(ReportDimension.BY_TEACHER, "2")).subjectId())
                    .isEqualTo("2");
            assertThat(roundTrip(new ReportSubjectsRequest(ReportDimension.BY_COURSE)).dimension())
                    .isEqualTo(ReportDimension.BY_COURSE);
        }

        @Test
        @DisplayName("a result's row list is copied, so a caller cannot mutate a served answer")
        void rowsAreCopied() {
            List<ReportRow> mutable = new ArrayList<>();
            mutable.add(row(1, "4821", 8, seededExecutionOne()));
            ReportResult result = new ReportResult(ReportDimension.BY_TEACHER,
                    new ReportSubject("2", "Dana", "dana.cohen", 1), mutable,
                    ReportSummary.EMPTY);

            mutable.clear();

            assertThat(result.rows()).hasSize(1);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> result.rows().clear());
        }
    }

    // ===================== The data browser's shapes (A1, E15.2) =========

    @Nested
    @DisplayName("the data browser's wire shapes (amendment A1)")
    class DataBrowseShapes {

        private DataExamRow exam(int versions) {
            return new DataExamRow("101101", "מבחן אמצע", "11", "אלגברה", "דנה כהן", versions,
                    OPENED);
        }

        @Test
        @DisplayName("an exam row needs an identity, because a catalogue row without one is noise")
        void identityIsRequired() {
            assertThatNullPointerException().isThrownBy(() ->
                    new DataExamRow(null, "מבחן", "11", "אלגברה", "דנה כהן", 1, OPENED));
            assertThatNullPointerException().isThrownBy(() ->
                    new DataExamRow("101101", "מבחן", null, "אלגברה", "דנה כהן", 1, OPENED));
        }

        @Test
        @DisplayName("an exam cannot have no versions: that is a broken query, not a state to draw")
        void versionsMustBePositive() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> exam(0))
                    .withMessageContaining("0 versions");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> exam(-2));
        }

        @Test
        @DisplayName("the optional labels fold to empty, so no cell ever prints the word null")
        void missingLabelsFoldToEmpty() {
            DataExamRow row = new DataExamRow("101101", null, "11", null, null, 1, OPENED);

            assertThat(row.examName()).isEmpty();
            assertThat(row.courseName()).isEmpty();
            assertThat(row.authorName()).isEmpty();
        }

        @Test
        @DisplayName("an exam written once has not been revised; one written twice has")
        void revisionIsReadOffTheCount() {
            assertThat(exam(1).hasBeenRevised()).isFalse();
            assertThat(exam(2).hasBeenRevised()).isTrue();
        }

        @Test
        @DisplayName("both lists fold null to empty and are copied, so a served answer is frozen")
        void listsAreDefensive() {
            List<DataExamRow> mutableExams = new ArrayList<>(List.of(exam(1)));
            List<ReportRow> mutableRows =
                    new ArrayList<>(List.of(row(1, "4821", 8, seededExecutionOne())));
            DataExams exams = new DataExams(mutableExams);
            DataResults results = new DataResults(mutableRows);

            mutableExams.clear();
            mutableRows.clear();

            assertThat(exams.exams()).hasSize(1);
            assertThat(results.sittings()).hasSize(1);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> exams.exams().clear());
            assertThat(new DataExams(null).exams()).isEmpty();
            assertThat(new DataResults(null).sittings()).isEmpty();
        }

        @Test
        @DisplayName("EMPTY is an empty state to draw rather than an error to report")
        void emptyConstants() {
            assertThat(DataExams.EMPTY.isEmpty()).isTrue();
            assertThat(DataResults.EMPTY.isEmpty()).isTrue();
            assertThat(new DataExams(List.of(exam(1))).isEmpty()).isFalse();
            assertThat(new DataResults(List.of(row(1, "4821", 8, seededExecutionOne())))
                    .isEmpty()).isFalse();
        }

        @Test
        @DisplayName("both payloads survive a round trip through Java serialization")
        void roundTrips() throws Exception {
            DataExams exams = roundTrip(new DataExams(List.of(exam(3))));
            DataResults results = roundTrip(
                    new DataResults(List.of(row(1, "4821", 8, seededExecutionOne()))));

            assertThat(exams.exams().get(0).examName()).isEqualTo("מבחן אמצע");
            assertThat(exams.exams().get(0).versions()).isEqualTo(3);
            assertThat(results.sittings().get(0).statistics().standardDeviation())
                    .as("the frozen sigma crosses the wire untouched, browse or report")
                    .isEqualTo(17.5);
        }

        @Test
        @DisplayName("the value semantics records give for free really are there")
        void valueSemantics() {
            assertThat(exam(2)).isEqualTo(exam(2)).hasSameHashCodeAs(exam(2));
            assertThat(exam(2)).isNotEqualTo(exam(3));
            assertThat(exam(2).toString()).contains("101101");
            assertThat(DataExams.EMPTY).isEqualTo(new DataExams(List.of()));
            assertThat(DataResults.EMPTY).isEqualTo(new DataResults(List.of()));
            assertThat(DataExams.EMPTY.hashCode()).isEqualTo(new DataExams(List.of()).hashCode());
            assertThat(DataResults.EMPTY.toString()).contains("sittings");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T value) throws IOException, ClassNotFoundException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        try (ObjectInputStream in =
                     new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) in.readObject();
        }
    }
}
