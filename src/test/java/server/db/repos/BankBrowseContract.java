package server.db.repos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.RepositoryTestBase;
import server.db.entities.Coordinator;
import server.db.entities.Difficulty;
import server.db.entities.Exam;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionQuestion;
import server.db.entities.ExamVersionStatus;
import server.db.entities.Question;
import server.db.entities.QuestionVersion;
import server.db.entities.User;
import server.db.entities.UserRole;
import server.db.projections.BankQuestionSummary;
import server.db.projections.ReferencingExam;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The E6 bank browse and the blocked-delete lookup (E6.4, E6.5).
 *
 * <p>A pair of its own rather than more tests on {@code BankRepositoryContract}, which holds
 * E2.11's authoring reads. These need a different fixture: many questions across several
 * courses, with versions, topics, difficulties and illustrations, plus exams referencing them.
 * Mixing the two would make both harder to read, and the fixture cost is paid once here.
 *
 * <p>Engine-agnostic tests live here. Anything that depends on the real collation is in the
 * MySQL leaf, because H2 in MySQL mode does not reproduce {@code utf8mb4_unicode_ci} and a
 * collation test passing here would read as coverage without being any.
 */
abstract class BankBrowseContract extends RepositoryTestBase {

    private static final Instant WHEN = Instant.parse("2026-08-20T09:00:00Z");
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    private final QuestionRepository questions = new QuestionRepository();
    private final CourseRepository courses = new CourseRepository();

    // ===================== The browse, E6.5 ===============================

    @Test
    @DisplayName("the bank lists the latest version of a question, never an older one")
    void listsOnlyTheLatestVersion() {
        long id = question(COURSE_ALGEBRA, (short) 1);
        version(id, 1, "first wording", "משוואות", Difficulty.EASY, null);
        version(id, 2, "second wording", "משוואות", Difficulty.HARD, null);

        List<BankQuestionSummary> page = browse(algebraOnly());

        assertThat(page).singleElement()
                .satisfies(row -> {
                    assertThat(row.versionNo()).isEqualTo(2);
                    assertThat(row.text()).isEqualTo("second wording");
                    // F2.3: an edit changes what the bank shows. Exams stay pinned (T-2.5),
                    // which is asserted where the pin lives, not here.
                    assertThat(row.difficulty()).isEqualTo(Difficulty.HARD);
                });
    }

    @Test
    @DisplayName("a soft-deleted question is gone from the bank (T-2.8)")
    void softDeletedIsExcluded() {
        long kept = question(COURSE_ALGEBRA, (short) 1);
        version(kept, 1, "still here", "משוואות", Difficulty.EASY, null);
        long removed = question(COURSE_ALGEBRA, (short) 2);
        version(removed, 1, "deleted", "משוואות", Difficulty.EASY, null);
        softDelete(removed);

        assertThat(browse(algebraOnly()))
                .extracting(BankQuestionSummary::text)
                .containsExactly("still here");
        assertThat(countOf(algebraOnly())).isEqualTo(1L);
    }

    @Test
    @DisplayName("a teacher reaches only her own courses (S-5)")
    void scopedToReachableCourses() {
        long algebra = question(COURSE_ALGEBRA, (short) 1);
        version(algebra, 1, "algebra question", "משוואות", Difficulty.EASY, null);
        long java = question(COURSE_JAVA, (short) 1);
        version(java, 1, "java question", "Recursion", Difficulty.HARD, null);

        List<BankQuestionSummary> page = browse(
                BankQuery.scopedTo(List.of(COURSE_ALGEBRA), null, null, null, null));

        assertThat(page).extracting(BankQuestionSummary::text)
                .containsExactly("algebra question");
    }

