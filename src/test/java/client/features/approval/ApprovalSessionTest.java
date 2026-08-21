package client.features.approval;

import client.events.DirectFxThreadPoster;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.approval.ApprovalDecision;
import common.dto.approval.ApprovalQueue;
import common.dto.approval.ApprovalRow;
import common.dto.approval.ApprovalState;
import common.dto.approval.ExamApproveRequest;
import common.dto.approval.ExamPreview;
import common.dto.approval.ExamPreviewRequest;
import common.dto.approval.ExamRejectRequest;
import common.dto.approval.MyApprovals;
import common.dto.approval.PreviewAnswerRow;
import common.dto.approval.TeacherOnlyBlock;
import common.dto.exam.ExamQuestion;
import common.protocol.ErrorCode;
import common.protocol.Verb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three approval screens' behaviour, proven without a JavaFX toolkit (E8.7).
 *
 * <p>Each session talks to a {@link FakeClientConnection} through a real
 * {@link RequestDispatcher}, and the FX hop is a {@link DirectFxThreadPoster}, so every
 * transition settles synchronously (TEAM_SPLIT §3.2). The fixture is the seeded world:
 * {@code dana.cohen}'s Calculus exam 101201 v1, waiting on {@code rina.barak}.
 */
class ApprovalSessionTest {

    private static final Instant SUBMITTED = Instant.parse("2026-08-20T09:00:00Z");
    private static final long CALCULUS_V1 = 31L;

    private static final ApprovalRow PENDING = new ApprovalRow(CALCULUS_V1, "101201",
            "מבחן אמצע — חדו\"א", "12", "חדו\"א", 1, "דנה כהן", SUBMITTED, 2, 60,
            ApprovalState.PENDING, "", false, 0);

    private static final ExamQuestion QUESTION_ONE = new ExamQuestion(901, "12001", 1, 50,
            "שאלה 1", "1, 6", "2, 3", "-2, -3", "0, 5", null);
    private static final ExamQuestion QUESTION_TWO = new ExamQuestion(902, "12002", 2, 50,
            "שאלה 2", "א", "ב", "ג", "ד", null);

    private static final ExamPreview PREVIEW = new ExamPreview(PENDING, "ענו על כל השאלות.",
            List.of(QUESTION_ONE, QUESTION_TWO),
            new TeacherOnlyBlock("For the marker only.", "דנה כהן",
                    List.of(new PreviewAnswerRow(901, 1, (byte) 2),
                            new PreviewAnswerRow(902, 2, (byte) 4))));

