package server.db.repos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.RepositoryTestBase;
import server.db.entities.AttemptStatus;
import server.db.entities.Difficulty;
import server.db.entities.Exam;
import server.db.entities.ExamExecution;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionQuestion;
import server.db.entities.ExamVersionStatus;
import server.db.entities.ExecutionStatus;
import server.db.entities.Question;
import server.db.entities.QuestionVersion;
import server.db.projections.AnswerRow;
import server.db.projections.AttemptRecord;
import server.db.projections.AttemptRow;
import server.db.projections.ExecutionContext;
import server.db.projections.ParticipationCounts;
import server.db.projections.QuestionOutline;
import server.features.exam.DuplicateAttemptException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The reads and the two guarded writes that take-exam stands on (E10/E11, over E2.11).
 *
 * <p>Every rule in {@code AttemptService} is unit-tested against an in-memory store, which
 * proves the rules and nothing about the SQL. This is the other half: the same operations
 * against a real database, so the HQL is exercised, the compare-and-set really is one
 * statement, and the unique key that makes a double start impossible really exists.
 *
 * <p>Run twice, as every repository contract here is: once on H2 for speed, once on the
 * migrated MySQL schema where the constraints are live (see {@code TestDatabases}).
 */
abstract class ExamFlowRepositoryContract extends RepositoryTestBase {

    protected static final Instant WHEN = Instant.parse("2026-08-20T09:00:00Z");
    protected static final int DURATION = 45;

    private final ExecutionRepository executions = new ExecutionRepository();
    private final AttemptRepository attempts = new AttemptRepository();
    private final QuestionRepository questions = new QuestionRepository();
    private final CourseRepository courses = new CourseRepository();

    // ===================== ExecutionContext ==============================

    @Test
    @DisplayName("the execution context joins four tables into one answer")
    void contextJoinsEverything() {
        long executionId = liveExecution("AB12");

        ExecutionContext ctx = inTx(session -> executions.findContext(session, executionId)).orElseThrow();

        assertThat(ctx.executionId()).isEqualTo(executionId);
        assertThat(ctx.examName()).isEqualTo("מבחן אמצע");
        assertThat(ctx.courseCode()).isEqualTo(COURSE_ALGEBRA);
        assertThat(ctx.courseName()).isEqualTo("אלגברה");
        assertThat(ctx.durationMinutes()).isEqualTo(DURATION);
        assertThat(ctx.generalText()).isEqualTo("ענו על כל השאלות.");
        assertThat(ctx.code()).isEqualTo("AB12");
        assertThat(ctx.status()).isEqualTo(ExecutionStatus.LIVE);
        assertThat(ctx.executingTeacherId()).isEqualTo(danaId);
        assertThat(ctx.authorId()).isEqualTo(danaId);
    }

