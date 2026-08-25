package server.db.repos;

import common.dto.approval.PreviewAnswerRow;
import org.hibernate.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.RepositoryTestBase;
import server.db.entities.Difficulty;
import server.db.entities.Exam;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionQuestion;
import server.db.entities.ExamVersionStatus;
import server.db.entities.Question;
import server.db.entities.QuestionVersion;
import server.db.projections.ExamVersionContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reads and the one guarded bulk write that the approval workflow stands on
 * (E8, over E2.11).
 *
 * <p>Every rule in {@code ApprovalService} is unit-tested against an in-memory store, which
 * proves the rules and nothing about the SQL. This is the other half: the same operations
 * against a real database, so the HQL is exercised, the {@code coordinators} join really does
 * scope the queue, and the supersede update really is one status-guarded statement.
 *
 * <p>The scoping test is the one that matters most. A queue that returned another subject's
 * exams and let a service filter them out afterwards would pass every service test written
 * against the fake, so it is established here, against real rows, that the foreign subject's
 * version is <b>not in the answer at all</b>.
 *
 * <p>Run twice, as every repository contract here is: once on H2 for speed, once on the
 * migrated MySQL schema where the constraints are live (see {@code TestDatabases}).
 */
abstract class ApprovalRepositoryContract extends RepositoryTestBase {

    protected static final Instant WHEN = Instant.parse("2026-08-20T09:00:00Z");
    protected static final int DURATION = 60;

    private final ExamRepository exams = new ExamRepository();
    private final QuestionRepository questions = new QuestionRepository();
    private final CourseRepository courses = new CourseRepository();

    // ===================== ExamVersionContext ============================

    @Test
    @DisplayName("the version context joins four tables into one answer, subject included")
    void contextJoinsEverything() {
        long versionId = pendingVersion(COURSE_ALGEBRA, 1, danaId);

        ExamVersionContext ctx = inTx(s -> exams.findVersionContext(s, versionId)).orElseThrow();

        assertThat(ctx.examVersionId()).isEqualTo(versionId);
        assertThat(ctx.examName()).isEqualTo("מבחן אמצע");
        assertThat(ctx.versionNo()).isEqualTo(1);
        assertThat(ctx.durationMinutes()).isEqualTo(DURATION);
        assertThat(ctx.studentText()).isEqualTo("ענו על כל השאלות.");
        assertThat(ctx.teacherText()).isEqualTo("לבודק בלבד");
        assertThat(ctx.status()).isEqualTo(ExamVersionStatus.PENDING);
        assertThat(ctx.rejectedReason()).isNull();
        assertThat(ctx.courseCode()).isEqualTo(COURSE_ALGEBRA);
        assertThat(ctx.courseName()).isEqualTo("אלגברה");
        assertThat(ctx.subjectCode())
                .as("the scoping key: a coordinator owns a subject, an exam names a course")
                .isEqualTo(SUBJECT_MATH);
        assertThat(ctx.authorId()).isEqualTo(danaId);
        assertThat(ctx.authorName()).isEqualTo("דנה כהן");
        assertThat(ctx.lockVersion()).isZero();
        assertThat(ctx.isPending()).isTrue();
        assertThat(ctx.isAuthoredBy(danaId)).isTrue();
        assertThat(ctx.isAuthoredBy(rinaId)).isFalse();
    }

    @Test
    @DisplayName("a version that does not exist yields empty rather than throwing")
    void unknownContext() {
        Optional<ExamVersionContext> missing = inTx(s -> exams.findVersionContext(s, 999_999L));
        assertThat(missing).isEmpty();
    }

    // ===================== The scoped queue ==============================

    @Test
    @DisplayName("the queue is scoped by the coordinators join, not by a filter afterwards")
    void queueIsScopedInSql() {
        long algebra = pendingVersion(COURSE_ALGEBRA, 1, danaId);
        long calculus = pendingVersion(COURSE_CALCULUS, 2, danaId);
        // Computer Science: Rina coordinates Mathematics only, so this must not be fetched.
        long java = pendingVersion(COURSE_JAVA, 3, danaId);

        List<ExamVersionContext> hers = inTx(s -> exams.findPendingForCoordinator(s, rinaId));

        assertThat(hers).extracting(ExamVersionContext::examVersionId)
                .containsExactly(algebra, calculus)
                .doesNotContain(java);
    }

