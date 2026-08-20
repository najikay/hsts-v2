package server.db.repos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.RepositoryTestBase;
import server.db.entities.Difficulty;
import server.db.entities.Exam;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionStatus;
import server.db.entities.Question;
import server.db.entities.QuestionVersion;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code QuestionRepository}'s authoring reads and {@code ExamRepository} (E2.11). */
abstract class BankRepositoryContract extends RepositoryTestBase {

    private static final Instant WHEN = Instant.parse("2026-08-20T09:00:00Z");

    private final QuestionRepository questions = new QuestionRepository();
    private final ExamRepository exams = new ExamRepository();

    @Test
    @DisplayName("a question is found by the id people type")
    void findsQuestionByDisplayId() {
        long id = persistQuestion((short) 5);

        Optional<Long> found = inTx(session ->
                questions.findByDisplayId(session, "11005").map(Question::getId));

        assertThat(found).contains(id);
    }

    @Test
    @DisplayName("an unknown display id is empty rather than an error")
    void unknownDisplayIdIsEmpty() {
        Optional<Question> found = inTx(session -> questions.findByDisplayId(session, "99999"));

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("a specific version is returned, not simply the newest")
    void findsAPinnedVersion() {
        // The seed's Algebra Midterm references question 11005 version 1 even though version 2
        // exists, because a released exam is pinned to the version it was built from (C-2).
        long questionId = persistQuestion((short) 5);
        persistVersion(questionId, 1, "ניסוח מקורי");
        persistVersion(questionId, 2, "ניסוח מחודש");

        Optional<String> pinned = inTx(session ->
                questions.findVersionForAuthoring(session, questionId, 1).map(QuestionVersion::getText));

        assertThat(pinned).contains("ניסוח מקורי");
    }

    @Test
    @DisplayName("the latest version is the highest numbered one, not the last inserted")
    void findsTheLatestVersion() {
        long questionId = persistQuestion((short) 5);
        persistVersion(questionId, 2, "שנייה");
        persistVersion(questionId, 1, "ראשונה");

        Optional<Integer> latest = inTx(session ->
                questions.findLatestVersionForAuthoring(session, questionId)
                        .map(QuestionVersion::getVersionNo));

        assertThat(latest).contains(2);
    }

    @Test
    @DisplayName("a question with no versions yet has no latest version")
    void noVersionsYet() {
        long questionId = persistQuestion((short) 7);

        Optional<QuestionVersion> latest = inTx(session ->
                questions.findLatestVersionForAuthoring(session, questionId));

        assertThat(latest).isEmpty();
    }

    @Test
    @DisplayName("an exam is found by its 6-digit id")
    void findsExamByDisplayId() {
        long examId = persistExam((byte) 1);

        Optional<Long> found = inTx(session ->
                exams.findByDisplayId(session, "101101").map(Exam::getId));

        assertThat(found).contains(examId);
    }

    @Test
    @DisplayName("an exam version is found by its number")
    void findsExamVersion() {
        long examId = persistExam((byte) 1);
        persistExamVersion(examId, 1, ExamVersionStatus.APPROVED);
        persistExamVersion(examId, 2, ExamVersionStatus.DRAFT);

        Optional<ExamVersionStatus> second = inTx(session ->
                exams.findVersion(session, examId, 2).map(ExamVersion::getStatus));

        assertThat(second).contains(ExamVersionStatus.DRAFT);
    }

    @Test
    @DisplayName("the approval queue holds only versions actually awaiting a decision")
    void pendingVersionsOnly() {
        long examId = persistExam((byte) 1);
        persistExamVersion(examId, 1, ExamVersionStatus.APPROVED);
        persistExamVersion(examId, 2, ExamVersionStatus.PENDING);
        persistExamVersion(examId, 3, ExamVersionStatus.REJECTED);
        persistExamVersion(examId, 4, ExamVersionStatus.PENDING);

        List<ExamVersion> pending = inTx(session -> exams.findPendingVersions(session, examId));

        assertThat(pending).extracting(ExamVersion::getVersionNo).containsExactly(2, 4);
    }

    private long persistQuestion(short serial) {
        return inTx(session -> {
            Question question = new Question(COURSE_ALGEBRA, serial,
                    COURSE_ALGEBRA + String.format("%03d", serial));
            session.persist(question);
            session.flush();
            return question.getId();
        });
    }

    private void persistVersion(long questionId, int versionNo, String text) {
        runInTx(session -> session.persist(new QuestionVersion(questionId, versionNo, text,
                "1, 6", "2, 3", "-2, -3", "0, 5", (byte) 2, "פונקציות", Difficulty.EASY,
                null, danaId, WHEN)));
    }

    private long persistExam(byte serial) {
        return inTx(session -> {
            Exam exam = new Exam(COURSE_ALGEBRA, serial,
                    SUBJECT_MATH + COURSE_ALGEBRA + String.format("%02d", serial), danaId);
            session.persist(exam);
            session.flush();
            return exam.getId();
        });
    }

    private void persistExamVersion(long examId, int versionNo, ExamVersionStatus status) {
        runInTx(session -> session.persist(new ExamVersion(examId, versionNo,
                "גרסה " + versionNo, 60, null, null, status, WHEN)));
    }
}
