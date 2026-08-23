package server.features.release;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.RepositoryTestBase;
import server.db.entities.AttemptStatus;
import server.db.entities.Enrollment;
import server.db.entities.Exam;
import server.db.entities.ExamAttempt;
import server.db.entities.ExamExecution;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionStatus;
import server.db.entities.ExecutionStatus;
import server.db.projections.ExamVersionContext;
import server.db.projections.ExecutionContext;
import server.db.projections.ParticipationCounts;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JpaReleaseStore} driven through the seam the release manager actually uses (E9).
 *
 * <p>{@code ReleaseServiceTest} proves every rule against an in-memory double, exactly and
 * instantly. What it cannot prove is that the rules survive the database, and two of E9's
 * are entirely a matter of SQL:
 *
 * <ul>
 *   <li><b>code uniqueness is partial.</b> There is no constraint behind it (§5: MySQL has no
 *       partial unique index), so "is this code in use" is a {@code where} clause and nothing
 *       else. A clause that forgot the status filter would reserve every code ever issued,
 *       and every unit test would still pass;</li>
 *   <li><b>the status transition is a compare-and-set.</b> A read-modify-write would look
 *       identical from a service's point of view and would let the scheduled check reopen a
 *       release a teacher cancelled a second earlier.</li>
 * </ul>
 *
 * <p>Run on both engines. H2 is the fast pass over the shapes; MySQL is the configuration the
 * server actually runs, where the collation makes the case-insensitive code comparison real.
 */
abstract class JpaReleaseStoreContract extends RepositoryTestBase {

    private static final Instant WHEN = Instant.parse("2026-08-20T09:00:00Z");

    private ReleaseStore store;

    /** @return the seam under test, built once per test class. */
    protected ReleaseStore store() {
        if (store == null) {
            store = new JpaReleaseStore(factory());
        }
        return store;
    }

    /**
     * An approved version of a course dana teaches.
     *
     * <p>Protected so the MySQL leaf can build the fixture its collation tests need without
     * copying it: those tests belong in the leaf because they are only true there, but their
     * setup is the same setup everything else here uses.
     *
     * @return the new version's id
     */
    protected long approvedVersion() {
        return version(COURSE_ALGEBRA, ExamVersionStatus.APPROVED);
    }

    /**
     * Creates a release through the seam, exactly as the service does.
     *
     * @param examVersionId the version to release
     * @param code          the code, already normalised the way the service normalises it
     * @return the new release's id
     */
    protected long createExecution(long examVersionId, String code) {
        return store().inTx(data -> data.createExecution(examVersionId, code,
                WHEN.plus(Duration.ofHours(1)), WHEN.plus(Duration.ofHours(2)), danaId));
    }

    /**
     * @param code a candidate, as typed
     * @return whether a scheduled or live release is holding it
     */
    protected boolean codeInUse(String code) {
        return flag(data -> data.isCodeInUse(code));
    }

    // ===================== The drawer ====================================

    @Test
    @DisplayName("only approved versions of courses she teaches are releasable ⚑")
    void releasableVersionsAreApprovedAndHers() {
        long approved = version(COURSE_ALGEBRA, ExamVersionStatus.APPROVED);
        long draft = version(COURSE_ALGEBRA, ExamVersionStatus.DRAFT);
        long pending = version(COURSE_ALGEBRA, ExamVersionStatus.PENDING);
        long othersCourse = version(COURSE_JAVA, ExamVersionStatus.APPROVED);

        List<ExamVersionContext> releasable =
                store().inTx(data -> data.releasableVersionsFor(danaId));

        // dana teaches Algebra and Calculus in the shared fixture, not Java.
        assertThat(releasable).extracting(ExamVersionContext::examVersionId)
                .containsExactly(approved)
                .doesNotContain(draft, pending, othersCourse);
    }

