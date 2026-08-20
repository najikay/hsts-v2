package server.db.repos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.RepositoryTestBase;
import server.db.entities.AttemptStatus;
import server.db.entities.Exam;
import server.db.entities.ExamAttempt;
import server.db.entities.ExamExecution;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionStatus;
import server.db.entities.ExecutionStatus;
import server.db.entities.Grade;
import server.db.projections.ParticipationCounts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code ExecutionRepository}, {@code AttemptRepository} and {@code GradeRepository} (E2.11). */
abstract class ExecutionRepositoryContract extends RepositoryTestBase {

    private static final Instant WHEN = Instant.parse("2026-08-20T09:00:00Z");

    private final ExecutionRepository executions = new ExecutionRepository();
    private final AttemptRepository attempts = new AttemptRepository();
    private final GradeRepository grades = new GradeRepository();

    @Test
    @DisplayName("an execution is found by its code whatever case it is typed in")
    void findsExecutionByCode() {
        persistExecution("AB12", ExecutionStatus.LIVE);

        List<ExamExecution> found = inTx(session -> executions.findByCode(session, "ab12"));

        assertThat(found).hasSize(1);
    }

    @Test
    @DisplayName("a blank code returns nothing rather than every execution")
    void blankCodeReturnsNothing() {
        persistExecution("AB12", ExecutionStatus.LIVE);

        List<ExamExecution> none = inTx(session -> executions.findByCode(session, "   "));

        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("a code reused after an execution closed returns both rows")
    void codeMayRepeatOnceClosed() {
        // Uniqueness holds only among non-closed executions, and §5 makes that a service rule
        // because MySQL has no partial unique index. A method returning Optional here would
        // throw on data the schema considers perfectly legal.
        persistExecution("AB12", ExecutionStatus.CLOSED);
        persistExecution("AB12", ExecutionStatus.LIVE);

        List<ExamExecution> found = inTx(session -> executions.findByCode(session, "AB12"));

        assertThat(found).hasSize(2);
    }

    @Test
    @DisplayName("only the live execution can be joined")
    void onlyLiveIsJoinable() {
        persistExecution("AB12", ExecutionStatus.CLOSED);
        persistExecution("AB12", ExecutionStatus.LIVE);

        Optional<ExecutionStatus> joinable = inTx(session ->
                executions.findJoinable(session, "AB12").map(ExamExecution::getStatus));

        assertThat(joinable).contains(ExecutionStatus.LIVE);
    }

    @Test
    @DisplayName("a cancelled or scheduled execution is not joinable")
    void nonLiveIsNotJoinable() {
        persistExecution("CD34", ExecutionStatus.SCHEDULED);
        persistExecution("EF56", ExecutionStatus.CANCELLED);

        Optional<ExamExecution> scheduled = inTx(session -> executions.findJoinable(session, "CD34"));
        Optional<ExamExecution> cancelled = inTx(session -> executions.findJoinable(session, "EF56"));

        assertThat(scheduled).isEmpty();
        assertThat(cancelled).isEmpty();
    }

    @Test
    @DisplayName("a student attempt at an execution is found, and there is at most one")
    void findsAttempt() {
        long executionId = persistExecution("AB12", ExecutionStatus.LIVE);
        persistAttempt(executionId, mayaId);

        Optional<Long> found = inTx(session ->
                attempts.findByExecutionAndStudent(session, executionId, mayaId)
                        .map(ExamAttempt::getStudentId));

        assertThat(found).contains(mayaId);
    }

    @Test
    @DisplayName("a student who has not started has no attempt")
    void noAttemptYet() {
        long executionId = persistExecution("AB12", ExecutionStatus.LIVE);

        Optional<ExamAttempt> none = inTx(session ->
                attempts.findByExecutionAndStudent(session, executionId, mayaId));

        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("participation is counted from the attempts, not stored")
    void participationIsDerived() {
        long executionId = persistExecution("AB12", ExecutionStatus.LIVE);
        persistAttempt(executionId, mayaId);
        persistAttempt(executionId, danaId);
        persistAttempt(executionId, rinaId);
        finalise(executionId, danaId, AttemptStatus.SUBMITTED);
        finalise(executionId, rinaId, AttemptStatus.TIMED_OUT);

        ParticipationCounts counts = inTx(session -> attempts.countParticipation(session, executionId));

        assertThat(counts.started()).isEqualTo(3);
        assertThat(counts.finished()).isEqualTo(1);
        assertThat(counts.timedOut()).isEqualTo(1);
    }

    @Test
    @DisplayName("an execution nobody joined counts zero rather than failing")
    void participationOfAnEmptyExecution() {
        long executionId = persistExecution("AB12", ExecutionStatus.SCHEDULED);

        ParticipationCounts counts = inTx(session -> attempts.countParticipation(session, executionId));

        assertThat(counts).isEqualTo(new ParticipationCounts(0, 0, 0));
    }

    @Test
    @DisplayName("the grade for an attempt is found")
    void findsGradeByAttempt() {
        long executionId = persistExecution("AB12", ExecutionStatus.CLOSED);
        long attemptId = persistAttempt(executionId, mayaId);
        runInTx(session -> session.persist(new Grade(attemptId, 88)));

        Optional<Integer> score = inTx(session ->
                grades.findByAttempt(session, attemptId).map(Grade::getAutoScore));

        assertThat(score).contains(88);
    }

    @Test
    @DisplayName("the grading queue holds only grades a teacher has not approved")
    void awaitingApprovalOnly() {
        long executionId = persistExecution("AB12", ExecutionStatus.CLOSED);
        long mayasAttempt = persistAttempt(executionId, mayaId);
        long danasAttempt = persistAttempt(executionId, danaId);
        runInTx(session -> {
            session.persist(new Grade(mayasAttempt, 88));
            session.persist(approvedGrade(danasAttempt));
        });

        List<Grade> queue = inTx(session -> grades.findAwaitingApproval(session, executionId));

        assertThat(queue).extracting(Grade::getAttemptId).containsExactly(mayasAttempt);
    }

    @Test
    @DisplayName("a student's grades are approved ones only — marking in progress publishes nothing")
    void approvedForStudentExcludesUnapproved() {
        long executionId = persistExecution("AB12", ExecutionStatus.CLOSED);
        long approvedAttempt = persistAttempt(executionId, mayaId);
        long pendingAttempt = persistAttempt(executionId, danaId);
        runInTx(session -> {
            session.persist(approvedGrade(approvedAttempt));
            session.persist(new Grade(pendingAttempt, 91));
        });

        List<Grade> mine = inTx(session -> grades.findApprovedForStudent(session, mayaId));
        List<Grade> theirs = inTx(session -> grades.findApprovedForStudent(session, danaId));

        assertThat(mine).extracting(Grade::getAttemptId).containsExactly(approvedAttempt);
        // danaId's only grade is still AUTO, so she sees nothing at all (C-3, S-24).
        assertThat(theirs).isEmpty();
    }

    @Test
    @DisplayName("⚑ a student's grades never include another student's")
    void approvedForStudentIsScopedToTheStudent() {
        long executionId = persistExecution("AB12", ExecutionStatus.CLOSED);
        long mayasAttempt = persistAttempt(executionId, mayaId);
        long danasAttempt = persistAttempt(executionId, danaId);
        runInTx(session -> {
            session.persist(approvedGrade(mayasAttempt));
            session.persist(approvedGrade(danasAttempt));
        });

        List<Grade> mine = inTx(session -> grades.findApprovedForStudent(session, mayaId));

        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).getAttemptId()).isEqualTo(mayasAttempt);
    }

