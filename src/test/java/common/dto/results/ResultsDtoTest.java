package common.dto.results;

import common.dto.grading.GradeState;
import common.dto.grading.StudentGradeRow;
import common.protocol.Message;
import common.protocol.Verb;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The E14 results wire contract, as types (E14.1 — {@code docs/contracts/RESULTS_WIRE_CONTRACT.md}).
 *
 * <p>These records travel between two separately-built JARs and are read back through their
 * canonical constructors, so what is asserted here is what a careless edit would break on the
 * receiving side: the round trip, the defensive copies running again after deserialization, and
 * the one structural rule the contract makes — a decile distribution is ten buckets or it is
 * not a distribution.
 *
 * <p>The fixture is the seeded execution 4821 throughout (finals 45, 55, 60, 70, 75, 85, 90,
 * 100), so a reader can check any number here against §9.1 of the seed document.
 */
class ResultsDtoTest {

    private static final Instant OPENED = Instant.parse("2026-08-07T06:00:00Z");
    private static final Instant CLOSED = Instant.parse("2026-08-07T08:00:00Z");

    /** §9.1's frozen statistics, in wire form. */
    private static ResultStatistics seededStats() {
        return new ResultStatistics(8, 72.5, 72.5, 17.5, 45, 100, 7, 0.875,
                List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2));
    }

    private static ExecutionResultRow seededExecution() {
        return new ExecutionResultRow(1, "4821", OPENED, CLOSED, ExecutionState.CLOSED,
                8, 8, true, false);
    }

    @Nested
    @DisplayName("ResultStatistics")
    class Statistics {

        @Test
        @DisplayName("round-trips every stored figure unchanged")
        void roundTrips() throws Exception {
            ResultStatistics restored = roundTrip(seededStats());

            assertThat(restored).isEqualTo(seededStats());
            assertThat(restored.mean()).isEqualTo(72.5);
            assertThat(restored.standardDeviation())
                    .as("population sigma, divisor n — the sample form would be 18.71")
                    .isEqualTo(17.5);
            assertThat(restored.deciles()).containsExactly(0, 0, 0, 0, 1, 1, 1, 2, 1, 2);
        }

        @Test
        @DisplayName("a distribution that is not ten buckets is refused, on both sides of the wire")
        void decilesMustBeTen() {
            assertThatThrownBy(() -> new ResultStatistics(8, 72.5, 72.5, 17.5, 45, 100, 7, 0.875,
                    List.of(1, 2, 3)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("10 buckets");
        }

        @Test
        @DisplayName("a null bucket is refused rather than counted as zero")
        void nullBucketIsRefused() {
            List<Integer> holed = new ArrayList<>(List.of(0, 0, 0, 0, 1, 1, 1, 2, 1));
            holed.add(null);

            assertThatThrownBy(() -> new ResultStatistics(8, 72.5, 72.5, 17.5, 45, 100, 7, 0.875,
                    holed))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("the deciles are copied, so a caller's later edit cannot reach the wire")
        void decilesAreCopied() {
            List<Integer> mutable = new ArrayList<>(List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2));
            ResultStatistics stats = new ResultStatistics(8, 72.5, 72.5, 17.5, 45, 100, 7,
                    0.875, mutable);

            mutable.set(0, 99);

            assertThat(stats.deciles().get(0)).isZero();
        }

        @Test
        @DisplayName("the pass rate is a fraction and the percentage is derived from it, not stored twice")
        void passRateIsAFraction() {
            assertThat(seededStats().passRate()).isEqualTo(0.875);
            assertThat(seededStats().passPercent()).isEqualTo(87.5);
            assertThat(ResultStatistics.PASS_MARK)
                    .as("F8.5's threshold, declared once so no screen invents a second one")
                    .isEqualTo(55);
        }

        @Test
        @DisplayName("one result is not chartable, two are")
        void chartableNeedsTwo() {
            ResultStatistics single = new ResultStatistics(1, 80, 80, 0, 80, 80, 1, 1.0,
                    List.of(0, 0, 0, 0, 0, 0, 0, 0, 1, 0));

            assertThat(single.isChartable()).isFalse();
            assertThat(seededStats().isChartable()).isTrue();
        }
    }

    @Nested
    @DisplayName("ExecutionResultRow")
    class ExecutionRow {

        @Test
        @DisplayName("round-trips, including the S-35 flag")
        void roundTrips() throws Exception {
            ExecutionResultRow foreign = new ExecutionResultRow(2, "7390", OPENED, CLOSED,
                    ExecutionState.CLOSED, 8, 6, false, true);

            ExecutionResultRow restored = roundTrip(foreign);

            assertThat(restored).isEqualTo(foreign);
            assertThat(restored.releasedByAnotherTeacher()).isTrue();
            assertThat(restored.hasStatistics()).isFalse();
        }

        @Test
        @DisplayName("marking progress is answerable without opening the sitting")
        void markingProgress() {
            assertThat(seededExecution().isFullyMarked()).isTrue();
            assertThat(new ExecutionResultRow(2, "7390", OPENED, CLOSED, ExecutionState.CLOSED,
                    8, 6, false, false).isFullyMarked()).isFalse();
            assertThat(new ExecutionResultRow(3, "5164", OPENED, CLOSED, ExecutionState.SCHEDULED,
                    0, 0, false, false).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("a null code, window or state is a bug worth failing on")
        void requiredFields() {
            assertThatThrownBy(() -> new ExecutionResultRow(1, null, OPENED, CLOSED,
                    ExecutionState.CLOSED, 0, 0, false, false))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ExecutionResultRow(1, "4821", OPENED, CLOSED,
                    null, 0, 0, false, false))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("TeacherResults")
    class Listing {

        @Test
        @DisplayName("an exam that was never released keeps its place with an empty list")
        void neverReleasedExamsStay() throws Exception {
            ExamResultRow released = new ExamResultRow(1, "101101", "מבחן אמצע: אלגברה",
                    "11", "אלגברה", List.of(seededExecution()));
            ExamResultRow drawer = new ExamResultRow(2, "101102", "בוחן: אי-שוויונות",
                    "11", "אלגברה", List.of());

            TeacherResults restored = roundTrip(new TeacherResults(List.of(released, drawer)));

            assertThat(restored.exams()).hasSize(2);
            assertThat(restored.exams().get(1).neverReleased()).isTrue();
            assertThat(restored.totalExecutions()).isEqualTo(1);
            assertThat(restored.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("the shared empty answer really is empty")
        void emptyConstant() {
            assertThat(TeacherResults.EMPTY.isEmpty()).isTrue();
            assertThat(TeacherResults.EMPTY.totalExecutions()).isZero();
        }

        @Test
        @DisplayName("the exam list is copied defensively, on both sides of the wire")
        void examsAreCopied() {
            List<ExamResultRow> mutable = new ArrayList<>();
            mutable.add(new ExamResultRow(1, "101101", "Algebra", "11", "אלגברה", List.of()));
            TeacherResults results = new TeacherResults(mutable);

            mutable.clear();

            assertThat(results.exams()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("ExecutionResults")
    class Detail {

        @Test
        @DisplayName("statistics arrive as an Optional so the absent case cannot be forgotten")
        void statisticsAreOptional() throws Exception {
            ExecutionResults unfinished = new ExecutionResults(seededExecution(), "Algebra",
                    "11", "אלגברה", List.of(approvedRow()), null);

            ExecutionResults restored = roundTrip(unfinished);

            assertThat(restored.statistics()).isEmpty();
            assertThat(restored.hasStatistics()).isFalse();
            assertThat(restored.isUnmarked()).isFalse();
        }

        @Test
        @DisplayName("a finished sitting carries its frozen statistics through the wire unchanged")
        void finishedSittingRoundTrips() throws Exception {
            ExecutionResults results = new ExecutionResults(seededExecution(), "Algebra",
                    "11", "אלגברה", List.of(approvedRow()), seededStats());

            ExecutionResults restored = roundTrip(results);

            assertThat(restored.statistics()).contains(seededStats());
            assertThat(restored.rows()).hasSize(1);
        }

        @Test
        @DisplayName("⚑ the override justification survives on the teacher path")
        void justificationIsCarriedForTeachers() throws Exception {
            StudentGradeRow adjusted = new StudentGradeRow(9, 13, "יעל אזולאי", 45, 55, 55,
                    GradeState.APPROVED, "ניתן ניקוד חלקי.", "שיפור ניכר.",
                    Instant.parse("2026-08-09T08:00:00Z"));

            ExecutionResults restored = roundTrip(new ExecutionResults(seededExecution(),
                    "Algebra", "11", "אלגברה", List.of(adjusted), seededStats()));

            // MyGrades strips this structurally; the teacher's own table is where S-23's
            // justification is supposed to be readable.
            assertThat(restored.rows().get(0).overrideReason()).isEqualTo("ניתן ניקוד חלקי.");
        }

        @Test
        @DisplayName("an execution nobody marked is unmarked rather than broken")
        void unmarked() {
            ExecutionResults results = new ExecutionResults(seededExecution(), "Algebra",
                    "11", "אלגברה", List.of(), null);

            assertThat(results.isUnmarked()).isTrue();
        }
    }

    @Test
    @DisplayName("a results DTO inside a Message envelope survives the same round-trip")
    void insideAnEnvelope() throws Exception {
        ExecutionResults payload = new ExecutionResults(seededExecution(), "Algebra", "11",
                "אלגברה", List.of(approvedRow()), seededStats());

        Message restored = roundTrip(
                Message.ok(Message.request(Verb.RESULTS_EXECUTION_GET,
                        new ExecutionResultsRequest(1)), payload));

        assertThat(restored.getVerb()).isEqualTo(Verb.RESULTS_EXECUTION_GET);
        assertThat(restored.getPayload()).isEqualTo(payload);
    }

    @Test
    @DisplayName("the request carries an execution id and nothing a client could widen scope with")
    void requestIsJustAnId() throws Exception {
        assertThat(roundTrip(new ExecutionResultsRequest(4821)).executionId()).isEqualTo(4821);
        assertThat(ExecutionResultsRequest.class.getRecordComponents()).hasSize(1);
    }

    private static StudentGradeRow approvedRow() {
        return new StudentGradeRow(1, 11, "מאיה לוי", 60, null, 60, GradeState.APPROVED,
                null, null, Instant.parse("2026-08-09T08:00:00Z"));
    }

    private static <T extends Serializable> T roundTrip(T original) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            @SuppressWarnings("unchecked")
            T restored = (T) in.readObject();
            return restored;
        }
    }
}
