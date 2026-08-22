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
import server.db.entities.ExecutionStats;
import server.db.entities.ExecutionStatus;
import server.db.entities.Grade;
import server.db.entities.User;
import server.db.entities.UserRole;
import server.db.projections.AuthoredExam;
import server.db.projections.ExecutionContext;
import server.db.projections.StudentResultRow;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The six reads E14's teacher results screen added to the shared repositories (E14.1).
 *
 * <p>Added under TEAM_SPLIT rule 5 — repositories grow with their callers — so each one is
 * contract-tested on both engines here, with its consumer named in its javadoc.
 *
 * <p>The rule these tests exist for is S-35, and it is a query, not a check: an exam's author
 * sees every sitting of it <b>including ones another teacher released</b>, and never sees a
 * sitting of an exam she did not write. Both halves are asserted against rows that differ only
 * in who released them, because that is the difference the {@code WHERE} clause turns on.
 */
abstract class ResultsRepositoryContract extends RepositoryTestBase {

    private static final Instant OPENED = Instant.parse("2026-08-07T06:00:00Z");

    private final ExamRepository exams = new ExamRepository();
    private final ExecutionRepository executions = new ExecutionRepository();
    private final AttemptRepository attempts = new AttemptRepository();
    private final GradeRepository grades = new GradeRepository();

    // ===================== ExamRepository.findAuthoredSummaries ==========

    @Test
    @DisplayName("a teacher's authored exams come back with their course and their name")
    void authoredExamsCarryCourseAndName() {
        newExam(COURSE_ALGEBRA, (byte) 1, danaId, "מבחן אמצע");

        List<AuthoredExam> mine = inTx(session -> exams.findAuthoredSummaries(session, danaId));

        assertThat(mine).hasSize(1);
        AuthoredExam exam = mine.get(0);
        assertThat(exam.displayId()).isEqualTo(SUBJECT_MATH + COURSE_ALGEBRA + "01");
        assertThat(exam.courseCode()).isEqualTo(COURSE_ALGEBRA);
        assertThat(exam.courseName()).isEqualTo("אלגברה");
        assertThat(exam.examName()).isEqualTo("מבחן אמצע");
    }

    @Test
    @DisplayName("⚑ an exam another teacher wrote is never in her list")
    void authoredExamsAreScopedToTheAuthor() {
        newExam(COURSE_ALGEBRA, (byte) 1, danaId, "שלה");
        newExam(COURSE_CALCULUS, (byte) 1, rinaId, "של רינה");

        List<AuthoredExam> mine = inTx(session -> exams.findAuthoredSummaries(session, danaId));
        List<AuthoredExam> hers = inTx(session -> exams.findAuthoredSummaries(session, rinaId));

        assertThat(mine).extracting(AuthoredExam::examName).containsExactly("שלה");
        assertThat(hers).extracting(AuthoredExam::examName).containsExactly("של רינה");
    }

    @Test
    @DisplayName("the name comes from the most recent version, not the first one")
    void latestVersionNamesTheExam() {
        long examId = newExam(COURSE_ALGEBRA, (byte) 1, danaId, "השם הישן");
        addVersion(examId, 2, "השם החדש");

        List<AuthoredExam> mine = inTx(session -> exams.findAuthoredSummaries(session, danaId));

        assertThat(mine).extracting(AuthoredExam::examName).containsExactly("השם החדש");
    }

    @Test
    @DisplayName("a teacher who wrote nothing gets an empty list rather than everybody's exams")
    void noExamsIsEmpty() {
        newExam(COURSE_ALGEBRA, (byte) 1, danaId, "שלה");

        List<AuthoredExam> none = inTx(session -> exams.findAuthoredSummaries(session, principalId));
        assertThat(none).isEmpty();
    }

    // ===================== ExecutionRepository.findContextsByExamAuthor ==