    @Test
    @DisplayName("a teacher with nothing approved gets an empty list, not an error")
    void nothingReleasable() {
        version(COURSE_ALGEBRA, ExamVersionStatus.DRAFT);

        List<ExamVersionContext> none = store().inTx(data -> data.releasableVersionsFor(danaId));

        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("'has she any exam at all' tells the two empty states apart")
    void hasAnyExam() {
        assertThat(flag(data -> data.hasAnyExam(danaId))).isFalse();

        version(COURSE_ALGEBRA, ExamVersionStatus.DRAFT);

        assertThat(flag(data -> data.hasAnyExam(danaId))).isTrue();
        // rina teaches Calculus only, and the exam above is Algebra's.
        assertThat(flag(data -> data.hasAnyExam(principalId))).isFalse();
    }

    @Test
    @DisplayName("a version is fetched whatever its status, so a draft can be refused by name")
    void versionByIdIsUnfiltered() {
        long draft = version(COURSE_ALGEBRA, ExamVersionStatus.DRAFT);

        Optional<ExamVersionContext> found = store().inTx(data -> data.versionById(draft));

        // A read that returned approved versions only could not tell "no such exam" from
        // "that exam is still a draft", and only the second has the F5.1 sentence.
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(ExamVersionStatus.DRAFT);
        Optional<ExamVersionContext> missing = store().inTx(data -> data.versionById(999_999L));
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("teaching is the narrow question, resolved through course_teachers")
    void teaching() {
        assertThat(flag(data -> data.teaches(danaId, COURSE_ALGEBRA))).isTrue();
        assertThat(flag(data -> data.teaches(danaId, COURSE_JAVA))).isFalse();
        // dana is *enrolled* in Databases, which is emphatically not teaching it.
        assertThat(flag(data -> data.teaches(danaId, COURSE_DATABASES))).isFalse();
    }

    // ===================== Codes =========================================

    @Test
    @DisplayName("⚑ a code is in use while its release is scheduled or live, and free once it is over")
    void codeUniquenessIsPartial() {
        long examVersionId = version(COURSE_ALGEBRA, ExamVersionStatus.APPROVED);
        execution("AAAA", ExecutionStatus.SCHEDULED, examVersionId);
        execution("BBBB", ExecutionStatus.LIVE, examVersionId);
        execution("CCCC", ExecutionStatus.CLOSED, examVersionId);
        execution("DDDD", ExecutionStatus.CANCELLED, examVersionId);

        assertThat(flag(data -> data.isCodeInUse("AAAA"))).isTrue();
        assertThat(flag(data -> data.isCodeInUse("BBBB"))).isTrue();
        // The seed's reuse case: a closed release must not hold its code for the rest of the
        // year, and a cancelled one never could be entered at all.
        assertThat(flag(data -> data.isCodeInUse("CCCC"))).isFalse();
        assertThat(flag(data -> data.isCodeInUse("DDDD"))).isFalse();
        assertThat(flag(data -> data.isCodeInUse("ZZZZ"))).isFalse();
    }

    @Test
    @DisplayName("codes compare case-insensitively, because students type them (C-1)")
    void codeComparisonFoldsCase() {
        long examVersionId = version(COURSE_ALGEBRA, ExamVersionStatus.APPROVED);
        execution("AB12", ExecutionStatus.LIVE, examVersionId);

        assertThat(flag(data -> data.isCodeInUse("ab12"))).isTrue();
    }

    @Test
    @DisplayName("a blank code is not in use, rather than matching everything")
    void blankCodeIsFree() {
        assertThat(flag(data -> data.isCodeInUse("  "))).isFalse();
        assertThat(flag(data -> data.isCodeInUse(null))).isFalse();
    }

    // ===================== Creating and reading ==========================

    @Test
    @DisplayName("a created release is scheduled, hers, and readable straight back")
    void createAndRead() {
        long examVersionId = version(COURSE_ALGEBRA, ExamVersionStatus.APPROVED);

        long executionId = store().inTx(data -> data.createExecution(examVersionId, "4B7Q",
                WHEN.plus(Duration.ofHours(1)), WHEN.plus(Duration.ofHours(2)), danaId));

        ExecutionContext context = store().inTx(data -> data.executionById(executionId))
                .orElseThrow();
        // Never inserted straight into LIVE: there is exactly one place a release becomes
        // live, and it is the scheduled check that also announces it.
        assertThat(context.status()).isEqualTo(ExecutionStatus.SCHEDULED);
        assertThat(context.code()).isEqualTo("4B7Q");
        assertThat(context.executingTeacherId()).isEqualTo(danaId);
        assertThat(context.isOwnedBy(danaId)).isTrue();
    }

    @Test
    @DisplayName("her list holds what she released and what her own exams were released as (S-35)")
    void listScope() {
        long hers = version(COURSE_ALGEBRA, ExamVersionStatus.APPROVED, danaId);
        long colleagues = version(COURSE_CALCULUS, ExamVersionStatus.APPROVED, rinaId);
        long releasedByHer = execution("AAAA", ExecutionStatus.SCHEDULED, colleagues, danaId);
        long releasedByHim = execution("BBBB", ExecutionStatus.SCHEDULED, hers, rinaId);
        long neitherHers = execution("CCCC", ExecutionStatus.SCHEDULED, colleagues, rinaId);

        List<ExecutionContext> list = store().inTx(data -> data.executionsFor(danaId));

        assertThat(list).extracting(ExecutionContext::executionId)
                .containsExactlyInAnyOrder(releasedByHer, releasedByHim)
                .doesNotContain(neitherHers);
    }

    @Test
    @DisplayName("a cancelled release stays on her list, unlike on the results picker")
    void cancelledStaysOnTheList() {
        long examVersionId = version(COURSE_ALGEBRA, ExamVersionStatus.APPROVED);
        long cancelled = execution("AAAA", ExecutionStatus.CANCELLED, examVersionId);

        List<ExecutionContext> list = store().inTx(data -> data.executionsFor(danaId));

        assertThat(list).extracting(ExecutionContext::executionId).contains(cancelled);
    }

    // ===================== Participation =================================

    @Test
    @DisplayName("participation is counted per release in one grouped read (§5)")
    void batchedParticipation() {
        long examVersionId = version(COURSE_ALGEBRA, ExamVersionStatus.APPROVED);
        long busy = execution("AAAA", ExecutionStatus.LIVE, examVersionId);
        long quiet = execution("BBBB", ExecutionStatus.SCHEDULED, examVersionId);
        attempt(busy, mayaId, AttemptStatus.IN_PROGRESS);
        attempt(busy, danaId, AttemptStatus.SUBMITTED);
        attempt(busy, rinaId, AttemptStatus.TIMED_OUT);

        Map<Long, ParticipationCounts> counts =
                store().inTx(data -> data.participationOf(List.of(busy, quiet)));

        assertThat(counts.get(busy)).isEqualTo(new ParticipationCounts(3, 1, 1));
        // Absent rather than three zeros: the caller defaults it, so there is one way to
        // say "nobody".
        assertThat(counts).doesNotContainKey(quiet);
    }

    @Test
    @DisplayName("asking about no releases counts none, not every attempt in the school")
    void participationOfNothing() {
        Map<Long, ParticipationCounts> none = store().inTx(data -> data.participationOf(List.of()));

        assertThat(none).isEmpty();
    }

    // ===================== The scheduled check ===========================

    @Test
    @DisplayName("the scheduled check sees only scheduled releases, up to its horizon")
    void scheduledOpeningBy() {
        long examVersionId = version(COURSE_ALGEBRA, ExamVersionStatus.APPROVED);
        long soon = execution("AAAA", ExecutionStatus.SCHEDULED, examVersionId,
                WHEN.plus(Duration.ofMinutes(10)));
        long later = execution("BBBB", ExecutionStatus.SCHEDULED, examVersionId,
                WHEN.plus(Duration.ofHours(5)));
        long alreadyLive = execution("CCCC", ExecutionStatus.LIVE, examVersionId,
                WHEN.minus(Duration.ofMinutes(10)));

        List<ExecutionContext> due = store().inTx(data ->
                data.scheduledOpeningBy(WHEN.plus(Duration.ofMinutes(30))));

        assertThat(due).extracting(ExecutionContext::executionId)
                .containsExactly(soon)
                .doesNotContain(later, alreadyLive);
    }

    @Test
    @DisplayName("the closing check sees only live releases whose stored window has ended")
    void liveClosingBy() {
        long examVersionId = version(COURSE_ALGEBRA, ExamVersionStatus.APPROVED);
        long over = execution("AAAA", ExecutionStatus.LIVE, examVersionId,
                WHEN.minus(Duration.ofHours(3)), WHEN.minus(Duration.ofMinutes(1)));
        long running = execution("BBBB", ExecutionStatus.LIVE, examVersionId,
                WHEN.minus(Duration.ofHours(1)), WHEN.plus(Duration.ofHours(1)));
        long scheduledButPast = execution("CCCC", ExecutionStatus.SCHEDULED, examVersionId,
                WHEN.minus(Duration.ofHours(3)), WHEN.minus(Duration.ofMinutes(1)));

        List<ExecutionContext> due = store().inTx(data -> data.liveClosingBy(WHEN));

        assertThat(due).extracting(ExecutionContext::executionId)
                .containsExactly(over)
                .doesNotContain(running, scheduledButPast);
    }

    // ===================== The transition ================================

    @Test
    @DisplayName("⚑ a transition only fires from the state it was told to expect")
    void guardedTransition() {
        long examVersionId = version(COURSE_ALGEBRA, ExamVersionStatus.APPROVED);
        long executionId = execution("AAAA", ExecutionStatus.SCHEDULED, examVersionId);

        int opened = store().inTx(data ->
                data.transition(executionId, ExecutionStatus.SCHEDULED, ExecutionStatus.LIVE));
        int again = store().inTx(data ->
                data.transition(executionId, ExecutionStatus.SCHEDULED, ExecutionStatus.CANCELLED));

        assertThat(opened).isEqualTo(1);
        // The whole point: the scheduled check and a teacher's cancel race, and the loser
        // must change nothing and know it changed nothing.
        assertThat(again).isZero();
        Optional<ExecutionContext> after = store().inTx(data -> data.executionById(executionId));
        assertThat(after.orElseThrow().status()).isEqualTo(ExecutionStatus.LIVE);
    }

    @Test
    @DisplayName("a transition of a release that does not exist changes nothing")
    void transitionOfAGhost() {
        assertThat(count(data -> data.transition(999_999L,
                ExecutionStatus.SCHEDULED, ExecutionStatus.LIVE))).isZero();
    }

    // ===================== Enrolment =====================================

    @Test
    @DisplayName("the opens-soon notice reaches the students enrolled in that course")
    void enrolledStudents() {
        runInTx(session -> session.persist(new Enrollment(COURSE_CALCULUS, mayaId)));

        List<Long> algebra = store().inTx(data -> data.enrolledStudents(COURSE_ALGEBRA));
        List<Long> calculus = store().inTx(data -> data.enrolledStudents(COURSE_CALCULUS));
        List<Long> unknownCourse = store().inTx(data -> data.enrolledStudents("99"));
        List<Long> noCourse = store().inTx(data -> data.enrolledStudents(null));

        assertThat(algebra).containsExactly(mayaId);
        assertThat(calculus).containsExactly(mayaId);
        assertThat(unknownCourse).isEmpty();
        assertThat(noCourse).isEmpty();
    }

    // ===================== Fixture =======================================

    /**
     * A boolean answer through the seam.
     *
     * <p>{@code inTx} is generic in its result, so a lambda returning a primitive leaves the
     * type variable to be inferred from AssertJ's overload set, which is ambiguous. Naming
     * the type once here is cheaper than a cast at every call site.
     */
    private boolean flag(java.util.function.Function<ReleaseData, Boolean> work) {
        return store().inTx(work);
    }

    /** An int answer through the seam, for the same reason. */
    private int count(java.util.function.Function<ReleaseData, Integer> work) {
        return store().inTx(work);
    }

    private long version(String courseCode, ExamVersionStatus status) {
        return version(courseCode, status, danaId);
    }

    /** A fresh exam and version per call: exam serials are unique per course. */
    private long version(String courseCode, ExamVersionStatus status, long authorId) {
        return inTx(session -> {
            byte serial = (byte) (session.createQuery("select count(e) from Exam e", Long.class)
                    .getSingleResult().intValue() + 1);
            Exam exam = new Exam(courseCode, serial,
                    "10" + courseCode + String.format("%02d", serial), authorId);
            session.persist(exam);
            session.flush();
            ExamVersion examVersion = new ExamVersion(exam.getId(), 1, "מבחן", 45,
                    null, null, status, WHEN);
            session.persist(examVersion);
            session.flush();
            return examVersion.getId();
        });
    }

    private long execution(String code, ExecutionStatus status, long examVersionId) {
        return execution(code, status, examVersionId, danaId);
    }

    private long execution(String code, ExecutionStatus status, long examVersionId, long createdBy) {
        return persist(code, status, examVersionId, createdBy,
                WHEN.plus(Duration.ofHours(1)), WHEN.plus(Duration.ofHours(2)));
    }

    private long execution(String code, ExecutionStatus status, long examVersionId, Instant openAt) {
        return persist(code, status, examVersionId, danaId, openAt, openAt.plus(Duration.ofHours(1)));
    }

    private long execution(String code, ExecutionStatus status, long examVersionId,
                           Instant openAt, Instant closeAt) {
        return persist(code, status, examVersionId, danaId, openAt, closeAt);
    }

    private long persist(String code, ExecutionStatus status, long examVersionId, long createdBy,
                         Instant openAt, Instant closeAt) {
        return inTx(session -> {
            ExamExecution execution =
                    new ExamExecution(examVersionId, code, openAt, closeAt, status, createdBy);
            session.persist(execution);
            session.flush();
            return execution.getId();
        });
    }

    private void attempt(long executionId, long studentId, AttemptStatus status) {
        runInTx(session -> {
            ExamAttempt attempt = new ExamAttempt(executionId, studentId, WHEN);
            session.persist(attempt);
            session.flush();
            if (status != AttemptStatus.IN_PROGRESS) {
                // Finalised the way production does it: a status-guarded UPDATE, because the
                // entity deliberately has no setter for it.
                session.createMutationQuery("""
                                update ExamAttempt set status = :status, endedAt = :endedAt,
                                    actualMinutes = 30
                                where id = :id and status = :inProgress
                                """)
                        .setParameter("status", status)
                        .setParameter("endedAt", WHEN.plus(Duration.ofMinutes(30)))
                        .setParameter("id", attempt.getId())
                        .setParameter("inProgress", AttemptStatus.IN_PROGRESS)
                        .executeUpdate();
            }
        });
    }
}
