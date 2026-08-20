package server.features.exam;

import server.db.entities.AttemptStatus;
import server.db.entities.ExecutionStatus;
import server.db.projections.AnswerRow;
import server.db.projections.AttemptRecord;
import server.db.projections.AttemptRow;
import server.db.projections.ExecutionContext;
import server.db.projections.ParticipationCounts;
import server.db.projections.QuestionOutline;
import server.db.projections.TakeExamQuestion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * An {@link ExamStore} in a {@code HashMap}, for the unit tests of every take-exam rule.
 *
 * <p>The services hold all the rules and reach the database only through {@link ExamData},
 * so this fixture is what lets "an answer arriving after the bell is rejected", "a submit
 * racing an expiry has one winner" and "a second start resumes rather than errors" be
 * tested in milliseconds, exactly, with a clock the test moves.
 *
 * <p>It is deliberately faithful about the two things the rules depend on:
 *
 * <ul>
 *   <li><b>{@code createAttempt} enforces the unique key.</b> A second attempt for the
 *       same student and execution throws {@link DuplicateAttemptException}, which is what
 *       the real constraint does and what the double-start test needs;</li>
 *   <li><b>{@code finalizeAttempt} is a compare-and-set.</b> It changes nothing and
 *       returns zero unless the attempt is still {@code IN_PROGRESS}, which is the whole
 *       race resolution.</li>
 * </ul>
 *
 * <p>What it is <b>not</b> is a substitute for the real thing: nothing here proves the HQL
 * is right, that the constraint exists, or that MySQL orders two concurrent updates the way
 * the design assumes. {@code JpaExamStore} is driven against H2 and real MySQL for that, and
 * {@code ExamConcurrencyIntegrationTest} runs the races with a database underneath.
 */
final class InMemoryExamStore implements ExamStore {

    private final Map<Long, ExecutionContext> executions = new LinkedHashMap<>();
    private final Map<Long, StudentIdentity> users = new LinkedHashMap<>();
    private final Set<String> enrolments = new LinkedHashSet<>();
    private final Map<Long, List<TakeExamQuestion>> papers = new LinkedHashMap<>();
    private final Map<Long, AttemptRecord> attempts = new LinkedHashMap<>();
    private final Map<Long, Map<Long, Integer>> answers = new LinkedHashMap<>();
    private final AtomicLong attemptIds = new AtomicLong();

    /** Set by a test to make one operation fail, so the failure paths are reachable. */
    private RuntimeException nextFailure;

    @Override
    public <T> T inTx(Function<ExamData, T> work) {
        if (nextFailure != null) {
            RuntimeException failure = nextFailure;
            nextFailure = null;
            throw failure;
        }
        return work.apply(new Data());
    }

    // ===================== Fixture building ==============================

    ExecutionContext execution(long id, long examVersionId, String code, ExecutionStatus status,
                               Instant openAt, Instant closeAt, int durationMinutes,
                               long teacherId, String courseCode) {
        ExecutionContext ctx = new ExecutionContext(id, examVersionId, 900 + id, courseCode,
                "Java Programming", "Java Midterm", durationMinutes, "Answer every question.",
                code, status, openAt, closeAt, 0, teacherId, teacherId);
        executions.put(id, ctx);
        return ctx;
    }

    void addExecution(ExecutionContext ctx) {
        executions.put(ctx.executionId(), ctx);
    }

    void addUser(long id, String name, String nationalId) {
        users.put(id, new StudentIdentity(id, name, nationalId));
    }

    void enrol(long studentId, String courseCode) {
        enrolments.add(studentId + "@" + courseCode);
    }

    void paper(long examVersionId, int questionCount) {
        List<TakeExamQuestion> questions = new ArrayList<>();
        for (int ordinal = 1; ordinal <= questionCount; ordinal++) {
            questions.add(new TakeExamQuestion(1000L + ordinal, "2100" + ordinal, ordinal, 10,
                    "Question " + ordinal, "a", "b", "c", "d", null));
        }
        papers.put(examVersionId, questions);
    }

    /** @return the question version id of the nth question (1-based) of a paper. */
    long questionId(long examVersionId, int ordinal) {
        return papers.get(examVersionId).get(ordinal - 1).questionVersionId();
    }

    /** Makes the next {@code inTx} throw, so a caller's failure branch can be reached. */
    void failNextWith(RuntimeException failure) {
        this.nextFailure = failure;
    }

    /** @return the stored attempt, for assertions about what actually happened. */
    Optional<AttemptRecord> attempt(long attemptId) {
        return Optional.ofNullable(attempts.get(attemptId));
    }

    /** @return the answers stored for an attempt, for assertions. */
    Map<Long, Integer> answersOf(long attemptId) {
        return Map.copyOf(answers.getOrDefault(attemptId, Map.of()));
    }

    // ===================== The data seam =================================

    private final class Data implements ExamData {

        @Override
        public List<ExecutionContext> executionsByCode(String code) {
            return executions.values().stream()
                    .filter(ctx -> ctx.code().equalsIgnoreCase(code))
                    .sorted(Comparator.comparing(ExecutionContext::openAt).reversed())
                    .toList();
        }

        @Override
        public Optional<ExecutionContext> executionById(long executionId) {
            return Optional.ofNullable(executions.get(executionId));
        }

        @Override
        public Optional<StudentIdentity> user(long userId) {
            return Optional.ofNullable(users.get(userId));
        }

        @Override
        public boolean isEnrolled(long studentId, String courseCode) {
            return enrolments.contains(studentId + "@" + courseCode);
        }

        @Override
        public List<TakeExamQuestion> questionsOf(long examVersionId) {
            return List.copyOf(papers.getOrDefault(examVersionId, List.of()));
        }

