package server.features.exambuild;

import common.dto.authoring.AutoComposeRequest;
import common.dto.authoring.ExamCreateRequest;
import common.dto.authoring.QuestionPin;
import common.dto.authoring.TopicQuota;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import server.db.projections.PinCandidate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExamValidator} - every rule the builder enforces before a write (E7.3, E7.8, §5).
 *
 * <p>The cases that matter here are the ones that would still pass if a rule were deleted, so
 * each is built so that it cannot:
 *
 * <ul>
 *   <li>{@code twoVersionsOfOneQuestionIsADuplicate} is T-3.9 and the only case that separates a
 *       correct duplicate check from the one that looks correct. Comparing
 *       {@code questionVersionId} passes every other case in this file.</li>
 *   <li>{@code aDeletedQuestionIsRefused} guards the rule with <b>no database backstop at all</b>.
 *       Delete the check and nothing underneath refuses the write.</li>
 *   <li>{@code pointsShortNamesTheGap} and {@code pointsOverNamesTheExcess} assert the sentence,
 *       not the refusal. T-3.2 watches the indicator go from wrong to right, and a generic
 *       "invalid" would pass a test that only checked that something was refused.</li>
 *   <li>{@code aNullPinIsNamedByPosition} covers the shape the lead's brief says survives
 *       deserialization on purpose, which is the one nothing upstream can catch.</li>
 * </ul>
 */
class ExamValidatorTest {

    private static final String COURSE = "11";
    private static final String OTHER_COURSE = "12";

    private static QuestionPin pin(long versionId, int points) {
        return new QuestionPin(versionId, points);
    }

    private static PinCandidate candidate(long versionId, long questionId, String course,
                                          boolean deleted) {
        return new PinCandidate(versionId, questionId, "1100" + questionId, course, deleted);
    }

    // ===================== Metadata (§5.3) ================================

    @Nested
    @DisplayName("metadata")
    class Metadata {

        @Test
        @DisplayName("a good form passes")
        void goodFormPasses() {
            assertThat(ExamValidator.metadataProblem("Algebra Midterm", 90, null, null))
                    .isEmpty();
        }

        @Test
        @DisplayName("⚑ both shapes of a missing name are refused, because the record folds one")
        void bothShapesOfMissingName() {
            // The record strips but does NOT blank-fold this field, so a client that sent spaces
            // arrives as "" and one that sent nothing arrives as null. A check on null alone
            // passes the first, and the lead's brief item 7 names exactly this.
            assertThat(ExamValidator.metadataProblem(null, 90, null, null))
                    .hasValueSatisfying(v ->
                            assertThat(v.message()).isEqualTo(ExamBuildMessages.NAME_REQUIRED));
            assertThat(ExamValidator.metadataProblem("", 90, null, null))
                    .hasValueSatisfying(v ->
                            assertThat(v.message()).isEqualTo(ExamBuildMessages.NAME_REQUIRED));
        }

        @Test
        @DisplayName("a name at the ceiling passes and one past it does not")
        void nameCeiling() {
            String atLimit = "x".repeat(ExamCreateRequest.MAX_NAME_LENGTH);
            assertThat(ExamValidator.metadataProblem(atLimit, 90, null, null)).isEmpty();
            assertThat(ExamValidator.metadataProblem(atLimit + "x", 90, null, null))
                    .hasValueSatisfying(v -> assertThat(v.field())
                            .isEqualTo(ExamValidator.FIELD_NAME));
        }

        @Test
        @DisplayName("⚑ the duration ceiling is 480, which is ruling 3 rather than the draft's 600")
        void durationCeilingIsTheRuling() {
            assertThat(ExamValidator.metadataProblem("Midterm", 480, null, null)).isEmpty();
            assertThat(ExamValidator.metadataProblem("Midterm", 481, null, null))
                    .as("the ruling cut 600 to 480 precisely so a 600-for-60 typo is refused")
                    .isPresent();
            assertThat(ExamValidator.metadataProblem("Midterm", 600, null, null)).isPresent();
            assertThat(ExamValidator.metadataProblem("Midterm", 0, null, null)).isPresent();
        }

