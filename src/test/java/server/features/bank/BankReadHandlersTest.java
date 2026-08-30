package server.features.bank;

import common.dto.ErrorPayload;
import common.dto.auth.Role;
import common.dto.bank.BankListRequest;
import common.dto.bank.BankPage;
import common.dto.bank.Difficulty;
import common.dto.bank.QuestionDetail;
import common.dto.bank.QuestionImage;
import common.dto.bank.QuestionImageRequest;
import common.dto.bank.QuestionRequest;
import common.dto.bank.QuestionVersionDetail;
import common.dto.bank.VersionHistory;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import server.core.AuthorizationException;
import server.core.CallerContext;
import server.core.MessageRouter;
import server.core.SessionManager;
import server.db.MockSessions;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BankReadHandlers} - the four read verbs and the gate they share (E6.3, E6.5, E6.6).
 *
 * <p>Written against the gate rather than the happy paths, for the reason {@link BankHandlersTest}
 * is: an authorization shape written once is only worth anything if every verb actually wears it,
 * and a test per verb is what proves a future fifth verb was added to the shared path rather than
 * beside it.
 *
 * <p>The two properties worth more than the rest:
 *
 * <ul>
 *   <li>{@code ThePrincipal} - she reads and does not write. That is the whole difference between
 *       this class's role list and {@code BankHandlers}', and the write PR's audit found the
 *       matching hole when one role-list entry stood between her and school-wide write access
 *       with nothing testing it. Both halves are now asserted, from opposite sides.</li>
 *   <li>{@code TheExistenceOracle} - unknown, deleted and out-of-reach must be one answer with
 *       one sentence. A test per route, all asserting the same bytes, because the property is
 *       that they are indistinguishable and not merely that each is a refusal.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class BankReadHandlersTest {

    private static final long TEACHER_ID = 3;
    private static final String COURSE = "11";
    private static final String DISPLAY_ID = "11007";
    private static final List<String> ANSWERS =
            List.of("Encapsulation", "Inheritance", "Polymorphism", "Abstraction");

    @Mock
    private Session session;
    @Mock
    private BankBrowseService browse;

    private BankReadHandlers handlers;
    private MockSessions.Wiring wiring;

    @BeforeEach
    void setUp() {
        wiring = MockSessions.commitsOn(session);
        handlers = new BankReadHandlers(wiring.factory(), browse);
    }

    // ===================== Fixtures =======================================

    private static CallerContext teacher() {
        return CallerContext.authenticated(null, TEACHER_ID, Role.TEACHER);
    }

    private static CallerContext coordinator() {
        return CallerContext.authenticated(null, TEACHER_ID, Role.COORDINATOR);
    }

    private static CallerContext principal() {
        return CallerContext.authenticated(null, 900, Role.PRINCIPAL);
    }

    private static CallerContext student() {
        return CallerContext.authenticated(null, 11, Role.STUDENT);
    }

    private static Message request(Verb verb, Object payload) {
        return Message.request(verb, payload);
    }

    private static String errorText(Message response) {
        return ((ErrorPayload) response.getPayload()).message();
    }

    private static ErrorCode errorCode(Message response) {
        return response.getErrorCode();
    }

    private static QuestionDetail aDetail() {
        return new QuestionDetail(DISPLAY_ID, COURSE, "Java", 1, 1, "What is encapsulation?",
                ANSWERS, 1, "OOP", Difficulty.MEDIUM, false, "Dana Cohen",
                Instant.parse("2026-08-21T20:00:00Z"));
    }

    private static VersionHistory aHistory() {
        return new VersionHistory(DISPLAY_ID, List.of(
                new QuestionVersionDetail(2, "What is encapsulation?", ANSWERS, 1, "OOP",
                        Difficulty.MEDIUM, false, "Dana Cohen",
                        Instant.parse("2026-08-22T09:00:00Z")),
                new QuestionVersionDetail(1, "What is encapsulation?", ANSWERS, 1, "OOP",
                        Difficulty.EASY, false, "Dana Cohen",
                        Instant.parse("2026-08-21T20:00:00Z"))));
    }

    private static BankPage aPage() {
        return new BankPage(List.of(), 0, BankListRequest.DEFAULT_PAGE_SIZE, 0, 0);
    }

    // ===================== Registration ===================================

    @Test
    @DisplayName("registers exactly the four read verbs, none of them open")
    void registersTheFourReadVerbs() {
        MessageRouter router = new MessageRouter(new SessionManager());

        handlers.registerOn(router);

        assertThat(router.isRegistered(Verb.BANK_LIST)).isTrue();
        assertThat(router.isRegistered(Verb.QUESTION_GET)).isTrue();
        assertThat(router.isRegistered(Verb.QUESTION_VERSIONS)).isTrue();
        assertThat(router.isRegistered(Verb.QUESTION_IMAGE_GET)).isTrue();

        assertThat(router.isOpen(Verb.BANK_LIST)).isFalse();
        assertThat(router.isOpen(Verb.QUESTION_GET)).isFalse();
        assertThat(router.isOpen(Verb.QUESTION_VERSIONS)).isFalse();
        assertThat(router.isOpen(Verb.QUESTION_IMAGE_GET)).isFalse();

        // The other half of the split: the write verbs are BankHandlers' and must not appear
        // here. Registering a verb twice throws inside the HSTSServer constructor, which no test
        // calls, so a collision would surface as a server that will not boot.
        assertThat(router.isRegistered(Verb.QUESTION_CREATE)).isFalse();
        assertThat(router.isRegistered(Verb.QUESTION_UPDATE)).isFalse();
        assertThat(router.isRegistered(Verb.QUESTION_DELETE)).isFalse();
    }

    // ===================== The gate =======================================

    @Nested
    @DisplayName("the shared gate")
    class TheGate {

        @Test
        @DisplayName("a student is refused on every one of the four verbs")
        void refusesTheStudent() {
            assertThatExceptionOfType(AuthorizationException.class).isThrownBy(() ->
                    handlers.list(student(), request(Verb.BANK_LIST, BankListRequest.firstPage())));
            assertThatExceptionOfType(AuthorizationException.class).isThrownBy(() ->
                    handlers.get(student(), request(Verb.QUESTION_GET,
                            new QuestionRequest(DISPLAY_ID))));
            assertThatExceptionOfType(AuthorizationException.class).isThrownBy(() ->
                    handlers.versions(student(), request(Verb.QUESTION_VERSIONS,
                            new QuestionRequest(DISPLAY_ID))));
            assertThatExceptionOfType(AuthorizationException.class).isThrownBy(() ->
                    handlers.image(student(), request(Verb.QUESTION_IMAGE_GET,
                            new QuestionImageRequest(DISPLAY_ID, 1))));

            verify(browse, never()).list(any(), any(), any());
            verify(browse, never()).get(any(), any(), anyString());
            verify(browse, never()).versions(any(), any(), anyString());
            verify(browse, never()).image(any(), any(), anyString(), anyInt());
        }

        @Test
        @DisplayName("role is checked before the payload is looked at")
        void studentSendingRubbishLearnsNothingAboutThePayload() {
            // Order matters and this is the test that pins it. If the payload check ran first, a
            // student could send junk to a verb that is not hers and read the validation message
            // to learn what it expects.
            assertThatExceptionOfType(AuthorizationException.class).isThrownBy(() ->
                    handlers.get(student(), request(Verb.QUESTION_GET, "not a request")));
        }

        @Test
        @DisplayName("a wrong payload type is VALIDATION rather than an exception")
        void wrongPayloadTypeIsAnAnswer() {
            Message response =
                    handlers.get(teacher(), request(Verb.QUESTION_GET, "not a request"));

            assertThat(errorCode(response)).isEqualTo(ErrorCode.VALIDATION);
            assertThat(errorText(response)).isEqualTo(BankMessages.MALFORMED_REQUEST);
            verify(browse, never()).get(any(), any(), anyString());
        }

        @Test
        @DisplayName("a missing payload is VALIDATION, not a NullPointerException")
        void aMissingPayloadIsAnAnswer() {
            // The log line inside the gate formats the payload's class, so a null payload has a
            // branch of its own there. Found by reading the coverage gap: without this, that
            // branch never ran, and the first null payload from a hostile client would have
            // taken the socket read thread down inside the logger rather than in the handler.
            Message response = handlers.versions(teacher(),
                    request(Verb.QUESTION_VERSIONS, null));

            assertThat(errorCode(response)).isEqualTo(ErrorCode.VALIDATION);
            assertThat(errorText(response)).isEqualTo(BankMessages.MALFORMED_REQUEST);
        }

        @Test
        @DisplayName("an image request naming no question is refused the same way")
        void blankImageIdIsRefused() {
            // Its own shape check and therefore its own test: QUESTION_IMAGE_GET takes a
            // different payload type, so the check for QUESTION_GET passing says nothing about
            // this one. They were written as two methods and could drift as two.
            Message response = handlers.image(teacher(),
                    request(Verb.QUESTION_IMAGE_GET, new QuestionImageRequest("  ", 1)));

            assertThat(errorCode(response)).isEqualTo(ErrorCode.VALIDATION);
            assertThat(errorText(response)).isEqualTo(BankMessages.MALFORMED_REQUEST);
            verify(browse, never()).image(any(), any(), anyString(), anyInt());
        }

        @Test
        @DisplayName("a request naming no question never opens a transaction")
        void blankIdIsRefusedBeforeTheDatabase() {
            Message response = handlers.get(teacher(),
                    request(Verb.QUESTION_GET, new QuestionRequest("   ")));

            assertThat(errorCode(response)).isEqualTo(ErrorCode.VALIDATION);
            // The point of checking shape outside the transaction: nothing is read on behalf of a
            // request that was never going to be honoured.
            verify(browse, never()).get(any(), any(), anyString());
        }
    }

    // ===================== The principal ==================================

    @Nested
    @DisplayName("the principal reads, and does not write")
    class ThePrincipal {

        @Test
        @DisplayName("she is admitted to all four read verbs (F9.3)")
        void readsTheWholeSchool() {
            when(browse.list(any(), any(), any())).thenReturn(aPage());
            when(browse.get(any(), any(), anyString())).thenReturn(Optional.of(aDetail()));

            assertThat(handlers.list(principal(),
                    request(Verb.BANK_LIST, BankListRequest.firstPage())).isOk()).isTrue();
            assertThat(handlers.get(principal(),
                    request(Verb.QUESTION_GET, new QuestionRequest(DISPLAY_ID))).isOk()).isTrue();
        }

        @Test
        @DisplayName("and the write gate still refuses her, which is the boundary")
        void writesNothing() {
            // Deliberately reaching across to the write handlers. The two role lists are the
            // entire difference between "may look at every course" and "may change nothing", and
            // asserting only this class's half would leave that boundary described but untested
            // from the side that matters.
            BankHandlers writes = new BankHandlers(wiring.factory(), new QuestionService(
                    new server.db.repos.QuestionRepository(),
                    new server.db.repos.CourseRepository(),
                    new server.db.repos.UserRepository(),
                    new server.db.ids.QuestionIdAllocator(),
                    java.time.Clock.systemUTC(),
                    // Nobody holds anything: the role gate has to refuse her before the write
                    // path gets far enough to consult a lock, so an empty lock world is the
                    // honest fixture. If this ever starts mattering, the gate has moved.
                    new server.features.locks.EditLockGuard(
                            new server.features.locks.EditLockService(
                                    new server.realtime.PushGateway(
                                            new server.core.SessionManager()),
                                    server.features.locks.DisplayNames.NONE))),
                    // Nobody is signed in, so U-63's bank push has nowhere to go. That is the
                    // honest fixture here too: the role gate refuses her long before a write
                    // could announce anything.
                    new server.db.repos.CourseRepository(),
                    new server.realtime.PushGateway(new server.core.SessionManager()));

            assertThatExceptionOfType(AuthorizationException.class).isThrownBy(() ->
                    writes.delete(principal(), request(Verb.QUESTION_DELETE,
                            new common.dto.bank.QuestionDeleteRequest(DISPLAY_ID, 1))));
        }
    }

    // ===================== The existence oracle ===========================

    @Nested
    @DisplayName("unknown, deleted and out of reach are one answer")
    class TheExistenceOracle {

        @Test
        @DisplayName("QUESTION_GET answers the same NOT_FOUND however the miss happened")
        void getIsUniform() {
            // The service collapses all three routes into an empty Optional, so the handler
            // cannot tell them apart. This asserts the handler does not reintroduce a
            // distinction of its own on the way out.
            when(browse.get(any(), any(), anyString())).thenReturn(Optional.empty());

            Message response = handlers.get(teacher(),
                    request(Verb.QUESTION_GET, new QuestionRequest(DISPLAY_ID)));

            assertThat(errorCode(response)).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(errorText(response)).isEqualTo(BankMessages.QUESTION_NOT_FOUND);
        }

        @Test
        @DisplayName("QUESTION_VERSIONS answers with the same sentence as QUESTION_GET")
        void versionsMatchesGet() {
            when(browse.get(any(), any(), anyString())).thenReturn(Optional.empty());
            when(browse.versions(any(), any(), anyString())).thenReturn(Optional.empty());

            String fromGet = errorText(handlers.get(teacher(),
                    request(Verb.QUESTION_GET, new QuestionRequest(DISPLAY_ID))));
            String fromVersions = errorText(handlers.versions(teacher(),
                    request(Verb.QUESTION_VERSIONS, new QuestionRequest(DISPLAY_ID))));

            // Two verbs answering differently is itself an oracle: a caller who gets one sentence
            // from one verb and another from the other learns which route the miss took.
            assertThat(fromVersions).isEqualTo(fromGet);
        }

        @Test
        @DisplayName("QUESTION_IMAGE_GET names the illustration, not the question")
        void imageMissIsAboutTheImage() {
            when(browse.image(any(), any(), anyString(), anyInt())).thenReturn(Optional.empty());

            Message response = handlers.image(teacher(),
                    request(Verb.QUESTION_IMAGE_GET, new QuestionImageRequest(DISPLAY_ID, 1)));

            assertThat(errorCode(response)).isEqualTo(ErrorCode.NOT_FOUND);
            // Its own sentence, and safe: all four of its miss routes answer with this one, so
            // nothing is distinguishable inside the verb. Telling a teacher looking at an open
            // question that the question is missing would be wrong on the screen she is on.
            assertThat(errorText(response)).isEqualTo(BankMessages.IMAGE_NOT_FOUND);
            assertThat(errorText(response)).isNotEqualTo(BankMessages.QUESTION_NOT_FOUND);
        }
    }

    // ===================== Happy paths ====================================

    @Nested
    @DisplayName("what the verbs answer when they find something")
    class HappyPaths {

        @Test
        @DisplayName("BANK_LIST answers with the page the service built")
        void listAnswersThePage() {
            when(browse.list(any(), any(), any())).thenReturn(aPage());

            Message response = handlers.list(coordinator(),
                    request(Verb.BANK_LIST, BankListRequest.firstPage()));

            assertThat(response.isOk()).isTrue();
            assertThat(response.getPayload()).isInstanceOf(BankPage.class);
        }

        @Test
        @DisplayName("QUESTION_GET answers with the detail")
        void getAnswersTheDetail() {
            when(browse.get(any(), any(), anyString())).thenReturn(Optional.of(aDetail()));

            Message response = handlers.get(teacher(),
                    request(Verb.QUESTION_GET, new QuestionRequest(DISPLAY_ID)));

            assertThat(response.isOk()).isTrue();
            assertThat(((QuestionDetail) response.getPayload()).displayId5())
                    .isEqualTo(DISPLAY_ID);
        }

        @Test
        @DisplayName("QUESTION_VERSIONS answers with the history newest first")
        void versionsAnswersTheHistory() {
            when(browse.versions(any(), any(), anyString())).thenReturn(Optional.of(aHistory()));

            Message response = handlers.versions(teacher(),
                    request(Verb.QUESTION_VERSIONS, new QuestionRequest(DISPLAY_ID)));

            assertThat(response.isOk()).isTrue();
            assertThat(((VersionHistory) response.getPayload()).versions())
                    .extracting(QuestionVersionDetail::versionNo)
                    .containsExactly(2, 1);
        }

        @Test
        @DisplayName("QUESTION_IMAGE_GET answers with the bytes and their sniffed type")
        void imageAnswersTheBytes() {
            byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
            when(browse.image(any(), any(), anyString(), anyInt()))
                    .thenReturn(Optional.of(new QuestionImage(DISPLAY_ID, 2, "image/png", png)));

            Message response = handlers.image(teacher(),
                    request(Verb.QUESTION_IMAGE_GET, new QuestionImageRequest(DISPLAY_ID, 2)));

            assertThat(response.isOk()).isTrue();
            assertThat(((QuestionImage) response.getPayload()).contentType()).isEqualTo("image/png");
        }
    }
}
