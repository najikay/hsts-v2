package server.db.seed;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import server.features.grading.ScoreStatistics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every number in {@code SEED_CONTENT.md} that is derived from another number in it (E22).
 *
 * <h2>Why this exists</h2>
 *
 * <p>The seed document states the same facts twice on purpose — a per-question answer grid
 * <em>and</em> the score it produces, a grade column <em>and</em> the statistics frozen from it,
 * a roster <em>and</em> a notification quoting its size. Stating them twice is what makes the
 * document readable and hand-checkable. It is also what lets the two halves drift, and a drifted
 * seed is the worst kind of defect this project can carry: everything loads, every test passes,
 * and the demo shows a class average that the class's own scores do not produce.
 *
 * <p>That is not hypothetical. §9 originally carried auto-scores like 92 and 78 that <b>no
 * combination of the exam's questions can yield</b> — invisible while the seed was only demo
 * data, and wrong the moment {@code AutoGrader} recomputed it. It was caught by hand. This is
 * the test that would have caught it in the build.
 *
 * <h2>The rule it enforces</h2>
 *
 * <p><b>Nothing here hardcodes an expected value.</b> Every figure is read from the document
 * through {@link SeedDocument} and recomputed from other figures read from the same document.
 * A test asserting {@code mean == 72.5} would only prove that someone typed 72.5 in two places;
 * this one proves that 72.5 is what the eight grades above it actually average to. The one thing
 * that is hardcoded is the <em>relationship</em> — that a mean is a mean — which is the part
 * that must not be read from the document.
 *
 * <p>The scoring is done by {@link server.features.grading.AutoGrader}'s own rules, reimplemented
 * here in three lines rather than invoked, and that is deliberate: invoking the grader would make
 * this test agree with the product by construction. What it needs to check is that the
 * <em>document</em> agrees with the product, so the arithmetic is stated independently and the
 * two are compared.
 *
 * <h2>The cross-section checks are the interesting ones</h2>
 *
 * <p>{@code CrossSection} holds the assertions nothing else in the build could make, because
 * they span parts of the document that no single loader or database test reads together: a
 * notification title in §11 quoting a mean computed in §9.1, and another quoting a roster size
 * from §9.2. Derived data in a text column is the class of fact that rots silently — the mean in
 * that title read 78 until the score fix, and only a human noticed.
 */
@DisplayName("SEED_CONTENT.md arithmetic")
class SeedArithmeticTest {

    /** The pass mark, from the product rather than from the document (PRD §6). */
    private static final int PASS_MARK = ScoreStatistics.PASS_MARK;

    private static SeedDocument seed;

    @BeforeAll
    static void readTheDocument() {
        seed = SeedDocument.read();
    }

    // ===================== Shared derivations ============================

    /** The correct option of every question, by display id, from §7. */
    private static Map<String, Integer> answerKey() {
        Map<String, Integer> key = new HashMap<>();
        for (SeedDocument.QuestionRow question : seed.questions()) {
            key.put(question.displayId(), question.correct());
        }
        return key;
    }

    /**
     * One exam version's slots, in exam order.
     *
     * @param exam    the exam number
     * @param version the version number
     */
    private static List<SeedDocument.CompositionRow> slotsOf(int exam, int version) {
        List<SeedDocument.CompositionRow> slots = new ArrayList<>();
        for (SeedDocument.CompositionRow slot : seed.composition()) {
            if (slot.exam() == exam && slot.examVersion() == version) {
                slots.add(slot);
            }
        }
        return slots;
    }

    /** Which exam version an execution sat, from §9. */
    private static SeedDocument.ExecutionRow executionRow(int number) {
        return seed.executions().stream()
                .filter(execution -> execution.number() == number)
                .findFirst()
                .orElseThrow(() -> new AssertionError("§9 has no execution " + number));
    }

    /** What each student picked, by question, for one execution. */
    private static Map<String, Map<String, Integer>> selectionsByStudent(int execution) {
        Map<String, Map<String, Integer>> byStudent = new LinkedHashMap<>();
        for (SeedDocument.SelectionRow row : seed.selections(execution)) {
            Map<String, Integer> answers =
                    byStudent.computeIfAbsent(row.student(), key -> new LinkedHashMap<>());
            if (row.answered()) {
                answers.put(row.question(), row.selected());
            }
            // An unanswered cell is deliberately absent rather than present with a sentinel:
            // that is exactly how GradingReads hands answers to the grader (F6.9).
        }
        return byStudent;
    }