        @Test
        @DisplayName("the two texts are bounded independently")
        void textCeilings() {
            String tooLong = "x".repeat(ExamCreateRequest.MAX_TEXT_LENGTH + 1);
            assertThat(ExamValidator.metadataProblem("Midterm", 90, tooLong, null))
                    .hasValueSatisfying(v -> assertThat(v.message())
                            .isEqualTo(ExamBuildMessages.STUDENT_TEXT_TOO_LONG));
            assertThat(ExamValidator.metadataProblem("Midterm", 90, null, tooLong))
                    .hasValueSatisfying(v -> assertThat(v.message())
                            .isEqualTo(ExamBuildMessages.TEACHER_TEXT_TOO_LONG));
        }

        @Test
        @DisplayName("a create with no course is refused")
        void courseRequired() {
            assertThat(ExamValidator.courseProblem(null)).isPresent();
            assertThat(ExamValidator.courseProblem("")).isPresent();
            assertThat(ExamValidator.courseProblem(COURSE)).isEmpty();
        }
    }

    // ===================== Points (§5.1) ==================================

    @Nested
    @DisplayName("points")
    class Points {

        @Test
        @DisplayName("exactly 100 passes")
        void exactlyOneHundred() {
            assertThat(ExamValidator.pointsProblem(List.of(pin(1, 34), pin(2, 33), pin(3, 33))))
                    .isEmpty();
        }

        @Test
        @DisplayName("⚑ short of 100 names the gap, not just the refusal")
        void pointsShortNamesTheGap() {
            Optional<ExamValidator.Violation> problem =
                    ExamValidator.pointsProblem(List.of(pin(1, 50), pin(2, 46)));

            assertThat(problem).hasValueSatisfying(v -> assertThat(v.message())
                    .as("T-3.2 watches the indicator go wrong to right, so she has to be told "
                            + "which way she is out and by how much")
                    .isEqualTo(ExamBuildMessages.pointsShort(96))
                    .contains("96")
                    .contains("4"));
        }

        @Test
        @DisplayName("⚑ over 100 names the excess, which the opposite sentence cannot")
        void pointsOverNamesTheExcess() {
            Optional<ExamValidator.Violation> problem =
                    ExamValidator.pointsProblem(List.of(pin(1, 60), pin(2, 45)));

            assertThat(problem).hasValueSatisfying(v -> assertThat(v.message())
                    .isEqualTo(ExamBuildMessages.pointsOver(105))
                    .contains("105")
                    .contains("5"));
        }

        @Test
        @DisplayName("⚑ an empty exam is told to add questions, not that it adds up to 0")
        void emptyIsNotAZeroSum() {
            // Summing first would answer "the points add up to 0, add 100 more", which is
            // arithmetically true and tells her to do the wrong thing.
            assertThat(ExamValidator.pointsProblem(List.of()))
                    .hasValueSatisfying(v -> assertThat(v.message())
                            .isEqualTo(ExamBuildMessages.NO_QUESTIONS));
            assertThat(ExamValidator.pointsProblem(null))
                    .hasValueSatisfying(v -> assertThat(v.message())
                            .isEqualTo(ExamBuildMessages.NO_QUESTIONS));
        }

        @Test
        @DisplayName("a question outside 1..100 is named by its position")
        void pointsRangeIsPerQuestion() {
            assertThat(ExamValidator.pointsProblem(List.of(pin(1, 0), pin(2, 100))))
                    .hasValueSatisfying(v -> assertThat(v.message())
                            .isEqualTo(ExamBuildMessages.pointsOutOfRange(1)));
            assertThat(ExamValidator.pointsProblem(List.of(pin(1, 50), pin(2, 101))))
                    .hasValueSatisfying(v -> assertThat(v.message())
                            .isEqualTo(ExamBuildMessages.pointsOutOfRange(2)));
        }