        @Override
        public int questionCountOf(long examVersionId) {
            return papers.getOrDefault(examVersionId, List.of()).size();
        }

        @Override
        public List<QuestionOutline> outlineOf(long examVersionId) {
            return questionsOf(examVersionId).stream()
                    .map(question -> new QuestionOutline(question.questionVersionId(),
                            question.displayId(), question.ordinal(), question.points()))
                    .toList();
        }

        @Override
        public boolean isOnPaper(long examVersionId, long questionVersionId) {
            return questionsOf(examVersionId).stream()
                    .anyMatch(question -> question.questionVersionId() == questionVersionId);
        }

        @Override
        public Optional<AttemptRecord> attemptOf(long executionId, long studentId) {
            return attempts.values().stream()
                    .filter(attempt -> attempt.executionId() == executionId
                            && attempt.studentId() == studentId)
                    .findFirst();
        }

        @Override
        public Optional<AttemptRecord> attemptById(long attemptId) {
            return Optional.ofNullable(attempts.get(attemptId));
        }

        @Override
        public AttemptRecord createAttempt(long executionId, long studentId, Instant startedAt) {
            if (attemptOf(executionId, studentId).isPresent()) {
                // What UNIQUE(execution_id, student_id) does, thrown the way the real
                // repository throws it, which is what the double-start test is about.
                throw new DuplicateAttemptException(executionId, studentId,
                        new IllegalStateException("uq_exam_attempts_student"));
            }
            AttemptRecord attempt = new AttemptRecord(attemptIds.incrementAndGet(), executionId,
                    studentId, startedAt, null, null, AttemptStatus.IN_PROGRESS);
            attempts.put(attempt.attemptId(), attempt);
            return attempt;
        }

        @Override
        public int finalizeAttempt(long attemptId, AttemptStatus status, Instant endedAt, int actualMinutes) {
            AttemptRecord attempt = attempts.get(attemptId);
            if (attempt == null || attempt.status() != AttemptStatus.IN_PROGRESS) {
                return 0;
            }
            attempts.put(attemptId, new AttemptRecord(attemptId, attempt.executionId(),
                    attempt.studentId(), attempt.startedAt(), endedAt, actualMinutes, status));
            return 1;
        }

        @Override
        public List<AttemptRecord> liveAttemptsOf(long executionId) {
            return attempts.values().stream()
                    .filter(attempt -> attempt.executionId() == executionId && attempt.isInProgress())
                    .toList();
        }

        @Override
        public List<AttemptRecord> allLiveAttempts() {
            return attempts.values().stream().filter(AttemptRecord::isInProgress).toList();
        }

        @Override
        public List<AnswerRow> answersOf(long attemptId) {
            return answers.getOrDefault(attemptId, Map.of()).entrySet().stream()
                    .map(entry -> new AnswerRow(entry.getKey(), entry.getValue()))
                    .toList();
        }

        @Override
        public void upsertAnswer(long attemptId, long questionVersionId, Byte selected, Instant savedAt) {
            answers.computeIfAbsent(attemptId, key -> new LinkedHashMap<>())
                    .put(questionVersionId, selected == null ? null : selected.intValue());
        }

        @Override
        public int countAnswered(long attemptId) {
            return (int) answers.getOrDefault(attemptId, Map.of()).values().stream()
                    .filter(java.util.Objects::nonNull).count();
        }

        @Override
        public ParticipationCounts participationOf(long executionId) {
            long started = attempts.values().stream()
                    .filter(attempt -> attempt.executionId() == executionId).count();
            long finished = countByStatus(executionId, AttemptStatus.SUBMITTED);
            long timedOut = countByStatus(executionId, AttemptStatus.TIMED_OUT);
            return new ParticipationCounts(started, finished, timedOut);
        }

        private long countByStatus(long executionId, AttemptStatus status) {
            return attempts.values().stream()
                    .filter(attempt -> attempt.executionId() == executionId
                            && attempt.status() == status)
                    .count();
        }

        @Override
        public List<AttemptRow> rowsOf(long executionId) {
            return attempts.values().stream()
                    .filter(attempt -> attempt.executionId() == executionId)
                    .map(attempt -> new AttemptRow(attempt.attemptId(), attempt.studentId(),
                            users.containsKey(attempt.studentId())
                                    ? users.get(attempt.studentId()).fullName() : "Unknown",
                            attempt.startedAt(), attempt.endedAt(), attempt.actualMinutes(),
                            attempt.status()))
                    .sorted(Comparator.comparing(AttemptRow::studentName))
                    .toList();
        }

        @Override
        public Map<Long, Integer> answeredCountsOf(long executionId) {
            Map<Long, Integer> counts = new LinkedHashMap<>();
            for (AttemptRecord attempt : attempts.values()) {
                if (attempt.executionId() == executionId) {
                    counts.put(attempt.attemptId(), countAnswered(attempt.attemptId()));
                }
            }
            return counts;
        }

        @Override
        public int addExtraMinutes(long executionId, int minutes) {
            ExecutionContext ctx = executions.get(executionId);
            int total = ctx.extraMinutes() + minutes;
            executions.put(executionId, ctx.withExtraMinutes(total));
            return total;
        }

        @Override
        public void closeExecution(long executionId, ParticipationCounts counts) {
            ExecutionContext ctx = executions.get(executionId);
            executions.put(executionId, new ExecutionContext(ctx.executionId(), ctx.examVersionId(),
                    ctx.examId(), ctx.courseCode(), ctx.courseName(), ctx.examName(),
                    ctx.durationMinutes(), ctx.generalText(), ctx.code(), ExecutionStatus.CLOSED,
                    ctx.openAt(), ctx.closeAt(), ctx.extraMinutes(), ctx.executingTeacherId(),
                    ctx.authorId()));
        }
    }
}