    private FakeClientConnection connection;
    private RequestDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        connection = new FakeClientConnection();
        dispatcher = new RequestDispatcher(connection);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
    }

    // ===================== The queue =====================================

    @Nested
    @DisplayName("ApprovalQueueSession")
    class Queue {

        private ApprovalQueueSession session;
        private int renders;

        @BeforeEach
        void openTheQueue() {
            session = new ApprovalQueueSession(dispatcher, new DirectFxThreadPoster())
                    .onChange(() -> renders++);
        }

        @Test
        @DisplayName("a loaded queue renders as content and asks the server once")
        void loadsTheQueue() {
            connection.replyOk(Verb.APPROVALS_QUEUE_GET, new ApprovalQueue(List.of(PENDING), true));

            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.READY);
            assertThat(session.rows()).containsExactly(PENDING);
            assertThat(session.pendingCount()).isEqualTo(1);
            assertThat(session.error()).isEmpty();
            assertThat(connection.sentCount()).isEqualTo(1);
            assertThat(connection.lastSent().getVerb()).isEqualTo(Verb.APPROVALS_QUEUE_GET);
            assertThat(connection.lastSent().getPayload())
                    .as("no user id on the wire: which subjects these are is the session's")
                    .isNull();
        }

        @Test
        @DisplayName("a skeleton while the request is in flight, and no second request behind it")
        void showsSkeletonWhileLoading() {
            session.load();
            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.LOADING);
            assertThat(session.state().showsSkeleton()).isTrue();
            assertThat(connection.sentCount())
                    .as("a second identical request could settle out of order")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a finished inbox and 'you coordinate nothing' are different empty states ⚑")
        void twoEmptyStates() {
            connection.replyOk(Verb.APPROVALS_QUEUE_GET, ApprovalQueue.empty());
            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.EMPTY);
            assertThat(session.emptyTitle()).isEqualTo(ApprovalCopy.QUEUE_EMPTY_TITLE);
            assertThat(session.emptyHint()).isEqualTo(ApprovalCopy.QUEUE_EMPTY_HINT);

            connection.replyOk(Verb.APPROVALS_QUEUE_GET, ApprovalQueue.notACoordinator());
            session.refresh();

            assertThat(session.state()).isEqualTo(AsyncViewState.EMPTY);
            assertThat(session.coordinatesAnything()).isFalse();
            assertThat(session.emptyTitle()).isEqualTo(ApprovalCopy.QUEUE_NOT_COORDINATOR_TITLE);
            assertThat(session.emptyHint()).isEqualTo(ApprovalCopy.QUEUE_NOT_COORDINATOR_HINT);
        }

        @Test
        @DisplayName("a refused load shows a sentence, not a stack trace")
        void errorPath() {
            connection.replyError(Verb.APPROVALS_QUEUE_GET, ErrorCode.FORBIDDEN, "nope");

            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.error()).contains(ApprovalCopy.QUEUE_LOAD_FAILED);
            assertThat(session.rows()).isEmpty();
        }

        @Test
        @DisplayName("an OK carrying the wrong type is treated as a failure, not rendered")
        void wrongPayloadType() {
            connection.replyOk(Verb.APPROVALS_QUEUE_GET, "surprise");

            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);
        }

        @Test
        @DisplayName("a refresh re-queries rather than patching the list it holds")
        void refreshRequeries() {
            connection.replyOk(Verb.APPROVALS_QUEUE_GET, new ApprovalQueue(List.of(PENDING), true));
            session.load();
            connection.replyOk(Verb.APPROVALS_QUEUE_GET, ApprovalQueue.empty());

            session.refresh();

            assertThat(session.rows()).isEmpty();
            assertThat(connection.sentCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("the F4.3 badge follows the server's own answer, never a client comparison")
        void selfAuthoredComesFromTheServer() {
            ApprovalRow hers = new ApprovalRow(61L, "202201", "Databases Final", "22",
                    "Databases", 1, "מיכל שרון", SUBMITTED, 3, 60,
                    ApprovalState.PENDING, "", true, 0);
            connection.replyOk(Verb.APPROVALS_QUEUE_GET, new ApprovalQueue(List.of(PENDING, hers), true));

            session.load();

            assertThat(session.isSelfAuthored(PENDING)).isFalse();
            assertThat(session.isSelfAuthored(hers)).isTrue();
        }
    }

    // ===================== The preview and the decisions =================

    @Nested
    @DisplayName("ExamPreviewSession")
    class Preview {

        private ExamPreviewSession session;
        private final List<ApprovalDecision> decisions = new ArrayList<>();

        @BeforeEach
        void openThePreview() {
            session = new ExamPreviewSession(dispatcher, new DirectFxThreadPoster())
                    .onDecided(decisions::add);
        }

        private void loadPreview() {
            connection.replyOk(Verb.EXAM_PREVIEW_GET, PREVIEW);
            session.open(CALCULUS_V1);
            connection.clearSent();
        }

        @Test
        @DisplayName("the paper arrives as the student's own type, key beside it ⚑")
        void loadsTheStudentPaper() {
            connection.replyOk(Verb.EXAM_PREVIEW_GET, PREVIEW);

            session.open(CALCULUS_V1);

            assertThat(session.state()).isEqualTo(AsyncViewState.READY);
            assertThat(session.questions()).containsExactly(QUESTION_ONE, QUESTION_TWO);
            assertThat(session.correctOptionFor(QUESTION_ONE)).isEqualTo(2);
            assertThat(session.correctOptionFor(QUESTION_TWO)).isEqualTo(4);
            assertThat(connection.lastSent().getPayload())
                    .isEqualTo(new ExamPreviewRequest(CALCULUS_V1));
        }

        @Test
        @DisplayName("a question with no key entry reads as 'not available', never as option 0")
        void missingKeyEntry() {
            connection.replyOk(Verb.EXAM_PREVIEW_GET, PREVIEW);
            session.open(CALCULUS_V1);

            ExamQuestion stranger = new ExamQuestion(999, "99999", 3, 0,
                    "not on this paper", "a", "b", "c", "d", null);
            assertThat(session.correctOptionFor(stranger)).isZero();
        }

        @Test
        @DisplayName("a failed load says so and leaves nothing decidable")
        void failedLoad() {
            connection.replyError(Verb.EXAM_PREVIEW_GET, ErrorCode.FORBIDDEN, "not yours");

            session.open(CALCULUS_V1);

            assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.error()).contains(ApprovalCopy.PREVIEW_LOAD_FAILED);
            assertThat(session.canDecide()).isFalse();
            assertThat(session.preview()).isEmpty();
        }

        @Test
        @DisplayName("approving sends the lock version the screen was rendered from ⚑")
        void approveEchoesTheLock() {
            ApprovalRow atLockThree = new ApprovalRow(CALCULUS_V1, "101201", "מבחן אמצע — חדו\"א",
                    "12", "חדו\"א", 1, "דנה כהן", SUBMITTED, 2, 60,
                    ApprovalState.PENDING, "", false, 3);
            connection.replyOk(Verb.EXAM_PREVIEW_GET, new ExamPreview(atLockThree, "", List.of(), null));
            session.open(CALCULUS_V1);
            connection.clearSent();
            connection.replyOk(Verb.EXAM_APPROVE, decided(ApprovalState.APPROVED, false));

            session.approve();

            assertThat(connection.lastSent().getVerb()).isEqualTo(Verb.EXAM_APPROVE);
            assertThat(connection.lastSent().getPayload())
                    .isEqualTo(new ExamApproveRequest(CALCULUS_V1, 3));
        }

        @Test
        @DisplayName("a landed decision is handed to the screen, which is what closes it")
        void decisionIsAnnounced() {
            loadPreview();
            connection.replyOk(Verb.EXAM_APPROVE, decided(ApprovalState.APPROVED, false));

            session.approve();

            assertThat(decisions).hasSize(1);
            assertThat(decisions.get(0).state()).isEqualTo(ApprovalState.APPROVED);
            assertThat(decisions.get(0).confirmation()).contains("can be released now");
            assertThat(session.decisionError()).isEmpty();
        }

        @Test
        @DisplayName("nothing is sent before a preview is loaded: there would be no lock to echo")
        void noDecisionWithoutAPreview() {
            session.approve();
            session.reject("A perfectly good reason, at length.");

            assertThat(connection.sentCount()).isZero();
        }

        @Test
        @DisplayName("only one decision at a time, because both write the same column")
        void oneDecisionAtATime() {
            loadPreview();

            session.approve();
            session.approve();

            assertThat(session.isDeciding()).isTrue();
            assertThat(connection.sentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a reason under the minimum never reaches the server ⚑")
        void shortReasonIsCaughtLocally() {
            loadPreview();

            session.reject("no");

            assertThat(connection.sentCount()).isZero();
            assertThat(session.decisionError()).contains(ExamRejectRequest.REASON_TOO_SHORT);
        }

        @Test
        @DisplayName("an empty reason never reaches the server either (T-4.2)")
        void emptyReasonIsCaughtLocally() {
            loadPreview();

            session.reject("   ");

            assertThat(connection.sentCount()).isZero();
            assertThat(session.decisionError()).contains(ExamRejectRequest.REASON_REQUIRED);
        }

        @Test
        @DisplayName("a real reason goes out trimmed, with the lock version")
        void realReasonIsSent() {
            loadPreview();
            connection.replyOk(Verb.EXAM_REJECT, decided(ApprovalState.REJECTED, false));

            session.reject("  Question 4 has two correct answers.  ");

            assertThat(connection.lastSent().getPayload())
                    .isEqualTo(new ExamRejectRequest(CALCULUS_V1,
                            "Question 4 has two correct answers.", 0));
            assertThat(decisions).hasSize(1);
        }

        @Test
        @DisplayName("a CONFLICT shows the server's sentence and reloads, rather than retrying ⚑")
        void conflictReloads() {
            loadPreview();
            connection.replyError(Verb.EXAM_APPROVE, ErrorCode.CONFLICT,
                    "This exam changed while you were looking at it.");

            session.approve();

            assertThat(session.decisionError())
                    .as("the server knows which refusal this is; we do not paraphrase it")
                    .contains("This exam changed while you were looking at it.");
            assertThat(decisions).isEmpty();
            assertThat(connection.sentMessages()).extracting(m -> m.getVerb())
                    .as("the approve, then a fresh read: the screen is holding a stale row")
                    .containsExactly(Verb.EXAM_APPROVE, Verb.EXAM_PREVIEW_GET);
        }

        @Test
        @DisplayName("a decided version cannot be decided again from the same screen")
        void decidedVersionIsNotDecidable() {
            ApprovalRow approved = new ApprovalRow(CALCULUS_V1, "101201", "מבחן אמצע — חדו\"א",
                    "12", "חדו\"א", 1, "דנה כהן", SUBMITTED, 2, 60,
                    ApprovalState.APPROVED, "", false, 1);
            connection.replyOk(Verb.EXAM_PREVIEW_GET, new ExamPreview(approved, "", List.of(), null));

            session.open(CALCULUS_V1);

            assertThat(session.canDecide()).isFalse();
        }

        @Test
        @DisplayName("the F4.3 flag comes off the loaded row")
        void selfAuthoredFlag() {
            assertThat(session.isSelfAuthored()).isFalse();
            loadPreview();
            assertThat(session.isSelfAuthored()).isFalse();

            ApprovalRow hers = new ApprovalRow(61L, "202201", "Databases Final", "22",
                    "Databases", 1, "מיכל שרון", SUBMITTED, 3, 60,
                    ApprovalState.PENDING, "", true, 0);
            connection.replyOk(Verb.EXAM_PREVIEW_GET, new ExamPreview(hers, "", List.of(), null));
            session.reload();

            assertThat(session.isSelfAuthored()).isTrue();
        }

        private ApprovalDecision decided(ApprovalState state, boolean self) {
            return new ApprovalDecision(new ApprovalRow(CALCULUS_V1, "101201",
                    "מבחן אמצע — חדו\"א", "12", "חדו\"א", 1, "דנה כהן", SUBMITTED, 2, 60,
                    state, state.isRejected() ? "Question 4 has two correct answers." : "",
                    self, 1), self);
        }
    }

    // ===================== The author's own list =========================

    @Nested
    @DisplayName("MyApprovalsSession")
    class Mine {

        private MyApprovalsSession session;

        @BeforeEach
        void openTheList() {
            session = new MyApprovalsSession(dispatcher, new DirectFxThreadPoster());
        }

        private ApprovalRow rejected(long versionId, String reason) {
            return new ApprovalRow(versionId, "101101", "מבחן אמצע — אלגברה", "11", "אלגברה",
                    1, "דנה כהן", SUBMITTED, 5, 60, ApprovalState.REJECTED, reason, true, 1);
        }

        @Test
        @DisplayName("the list loads and the rejected ones are separable")
        void loadsTheList() {
            ApprovalRow sentBack = rejected(11L, "חמש שאלות בלבד ל-60 דקות.");
            connection.replyOk(Verb.MY_APPROVALS_GET, new MyApprovals(List.of(sentBack, PENDING)));

            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.READY);
            assertThat(session.rows()).hasSize(2);
            assertThat(session.rejected()).containsExactly(sentBack);
        }

        @Test
        @DisplayName("a teacher who has submitted nothing gets an explanation, not a blank panel")
        void emptyState() {
            connection.replyOk(Verb.MY_APPROVALS_GET, MyApprovals.empty());

            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.EMPTY);
            assertThat(session.rows()).isEmpty();
        }

        @Test
        @DisplayName("a failed load says so")
        void errorPath() {
            connection.replyError(Verb.MY_APPROVALS_GET, ErrorCode.INTERNAL, "boom");

            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.error()).contains(ApprovalCopy.MINE_LOAD_FAILED);
        }

        @Test
        @DisplayName("a notification's version is the one that opens (F4.2's deep link) ⚑")
        void notificationSelectsItsVersion() {
            ApprovalRow first = rejected(11L, "First reason, long enough to count.");
            ApprovalRow second = rejected(12L, "Second reason, also long enough.");
            connection.replyOk(Verb.MY_APPROVALS_GET, new MyApprovals(List.of(first, second)));

            session.selectedVersionId(12L);
            session.load();

            assertThat(session.focused()).contains(second);
            assertThat(session.focusedRejectionReason())
                    .contains("Second reason, also long enough.");
        }

        @Test
        @DisplayName("a reference to something no longer listed falls back rather than erroring")
        void danglingReferenceFallsBack() {
            ApprovalRow sentBack = rejected(11L, "The one reason there is.");
            connection.replyOk(Verb.MY_APPROVALS_GET, new MyApprovals(List.of(PENDING, sentBack)));

            session.selectedVersionId(9_999L);
            session.load();

            assertThat(session.focused())
                    .as("notifications outlive what they point at; the screen still works")
                    .contains(sentBack);
        }

        @Test
        @DisplayName("with nothing rejected there is no reason panel to draw")
        void noRejectionNoPanel() {
            connection.replyOk(Verb.MY_APPROVALS_GET, new MyApprovals(List.of(PENDING)));

            session.load();

            assertThat(session.focused()).contains(PENDING);
            assertThat(session.focusedRejectionReason())
                    .as("a heading with nothing under it is a mystery state")
                    .isEmpty();
        }

        @Test
        @DisplayName("an empty list has nothing focused at all")
        void nothingFocusedWhenEmpty() {
            connection.replyOk(Verb.MY_APPROVALS_GET, MyApprovals.empty());

            session.load();

            assertThat(session.focused()).isEmpty();
            assertThat(session.focusedRejectionReason()).isEmpty();
        }

        @Test
        @DisplayName("a push re-queries rather than patching the row it was handed (NFR-18)")
        void decisionPushRequeries() {
            connection.replyOk(Verb.MY_APPROVALS_GET, new MyApprovals(List.of(PENDING)));
            session.load();
            connection.replyOk(Verb.MY_APPROVALS_GET,
                    new MyApprovals(List.of(rejected(11L, "Please add a fourth question."))));

            session.onDecisionArrived();

            assertThat(session.rejected()).hasSize(1);
            assertThat(connection.sentCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("no second request while one is in flight")
        void noDoubleLoad() {
            session.load();
            session.load();

            assertThat(session.isLoading()).isTrue();
            assertThat(connection.sentCount()).isEqualTo(1);
        }
    }
}
