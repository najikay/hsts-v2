package server.features.bank;

import common.dto.bank.Question;
import common.dto.bank.QuestionUpdate;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Status;
import common.protocol.Verb;
import ocsf.server.ConnectionToClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import server.core.CallerContext;
import server.core.MessageRouter;
import server.core.SessionManager;
import server.db.QuestionDAO;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Router-level tests for the ported prototype flow (E1 regression guard).
 *
 * <p>Phase 3's demo — list the question bank, edit one, see it persist — must
 * still work after the protocol rewrite. With {@link QuestionDAO} mocked there
 * is no MySQL in the loop, so this suite runs anywhere and still proves the
 * whole server-side path: {@code MessageRouter.handle(Object, ConnectionToClient)}
 * → verb lookup → handler → DAO → correlated OK back on the socket.
 */
@ExtendWith(MockitoExtension.class)
class LegacyQuestionHandlersTest {

    private static final long TEACHER_ID = 1001L;

    @Mock
    private QuestionDAO questionDAO;

    @Mock
    private ConnectionToClient connection;

    private MessageRouter router;

    private SessionManager sessions;

    @BeforeEach
    void setUp() {
        sessions = new SessionManager();
        router = new MessageRouter(sessions);
        new LegacyQuestionHandlers(questionDAO).registerOn(router);
        // Since E5 both verbs require a session; the flows below are about the
        // handlers, so the socket under test carries one.
        sessions.attach(TEACHER_ID, common.dto.auth.Role.TEACHER, connection);
    }

    @Test
    @DisplayName("both legacy verbs now require an authenticated session (E5)")
    void bothVerbsRequireASession() {
        assertThat(router.isRegistered(Verb.GET_ALL_QUESTIONS)).isTrue();
        assertThat(router.isRegistered(Verb.UPDATE_QUESTION)).isTrue();
        assertThat(router.isOpen(Verb.GET_ALL_QUESTIONS)).isFalse();
        assertThat(router.isOpen(Verb.UPDATE_QUESTION)).isFalse();
    }

    @Test
    @DisplayName("without a session the router refuses them with UNAUTHORIZED")
    void anonymousCallersAreRefused() {
        Message response = router.route(Message.request(Verb.GET_ALL_QUESTIONS, null),
                CallerContext.anonymous(null));

        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        verify(questionDAO, never()).getAll();
    }

    @Test
    @DisplayName("GET_ALL_QUESTIONS end to end: request in, correlated list out")
    void getAllQuestionsEndToEnd() throws Exception {
        List<Question> stored = List.of(
                new Question(1, "מהי בירת צרפת?", "פריז"),
                new Question(2, "2 + 2 = ?", "4"));
        when(questionDAO.getAll()).thenReturn(new ArrayList<>(stored));
        Message request = Message.request(Verb.GET_ALL_QUESTIONS, null);

        router.handle(request, connection);

        Message response = captureResponse();
        assertThat(response.getStatus()).isEqualTo(Status.OK);
        assertThat(response.getVerb()).isEqualTo(Verb.GET_ALL_QUESTIONS);
        assertThat(response.getRequestId()).isEqualTo(request.getRequestId());

        @SuppressWarnings("unchecked")
        List<Question> payload = (List<Question>) response.getPayload();
        assertThat(payload).hasSize(2);
        assertThat(payload.get(0).getQuestionText()).isEqualTo("מהי בירת צרפת?");
    }

    @Test
    @DisplayName("the answered list is serializable — it has to survive the socket")
    void responsePayloadIsSerializable() throws Exception {
        when(questionDAO.getAll()).thenReturn(new ArrayList<>(List.of(new Question(1, "q", "a"))));

        router.handle(Message.request(Verb.GET_ALL_QUESTIONS, null), connection);

        Message response = captureResponse();
        Message restored = roundTrip(response);
        @SuppressWarnings("unchecked")
        List<Question> payload = (List<Question>) restored.getPayload();
        assertThat(payload.get(0).getAnswer()).isEqualTo("a");
    }