    @Test
    @DisplayName("a teacher who coordinates nothing gets an empty queue, not everybody's")
    void nonCoordinatorGetsNothing() {
        pendingVersion(COURSE_ALGEBRA, 1, danaId);

        List<ExamVersionContext> danas = inTx(s -> exams.findPendingForCoordinator(s, danaId));
        List<ExamVersionContext> mayas = inTx(s -> exams.findPendingForCoordinator(s, mayaId));

        assertThat(danas).isEmpty();
        assertThat(mayas).isEmpty();
    }

    @Test
    @DisplayName("only pending versions are in the queue")
    void onlyPending() {
        long pending = pendingVersion(COURSE_ALGEBRA, 1, danaId);
        long approved = versionOf(COURSE_ALGEBRA, 2, danaId, ExamVersionStatus.APPROVED, WHEN);
        long draft = versionOf(COURSE_ALGEBRA, 3, danaId, ExamVersionStatus.DRAFT, WHEN);

        List<ExamVersionContext> hers = inTx(s -> exams.findPendingForCoordinator(s, rinaId));

        assertThat(hers).extracting(ExamVersionContext::examVersionId)
                .containsExactly(pending)
                .doesNotContain(approved, draft);
    }

    @Test
    @DisplayName("the queue is oldest first, so nothing waits behind a newer submission")
    void queueIsOldestFirst() {
        long later = versionOf(COURSE_ALGEBRA, 1, danaId, ExamVersionStatus.PENDING,
                WHEN.plus(Duration.ofHours(2)));
        long earlier = versionOf(COURSE_ALGEBRA, 2, danaId, ExamVersionStatus.PENDING, WHEN);

        List<ExamVersionContext> hers = inTx(s -> exams.findPendingForCoordinator(s, rinaId));

        assertThat(hers).extracting(ExamVersionContext::examVersionId)
                .containsExactly(earlier, later);
    }

    // ===================== The author's own list =========================

// findSubmittedByAuthor retired with MY_APPROVALS_GET (E7.10 integration, 2026-08-25);
    // its two contract tests were deleted with it - rule 5 runs in both directions.

    // ===================== Question counts ===============================

    @Test
    @DisplayName("question counts come back for many versions in one query")
    void batchedQuestionCounts() {
        long first = pendingVersion(COURSE_ALGEBRA, 1, danaId);
        long second = pendingVersion(COURSE_CALCULUS, 2, danaId);
        long empty = versionOf(COURSE_ALGEBRA, 3, danaId, ExamVersionStatus.PENDING, WHEN);

        Map<Long, Integer> counts =
                inTx(s -> exams.countQuestionsByVersion(s, List.of(first, second, empty)));

        assertThat(counts).containsEntry(first, 3).containsEntry(second, 3);
        assertThat(counts)
                .as("a version with no questions is absent, which is what a group by answers")
                .doesNotContainKey(empty);
    }

    @Test
    @DisplayName("asking for no versions asks the database nothing")
    void emptyCountsAreEmpty() {
        Map<Long, Integer> none = inTx(s -> exams.countQuestionsByVersion(s, List.of()));
        Map<Long, Integer> nulls = inTx(s -> exams.countQuestionsByVersion(s, null));

        assertThat(none).isEmpty();
        assertThat(nulls).isEmpty();
    }

    // ===================== Supersede (E8.2) ==============================

    @Test
    @DisplayName("submitting a newer version sends every older pending one back")
    void supersedeSendsOldOnesBack() {
        long first = pendingVersion(COURSE_ALGEBRA, 1, danaId);
        long second = inTx(s -> {
            ExamVersion version = new ExamVersion(examIdOf(s, first), 2, "מבחן אמצע", DURATION,
                    "ענו על כל השאלות.", "לבודק בלבד", ExamVersionStatus.PENDING, WHEN);
            s.persist(version);
            s.flush();
            return version.getId();
        });

        int superseded = inTx(s ->
                exams.supersedePendingVersions(s, examIdOf(s, second), second, "Superseded."));

        assertThat(superseded).isEqualTo(1);
        ExamVersionContext oldOne = contextOf(first);
        assertThat(oldOne.status()).isEqualTo(ExamVersionStatus.REJECTED);
        assertThat(oldOne.rejectedReason()).isEqualTo("Superseded.");
        assertThat(contextOf(second).status())
                .as("the version that was just submitted survives")
                .isEqualTo(ExamVersionStatus.PENDING);
    }

