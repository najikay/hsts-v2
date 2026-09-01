package server.features.release;

import server.db.entities.ExamVersionStatus;
import server.db.entities.ExecutionStatus;
import server.db.projections.ExamVersionContext;
import server.db.projections.ExecutionContext;
import server.db.projections.ParticipationCounts;

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
 * The release manager's data seam, in memory (E9 test fixture).
 *
 * <p>Sibling of {@code InMemoryExamStore}, and it exists for the same reason: every rule in
 * {@link ReleaseService} and {@link ReleaseScheduler} is about <em>decisions</em> — is this
 * approved, is it hers, is this code free, has this window begun — and none of them is about
 * SQL. Proving them against a map is exact and instant; the SQL half is proven separately by
 * {@code JpaReleaseStoreContract} on both engines, which is where a wrong {@code where}
 * clause can actually fail.
 *
 * <p>Deliberately not a mock. These rules are read-decide-write sequences over several
 * entities, and a Mockito script for them would be longer than the fixture and would assert
 * the implementation's call order rather than its behaviour.
 */
final class InMemoryReleaseStore implements ReleaseStore, ReleaseData {

    private final Map<Long, ExamVersionContext> versions = new LinkedHashMap<>();
    private final Map<Long, ExecutionContext> executions = new LinkedHashMap<>();
    private final Map<Long, Set<String>> taught = new LinkedHashMap<>();
    private final Map<String, List<Long>> enrolled = new LinkedHashMap<>();
    private final Map<Long, ParticipationCounts> participation = new LinkedHashMap<>();
    private final Map<Long, Integer> questionCounts = new LinkedHashMap<>();

    private long nextExecutionId = 5000;

    /** How many transactions have been opened, so a test can assert reads are batched. */
    int transactions;

    @Override
    public <T> T inTx(Function<ReleaseData, T> work) {
        transactions++;
        return work.apply(this);
    }

    // ===================== Fixture builders ==============================

    /**
     * Records that this teacher teaches these courses.
     *
     * <p>Not called {@code teaches}: {@link ReleaseData#teaches(long, String)} already is,
     * and the two would be an overload pair whose resolution depends on how many course
     * codes a test happens to pass.
     */
    InMemoryReleaseStore withTeacher(long teacherId, String... courseCodes) {
        taught.computeIfAbsent(teacherId, key -> new LinkedHashSet<>()).addAll(List.of(courseCodes));
        return this;
    }

    /** Records these students as enrolled in this course. */
    InMemoryReleaseStore enrols(String courseCode, Long... studentIds) {
        enrolled.computeIfAbsent(courseCode, key -> new ArrayList<>()).addAll(List.of(studentIds));
        return this;
    }

    /** Adds an exam version in the given state. */
    InMemoryReleaseStore version(long examVersionId, long examId, String courseCode,
                                 ExamVersionStatus status, long authorId) {
        versions.put(examVersionId, new ExamVersionContext(examVersionId, examId,
                "10" + courseCode + "01", "מבחן " + examVersionId, 1, 45, null, null,
                status, null, Instant.parse("2026-08-01T00:00:00Z"), 0,
                courseCode, "Course " + courseCode, "10", authorId, "Author"));
        return this;
    }

    /** Adds a release, and returns its id. */
    long execution(String code, ExecutionStatus status, Instant openAt, Instant closeAt,
                   long createdBy, long examVersionId) {
        long executionId = ++nextExecutionId;
        ExamVersionContext version = versions.get(examVersionId);
        executions.put(executionId, new ExecutionContext(executionId, examVersionId,
                version == null ? 0 : version.examId(),
                version == null ? "11" : version.courseCode(),
                version == null ? "Course" : version.courseName(),
                version == null ? "Exam" : version.examName(),
                45, null, code, status, openAt, closeAt, 0, createdBy,
                version == null ? createdBy : version.authorId()));
        return executionId;
    }

    /** Sets the participation a release reports. */
    InMemoryReleaseStore counts(long executionId, long started, long finished, long timedOut) {
        participation.put(executionId, new ParticipationCounts(started, finished, timedOut));
        return this;
    }

    /** Grants extra minutes, so the effective close moves (S-20). */
    void extend(long executionId, int minutes) {
        executions.computeIfPresent(executionId,
                (id, context) -> context.withExtraMinutes(context.extraMinutes() + minutes));
    }

    /** @return the stored status, for assertions that a transition really happened. */
    ExecutionStatus statusOf(long executionId) {
        ExecutionContext context = executions.get(executionId);
        return context == null ? null : context.status();
    }

    /** @return the stored code, for the uniqueness assertions. */
    String codeOf(long executionId) {
        ExecutionContext context = executions.get(executionId);
        return context == null ? null : context.code();
    }