    @Test
    @DisplayName("an empty bank answers with an empty list, not an error")
    void emptyBankIsNotAnError() throws Exception {
        when(questionDAO.getAll()).thenReturn(new ArrayList<>());

        router.handle(Message.request(Verb.GET_ALL_QUESTIONS, null), connection);

        Message response = captureResponse();
        assertThat(response.isOk()).isTrue();
        assertThat((List<?>) response.getPayload()).isEmpty();
    }

    @Test
    @DisplayName("UPDATE_QUESTION persists and answers with the refreshed list")
    void updateQuestionEndToEnd() throws Exception {
        Question edited = new Question(2, "2 + 2 = ?", "four");
        when(questionDAO.update(edited)).thenReturn(true);
        when(questionDAO.getAll()).thenReturn(new ArrayList<>(List.of(edited)));

        router.handle(Message.request(Verb.UPDATE_QUESTION, edited), connection);

        Message response = captureResponse();
        assertThat(response.isOk()).isTrue();
        assertThat(response.getVerb()).isEqualTo(Verb.UPDATE_QUESTION);
        @SuppressWarnings("unchecked")
        List<Question> payload = (List<Question>) response.getPayload();
        assertThat(payload).containsExactly(edited);
        verify(questionDAO).update(edited);
    }

    @Test
    @DisplayName("an update that matches no row answers NOT_FOUND and reads nothing back")
    void updateThatChangedNothing() throws Exception {
        Question missing = new Question(404, "gone", "gone");
        when(questionDAO.update(missing)).thenReturn(false);

        router.handle(Message.request(Verb.UPDATE_QUESTION, missing), connection);

        Message response = captureResponse();
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(response.errorMessage()).contains("404");
        verify(questionDAO, never()).getAll();
    }

    @Test
    @DisplayName("a payload that is not a Question is a VALIDATION error, and never reaches the DAO")
    void wrongPayloadIsRejectedBeforeTheDao() throws Exception {
        router.handle(Message.request(Verb.UPDATE_QUESTION, "not a question"), connection);

        Message response = captureResponse();
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
        verify(questionDAO, never()).update(any());
    }

