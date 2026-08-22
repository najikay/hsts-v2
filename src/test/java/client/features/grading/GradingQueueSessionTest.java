package client.features.grading;

import client.events.DirectFxThreadPoster;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.grading.ApproveRequest;
import common.dto.grading.ApproveResult;
import common.dto.grading.ExecutionGrades;
import common.dto.grading.ExecutionGradesRequest;
import common.dto.grading.ExecutionGradingSummary;
import common.dto.grading.GradeOverrideRequest;
import common.dto.grading.GradeState;
import common.dto.grading.GradingQueue;
import common.dto.grading.StudentGradeRow;
import common.protocol.ErrorCode;
import common.protocol.Verb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GradingQueueSession} — the teacher's grading screen, without a toolkit (E12.5–E12.7).
 *
 * <p>The tests that carry weight are about what happens <em>after</em> a write.
 * {@code approvalRereadsRatherThanPatching} is the one: patching rows in place is faster and is
 * how a screen ends up disagreeing with the server, because an approval can be partly refused
 * and an override moves both a score and a state. And {@code selectionIsClearedOnReread} closes
 * the matching hazard — a selection that survived a refresh would let a teacher approve a row
 * whose state changed underneath her.
 *
 * <p>Fixture: the seeded Java sitting (execution 2), eight students, all `AUTO`.
 */
class GradingQueueSessionTest {

    private static final long EXECUTION = 4822;

    private FakeClientConnection connection;
    private GradingQueueSession session;
    private int renders;

    @BeforeEach
    void setUp() {
        connection = new FakeClientConnection();
        RequestDispatcher dispatcher = new RequestDispatcher(connection);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
        session = new GradingQueueSession(dispatcher, new DirectFxThreadPoster())
                .onChange(() -> renders++);
    }

    private static ExecutionGradingSummary summary(int graded, int approved) {
        return new ExecutionGradingSummary(EXECUTION, "Java midterm", "21", "7390",
                Instant.parse("2026-06-02T10:00:00Z"), 8, graded, approved);
    }

    private static StudentGradeRow row(long gradeId, String name, int auto, GradeState state) {
        Integer finalScore = state == GradeState.APPROVED ? auto : null;
        return new StudentGradeRow(gradeId, gradeId, name, auto, finalScore, auto, state,
                null, null, state == GradeState.APPROVED
                        ? Instant.parse("2026-06-03T09:00:00Z") : null);
    }

    private static ExecutionGrades twoRows() {
        return new ExecutionGrades(summary(2, 0), List.of(
                row(1, "מאיה לוי", 100, GradeState.AUTO),
                row(2, "עומר כץ", 40, GradeState.AUTO)));
    }

    /** Loads the queue and opens the sitting, leaving the session ready to act. */
    private void givenOpenSitting() {
        connection.replyOk(Verb.GRADING_QUEUE_GET, new GradingQueue(List.of(summary(2, 0))));
        session.load();
        connection.replyOk(Verb.GRADING_EXECUTION_GET, twoRows());
        session.openExecution(summary(2, 0));
    }

    // ===================== The queue ======================================

    @Nested
    @DisplayName("The queue")
    class Queue {

        @Test
        @DisplayName("loads and reports content")
        void loads() {
            connection.replyOk(Verb.GRADING_QUEUE_GET, new GradingQueue(List.of(summary(8, 0))));

            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.READY);
            assertThat(session.queue()).hasSize(1);
        }

