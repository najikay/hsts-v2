package server.db.repos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.RepositoryTestBase;
import server.db.entities.AttemptStatus;
import server.db.entities.Difficulty;
import server.db.entities.Exam;
import server.db.entities.ExamAttempt;
import server.db.entities.ExamExecution;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionQuestion;
import server.db.entities.ExamVersionStatus;
import server.db.entities.ExecutionStatus;
import server.db.entities.Grade;
import server.db.entities.Question;

import server.db.entities.QuestionVersion;
import server.db.projections.ExamVersionContext;
import server.db.projections.ExecutionContext;
import server.db.projections.GradeExamLabel;
import server.db.projections.ParticipationCounts;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code ExecutionRepository}, {@code AttemptRepository} and {@code GradeRepository} (E2.11). */
abstract class ExecutionRepositoryContract extends RepositoryTestBase {

    private static final Instant WHEN = Instant.parse("2026-08-20T09:00:00Z");

    private final ExecutionRepository executions = new ExecutionRepository();
    private final AttemptRepository attempts = new AttemptRepository();
    private final GradeRepository grades = new GradeRepository();
    private final ExamRepository exams = new ExamRepository();
    private final QuestionRepository questions = new QuestionRepository();

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
    @DisplayName("an execution is found by its id, and a missing one is empty rather than an error")
    void findsExecutionById() {
        long executionId = persistExecution("AB12", ExecutionStatus.CLOSED);

        Optional<ExamExecution> found = inTx(session -> executions.findById(session, executionId));
        Optional<ExamExecution> missing = inTx(session -> executions.findById(session, 999_999L));

        assertThat(found).isPresent();
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("the pinned questions come back in presentation order with their points")
    void pinnedQuestionsInOrder() {
        long examVersionId = newExamVersion();
        long first = persistQuestionVersion(1, (byte) 2);
        long second = persistQuestionVersion(2, (byte) 3);
        runInTx(session -> {
            session.persist(new ExamVersionQuestion(examVersionId, second, questionIdOf(session, second), 40, 2));
            session.persist(new ExamVersionQuestion(examVersionId, first, questionIdOf(session, first), 60, 1));
        });

        List<ExamVersionQuestion> pinned =
                inTx(session -> exams.findPinnedQuestions(session, examVersionId));

        // Insertion order was 2 then 1; the read must return ordinal order regardless.
        assertThat(pinned).extracting(ExamVersionQuestion::getQuestionVersionId)
                .containsExactly(first, second);
        assertThat(pinned).extracting(ExamVersionQuestion::getPoints).containsExactly(60, 40);
    }

    @Test
    @DisplayName("an exam version with no questions pins nothing rather than failing")
    void pinnedQuestionsOfAnEmptyVersion() {
        long examVersionId = newExamVersion();

        List<ExamVersionQuestion> none = inTx(session -> exams.findPinnedQuestions(session, examVersionId));
        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("ForGrading returns the answer key for exactly the versions asked for")
    void versionsForGradingCarryTheKey() {
        long wanted = persistQuestionVersion(1, (byte) 3);
        long other = persistQuestionVersion(2, (byte) 1);

        List<QuestionVersion> loaded = inTx(session ->
                questions.findVersionsForGrading(session, List.of(wanted)));

        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).getId()).isEqualTo(wanted);
        assertThat(loaded.get(0).getCorrectAnswer()).isEqualTo((byte) 3);
        assertThat(loaded).extracting(QuestionVersion::getId).doesNotContain(other);
    }