    @Test
    @DisplayName("a missing payload is a VALIDATION error too")
    void nullPayloadIsRejected() throws Exception {
        router.handle(Message.request(Verb.UPDATE_QUESTION, null), connection);

        assertThat(captureResponse().getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
    }

    // ===================== Optimistic concurrency (E18.4) ================

    @Test
    @DisplayName("a guarded update whose baseline still matches saves and returns the fresh list")
    void guardedUpdateSaves() throws Exception {
        QuestionUpdate update = new QuestionUpdate(
                new Question(2, "2 + 2 = ?", "four"), "2 + 2 = ?", "4");
        when(questionDAO.updateGuarded(update)).thenReturn(QuestionDAO.UpdateOutcome.SAVED);
        when(questionDAO.getAll()).thenReturn(new ArrayList<>(List.of(update.edited())));

        router.handle(Message.request(Verb.UPDATE_QUESTION, update), connection);

        Message response = captureResponse();
        assertThat(response.isOk()).isTrue();
        assertThat((List<?>) response.getPayload()).hasSize(1);
        verify(questionDAO, never()).update(any());
    }

    @Test
    @DisplayName("a stale guarded update is refused with CONFLICT and writes nothing")
    void guardedUpdateRejectsAStaleWrite() throws Exception {
        QuestionUpdate update = new QuestionUpdate(
                new Question(2, "mine", "mine"), "what I loaded", "what I loaded");
        when(questionDAO.updateGuarded(update)).thenReturn(QuestionDAO.UpdateOutcome.STALE);

        router.handle(Message.request(Verb.UPDATE_QUESTION, update), connection);

        Message response = captureResponse();
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(response.errorMessage())
                .isEqualTo(LegacyQuestionHandlers.STALE_WRITE_MESSAGE)
                .contains("Reload");
        verify(questionDAO, never()).getAll();
    }

    @Test
    @DisplayName("a guarded update on a row that is gone is NOT_FOUND, not CONFLICT")
    void guardedUpdateOnAMissingRow() throws Exception {
        QuestionUpdate update = new QuestionUpdate(new Question(404, "gone", "gone"), "", "");
        when(questionDAO.updateGuarded(update)).thenReturn(QuestionDAO.UpdateOutcome.MISSING);

        router.handle(Message.request(Verb.UPDATE_QUESTION, update), connection);

        Message response = captureResponse();
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(response.errorMessage()).contains("404");
    }

    @Test
    @DisplayName("a database failure is INTERNAL, never 'it may have been removed'")
    void guardedUpdateOnADatabaseFailure() throws Exception {
        QuestionUpdate update = new QuestionUpdate(new Question(2, "mine", "mine"), "loaded", "loaded");
        when(questionDAO.updateGuarded(update)).thenReturn(QuestionDAO.UpdateOutcome.FAILED);

        router.handle(Message.request(Verb.UPDATE_QUESTION, update), connection);

        Message response = captureResponse();
        // An outage must not masquerade as a deleted question: the user would stop
        // trying to save work that is perfectly saveable once the DB is back.
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(response.errorMessage()).contains("try again");
        verify(questionDAO, never()).getAll();
    }

    @Test
    @DisplayName("the pre-E18 bare-Question payload still writes unguarded (backward compatible)")
    void bareQuestionStillTakesTheOldPath() throws Exception {
        Question edited = new Question(2, "2 + 2 = ?", "four");
        when(questionDAO.update(edited)).thenReturn(true);
        when(questionDAO.getAll()).thenReturn(new ArrayList<>(List.of(edited)));

        router.handle(Message.request(Verb.UPDATE_QUESTION, edited), connection);

        assertThat(captureResponse().isOk()).isTrue();
        verify(questionDAO).update(edited);
        verify(questionDAO, never()).updateGuarded(any());
    }

    @Test
    @DisplayName("the stale-write message says what happened and what to do next (PRD §4.1)")
    void staleWriteCopyRules() {
        assertThat(LegacyQuestionHandlers.STALE_WRITE_MESSAGE)
                .doesNotContain("—")
                .contains("changed by someone else")
                .contains("Reload the latest version");
    }

    @Test
    @DisplayName("a DAO that explodes becomes a generic INTERNAL error, leaking no SQL")
    void daoFailureIsContained() throws Exception {
        when(questionDAO.getAll()).thenThrow(
                new RuntimeException("Communications link failure to jdbc:mysql://10.0.0.5/hsts"));

        router.handle(Message.request(Verb.GET_ALL_QUESTIONS, null), connection);

        Message response = captureResponse();
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(response.errorMessage()).isEqualTo(MessageRouter.GENERIC_INTERNAL_MESSAGE);
        assertThat(response.errorMessage()).doesNotContain("jdbc");
    }

    @Test
    @DisplayName("handlers can also be called directly, without a socket")
    void handlersAreUnitTestableOnTheirOwn() {
        when(questionDAO.getAll()).thenReturn(new ArrayList<>());
        LegacyQuestionHandlers handlers = new LegacyQuestionHandlers(questionDAO);
        Message request = Message.request(Verb.GET_ALL_QUESTIONS, null);

        Message response = handlers.getAllQuestions(CallerContext.anonymous(connection), request);

        assertThat(response.isOk()).isTrue();
    }

    @Test
    @DisplayName("it refuses to be built without a DAO")
    void validatesArguments() {
        assertThatNullPointerException().isThrownBy(() -> new LegacyQuestionHandlers(null));
    }

    private Message captureResponse() throws IOException {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(connection).sendToClient(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(Message.class);
        return (Message) captor.getValue();
    }

    private static Message roundTrip(Message original) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (Message) in.readObject();
        }
    }
}