    @Test
    @DisplayName("a caller who reaches nothing gets nothing, and no exception")
    void emptyScopeMatchesNothing() {
        long id = question(COURSE_ALGEBRA, (short) 1);
        version(id, 1, "algebra question", "משוואות", Difficulty.EASY, null);
        BankQuery nothing = BankQuery.scopedTo(List.of(), null, null, null, null);

        // The trap this pins, watched failing before it was trusted: a scope predicate that is
        // skipped when the list is empty. That is the natural-looking way to "handle" the case,
        // and it hands every question in the school to a caller entitled to none. Planted, and
        // this assertion caught it with "Expecting empty but was: [BankQuestionSummary[...]]".
        //
        // Not what an earlier comment here claimed. Hibernate 6 expands an empty `in ()` into a
        // false predicate on both engines, so the short-circuit in BankQuery is an optimisation
        // and nothing more. The doesNotThrow below still earns its line: it caught a version
        // where the WHERE and the parameter binding disagreed about whether the clause existed.
        assertThatCode(() -> browse(nothing)).doesNotThrowAnyException();
        assertThat(browse(nothing)).isEmpty();
        assertThat(countOf(nothing)).isZero();
    }

    @Test
    @DisplayName("the principal reaches every course (F9.3)")
    void unrestrictedSeesEverything() {
        version(question(COURSE_ALGEBRA, (short) 1), 1, "algebra", "משוואות",
                Difficulty.EASY, null);
        version(question(COURSE_JAVA, (short) 1), 1, "java", "Recursion", Difficulty.HARD, null);

        assertThat(browse(BankQuery.everyCourse(null, null, null, null)))
                .extracting(BankQuestionSummary::text)
                .containsExactlyInAnyOrder("algebra", "java");
    }

    @Test
    @DisplayName("a course filter naming an unreachable course matches nothing, and is not an error")
    void unreachableCourseFilterIsNotAnError() {
        version(question(COURSE_JAVA, (short) 1), 1, "java", "Recursion", Difficulty.HARD, null);

        // The client's filter list is a convenience, not a boundary. Asking for a course
        // outside scope must be indistinguishable from asking for one with no questions.
        BankQuery outside = BankQuery.scopedTo(List.of(COURSE_ALGEBRA), COURSE_JAVA,
                null, null, null);

        assertThat(browse(outside)).isEmpty();
    }

    @Test
    @DisplayName("filters combine: course, topic, difficulty and search together (T-2.6)")
    void filtersCombine() {
        long wanted = question(COURSE_ALGEBRA, (short) 1);
        version(wanted, 1, "find the root of the equation", "משוואות", Difficulty.MEDIUM, null);
        long wrongTopic = question(COURSE_ALGEBRA, (short) 2);
        version(wrongTopic, 1, "find the root of the tree", "פונקציות", Difficulty.MEDIUM, null);
        long wrongDifficulty = question(COURSE_ALGEBRA, (short) 3);
        version(wrongDifficulty, 1, "find the root here", "משוואות", Difficulty.HARD, null);
        long wrongText = question(COURSE_ALGEBRA, (short) 4);
        version(wrongText, 1, "something else entirely", "משוואות", Difficulty.MEDIUM, null);

        List<BankQuestionSummary> page = browse(BankQuery.scopedTo(
                List.of(COURSE_ALGEBRA, COURSE_JAVA), COURSE_ALGEBRA, "משוואות",
                Difficulty.MEDIUM, "root"));

        assertThat(page).extracting(BankQuestionSummary::text)
                .containsExactly("find the root of the equation");
    }

    @Test
    @DisplayName("search matches a substring of the stem, case-insensitively")
    void searchIsCaseInsensitiveSubstring() {
        long id = question(COURSE_ALGEBRA, (short) 1);
        version(id, 1, "Find The ROOT", "משוואות", Difficulty.EASY, null);

        assertThat(browse(search("root"))).hasSize(1);
        assertThat(browse(search("ROOT"))).hasSize(1);
        assertThat(browse(search("  the  "))).hasSize(1);
        assertThat(browse(search("branch"))).isEmpty();
    }

