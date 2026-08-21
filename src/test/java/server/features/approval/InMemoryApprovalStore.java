package server.features.approval;

import common.dto.approval.PreviewAnswerRow;
import org.hibernate.StaleStateException;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionStatus;
import server.db.projections.ExamVersionContext;
import server.db.projections.TakeExamQuestion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * An {@link ApprovalStore} in a map, for the E8 rule tests.
 *
 * <p>Holds real {@link ExamVersion} entities so the service exercises the real
 * {@code approve()} and {@code reject()} transitions rather than a test-only stand-in, and
 * keeps everything the persistence layer would supply — the generated id, the join to the
 * exam and course, and {@code lock_version} — beside them in {@link Meta}. That split is what
 * lets the fake be honest about the one thing these tests are really about: the optimistic
 * lock is bumped on {@link #flush()}, exactly where Hibernate would bump it.
 *
 * <p>{@link #failNextFlush()} is how the genuinely concurrent case is reached. The
 * service's own {@code lockVersion} check catches a <em>stale screen</em>; the flush failure
 * is the other race, where two writers both read the same value and one of them gets there
 * first. Both end in {@code CONFLICT} and both need a test, and only this one needs a fake
 * that can misbehave on demand.
 */
final class InMemoryApprovalStore implements ApprovalStore, ApprovalData {

    /** Everything about a version that lives outside the entity. */
    record Meta(long examId, String examDisplayId, String courseCode, String courseName,
                String subjectCode, long authorId, String authorName, int lockVersion,
                List<TakeExamQuestion> questions, List<PreviewAnswerRow> answerKey) {

        Meta withLockVersion(int next) {
            return new Meta(examId, examDisplayId, courseCode, courseName, subjectCode,
                    authorId, authorName, next, questions, answerKey);
        }
    }

    private final Map<Long, ExamVersion> versions = new LinkedHashMap<>();
    private final Map<Long, Meta> meta = new LinkedHashMap<>();
    private final Map<String, Long> coordinators = new LinkedHashMap<>();
    private final Set<Long> dirty = new LinkedHashSet<>();

    private boolean failNextFlush;

    // ===================== Fixture building ==============================

    /** Registers who coordinates a subject. */
    InMemoryApprovalStore coordinator(String subjectCode, long teacherId) {
        coordinators.put(subjectCode, teacherId);
        return this;
    }

    /** Adds one version with its exam, course and author, and returns the store. */
    @SuppressWarnings("checkstyle:ParameterNumber")
    InMemoryApprovalStore version(long versionId, long examId, String displayId, String examName,
                                  int versionNo, ExamVersionStatus status, String courseCode,
                                  String courseName, String subjectCode, long authorId,
                                  String authorName, Instant createdAt) {
        ExamVersion version = new ExamVersion(examId, versionNo, examName, 60,
                "ענו על כל השאלות.", "For the marker only.", status, createdAt);
        versions.put(versionId, version);
        meta.put(versionId, new Meta(examId, displayId, courseCode, courseName, subjectCode,
                authorId, authorName, 0, List.of(), List.of()));
        return this;
    }

    /** Gives a version a paper and its answer key. */
    InMemoryApprovalStore paper(long versionId, List<TakeExamQuestion> questions,
                                List<PreviewAnswerRow> answerKey) {
        Meta current = meta.get(versionId);
        meta.put(versionId, new Meta(current.examId(), current.examDisplayId(),
                current.courseCode(), current.courseName(), current.subjectCode(),
                current.authorId(), current.authorName(), current.lockVersion(),
                List.copyOf(questions), List.copyOf(answerKey)));
        return this;
    }

    /** Bumps a version's lock, as a competing writer would. */
    void bumpLock(long versionId) {
        meta.computeIfPresent(versionId, (id, m) -> m.withLockVersion(m.lockVersion() + 1));
    }

    /** Makes the next {@link #flush()} lose an optimistic-lock race. */
    void failNextFlush() {
        this.failNextFlush = true;
    }

    /** @return the stored status of a version, for assertions. */
    ExamVersionStatus statusOf(long versionId) {
        return versions.get(versionId).getStatus();
    }

    /** @return the stored rejection reason of a version, for assertions. */
    String reasonOf(long versionId) {
        return versions.get(versionId).getRejectedReason();
    }

    /** @return the current optimistic-lock value, for assertions. */
    int lockOf(long versionId) {
        return meta.get(versionId).lockVersion();
    }

    // ===================== ApprovalStore =================================

    @Override
    public <T> T inTx(Function<ApprovalData, T> work) {
        dirty.clear();
        return work.apply(this);
    }

    // ===================== ApprovalData ==================================

    @Override
    public Optional<ExamVersionContext> versionContext(long examVersionId) {
        ExamVersion version = versions.get(examVersionId);
        return version == null ? Optional.empty() : Optional.of(contextOf(examVersionId, version));
    }

    @Override
    public List<ExamVersionContext> pendingFor(long coordinatorId) {
        // Scoped exactly as the real query is: joined on the coordinators table, so a
        // version outside her subjects is never in the answer to be filtered out later.
        List<ExamVersionContext> found = new ArrayList<>();
        versions.forEach((id, version) -> {
            Meta m = meta.get(id);
            if (version.getStatus() == ExamVersionStatus.PENDING
                    && Long.valueOf(coordinatorId).equals(coordinators.get(m.subjectCode()))) {
                found.add(contextOf(id, version));
            }
        });
        found.sort(java.util.Comparator.comparing(ExamVersionContext::createdAt)
                .thenComparingLong(ExamVersionContext::examVersionId));
        return List.copyOf(found);
    }

    @Override
    public List<ExamVersionContext> submittedByAuthor(long authorId) {
        List<ExamVersionContext> found = new ArrayList<>();
        versions.forEach((id, version) -> {
            if (meta.get(id).authorId() == authorId && version.getStatus() != ExamVersionStatus.DRAFT) {
                found.add(contextOf(id, version));
            }
        });
        found.sort(java.util.Comparator.comparing(ExamVersionContext::createdAt).reversed());
        return List.copyOf(found);
    }

    @Override
    public List<String> coordinatedSubjects(long teacherId) {
        return coordinators.entrySet().stream()
                .filter(entry -> entry.getValue() == teacherId)
                .map(Map.Entry::getKey)
                .toList();
    }

    @Override
    public boolean coordinates(long teacherId, String subjectCode) {
        return Long.valueOf(teacherId).equals(coordinators.get(subjectCode));
    }

    @Override
    public Optional<Long> coordinatorOf(String subjectCode) {
        return Optional.ofNullable(coordinators.get(subjectCode));
    }

    @Override
    public List<TakeExamQuestion> questionsOf(long examVersionId) {
        return meta.get(examVersionId).questions();
    }

    @Override
    public List<PreviewAnswerRow> answerKeyOf(long examVersionId) {
        return meta.get(examVersionId).answerKey();
    }

    @Override
    public Map<Long, Integer> questionCounts(List<Long> examVersionIds) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Long id : examVersionIds) {
            int size = meta.get(id).questions().size();
            if (size > 0) {
                // Absent rather than zero, exactly as a `group by` answers.
                counts.put(id, size);
            }
        }
        return counts;
    }

    @Override
    public Optional<ExamVersion> versionForUpdate(long examVersionId) {
        ExamVersion version = versions.get(examVersionId);
        if (version != null) {
            dirty.add(examVersionId);
        }
        return Optional.ofNullable(version);
    }

    @Override
    public void flush() {
        if (failNextFlush) {
            failNextFlush = false;
            throw new StaleStateException("simulated optimistic-lock loss");
        }
        // @Version is bumped by the flush that writes the row, so the fake bumps here too:
        // a service that read lock_version back before flushing would otherwise pass a test
        // it should not.
        new HashSet<>(dirty).forEach(this::bumpLock);
        dirty.clear();
    }

    @Override
    public int supersedePending(long examId, long keepVersionId, String reason) {
        int count = 0;
        for (Map.Entry<Long, ExamVersion> entry : versions.entrySet()) {
            Meta m = meta.get(entry.getKey());
            if (m.examId() == examId && entry.getKey() != keepVersionId
                    && entry.getValue().getStatus() == ExamVersionStatus.PENDING) {
                entry.getValue().reject(reason);
                // A bulk update does not go through @Version, and the real query does not
                // either. Not bumping here is what makes the "superseded then approved"
                // race land on the status guard rather than on the lock check.
                count++;
            }
        }
        return count;
    }

    private ExamVersionContext contextOf(long versionId, ExamVersion version) {
        Meta m = meta.get(versionId);
        return new ExamVersionContext(versionId, m.examId(), m.examDisplayId(), version.getName(),
                version.getVersionNo(), version.getDurationMinutes(), version.getStudentText(),
                version.getTeacherText(), version.getStatus(), version.getRejectedReason(),
                version.getCreatedAt(), m.lockVersion(), m.courseCode(), m.courseName(),
                m.subjectCode(), m.authorId(), m.authorName());
    }
}
