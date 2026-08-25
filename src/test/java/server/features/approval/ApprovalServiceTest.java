package server.features.approval;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import common.dto.approval.ApprovalDecision;
import common.dto.approval.ApprovalQueue;
import common.dto.approval.ApprovalRow;
import common.dto.approval.ApprovalState;
import common.dto.approval.ExamApproveRequest;
import common.dto.approval.ExamPreview;
import common.dto.approval.ExamPreviewRequest;
import common.dto.approval.ExamRejectRequest;
import common.dto.approval.PreviewAnswerRow;
import common.dto.auth.Role;
import common.dto.exam.ExamQuestion;
import common.dto.notify.NotificationType;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import ocsf.server.ConnectionToClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import server.core.AuthorizationException;
import server.core.CallerContext;
import server.core.MessageRouter;
import server.core.SessionManager;
import server.db.entities.ExamVersionStatus;
import server.db.projections.TakeExamQuestion;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Every rule of the approval workflow, against an in-memory store (E8.7 — F4).
 *
 * <p>No database, no socket, no session: {@link InMemoryApprovalStore} supplies the data and
 * {@link RecordingNotifier} records who was told, which is what makes the negative cases
 * cheap enough to write all of them. The SQL behind these rules is proved separately by
 * {@code ApprovalRepositoryContract} on both engines.
 *
 * <p>The one test here that is an interface rather than a check is
 * {@link SelfApproval#selfApprovalIsLoggedInAStableShape()}: acceptance case 4.6 inspects the
 * server log for that line, so its shape is pinned rather than left to whoever next edits the
 * method.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalServiceTest {

    private static final Instant SUBMITTED = Instant.parse("2026-08-20T09:00:00Z");

    private static final String SUBJECT_MATH = "10";
    private static final String SUBJECT_CS = "20";

    private static final long RINA = 3L;      // coordinates Mathematics, teaches nothing
    private static final long DANA = 2L;      // teaches Algebra and Calculus
    private static final long MICHAL = 6L;    // coordinates CS and is the only Databases teacher

    /** exam 3, 101201 v1 PENDING, written by Dana, awaiting Rina (seed §8). */
    private static final long CALCULUS_V1 = 31L;

    /** exam 6, 202201 v1 PENDING, written by Michal, awaiting Michal herself (F4.3). */
    private static final long DATABASES_V1 = 61L;

    @Mock
    private ConnectionToClient connection;

    private InMemoryApprovalStore store;
    private RecordingNotifier notifier;
    private ApprovalService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryApprovalStore()
                .coordinator(SUBJECT_MATH, RINA)
                .coordinator(SUBJECT_CS, MICHAL)
                .version(CALCULUS_V1, 3L, "101201", "מבחן אמצע — חדו\"א", 1,
                        ExamVersionStatus.PENDING, "12", "חדו\"א", SUBJECT_MATH,
                        DANA, "דנה כהן", SUBMITTED)
                .version(DATABASES_V1, 6L, "202201", "Databases Final", 1,
                        ExamVersionStatus.PENDING, "22", "Databases", SUBJECT_CS,
                        MICHAL, "מיכל שרון", SUBMITTED)
                .paper(CALCULUS_V1, List.of(question(901, 1), question(902, 2)),
                        List.of(new PreviewAnswerRow(901, 1, (byte) 2),
                                new PreviewAnswerRow(902, 2, (byte) 4)));

        notifier = new RecordingNotifier();
        service = new ApprovalService(store, notifier);
    }

    private static TakeExamQuestion question(long versionId, int ordinal) {
        return new TakeExamQuestion(versionId, "1200" + ordinal, ordinal, 50,
                "שאלה " + ordinal, "1, 6", "2, 3", "-2, -3", "0, 5", null);
    }

    private CallerContext caller(long userId, Role role) {
        return CallerContext.authenticated(connection, userId, role);
    }

    private static Message request(Verb verb, Object payload) {
        return Message.request(verb, payload);
    }

    // ===================== Registration ==================================

    @Test
    @DisplayName("all four verbs are registered, and none of them is open")
    void registersItsVerbs() {
        MessageRouter router = new MessageRouter(new SessionManager());

        service.registerOn(router);

        // Four since 2026-08-25: MY_APPROVALS_GET retired into E7.10's EXAM_LIST, which
        // ExamService registers (APPROVAL ruling 1).
        for (Verb verb : List.of(Verb.APPROVALS_QUEUE_GET, Verb.EXAM_PREVIEW_GET,
                Verb.EXAM_APPROVE, Verb.EXAM_REJECT)) {
            assertThat(router.isRegistered(verb)).as("%s registered", verb).isTrue();
            assertThat(router.isOpen(verb)).as("%s must need a session", verb).isFalse();
        }
    }

    // ===================== The queue =====================================

    @Nested
    @DisplayName("APPROVALS_QUEUE_GET")
    class Queue {

        @Test
        @DisplayName("a coordinator sees the pending versions of her own subject")
        void herOwnSubject() {
            Message response = service.queue(caller(RINA, Role.COORDINATOR),
                    request(Verb.APPROVALS_QUEUE_GET, null));

            assertThat(response.isOk()).isTrue();
            ApprovalQueue queue = (ApprovalQueue) response.getPayload();
            assertThat(queue.rows()).extracting(ApprovalRow::examVersionId)
                    .containsExactly(CALCULUS_V1);
            assertThat(queue.rows().get(0).authorName()).isEqualTo("דנה כהן");
            assertThat(queue.rows().get(0).questionCount()).isEqualTo(2);
            assertThat(queue.rows().get(0).state()).isEqualTo(ApprovalState.PENDING);
        }

        @Test
        @DisplayName("and cannot see another subject's, which is the negative half of that ⚑")
        void notSomebodyElsesSubject() {
            // Michal coordinates Computer Science. Dana's Calculus exam is Mathematics, and
            // it must not appear in her queue at all — not filtered out afterwards, absent.
            Message response = service.queue(caller(MICHAL, Role.COORDINATOR),
                    request(Verb.APPROVALS_QUEUE_GET, null));

            ApprovalQueue queue = (ApprovalQueue) response.getPayload();
            assertThat(queue.rows()).extracting(ApprovalRow::examVersionId)
                    .containsExactly(DATABASES_V1)
                    .doesNotContain(CALCULUS_V1);
        }

        @Test
        @DisplayName("a teacher who is not a coordinator is refused, not shown an empty list")
        void teachersAreRefused() {
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> service.queue(caller(DANA, Role.TEACHER),
                            request(Verb.APPROVALS_QUEUE_GET, null)))
                    .satisfies(e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        }

        @Test
        @DisplayName("a student is refused too")
        void studentsAreRefused() {
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> service.queue(caller(99L, Role.STUDENT),
                            request(Verb.APPROVALS_QUEUE_GET, null)));
        }

        @Test
        @DisplayName("an empty queue and 'you coordinate nothing' are different answers")
        void twoDifferentEmptyStates() {
            // Rina decides the one exam waiting on her: her inbox is finished.
            service.approve(caller(RINA, Role.COORDINATOR),
                    request(Verb.EXAM_APPROVE, new ExamApproveRequest(CALCULUS_V1, 0)));
            ApprovalQueue finished = (ApprovalQueue) service.queue(caller(RINA, Role.COORDINATOR),
                    request(Verb.APPROVALS_QUEUE_GET, null)).getPayload();

            // A COORDINATOR session whose coordinators row is gone: a different situation.
            ApprovalQueue stale = (ApprovalQueue) service.queue(caller(404L, Role.COORDINATOR),
                    request(Verb.APPROVALS_QUEUE_GET, null)).getPayload();

            assertThat(finished.isEmpty()).isTrue();
            assertThat(finished.coordinatesAnything()).isTrue();
            assertThat(stale.isEmpty()).isTrue();
            assertThat(stale.coordinatesAnything()).isFalse();
        }
    }

    // ===================== The preview ===================================

    @Nested
    @DisplayName("EXAM_PREVIEW_GET")
    class Preview {

        @Test
        @DisplayName("the paper is the student's own wire type, and the key is beside it ⚑")
        void studentPaperPlusTeacherBlock() {
            Message response = service.preview(caller(RINA, Role.COORDINATOR),
                    request(Verb.EXAM_PREVIEW_GET, new ExamPreviewRequest(CALCULUS_V1)));

            assertThat(response.isOk()).isTrue();
            ExamPreview preview = (ExamPreview) response.getPayload();

            assertThat(preview.questions()).hasSize(2);
            assertThat(preview.questions().get(0)).isInstanceOf(ExamQuestion.class);
            assertThat(preview.questions().get(0).option(1)).isEqualTo("1, 6");
            assertThat(preview.totalPoints()).isEqualTo(100);
            assertThat(preview.studentText()).isEqualTo("ענו על כל השאלות.");

            assertThat(preview.teacherOnly().teacherText()).isEqualTo("For the marker only.");
            assertThat(preview.teacherOnly().authorName()).isEqualTo("דנה כהן");
            assertThat(preview.teacherOnly().correctOptionOf(901)).isEqualTo(2);
            assertThat(preview.teacherOnly().correctOptionOf(902)).isEqualTo(4);
        }

        @Test
        @DisplayName("the summary carries the lock version the decision has to echo")
        void summaryCarriesTheLock() {
            store.bumpLock(CALCULUS_V1);

            ExamPreview preview = (ExamPreview) service.preview(caller(RINA, Role.COORDINATOR),
                    request(Verb.EXAM_PREVIEW_GET, new ExamPreviewRequest(CALCULUS_V1)))
                    .getPayload();

            assertThat(preview.summary().lockVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("a plain teacher who neither authored nor coordinates is refused ⚑")
        void notAnUninvolvedTeachers() {
            // MICHAL teaches Databases and coordinates CS; CALCULUS_V1 is neither hers
            // nor her subject's. The licence names two audiences and she is a third.
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> service.preview(caller(MICHAL, Role.TEACHER),
                            request(Verb.EXAM_PREVIEW_GET, new ExamPreviewRequest(CALCULUS_V1))))
                    .satisfies(e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        }

        @Test
        @DisplayName("the version's own author may read it back, which is what makes a reason actionable")
        void theAuthorMayReadItBack() {
            Message response = service.preview(caller(DANA, Role.TEACHER),
                    request(Verb.EXAM_PREVIEW_GET, new ExamPreviewRequest(CALCULUS_V1)));

            assertThat(response.isOk()).isTrue();
            assertThat(((ExamPreview) response.getPayload()).summary().selfAuthored()).isTrue();
        }

        @Test
        @DisplayName("another subject's coordinator cannot open it ⚑")
        void notSomebodyElsesExam() {
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> service.preview(caller(MICHAL, Role.COORDINATOR),
                            request(Verb.EXAM_PREVIEW_GET, new ExamPreviewRequest(CALCULUS_V1))))
                    .satisfies(e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN))
                    .withMessageContaining(SUBJECT_MATH);
        }

        @Test
        @DisplayName("an unknown version is NOT_FOUND with a sentence saying where to go")
        void unknownVersion() {
            Message response = service.preview(caller(RINA, Role.COORDINATOR),
                    request(Verb.EXAM_PREVIEW_GET, new ExamPreviewRequest(9_999L)));

            assertThat(response.isError()).isTrue();
            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(response.errorMessage()).isEqualTo(ApprovalMessages.VERSION_UNKNOWN);
        }

        @Test
        @DisplayName("a payload of the wrong type is VALIDATION rather than a stack trace")
        void malformedPayload() {
            Message response = service.preview(caller(RINA, Role.COORDINATOR),
                    request(Verb.EXAM_PREVIEW_GET, "not a request"));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(response.errorMessage()).isEqualTo(ApprovalMessages.MALFORMED_REQUEST);
        }
    }

    // ===================== Approve =======================================

    @Nested
    @DisplayName("EXAM_APPROVE")
    class Approve {

        @Test
        @DisplayName("PENDING becomes APPROVED and the author is told she can release it")
        void approves() {
            Message response = service.approve(caller(RINA, Role.COORDINATOR),
                    request(Verb.EXAM_APPROVE, new ExamApproveRequest(CALCULUS_V1, 0)));

            assertThat(response.isOk()).isTrue();
            ApprovalDecision decision = (ApprovalDecision) response.getPayload();
            assertThat(decision.state()).isEqualTo(ApprovalState.APPROVED);
            assertThat(decision.selfApproved()).isFalse();
            assertThat(store.statusOf(CALCULUS_V1)).isEqualTo(ExamVersionStatus.APPROVED);

            assertThat(notifier.of(NotificationType.APPROVAL_APPROVED)).hasSize(1);
            assertThat(notifier.recipients())
                    .as("the author, and nobody else")
                    .containsExactly(DANA);
        }

        @Test
        @DisplayName("the decision bumps the lock, so a second press cannot land")
        void bumpsTheLock() {
            service.approve(caller(RINA, Role.COORDINATOR),
                    request(Verb.EXAM_APPROVE, new ExamApproveRequest(CALCULUS_V1, 0)));

            assertThat(store.lockOf(CALCULUS_V1)).isEqualTo(1);
        }

        @Test
        @DisplayName("a decision on something that is not pending is CONFLICT, not a silent move")
        void alreadyDecided() {
            service.approve(caller(RINA, Role.COORDINATOR),
                    request(Verb.EXAM_APPROVE, new ExamApproveRequest(CALCULUS_V1, 0)));

            Message again = service.approve(caller(RINA, Role.COORDINATOR),
                    request(Verb.EXAM_APPROVE, new ExamApproveRequest(CALCULUS_V1, 1)));

            assertThat(again.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(again.errorMessage()).isEqualTo(ApprovalMessages.NOT_PENDING);
        }

        @Test
        @DisplayName("a stale lock version is CONFLICT with a sentence telling her to reload ⚑")
        void staleLockIsRefused() {
            // Somebody wrote the row after her screen was rendered.
            store.bumpLock(CALCULUS_V1);

            Message response = service.approve(caller(RINA, Role.COORDINATOR),
                    request(Verb.EXAM_APPROVE, new ExamApproveRequest(CALCULUS_V1, 0)));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(response.errorMessage())
                    .isEqualTo(ApprovalMessages.DECISION_RACED)
                    .contains("Open it again");
            assertThat(store.statusOf(CALCULUS_V1))
                    .as("and nothing was written")
                    .isEqualTo(ExamVersionStatus.PENDING);
            assertThat(notifier.all()).isEmpty();
        }

        @Test
        @DisplayName("losing the flush race is CONFLICT too, not an internal error ⚑")
        void concurrentWriterWinsTheFlush() {
            store.failNextFlush();

            Message response = service.approve(caller(RINA, Role.COORDINATOR),
                    request(Verb.EXAM_APPROVE, new ExamApproveRequest(CALCULUS_V1, 0)));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(response.errorMessage()).isEqualTo(ApprovalMessages.DECISION_RACED);
        }

        @Test
        @DisplayName("another subject's coordinator cannot approve ⚑")
        void notSomebodyElsesExam() {
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> service.approve(caller(MICHAL, Role.COORDINATOR),
                            request(Verb.EXAM_APPROVE, new ExamApproveRequest(CALCULUS_V1, 0))))
                    .satisfies(e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));

            assertThat(store.statusOf(CALCULUS_V1)).isEqualTo(ExamVersionStatus.PENDING);
        }

        @Test
        @DisplayName("a plain teacher cannot approve at all")
        void teachersCannotApprove() {
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> service.approve(caller(DANA, Role.TEACHER),
                            request(Verb.EXAM_APPROVE, new ExamApproveRequest(CALCULUS_V1, 0))));
        }
    }

    // ===================== Reject ========================================

    @Nested
    @DisplayName("EXAM_REJECT")
    class Reject {

        @Test
        @DisplayName("a reason is stored, the status moves, and the author is told what it was")
        void rejects() {
            String reason = "Question 4 has two correct answers. Please fix it and resubmit.";

            Message response = service.reject(caller(RINA, Role.COORDINATOR),
                    request(Verb.EXAM_REJECT, new ExamRejectRequest(CALCULUS_V1, reason, 0)));

            assertThat(response.isOk()).isTrue();
            assertThat(store.statusOf(CALCULUS_V1)).isEqualTo(ExamVersionStatus.REJECTED);
            assertThat(store.reasonOf(CALCULUS_V1)).isEqualTo(reason);

            List<RecordingNotifier.Sent> sent = notifier.of(NotificationType.APPROVAL_REJECTED);
            assertThat(sent).hasSize(1);
            assertThat(sent.get(0).userIds()).containsExactly(DANA);
            assertThat(sent.get(0).body())
                    .as("a rejection the author cannot act on is the one message we must not send")
                    .contains("Question 4 has two correct answers");
        }

        @Test
        @DisplayName("the notification deep-links to a route the client actually registers")
        void rejectionDeepLinksSomewhereReal() {
            service.reject(caller(RINA, Role.COORDINATOR), request(Verb.EXAM_REJECT,
                    new ExamRejectRequest(CALCULUS_V1, "Please add a fourth question.", 0)));

            RecordingNotifier.Sent sent = notifier.of(NotificationType.APPROVAL_REJECTED).get(0);
            assertThat(sent.ref().isNavigable()).isTrue();
            assertThat(sent.ref().route()).isEqualTo("exams");
            assertThat(sent.ref().entityId()).isEqualTo(CALCULUS_V1);
        }

        @Test
        @DisplayName("no reason at all is refused before anything is read (T-4.2) ⚑")
        void reasonIsRequired() {
            Message response = service.reject(caller(RINA, Role.COORDINATOR),
                    request(Verb.EXAM_REJECT, new ExamRejectRequest(CALCULUS_V1, "  ", 0)));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(response.errorMessage()).isEqualTo(ExamRejectRequest.REASON_REQUIRED);
            assertThat(store.statusOf(CALCULUS_V1)).isEqualTo(ExamVersionStatus.PENDING);
            assertThat(notifier.all()).isEmpty();
        }

        @Test
        @DisplayName("a one-word reason is refused, and the message names the minimum ⚑")
        void reasonHasAMinimumLength() {
            Message response = service.reject(caller(RINA, Role.COORDINATOR),
                    request(Verb.EXAM_REJECT, new ExamRejectRequest(CALCULUS_V1, "no", 0)));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(response.errorMessage())
                    .isEqualTo(ExamRejectRequest.REASON_TOO_SHORT)
                    .contains(String.valueOf(ExamRejectRequest.MIN_REASON_LENGTH));
            assertThat(store.statusOf(CALCULUS_V1)).isEqualTo(ExamVersionStatus.PENDING);
        }

        @Test
        @DisplayName("the stored reason is trimmed, so a notification never has a ragged edge")
        void reasonIsTrimmed() {
            service.reject(caller(RINA, Role.COORDINATOR), request(Verb.EXAM_REJECT,
                    new ExamRejectRequest(CALCULUS_V1, "   Too few questions for 60 minutes.   ", 0)));

            assertThat(store.reasonOf(CALCULUS_V1)).isEqualTo("Too few questions for 60 minutes.");
        }

        @Test
        @DisplayName("a stale lock version is refused here too, before the reason is stored")
        void staleLockIsRefused() {
            store.bumpLock(CALCULUS_V1);

            Message response = service.reject(caller(RINA, Role.COORDINATOR), request(Verb.EXAM_REJECT,
                    new ExamRejectRequest(CALCULUS_V1, "Please add a fourth question.", 0)));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(store.reasonOf(CALCULUS_V1)).isNull();
        }
    }

    // ===================== Self-approval (F4.3) ==========================

    @Nested
    @DisplayName("self-approval (F4.3, acceptance case 4.6)")
    class SelfApproval {

        private ListAppender<ILoggingEvent> appender;
        private Logger serviceLog;

        @BeforeEach
        void captureTheLog() {
            serviceLog = (Logger) LoggerFactory.getLogger(ApprovalService.class);
            appender = new ListAppender<>();
            appender.start();
            serviceLog.addAppender(appender);
        }

        @AfterEach
        void releaseTheLog() {
            serviceLog.detachAppender(appender);
            appender.stop();
        }

        @Test
        @DisplayName("a coordinator may approve her own exam: it succeeds")
        void selfApprovalSucceeds() {
            Message response = service.approve(caller(MICHAL, Role.COORDINATOR),
                    request(Verb.EXAM_APPROVE, new ExamApproveRequest(DATABASES_V1, 0)));

            assertThat(response.isOk()).isTrue();
            assertThat(store.statusOf(DATABASES_V1)).isEqualTo(ExamVersionStatus.APPROVED);
            assertThat(((ApprovalDecision) response.getPayload()).selfApproved()).isTrue();
        }

        @Test
        @DisplayName("and it is logged, in a shape acceptance case 4.6 can find ⚑")
        void selfApprovalIsLoggedInAStableShape() {
            service.approve(caller(MICHAL, Role.COORDINATOR),
                    request(Verb.EXAM_APPROVE, new ExamApproveRequest(DATABASES_V1, 0)));

            List<ILoggingEvent> warnings = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .toList();

            assertThat(warnings)
                    .as("'allowed but logged' with no log line is a silent failure")
                    .hasSize(1);
            String line = warnings.get(0).getFormattedMessage();
            assertThat(line)
                    .startsWith(ApprovalMessages.SELF_APPROVAL_MARKER)
                    .contains(String.valueOf(MICHAL))
                    .contains("מיכל שרון")
                    .contains("202201")
                    .contains("Databases Final")
                    .contains("version 1")
                    .contains("F4.3");
        }

        @Test
        @DisplayName("a decision by somebody else is not logged as a self-approval")
        void ordinaryApprovalIsNotLogged() {
            service.approve(caller(RINA, Role.COORDINATOR),
                    request(Verb.EXAM_APPROVE, new ExamApproveRequest(CALCULUS_V1, 0)));

            assertThat(appender.list)
                    .filteredOn(event -> event.getFormattedMessage()
                            .contains(ApprovalMessages.SELF_APPROVAL_MARKER))
                    .isEmpty();
        }

        @Test
        @DisplayName("she is not also sent a notification telling her what she just did")
        void noNotificationToHerself() {
            service.approve(caller(MICHAL, Role.COORDINATOR),
                    request(Verb.EXAM_APPROVE, new ExamApproveRequest(DATABASES_V1, 0)));

            assertThat(notifier.all())
                    .as("the log line is the record; a bell would be noise")
                    .isEmpty();
        }

        @Test
        @DisplayName("rejecting her own exam still notifies her, because a reason is a document")
        void selfRejectionStillNotifies() {
            service.reject(caller(MICHAL, Role.COORDINATOR), request(Verb.EXAM_REJECT,
                    new ExamRejectRequest(DATABASES_V1, "Withdrawing this until the syllabus lands.", 0)));

            assertThat(notifier.of(NotificationType.APPROVAL_REJECTED)).hasSize(1);
        }
    }

    // ===================== Supersede (E8.2) ==============================

    @Nested
    @DisplayName("a newer submission invalidates the older pending one (E8.2)")
    class Supersede {

        @BeforeEach
        void addASecondVersion() {
            store.version(32L, 3L, "101201", "מבחן אמצע — חדו\"א", 2,
                    ExamVersionStatus.PENDING, "12", "חדו\"א", SUBJECT_MATH,
                    DANA, "דנה כהן", SUBMITTED.plusSeconds(3600));
        }

        @Test
        @DisplayName("the older version is sent back with the fixed system reason")
        void olderPendingIsSentBack() {
            int superseded = service.versionSubmitted(32L);

            assertThat(superseded).isEqualTo(1);
            assertThat(store.statusOf(CALCULUS_V1)).isEqualTo(ExamVersionStatus.REJECTED);
            assertThat(store.reasonOf(CALCULUS_V1))
                    .isEqualTo(ApprovalMessages.SUPERSEDED_REASON)
                    .startsWith("Superseded by a newer version.");
            assertThat(store.statusOf(32L))
                    .as("and the new one is untouched")
                    .isEqualTo(ExamVersionStatus.PENDING);
        }

        @Test
        @DisplayName("the coordinator is told, so a row does not vanish from her queue in silence")
        void coordinatorIsNotified() {
            service.versionSubmitted(32L);

            assertThat(notifier.of(NotificationType.APPROVAL_SUPERSEDED))
                    .singleElement()
                    .satisfies(sent -> assertThat(sent.userIds()).containsExactly(RINA));
            assertThat(notifier.of(NotificationType.APPROVAL_REQUESTED))
                    .as("and she gets the ordinary request for the new one")
                    .singleElement()
                    .satisfies(sent -> assertThat(sent.userIds()).containsExactly(RINA));
        }

        @Test
        @DisplayName("the queue then holds exactly the newest submission")
        void queueHoldsOnlyTheNewest() {
            service.versionSubmitted(32L);

            ApprovalQueue queue = (ApprovalQueue) service.queue(caller(RINA, Role.COORDINATOR),
                    request(Verb.APPROVALS_QUEUE_GET, null)).getPayload();

            assertThat(queue.rows()).extracting(ApprovalRow::examVersionId).containsExactly(32L);
        }

        @Test
        @DisplayName("a first submission supersedes nothing and still requests approval")
        void firstSubmissionSupersedesNothing() {
            int superseded = service.versionSubmitted(DATABASES_V1);

            assertThat(superseded).isZero();
            assertThat(notifier.of(NotificationType.APPROVAL_SUPERSEDED)).isEmpty();
            assertThat(notifier.of(NotificationType.APPROVAL_REQUESTED))
                    .singleElement()
                    .satisfies(sent -> assertThat(sent.userIds())
                            .as("the dual-hat coordinator still has to receive her own request")
                            .containsExactly(MICHAL));
        }

        @Test
        @DisplayName("the hook refuses to act for a version that is not pending")
        void hookIgnoresNonPendingVersions() {
            service.approve(caller(RINA, Role.COORDINATOR),
                    request(Verb.EXAM_APPROVE, new ExamApproveRequest(32L, 0)));
            notifier.clear();

            int superseded = service.versionSubmitted(32L);

            assertThat(superseded).isZero();
            assertThat(store.statusOf(CALCULUS_V1))
                    .as("the sibling that is still waiting is not dragged out of the queue")
                    .isEqualTo(ExamVersionStatus.PENDING);
            assertThat(notifier.all()).isEmpty();
        }

        @Test
        @DisplayName("the hook is harmless for a version that does not exist")
        void hookIgnoresUnknownVersions() {
            assertThat(service.versionSubmitted(9_999L)).isZero();
            assertThat(notifier.all()).isEmpty();
        }

        @Test
        @DisplayName("approving a version that was just superseded is CONFLICT ⚑")
        void supersededVersionCannotBeApproved() {
            // Rina's screen was rendered before the resubmission landed, so her lock version
            // is still current: the status guard is what has to catch this one.
            service.versionSubmitted(32L);

            Message response = service.approve(caller(RINA, Role.COORDINATOR),
                    request(Verb.EXAM_APPROVE, new ExamApproveRequest(CALCULUS_V1, 0)));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(response.errorMessage()).isEqualTo(ApprovalMessages.NOT_PENDING);
        }
    }

    // ===================== The author's own list (retired) ===============
    //
    // MY_APPROVALS_GET's three cases stood here: her own submissions with the reason, never
    // anybody else's, and a student refused. The verb retired into E7.10's EXAM_LIST on
    // 2026-08-25 (APPROVAL ruling 1) and ExamService answers all three now — the scoping one
    // included, since EXAM_LIST is author-scoped in the SQL with no id on the wire either.

    // ===================== Wire mapping ==================================

    @Test
    @DisplayName("the stored status and the wire state name the same four things")
    void thetwoEnumsAgree() {
        // The bridge is an exhaustive switch, so a value added on one side is a compile
        // error rather than a runtime surprise; this pins the names as well, because they
        // are what a chip is looked up by.
        assertThat(java.util.Arrays.stream(ExamVersionStatus.values()).map(Enum::name).toList())
                .containsExactlyElementsOf(
                        java.util.Arrays.stream(ApprovalState.values()).map(Enum::name).toList());
        for (ExamVersionStatus status : ExamVersionStatus.values()) {
            assertThat(ApprovalService.toWire(status).name()).isEqualTo(status.name());
        }
    }
}