        @Test
        @DisplayName("⚑ a null pin is named by position rather than throwing")
        void aNullPinIsNamedByPosition() {
            // The lead's brief item 1: the record copies with new ArrayList<> and not
            // List.copyOf precisely so this survives to be refused with a sentence. A throw here
            // would run on the socket read thread and kill the connection (E1.11).
            List<QuestionPin> withHole = new ArrayList<>(Arrays.asList(pin(1, 50), null));

            assertThat(ExamValidator.pointsProblem(withHole))
                    .hasValueSatisfying(v -> assertThat(v.message())
                            .isEqualTo(ExamBuildMessages.questionMissingAt(2)));
        }
    }

    // ===================== Composition (§5.2) =============================

    @Nested
    @DisplayName("composition")
    class Composition {

        @Test
        @DisplayName("a clean composition passes")
        void cleanPasses() {
            assertThat(ExamValidator.compositionProblem(
                    List.of(pin(10, 50), pin(20, 50)),
                    List.of(candidate(10, 1, COURSE, false), candidate(20, 2, COURSE, false)),
                    COURSE)).isEmpty();
        }

        @Test
        @DisplayName("⚑ two versions of ONE question is a duplicate, which version ids hide")
        void twoVersionsOfOneQuestionIsADuplicate() {
            // T-3.9, and the case that separates a correct check from the one that looks
            // correct. Both pins carry a DIFFERENT questionVersionId and the SAME questionId:
            // a check comparing version ids sees two unique values and lets the exam ask the
            // same thing twice.
            Optional<ExamValidator.Violation> problem = ExamValidator.compositionProblem(
                    List.of(pin(10, 50), pin(11, 50)),
                    List.of(candidate(10, 7, COURSE, false), candidate(11, 7, COURSE, false)),
                    COURSE);

            assertThat(problem).hasValueSatisfying(v -> assertThat(v.message())
                    .isEqualTo(ExamBuildMessages.questionPinnedTwice("11007"))
                    .as("and it names the question she has to remove, by the id on her screen")
                    .contains("11007"));
        }

        @Test
        @DisplayName("⚑ a deleted question is refused, and nothing underneath would refuse it")
        void aDeletedQuestionIsRefused() {
            Optional<ExamValidator.Violation> problem = ExamValidator.compositionProblem(
                    List.of(pin(10, 100)),
                    List.of(candidate(10, 3, COURSE, true)),
                    COURSE);

            assertThat(problem).hasValueSatisfying(v -> assertThat(v.message())
                    .as("soft delete is an UPDATE and no foreign key fires on an update, so this "
                            + "check is the whole of the rule")
                    .isEqualTo(ExamBuildMessages.questionDeleted("11003")));
        }

        @Test
        @DisplayName("a question from another course is refused and named")
        void anotherCoursesQuestionIsRefused() {
            assertThat(ExamValidator.compositionProblem(
                    List.of(pin(10, 100)),
                    List.of(candidate(10, 4, OTHER_COURSE, false)),
                    COURSE))
                    .hasValueSatisfying(v -> assertThat(v.message())
                            .isEqualTo(ExamBuildMessages.questionFromAnotherCourse("11004")));
        }

        @Test
        @DisplayName("⚑ an unknown version id is VALIDATION by position, never NOT_FOUND")
        void anUnknownVersionIsNamedByPosition() {
            // She is describing a composition, so the thing that was not found is a field of her
            // request rather than the object she addressed. The store simply returns no row.
            assertThat(ExamValidator.compositionProblem(
                    List.of(pin(10, 50), pin(999, 50)),
                    List.of(candidate(10, 1, COURSE, false)),
                    COURSE))
                    .hasValueSatisfying(v -> assertThat(v.message())
                            .isEqualTo(ExamBuildMessages.questionUnknownAt(2)));
        }

        @Test
        @DisplayName("deleted is reported ahead of the wrong course, being the more actionable")
        void deletedBeatsWrongCourse() {
            assertThat(ExamValidator.compositionProblem(
                    List.of(pin(10, 100)),
                    List.of(candidate(10, 5, OTHER_COURSE, true)),
                    COURSE))
                    .hasValueSatisfying(v -> assertThat(v.message())
                            .isEqualTo(ExamBuildMessages.questionDeleted("11005")));
        }