    @Test
    @DisplayName("paging is stable and pages do not overlap")
    void pagingIsStable() {
        for (short serial = 1; serial <= 5; serial++) {
            long id = question(COURSE_ALGEBRA, serial);
            version(id, 1, "question " + serial, "משוואות", Difficulty.EASY, null);
        }

        List<String> first = browse(algebraOnly(), 0, 2).stream()
                .map(BankQuestionSummary::displayId).toList();
        List<String> second = browse(algebraOnly(), 2, 2).stream()
                .map(BankQuestionSummary::displayId).toList();
        List<String> third = browse(algebraOnly(), 4, 2).stream()
                .map(BankQuestionSummary::displayId).toList();

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(2);
        assertThat(third).hasSize(1);
        // Ordered by display id, so a row cannot appear on two pages or fall between them.
        // Without a deterministic order the database may return any order per call and a
        // teacher paging through the bank silently misses questions.
        assertThat(first).doesNotContainAnyElementsOf(second);
        assertThat(second).doesNotContainAnyElementsOf(third);
        assertThat(first).isSorted();
    }

    @Test
    @DisplayName("the count is the whole match, not the page")
    void countIgnoresPaging() {
        for (short serial = 1; serial <= 5; serial++) {
            version(question(COURSE_ALGEBRA, serial), 1, "question " + serial, "משוואות",
                    Difficulty.EASY, null);
        }

        assertThat(browse(algebraOnly(), 0, 2)).hasSize(2);
        assertThat(countOf(algebraOnly())).isEqualTo(5L);
    }

    @Test
    @DisplayName("the count applies the same filters as the page")
    void countUsesTheSameFilters() {
        version(question(COURSE_ALGEBRA, (short) 1), 1, "root", "משוואות",
                Difficulty.EASY, null);
        version(question(COURSE_ALGEBRA, (short) 2), 1, "branch", "משוואות",
                Difficulty.EASY, null);

        // A count that ignored a filter would give the pager more pages than exist, and the
        // teacher would scroll into empty ones. The two share a FROM and WHERE for this reason.
        assertThat(countOf(search("root"))).isEqualTo(1L);
    }