    @Test
    @DisplayName("it is status-guarded: an approved sibling is not dragged back out")
    void supersedeIsStatusGuarded() {
        long approved = versionOf(COURSE_ALGEBRA, 1, danaId, ExamVersionStatus.APPROVED, WHEN);
        long submitted = inTx(s -> {
            ExamVersion version = new ExamVersion(examIdOf(s, approved), 2, "מבחן אמצע", DURATION,
                    null, null, ExamVersionStatus.PENDING, WHEN);
            s.persist(version);
            s.flush();
            return version.getId();
        });

        int superseded = inTx(s ->
                exams.supersedePendingVersions(s, examIdOf(s, submitted), submitted, "Superseded."));

        assertThat(superseded).isZero();
        assertThat(contextOf(approved).status()).isEqualTo(ExamVersionStatus.APPROVED);
    }

    @Test
    @DisplayName("and it never reaches another exam's pending versions")
    void supersedeIsScopedToOneExam() {
        long otherExam = pendingVersion(COURSE_CALCULUS, 2, danaId);
        long mine = pendingVersion(COURSE_ALGEBRA, 1, danaId);

        inTx(s -> exams.supersedePendingVersions(s, examIdOf(s, mine), mine, "Superseded."));

        assertThat(contextOf(otherExam).status()).isEqualTo(ExamVersionStatus.PENDING);
    }

    // ===================== Optimistic locking ============================

    @Test
    @DisplayName("a decision bumps lock_version, which is what a stale screen is caught by")
    void decisionBumpsTheLock() {
        long versionId = pendingVersion(COURSE_ALGEBRA, 1, danaId);

        runInTx(s -> s.get(ExamVersion.class, versionId).approve());

        ExamVersionContext after = contextOf(versionId);
        assertThat(after.status()).isEqualTo(ExamVersionStatus.APPROVED);
        assertThat(after.lockVersion()).isEqualTo(1);
    }

    // ===================== Coordinator lookups ===========================

    @Test
    @DisplayName("coordinates() answers the narrow question, and only for the right subject")
    void coordinatesIsNarrow() {
        assertThat(coordinates(rinaId, SUBJECT_MATH)).isTrue();
        assertThat(coordinates(rinaId, SUBJECT_CS)).isFalse();
        assertThat(coordinates(danaId, SUBJECT_MATH))
                .as("teaching two of a subject's courses does not make you its coordinator")
                .isFalse();
        assertThat(coordinates(rinaId, null)).isFalse();
        assertThat(coordinates(rinaId, "  ")).isFalse();
    }

    @Test
    @DisplayName("one coordinator per subject, and empty for a subject that has none")
    void coordinatorOfASubject() {
        Optional<Long> maths = inTx(s -> courses.findCoordinatorOf(s, SUBJECT_MATH));
        Optional<Long> cs = inTx(s -> courses.findCoordinatorOf(s, SUBJECT_CS));
        Optional<Long> nothing = inTx(s -> courses.findCoordinatorOf(s, null));

        assertThat(maths).contains(rinaId);
        assertThat(cs)
                .as("a submission nobody can approve is an administrative gap, not an error")
                .isEmpty();
        assertThat(nothing).isEmpty();
    }

    @Test
    @DisplayName("a course names its subject, in one column")
    void subjectOfACourse() {
        Optional<String> algebra = inTx(s -> courses.findSubjectOf(s, COURSE_ALGEBRA));
        Optional<String> java = inTx(s -> courses.findSubjectOf(s, COURSE_JAVA));
        Optional<String> unknown = inTx(s -> courses.findSubjectOf(s, "99"));
        Optional<String> blank = inTx(s -> courses.findSubjectOf(s, " "));

        assertThat(algebra).contains(SUBJECT_MATH);
        assertThat(java).contains(SUBJECT_CS);
        assertThat(unknown).isEmpty();
        assertThat(blank).isEmpty();
    }

    // ===================== The answer key ================================

    @Test
    @DisplayName("the preview's answer key comes back in exam order, key included")
    void answerKeyForAuthoring() {
        long versionId = pendingVersion(COURSE_ALGEBRA, 1, danaId);

        List<QuestionVersion> key = inTx(s -> questions.findAnswerKeyForAuthoring(s, versionId));

        assertThat(key).hasSize(3);
        assertThat(key).extracting(QuestionVersion::getCorrectAnswer)
                .as("the three seeded questions are keyed 1, 2 and 3, in that order")
                .containsExactly((byte) 1, (byte) 2, (byte) 3);
        // Which is exactly the shape the wire row is built from.
        assertThat(new PreviewAnswerRow(key.get(1).getId(), 2, key.get(1).getCorrectAnswer())
                .correctOption()).isEqualTo((byte) 2);
    }