    @Test
    @DisplayName("⚑ the author sees a sitting another teacher released (S-35)")
    void authorSeesSittingsRunByOthers() {
        long examId = newExam(COURSE_ALGEBRA, (byte) 1, danaId, "מבחן");
        long versionId = versionIdOf(examId, 1);
        // Rina released Dana's exam. That is the case S-35 is about.
        long executionId = newExecution(versionId, "4821", ExecutionStatus.CLOSED, rinaId, 0);

        List<ExecutionContext> mine =
                inTx(session -> executions.findContextsByExamAuthor(session, danaId));

        assertThat(mine).extracting(ExecutionContext::executionId).containsExactly(executionId);
        assertThat(mine.get(0).executingTeacherId()).isEqualTo(rinaId);
        assertThat(mine.get(0).authorId()).isEqualTo(danaId);
    }

    @Test
    @DisplayName("⚑ a sitting of somebody else's exam is not returned, whoever released it")
    void nonAuthorSeesNothing() {
        long examId = newExam(COURSE_ALGEBRA, (byte) 1, danaId, "מבחן");
        newExecution(versionIdOf(examId, 1), "4821", ExecutionStatus.CLOSED, rinaId, 0);

        // Rina released it and still cannot read its results: she did not write the exam.
        List<ExecutionContext> hers =
                inTx(session -> executions.findContextsByExamAuthor(session, rinaId));
        assertThat(hers).isEmpty();
    }

    @Test
    @DisplayName("cancelled sittings are excluded (H15.2)")
    void cancelledSittingsAreExcluded() {
        long examId = newExam(COURSE_ALGEBRA, (byte) 1, danaId, "מבחן");
        long versionId = versionIdOf(examId, 1);
        long live = newExecution(versionId, "2075", ExecutionStatus.LIVE, danaId, 0);
        newExecution(versionId, "9999", ExecutionStatus.CANCELLED, danaId, 1);

        List<ExecutionContext> mine =
                inTx(session -> executions.findContextsByExamAuthor(session, danaId));

        assertThat(mine).extracting(ExecutionContext::executionId).containsExactly(live);
    }

    @Test
    @DisplayName("sittings come back newest first, so the picker opens on the recent one")
    void sittingsAreNewestFirst() {
        long examId = newExam(COURSE_ALGEBRA, (byte) 1, danaId, "מבחן");
        long versionId = versionIdOf(examId, 1);
        long older = newExecution(versionId, "4821", ExecutionStatus.CLOSED, danaId, 0);
        long newer = newExecution(versionId, "2075", ExecutionStatus.CLOSED, danaId, 5);

        List<ExecutionContext> mine =
                inTx(session -> executions.findContextsByExamAuthor(session, danaId));

        assertThat(mine).extracting(ExecutionContext::executionId).containsExactly(newer, older);
    }

    // ===================== ExecutionRepository.findIdsWithStatistics =====

    @Test
    @DisplayName("only the sittings whose statistics are frozen come back")
    void statisticsFlagIsAColumnCheck() {
        long examId = newExam(COURSE_ALGEBRA, (byte) 1, danaId, "מבחן");
        long versionId = versionIdOf(examId, 1);
        long frozen = newExecution(versionId, "4821", ExecutionStatus.CLOSED, danaId, 0);
        long unfrozen = newExecution(versionId, "2075", ExecutionStatus.CLOSED, danaId, 1);
        freezeStatistics(frozen);

        List<Long> found = inTx(session ->
                executions.findIdsWithStatistics(session, List.of(frozen, unfrozen)));

        assertThat(found).containsExactly(frozen);
    }