        @Test
        @DisplayName("the fetch list skips nulls so the two checks cannot fight over ordering")
        void pinnedVersionIdsSkipsNulls() {
            List<QuestionPin> withHole = new ArrayList<>(Arrays.asList(pin(10, 50), null,
                    pin(20, 50)));

            assertThat(ExamValidator.pinnedVersionIds(withHole)).containsExactly(10L, 20L);
            assertThat(ExamValidator.pinnedVersionIds(null)).isEmpty();
        }
    }

    // ===================== Auto-compose criteria (§5.3) ===================

    @Nested
    @DisplayName("criteria")
    class Criteria {

        private static AutoComposeRequest criteria(TopicQuota... quotas) {
            return new AutoComposeRequest(COURSE, Arrays.asList(quotas));
        }

        @Test
        @DisplayName("distinct topics pass")
        void distinctTopicsPass() {
            assertThat(ExamValidator.quotaProblem(criteria(
                    TopicQuota.ofAnyDifficulty("Algebra", 3),
                    TopicQuota.ofAnyDifficulty("Recursion", 2)))).isEmpty();
        }

        @Test
        @DisplayName("⚑ one topic twice is refused, because it would make a shortfall untrue")
        void oneTopicTwiceIsRefused() {
            assertThat(ExamValidator.quotaProblem(criteria(
                    TopicQuota.ofAnyDifficulty("Algebra", 3),
                    TopicQuota.ofAnyDifficulty("Algebra", 2))))
                    .hasValueSatisfying(v -> assertThat(v.message())
                            .as("two buckets over one candidate pool break the disjointness "
                                    + "most-constrained-first relies on, and the report could "
                                    + "then name a shortfall she can disprove")
                            .isEqualTo(ExamBuildMessages.topicRequestedTwice("Algebra")));
        }

        @Test
        @DisplayName("⚑ blank and null are ONE course-wide bucket, not two")
        void blankAndNullAreOneBucket() {
            // TopicQuota folds blank to null, so "" and null normalise to the same bucket. A
            // comparison on the raw string would see two distinct topics and admit the hazard
            // above while looking like two legitimate rows.
            assertThat(ExamValidator.quotaProblem(criteria(
                    TopicQuota.ofAnyDifficulty("", 3),
                    TopicQuota.ofAnyDifficulty(null, 2))))
                    .hasValueSatisfying(v -> assertThat(v.message())
                            .isEqualTo(ExamBuildMessages.topicRequestedTwice(null)));
        }

        @Test
        @DisplayName("⚑ a negative bucket is refused even when the row's total is not")
        void negativeBucketIsRefused() {
            // +3 and -3 sum to zero, so a check on total() alone admits a negative bucket and
            // lets totalRequested() report a number she asked for in no row.
            assertThat(ExamValidator.quotaProblem(criteria(
                    new TopicQuota("Algebra", 3, -3, 0, 5))))
                    .hasValueSatisfying(v -> assertThat(v.message())
                            .isEqualTo(ExamBuildMessages.QUOTA_NEGATIVE));
        }

        @Test
        @DisplayName("criteria asking for nothing are refused")
        void emptyCriteriaRefused() {
            assertThat(ExamValidator.quotaProblem(criteria(
                    TopicQuota.ofAnyDifficulty("Algebra", 0))))
                    .hasValueSatisfying(v -> assertThat(v.message())
                            .isEqualTo(ExamBuildMessages.QUOTA_EMPTY));
            assertThat(ExamValidator.quotaProblem(criteria())).isPresent();
        }

        @Test
        @DisplayName("a null quota is named by position rather than throwing")
        void nullQuotaNamedByPosition() {
            AutoComposeRequest request = new AutoComposeRequest(COURSE,
                    new ArrayList<>(Arrays.asList(TopicQuota.ofAnyDifficulty("Algebra", 3), null)));

            assertThat(ExamValidator.quotaProblem(request))
                    .hasValueSatisfying(v -> assertThat(v.message())
                            .isEqualTo(ExamBuildMessages.quotaMissingAt(2)));
        }
    }
}