    @Test
    @DisplayName("a student who has been approved nothing gets an empty list, not an error")
    void approvedForStudentEmptyWhenNothingApproved() {
        List<Grade> none = inTx(session -> grades.findApprovedForStudent(session, mayaId));

        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("a student's own grade is found by id")
    void findsOwnGradeById() {
        long executionId = persistExecution("AB12", ExecutionStatus.CLOSED);
        long attemptId = persistAttempt(executionId, mayaId);
        long gradeId = inTx(session -> {
            Grade grade = approvedGrade(attemptId);
            session.persist(grade);
            session.flush();
            return grade.getId();
        });

        Optional<Grade> found = inTx(session -> grades.findForStudent(session, gradeId, mayaId));

        assertThat(found).isPresent();
        assertThat(found.get().getAttemptId()).isEqualTo(attemptId);
    }

    @Test
    @DisplayName("⚑ another student's grade id is empty, exactly like an id that does not exist")
    void anotherStudentsGradeIsIndistinguishableFromMissing() {
        long executionId = persistExecution("AB12", ExecutionStatus.CLOSED);
        long danasAttempt = persistAttempt(executionId, danaId);
        long danasGradeId = inTx(session -> {
            Grade grade = approvedGrade(danasAttempt);
            session.persist(grade);
            session.flush();
            return grade.getId();
        });

        // maya asks for dana's grade, and for one that was never created.
        Optional<Grade> somebodyElses =
                inTx(session -> grades.findForStudent(session, danasGradeId, mayaId));
        Optional<Grade> neverExisted =
                inTx(session -> grades.findForStudent(session, 999_999L, mayaId));

        // Identical answers: the query cannot be used to discover that a grade exists.
        assertThat(somebodyElses).isEqualTo(neverExisted).isEmpty();
    }

    private long persistExecution(String code, ExecutionStatus status) {
        long examVersionId = newExamVersion();
        return inTx(session -> {
            ExamExecution execution = new ExamExecution(examVersionId, code,
                    WHEN, WHEN.plusSeconds(3600), status, danaId);
            session.persist(execution);
            session.flush();
            return execution.getId();
        });
    }

    /** A fresh exam and version per call: exam serials are unique per course. */
    private long newExamVersion() {
        return inTx(session -> {
            byte serial = (byte) (countExams(session) + 1);
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

    private static int countExams(org.hibernate.Session session) {
        return session.createQuery("select count(e) from Exam e", Long.class)
                .getSingleResult()
                .intValue();
    }

    private long persistAttempt(long executionId, long studentId) {
        return inTx(session -> {
            ExamAttempt attempt = new ExamAttempt(executionId, studentId, WHEN);
            session.persist(attempt);
            session.flush();
            return attempt.getId();
        });
    }

    /**
     * Finalises an attempt the way production does: a status-guarded atomic UPDATE (§5), not a
     * setter, because the entity deliberately has none.
     */
    private void finalise(long executionId, long studentId, AttemptStatus status) {
        runInTx(session -> session.createMutationQuery(
                        "update ExamAttempt set status = :status, endedAt = :endedAt,"
                                + " actualMinutes = 30 where executionId = :executionId"
                                + " and studentId = :studentId and status = :inProgress")
                .setParameter("status", status)
                .setParameter("endedAt", WHEN.plusSeconds(1800))
                .setParameter("executionId", executionId)
                .setParameter("studentId", studentId)
                .setParameter("inProgress", AttemptStatus.IN_PROGRESS)
                .executeUpdate());
    }

    /** A grade a teacher has already signed off, so it must not appear in the queue. */
    private Grade approvedGrade(long attemptId) {
        Grade grade = new Grade(attemptId, 70);
        grade.override(75, "התאמה לרמת הכיתה");
        grade.setTeacherComment("עבודה טובה");
        grade.approve(danaId, WHEN);
        return grade;
    }
}