    @Test
    @DisplayName("an execution that does not exist yields empty rather than throwing")
    void unknownContext() {
        Optional<ExecutionContext> missing = inTx(session -> executions.findContext(session, 999_999L));
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("a code lookup returns non-live executions too, so entry errors can differ")
    void codeLookupIsUnfiltered() {
        execution("SAME", ExecutionStatus.CLOSED, WHEN.minus(Duration.ofDays(2)));
        execution("SAME", ExecutionStatus.LIVE, WHEN.minus(Duration.ofMinutes(5)));

        List<ExecutionContext> found = inTx(session -> executions.findContextsByCode(session, "same"));

        assertThat(found).hasSize(2);
        assertThat(found.get(0).status())
                .as("newest first, so the service takes the live one")
                .isEqualTo(ExecutionStatus.LIVE);
    }

    @Test
    @DisplayName("a blank code returns nothing rather than every execution")
    void blankCodeReturnsNothing() {
        liveExecution("AB12");

        List<ExecutionContext> blank = inTx(session -> executions.findContextsByCode(session, "  "));
        List<ExecutionContext> nothing = inTx(session -> executions.findContextsByCode(session, null));
        assertThat(blank).isEmpty();
        assertThat(nothing).isEmpty();
    }

    @Test
    @DisplayName("the derived duration and window follow the granted extension (S-20)")
    void extensionMovesTheDerivedValues() {
        long executionId = liveExecution("AB12");

        int total = inTx(session -> executions.addExtraMinutes(session, executionId, 15));

        ExecutionContext ctx = inTx(session -> executions.findContext(session, executionId)).orElseThrow();
        assertThat(total).isEqualTo(15);
        assertThat(ctx.extraMinutes()).isEqualTo(15);
        assertThat(ctx.allottedMinutes()).isEqualTo(DURATION + 15);
        assertThat(ctx.effectiveCloseAt()).isEqualTo(ctx.closeAt().plus(Duration.ofMinutes(15)));
    }

    @Test
    @DisplayName("two extensions accumulate on the execution")
    void extensionsAccumulate() {
        long executionId = liveExecution("AB12");

        inTx(session -> executions.addExtraMinutes(session, executionId, 10));
        int total = inTx(session -> executions.addExtraMinutes(session, executionId, 5));

        assertThat(total).isEqualTo(15);
    }

    @Test
    @DisplayName("extending something that does not exist fails loudly")
    void extendingNothing() {
        assertThatIllegalStateException()
                .isThrownBy(() -> inTx(session -> executions.addExtraMinutes(session, 999_999L, 5)));
    }

    @Test
    @DisplayName("the derived deadline is the start plus the allotted minutes (ADR-010)")
    void deadlineIsDerived() {
        long executionId = liveExecution("AB12");
        long attemptId = startAttempt(executionId, mayaId, WHEN);
        inTx(session -> executions.addExtraMinutes(session, executionId, 15));

        ExecutionContext ctx = inTx(session -> executions.findContext(session, executionId)).orElseThrow();
        AttemptRecord attempt = inTx(session -> attempts.findRecordById(session, attemptId)).orElseThrow();

        // Nothing was written to the attempt: the extension moved its deadline anyway,
        // which is what makes E11.4's "applies on resume" work with no migration at all.
        assertThat(attempt.deadline(ctx))
                .isEqualTo(WHEN.plus(Duration.ofMinutes(DURATION + 15)));
    }

    @Test
    @DisplayName("the derived deadline never outlives the execution's window (B-14 ⚑)")
    void deadlineIsCappedByTheWindow() {
        // A window that shuts twenty minutes from the start, against a 45-minute paper: the
        // exact shape a student who joins legally late is in, and the shape every existing
        // fixture avoided by building a window generously wider than the exam.
        long executionId = execution("CD34", ExecutionStatus.LIVE, WHEN.minus(Duration.ofHours(3))
                .plus(Duration.ofMinutes(20)));
        long attemptId = startAttempt(executionId, mayaId, WHEN);

        ExecutionContext ctx = inTx(session -> executions.findContext(session, executionId)).orElseThrow();
        AttemptRecord attempt = inTx(session -> attempts.findRecordById(session, attemptId)).orElseThrow();

        assertThat(ctx.effectiveCloseAt()).isEqualTo(WHEN.plus(Duration.ofMinutes(20)));
        assertThat(attempt.deadline(ctx))
                .as("min(started + allotted, the window's close) - the window wins here")
                .isEqualTo(WHEN.plus(Duration.ofMinutes(20)));
        assertThat(ctx.sittingMinutesFrom(WHEN))
                .as("what she must be told at entry, rather than the paper's own 45")
                .isEqualTo(20);
        assertThat(ctx.windowShortensSittingFrom(WHEN)).isTrue();
    }

    @Test
    @DisplayName("closing freezes the counts and marks the execution closed (S-21)")
    void closingFreezesCounts() {
        long executionId = liveExecution("AB12");
        startAttempt(executionId, mayaId, WHEN);
        long danas = startAttempt(executionId, danaId, WHEN);
        inTx(session -> attempts.finalizeAttempt(session, danas, AttemptStatus.SUBMITTED,
                WHEN.plus(Duration.ofMinutes(30)), 30));

        ParticipationCounts counts = inTx(session -> attempts.countParticipation(session, executionId));
        runInTx(session -> executions.freezeParticipation(session, executionId, counts));

        ExamExecution stored = inTx(session -> session.get(ExamExecution.class, executionId));
        assertThat(stored.getStatus()).isEqualTo(ExecutionStatus.CLOSED);
        assertThat(stored.getParticipation().started()).isEqualTo(2);
        assertThat(stored.getParticipation().finished()).isEqualTo(1);
        assertThat(stored.getParticipation().timedOut()).isZero();
    }

    @Test
    @DisplayName("freezing an execution that does not exist fails loudly")
    void freezingNothing() {
        assertThatIllegalStateException().isThrownBy(() ->
                runInTx(session -> executions.freezeParticipation(session, 999_999L,
                        new ParticipationCounts(0, 0, 0))));
    }

    @Test
    @DisplayName("executions with live attempts are what the server re-arms from after a restart")
    void findsExecutionsToRearm() {
        long withLive = liveExecution("AB12");
        long allDone = liveExecution("CD34");
        startAttempt(withLive, mayaId, WHEN);
        long finished = startAttempt(allDone, mayaId, WHEN);
        inTx(session -> attempts.finalizeAttempt(session, finished, AttemptStatus.SUBMITTED, WHEN, 10));

        List<Long> ids = inTx(session -> executions.findExecutionIdsWithLiveAttempts(session));

        assertThat(ids).containsExactly(withLive);
    }

    // ===================== Attempts ======================================

    @Test
    @DisplayName("an attempt is created and found by execution and by id")
    void createsAndFindsAnAttempt() {
        long executionId = liveExecution("AB12");

        AttemptRecord created = inTx(session ->
                attempts.createAttempt(session, executionId, mayaId, WHEN));

        assertThat(created.status()).isEqualTo(AttemptStatus.IN_PROGRESS);
        assertThat(created.startedAt()).isEqualTo(WHEN);
        Optional<AttemptRecord> byStudent = inTx(session -> attempts.findRecord(session, executionId, mayaId));
        Optional<AttemptRecord> byId = inTx(session -> attempts.findRecordById(session, created.attemptId()));
        assertThat(byStudent).contains(created);
        assertThat(byId).contains(created);
    }

    @Test
    @DisplayName("a second attempt is refused by the unique key, not by a check (F6.7) ⚑")
    void secondAttemptIsRefused() {
        long executionId = liveExecution("AB12");
        inTx(session -> attempts.createAttempt(session, executionId, mayaId, WHEN));

        // Two clicks a millisecond apart both pass a "does she have one?" read; only the
        // constraint can stop the second, and this is where that is proved. It throws
        // rather than answering empty because the failed flush poisons its transaction.
        assertThatExceptionOfType(DuplicateAttemptException.class).isThrownBy(() ->
                inTx(session -> attempts.createAttempt(session, executionId, mayaId, WHEN.plusSeconds(1))));

        AttemptRecord survivor = inTx(session ->
                attempts.findRecord(session, executionId, mayaId)).orElseThrow();
        assertThat(survivor.startedAt())
                .as("and the first attempt is untouched, with its own start time")
                .isEqualTo(WHEN);
    }

    @Test
    @DisplayName("two students at the same execution get two separate attempts")
    void twoStudentsTwoAttempts() {
        long executionId = liveExecution("AB12");

        long mayas = startAttempt(executionId, mayaId, WHEN);
        long danas = startAttempt(executionId, danaId, WHEN);

        assertThat(mayas).isNotEqualTo(danas);
    }

    @Test
    @DisplayName("finalising is a compare-and-set: the first caller wins, the second sees zero ⚑")
    void finalisationIsCompareAndSet() {
        long executionId = liveExecution("AB12");
        long attemptId = startAttempt(executionId, mayaId, WHEN);

        int first = inTx(session -> attempts.finalizeAttempt(session, attemptId,
                AttemptStatus.SUBMITTED, WHEN.plus(Duration.ofMinutes(30)), 30));
        int second = inTx(session -> attempts.finalizeAttempt(session, attemptId,
                AttemptStatus.TIMED_OUT, WHEN.plus(Duration.ofMinutes(45)), 45));


        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        AttemptRecord stored = inTx(session -> attempts.findRecordById(session, attemptId)).orElseThrow();
        assertThat(stored.status())
                .as("the loser must not overwrite the winner (§5)")
                .isEqualTo(AttemptStatus.SUBMITTED);
        assertThat(stored.actualMinutes()).isEqualTo(30);
    }

    @Test
    @DisplayName("an attempt is never finalised back to IN_PROGRESS")
    void cannotReopenAnAttempt() {
        long executionId = liveExecution("AB12");
        long attemptId = startAttempt(executionId, mayaId, WHEN);

        assertThatIllegalArgumentException().isThrownBy(() ->
                inTx(session -> attempts.finalizeAttempt(session, attemptId,
                        AttemptStatus.IN_PROGRESS, WHEN, 0)));
    }

    @Test
    @DisplayName("live attempts are found per execution and across the server")
    void findsLiveAttempts() {
        long executionId = liveExecution("AB12");
        long live = startAttempt(executionId, mayaId, WHEN);
        long done = startAttempt(executionId, danaId, WHEN);
        inTx(session -> attempts.finalizeAttempt(session, done, AttemptStatus.TIMED_OUT, WHEN, 45));

        List<AttemptRecord> here = inTx(session -> attempts.findInProgressAt(session, executionId));
        List<AttemptRecord> everywhere = inTx(session -> attempts.findAllInProgress(session));
        assertThat(here).extracting(AttemptRecord::attemptId).containsExactly(live);
        assertThat(everywhere).extracting(AttemptRecord::attemptId).containsExactly(live);
    }

    // ===================== Answers =======================================

    @Test
    @DisplayName("an answer is written once and replaced on change, never duplicated")
    void answersAreUpserted() {
        long executionId = liveExecution("AB12");
        long attemptId = startAttempt(executionId, mayaId, WHEN);
        long questionVersionId = firstQuestionVersionId();

        runInTx(session -> attempts.upsertAnswer(session, attemptId, questionVersionId, (byte) 2, WHEN));
        runInTx(session -> attempts.upsertAnswer(session, attemptId, questionVersionId, (byte) 4,
                WHEN.plusSeconds(30)));

        List<AnswerRow> rows = inTx(session -> attempts.findAnswers(session, attemptId));
        int answered = inTx(session -> attempts.countAnswered(session, attemptId));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).selected()).isEqualTo(4);
        assertThat(answered).isEqualTo(1);
    }

    @Test
    @DisplayName("a cleared answer keeps its row but stops counting")
    void clearedAnswersDoNotCount() {
        long executionId = liveExecution("AB12");
        long attemptId = startAttempt(executionId, mayaId, WHEN);
        long questionVersionId = firstQuestionVersionId();
        runInTx(session -> attempts.upsertAnswer(session, attemptId, questionVersionId, (byte) 2, WHEN));

        runInTx(session -> attempts.upsertAnswer(session, attemptId, questionVersionId, null, WHEN));

        int answered = inTx(session -> attempts.countAnswered(session, attemptId));
        List<AnswerRow> rows = inTx(session -> attempts.findAnswers(session, attemptId));
        assertThat(answered).isZero();
        assertThat(rows).singleElement().extracting(AnswerRow::isAnswered).isEqualTo(false);
    }

    @Test
    @DisplayName("an attempt with no answers reads as empty, not null")
    void noAnswersYet() {
        long executionId = liveExecution("AB12");
        long attemptId = startAttempt(executionId, mayaId, WHEN);

        List<AnswerRow> rows = inTx(session -> attempts.findAnswers(session, attemptId));
        int answered = inTx(session -> attempts.countAnswered(session, attemptId));
        assertThat(rows).isEmpty();
        assertThat(answered).isZero();
    }

    // ===================== The monitor's reads ===========================

    @Test
    @DisplayName("monitor rows carry the student's name, ordered by it")
    void monitorRowsAreNamedAndOrdered() {
        long executionId = liveExecution("AB12");
        startAttempt(executionId, mayaId, WHEN);
        startAttempt(executionId, danaId, WHEN);

        List<AttemptRow> rows = inTx(session -> attempts.findRows(session, executionId));

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(AttemptRow::studentName).isSorted();
        assertThat(rows).extracting(AttemptRow::status)
                .containsOnly(AttemptStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("answered counts come back for every attempt in one query")
    void answeredCountsInOneQuery() {
        long executionId = liveExecution("AB12");
        long mayas = startAttempt(executionId, mayaId, WHEN);
        long danas = startAttempt(executionId, danaId, WHEN);
        List<Long> paper = questionVersionIds();
        runInTx(session -> {
            attempts.upsertAnswer(session, mayas, paper.get(0), (byte) 1, WHEN);
            attempts.upsertAnswer(session, mayas, paper.get(1), (byte) 2, WHEN);
            attempts.upsertAnswer(session, danas, paper.get(0), (byte) 3, WHEN);
        });

        Map<Long, Integer> counts = inTx(session ->
                attempts.countAnsweredByAttempt(session, executionId));

        assertThat(counts).containsEntry(mayas, 2).containsEntry(danas, 1);
    }

    @Test
    @DisplayName("an attempt with no answers is simply absent from the counts")
    void unansweredAttemptIsAbsent() {
        long executionId = liveExecution("AB12");
        long attemptId = startAttempt(executionId, mayaId, WHEN);

        Map<Long, Integer> counts = inTx(session -> attempts.countAnsweredByAttempt(session, executionId));
        assertThat(counts).doesNotContainKey(attemptId);
    }

    // ===================== The paper's reads =============================

    @Test
    @DisplayName("the question count comes back without fetching the paper")
    void countsQuestions() {
        long executionId = liveExecution("AB12");
        long examVersionId = inTx(session -> executions.findContext(session, executionId))
                .orElseThrow().examVersionId();

        int count = inTx(session -> questions.countForTakeExam(session, examVersionId));
        assertThat(count).isEqualTo(3);
    }

    @Test
    @DisplayName("the outline carries positions, ids and points, and no answer key")
    void outlineHasNoAnswerKey() {
        long executionId = liveExecution("AB12");
        long examVersionId = inTx(session -> executions.findContext(session, executionId))
                .orElseThrow().examVersionId();

        List<QuestionOutline> outline = inTx(session ->
                questions.findOutlineForTakeExam(session, examVersionId));

        assertThat(outline).extracting(QuestionOutline::ordinal).containsExactly(1, 2, 3);
        assertThat(outline).extracting(QuestionOutline::points).containsExactly(40, 40, 20);
        assertThat(outline).extracting(QuestionOutline::displayId)
                .containsExactly("11001", "11002", "11003");
    }

    @Test
    @DisplayName("a question is only answerable if it is on this paper")
    void questionMembershipIsChecked() {
        long executionId = liveExecution("AB12");
        long examVersionId = inTx(session -> executions.findContext(session, executionId))
                .orElseThrow().examVersionId();
        long onPaper = firstQuestionVersionId();

        boolean mine = inTx(session -> questions.isOnTakeExamPaper(session, examVersionId, onPaper));
        boolean stranger = inTx(session -> questions.isOnTakeExamPaper(session, examVersionId, 999_999L));
        assertThat(mine).isTrue();
        assertThat(stranger).isFalse();
    }

    // ===================== Enrolment =====================================

    @Test
    @DisplayName("enrolment is its own question, and teaching a course is not enrolment")
    void enrolmentIsNarrow() {
        // Maya is enrolled in Algebra; Dana teaches it. The take-exam gate must tell them
        // apart, which a scan of "courses this user is attached to" could not.
        boolean mayaAlgebra = inTx(session -> courses.isEnrolled(session, mayaId, COURSE_ALGEBRA));
        boolean danaAlgebra = inTx(session -> courses.isEnrolled(session, danaId, COURSE_ALGEBRA));
        boolean mayaCalculus = inTx(session -> courses.isEnrolled(session, mayaId, COURSE_CALCULUS));
        boolean nullCourse = inTx(session -> courses.isEnrolled(session, mayaId, null));
        boolean blankCourse = inTx(session -> courses.isEnrolled(session, mayaId, " "));
        assertThat(mayaAlgebra).isTrue();
        assertThat(danaAlgebra).isFalse();
        assertThat(mayaCalculus).isFalse();
        assertThat(nullCourse).isFalse();
        assertThat(blankCourse).isFalse();
    }

    // ===================== Fixture =======================================

    /** An Algebra execution with a three-question paper, run by Dana. */
    protected final long liveExecution(String code) {
        return execution(code, ExecutionStatus.LIVE, WHEN.minus(Duration.ofMinutes(5)));
    }

    protected final long execution(String code, ExecutionStatus status, Instant openAt) {
        long examVersionId = composeExam();
        return inTx(session -> {
            ExamExecution execution = new ExamExecution(examVersionId, code, openAt,
                    openAt.plus(Duration.ofHours(3)), status, danaId);
            session.persist(execution);
            session.flush();
            return execution.getId();
        });
    }

    protected final long startAttempt(long executionId, long studentId, Instant startedAt) {
        return inTx(session -> attempts.createAttempt(session, executionId, studentId, startedAt))
                .attemptId();
    }

    /** @return the question version ids of the most recently composed paper, in order. */
    protected final List<Long> questionVersionIds() {
        return inTx(session -> session.createQuery("""
                        select qv.id from QuestionVersion qv order by qv.id
                        """, Long.class).getResultList());
    }

    private long firstQuestionVersionId() {
        return questionVersionIds().get(0);
    }

    /**
     * A fresh exam and version with three questions worth 40/40/20.
     *
     * <p>A copy of {@code TakeExamProjectionContract.composeExam} rather than a shared
     * superclass: that one hardcodes serial 1 because it only ever builds one exam, and
     * these tests build several in a single method. Sharing it would mean changing a
     * fixture another suite's assertions depend on.
     */
    protected final long composeExam() {
        return inTx(session -> {
            byte serial = (byte) (countExams(session) + 1);
            Exam exam = new Exam(COURSE_ALGEBRA, serial,
                    SUBJECT_MATH + COURSE_ALGEBRA + String.format("%02d", serial), danaId);
            session.persist(exam);
            session.flush();

            ExamVersion version = new ExamVersion(exam.getId(), 1, "מבחן אמצע", DURATION,
                    "ענו על כל השאלות.", "לבודק בלבד", ExamVersionStatus.APPROVED, WHEN);
            session.persist(version);
            session.flush();

            int[] points = {40, 40, 20};
            for (int index = 0; index < 3; index++) {
                short questionSerial = (short) (countQuestions(session) + 1);
                String displayId = COURSE_ALGEBRA + String.format("%03d", questionSerial);
                Question question = new Question(COURSE_ALGEBRA, questionSerial, displayId);
                session.persist(question);
                session.flush();

                QuestionVersion qv = new QuestionVersion(question.getId(), 1,
                        "שאלה " + questionSerial, "1, 6", "2, 3", "-2, -3", "0, 5",
                        (byte) 2, "פונקציות", Difficulty.EASY, null, danaId, WHEN);
                session.persist(qv);
                session.flush();

                session.persist(new ExamVersionQuestion(
                        version.getId(), qv.getId(), question.getId(), points[index], index + 1));
            }
            session.flush();
            return version.getId();
        });
    }

    private static int countExams(org.hibernate.Session session) {
        return session.createQuery("select count(e) from Exam e", Long.class)
                .getSingleResult().intValue();
    }

    private static int countQuestions(org.hibernate.Session session) {
        return session.createQuery("select count(q) from Question q", Long.class)
                .getSingleResult().intValue();
    }
}