    /**
     * The score the document's own grid produces, by the grader's rules stated independently.
     *
     * <p>Unanswered scores zero (§6). A wrong answer scores zero. There is no partial credit;
     * an override is a separate column and is not this arithmetic.
     */
    private static int scoreFrom(List<SeedDocument.CompositionRow> slots,
                                 Map<String, Integer> key,
                                 Map<String, Integer> chosen) {
        int score = 0;
        for (SeedDocument.CompositionRow slot : slots) {
            Integer selected = chosen.get(slot.question());
            Integer correct = key.get(slot.question());
            assertThat(correct)
                    .as("§7 states no correct answer for question %s, which §8.1 puts on an exam",
                            slot.question())
                    .isNotNull();
            if (selected != null && selected.equals(correct)) {
                score += slot.points();
            }
        }
        return score;
    }

    /** Every total the given point values can add up to. */
    private static Set<Integer> reachableTotals(List<SeedDocument.CompositionRow> slots) {
        Set<Integer> totals = new TreeSet<>();
        totals.add(0);
        for (SeedDocument.CompositionRow slot : slots) {
            Set<Integer> extended = new LinkedHashSet<>(totals);
            for (Integer total : totals) {
                extended.add(total + slot.points());
            }
            totals.addAll(extended);
        }
        return totals;
    }

    // ===================== Exam composition ==============================

    @Nested
    @DisplayName("§8.1 composition")
    class Composition {

        @Test
        @DisplayName("every exam version's points sum to exactly 100")
        void pointsSumToOneHundred() {
            Map<String, Integer> totals = new LinkedHashMap<>();
            for (SeedDocument.CompositionRow slot : seed.composition()) {
                totals.merge(slot.exam() + " v" + slot.examVersion(), slot.points(), Integer::sum);
            }

            assertThat(totals).isNotEmpty();
            assertThat(totals).allSatisfy((version, total) -> assertThat(total)
                    .as("exam %s totals %s points; §8.1 says every row sums to 100, and "
                            + "AutoGrader refuses to grade a version that does not", version, total)
                    .isEqualTo(100));
        }

        @Test
        @DisplayName("no exam version lists the same question twice")
        void noQuestionAppearsTwiceInAVersion() {
            Map<String, Set<String>> seen = new LinkedHashMap<>();
            for (SeedDocument.CompositionRow slot : seed.composition()) {
                String version = slot.exam() + " v" + slot.examVersion();
                boolean fresh = seen.computeIfAbsent(version, key -> new LinkedHashSet<>())
                        .add(slot.question());
                assertThat(fresh)
                        .as("exam %s lists question %s more than once, which the "
                                + "UNIQUE(exam_version_id, question_id) index forbids",
                                version, slot.question())
                        .isTrue();
            }
        }
    }

    // ===================== The scores =====================================

    @Nested
    @DisplayName("Auto-scores recomputed from the answer grids")
    class AutoScores {

        @Test
        @DisplayName("§9.1 — every auto score is what §9.1.1's selections actually produce")
        void executionOneScoresAreWhatTheGridProduces() {
            assertScoresMatchTheGrid(1);
        }

        @Test
        @DisplayName("§9.2 — every auto score is what §9.2.1's selections actually produce")
        void executionTwoScoresAreWhatTheGridProduces() {
            assertScoresMatchTheGrid(2);
        }

        private void assertScoresMatchTheGrid(int execution) {
            SeedDocument.ExecutionRow row = executionRow(execution);
            List<SeedDocument.CompositionRow> slots = slotsOf(row.exam(), row.examVersion());
            assertThat(slots)
                    .as("§8.1 lists no questions for exam %s v%s, which execution %s sat",
                            row.exam(), row.examVersion(), execution)
                    .isNotEmpty();

            Map<String, Integer> key = answerKey();
            Map<String, Map<String, Integer>> chosen = selectionsByStudent(execution);
            List<SeedDocument.GradeRow> grades = seed.grades(execution);

            // A grid that covered nobody would make every assertion below pass vacuously.
            assertThat(chosen).as("§9.%s.1 produced no selections", execution).isNotEmpty();

            for (SeedDocument.GradeRow grade : grades) {
                Map<String, Integer> answers = chosen.get(grade.student());
                assertThat(answers)
                        .as("§9.%s.1's grid has no row for %s, who §9.%s has a grade for",
                                execution, grade.student(), execution)
                        .isNotNull();

                assertThat(scoreFrom(slots, key, answers))
                        .as("%s: §9.%s says auto %s, but §9.%s.1's selections score "
                                        + "differently. One of the two was edited without "
                                        + "the other",
                                grade.student(), execution, grade.auto(), execution)
                        .isEqualTo(grade.auto());
            }
        }

