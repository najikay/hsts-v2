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
import common.protocol.Message;
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
    private static final long OTHER_EXECUTION = 4823;

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

    /** A second sitting, so two in-flight answers can be told apart by what arrived. */
    private static ExecutionGradingSummary otherSummary() {
        return new ExecutionGradingSummary(OTHER_EXECUTION, "Databases final", "22", "5164",
                Instant.parse("2026-06-04T10:00:00Z"), 8, 1, 0);
    }

    private static ExecutionGrades otherRows() {
        return new ExecutionGrades(otherSummary(), List.of(
                row(3, "יעל אזולאי", 80, GradeState.AUTO)));
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

        /**
         * ⚑ The generation-guard sweep. {@code GradingQueueView} wires {@code openExecution} to
         * the queue list's <b>selection</b> listener, so holding the arrow key down the rail
         * fires one request per row and every one of them is in flight at once. Nothing checked
         * which sitting an arriving answer was about, so whichever the network delivered last
         * became the open sitting — and the teacher approved one exam's grades while reading
         * another exam's row.
         */
        @Test
        @DisplayName("⚑ an answer for the sitting she left loses to the one she opened")
        void aLateAnswerForAnotherSittingIsDropped() {
            connection.replyOk(Verb.GRADING_QUEUE_GET,
                    new GradingQueue(List.of(summary(2, 0), otherSummary())));
            session.load();
            connection.clearSent();

            // No responder from here on, so both futures stay pending.
            session.openExecution(summary(2, 0));
            session.openExecution(otherSummary());

            connection.deliver(Message.ok(connection.sentMessages().get(1), otherRows()));
            connection.deliver(Message.ok(connection.sentMessages().get(0), twoRows()));

            assertThat(session.openExecution()).isPresent();
            assertThat(session.openExecution().orElseThrow().summary().executionId())
                    .as("the rows on screen must belong to the sitting the rail has selected")
                    .isEqualTo(OTHER_EXECUTION);
            assertThat(session.rows()).hasSize(1);
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

    // ===================== Choosing rows ==================================

    @Nested
    @DisplayName("Choosing rows (2026-08-30, live session, U-46)")
    class ChoosingRows {

        /** One paper already published among two still waiting: what Select all is about. */
        private void givenOneAlreadyApproved() {
            connection.replyOk(Verb.GRADING_QUEUE_GET, new GradingQueue(List.of(summary(3, 1))));
            session.load();
            connection.replyOk(Verb.GRADING_EXECUTION_GET,
                    new ExecutionGrades(summary(3, 1), List.of(
                            row(1, "מאיה לוי", 100, GradeState.AUTO),
                            row(2, "עומר כץ", 40, GradeState.APPROVED),
                            row(3, "יעל אזולאי", 80, GradeState.AUTO))));
            session.openExecution(summary(3, 1));
        }

        @Test
        @DisplayName("Select all ticks every row still waiting, and only those")
        void ticksTheApprovable() {
            givenOneAlreadyApproved();

            session.selectAllApprovable();

            // The published one is skipped rather than ticked and refused. Re-approving is
            // harmless by contract, but the confirmation counts ticks and would overstate.
            assertThat(session.selection()).containsExactly(1L, 3L);
        }

        @Test
        @DisplayName("it replaces what was ticked rather than adding to it")
        void replacesRatherThanAdds() {
            givenOneAlreadyApproved();
            session.select(3, true);

            session.selectAllApprovable();

            // "Select all" twice is still all of them, not two of some of them.
            assertThat(session.selection()).containsExactly(1L, 3L);
        }

        @Test
        @DisplayName("with no sitting open it ticks nothing rather than throwing")
        void nothingOpen() {
            session.selectAllApprovable();

            assertThat(session.selection()).isEmpty();
        }

        @Test
        @DisplayName("it redraws, because the checkboxes are drawn from here")
        void redraws() {
            givenOneAlreadyApproved();
            int before = renders;

            session.selectAllApprovable();

            assertThat(renders).isGreaterThan(before);
        }

        @Test
        @DisplayName("isSelected answers per row, which is what one checkbox asks")
        void isSelectedIsPerRow() {
            givenOpenSitting();
            session.select(2, true);

            // The column renders itself from the session rather than remembering its own ticks,
            // so a re-read that clears the selection empties the boxes with it.
            assertThat(session.isSelected(2)).isTrue();
            assertThat(session.isSelected(1)).isFalse();
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
        @DisplayName("sends the comment beside the reason when she wrote one (S-22)")
        void sendsTheComment() {
            givenOpenSitting();
            connection.clearSent();
            connection.replyOk(Verb.GRADE_OVERRIDE, null);

            boolean sent = session.override(1, 80, "question 3 was ambiguous", "שיפור ניכר.");

            assertThat(sent).isTrue();
            assertThat(connection.sentMessages().get(0).getPayload())
                    .isEqualTo(new GradeOverrideRequest(1, 80, "question 3 was ambiguous",
                            "שיפור ניכר."));
        }

        @Test
        @DisplayName("the comment is optional: neither a missing nor a blank one is refused")
        void commentIsOptional() {
            // The reason has a client-side check and the comment deliberately has none. A
            // teacher who only wants to move a mark is never made to write to the student.
            givenOpenSitting();
            connection.replyOk(Verb.GRADE_OVERRIDE, null);

            assertThat(session.override(1, 80, "question 3 was ambiguous", null)).isTrue();
            assertThat(session.override(1, 80, "question 3 was ambiguous", "   ")).isTrue();
            assertThat(session.error()).isEmpty();
        }

        @Test
        @DisplayName("a blank comment becomes null rather than blanking what is saved")
        void blankCommentTravelsAsNull() {
            // The screen does not check this and must not: the record collapses it, so both
            // tiers agree about what "she wrote nothing" is. Null then preserves server-side,
            // which is what GradingCopy.COMMENT_LABEL promises her.
            givenOpenSitting();
            connection.clearSent();
            connection.replyOk(Verb.GRADE_OVERRIDE, null);

            session.override(1, 80, "question 3 was ambiguous", "   ");

            assertThat(connection.sentMessages().get(0).getPayload())
                    .isEqualTo(new GradeOverrideRequest(1, 80, "question 3 was ambiguous", null));
        }

        @Test
        @DisplayName("the reason is still mandatory even when she wrote a comment (S-23)")
        void commentDoesNotExcuseABlankReason() {
            givenOpenSitting();
            connection.clearSent();

            boolean sent = session.override(1, 80, "  ", "כל הכבוד!");

            assertThat(sent).isFalse();
            assertThat(connection.sentCount()).isZero();
            assertThat(session.error()).contains(GradingCopy.JUSTIFICATION_REQUIRED);
        }

        @Test
        @DisplayName("the three-argument call still means an override with no comment")
        void theOldCallStillWorks() {
            givenOpenSitting();
            connection.clearSent();
            connection.replyOk(Verb.GRADE_OVERRIDE, null);

            session.override(1, 80, "question 3 was ambiguous");

            assertThat(connection.sentMessages().get(0).getPayload())
                    .isEqualTo(new GradeOverrideRequest(1, 80, "question 3 was ambiguous", null));
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
