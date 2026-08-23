package server.features.reports;

import common.dto.report.ReportDimension;
import common.dto.report.ReportResult;
import common.dto.report.ReportRow;
import common.dto.report.ReportSubject;
import common.dto.report.ReportSubjects;
import common.dto.results.ResultStatistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import server.db.entities.ExecutionStats;
import server.db.entities.ExecutionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link ReportEngine} and the three shipped strategies, against the seed (E15.3 ⚑ — F9.4).
 *
 * <p>The fixture is SEED_CONTENT's world, small enough to reason about and faithful in the four
 * places the rules turn on it:
 *
 * <ul>
 *   <li><b>Execution 1</b> (code 4821, Algebra, written by Dana): CLOSED, frozen at mean 72.5,
 *       median 72.5, population σ 17.5, min 45, max 100, 7 of 8 passed, sat by all eight Algebra
 *       students. The one sitting in the seed that a report can compare.</li>
 *   <li><b>Execution 2</b> (code 7390, Java, written by Avi): CLOSED and <b>awaiting grading</b>,
 *       so it has no frozen statistics.</li>
 *   <li><b>Execution 4</b> (code 2075, Algebra, written by Dana): LIVE — the S-2 second release
 *       of the same exam.</li>
 *   <li><b>A cancelled sitting with statistics on it</b>, which the seed does not contain and
 *       which is here on purpose: it is the only fixture that can tell "cancelled is excluded"
 *       apart from "cancelled sittings never have statistics anyway" (H15.2 ⚑).</li>
 * </ul>
 *
 * <p>Every assertion about a figure is against the seed document's table, so a divisor changing
 * anywhere on this path fails here rather than being noticed on a projector.
 */
class ReportEngineTest {

    private static final Instant SPRING = Instant.parse("2026-03-10T07:00:00Z");
    private static final Instant SUMMER = Instant.parse("2026-08-07T06:00:00Z");
    private static final Instant AUTUMN = Instant.parse("2026-11-02T08:00:00Z");

    private static final long DANA = 2;
    private static final long AVI = 4;
    private static final long RINA = 3;

    private static final long EXEC_ALGEBRA = 1;
    private static final long EXEC_JAVA = 2;
    private static final long EXEC_ALGEBRA_LIVE = 4;
    private static final long EXEC_CANCELLED = 5;

