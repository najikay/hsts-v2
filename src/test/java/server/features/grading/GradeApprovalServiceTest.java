package server.features.grading;

import common.dto.grading.ApproveRequest;
import common.dto.grading.ApproveResult;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import server.db.entities.AttemptStatus;
import server.db.entities.ExamExecution;
import server.db.entities.ExecutionStats;
import server.db.entities.ExecutionStatus;
import server.db.entities.Grade;
import server.db.entities.GradeStatus;
import server.db.projections.AttemptRecord;
import server.db.projections.ExecutionContext;
import server.db.repos.AttemptRepository;
import server.db.repos.ExecutionRepository;
import server.db.repos.GradeRepository;
import server.features.notify.Notifier;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link GradeApprovalService} — E12.2 / E12.7.
 *
 * <p>Four rules carry the weight here, and each is one a plausible implementation gets wrong:
 * approval is idempotent <i>without re-stamping the audit fields</i>, partial success is a normal
 * outcome rather than a rollback, "not yours" and "does not exist" produce the same answer, and
 * the last approval of an execution freezes its statistics.
 *
 * <p>The fixture is the seeded execution 4821 — `dana.cohen` (id 2) is both the executing teacher
 * and the exam's author, and the eight final scores are §9.1's, so the frozen figures the last
 * test asserts are the ones the seed document already states.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalServiceTest {

    private static final long EXECUTION = 4821;
    private static final long DANA = 2;
    private static final long AVI = 4;
    private static final Instant NOW = Instant.parse("2026-08-08T09:00:00Z");
    private static final Instant EARLIER = Instant.parse("2026-08-01T00:00:00Z");

    /** Seed §9.1's eight final scores. */
    private static final List<Integer> SEEDED_SCORES = List.of(100, 90, 85, 75, 70, 60, 55, 45);

    @Mock
    private Session session;
    @Mock
    private GradeRepository grades;
    @Mock
    private AttemptRepository attempts;
    @Mock
    private ExecutionRepository executions;
    @Mock
    private Notifier notifier;

    private GradeApprovalService service;

    @BeforeEach
    void setUp() {
        service = new GradeApprovalService(grades, attempts, executions, notifier,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Nested
    @DisplayName("approving")
    class Approving {

        @Test
        @DisplayName("a single grade becomes APPROVED, stamped with the caller and the clock")
        void approvesOne() {
            Grade g = auto(1, 71);
            wire(List.of(g), context(DANA, DANA));

            ApproveResult result = service.approve(session, DANA, ApproveRequest.one(1));

            assertThat(result.approved()).isEqualTo(1);
            assertThat(result.alreadyApproved()).isZero();
            assertThat(result.refused()).isEmpty();
            assertThat(g.getStatus()).isEqualTo(GradeStatus.APPROVED);
            assertThat(g.getApprovedBy()).isEqualTo(DANA);
            assertThat(g.getApprovedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("approving fills finalScore from autoScore when nobody overrode it")
        void approvingFillsFinalScore() {
            Grade g = auto(1, 71);
            wire(List.of(g), context(DANA, DANA));

            service.approve(session, DANA, ApproveRequest.one(1));

            assertThat(g.getFinalScore()).isEqualTo(71);
            assertThat(g.getEffectiveScore()).isEqualTo(71);
        }

        @Test
        @DisplayName("an override survives approval — the teacher's score is not overwritten")
        void approvingKeepsAnOverride() {
            Grade g = auto(1, 45);
            g.override(55, "partial credit on 11011");
            wire(List.of(g), context(DANA, DANA));

            service.approve(session, DANA, ApproveRequest.one(1));

            assertThat(g.getFinalScore()).isEqualTo(55);
            assertThat(g.getOverrideReason()).isEqualTo("partial credit on 11011");
        }

        @Test
        @DisplayName("E12.7 — one verb approves a whole class")
        void approvesInBulk() {
            List<Grade> all = autos(8);
            wire(all, context(DANA, DANA));

            ApproveResult result = service.approve(session, DANA, request(8));

            assertThat(result.approved()).isEqualTo(8);
            assertThat(result.isComplete()).isTrue();
            assertThat(all).allMatch(g -> g.getStatus() == GradeStatus.APPROVED);
        }

        @Test
        @DisplayName("the exam's author may approve even when another teacher ran the execution")
        void authorMayApprove() {
            Grade g = auto(1, 71);
            wire(List.of(g), context(AVI, DANA));   // avi ran it, dana wrote it

            ApproveResult result = service.approve(session, DANA, ApproveRequest.one(1));

            assertThat(result.approved()).isEqualTo(1);
        }

        @Test
        @DisplayName("an empty request is a no-op, not an error")
        void emptyRequest() {
            ApproveResult result = service.approve(session, DANA, new ApproveRequest(List.of()));

            assertThat(result.approved()).isZero();
            assertThat(result.refused()).isEmpty();
            verify(grades, never()).findByIds(any(), any());
        }
    }

    @Nested
    @DisplayName("idempotence — §6")
    class Idempotence {

        @Test
        @DisplayName("re-approving counts in alreadyApproved and is not an error")
        void reApprovingIsCounted() {
            Grade already = approved(1, 71);
            wire(List.of(already), context(DANA, DANA));

            ApproveResult result = service.approve(session, DANA, ApproveRequest.one(1));

            assertThat(result.approved()).isZero();
            assertThat(result.alreadyApproved()).isEqualTo(1);
            assertThat(result.refused()).isEmpty();
        }

        @Test
        @DisplayName("re-approving does NOT re-stamp approvedAt — the audit trail is not rewritten")
        void reApprovingDoesNotRestamp() {
            Grade already = approved(1, 71);
            wire(List.of(already), context(DANA, DANA));

            service.approve(session, AVI, ApproveRequest.one(1));

            // Still the original approver and the original moment, not AVI and NOW.
            assertThat(already.getApprovedAt()).isEqualTo(EARLIER);
            assertThat(already.getApprovedBy()).isEqualTo(DANA);
        }

        @Test
        @DisplayName("a mixed batch reports both counts rather than failing")
        void mixedBatch() {
            Grade fresh = auto(1, 71);
            Grade done = approved(2, 85);
            wire(List.of(fresh, done), context(DANA, DANA));

            ApproveResult result = service.approve(session, DANA,
                    new ApproveRequest(List.of(1L, 2L)));

            assertThat(result.approved()).isEqualTo(1);
            assertThat(result.alreadyApproved()).isEqualTo(1);
            assertThat(result.refused()).isEmpty();
        }
    }

    @Nested
    @DisplayName("refusal — partial success is normal")
    class Refusal {

        @Test
        @DisplayName("a grade belonging to another teacher's execution is refused, not approved")
        void refusesSomebodyElses() {
            Grade g = auto(1, 71);
            wire(List.of(g), context(AVI, AVI));   // neither role is dana

            ApproveResult result = service.approve(session, DANA, ApproveRequest.one(1));

            assertThat(result.approved()).isZero();
            assertThat(result.refused()).containsExactly(1L);
            assertThat(g.getStatus()).isEqualTo(GradeStatus.AUTO);
        }

        @Test
        @DisplayName("⚑ an id that does not exist is refused identically to one that is not yours")
        void missingAndForbiddenAreIndistinguishable() {
            // Nothing found at all — covers both "no such grade" and a grade the query
            // did not return. The caller cannot tell which happened.
            lenient().when(grades.findByIds(any(), any())).thenReturn(List.of());

            ApproveResult missing = service.approve(session, DANA, ApproveRequest.one(999));

            Grade notMine = auto(1, 71);
            wire(List.of(notMine), context(AVI, AVI));
            ApproveResult forbidden = service.approve(session, DANA, ApproveRequest.one(1));

            assertThat(missing.approved()).isEqualTo(forbidden.approved()).isZero();
            assertThat(missing.alreadyApproved()).isEqualTo(forbidden.alreadyApproved()).isZero();
            assertThat(missing.refused()).hasSize(forbidden.refused().size());
        }

        @Test
        @DisplayName("approving eight of ten is a real outcome — the eight still land")
        void partialSuccessIsNotRolledBack() {
            Grade mine = auto(1, 71);
            wire(List.of(mine), context(DANA, DANA));

            ApproveResult result = service.approve(session, DANA,
                    new ApproveRequest(List.of(1L, 998L, 999L)));

            assertThat(result.approved()).isEqualTo(1);
            assertThat(result.refused()).containsExactlyInAnyOrder(998L, 999L);
            assertThat(mine.getStatus()).isEqualTo(GradeStatus.APPROVED);
            assertThat(result.isComplete()).isFalse();
        }
    }

    @Nested
    @DisplayName("freezing the statistics — E12.4's \"→ stored\"")
    class FreezingStats {

        @Test
        @DisplayName("the last approval of an execution writes the frozen stats")
        void freezesOnCompletion() {
            List<Grade> all = autos(8);
            wire(all, context(DANA, DANA));
            ExamExecution execution = execution();
            lenient().when(grades.findAllForExecution(any(), anyLong()))
                    .thenAnswer(inv -> all);   // after approval, all eight are APPROVED
            lenient().when(executions.findById(any(), anyLong()))
                    .thenReturn(Optional.of(execution));

            service.approve(session, DANA, request(8));

            ExecutionStats stats = execution.getStats();
            assertThat(stats).isNotNull();
            assertThat(stats.average()).isEqualTo(72.5);
            assertThat(stats.median()).isEqualTo(72.5);
            assertThat(stats.stdDev()).isEqualTo(17.5);
            assertThat(stats.min()).isEqualTo(45);
            assertThat(stats.max()).isEqualTo(100);
            assertThat(stats.passRate()).isEqualTo(0.875);
            assertThat(stats.deciles()).containsExactly(0, 0, 0, 0, 1, 1, 1, 2, 1, 2);
        }

        @Test
        @DisplayName("half a class approved freezes nothing — grading is still in progress")
        void doesNotFreezeWhileIncomplete() {
            List<Grade> all = autos(8);
            wire(List.of(all.get(0)), context(DANA, DANA));
            ExamExecution execution = execution();
            lenient().when(grades.findAllForExecution(any(), anyLong())).thenReturn(all);
            lenient().when(executions.findById(any(), anyLong()))
                    .thenReturn(Optional.of(execution));

            service.approve(session, DANA, ApproveRequest.one(1));

            // Seven rows are still AUTO, so nothing is frozen.
            assertThat(execution.getStats()).isNull();
        }

        @Test
        @DisplayName("nothing is frozen when the approval was refused")
        void doesNotFreezeOnRefusal() {
            List<Grade> all = autos(8);
            wire(all, context(AVI, AVI));
            ExamExecution execution = execution();
            lenient().when(executions.findById(any(), anyLong()))
                    .thenReturn(Optional.of(execution));

            service.approve(session, DANA, request(8));

            assertThat(execution.getStats()).isNull();
            verify(grades, never()).findAllForExecution(any(), anyLong());
        }
    }

    @Nested
    @DisplayName("publishing to the student — C-3, E13.6")
    class Publishing {

        @Test
        @DisplayName("each newly approved grade notifies its student")
        void notifiesOnApproval() {
            List<Grade> all = autos(3);
            wire(all, context(DANA, DANA));

            service.approve(session, DANA, request(3));

            verify(notifier, org.mockito.Mockito.times(3)).notifyUser(anyLong(), any());
        }

        @Test
        @DisplayName("an already-approved grade does not notify again")
        void doesNotRenotify() {
            Grade already = approved(1, 71);
            wire(List.of(already), context(DANA, DANA));

            service.approve(session, DANA, ApproveRequest.one(1));

            verify(notifier, never()).notifyUser(anyLong(), any());
        }

        @Test
        @DisplayName("a refused grade does not notify")
        void refusedDoesNotNotify() {
            Grade g = auto(1, 71);
            wire(List.of(g), context(AVI, AVI));

            service.approve(session, DANA, ApproveRequest.one(1));

            verify(notifier, never()).notifyUser(anyLong(), any());
        }
    }

    // ===== fixtures =======================================================

    private static Grade auto(long id, int autoScore) {
        Grade g = new Grade(id * 10, autoScore);
        setField(g, "id", id);
        return g;
    }

    private static Grade approved(long id, int autoScore) {
        Grade g = auto(id, autoScore);
        g.approve(DANA, EARLIER);
        return g;
    }

    /** {@code n} AUTO grades carrying the seed's final scores, ids 1..n. */
    private static List<Grade> autos(int n) {
        List<Grade> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(auto(i + 1, SEEDED_SCORES.get(i)));
        }
        return out;
    }

    private static ApproveRequest request(int n) {
        List<Long> ids = new ArrayList<>(n);
        for (long i = 1; i <= n; i++) {
            ids.add(i);
        }
        return new ApproveRequest(ids);
    }

    private static ExamExecution execution() {
        return new ExamExecution(11L, "4821", NOW.minusSeconds(7200),
                NOW.minusSeconds(3600), ExecutionStatus.CLOSED, DANA);
    }

    private static ExecutionContext context(long executingTeacher, long author) {
        return new ExecutionContext(EXECUTION, 11L, 1L, "11", "אלגברה", "מבחן אמצע — אלגברה",
                75, null, "4821", ExecutionStatus.CLOSED,
                NOW.minusSeconds(7200), NOW.minusSeconds(3600), 0, executingTeacher, author);
    }

    private void wire(List<Grade> found, ExecutionContext ctx) {
        lenient().when(grades.findByIds(any(), any())).thenReturn(found);
        lenient().when(attempts.findRecordById(any(), anyLong())).thenAnswer(inv -> {
            long attemptId = inv.getArgument(1);
            return Optional.of(new AttemptRecord(attemptId, EXECUTION, 100 + attemptId,
                    NOW.minusSeconds(7200), NOW.minusSeconds(5400), 30, AttemptStatus.SUBMITTED));
        });
        lenient().when(executions.findContext(any(), anyLong())).thenReturn(Optional.of(ctx));
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not set " + name, e);
        }
    }
}
