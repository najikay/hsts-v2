package server.features.grading;

import common.dto.grading.AnswerReviewRow;
import common.dto.grading.GradeReview;
import common.dto.grading.GradeState;
import common.dto.grading.StudentGradeRow;
import org.hibernate.Session;
import server.db.entities.Grade;
import server.db.entities.GradeStatus;
import server.db.entities.QuestionVersion;
import server.db.entities.User;
import server.db.projections.AttemptRecord;
import server.db.projections.ExecutionContext;
import server.db.repos.AttemptRepository;
import server.db.repos.ExecutionRepository;
import server.db.repos.GradeRepository;
import server.db.repos.QuestionRepository;
import server.db.repos.UserRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Assembles a marked paper (Logic tier, E12.6 — and the read half of E12.3).
 *
 * <p>Two verbs need exactly this object and they are written by the same rules, so it is built
 * once here rather than twice at the handlers. {@code GRADE_REVIEW_GET} asks for it directly;
 * {@code GRADE_OVERRIDE} answers with it refreshed, because a teacher who has just changed a
 * score should be looking at the paper as it now stands rather than at what they sent.
 * E13.4's checked form will reuse {@link #answers} under its own three conditions — the row
 * shape is shared on purpose (see {@link AnswerReviewRow}), and sharing the assembly as well
 * is what keeps that one shape honest.
 *
 * <h2>The marks come from the grader, not from a second rule</h2>
 *
 * <p>Which questions were right and what each contributed are taken from
 * {@link AutoGrader#grade}, re-run over the pinned questions and the stored answers, rather
 * than recomputed here by comparing bytes. A review that marked questions by its own rule
 * would be a second scoring implementation, and the first time the two disagreed the teacher
 * would be looking at a paper whose ticks did not add up to the score printed above them.
 * Re-running is cheap — it is arithmetic over at most a few dozen rows — and it cannot drift.
 *
 * <p>The <b>score in the header is the stored one</b> all the same. Re-running the grader
 * produces the auto score; the header carries {@code effectiveScore}, which is the teacher's
 * once they have overridden. So the ticks are recomputed and the total is remembered, which is
 * exactly the pair a teacher needs in order to see that a score was changed by hand.
 *
 * <h2>What it does not decide</h2>
 *
 * <p>Nothing about who may see it. This class assembles; the handlers gate. That split is why
 * the same method can serve a teacher reviewing a class and, later, a student reading their
 * own paper — the two callers differ in what they are allowed to ask for, not in what a marked
 * paper is.
 */
public class GradeReviewService {

    private final GradeRepository grades;
    private final AttemptRepository attempts;
    private final ExecutionRepository executions;
    private final GradingReads reads;
    private final QuestionRepository questions;
    private final UserRepository users;

    public GradeReviewService(GradeRepository grades,
                              AttemptRepository attempts,
                              ExecutionRepository executions,
                              GradingReads reads,
                              QuestionRepository questions,
                              UserRepository users) {
        this.grades = Objects.requireNonNull(grades, "grades");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.questions = Objects.requireNonNull(questions, "questions");
        this.users = Objects.requireNonNull(users, "users");
    }

    /**
     * A grade with the two rows every decision about it needs.
     *
     * <p>Resolved once and passed around, because ownership, the exam labels and the pinned
     * version all come from the execution and re-reading it per question would be three round
     * trips to answer one screen.
     *
     * @param grade     the grade itself, managed by the session
     * @param attempt   the attempt behind it
     * @param execution the execution it was sat in
     */
    public record ReviewContext(Grade grade, AttemptRecord attempt, ExecutionContext execution) {

        /**
         * @param teacherId a caller
         * @return {@code true} when this grade is theirs to read or change — the contract's rule
         *         for every teacher verb: the executing teacher, or the exam's author (S-35)
         */
        public boolean isOwnedBy(long teacherId) {
            return execution.isOwnedBy(teacherId);
        }
    }

    /**
     * Resolves a grade id to everything a grading verb needs to decide about it.
     *
     * <p>Empty for an id that does not exist <b>and</b> for one whose attempt or execution has
     * gone missing. A caller cannot tell the three apart, which is deliberate: the handlers
     * turn all of them into one {@code NOT_FOUND}.
     *
     * @param session the current session, inside a transaction
     * @param gradeId the grade
     * @return its context, or empty when it cannot be resolved
     */
    public Optional<ReviewContext> contextOf(Session session, long gradeId) {
        Objects.requireNonNull(session, "session");
        return grades.findByIdUnscoped(session, gradeId)
                .flatMap(grade -> attempts.findRecordById(session, grade.getAttemptId())
                        .flatMap(attempt -> executions.findContext(session, attempt.executionId())
                                .map(execution -> new ReviewContext(grade, attempt, execution))));
    }

    /**
     * The whole marked paper: header row plus one row per question.
     *
     * @param session the current session, inside a transaction
     * @param context the resolved grade
     * @return the review, as {@code GRADE_REVIEW_GET} and {@code GRADE_OVERRIDE} both answer it
     */
    public GradeReview review(Session session, ReviewContext context) {
        Objects.requireNonNull(context, "context");
        return new GradeReview(teacherRow(session, context), answers(session, context));
    }

    /**
     * The header row, in its teacher shape.
     *
     * <p>{@code examName} and {@code courseCode} are left null: v1.1 populates them on the
     * student paths only, because a teacher reading one execution already has them once in the
     * {@code ExecutionGradingSummary} above the table and does not need them per row.
     *
     * <p>{@code teacherName} is carried even so (A7). It costs nothing here — the execution is
     * already resolved and this class already holds the {@code UserRepository} that names the
     * student two lines below — and a row that knows who released its sitting is one fewer
     * place where the same record means different things on different paths.
     *
     * @param session the current session
     * @param context the resolved grade
     * @return the row, justification included — this is the teacher wire
     */
    public StudentGradeRow teacherRow(Session session, ReviewContext context) {
        Objects.requireNonNull(context, "context");
        Grade grade = context.grade();
        String studentName = users.findById(session, context.attempt().studentId())
                .map(User::getFullName)
                .orElse("(unknown student)");
        // A7. Empty rather than "(unknown teacher)": the student's placeholder is read by a
        // teacher looking for a missing row, and this one is read by a screen that omits the
        // line, so an absence is worth more than a word here.
        String teacherName = users.findById(session, context.execution().executingTeacherId())
                .map(User::getFullName)
                .orElse("");

        return new StudentGradeRow(
                grade.getId(),
                context.attempt().studentId(),
                studentName,
                grade.getAutoScore(),
                grade.getFinalScore(),
                grade.getEffectiveScore(),
                grade.getStatus() == GradeStatus.APPROVED ? GradeState.APPROVED : GradeState.AUTO,
                grade.getOverrideReason(),
                grade.getTeacherComment(),
                grade.getApprovedAt(),
                null,
                null,
                teacherName);
    }

    /**
     * One row per question of the paper, in exam order, with what was chosen and what was right.
     *
     * <p>Ordinals are the position in the pinned order, 1-based, assigned here so no client
     * numbers a list and no two screens number it differently.
     *
     * <p>A question whose version failed to load is skipped rather than rendered blank: the
     * link table has a RESTRICT foreign key, so this cannot happen against a consistent
     * database, and a row of empty strings would look like a question the student was asked
     * and left unanswered. {@link RepositoryGradingReads} makes the same read fail loudly on
     * the scoring path, where a missing question would change a score.
     *
     * @param session the current session
     * @param context the resolved grade
     * @return the marked questions, in exam order; empty when the exam pinned nothing
     */
    public List<AnswerReviewRow> answers(Session session, ReviewContext context) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(context, "context");

        long examVersionId = context.execution().examVersionId();
        List<AutoGrader.PinnedQuestion> pinned = reads.pinnedQuestions(session, examVersionId);
        if (pinned.isEmpty()) {
            return List.of();
        }
        Map<Long, Byte> selected = reads.selectedAnswers(session, context.attempt().attemptId());

        // The marks come from the grader itself, so the ticks on the paper and the score above
        // it can never be produced by two different rules.
        AutoGrader.Result marked = AutoGrader.grade(pinned, selected);
        Map<Long, AutoGrader.ScoredQuestion> scoredByVersion = new LinkedHashMap<>();
        for (AutoGrader.ScoredQuestion scored : marked.questions()) {
            scoredByVersion.put(scored.questionVersionId(), scored);
        }

        List<Long> versionIds = new ArrayList<>(pinned.size());
        for (AutoGrader.PinnedQuestion question : pinned) {
            versionIds.add(question.questionVersionId());
        }
        Map<Long, QuestionVersion> versions = new LinkedHashMap<>();
        List<Long> questionIds = new ArrayList<>(pinned.size());
        for (QuestionVersion version : questions.findVersionsForGrading(session, versionIds)) {
            versions.put(version.getId(), version);
            questionIds.add(version.getQuestionId());
        }
        Map<Long, String> displayIds = questions.findDisplayIds(session, questionIds);

        List<AnswerReviewRow> rows = new ArrayList<>(pinned.size());
        int ordinal = 0;
        for (AutoGrader.PinnedQuestion question : pinned) {
            ordinal++;
            QuestionVersion version = versions.get(question.questionVersionId());
            if (version == null) {
                continue;
            }
            AutoGrader.ScoredQuestion scored = scoredByVersion.get(question.questionVersionId());
            rows.add(new AnswerReviewRow(
                    ordinal,
                    displayIds.getOrDefault(version.getQuestionId(), "?????"),
                    version.getText(),
                    version.getA1(),
                    version.getA2(),
                    version.getA3(),
                    version.getA4(),
                    question.points(),
                    scored == null ? null : scored.chosen(),
                    version.getCorrectAnswer(),
                    scored != null && scored.correct(),
                    scored == null ? 0 : scored.pointsAwarded()));
        }
        return rows;
    }
}