    @Test
    @DisplayName("hasImage is a flag, and an illustrated question is marked")
    void hasImageFlag() {
        long plain = question(COURSE_ALGEBRA, (short) 1);
        version(plain, 1, "no picture", "משוואות", Difficulty.EASY, null);
        long illustrated = question(COURSE_ALGEBRA, (short) 2);
        version(illustrated, 1, "has picture", "משוואות", Difficulty.EASY, PNG);

        assertThat(browse(algebraOnly()))
                .extracting(BankQuestionSummary::text, BankQuestionSummary::hasImage)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("no picture", false),
                        org.assertj.core.api.Assertions.tuple("has picture", true));
    }

    @Test
    @DisplayName("lastVersionAt is the latest version's timestamp, not the first")
    void lastVersionAtFollowsTheLatestVersion() {
        long id = question(COURSE_ALGEBRA, (short) 1);
        Instant later = WHEN.plusSeconds(3600);
        versionAt(id, 1, "first", WHEN);
        versionAt(id, 2, "second", later);

        assertThat(browse(algebraOnly())).singleElement()
                .satisfies(row -> assertThat(row.lastVersionAt()).isEqualTo(later));
    }

    // ===================== The blocked delete, E6.4 =======================

    @Test
    @DisplayName("a question no exam uses has no blockers")
    void nothingBlocksAnUnusedQuestion() {
        long id = question(COURSE_ALGEBRA, (short) 1);
        long versionId = version(id, 1, "unused", "משוואות", Difficulty.EASY, null);
        assertThat(versionId).isPositive();

        assertThat(blockersFor(id)).isEmpty();
    }

    @Test
    @DisplayName("an exam that uses the question is named once per exam, not once per version")
    void oneRowPerExamNotPerVersion() {
        long questionId = question(COURSE_ALGEBRA, (short) 1);
        long versionId = version(questionId, 1, "used twice", "משוואות", Difficulty.EASY, null);

        long examId = exam((byte) 1);
        long v1 = examVersion(examId, 1, "Algebra Midterm");
        long v2 = examVersion(examId, 2, "Algebra Midterm");
        pin(v1, versionId, questionId);
        pin(v2, versionId, questionId);

        // The case this exists for. Seed exam 101101 pins question 11005 in BOTH of its
        // versions, which SeedDatasetContract asserts, so the obvious join returns the exam
        // twice and T-2.7's dialog reads "2 exams use it: Algebra Midterm, Algebra Midterm".
        assertThat(blockersFor(questionId))
                .singleElement()
                .satisfies(blocker -> assertThat(blocker.name()).isEqualTo("Algebra Midterm"));
    }

    @Test
    @DisplayName("two different exams both appear, ordered by id")
    void everyBlockingExamIsNamed() {
        long questionId = question(COURSE_ALGEBRA, (short) 1);
        long versionId = version(questionId, 1, "popular", "משוואות", Difficulty.EASY, null);

        pin(examVersion(exam((byte) 2), 1, "Second Exam"), versionId, questionId);
        pin(examVersion(exam((byte) 1), 1, "First Exam"), versionId, questionId);

        assertThat(blockersFor(questionId))
                .extracting(ReferencingExam::name)
                .containsExactly("First Exam", "Second Exam");
    }

    @Test
    @DisplayName("the blocker is named by its latest version, even when an older one holds the pin")
    void nameComesFromTheLatestVersion() {
        long questionId = question(COURSE_ALGEBRA, (short) 1);
        long versionId = version(questionId, 1, "pinned in v1 only", "משוואות",
                Difficulty.EASY, null);

        long examId = exam((byte) 1);
        long v1 = examVersion(examId, 1, "Old Name");
        examVersion(examId, 2, "Current Name");
        pin(v1, versionId, questionId);

        // An old version still referencing the question is still a reason the delete must be
        // refused, but the teacher looking for the exam sees the name on her current list.
        assertThat(blockersFor(questionId))
                .singleElement()
                .satisfies(blocker -> assertThat(blocker.name()).isEqualTo("Current Name"));
    }

    // ===================== The scoped lookup ==============================

    @Test
    @DisplayName("findActiveByDisplayId finds a live question and hides a soft-deleted one")
    void activeLookupFiltersDeleted() {
        long id = question(COURSE_ALGEBRA, (short) 7);
        version(id, 1, "live", "משוואות", Difficulty.EASY, null);
        String displayId = COURSE_ALGEBRA + "007";

        assertThat(activeByDisplayId(displayId))
                .isPresent();

        softDelete(id);

        Optional<Question> afterDelete =
                activeByDisplayId(displayId);
        assertThat(afterDelete).isEmpty();

        // And the seed loader's method still sees it, which is why the two exist separately:
        // a reseed must not hand a deleted question's display id to a different question.
        assertThat(anyByDisplayId(displayId)).isPresent();
    }

    // ===================== Scope resolution ==============================

    @Test
    @DisplayName("taught courses are the teaching rows, never the enrolments")
    void taughtCoursesExcludeEnrolments() {
        // The base fixture enrols dana in Databases while she teaches Algebra and Calculus.
        // findForUser merges both because a home screen shows both; authoring scope must not,
        // or a teacher enrolled in a course could author questions into its bank.
        assertThat(taughtBy(danaId))
                .containsExactly(COURSE_ALGEBRA, COURSE_CALCULUS)
                .doesNotContain(COURSE_DATABASES);
    }

    @Test
    @DisplayName("a coordinator reaches every course of the subject she coordinates")
    void coordinatedCoursesAreTheWholeSubject() {
        assertThat(coordinatedBy(rinaId))
                .containsExactly(COURSE_ALGEBRA, COURSE_CALCULUS);
    }

    @Test
    @DisplayName("a coordinator who teaches nothing still reaches her subject (the rina.barak case)")
    void pureCoordinatorReachesHerSubject() {
        // The defect this pins, found by the pre-build red team: seed rina.barak holds a
        // coordinators row and ZERO course_teachers rows, deliberately, so that deriving
        // coordinator-ness from the wrong table fails. Scoping the bank by teaching would have
        // shown a starred demo account an empty screen. The shared fixture's rina still teaches
        // Calculus (it predates the 2026-08-20 roster decision), so this builds its own.
        long pureId = inTx(session -> {
            User pure = new User("pure.coordinator", FAKE_HASH, "Pure Coordinator",
                    UserRole.TEACHER, "900000001");
            session.persist(pure);
            session.flush();
            session.persist(new Coordinator(SUBJECT_CS, pure.getId()));
            return pure.getId();
        });

        assertThat(taughtBy(pureId)).isEmpty();
        assertThat(coordinatedBy(pureId))
                .containsExactly(COURSE_JAVA, COURSE_DATABASES);
    }

    // ===================== Fixture =======================================

    private BankQuery algebraOnly() {
        return BankQuery.scopedTo(List.of(COURSE_ALGEBRA), null, null, null, null);
    }

    private BankQuery search(String term) {
        return BankQuery.scopedTo(List.of(COURSE_ALGEBRA), null, null, null, term);
    }

    // Typed wrappers rather than assertThat(inTx(...)) at each call site. inTx is generic, so
    // AssertJ cannot choose between its long, Long and Object overloads and the compile fails
    // with "reference to assertThat is ambiguous". Declaring the return type here pins it once.

    protected final long countOf(BankQuery query) {
        return inTx(session -> questions.countBank(session, query));
    }

    protected final List<ReferencingExam> blockersFor(long questionId) {
        return inTx(session -> questions.findReferencingExams(session, questionId));
    }

    protected final Optional<Question> activeByDisplayId(String displayId) {
        return inTx(session -> questions.findActiveByDisplayId(session, displayId));
    }

    protected final Optional<Question> anyByDisplayId(String displayId) {
        return inTx(session -> questions.findByDisplayId(session, displayId));
    }

    protected final List<String> taughtBy(long userId) {
        return inTx(session -> courses.findTaughtCourseCodes(session, userId));
    }

    protected final List<String> coordinatedBy(long userId) {
        return inTx(session -> courses.findCoordinatedCourseCodes(session, userId));
    }

    protected final List<BankQuestionSummary> browse(BankQuery query) {
        return browse(query, 0, 50);
    }

    protected final List<BankQuestionSummary> browse(BankQuery query, int offset, int limit) {
        return inTx(session -> questions.findBankPage(session, query, offset, limit));
    }

    protected final long question(String courseCode, short serial) {
        return inTx(session -> {
            Question question = new Question(courseCode, serial,
                    courseCode + String.format("%03d", serial));
            session.persist(question);
            session.flush();
            return question.getId();
        });
    }

    protected final long version(long questionId, int versionNo, String text, String topic,
                                 Difficulty difficulty, byte[] image) {
        return inTx(session -> {
            QuestionVersion version = new QuestionVersion(questionId, versionNo, text,
                    "answer one", "answer two", "answer three", "answer four", (byte) 2,
                    topic, difficulty, image, danaId, WHEN);
            session.persist(version);
            session.flush();
            return version.getId();
        });
    }

    private void versionAt(long questionId, int versionNo, String text, Instant at) {
        runInTx(session -> session.persist(new QuestionVersion(questionId, versionNo, text,
                "answer one", "answer two", "answer three", "answer four", (byte) 2,
                "משוואות", Difficulty.EASY, null, danaId, at)));
    }

    private void softDelete(long questionId) {
        runInTx(session -> session.createMutationQuery(
                        "update Question set deletedAt = :now where id = :id")
                .setParameter("now", WHEN)
                .setParameter("id", questionId)
                .executeUpdate());
    }

    private long exam(byte serial) {
        return inTx(session -> {
            Exam exam = new Exam(COURSE_ALGEBRA, serial,
                    SUBJECT_MATH + COURSE_ALGEBRA + String.format("%02d", serial), danaId);
            session.persist(exam);
            session.flush();
            return exam.getId();
        });
    }

    private long examVersion(long examId, int versionNo, String name) {
        return inTx(session -> {
            ExamVersion version = new ExamVersion(examId, versionNo, name, 60, null, null,
                    ExamVersionStatus.APPROVED, WHEN);
            session.persist(version);
            session.flush();
            return version.getId();
        });
    }

    private void pin(long examVersionId, long questionVersionId, long questionId) {
        runInTx(session -> session.persist(new ExamVersionQuestion(
                examVersionId, questionVersionId, questionId, 100, 1)));
    }
}