        @Test
        @DisplayName("the grid covers exactly the students who have grades — no extras, no gaps")
        void gridAndGradeRosterAgree() {
            for (int execution : List.of(1, 2)) {
                Set<String> graded = new LinkedHashSet<>();
                for (SeedDocument.GradeRow grade : seed.grades(execution)) {
                    graded.add(grade.student());
                }

                assertThat(selectionsByStudent(execution).keySet())
                        .as("execution %s: the answer grid and the grade table name different "
                                + "students", execution)
                        .containsExactlyInAnyOrderElementsOf(graded);
            }
        }

        @Test
        @DisplayName("every auto score is a total this exam can actually produce")
        void everyScoreIsReachable() {
            for (int execution : List.of(1, 2)) {
                SeedDocument.ExecutionRow row = executionRow(execution);
                Set<Integer> reachable = reachableTotals(slotsOf(row.exam(), row.examVersion()));

                for (SeedDocument.GradeRow grade : seed.grades(execution)) {
                    assertThat(reachable)
                            .as("%s scored %s in execution %s, which no combination of exam %s "
                                            + "v%s's questions can yield — this is the defect "
                                            + "class that put 92 and 78 in an earlier draft",
                                    grade.student(), grade.auto(), execution,
                                    row.exam(), row.examVersion())
                            .contains(grade.auto());
                }
            }
        }

        @Test
        @DisplayName("§9.2 approves nothing, so no row there has a final score (S-24)")
        void executionTwoHasNoApprovedGrades() {
            assertThat(seed.grades(2))
                    .as("§9.2 is the awaiting-grading fixture; a final score there would make "
                            + "MY_GRADES_GET return something for a student it must not")
                    .allSatisfy(grade -> assertThat(grade.finalScore()).isNull());
        }
    }

    // ===================== The frozen statistics ==========================

    @Nested
    @DisplayName("§9.1's frozen stats, recomputed from the grades above them")
    class FrozenStatistics {

        private ScoreStatistics recomputed() {
            List<Integer> finals = new ArrayList<>();
            for (SeedDocument.GradeRow grade : seed.grades(1)) {
                // The final column is what the stats are frozen from (S-25): the override
                // counts, which is the whole reason yael.azulay's 45 becomes a 55 here.
                finals.add(grade.finalScore() == null ? grade.auto() : grade.finalScore());
            }
            Optional<ScoreStatistics> stats = ScoreStatistics.of(finals);
            assertThat(stats).as("§9.1 has no grades to compute statistics from").isPresent();
            return stats.get();
        }

        @Test
        @DisplayName("mean, median and the population standard deviation all agree")
        void meanMedianAndSigma() {
            SeedDocument.FrozenStats stated = seed.frozenStats();
            ScoreStatistics actual = recomputed();

            assertThat(actual.mean()).isEqualTo(stated.mean());
            assertThat(actual.median()).isEqualTo(stated.median());
            // Population sigma, divisor n — open question 4, settled. The sample form would
            // differ by about a point here and would look like a rounding bug rather than a
            // different formula.
            assertThat(actual.standardDeviation()).isEqualTo(stated.stddev());
        }

        @Test
        @DisplayName("min and max are the grades' own")
        void minAndMax() {
            SeedDocument.FrozenStats stated = seed.frozenStats();
            ScoreStatistics actual = recomputed();

            assertThat(actual.min()).isEqualTo(stated.min());
            assertThat(actual.max()).isEqualTo(stated.max());
        }

        @Test
        @DisplayName("the pass rate counts every scored attempt, timed-out ones included")
        void passRate() {
            SeedDocument.FrozenStats stated = seed.frozenStats();
            ScoreStatistics actual = recomputed();

            assertThat(actual.passRate()).isEqualTo(stated.passRate());
            // A timed-out attempt was sat and failed; dropping it from the denominator would
            // flatter the class and contradict H12.4.
            assertThat(actual.count()).isEqualTo(seed.grades(1).size());
        }

        @Test
        @DisplayName("the deciles are the grades' own distribution")
        void deciles() {
            assertThat(recomputed().deciles())
                    .as("§9.1's decile row and its grade column disagree")
                    .isEqualTo(seed.frozenStats().deciles());
        }

        @Test
        @DisplayName("the pass mark the document reasons about is the product's")
        void passMarkMatchesTheProduct() {
            long passing = seed.grades(1).stream()
                    .map(grade -> grade.finalScore() == null ? grade.auto() : grade.finalScore())
                    .filter(score -> score >= PASS_MARK)
                    .count();

            assertThat((double) passing / seed.grades(1).size())
                    .as("§9.1's stated pass rate does not match counting scores at or above the "
                            + "product's pass mark of %s", PASS_MARK)
                    .isEqualTo(seed.frozenStats().passRate());
        }