    /** SEED_CONTENT section 9.1's frozen record, verbatim. */
    private static final ExecutionStats SEEDED = new ExecutionStats(
            72.5, 72.5, 17.5, 45, 100, 0.875, List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2));

    /** The eight Algebra students of section 9.1, by seed id. */
    private static final long[] ALGEBRA_CLASS = {15, 7, 9, 14, 8, 11, 13, 10};

    private InMemoryReportStore store;
    private ReportEngine engine;

    @BeforeEach
    void setUp() {
        store = new InMemoryReportStore()
                .teacher(DANA, "דנה כהן", "dana.cohen")
                .teacher(RINA, "רינה ברק", "rina.barak")
                .teacher(AVI, "אבי מזרחי", "avi.mizrahi")
                .course("11", "אלגברה")
                .course("12", "חדו\"א")
                .course("21", "תכנות מונחה עצמים");
        for (long studentId : ALGEBRA_CLASS) {
            store.student(studentId, "תלמיד " + studentId, "student." + studentId);
        }
        store.student(99, "מישהי אחרת", "nobody.here");

        store.sitting(EXEC_ALGEBRA, "4821", SUMMER, "מבחן אמצע: אלגברה", "11", DANA,
                        ExecutionStatus.CLOSED, SEEDED)
                .sat(EXEC_ALGEBRA, ALGEBRA_CLASS)
                // Closed and awaiting grading: no frozen statistics, so nothing to compare.
                .sitting(EXEC_JAVA, "7390", SUMMER, "Java Fundamentals", "21", AVI,
                        ExecutionStatus.CLOSED, null)
                .sat(EXEC_JAVA, 11)
                // Live: the S-2 second release of Dana's Algebra exam.
                .sitting(EXEC_ALGEBRA_LIVE, "2075", AUTUMN, "מבחן אמצע: אלגברה", "11", DANA,
                        ExecutionStatus.LIVE, null)
                .sat(EXEC_ALGEBRA_LIVE, 11);

        engine = new ReportEngine(store, ReportStrategies.all());
    }

    /** Adds the contrived cancelled sitting that carries statistics (H15.2 ⚑). */
    private void cancelledSittingWithStatistics() {
        store.sitting(EXEC_CANCELLED, "9999", SPRING, "מבחן שבוטל", "11", DANA,
                        ExecutionStatus.CANCELLED, SEEDED)
                .sat(EXEC_CANCELLED, ALGEBRA_CLASS);
    }

    private static ReportRow onlyRow(ReportResult result) {
        assertThat(result.rows()).hasSize(1);
        return result.rows().get(0);
    }

    private ReportResult report(ReportDimension dimension, String subjectId) {
        return engine.report(dimension, subjectId).orElseThrow();
    }

    // ===================== The three populations =========================

    @Nested
    @DisplayName("the row population of each shipped dimension")
    class Populations {

        @Test
        @DisplayName("⚑ BY_TEACHER: execution 1's frozen 72.5 / 72.5 / 17.5 is Dana's row")
        void byTeacher() {
            ReportRow row = onlyRow(report(ReportDimension.BY_TEACHER, String.valueOf(DANA)));

            assertThat(row.executionId()).isEqualTo(EXEC_ALGEBRA);
            assertThat(row.code4()).isEqualTo("4821");
            assertThat(row.statistics().mean()).isEqualTo(72.5);
            assertThat(row.statistics().median()).isEqualTo(72.5);
            assertThat(row.statistics().standardDeviation()).isEqualTo(17.5);
            assertThat(row.participants()).isEqualTo(8);
        }

        @Test
        @DisplayName("⚑ BY_COURSE: the same sitting is Algebra's row")
        void byCourse() {
            ReportRow row = onlyRow(report(ReportDimension.BY_COURSE, "11"));

            assertThat(row.executionId()).isEqualTo(EXEC_ALGEBRA);
            assertThat(row.statistics().standardDeviation()).isEqualTo(17.5);
            assertThat(row.courseName()).isEqualTo("אלגברה");
        }

        @ParameterizedTest
        @ValueSource(longs = {15, 7, 9, 14, 8, 11, 13, 10})
        @DisplayName("⚑ BY_STUDENT: the same sitting is each of the eight students' row")
        void byStudent(long studentId) {
            ReportRow row = onlyRow(report(ReportDimension.BY_STUDENT,
                    String.valueOf(studentId)));

            assertThat(row.executionId()).isEqualTo(EXEC_ALGEBRA);
            assertThat(row.statistics().mean()).isEqualTo(72.5);
            assertThat(row.statistics().passCount())
                    .as("the class's pass count, reconstituted from the stored rate")
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("a student who sat nothing gets a present report with no rows, not a refusal")
        void studentWhoSatNothing() {
            ReportResult result = report(ReportDimension.BY_STUDENT, "99");

            assertThat(result.isEmpty()).isTrue();
            assertThat(result.subject().label()).isEqualTo("מישהי אחרת");
            assertThat(result.summary().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("a course whose only sitting is unmarked has nothing to compare")
        void courseAwaitingGrading() {
            ReportResult result = report(ReportDimension.BY_COURSE, "21");

            assertThat(result.rows())
                    .as("closed, sat, and awaiting grading is not comparable")
                    .isEmpty();
        }

        @Test
        @DisplayName("a live sitting is not in a report, and neither is a scheduled one")
        void liveIsNotReportable() {
            ReportResult result = report(ReportDimension.BY_TEACHER, String.valueOf(DANA));

            assertThat(result.rows()).extracting(ReportRow::executionId)
                    .doesNotContain(EXEC_ALGEBRA_LIVE);
        }

        @Test
        @DisplayName("the teacher is the exam's author, not whoever released the room (S-35)")
        void authorRatherThanRunner() {
            // Rina released nothing and wrote nothing. If the dimension keyed on created_by
            // instead, this report would be about a sitting she ran.
            assertThat(report(ReportDimension.BY_TEACHER, String.valueOf(RINA)).rows()).isEmpty();
        }

        @Test
        @DisplayName("rows come back oldest first, so a comparison reads left to right")
        void oldestFirst() {
            store.sitting(11, "1111", SPRING, "מבחן אמצע: אלגברה", "11", DANA,
                    ExecutionStatus.CLOSED, SEEDED).sat(11, ALGEBRA_CLASS);
            store.sitting(12, "2222", AUTUMN, "מבחן אמצע: אלגברה", "11", DANA,
                    ExecutionStatus.CLOSED, SEEDED).sat(12, ALGEBRA_CLASS);

            ReportResult result = report(ReportDimension.BY_TEACHER, String.valueOf(DANA));

            assertThat(result.rows()).extracting(ReportRow::code4)
                    .containsExactly("1111", "4821", "2222");
        }
    }

    // ===================== H15.2 =========================================

    @Nested
    @DisplayName("cancelled sittings ⚑ (H15.2)")
    class CancelledExcluded {

        @Test
        @DisplayName("⚑ a cancelled sitting is excluded even when statistics are frozen on it")
        void cancelledIsExcluded() {
            cancelledSittingWithStatistics();

            ReportResult byTeacher = report(ReportDimension.BY_TEACHER, String.valueOf(DANA));
            ReportResult byCourse = report(ReportDimension.BY_COURSE, "11");
            ReportResult byStudent = report(ReportDimension.BY_STUDENT, "11");

            assertThat(byTeacher.rows()).extracting(ReportRow::executionId)
                    .containsExactly(EXEC_ALGEBRA);
            assertThat(byCourse.rows()).extracting(ReportRow::executionId)
                    .containsExactly(EXEC_ALGEBRA);
            assertThat(byStudent.rows()).extracting(ReportRow::executionId)
                    .containsExactly(EXEC_ALGEBRA);
        }

        @Test
        @DisplayName("⚑ a cancelled sitting is not counted in the picker either")
        void cancelledIsNotCounted() {
            cancelledSittingWithStatistics();

            ReportSubjects subjects =
                    engine.subjects(ReportDimension.BY_TEACHER).orElseThrow();

            assertThat(subjectNamed(subjects, String.valueOf(DANA)).executions())
                    .as("a count of two would promise a comparison the table cannot draw")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("⚑ a cancelled sitting cannot move the summary")
        void cancelledCannotMoveTheSummary() {
            ReportResult before = report(ReportDimension.BY_COURSE, "11");
            cancelledSittingWithStatistics();
            ReportResult after = report(ReportDimension.BY_COURSE, "11");

            assertThat(after.summary()).isEqualTo(before.summary());
        }
    }

    // ===================== The frozen-statistics pin =====================

    @Nested
    @DisplayName("statistics are read, never recomputed ⚑ (F8.5, H14.4)")
    class FrozenPin {

        @Test
        @DisplayName("⚑ a stored record that contradicts itself is served exactly as stored")
        void storedValuesReachTheWireVerbatim() {
            // A sitting whose stored sigma is impossible for its own distribution, and whose
            // mean is nowhere near the middle of its buckets. If anything on this path
            // recomputed a figure, these numbers would be corrected on the way out - which is
            // precisely the failure the frozen column exists to prevent, because the corrected
            // ones would look right.
            ExecutionStats contradictory = new ExecutionStats(
                    12.5, 88.0, 3.0, 0, 100, 0.125, List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2));
            store.sitting(21, "3333", AUTUMN, "מבחן", "12", DANA, ExecutionStatus.CLOSED,
                    contradictory).sat(21, ALGEBRA_CLASS);

            ResultStatistics served =
                    onlyRow(report(ReportDimension.BY_COURSE, "12")).statistics();

            assertThat(served.mean()).isEqualTo(12.5);
            assertThat(served.median()).isEqualTo(88.0);
            assertThat(served.standardDeviation()).isEqualTo(3.0);
            assertThat(served.min()).isZero();
            assertThat(served.max()).isEqualTo(100);
            assertThat(served.deciles()).containsExactly(0, 0, 0, 0, 1, 1, 1, 2, 1, 2);
            assertThat(served.passRate()).isEqualTo(0.125);
            assertThat(served.passCount())
                    .as("round(0.125 x 8): arithmetic on two stored numbers, not the pass mark "
                            + "applied a second time")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the population is the sum of the stored buckets, not a count of grades")
        void populationComesFromTheDistribution() {
            ResultStatistics served =
                    onlyRow(report(ReportDimension.BY_COURSE, "11")).statistics();

            assertThat(served.count()).isEqualTo(8);
        }

        @Test
        @DisplayName("a sitting whose stored record is unusable is left out rather than zeroed")
        void unusableRecordIsDropped() {
            // Six buckets. FrozenStatistics answers absent and logs; a report cannot render
            // "grading unfinished" for a row whose whole purpose is to be compared, and
            // including it as zeros would pull the pooled mean down invisibly.
            ExecutionStats malformed =
                    new ExecutionStats(70, 70, 5, 50, 90, 0.9, List.of(1, 1, 1, 1, 1, 1));
            store.sitting(22, "4444", AUTUMN, "מבחן", "12", DANA, ExecutionStatus.CLOSED,
                    malformed).sat(22, ALGEBRA_CLASS);

            ReportResult result = report(ReportDimension.BY_COURSE, "12");

            assertThat(result.rows()).isEmpty();
            assertThat(result.summary().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("the summary of the seeded sitting is the seeded sitting")
        void singleSittingSummaryMatchesTheSeed() {
            ReportResult result = report(ReportDimension.BY_COURSE, "11");

            assertThat(result.summary().mean()).isEqualTo(72.5);
            assertThat(result.summary().standardDeviation()).isCloseTo(17.5, within(1e-9));
            assertThat(result.summary().passCount()).isEqualTo(7);
            assertThat(result.summary().scored()).isEqualTo(8);
            assertThat(result.summary().participants()).isEqualTo(8);
            assertThat(result.summary().medianBucket())
                    .as("the fourth-lowest of eight is 70, which is the 70-79 band")
                    .isEqualTo(7);
        }
    }

    // ===================== Subjects ======================================

    @Nested
    @DisplayName("the subject pickers")
    class Subjects {

        @Test
        @DisplayName("every teacher is listed, with how many sittings each has to compare")
        void teacherSubjects() {
            ReportSubjects subjects =
                    engine.subjects(ReportDimension.BY_TEACHER).orElseThrow();

            assertThat(subjects.dimension()).isEqualTo(ReportDimension.BY_TEACHER);
            assertThat(subjects.subjects()).extracting(ReportSubject::label)
                    .containsExactlyInAnyOrder("דנה כהן", "רינה ברק", "אבי מזרחי");
            assertThat(subjectNamed(subjects, String.valueOf(DANA)).executions()).isEqualTo(1);
            assertThat(subjectNamed(subjects, String.valueOf(AVI)).hasNothingToReport())
                    .as("her sitting is closed and unmarked, so it counts for nothing yet")
                    .isTrue();
        }

        @Test
        @DisplayName("a teacher with nothing to report is listed rather than hidden (E15.5)")
        void emptySubjectsAreStillListed() {
            ReportSubjects subjects =
                    engine.subjects(ReportDimension.BY_TEACHER).orElseThrow();

            assertThat(subjectNamed(subjects, String.valueOf(RINA)).hasNothingToReport())
                    .isTrue();
        }

        @Test
        @DisplayName("courses are listed by code, with their names and their counts")
        void courseSubjects() {
            ReportSubjects subjects = engine.subjects(ReportDimension.BY_COURSE).orElseThrow();

            assertThat(subjects.subjects()).extracting(ReportSubject::id)
                    .containsExactly("11", "12", "21");
            assertThat(subjectNamed(subjects, "11").label()).isEqualTo("אלגברה");
            assertThat(subjectNamed(subjects, "11").detail()).isEqualTo("Course 11");
            assertThat(subjectNamed(subjects, "11").executions()).isEqualTo(1);
            assertThat(subjectNamed(subjects, "12").executions()).isZero();
        }

        @Test
        @DisplayName("students are listed with the sittings they sat")
        void studentSubjects() {
            ReportSubjects subjects = engine.subjects(ReportDimension.BY_STUDENT).orElseThrow();

            assertThat(subjects.subjects()).hasSize(ALGEBRA_CLASS.length + 1);
            assertThat(subjectNamed(subjects, "15").executions()).isEqualTo(1);
            assertThat(subjectNamed(subjects, "99").hasNothingToReport()).isTrue();
        }

        @Test
        @DisplayName("the detail line tells two people with the same name apart")
        void detailIsTheUsername() {
            ReportSubjects subjects = engine.subjects(ReportDimension.BY_TEACHER).orElseThrow();

            assertThat(subjectNamed(subjects, String.valueOf(DANA)).detail())
                    .isEqualTo("dana.cohen");
        }
    }

    // ===================== Refusals ======================================

    @Nested
    @DisplayName("subjects that do not exist")
    class UnknownSubjects {

        @Test
        @DisplayName("an id that names nobody is an empty answer, on every dimension")
        void unknownSubject() {
            assertThat(engine.report(ReportDimension.BY_TEACHER, "9999")).isEmpty();
            assertThat(engine.report(ReportDimension.BY_COURSE, "zz")).isEmpty();
            assertThat(engine.report(ReportDimension.BY_STUDENT, "9999")).isEmpty();
        }

        @Test
        @DisplayName("an id of the wrong shape is empty rather than an exception on a socket")
        void malformedId() {
            assertThat(engine.report(ReportDimension.BY_TEACHER, "not-a-number")).isEmpty();
            assertThat(engine.report(ReportDimension.BY_STUDENT, "  ")).isEmpty();
        }

        @Test
        @DisplayName("a teacher's id is not a student's: the roles do not resolve each other")
        void rolesDoNotCrossOver() {
            // Dana is a teacher. Asking for a student report about her id must not answer with
            // a teacher's name and a teacher's rows.
            assertThat(engine.report(ReportDimension.BY_STUDENT, String.valueOf(DANA)))
                    .isEmpty();
            assertThat(engine.report(ReportDimension.BY_TEACHER, "15")).isEmpty();
        }
    }

    // ===================== Registration ==================================

    @Nested
    @DisplayName("registration")
    class Registration {

        @Test
        @DisplayName("the three shipped strategies are the three dimensions, exactly")
        void shippedStrategies() {
            assertThat(engine.served())
                    .containsExactlyInAnyOrder(ReportDimension.values());
            assertThat(ReportStrategies.all()).hasSize(ReportDimension.values().length);
        }

        @Test
        @DisplayName("two strategies claiming one dimension is a startup failure, not a coin toss")
        void duplicateDimensionsAreRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReportEngine(store,
                            List.of(new ByCourseStrategy(), new ByCourseStrategy())))
                    .withMessageContaining("BY_COURSE")
                    .withMessageContaining("ReportStrategies");
        }

        @Test
        @DisplayName("an engine serving nothing answers empty rather than throwing")
        void unservedDimension() {
            ReportEngine bare = new ReportEngine(store, List.of());

            assertThat(bare.subjects(ReportDimension.BY_COURSE)).isEmpty();
            assertThat(bare.report(ReportDimension.BY_COURSE, "11")).isEmpty();
        }
    }

    private static ReportSubject subjectNamed(ReportSubjects subjects, String id) {
        Optional<ReportSubject> found = subjects.subjects().stream()
                .filter(subject -> subject.id().equals(id))
                .findFirst();
        assertThat(found).as("subject %s is in the picker", id).isPresent();
        return found.orElseThrow();
    }
}
