package server.features.results;

import server.db.entities.ExecutionStats;
import server.db.entities.ExecutionStatus;
import server.db.projections.AuthoredExam;
import server.db.projections.ExecutionContext;
import server.db.projections.StudentResultRow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * A {@link TeacherResultsStore} in a {@code HashMap}, for the unit tests of every E14 rule.
 *
 * <p>{@link TeacherResultsService} holds all the rules and reaches the database only through
 * {@link TeacherResultsData}, so this fixture is what lets the S-35 scoping — the
 * defence-critical one — be proven in milliseconds and without a schema.
 *
 * <p>It is deliberately faithful about the three things the rules depend on:
 *
 * <ul>
 *   <li><b>{@code examsAuthoredBy} filters on the author</b>, exactly as the SQL does, so a
 *       test that broke the scoping here would not accidentally pass;</li>
 *   <li><b>{@code executionsOfExamsAuthoredBy} excludes cancelled sittings</b> and orders them
 *       newest first, which is what the query promises and what the picker's default depends
 *       on;</li>
 *   <li><b>the count maps omit zeros.</b> The real grouped queries return no row for an
 *       execution nobody sat, and a fixture that helpfully inserted a zero would hide a
 *       {@code getOrDefault} that was never written.</li>
 * </ul>
 */
final class InMemoryTeacherResultsStore implements TeacherResultsStore, TeacherResultsData {

    private final Map<Long, AuthoredExam> exams = new LinkedHashMap<>();
    private final Map<Long, Long> examAuthors = new LinkedHashMap<>();
    private final Map<Long, ExecutionContext> executions = new LinkedHashMap<>();
    private final Map<Long, Integer> participants = new LinkedHashMap<>();
    private final Map<Long, ExecutionStats> statistics = new LinkedHashMap<>();
    private final Map<Long, List<StudentResultRow>> rows = new LinkedHashMap<>();

    // ===================== Building the world ============================

    /** Adds an exam and records who wrote it. */
    InMemoryTeacherResultsStore exam(long examId, String displayId, String courseCode,
                                     String courseName, String examName, long authorId) {
        exams.put(examId, new AuthoredExam(examId, displayId, courseCode, courseName, examName));
        examAuthors.put(examId, authorId);
        return this;
    }

    /**
     * Adds one sitting of an exam.
     *
     * @param releasedBy the teacher who released it, which is <b>not</b> necessarily the
     *                   exam's author — that difference is the whole of S-35
     */
    InMemoryTeacherResultsStore execution(long executionId, long examId, String code,
                                          ExecutionStatus status, Instant openAt, Instant closeAt,
                                          long releasedBy) {
        AuthoredExam exam = exams.get(examId);
        if (exam == null) {
            throw new IllegalStateException("Seed the exam before its executions: " + examId);
        }
        executions.put(executionId, new ExecutionContext(executionId, executionId * 100, examId,
                exam.courseCode(), exam.courseName(), exam.examName(), 60, null, code, status,
                openAt, closeAt, 0, releasedBy, examAuthors.get(examId)));
        return this;
    }

    /** How many students sat it. Absent executions count zero, as the real query does. */
    InMemoryTeacherResultsStore participants(long executionId, int count) {
        participants.put(executionId, count);
        return this;
    }

    /** Freezes statistics on an execution, exactly as approval completion does (F8.5). */
    InMemoryTeacherResultsStore statistics(long executionId, ExecutionStats stats) {
        statistics.put(executionId, stats);
        return this;
    }

    /** Adds one marked student to an execution. */
    InMemoryTeacherResultsStore row(long executionId, StudentResultRow row) {
        rows.computeIfAbsent(executionId, key -> new ArrayList<>()).add(row);
        return this;
    }

    // ===================== The seam ======================================

    @Override
    public <T> T inTx(Function<TeacherResultsData, T> work) {
        return work.apply(this);
    }

    @Override
    public List<AuthoredExam> examsAuthoredBy(long teacherId) {
        List<AuthoredExam> mine = new ArrayList<>();
        for (AuthoredExam exam : exams.values()) {
            if (examAuthors.get(exam.examId()) == teacherId) {
                mine.add(exam);
            }
        }
        mine.sort(Comparator.comparing(AuthoredExam::displayId));
        return mine;
    }

    @Override
    public List<ExecutionContext> executionsOfExamsAuthoredBy(long teacherId) {
        List<ExecutionContext> mine = new ArrayList<>();
        for (ExecutionContext execution : executions.values()) {
            if (execution.authorId() == teacherId
                    && execution.status() != ExecutionStatus.CANCELLED) {
                mine.add(execution);
            }
        }
        mine.sort(Comparator.comparing(ExecutionContext::openAt).reversed());
        return mine;
    }

    @Override
    public Optional<ExecutionContext> executionById(long executionId) {
        return Optional.ofNullable(executions.get(executionId));
    }

    @Override
    public Set<Long> executionsWithStatistics(Collection<Long> executionIds) {
        Set<Long> found = new LinkedHashSet<>();
        for (Long id : executionIds) {
            if (statistics.containsKey(id)) {
                found.add(id);
            }
        }
        return found;
    }

    @Override
    public Map<Long, Integer> participantsByExecution(Collection<Long> executionIds) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Long id : executionIds) {
            Integer count = participants.get(id);
            if (count != null && count > 0) {
                counts.put(id, count);
            }
        }
        return counts;
    }

    @Override
    public Map<Long, Integer> gradedByExecution(Collection<Long> executionIds) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Long id : executionIds) {
            List<StudentResultRow> marked = rows.get(id);
            if (marked != null && !marked.isEmpty()) {
                counts.put(id, marked.size());
            }
        }
        return counts;
    }

    @Override
    public Optional<ExecutionStats> statisticsOf(long executionId) {
        return Optional.ofNullable(statistics.get(executionId));
    }

    @Override
    public List<StudentResultRow> resultRowsOf(long executionId) {
        return List.copyOf(rows.getOrDefault(executionId, List.of()));
    }
}
