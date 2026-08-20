package server.features.exam;

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
import server.db.projections.TakeExamQuestion;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * {@link JpaExamStore} driven through the seam the services actually use (E10/E11).
 *
 * <p>{@code ExamFlowRepositoryContract} proves the repositories; this proves the thing in
 * front of them. The distinction matters because the store is what every rule in
 * {@code AttemptService} runs against in production, while the unit tests run against an
 * in-memory double — so without this suite the production data path would be reasoned about
 * rather than executed, and a method wired to the wrong repository would pass everything.
 *
 * <p>It also pins the transaction boundary: an {@link ExamData} is valid for one
 * {@code inTx} call, and a caller that keeps one is holding a closed session.
 */
abstract class JpaExamStoreContract extends RepositoryTestBase {

    protected static final Instant WHEN = Instant.parse("2026-08-20T09:00:00Z");
    protected static final int DURATION = 45;

    private ExamStore store;

    private ExamStore store() {
        if (store == null) {
            store = new JpaExamStore(factory());
        }
        return store;
    }

    // ===================== Executions and people =========================

    @Test
    @DisplayName("a code lookup reaches the real query, non-live rows included")
    void executionsByCode() {
        long executionId = liveExecution("AB12");

        List<ExecutionContext> found = store().inTx(data -> data.executionsByCode("ab12"));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).executionId()).isEqualTo(executionId);
        assertThat(found.get(0).allottedMinutes()).isEqualTo(DURATION);
    }

    @Test
    @DisplayName("an execution is found by id, and an unknown one answers empty")
    void executionById() {
        long executionId = liveExecution("AB12");

        Optional<ExecutionContext> found = store().inTx(data -> data.executionById(executionId));
        Optional<ExecutionContext> missing = store().inTx(data -> data.executionById(999_999L));
        assertThat(found).isPresent();
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("a user's identity comes back with the number S-18 compares against")
    void userIdentity() {
        Optional<StudentIdentity> maya = store().inTx(data -> data.user(mayaId));

        assertThat(maya).isPresent();
        assertThat(maya.get().matches("374301851")).isTrue();
        assertThat(maya.get().matches("999999999")).isFalse();
        Optional<StudentIdentity> nobody = store().inTx(data -> data.user(999_999L));
        assertThat(nobody).isEmpty();
    }

    @Test
    @DisplayName("enrolment is asked of the real table")
    void enrolment() {
        boolean mayaEnrolled = store().inTx(data -> data.isEnrolled(mayaId, COURSE_ALGEBRA));
        boolean danaEnrolled = store().inTx(data -> data.isEnrolled(danaId, COURSE_ALGEBRA));
        assertThat(mayaEnrolled).isTrue();
        assertThat(danaEnrolled).isFalse();
    }

    // ===================== The paper =====================================

    @Test
    @DisplayName("the paper comes back through the no-correctness projection (E2.12 ⚑)")
    void paperReads() {
        long executionId = liveExecution("AB12");
        long examVersionId = versionOf(executionId);

        List<TakeExamQuestion> paper = store().inTx(data -> data.questionsOf(examVersionId));
        int count = store().inTx(data -> data.questionCountOf(examVersionId));
        List<QuestionOutline> outline = store().inTx(data -> data.outlineOf(examVersionId));

        assertThat(paper).hasSize(3);
        assertThat(count).isEqualTo(3);
        assertThat(outline).extracting(QuestionOutline::ordinal).containsExactly(1, 2, 3);
        assertThat(paper).extracting(TakeExamQuestion::text).doesNotContainNull();
    }

    @Test
    @DisplayName("a question is only answerable if it is on this paper")
    void paperMembership() {
        long executionId = liveExecution("AB12");
        long examVersionId = versionOf(executionId);
        long onPaper = store().inTx(data -> data.questionsOf(examVersionId)).get(0).questionVersionId();

        boolean mine = store().inTx(data -> data.isOnPaper(examVersionId, onPaper));
        boolean stranger = store().inTx(data -> data.isOnPaper(examVersionId, 999_999L));
        assertThat(mine).isTrue();
        assertThat(stranger).isFalse();
    }

    // ===================== Attempts and answers ==========================

    @Test
    @DisplayName("an attempt is created, found both ways, and refused a second time")
    void attemptLifecycle() {
        long executionId = liveExecution("AB12");

        AttemptRecord created = store().inTx(data ->
                data.createAttempt(executionId, mayaId, WHEN));

        Optional<AttemptRecord> byId = store().inTx(data -> data.attemptById(created.attemptId()));
        Optional<AttemptRecord> byStudent = store().inTx(data -> data.attemptOf(executionId, mayaId));
        assertThat(byId).contains(created);
        assertThat(byStudent).contains(created);
        assertThatExceptionOfType(DuplicateAttemptException.class).isThrownBy(() ->
                store().inTx(data -> data.createAttempt(executionId, mayaId, WHEN)));
    }

    @Test
    @DisplayName("finalising through the store is still the compare-and-set (§5)")
    void finalisation() {
        long executionId = liveExecution("AB12");
        long attemptId = store().inTx(data ->
                data.createAttempt(executionId, mayaId, WHEN)).attemptId();

        int first = store().inTx(data -> data.finalizeAttempt(attemptId,
                AttemptStatus.SUBMITTED, WHEN.plus(Duration.ofMinutes(20)), 20));
        int second = store().inTx(data -> data.finalizeAttempt(attemptId,
                AttemptStatus.TIMED_OUT, WHEN.plus(Duration.ofMinutes(45)), 45));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        Optional<AttemptRecord> stored = store().inTx(data -> data.attemptById(attemptId));
        assertThat(stored).get().extracting(AttemptRecord::status).isEqualTo(AttemptStatus.SUBMITTED);
    }

    @Test
    @DisplayName("answers are upserted and counted through the store")
    void answers() {
        long executionId = liveExecution("AB12");
        long attemptId = store().inTx(data ->
                data.createAttempt(executionId, mayaId, WHEN)).attemptId();
        long questionVersionId = store().inTx(data ->
                data.questionsOf(versionOf(executionId))).get(0).questionVersionId();

        store().runInTx(data -> data.upsertAnswer(attemptId, questionVersionId, (byte) 2, WHEN));
        store().runInTx(data -> data.upsertAnswer(attemptId, questionVersionId, (byte) 4, WHEN));

        List<AnswerRow> rows = store().inTx(data -> data.answersOf(attemptId));
        int answered = store().inTx(data -> data.countAnswered(attemptId));

        assertThat(rows).singleElement().extracting(AnswerRow::selected).isEqualTo(4);
        assertThat(answered).isEqualTo(1);
    }

    @Test
    @DisplayName("live attempts are found per execution and across the server")
    void liveAttempts() {
        long executionId = liveExecution("AB12");
        long live = store().inTx(data -> data.createAttempt(executionId, mayaId, WHEN)).attemptId();
        long done = store().inTx(data -> data.createAttempt(executionId, danaId, WHEN)).attemptId();
        store().inTx(data -> data.finalizeAttempt(done, AttemptStatus.TIMED_OUT, WHEN, 45));

        List<AttemptRecord> here = store().inTx(data -> data.liveAttemptsOf(executionId));
        List<AttemptRecord> everywhere = store().inTx(data -> data.allLiveAttempts());
        assertThat(here).extracting(AttemptRecord::attemptId).containsExactly(live);
        assertThat(everywhere).extracting(AttemptRecord::attemptId).containsExactly(live);
    }

    // ===================== Monitoring and writes =========================

    @Test
    @DisplayName("the monitor's three reads all come through the store")
    void monitorReads() {
        long executionId = liveExecution("AB12");
        long attemptId = store().inTx(data ->
                data.createAttempt(executionId, mayaId, WHEN)).attemptId();
        long questionVersionId = store().inTx(data ->
                data.questionsOf(versionOf(executionId))).get(0).questionVersionId();
        store().runInTx(data -> data.upsertAnswer(attemptId, questionVersionId, (byte) 1, WHEN));

        ParticipationCounts counts = store().inTx(data -> data.participationOf(executionId));
        List<AttemptRow> rows = store().inTx(data -> data.rowsOf(executionId));
        Map<Long, Integer> answered = store().inTx(data -> data.answeredCountsOf(executionId));

        assertThat(counts.started()).isEqualTo(1);
        assertThat(rows).singleElement().extracting(AttemptRow::studentId).isEqualTo(mayaId);
        assertThat(answered).containsEntry(attemptId, 1);
    }

    @Test
    @DisplayName("extending and closing both write through the store (S-20, S-21)")
    void writesOnTheExecution() {
        long executionId = liveExecution("AB12");
        store().inTx(data -> data.createAttempt(executionId, mayaId, WHEN));

        int total = store().inTx(data -> data.addExtraMinutes(executionId, 15));
        ParticipationCounts counts = store().inTx(data -> data.participationOf(executionId));
        store().runInTx(data -> data.closeExecution(executionId, counts));

        assertThat(total).isEqualTo(15);
        ExecutionContext after = store().inTx(data -> data.executionById(executionId)).orElseThrow();
        assertThat(after.extraMinutes()).isEqualTo(15);
        assertThat(after.status()).isEqualTo(ExecutionStatus.CLOSED);
    }

    // ===================== The seam itself ===============================

    @Test
    @DisplayName("a unit of work returns its own value, and null work is rejected")
    void transactionSeam() {
        String value = store().inTx(data -> "done");
        assertThat(value).isEqualTo("done");
        assertThatNullPointerException().isThrownBy(() -> store().inTx(null));
        assertThatNullPointerException().isThrownBy(() -> new JpaExamStore(null));
    }

    @Test
    @DisplayName("a failure inside a unit of work rolls back rather than half-committing")
    void failureRollsBack() {
        long executionId = liveExecution("AB12");

        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() ->
                store().runInTx(data -> {
                    data.createAttempt(executionId, mayaId, WHEN);
                    throw new IllegalStateException("something went wrong halfway");
                }));

        // A half-committed exam submission is the kind of bug that only appears in a demo.
        Optional<AttemptRecord> nothing = store().inTx(data -> data.attemptOf(executionId, mayaId));
        assertThat(nothing).isEmpty();
    }

    // ===================== Fixture =======================================

    private long versionOf(long executionId) {
        return store().inTx(data -> data.executionById(executionId)).orElseThrow().examVersionId();
    }

    /** One live Algebra execution with a three-question paper, run by Dana. */
    private long liveExecution(String code) {
        return inTx(session -> {
            Exam exam = new Exam(COURSE_ALGEBRA, (byte) 1, "101101", danaId);
            session.persist(exam);
            session.flush();

            ExamVersion version = new ExamVersion(exam.getId(), 1, "מבחן אמצע", DURATION,
                    "ענו על כל השאלות.", null, ExamVersionStatus.APPROVED, WHEN);
            session.persist(version);
            session.flush();

            for (int index = 0; index < 3; index++) {
                short serial = (short) (index + 1);
                Question question = new Question(COURSE_ALGEBRA, serial,
                        COURSE_ALGEBRA + String.format("%03d", serial));
                session.persist(question);
                session.flush();

                QuestionVersion qv = new QuestionVersion(question.getId(), 1,
                        "שאלה " + serial, "1, 6", "2, 3", "-2, -3", "0, 5",
                        (byte) 2, "פונקציות", Difficulty.EASY, null, danaId, WHEN);
                session.persist(qv);
                session.flush();

                session.persist(new ExamVersionQuestion(version.getId(), qv.getId(),
                        question.getId(), index == 2 ? 20 : 40, index + 1));
            }

            ExamExecution execution = new ExamExecution(version.getId(), code,
                    WHEN.minus(Duration.ofMinutes(5)), WHEN.plus(Duration.ofHours(3)),
                    ExecutionStatus.LIVE, danaId);
            session.persist(execution);
            session.flush();
            return execution.getId();
        });
    }
}