        @Test
        @DisplayName("an empty queue is EMPTY, not an error — a finished inbox is a state")
        void emptyIsAState() {
            connection.replyOk(Verb.GRADING_QUEUE_GET, GradingQueue.EMPTY);

            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.EMPTY);
            assertThat(session.error()).isEmpty();
        }

        @Test
        @DisplayName("a failure says so without saying why")
        void failure() {
            connection.replyError(Verb.GRADING_QUEUE_GET, ErrorCode.INTERNAL, "boom");

            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.error()).contains(GradingCopy.LOAD_FAILED);
        }

        @Test
        @DisplayName("a second load while one is in flight is ignored rather than raced")
        void concurrentLoadIgnored() {
            session.load();
            connection.clearSent();

            session.load();

            assertThat(connection.sentCount()).isZero();
        }
    }

    // ===================== Opening a sitting ==============================

    @Nested
    @DisplayName("Opening a sitting")
    class Opening {

        @Test
        @DisplayName("asks for the execution the row names and shows its rows")
        void opens() {
            givenOpenSitting();

            assertThat(session.openExecution()).isPresent();
            assertThat(session.rows()).hasSize(2);
        }

        @Test
        @DisplayName("a refusal leaves the queue intact and says only that it could not open")
        void refusalKeepsTheQueue() {
            connection.replyOk(Verb.GRADING_QUEUE_GET, new GradingQueue(List.of(summary(2, 0))));
            session.load();
            connection.replyError(Verb.GRADING_EXECUTION_GET, ErrorCode.NOT_FOUND, "no");

            session.openExecution(summary(2, 0));

            assertThat(session.error()).contains(GradingCopy.EXECUTION_FAILED);
            assertThat(session.queue()).hasSize(1);
        }
    }

    // ===================== Approving ======================================

    @Nested
    @DisplayName("Approving")
    class Approving {

        @Test
        @DisplayName("sends exactly the ticked rows")
        void sendsTheSelection() {
            givenOpenSitting();
            session.select(1, true);
            session.select(2, true);
            session.select(2, false);
            connection.clearSent();
            connection.replyOk(Verb.GRADES_APPROVE, new ApproveResult(1, 0, List.of()));

            session.approveSelected();

            assertThat(connection.sentMessages().get(0).getPayload())
                    .isEqualTo(new ApproveRequest(List.of(1L)));
        }

        @Test
        @DisplayName("does nothing at all when nothing is ticked")
        void nothingSelectedSendsNothing() {
            givenOpenSitting();
            connection.clearSent();

            session.approveSelected();

            // A screen that sends a request for no work is a screen whose buttons mean nothing.
            assertThat(connection.sentCount()).isZero();
        }

        @Test
        @DisplayName("re-reads the sitting rather than patching the rows it already has")
        void approvalRereadsRatherThanPatching() {
            givenOpenSitting();
            session.select(1, true);
            connection.clearSent();
            connection.replyOk(Verb.GRADES_APPROVE, new ApproveResult(1, 0, List.of()));

            session.approveSelected();

            // Patching is faster and is how a screen drifts: an approval can be partly refused,
            // and freezing an execution's statistics happens server-side where a client cannot
            // see it. The re-read is the only thing that cannot disagree.
            assertThat(connection.sentMessages()).extracting(m -> m.getVerb())
                    .containsSubsequence(Verb.GRADES_APPROVE, Verb.GRADING_EXECUTION_GET);
        }

        @Test
        @DisplayName("keeps the refused ids, which the re-read cannot report")
        void keepsRefusedIds() {
            givenOpenSitting();
            session.select(1, true);
            session.select(2, true);
            connection.replyOk(Verb.GRADES_APPROVE, new ApproveResult(1, 0, List.of(2L)));

            session.approveSelected();

            // A refused row and an untouched row look identical once the table refreshes.
            assertThat(session.lastApproval()).isPresent();
            assertThat(session.lastApproval().get().refused()).containsExactly(2L);
        }

        @Test
        @DisplayName("the selection is cleared on the re-read")
        void selectionIsClearedOnReread() {
            givenOpenSitting();
            session.select(1, true);
            connection.replyOk(Verb.GRADES_APPROVE, new ApproveResult(1, 0, List.of()));
            connection.replyOk(Verb.GRADING_EXECUTION_GET, twoRows());

            session.approveSelected();

            // A selection that survived a refresh would let her approve a row whose state
            // changed underneath her.
            assertThat(session.selectionSize()).isZero();
        }

        @Test
        @DisplayName("select-all ticks only the rows that can still be approved")
        void selectAllSkipsApproved() {
            connection.replyOk(Verb.GRADING_QUEUE_GET, new GradingQueue(List.of(summary(2, 1))));
            session.load();
            connection.replyOk(Verb.GRADING_EXECUTION_GET, new ExecutionGrades(summary(2, 1),
                    List.of(row(1, "מאיה לוי", 100, GradeState.AUTO),
                            row(2, "עומר כץ", 40, GradeState.APPROVED))));
            session.openExecution(summary(2, 1));

            session.selectAllApprovable();

            // Counting rows already done would make the confirmation overstate what is about
            // to happen.
            assertThat(session.selection()).containsExactly(1L);
        }

        @Test
        @DisplayName("a failed approval says so and does not clear what she chose")
        void failedApprovalKeepsSelection() {
            givenOpenSitting();
            session.select(1, true);
            connection.replyError(Verb.GRADES_APPROVE, ErrorCode.INTERNAL, "boom");

            session.approveSelected();

            assertThat(session.error()).contains(GradingCopy.APPROVE_FAILED);
            assertThat(session.selectionSize()).isEqualTo(1);
        }
    }

    // ===================== Overriding =====================================

    @Nested
    @DisplayName("Overriding")
    class Overriding {

        @Test
        @DisplayName("sends the score and the reason together")
        void sendsOverride() {
            givenOpenSitting();
            connection.clearSent();
            connection.replyOk(Verb.GRADE_OVERRIDE, null);

            boolean sent = session.override(1, 80, "question 3 was ambiguous");

            assertThat(sent).isTrue();
            assertThat(connection.sentMessages().get(0).getPayload())
                    .isEqualTo(new GradeOverrideRequest(1, 80, "question 3 was ambiguous"));
        }

        @Test
        @DisplayName("refuses a blank reason before the request travels (S-23)")
        void blankReasonNeverLeaves() {
            givenOpenSitting();
            connection.clearSent();

            boolean sent = session.override(1, 80, "   ");

            // The server would refuse it too. Refusing here means she is told before she waits,
            // not after.
            assertThat(sent).isFalse();
            assertThat(connection.sentCount()).isZero();
            assertThat(session.error()).contains(GradingCopy.JUSTIFICATION_REQUIRED);
        }

        @Test
        @DisplayName("refuses a score outside 0..100 before the request travels")
        void outOfRangeNeverLeaves() {
            givenOpenSitting();
            connection.clearSent();

            assertThat(session.override(1, 101, "because")).isFalse();
            assertThat(session.override(1, -1, "because")).isFalse();
            assertThat(connection.sentCount()).isZero();
            assertThat(session.error()).contains(GradingCopy.SCORE_OUT_OF_RANGE);
        }

        @Test
        @DisplayName("CONFLICT reads as a state, not as a fault")
        void conflictIsAState() {
            givenOpenSitting();
            connection.replyError(Verb.GRADE_OVERRIDE, ErrorCode.CONFLICT, "already approved");

            session.override(1, 80, "question 3 was ambiguous");

            // Overriding an approved grade is refused by design. She is told what happened,
            // not that something broke.
            assertThat(session.error()).contains(GradingCopy.OVERRIDE_CONFLICT);
        }

        @Test
        @DisplayName("re-reads the whole sitting, not just the row that changed")
        void overrideRereadsTheSitting() {
            givenOpenSitting();
            connection.clearSent();
            connection.replyOk(Verb.GRADE_OVERRIDE, null);

            session.override(1, 80, "question 3 was ambiguous");

            // The response carries the refreshed review, but an override moves a score, a state
            // and the summary's counts with it.
            assertThat(connection.sentMessages()).extracting(m -> m.getVerb())
                    .containsSubsequence(Verb.GRADE_OVERRIDE, Verb.GRADING_EXECUTION_GET);
        }
    }

    @Test
    @DisplayName("the screen is told to re-render on every transition")
    void notifiesOnEveryTransition() {
        connection.replyOk(Verb.GRADING_QUEUE_GET, new GradingQueue(List.of(summary(8, 0))));

        session.load();

        assertThat(renders).isEqualTo(2);
    }
}