    /** @return how many releases exist, so a refused create can be proved to have written none. */
    int executionCount() {
        return executions.size();
    }

    /** Closes a release the way {@code ExecutionCloseService} would, for the close-early test. */
    void markClosed(long executionId) {
        replaceStatus(executionId, ExecutionStatus.CLOSED);
    }

    // ===================== ReleaseData ===================================

    @Override
    public Map<Long, Integer> questionCountsByVersion(Collection<Long> versionIds) {
        Map<Long, Integer> found = new LinkedHashMap<>();
        for (Long id : versionIds) {
            Integer count = questionCounts.get(id);
            if (count != null) {
                found.put(id, count);
            }
        }
        return found;
    }

    /** Fixture: how many questions a version carries (U-93). */
    void questionCount(long versionId, int count) {
        questionCounts.put(versionId, count);
    }

    public List<ExamVersionContext> releasableVersionsFor(long teacherId) {
        Set<String> courses = taught.getOrDefault(teacherId, Set.of());
        return versions.values().stream()
                .filter(version -> version.status() == ExamVersionStatus.APPROVED)
                .filter(version -> courses.contains(version.courseCode()))
                .sorted(Comparator.comparingLong(ExamVersionContext::examVersionId).reversed())
                .toList();
    }

    @Override
    public boolean hasAnyExam(long teacherId) {
        Set<String> courses = taught.getOrDefault(teacherId, Set.of());
        return versions.values().stream().anyMatch(v -> courses.contains(v.courseCode()));
    }

    @Override
    public Optional<ExamVersionContext> versionById(long examVersionId) {
        return Optional.ofNullable(versions.get(examVersionId));
    }

    @Override
    public boolean teaches(long teacherId, String courseCode) {
        return taught.getOrDefault(teacherId, Set.of()).contains(courseCode);
    }

    @Override
    public boolean isCodeInUse(String code) {
        return executions.values().stream()
                .filter(context -> context.status() == ExecutionStatus.SCHEDULED
                        || context.status() == ExecutionStatus.LIVE)
                .anyMatch(context -> context.code().equalsIgnoreCase(code));
    }

    @Override
    public long createExecution(long examVersionId, String code, Instant openAt,
                                Instant closeAt, long createdBy) {
        return execution(code, ExecutionStatus.SCHEDULED, openAt, closeAt, createdBy, examVersionId);
    }

    @Override
    public Optional<ExecutionContext> executionById(long executionId) {
        return Optional.ofNullable(executions.get(executionId));
    }

    @Override
    public List<ExecutionContext> executionsFor(long teacherId) {
        return executions.values().stream()
                .filter(context -> context.isOwnedBy(teacherId))
                .sorted(Comparator.comparing(ExecutionContext::openAt).reversed())
                .toList();
    }

    @Override
    public Map<Long, ParticipationCounts> participationOf(Collection<Long> executionIds) {
        Map<Long, ParticipationCounts> found = new LinkedHashMap<>();
        for (Long executionId : executionIds) {
            ParticipationCounts counts = participation.get(executionId);
            if (counts != null) {
                // Absent rather than zero, exactly as the repository answers.
                found.put(executionId, counts);
            }
        }
        return found;
    }

    @Override
    public List<ExecutionContext> scheduledOpeningBy(Instant limit) {
        return executions.values().stream()
                .filter(context -> context.status() == ExecutionStatus.SCHEDULED)
                .filter(context -> !context.openAt().isAfter(limit))
                .sorted(Comparator.comparing(ExecutionContext::openAt))
                .toList();
    }

    @Override
    public List<ExecutionContext> liveClosingBy(Instant limit) {
        return executions.values().stream()
                .filter(context -> context.status() == ExecutionStatus.LIVE)
                .filter(context -> !context.closeAt().isAfter(limit))
                .sorted(Comparator.comparing(ExecutionContext::closeAt))
                .toList();
    }

    @Override
    public int transition(long executionId, ExecutionStatus from, ExecutionStatus to) {
        ExecutionContext context = executions.get(executionId);
        if (context == null || context.status() != from) {
            return 0;
        }
        replaceStatus(executionId, to);
        return 1;
    }

    @Override
    public List<Long> enrolledStudents(String courseCode) {
        return List.copyOf(enrolled.getOrDefault(courseCode, List.of()));
    }

    private void replaceStatus(long executionId, ExecutionStatus status) {
        ExecutionContext old = executions.get(executionId);
        if (old == null) {
            return;
        }
        executions.put(executionId, new ExecutionContext(old.executionId(), old.examVersionId(),
                old.examId(), old.courseCode(), old.courseName(), old.examName(),
                old.durationMinutes(), old.generalText(), old.code(), status,
                old.openAt(), old.closeAt(), old.extraMinutes(),
                old.executingTeacherId(), old.authorId()));
    }
}
