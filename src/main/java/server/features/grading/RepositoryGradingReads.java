package server.features.grading;

import org.hibernate.Session;
import server.db.entities.ExamExecution;
import server.db.entities.ExamVersionQuestion;
import server.db.entities.QuestionVersion;
import server.db.projections.AnswerRow;
import server.db.repos.AttemptRepository;
import server.db.repos.ExamRepository;
import server.db.repos.ExecutionRepository;
import server.db.repos.QuestionRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The repository-backed {@link GradingReads} (Logic tier, E12.1).
 *
 * <p>All this class does is fetch and pair. Every rule about what a score means lives in
 * {@link AutoGrader}, and every rule about which attempts are gradeable lives in
 * {@link GradingService} — so the piece that touches the database has no decisions in it, and
 * the pieces that have decisions never touch a database.
 *
 * <p><b>The answer key is fetched here and consumed by the grader.</b> It never leaves the
 * server: {@link AutoGrader.Result} carries scores and which questions were right, not what the
 * right answers were. The read that supplies it is
 * {@code QuestionRepository#findVersionsForGrading}, whose {@code ForGrading} suffix is the
 * third sanctioned audience in {@code CorrectnessLeakGuardTest}.
 */
public class RepositoryGradingReads implements GradingReads {

    private final ExecutionRepository executions;
    private final ExamRepository exams;
    private final QuestionRepository questions;
    private final AttemptRepository attempts;

    public RepositoryGradingReads(ExecutionRepository executions,
                                  ExamRepository exams,
                                  QuestionRepository questions,
                                  AttemptRepository attempts) {
        this.executions = Objects.requireNonNull(executions, "executions");
        this.exams = Objects.requireNonNull(exams, "exams");
        this.questions = Objects.requireNonNull(questions, "questions");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
    }

    @Override
    public List<AutoGrader.PinnedQuestion> pinnedQuestions(Session session, long examVersionId) {
        List<ExamVersionQuestion> pinned = exams.findPinnedQuestions(session, examVersionId);
        if (pinned.isEmpty()) {
            return List.of();
        }

        List<Long> versionIds = new ArrayList<>(pinned.size());
        for (ExamVersionQuestion question : pinned) {
            versionIds.add(question.getQuestionVersionId());
        }

        Map<Long, Byte> correctByVersion = new HashMap<>();
        for (QuestionVersion version : questions.findVersionsForGrading(session, versionIds)) {
            correctByVersion.put(version.getId(), version.getCorrectAnswer());
        }

        List<AutoGrader.PinnedQuestion> result = new ArrayList<>(pinned.size());
        for (ExamVersionQuestion question : pinned) {
            long versionId = question.getQuestionVersionId();
            Byte correct = correctByVersion.get(versionId);
            if (correct == null) {
                // The link table has a RESTRICT foreign key to question_versions, so a pinned
                // row without its version means the two reads disagreed — a torn read, not a
                // gradeable exam. Scoring on what did load would silently mark the missing
                // question wrong for every student.
                throw new IllegalStateException(
                        "Exam version " + examVersionId + " pins question version " + versionId
                                + ", which did not load — refusing to grade a partial exam");
            }
            result.add(new AutoGrader.PinnedQuestion(versionId, question.getPoints(), correct));
        }
        return result;
    }

    @Override
    public Map<Long, Byte> selectedAnswers(Session session, long attemptId) {
        Map<Long, Byte> selected = new LinkedHashMap<>();
        for (AnswerRow answer : attempts.findAnswers(session, attemptId)) {
            // An unanswered question is absent from the map, never present with a null — the
            // grader treats a missing key as unanswered and there must be only one way to say it.
            if (answer.isAnswered()) {
                // AnswerRow carries Integer because that is what the take-exam path wires;
                // a selection is 1..4 (C-8), so narrowing to the grader's byte is lossless.
                selected.put(answer.questionVersionId(), answer.selected().byteValue());
            }
        }
        return selected;
    }

    @Override
    public long examVersionOf(Session session, long executionId) {
        return executions.findById(session, executionId)
                .map(ExamExecution::getExamVersionId)
                .orElseThrow(() -> new IllegalStateException(
                        "Execution " + executionId + " does not exist"));
    }
}