        @Test
        @DisplayName("the override is what lifts the pass rate — the document's stated point")
        void theOverrideIsWhatChangesThePassRate() {
            List<SeedDocument.GradeRow> grades = seed.grades(1);
            long passingOnAuto = grades.stream()
                    .filter(grade -> grade.auto() >= PASS_MARK).count();
            long passingOnFinal = grades.stream()
                    .map(grade -> grade.finalScore() == null ? grade.auto() : grade.finalScore())
                    .filter(score -> score >= PASS_MARK).count();

            // §9.1 says in prose: "the only fail turned into a pass". If an edit ever makes the
            // override cosmetic, the sentence stops being true and the T-8.3 demo loses its point.
            assertThat(passingOnFinal)
                    .as("§9.1's manual override no longer changes whether anybody passes, so the "
                            + "S-23 demo has nothing to show")
                    .isEqualTo(passingOnAuto + 1);
        }
    }

    // ===================== Participation ==================================

    @Nested
    @DisplayName("§9.1's frozen participation")
    class Participation {

        @Test
        @DisplayName("started, finished and timed_out are the attempt-status column's own counts")
        void countsMatchTheAttemptStatuses() {
            List<SeedDocument.GradeRow> grades = seed.grades(1);
            long submitted = grades.stream()
                    .filter(grade -> grade.attemptStatus().equals("SUBMITTED")).count();
            long timedOut = grades.stream()
                    .filter(grade -> grade.attemptStatus().equals("TIMED_OUT")).count();

            SeedDocument.Participation stated = seed.participation();

            assertThat(stated.started()).isEqualTo(grades.size());
            assertThat(stated.finished()).isEqualTo((int) submitted);
            assertThat(stated.timedOut()).isEqualTo((int) timedOut);
            // And the two halves account for everybody: a third status would be silently lost.
            assertThat(stated.finished() + stated.timedOut()).isEqualTo(stated.started());
        }
    }

    // ===================== Across sections ================================

    @Nested
    @DisplayName("Derived data quoted in other sections")
    class CrossSection {

        private String titleOf(String seedId) {
            return seed.notifications().stream()
                    .filter(notification -> notification.seedId().equals(seedId))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("§11 has no notification " + seedId))
                    .title();
        }

        @Test
        @DisplayName("N-EXEC-CLOSED-ALG quotes §9.1's mean, in a text column")
        void closedExecutionNotificationQuotesTheMean() {
            String title = titleOf("N-EXEC-CLOSED-ALG");
            double mean = seed.frozenStats().mean();

            // It read 78 until the score fix. Nothing but a human noticed, because a title is
            // a string and no loader compares it to anything.
            assertThat(title)
                    .as("§11's N-EXEC-CLOSED-ALG quotes an average that is not §9.1's %s", mean)
                    .contains(trimTrailingZero(mean));
        }

        @Test
        @DisplayName("N-EXEC-CLOSED-ALG quotes §9.1's roster size too")
        void closedExecutionNotificationQuotesTheRoster() {
            assertThat(titleOf("N-EXEC-CLOSED-ALG"))
                    .as("§11's N-EXEC-CLOSED-ALG quotes a headcount that is not §9.1's")
                    .contains(String.valueOf(seed.grades(1).size()));
        }

        @Test
        @DisplayName("N-GRADING-DUE-JAVA quotes §9.2's number of awaiting attempts")
        void gradingDueNotificationQuotesTheJavaRoster() {
            assertThat(titleOf("N-GRADING-DUE-JAVA"))
                    .as("§11's N-GRADING-DUE-JAVA quotes a count that is not §9.2's %s rows",
                            seed.grades(2).size())
                    .contains(String.valueOf(seed.grades(2).size()));
        }

        @Test
        @DisplayName("a grade notification exists for each student §9.1 says was told")
        void gradePublishedNotificationsNameStudentsWhoHaveGrades() {
            Set<String> graded = new LinkedHashSet<>();
            for (SeedDocument.GradeRow grade : seed.grades(1)) {
                graded.add(grade.student());
            }

            List<String> published = seed.notifications().stream()
                    .filter(notification -> notification.type().equals("GRADE_PUBLISHED"))
                    .map(SeedDocument.NotificationRow::recipient)
                    .toList();

            assertThat(published)
                    .as("§11 publishes a grade to somebody §9.1 has no grade for")
                    .isNotEmpty()
                    .allSatisfy(recipient -> assertThat(graded).contains(recipient));
        }

        /**
         * A number as the document would write it, not as {@code Double.toString} would.
         *
         * <p>A whole mean of 80 prints as {@code 80.0} from a double and is written {@code 80}
         * in a Hebrew notification title, so comparing the raw string would fail on a seed that
         * is perfectly consistent.
         */
        private static String trimTrailingZero(double value) {
            return value == Math.rint(value)
                    ? String.valueOf((long) value)
                    : String.valueOf(value);
        }
    }
}