    @Test
    @DisplayName("it and the student's paper describe the same questions, one with a key and one without")
    void keyAndPaperLineUp() {
        long versionId = pendingVersion(COURSE_ALGEBRA, 1, danaId);

        List<Long> keyIds = inTx(s -> questions.findAnswerKeyForAuthoring(s, versionId)).stream()
                .map(QuestionVersion::getId).toList();
        List<Long> paperIds = inTx(s -> questions.findForTakeExam(s, versionId)).stream()
                .map(server.db.projections.TakeExamQuestion::questionVersionId).toList();

        assertThat(keyIds)
                .as("the preview pairs them by id, so the two orders have to agree")
                .containsExactlyElementsOf(paperIds);
    }

    @Test
    @DisplayName("a version with no questions has no key, rather than a broken one")
    void emptyPaperHasNoKey() {
        long versionId = versionOf(COURSE_ALGEBRA, 1, danaId, ExamVersionStatus.PENDING, WHEN);

        List<QuestionVersion> key = inTx(s -> questions.findAnswerKeyForAuthoring(s, versionId));
        assertThat(key).isEmpty();
    }

    // ===================== Fixture =======================================

    /** An exam with one PENDING version of three questions worth 40/30/30. */
    protected final long pendingVersion(String courseCode, int serial, long authorId) {
        long versionId = versionOf(courseCode, serial, authorId, ExamVersionStatus.PENDING, WHEN);
        addQuestions(versionId, courseCode, authorId);
        return versionId;
    }

    /** An exam with one version in the given state and no questions. */
    protected final long versionOf(String courseCode, int serial, long authorId,
                                   ExamVersionStatus status, Instant createdAt) {
        return inTx(session -> {
            Exam exam = new Exam(courseCode, (byte) serial,
                    subjectOf(courseCode) + courseCode + String.format("%02d", serial), authorId);
            session.persist(exam);
            session.flush();

            ExamVersion version = new ExamVersion(exam.getId(), 1, "מבחן אמצע", DURATION,
                    "ענו על כל השאלות.", "לבודק בלבד", status, createdAt);
            session.persist(version);
            session.flush();
            return version.getId();
        });
    }

    private void addQuestions(long examVersionId, String courseCode, long authorId) {
        runInTx(session -> {
            int[] points = {40, 30, 30};
            for (int index = 0; index < 3; index++) {
                short serial = (short) (countQuestions(session) + 1);
                Question question = new Question(courseCode, serial,
                        courseCode + String.format("%03d", serial));
                session.persist(question);
                session.flush();

                QuestionVersion qv = new QuestionVersion(question.getId(), 1,
                        "שאלה " + serial, "1, 6", "2, 3", "-2, -3", "0, 5",
                        (byte) (index + 1), "פונקציות", Difficulty.EASY, null, authorId, WHEN);
                session.persist(qv);
                session.flush();

                session.persist(new ExamVersionQuestion(
                        examVersionId, qv.getId(), question.getId(), points[index], index + 1));
            }
            session.flush();
        });
    }

    private static String subjectOf(String courseCode) {
        return switch (courseCode) {
            case COURSE_JAVA, COURSE_DATABASES -> SUBJECT_CS;
            default -> SUBJECT_MATH;
        };
    }

    private static long examIdOf(Session session, long examVersionId) {
        return session.get(ExamVersion.class, examVersionId).getExamId();
    }

    private static int countQuestions(Session session) {
        return session.createQuery("select count(q) from Question q", Long.class)
                .getSingleResult().intValue();
    }

    /** @return the version context, typed, so an inline assertThat is never ambiguous. */
    private ExamVersionContext contextOf(long examVersionId) {
        Optional<ExamVersionContext> found = inTx(s -> exams.findVersionContext(s, examVersionId));
        return found.orElseThrow();
    }

    /** @return whether this teacher coordinates that subject, typed for the same reason. */
    private boolean coordinates(long teacherId, String subjectCode) {
        Boolean answer = inTx(s -> courses.coordinates(s, teacherId, subjectCode));
        return answer;
    }

    /** @return the version, for the leaves that want the entity itself. */
    protected final Optional<ExamVersion> versionEntity(long examVersionId) {
        return inTx(session -> Optional.ofNullable(session.get(ExamVersion.class, examVersionId)));
    }
}
