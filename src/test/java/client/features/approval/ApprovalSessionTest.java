package client.features.approval;

import client.events.ClientEventBus;
import client.events.DirectFxThreadPoster;
import client.events.PushEventBridge;
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
import common.dto.approval.PreviewAnswerRow;
import common.dto.approval.TeacherOnlyBlock;
import common.dto.exam.ExamQuestion;
import common.dto.notify.NavRef;
import common.dto.notify.NotificationDto;
import common.dto.notify.NotificationType;
import common.protocol.ErrorCode;
import common.protocol.Message;
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
 * The approval screens' behaviour, proven without a JavaFX toolkit (E8.7).
 *
 * <p>Two sessions now rather than three: the author's own list retired into E7.10's exam list
 * with {@code MY_APPROVALS_GET} (APPROVAL ruling 1), and {@code ExamListSessionTest} is where
 * that behaviour is measured.
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

    /** A second version, so two in-flight previews can be told apart by what arrived. */
    private static final long ALGEBRA_V2 = 42L;
    private static final ApprovalRow OTHER_PENDING = new ApprovalRow(ALGEBRA_V2, "101101",
            "מבחן אמצע — אלגברה", "11", "אלגברה", 2, "דנה כהן", SUBMITTED, 2, 60,
            ApprovalState.PENDING, "", false, 0);
    private static final ExamPreview OTHER_PREVIEW = new ExamPreview(OTHER_PENDING,
            "ענו על כל השאלות.", List.of(QUESTION_ONE),
            new TeacherOnlyBlock("For the marker only.", "דנה כהן",
                    List.of(new PreviewAnswerRow(901, 1, (byte) 2))));

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
            // A real bus, so the B-30 tests exercise the registration and not a method call.
            ClientEventBus eventBus =
                    new ClientEventBus(ClientEventBus.newBus(), new DirectFxThreadPoster());
            dispatcher.setPushListener(new PushEventBridge(eventBus));
            session = new ApprovalQueueSession(dispatcher, new DirectFxThreadPoster())
                    .onChange(() -> renders++)
                    .subscribeTo(eventBus);
        }

        /** One notification of the given type, as the server sends it to a coordinator. */
        private NotificationDto notification(NotificationType type) {
            return new NotificationDto(1L, type, "An exam is waiting for your approval", "",
                    NavRef.to("approvals", CALCULUS_V1), SUBMITTED, null);
        }

        private long queueReads() {
            return connection.sentMessages().stream()
                    .filter(message -> message.getVerb() == Verb.APPROVALS_QUEUE_GET)
                    .count();
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

        /**
         * B-30's proof, and it is driven through the REAL bus rather than by calling the
         * method. Before this batch {@code ApprovalQueueSession} had no {@code @Subscribe} at
         * all: acceptance case 18.2 watched the coordinator's bell badge increment while the
         * list beneath it stayed exactly as it was. Nothing could have failed for that, which
         * is why the test has to push a real {@code NotificationDto} onto a real bus.
         */
        @Test
        @DisplayName("an APPROVAL_REQUESTED push re-asks the queue with no user action ⚑ (B-30)")
        void anArrivingExamRefreshesTheQueue() {
            connection.replyOk(Verb.APPROVALS_QUEUE_GET, ApprovalQueue.empty());
            session.load();
            assertThat(session.rows()).isEmpty();
            connection.replyOk(Verb.APPROVALS_QUEUE_GET,
                    new ApprovalQueue(List.of(PENDING), true));

            connection.pushToClient(Verb.PUSH_NOTIFICATION,
                    notification(NotificationType.APPROVAL_REQUESTED));

            assertThat(queueReads())
                    .as("she pressed nothing: NFR-18 on the one screen that is an inbox")
                    .isEqualTo(2);
            assertThat(session.rows()).containsExactly(PENDING);
        }

        @Test
        @DisplayName("a supersede re-asks too: it takes a row away rather than adding one")
        void aSupersedeRefreshesTheQueue() {
            connection.replyOk(Verb.APPROVALS_QUEUE_GET,
                    new ApprovalQueue(List.of(PENDING), true));
            session.load();

            connection.pushToClient(Verb.PUSH_NOTIFICATION,
                    notification(NotificationType.APPROVAL_SUPERSEDED));

            assertThat(queueReads()).isEqualTo(2);
        }

        @Test
        @DisplayName("a push about something else does not re-query the queue ⚑")
        void unrelatedPushesAreIgnored() {
            connection.replyOk(Verb.APPROVALS_QUEUE_GET,
                    new ApprovalQueue(List.of(PENDING), true));
            session.load();

            connection.pushToClient(Verb.PUSH_NOTIFICATION,
                    notification(NotificationType.GRADE_PUBLISHED));
            connection.pushToClient(Verb.PUSH_NOTIFICATION,
                    notification(NotificationType.APPROVAL_APPROVED));
            connection.pushToClient(Verb.PUSH_GRADE_PUBLISHED, "not a notification");

            assertThat(queueReads())
                    .as("PUSH_NOTIFICATION carries every kind this app has, and a decision on "
                            + "her own exam is the author's news rather than the queue's")
                    .isEqualTo(1);
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

        /**
         * ⚑ The 4.1 defect, and the reason the whole session sweep happened.
         *
         * <p>{@code ExamPreviewView} is built once and calls {@code session.open(id)} from
         * {@code onShow}, which runs on <b>every</b> navigation. The old guard was
         * {@code if (state == LOADING) return}, so a coordinator who opened version A, went back
         * to the queue and opened version B before A answered had her request for B dropped on
         * the floor — and then watched A paint itself onto the screen she had asked for B on,
         * with Approve and Reject wired to A.
         *
         * <p>Two assertions, because the fix has two halves: the request for B must travel, and
         * A's answer must lose when it eventually lands.
         */
        @Test
        @DisplayName("⚑ a second version opened mid-flight travels, and the first answer loses")
        void aLateAnswerForAnotherVersionIsDropped() {
            // No responder, so both futures stay pending and the answers can be delivered in the
            // order the network chose rather than the order the clicks did.
            session.open(CALCULUS_V1);
            session.open(ALGEBRA_V2);

            assertThat(connection.sentCount())
                    .as("the version she actually asked for has to be requested; dropping it is "
                            + "how she ends up reviewing the exam she left")
                    .isEqualTo(2);
            assertThat(connection.lastSent().getPayload())
                    .isEqualTo(new ExamPreviewRequest(ALGEBRA_V2));

            connection.deliver(Message.ok(connection.sentMessages().get(1), OTHER_PREVIEW));
            connection.deliver(Message.ok(connection.sentMessages().get(0), PREVIEW));

            assertThat(session.preview()).isPresent();
            assertThat(session.preview().orElseThrow().summary().examVersionId())
                    .as("the paper on screen has to be the one she opened, and the decision "
                            + "buttons read their lockVersion off exactly this object")
                    .isEqualTo(ALGEBRA_V2);
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
}
