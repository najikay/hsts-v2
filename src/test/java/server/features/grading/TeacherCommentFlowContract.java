package server.features.grading;

import common.dto.grading.ApproveRequest;
import common.dto.grading.CheckedForm;
import common.dto.grading.GradeOverrideRequest;
import common.dto.grading.MyGrades;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.RepositoryTestBase;
import server.db.entities.AttemptAnswer;
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
import server.db.repos.AttemptRepository;
import server.db.repos.ExamRepository;
import server.db.repos.ExecutionRepository;
import server.db.repos.GradeRepository;
import server.db.repos.QuestionRepository;
import server.db.repos.UserRepository;
import server.features.notify.Notifier;
import server.features.results.CheckedFormService;
import server.features.results.ResultsService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance case 8.4, walked end to end against a real database (S-22/S-23, F8.3).
 *
 * <p>"Change a grade with a justification, and add a comment to the student. Then inspect the
 * stored record." Until 2026-08-23 that case could not pass: {@code teacherComment} could be
 * read on every wire and rendered on every screen, and <b>no request carried one and no service
 * wrote one</b>. The seed hid it, because a seeded comment made every screen look finished.
 *
 * <p>So this suite is deliberately not a unit test with a mocked service. Every unit test
 * involved here already passed while the feature did not exist, which is precisely the failure
 * mode; what was missing was a path, and only a walk can prove a path. It runs the four steps
 * of 8.4 through the real services against the real schema — override with a reason and a
 * comment, approve, then read the student's own checked form and her grade list — and asserts
 * both halves of the S-23 rule at the far end: <b>the comment arrives and the justification
 * does not.</b>
 *
 * <p>{@link #aSecondCorrectionDoesNotEraseWhatSheWroteToTheStudent()} is the other one worth
 * reading. It is the amendment's null-preserves rule proved through the database rather than
 * against an in-memory entity, which is where "the update statement wrote a null" would show.
 */
abstract class TeacherCommentFlowContract extends RepositoryTestBase {

    private static final Instant WHEN = Instant.parse("2026-08-23T09:00:00Z");
    private static final Instant APPROVED_AT = Instant.parse("2026-08-23T12:00:00Z");

    private static final String REASON = "Question 2 was ambiguous, partial credit given.";
    private static final String COMMENT = "שיפור ניכר מאז המבחן הקודם. שימי לב לשאלה 2.";

    private final GradeRepository grades = new GradeRepository();
    private final AttemptRepository attempts = new AttemptRepository();
    private final ExecutionRepository executions = new ExecutionRepository();
    private final ExamRepository exams = new ExamRepository();
    private final QuestionRepository questions = new QuestionRepository();
    private final UserRepository users = new UserRepository();

    /** Persisted for everyone, delivered live to nobody: this walk is not about the bell. */
    private final Notifier notifier =
            (userIds, type, title, body, ref) -> new Notifier.Outcome(
                    userIds == null ? 0 : userIds.size(), 0);

    private GradeReviewService reviews() {
        return new GradeReviewService(grades, attempts, executions,
                new RepositoryGradingReads(executions, exams, questions, attempts),
                questions, users);
    }

    private OverrideService overrides() {
        return new OverrideService(reviews());
    }

    private GradeApprovalService approvals() {
        return new GradeApprovalService(grades, attempts, executions, notifier,
                Clock.fixed(APPROVED_AT, ZoneOffset.UTC));
    }

    private CheckedFormService checkedForms() {
        return new CheckedFormService(new ResultsService(grades, users), reviews(),
                attempts, executions, users);
    }

    // ===================== 8.4, start to finish ===========================

    @Test
    @DisplayName("⚑ 8.4 — override with a reason and a comment, approve, and the student's "
            + "checked form carries the comment")
    void acceptance84WalksEndToEnd() {
        Fixture fixture = seedGradedAttempt();

        // Step one: the teacher moves the mark and writes to the student, in one act.
        OverrideService.Outcome moved = inTx(session -> overrides()
                .override(session, danaId,
                        new GradeOverrideRequest(fixture.gradeId(), 75, REASON, COMMENT))
                .outcome());
        assertThat(moved).isEqualTo(OverrideService.Outcome.OVERRIDDEN);

        // Step two: "inspect the stored record" — the auto score, the new score, the reason
        // and the comment are all on the row, which is the audit trail 8.4 asks to see.
        Grade stored = reload(fixture.gradeId());
        assertThat(stored.getAutoScore()).as("the machine's number survives the override")
                .isEqualTo(60);
        assertThat(stored.getFinalScore()).isEqualTo(75);
        assertThat(stored.getOverrideReason()).isEqualTo(REASON);
        assertThat(stored.getTeacherComment()).isEqualTo(COMMENT);

        // Step three: she approves, which is what lets the student see anything at all (C-3).
        int approved = inTx(session -> approvals()
                .approve(session, danaId, ApproveRequest.one(fixture.gradeId())).approved());
        assertThat(approved).isEqualTo(1);

        // Step four: the student opens her own marked paper.
        CheckedForm form = inTx(session ->
                checkedForms().checkedForm(session, mayaId, fixture.gradeId()))
                .orElseThrow(() -> new AssertionError("the student could not open her own form"));

        assertThat(form.grade().teacherComment())
                .as("S-22: the comment is what the student reads")
                .isEqualTo(COMMENT);
        assertThat(form.grade().overrideReason())
                .as("S-23: the justification is teacher and audit material and never travels")
                .isNull();
        assertThat(form.grade().effectiveScore()).isEqualTo(75);
        assertThat(form.grade().autoScore()).isEqualTo(60);
        assertThat(form.answers()).hasSize(2);
        // A6, through the database rather than a stub: the name is joined from the execution's
        // releasing teacher, who is the Dana who wrote the exam, released it and marked it.
        assertThat(form.teacherName())
                .as("A6: the paper says whose exam it was")
                .isEqualTo("דנה כהן");
    }

    @Test
    @DisplayName("the comment reaches her grade list too, with the justification still stripped")
    void theCommentAlsoReachesMyGrades() {
        Fixture fixture = seedGradedAttempt();
        runInTx(session -> overrides().override(session, danaId,
                new GradeOverrideRequest(fixture.gradeId(), 75, REASON, COMMENT)));
        runInTx(session -> approvals().approve(session, danaId,
                ApproveRequest.one(fixture.gradeId())));

        MyGrades mine = inTx(session -> new ResultsService(grades, users).myGrades(session, mayaId));

        assertThat(mine.grades()).hasSize(1);
        assertThat(mine.grades().get(0).teacherComment()).isEqualTo(COMMENT);
        assertThat(mine.grades().get(0).overrideReason()).isNull();
    }

    @Test
    @DisplayName("⚑ a second correction does not erase what she wrote to the student")
    void aSecondCorrectionDoesNotEraseWhatSheWroteToTheStudent() {
        // The null-preserves rule (contract A3), through the database. The dialog's comment box
        // opens empty every time, so the second override genuinely arrives with no comment —
        // and an UPDATE that set the column unconditionally would blank it here.
        Fixture fixture = seedGradedAttempt();
        runInTx(session -> overrides().override(session, danaId,
                new GradeOverrideRequest(fixture.gradeId(), 75, REASON, COMMENT)));

        runInTx(session -> overrides().override(session, danaId,
                new GradeOverrideRequest(fixture.gradeId(), 80,
                        "Re-read question 4 after the moderation meeting.")));

        Grade stored = reload(fixture.gradeId());
        assertThat(stored.getFinalScore()).as("the second correction still moved the mark")
                .isEqualTo(80);
        assertThat(stored.getOverrideReason())
                .as("the reason is replaced, because a reason describes this change")
                .isEqualTo("Re-read question 4 after the moderation meeting.");
        assertThat(stored.getTeacherComment())
                .as("the comment is kept, because nothing said to remove it")
                .isEqualTo(COMMENT);
    }

    @Test
    @DisplayName("a comment cannot be added after approval: it is refused with the override")
    void aCommentAfterApprovalIsRefusedWithTheOverride() {
        // Riding the override means inheriting its gates. Once a grade is published the paper
        // is closed, comment included — the alternative is changing what a student has already
        // been shown through a door the score change itself is locked out of.
        Fixture fixture = seedGradedAttempt();
        runInTx(session -> approvals().approve(session, danaId,
                ApproveRequest.one(fixture.gradeId())));

        OverrideService.Outcome outcome = inTx(session -> overrides()
                .override(session, danaId,
                        new GradeOverrideRequest(fixture.gradeId(), 75, REASON, COMMENT))
                .outcome());

        assertThat(outcome).isEqualTo(OverrideService.Outcome.ALREADY_APPROVED);
        assertThat(reload(fixture.gradeId()).getTeacherComment()).isNull();
        assertThat(reload(fixture.gradeId()).getEffectiveScore()).isEqualTo(60);
    }

    @Test
    @DisplayName("another teacher's comment never lands, and the student never sees one")
    void anotherTeachersCommentNeverLands() {
        Fixture fixture = seedGradedAttempt();

        OverrideService.Outcome outcome = inTx(session -> overrides()
                .override(session, rinaId,
                        new GradeOverrideRequest(fixture.gradeId(), 75, REASON, COMMENT))
                .outcome());

        assertThat(outcome).isEqualTo(OverrideService.Outcome.NOT_FOUND);
        assertThat(reload(fixture.gradeId()).getTeacherComment()).isNull();
    }

    // ===================== Fixture ========================================

    /** One graded, unapproved attempt: Maya sat Dana's closed Algebra sitting and scored 60. */
    private record Fixture(long executionId, long attemptId, long gradeId) {
    }

    private Fixture seedGradedAttempt() {
        long examVersionId = newExamVersion();
        long firstQuestion = newQuestion(1);
        long secondQuestion = newQuestion(2);
        long firstVersion = newQuestionVersion(firstQuestion, (byte) 2);
        long secondVersion = newQuestionVersion(secondQuestion, (byte) 4);
        pin(examVersionId, firstVersion, firstQuestion, 60, 1);
        pin(examVersionId, secondVersion, secondQuestion, 40, 2);

        long executionId = newClosedExecution(examVersionId);
        long attemptId = newSubmittedAttempt(executionId);
        // Right on the first (60), wrong on the second: the machine's 60 is a real score, and
        // the override has something to move away from.
        runInTx(session -> {
            session.persist(new AttemptAnswer(attemptId, firstVersion, (byte) 2, WHEN));
            session.persist(new AttemptAnswer(attemptId, secondVersion, (byte) 1, WHEN));
        });

        long gradeId = inTx(session -> {
            Grade grade = new Grade(attemptId, 60);
            session.persist(grade);
            session.flush();
            return grade.getId();
        });
        return new Fixture(executionId, attemptId, gradeId);
    }

    private Grade reload(long gradeId) {
        return inTx(session -> {
            // Cleared first, so this is a read of the row rather than of an entity the write
            // left in a persistence context.
            session.clear();
            return Optional.ofNullable(session.get(Grade.class, gradeId))
                    .orElseThrow(() -> new AssertionError("grade " + gradeId + " vanished"));
        });
    }

    private long newExamVersion() {
        return inTx(session -> {
            Exam exam = new Exam(COURSE_ALGEBRA, (byte) 1, SUBJECT_MATH + COURSE_ALGEBRA + "01",
                    danaId);
            session.persist(exam);
            session.flush();
            ExamVersion version = new ExamVersion(exam.getId(), 1, "מבחן אמצע", 60, null, null,
                    ExamVersionStatus.APPROVED, WHEN);
            session.persist(version);
            session.flush();
            return version.getId();
        });
    }

    private long newClosedExecution(long examVersionId) {
        return inTx(session -> {
            ExamExecution execution = new ExamExecution(examVersionId, "4821", WHEN,
                    WHEN.plusSeconds(3600), ExecutionStatus.CLOSED, danaId);
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

    private long newQuestionVersion(long questionId, byte correctAnswer) {
        return inTx(session -> {
            QuestionVersion version = new QuestionVersion(questionId, 1, "שאלה",
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

    /** Finalised the way production does: a status-guarded UPDATE, because there is no setter. */
    private long newSubmittedAttempt(long executionId) {
        long attemptId = inTx(session -> {
            ExamAttempt attempt = new ExamAttempt(executionId, mayaId, WHEN);
            session.persist(attempt);
            session.flush();
            return attempt.getId();
        });
        runInTx(session -> session.createMutationQuery(
                        "update ExamAttempt set status = :status, endedAt = :endedAt,"
                                + " actualMinutes = 42 where id = :id and status = :inProgress")
                .setParameter("status", AttemptStatus.SUBMITTED)
                .setParameter("endedAt", WHEN.plusSeconds(2520))
                .setParameter("id", attemptId)
                .setParameter("inProgress", AttemptStatus.IN_PROGRESS)
                .executeUpdate());
        return attemptId;
    }
}
