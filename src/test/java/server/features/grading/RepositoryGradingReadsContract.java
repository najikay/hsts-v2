package server.features.grading;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.RepositoryTestBase;
import server.db.entities.AttemptAnswer;
import server.db.entities.Difficulty;
import server.db.entities.Exam;
import server.db.entities.ExamAttempt;
import server.db.entities.ExamExecution;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionQuestion;
import server.db.entities.ExamVersionStatus;
import server.db.entities.ExecutionStatus;
import server.db.entities.Question;
import server.db.entities.QuestionVersion;
import server.db.repos.AttemptRepository;
import server.db.repos.ExamRepository;
import server.db.repos.ExecutionRepository;
import server.db.repos.QuestionRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * {@link RepositoryGradingReads} against a real database — E12.1's last mile.
 *
 * <p>`GradingServiceTest` proves the grading rules against mocks; this proves the reads those
 * mocks stood in for actually return what the rules assume. The two together are what make
 * "auto-grading works" a claim rather than a hope.
 *
 * <p>The fixture is the seeded Algebra Midterm's shape: a pinned question at **version 1** while
 * a version 2 of the same question exists in the bank. That is the trap PRD §6 describes, and
 * {@link #gradesAgainstThePinnedVersionNotTheLatest()} is the test that springs it.
 */
abstract class RepositoryGradingReadsContract extends RepositoryTestBase {

    private static final Instant WHEN = Instant.parse("2026-08-20T09:00:00Z");

    private final RepositoryGradingReads reads = new RepositoryGradingReads(
            new ExecutionRepository(), new ExamRepository(),
            new QuestionRepository(), new AttemptRepository());

    @Test
    @DisplayName("the pinned questions come back with their points and their answer key")
    void readsPinnedQuestionsWithTheKey() {
        long examVersionId = newExamVersion();
        long questionId = newQuestion(1);
        long v1 = newVersion(questionId, 1, (byte) 2);
        pin(examVersionId, v1, questionId, 100, 1);

        List<AutoGrader.PinnedQuestion> pinned =
                inTx(session -> reads.pinnedQuestions(session, examVersionId));

        assertThat(pinned).hasSize(1);
        assertThat(pinned.get(0).questionVersionId()).isEqualTo(v1);
        assertThat(pinned.get(0).points()).isEqualTo(100);
        assertThat(pinned.get(0).correctAnswer()).isEqualTo((byte) 2);
    }

    @Test
    @DisplayName("⚑ H12.6 — the key read is the PINNED version's, not the question's latest")
    void gradesAgainstThePinnedVersionNotTheLatest() {
        long examVersionId = newExamVersion();
        long questionId = newQuestion(1);
        long v1 = newVersion(questionId, 1, (byte) 2);   // released exam pins this
        long v2 = newVersion(questionId, 2, (byte) 4);   // later edit, different correct answer
        pin(examVersionId, v1, questionId, 100, 1);

        List<AutoGrader.PinnedQuestion> pinned =
                inTx(session -> reads.pinnedQuestions(session, examVersionId));

        // If this ever reads v2, every past grade on this exam silently changes.
        assertThat(pinned).hasSize(1);
        assertThat(pinned.get(0).questionVersionId()).isEqualTo(v1);
        assertThat(pinned.get(0).correctAnswer()).isEqualTo((byte) 2);
        assertThat(v2).isNotEqualTo(v1);
    }

    @Test
    @DisplayName("questions arrive in presentation order, whatever order they were pinned in")
    void pinnedQuestionsKeepExamOrder() {
        long examVersionId = newExamVersion();
        long firstQ = newQuestion(1);
        long secondQ = newQuestion(2);
        long first = newVersion(firstQ, 1, (byte) 1);
        long second = newVersion(secondQ, 1, (byte) 3);
        pin(examVersionId, second, secondQ, 40, 2);
        pin(examVersionId, first, firstQ, 60, 1);

        List<AutoGrader.PinnedQuestion> pinned =
                inTx(session -> reads.pinnedQuestions(session, examVersionId));

        assertThat(pinned).extracting(AutoGrader.PinnedQuestion::questionVersionId)
                .containsExactly(first, second);
    }

    @Test
    @DisplayName("only answered questions appear — an unanswered one is absent, not null")
    void readsSelectedAnswers() {
        long executionId = newExecution();
        long attemptId = newAttempt(executionId, mayaId);
        long questionId = newQuestion(1);
        long answered = newVersion(questionId, 1, (byte) 2);
        long untouchedQ = newQuestion(2);
        long untouched = newVersion(untouchedQ, 1, (byte) 3);
        runInTx(session -> session.persist(new AttemptAnswer(attemptId, answered, (byte) 2, WHEN)));

        Map<Long, Byte> selected = inTx(session -> reads.selectedAnswers(session, attemptId));

        assertThat(selected).containsExactly(Map.entry(answered, (byte) 2));
        assertThat(selected).doesNotContainKey(untouched);
    }

    @Test
    @DisplayName("H12.4 — an attempt that answered nothing reads as an empty map")
    void readsNoAnswers() {
        long executionId = newExecution();
        long attemptId = newAttempt(executionId, mayaId);

        Map<Long, Byte> selected = inTx(session -> reads.selectedAnswers(session, attemptId));

        assertThat(selected).isEmpty();
    }

    @Test
    @DisplayName("the exam version an execution released is resolved from the execution")
    void resolvesExamVersion() {
        long examVersionId = newExamVersion();
        long executionId = inTx(session -> {
            ExamExecution execution = new ExamExecution(examVersionId, "AB12",
                    WHEN, WHEN.plusSeconds(3600), ExecutionStatus.CLOSED, danaId);
            session.persist(execution);
            session.flush();
            return execution.getId();
        });

        long resolved = inTx(session -> reads.examVersionOf(session, executionId));

        assertThat(resolved).isEqualTo(examVersionId);
    }

    @Test
    @DisplayName("an execution that does not exist is a server fault, not a silent zero")
    void missingExecution() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> inTx(session -> reads.examVersionOf(session, 999_999L)))
                .withMessageContaining("999999");
    }

    @Test
    @DisplayName("an exam version with no questions reads as empty — the grader refuses it, not this")
    void emptyExamVersion() {
        long examVersionId = newExamVersion();

        List<AutoGrader.PinnedQuestion> none =
                inTx(session -> reads.pinnedQuestions(session, examVersionId));

        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("what the reads produce is gradeable end to end")
    void feedsTheGraderDirectly() {
        long examVersionId = newExamVersion();
        long qA = newQuestion(1);
        long qB = newQuestion(2);
        long vA = newVersion(qA, 1, (byte) 2);
        long vB = newVersion(qB, 1, (byte) 4);
        pin(examVersionId, vA, qA, 60, 1);
        pin(examVersionId, vB, qB, 40, 2);
        long executionId = newExecutionFor(examVersionId);
        long attemptId = newAttempt(executionId, mayaId);
        runInTx(session -> session.persist(new AttemptAnswer(attemptId, vA, (byte) 2, WHEN)));

        AutoGrader.Result result = inTx(session -> AutoGrader.grade(
                reads.pinnedQuestions(session, examVersionId),
                reads.selectedAnswers(session, attemptId)));

        // First right (60), second unanswered (0).
        assertThat(result.score()).isEqualTo(60);
        assertThat(result.questions()).hasSize(2);
    }

    // ===== fixtures =======================================================

    private long newExamVersion() {
        return inTx(session -> {
            byte serial = (byte) (count(session, "Exam") + 1);
            Exam exam = new Exam(COURSE_ALGEBRA, serial,
                    SUBJECT_MATH + COURSE_ALGEBRA + String.format("%02d", serial), danaId);
            session.persist(exam);
            session.flush();
            ExamVersion version = new ExamVersion(exam.getId(), 1, "מבחן", 60,
                    null, null, ExamVersionStatus.APPROVED, WHEN);
            session.persist(version);
            session.flush();
            return version.getId();
        });
    }

    private long newExecution() {
        return newExecutionFor(newExamVersion());
    }

    private long newExecutionFor(long examVersionId) {
        return inTx(session -> {
            ExamExecution execution = new ExamExecution(examVersionId, "AB12",
                    WHEN, WHEN.plusSeconds(3600), ExecutionStatus.CLOSED, danaId);
            session.persist(execution);
            session.flush();
            return execution.getId();
        });
    }

    private long newQuestion(int serial) {
        return inTx(session -> {
            Question question = new Question(COURSE_ALGEBRA, (short) serial,
                    COURSE_ALGEBRA + String.format("%03d", serial));
            session.persist(question);
            session.flush();
            return question.getId();
        });
    }

    private long newVersion(long questionId, int versionNo, byte correctAnswer) {
        return inTx(session -> {
            QuestionVersion version = new QuestionVersion(questionId, versionNo, "שאלה",
                    "א", "ב", "ג", "ד", correctAnswer, "משוואות", Difficulty.EASY, null,
                    danaId, WHEN);
            session.persist(version);
            session.flush();
            return version.getId();
        });
    }

    private void pin(long examVersionId, long questionVersionId, long questionId,
                     int points, int ordinal) {
        runInTx(session -> session.persist(new ExamVersionQuestion(
                examVersionId, questionVersionId, questionId, points, ordinal)));
    }

    private long newAttempt(long executionId, long studentId) {
        return inTx(session -> {
            ExamAttempt attempt = new ExamAttempt(executionId, studentId, WHEN);
            session.persist(attempt);
            session.flush();
            return attempt.getId();
        });
    }

    private static int count(org.hibernate.Session session, String entity) {
        return session.createQuery("select count(e) from " + entity + " e", Long.class)
                .getSingleResult()
                .intValue();
    }

}