    @Test
    @DisplayName("asking ForGrading for nothing returns nothing, not everything")
    void versionsForGradingWithNoIds() {
        persistQuestionVersion(1, (byte) 1);

        // An `in ()` with no values would be a syntax error or a full scan; neither is right.
        List<QuestionVersion> none = inTx(session -> questions.findVersionsForGrading(session, List.of()));
        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("a grade is fetched by its own id, unscoped — the caller owns the gate")
    void findsGradeByIdUnscoped() {
        long executionId = persistExecution("AB12", ExecutionStatus.CLOSED);
        long attemptId = persistAttempt(executionId, mayaId);
        long gradeId = inTx(session -> {
            Grade grade = new Grade(attemptId, 88);
            session.persist(grade);
            session.flush();
            return grade.getId();
        });

        Optional<Grade> found = inTx(session -> grades.findByIdUnscoped(session, gradeId));

        assertThat(found).isPresent();
        assertThat(found.get().getAutoScore()).isEqualTo(88);
    }

    @Test
    @DisplayName("a grade id that was never issued is empty, not an exception")
    void findByIdUnscopedIsEmptyForUnknown() {
        Optional<Grade> none = inTx(session -> grades.findByIdUnscoped(session, 999_999L));

        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("grades are labelled with the exam they were for, keyed by grade id")
    void findsExamLabels() {
        long executionId = persistExecution("AB12", ExecutionStatus.CLOSED);
        long mayasAttempt = persistAttempt(executionId, mayaId);
        long danasAttempt = persistAttempt(executionId, danaId);
        List<Long> ids = inTx(session -> {
            Grade a = new Grade(mayasAttempt, 88);
            Grade b = new Grade(danasAttempt, 71);
            session.persist(a);
            session.persist(b);
            session.flush();
            return List.of(a.getId(), b.getId());
        });

        Map<Long, GradeExamLabel> labels =
                inTx(session -> grades.findExamLabels(session, ids));

        // Four joins away from the grade row, which is the whole reason this read exists.
        assertThat(labels).hasSize(2);
        assertThat(labels.get(ids.get(0)).examName()).isEqualTo("מבחן");
        assertThat(labels.get(ids.get(0)).courseCode()).isEqualTo(COURSE_ALGEBRA);
        assertThat(labels.get(ids.get(1)).gradeId()).isEqualTo(ids.get(1));
    }

    @Test
    @DisplayName("a grade whose label was not asked for is simply absent from the map")
    void findsExamLabelsOnlyForWhatWasAsked() {
        long executionId = persistExecution("AB12", ExecutionStatus.CLOSED);
        long attemptId = persistAttempt(executionId, mayaId);
        long gradeId = inTx(session -> {
            Grade grade = new Grade(attemptId, 88);
            session.persist(grade);
            session.flush();
            return grade.getId();
        });

        Map<Long, GradeExamLabel> labels = inTx(session ->
                grades.findExamLabels(session, List.of(gradeId, 999_999L)));

        assertThat(labels).containsOnlyKeys(gradeId);
    }

    @Test
    @DisplayName("asking for no labels returns none, not every grade's")
    void findsNoLabelsForAnEmptyIdList() {
        Map<Long, GradeExamLabel> none =
                inTx(session -> grades.findExamLabels(session, List.of()));

        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("display ids come back by question id, for a marked paper")
    void findsDisplayIds() {
        long first = persistQuestionVersion(1, (byte) 1);
        long second = persistQuestionVersion(2, (byte) 4);
        List<Long> questionIds = inTx(session ->
                List.of(questionIdOf(session, first), questionIdOf(session, second)));

        Map<Long, String> displayIds =
                inTx(session -> questions.findDisplayIds(session, questionIds));

        assertThat(displayIds).hasSize(2);
        assertThat(displayIds.get(questionIds.get(0))).isEqualTo(COURSE_ALGEBRA + "001");
        assertThat(displayIds.get(questionIds.get(1))).isEqualTo(COURSE_ALGEBRA + "002");
    }

    @Test
    @DisplayName("asking for no display ids returns none, not the whole bank")
    void findsNoDisplayIdsForAnEmptyIdList() {
        persistQuestionVersion(1, (byte) 1);

        Map<Long, String> none = inTx(session -> questions.findDisplayIds(session, List.of()));

        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("grades are fetched by id, and ids that do not exist simply do not appear")
    void findsGradesByIds() {
        long executionId = persistExecution("AB12", ExecutionStatus.CLOSED);
        long mayasAttempt = persistAttempt(executionId, mayaId);
        long danasAttempt = persistAttempt(executionId, danaId);
        List<Long> ids = inTx(session -> {
            Grade a = new Grade(mayasAttempt, 88);
            Grade b = new Grade(danasAttempt, 71);
            session.persist(a);
            session.persist(b);
            session.flush();
            return List.of(a.getId(), b.getId());
        });

        List<Grade> found = inTx(session ->
                grades.findByIds(session, List.of(ids.get(0), ids.get(1), 999_999L)));

        // The missing id is absent rather than an error — the caller reports it as refused.
        assertThat(found).extracting(Grade::getId).containsExactly(ids.get(0), ids.get(1));
    }

    @Test
    @DisplayName("asking for no ids returns nothing, not every grade")
    void findsNoGradesForAnEmptyIdList() {
        long executionId = persistExecution("AB12", ExecutionStatus.CLOSED);
        long attemptId = persistAttempt(executionId, mayaId);
        runInTx(session -> session.persist(new Grade(attemptId, 88)));

        List<Grade> none = inTx(session -> grades.findByIds(session, List.of()));

        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("every grade of an execution comes back, whatever its status")
    void findsAllGradesForExecution() {
        long executionId = persistExecution("AB12", ExecutionStatus.CLOSED);
        long mayasAttempt = persistAttempt(executionId, mayaId);
        long danasAttempt = persistAttempt(executionId, danaId);
        runInTx(session -> {
            session.persist(new Grade(mayasAttempt, 88));          // AUTO
            session.persist(approvedGrade(danasAttempt));           // APPROVED
        });

        List<Grade> all = inTx(session -> grades.findAllForExecution(session, executionId));

        // findAwaitingApproval would return one; completion detection needs both.
        assertThat(all).hasSize(2);
        assertThat(all).extracting(Grade::getAttemptId)
                .containsExactly(mayasAttempt, danasAttempt);
    }

    @Test
    @DisplayName("an execution nobody sat has no grades rather than failing")
    void findsNoGradesForAnUngradedExecution() {
        long executionId = persistExecution("AB12", ExecutionStatus.SCHEDULED);

        List<Grade> none = inTx(session -> grades.findAllForExecution(session, executionId));

        assertThat(none).isEmpty();
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

    // ===================== E9: the release manager's additions ============

    @Test
    @DisplayName("⚑ a code is held only while its execution is scheduled or live (§5, C-1)")
    void codeUniquenessIsPartial() {
        persistExecution("AAAA", ExecutionStatus.SCHEDULED);
        persistExecution("BBBB", ExecutionStatus.LIVE);
        persistExecution("CCCC", ExecutionStatus.CLOSED);
        persistExecution("DDDD", ExecutionStatus.CANCELLED);

        // The rule has no constraint behind it, so it is entirely this where clause: one
        // that forgot the status filter would reserve every code ever issued, for ever.
        boolean scheduledHolds = inTx(session -> executions.isCodeInUse(session, "AAAA"));
        boolean liveHolds = inTx(session -> executions.isCodeInUse(session, "bbbb"));
        boolean closedHolds = inTx(session -> executions.isCodeInUse(session, "CCCC"));
        boolean cancelledHolds = inTx(session -> executions.isCodeInUse(session, "DDDD"));

        assertThat(scheduledHolds).isTrue();
        assertThat(liveHolds).isTrue();
        assertThat(closedHolds).isFalse();
        assertThat(cancelledHolds).isFalse();
    }

    @Test
    @DisplayName("a created execution is scheduled and belongs to the teacher who released it")
    void createsAScheduledExecution() {
        long examVersionId = newExamVersion();

        long executionId = inTx(session -> executions.create(session, examVersionId, "4B7Q",
                WHEN, WHEN.plusSeconds(3600), danaId));

        ExamExecution created = inTx(session -> executions.findById(session, executionId))
                .orElseThrow();
        assertThat(created.getStatus()).isEqualTo(ExecutionStatus.SCHEDULED);
        assertThat(created.getCreatedBy()).isEqualTo(danaId);
        assertThat(created.getCode()).isEqualTo("4B7Q");
    }

    @Test
    @DisplayName("⚑ a status transition fires only from the state it was told to expect (§5)")
    void transitionIsGuarded() {
        long executionId = persistExecution("AAAA", ExecutionStatus.SCHEDULED);

        int opened = inTx(session -> executions.transition(session, executionId,
                ExecutionStatus.SCHEDULED, ExecutionStatus.LIVE));
        int cancelledTooLate = inTx(session -> executions.transition(session, executionId,
                ExecutionStatus.SCHEDULED, ExecutionStatus.CANCELLED));

        // The scheduled check opening a release and a teacher cancelling it are two writers
        // a second apart; the loser must change nothing and know it changed nothing.
        assertThat(opened).isEqualTo(1);
        assertThat(cancelledTooLate).isZero();
        Optional<ExamExecution> after = inTx(session -> executions.findById(session, executionId));
        assertThat(after.orElseThrow().getStatus()).isEqualTo(ExecutionStatus.LIVE);
    }

    @Test
    @DisplayName("participation for a whole list comes back in one grouped read")
    void batchedParticipation() {
        long busy = persistExecution("AAAA", ExecutionStatus.LIVE);
        long quiet = persistExecution("BBBB", ExecutionStatus.SCHEDULED);
        persistAttempt(busy, mayaId);
        persistAttempt(busy, danaId);
        persistAttempt(busy, rinaId);
        finalise(busy, danaId, AttemptStatus.SUBMITTED);
        finalise(busy, rinaId, AttemptStatus.TIMED_OUT);

        Map<Long, ParticipationCounts> counts = inTx(session ->
                attempts.countParticipationByExecution(session, List.of(busy, quiet)));

        assertThat(counts.get(busy)).isEqualTo(new ParticipationCounts(3, 1, 1));
        // Absent rather than three zeros, on the same convention as the other batched reads.
        assertThat(counts).doesNotContainKey(quiet);
        Map<Long, ParticipationCounts> none = inTx(session ->
                attempts.countParticipationByExecution(session, List.of()));
        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("the release list holds what she ran and what her own exams were run as (S-35)")
    void executionsForATeacher() {
        long executionId = persistExecution("AAAA", ExecutionStatus.CANCELLED);

        List<ExecutionContext> hers =
                inTx(session -> executions.findContextsForTeacher(session, danaId));
        List<ExecutionContext> somebodyElses =
                inTx(session -> executions.findContextsForTeacher(session, mayaId));

        // Cancelled rows stay here, unlike on the results picker: "I called that one off" is
        // exactly the fact the release manager is being asked for.
        assertThat(hers).extracting(ExecutionContext::executionId).contains(executionId);
        assertThat(somebodyElses).isEmpty();
    }

    @Test
    @DisplayName("the scheduled check sees scheduled rows up to its horizon, and live overdue ones")
    void schedulerReads() {
        long soon = persistExecution("AAAA", ExecutionStatus.SCHEDULED);
        long live = persistExecution("BBBB", ExecutionStatus.LIVE);

        List<ExecutionContext> opening = inTx(session ->
                executions.findScheduledOpeningBy(session, WHEN.plusSeconds(60)));
        List<ExecutionContext> closing = inTx(session ->
                executions.findLiveClosingBy(session, WHEN.plusSeconds(7200)));

        assertThat(opening).extracting(ExecutionContext::executionId).containsExactly(soon);
        assertThat(closing).extracting(ExecutionContext::executionId).containsExactly(live);
    }

    @Test
    @DisplayName("only approved versions of a course she teaches are releasable (F5.1)")
    void releasableVersions() {
        long approved = newApprovedVersion();

        List<ExamVersionContext> releasable =
                inTx(session -> exams.findReleasableForTeacher(session, danaId));
        List<ExamVersionContext> notHers =
                inTx(session -> exams.findReleasableForTeacher(session, mayaId));

        assertThat(releasable).extracting(ExamVersionContext::examVersionId)
                .containsExactly(approved);
        assertThat(notHers).isEmpty();
        boolean danaHasExams = inTx(session -> exams.hasAnyExamInTaughtCourses(session, danaId));
        boolean mayaHasExams = inTx(session -> exams.hasAnyExamInTaughtCourses(session, mayaId));
        assertThat(danaHasExams).isTrue();
        assertThat(mayaHasExams).isFalse();
    }

    @Test
    @DisplayName("the students enrolled in a course are who the opens-soon notice reaches")
    void enrolledStudents() {
        List<Long> algebra = inTx(session ->
                new CourseRepository().findEnrolledStudentIds(session, COURSE_ALGEBRA));
        List<Long> nobody = inTx(session ->
                new CourseRepository().findEnrolledStudentIds(session, "99"));

        assertThat(algebra).containsExactly(mayaId);
        assertThat(nobody).isEmpty();
    }

    /** An approved version of a course dana teaches, for the releasable-versions read. */
    private long newApprovedVersion() {
        long examVersionId = newExamVersion();
        runInTx(session -> session.createMutationQuery(
                        "update ExamVersion set status = :status where id = :id")
                .setParameter("status", ExamVersionStatus.APPROVED)
                .setParameter("id", examVersionId)
                .executeUpdate());
        return examVersionId;
    }

    /** A question with one version, so grading fixtures have a real answer key to read. */
    private long persistQuestionVersion(int serial, byte correctAnswer) {
        return inTx(session -> {
            Question question = new Question(COURSE_ALGEBRA, (short) serial,
                    COURSE_ALGEBRA + String.format("%03d", serial));
            session.persist(question);
            session.flush();
            QuestionVersion version = new QuestionVersion(question.getId(), 1, "שאלה",
                    "א", "ב", "ג", "ד", correctAnswer, "משוואות", Difficulty.EASY, null,
                    danaId, WHEN);
            session.persist(version);
            session.flush();
            return version.getId();
        });
    }

    private static long questionIdOf(org.hibernate.Session session, long questionVersionId) {
        return session.createQuery(
                        "select questionId from QuestionVersion where id = :id", Long.class)
                .setParameter("id", questionVersionId)
                .getSingleResult();
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