    @Test
    @DisplayName("asking about no sittings returns nothing rather than every sitting")
    void statisticsOfNothing() {
        long examId = newExam(COURSE_ALGEBRA, (byte) 1, danaId, "מבחן");
        freezeStatistics(newExecution(versionIdOf(examId, 1), "4821",
                ExecutionStatus.CLOSED, danaId, 0));

        List<Long> none = inTx(session -> executions.findIdsWithStatistics(session, List.of()));
        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("the frozen record survives the JSON round trip exactly (F8.5)")
    void frozenStatisticsRoundTrip() {
        long examId = newExam(COURSE_ALGEBRA, (byte) 1, danaId, "מבחן");
        long executionId = newExecution(versionIdOf(examId, 1), "4821",
                ExecutionStatus.CLOSED, danaId, 0);
        freezeStatistics(executionId);

        ExecutionStats stored = inTx(session ->
                executions.findById(session, executionId).orElseThrow().getStats());

        assertThat(stored.average()).isEqualTo(72.5);
        assertThat(stored.stdDev())
                .as("population sigma; a sample divisor would have stored 18.71")
                .isEqualTo(17.5);
        assertThat(stored.passRate()).isEqualTo(0.875);
        assertThat(stored.deciles()).containsExactly(0, 0, 0, 0, 1, 1, 1, 2, 1, 2);
    }

    // ===================== The two grouped counts ========================

    @Test
    @DisplayName("participants are counted per sitting in one query, and zeros are absent")
    void participantsAreGrouped() {
        long examId = newExam(COURSE_ALGEBRA, (byte) 1, danaId, "מבחן");
        long versionId = versionIdOf(examId, 1);
        long sat = newExecution(versionId, "4821", ExecutionStatus.CLOSED, danaId, 0);
        long nobody = newExecution(versionId, "2075", ExecutionStatus.CLOSED, danaId, 1);
        long yaelId = newStudent("yael.azulay", "יעל אזולאי", "301548203");
        persistAttempt(sat, mayaId);
        persistAttempt(sat, yaelId);

        Map<Long, Integer> counts = inTx(session ->
                attempts.countAttemptsByExecution(session, List.of(sat, nobody)));

        assertThat(counts).containsEntry(sat, 2);
        assertThat(counts)
                .as("an execution nobody joined is absent, exactly as the monitor's counts are")
                .doesNotContainKey(nobody);
    }

    @Test
    @DisplayName("counting no sittings returns nothing rather than every attempt")
    void participantsOfNothing() {
        Map<Long, Integer> none =
                inTx(session -> attempts.countAttemptsByExecution(session, List.of()));
        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("grade rows are counted per sitting, approved or not")
    void gradesAreGrouped() {
        long examId = newExam(COURSE_ALGEBRA, (byte) 1, danaId, "מבחן");
        long versionId = versionIdOf(examId, 1);
        long marked = newExecution(versionId, "4821", ExecutionStatus.CLOSED, danaId, 0);
        long unmarked = newExecution(versionId, "2075", ExecutionStatus.CLOSED, danaId, 1);
        long yaelId = newStudent("yael.azulay", "יעל אזולאי", "301548203");
        long mayasAttempt = persistAttempt(marked, mayaId);
        long yaelsAttempt = persistAttempt(marked, yaelId);
        persistAttempt(unmarked, mayaId);
        runInTx(session -> {
            session.persist(new Grade(mayasAttempt, 60));
            Grade approved = new Grade(yaelsAttempt, 45);
            approved.approve(danaId, OPENED);
            session.persist(approved);
        });

        Map<Long, Integer> counts = inTx(session ->
                grades.countGradesByExecution(session, List.of(marked, unmarked)));

        assertThat(counts).containsEntry(marked, 2);
        assertThat(counts).doesNotContainKey(unmarked);
    }

    @Test
    @DisplayName("approved grades are counted separately from all grades (E12.5)")
    void approvedGradesAreGrouped() {
        long examId = newExam(COURSE_ALGEBRA, (byte) 1, danaId, "מבחן");
        long versionId = versionIdOf(examId, 1);
        long half = newExecution(versionId, "4821", ExecutionStatus.CLOSED, danaId, 0);
        long untouched = newExecution(versionId, "2075", ExecutionStatus.CLOSED, danaId, 1);
        long yaelId = newStudent("yael.azulay", "יעל אזולאי", "301548203");
        long mayasAttempt = persistAttempt(half, mayaId);
        long yaelsAttempt = persistAttempt(half, yaelId);
        long untouchedAttempt = persistAttempt(untouched, mayaId);
        runInTx(session -> {
            // One approved, one still AUTO, in the same sitting.
            Grade pending = new Grade(mayasAttempt, 60);
            session.persist(pending);
            Grade approved = new Grade(yaelsAttempt, 45);
            approved.approve(danaId, OPENED);
            session.persist(approved);
            // A second sitting with a grade nobody has approved.
            session.persist(new Grade(untouchedAttempt, 70));
        });

        Map<Long, Integer> approvedCounts = inTx(session ->
                grades.countApprovedByExecution(session, List.of(half, untouched)));
        Map<Long, Integer> allCounts = inTx(session ->
                grades.countGradesByExecution(session, List.of(half, untouched)));

        // The pair is what tells "half done" from "finished": two grades, one approved.
        assertThat(approvedCounts).containsEntry(half, 1);
        assertThat(allCounts).containsEntry(half, 2);
        // A sitting with nothing approved is ABSENT rather than present with a zero, which is
        // the same convention its sibling follows, so a caller reads both the same way.
        assertThat(approvedCounts).doesNotContainKey(untouched);
        assertThat(allCounts).containsEntry(untouched, 1);
    }

    @Test
    @DisplayName("counting approved grades for no sittings returns nothing, not every grade")
    void approvedGradesOfNothing() {
        Map<Long, Integer> none =
                inTx(session -> grades.countApprovedByExecution(session, List.of()));
        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("counting grades for no sittings returns nothing")
    void gradesOfNothing() {
        Map<Long, Integer> none =
                inTx(session -> grades.countGradesByExecution(session, List.of()));
        assertThat(none).isEmpty();
    }

    // ===================== GradeRepository.findResultRows ================

    @Test
    @DisplayName("the results table joins the grade, the attempt and the student's name")
    void resultRowsCarryEverythingTheTableShows() {
        long examId = newExam(COURSE_ALGEBRA, (byte) 1, danaId, "מבחן");
        long executionId = newExecution(versionIdOf(examId, 1), "4821",
                ExecutionStatus.CLOSED, danaId, 0);
        long attemptId = persistAttempt(executionId, mayaId);
        finalise(attemptId);
        runInTx(session -> {
            Grade grade = new Grade(attemptId, 45);
            grade.override(55, "ניתן ניקוד חלקי.");
            grade.setTeacherComment("שיפור ניכר.");
            grade.approve(danaId, OPENED);
            session.persist(grade);
        });

        List<StudentResultRow> rows =
                inTx(session -> grades.findResultRows(session, executionId));

        assertThat(rows).hasSize(1);
        StudentResultRow row = rows.get(0);
        assertThat(row.studentName()).isEqualTo("מאיה לוי");
        assertThat(row.autoScore()).as("the machine's score survives the override").isEqualTo(45);
        assertThat(row.finalScore()).isEqualTo(55);
        assertThat(row.effectiveScore()).isEqualTo(55);
        assertThat(row.wasAdjusted()).isTrue();
        assertThat(row.overrideReason()).isEqualTo("ניתן ניקוד חלקי.");
        assertThat(row.teacherComment()).isEqualTo("שיפור ניכר.");
        assertThat(row.approvedAt()).isNotNull();
        assertThat(row.actualMinutes()).isEqualTo(30);
    }

    @Test
    @DisplayName("rows are ordered by student name, so the table is stable across refreshes")
    void resultRowsAreOrderedByName() {
        long examId = newExam(COURSE_ALGEBRA, (byte) 1, danaId, "מבחן");
        long executionId = newExecution(versionIdOf(examId, 1), "4821",
                ExecutionStatus.CLOSED, danaId, 0);
        long avivId = newStudent("aviv.tal", "אביב טל", "301548204");
        long mayasAttempt = persistAttempt(executionId, mayaId);
        long avivsAttempt = persistAttempt(executionId, avivId);
        runInTx(session -> {
            session.persist(new Grade(mayasAttempt, 60));
            session.persist(new Grade(avivsAttempt, 90));
        });

        List<StudentResultRow> rows =
                inTx(session -> grades.findResultRows(session, executionId));

        assertThat(rows).extracting(StudentResultRow::studentName)
                .containsExactly("אביב טל", "מאיה לוי");
    }

    @Test
    @DisplayName("a student who sat it but has not been marked is absent, not a row of nulls")
    void unmarkedAttemptsAreAbsent() {
        long examId = newExam(COURSE_ALGEBRA, (byte) 1, danaId, "מבחן");
        long executionId = newExecution(versionIdOf(examId, 1), "4821",
                ExecutionStatus.CLOSED, danaId, 0);
        persistAttempt(executionId, mayaId);

        List<StudentResultRow> none = inTx(session -> grades.findResultRows(session, executionId));
        assertThat(none).isEmpty();
    }

    // ===================== Fixture =======================================

    private long newExam(String courseCode, byte serial, long authorId, String name) {
        return inTx(session -> {
            Exam exam = new Exam(courseCode, serial,
                    subjectOf(courseCode) + courseCode + String.format("%02d", serial), authorId);
            session.persist(exam);
            session.flush();
            ExamVersion version = new ExamVersion(exam.getId(), 1, name, 60, null, null,
                    ExamVersionStatus.APPROVED, OPENED);
            session.persist(version);
            session.flush();
            return exam.getId();
        });
    }

    private void addVersion(long examId, int versionNo, String name) {
        runInTx(session -> {
            ExamVersion version = new ExamVersion(examId, versionNo, name, 60, null, null,
                    ExamVersionStatus.APPROVED, OPENED);
            session.persist(version);
        });
    }

    private long versionIdOf(long examId, int versionNo) {
        return inTx(session -> exams.findVersion(session, examId, versionNo).orElseThrow().getId());
    }

    private long newExecution(long examVersionId, String code, ExecutionStatus status,
                              long releasedBy, int daysAfterAnchor) {
        return inTx(session -> {
            Instant opens = OPENED.plusSeconds(daysAfterAnchor * 86_400L);
            ExamExecution execution = new ExamExecution(examVersionId, code, opens,
                    opens.plusSeconds(7200), status, releasedBy);
            session.persist(execution);
            session.flush();
            return execution.getId();
        });
    }

    /** §9.1's frozen record, written the way approval completion writes it (F8.5). */
    private void freezeStatistics(long executionId) {
        runInTx(session -> {
            ExamExecution execution = session.get(ExamExecution.class, executionId);
            execution.setStats(new ExecutionStats(72.5, 72.5, 17.5, 45, 100, 0.875,
                    List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2)));
        });
    }

    private long persistAttempt(long executionId, long studentId) {
        return inTx(session -> {
            ExamAttempt attempt = new ExamAttempt(executionId, studentId, OPENED);
            session.persist(attempt);
            session.flush();
            return attempt.getId();
        });
    }

    /** Finalises the way production does: a status-guarded UPDATE, because there is no setter. */
    private void finalise(long attemptId) {
        runInTx(session -> session.createMutationQuery(
                        "update ExamAttempt set status = :status, endedAt = :endedAt,"
                                + " actualMinutes = 30 where id = :id and status = :inProgress")
                .setParameter("status", AttemptStatus.SUBMITTED)
                .setParameter("endedAt", OPENED.plusSeconds(1800))
                .setParameter("id", attemptId)
                .setParameter("inProgress", AttemptStatus.IN_PROGRESS)
                .executeUpdate());
    }

    private long newStudent(String username, String fullName, String nationalId) {
        return inTx(session -> {
            User student = new User(username, FAKE_HASH, fullName, UserRole.STUDENT, nationalId);
            session.persist(student);
            session.flush();
            return student.getId();
        });
    }

    private static String subjectOf(String courseCode) {
        return COURSE_JAVA.equals(courseCode) || COURSE_DATABASES.equals(courseCode)
                ? SUBJECT_CS : SUBJECT_MATH;
    }
}
