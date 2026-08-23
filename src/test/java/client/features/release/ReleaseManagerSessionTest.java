package client.features.release;

import client.events.ClientEventBus;
import client.events.DirectFxThreadPoster;
import client.events.PushEventBridge;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.exam.MonitorCounts;
import common.dto.release.ReleasableVersion;
import common.dto.release.ReleaseActionRequest;
import common.dto.release.ReleaseCodeIssue;
import common.dto.release.ReleaseCreateRequest;
import common.dto.release.ReleaseList;
import common.dto.release.ReleaseOptions;
import common.dto.release.ReleaseRow;
import common.dto.release.ReleaseState;
import common.dto.release.ReleaseWindow;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The Release Manager, with no JavaFX toolkit (E9.5/E9.6 — F5).
 *
 * <p>Every decision the screen makes is here, so this file is where they are pinned. Four
 * are worth naming, because a screenshot would show none of them:
 *
 * <ol>
 *   <li>a pushed row is <b>adopted</b> into the list, replacing the row of that id or
 *       inserting it when the list has never seen it — which is how a release created on her
 *       other machine appears without a refresh button existing (NFR-18);</li>
 *   <li>the code reveal is <b>sticky</b>: it survives until she dismisses it, because a
 *       teacher who looked away mid-sentence has to be able to look back;</li>
 *   <li>local validation uses the wire's own window rule, so the sentence she sees before
 *       sending is the sentence the server would have sent back;</li>
 *   <li>a refusal is the server's sentence, not a second copy of it invented here.</li>
 * </ol>
 */
class ReleaseManagerSessionTest {

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");
    private static final long EXECUTION = 5001L;
    private static final long VERSION = 7001L;

    private FakeClientConnection connection;
    private ClientEventBus eventBus;
    private ReleaseManagerSession session;
    private List<ReleaseList> updates;

    @BeforeEach
    void setUp() throws IOException {
        connection = new FakeClientConnection();
        connection.connect();
        RequestDispatcher dispatcher = new RequestDispatcher(connection);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
        eventBus = new ClientEventBus(ClientEventBus.newBus(), new DirectFxThreadPoster());
        dispatcher.setPushListener(new PushEventBridge(eventBus));
        session = new ReleaseManagerSession(dispatcher, eventBus);
        session.useClock(Clock.fixed(NOW, ZoneId.of("UTC")));
        updates = new ArrayList<>();
        session.onUpdate(updates::add);
    }

    @Test
    @DisplayName("the collaborators are required rather than defaulted")
    void constructorRejectsNulls() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ReleaseManagerSession(null, eventBus));
        assertThatNullPointerException()
                .isThrownBy(() -> new ReleaseManagerSession(
                        new RequestDispatcher(connection), null));
    }

    // ===================== Opening =======================================

    @Nested
    @DisplayName("opening the screen")
    class Opening {

        @Test
        @DisplayName("the list and the picker are both fetched, and both land on the model")
        void fetchesBoth() {
            connection.replyOk(Verb.RELEASE_LIST_GET, listOf(row(EXECUTION, ReleaseState.LIVE)));
            connection.replyOk(Verb.RELEASE_OPTIONS_GET, options());

            session.start().join();

            assertThat(session.rows()).extracting(ReleaseRow::executionId).containsExactly(EXECUTION);
            assertThat(session.options().versions()).hasSize(1);
            assertThat(session.isStarted()).isTrue();
            assertThat(session.isLoading()).isFalse();
        }

        @Test
        @DisplayName("a teacher who has released nothing gets an empty list, not an error")
        void emptyList() {
            connection.replyOk(Verb.RELEASE_LIST_GET, ReleaseList.empty(NOW));
            connection.replyOk(Verb.RELEASE_OPTIONS_GET, ReleaseOptions.empty());

            session.start().join();

            assertThat(session.releases().isEmpty()).isTrue();
            assertThat(session.lastError()).isEmpty();
        }

        @Test
        @DisplayName("leaving the screen unsubscribes and forgets everything")
        void stopForgets() {
            connection.replyOk(Verb.RELEASE_LIST_GET, listOf(row(EXECUTION, ReleaseState.LIVE)));
            connection.replyOk(Verb.RELEASE_OPTIONS_GET, options());
            session.start().join();

            session.stop();

            assertThat(session.isStarted()).isFalse();
            assertThat(session.rows()).isEmpty();
            assertThat(session.lastCreated()).isEmpty();
        }

        @Test
        @DisplayName("a picker that could not be loaded does not break the list")
        void pickerFailureIsSurvivable() {
            connection.replyOk(Verb.RELEASE_LIST_GET, listOf(row(EXECUTION, ReleaseState.LIVE)));
            connection.replyError(Verb.RELEASE_OPTIONS_GET, ErrorCode.INTERNAL, "no");

            session.start().join();

            assertThat(session.rows()).hasSize(1);
            assertThat(session.options().isEmpty()).isTrue();
        }
    }

    // ===================== Creating ======================================

    @Nested
    @DisplayName("releasing an exam (F5.1, F5.2)")
    class Creating {

        @Test
        @DisplayName("the created release joins the list and its code goes on screen ⚑")
        void codeIsRevealed() {
            connection.replyOk(Verb.RELEASE_LIST_GET, ReleaseList.empty(NOW));
            connection.replyOk(Verb.RELEASE_OPTIONS_GET, options());
            connection.replyOk(Verb.RELEASE_CREATE, row(EXECUTION, ReleaseState.SCHEDULED));
            session.start().join();

            session.create(VERSION, NOW.plus(Duration.ofHours(1)),
                    NOW.plus(Duration.ofHours(2))).join();

            assertThat(session.lastCreated()).isPresent();
            assertThat(session.lastCreated().orElseThrow().code()).isEqualTo("4B7Q");
            assertThat(session.rows()).extracting(ReleaseRow::executionId).containsExactly(EXECUTION);
        }

        @Test
        @DisplayName("the reveal is sticky, and is dismissed by the teacher rather than by time")
        void revealIsSticky() {
            connection.replyOk(Verb.RELEASE_CREATE, row(EXECUTION, ReleaseState.SCHEDULED));
            session.create(VERSION, NOW.plus(Duration.ofHours(1)),
                    NOW.plus(Duration.ofHours(2))).join();

            session.clearCreated();

            assertThat(session.lastCreated()).isEmpty();
        }

        @Test
        @DisplayName("a pushed change to the revealed release keeps the panel but refreshes it")
        void revealFollowsPushes() {
            connection.replyOk(Verb.RELEASE_LIST_GET, ReleaseList.empty(NOW));
            connection.replyOk(Verb.RELEASE_OPTIONS_GET, options());
            connection.replyOk(Verb.RELEASE_CREATE, row(EXECUTION, ReleaseState.SCHEDULED));
            session.start().join();
            session.create(VERSION, NOW.plus(Duration.ofHours(1)),
                    NOW.plus(Duration.ofHours(2))).join();

            connection.pushToClient(Verb.PUSH_EXECUTION_STATUS, row(EXECUTION, ReleaseState.LIVE));

            // The code is still on screen, because it is still the code she has to read out;
            // it simply stops claiming a state the release has left.
            assertThat(session.lastCreated()).isPresent();
            assertThat(session.lastCreated().orElseThrow().state()).isEqualTo(ReleaseState.LIVE);
        }

        @Test
        @DisplayName("⚑ an illegal window is refused locally, with the wire's own sentence")
        void illegalWindowNeverReachesTheWire() {
            connection.replyOk(Verb.RELEASE_CREATE, row(EXECUTION, ReleaseState.SCHEDULED));

            session.create(VERSION, NOW.plus(Duration.ofHours(2)),
                    NOW.plus(Duration.ofHours(1))).join();

            assertThat(session.lastError())
                    .isEqualTo(ReleaseWindow.CLOSE_NOT_AFTER_OPEN.sentence());
            // Not sent at all: the rule is the same one the server would apply, so there is
            // nothing to learn from the round trip.
            assertThat(connection.sentMessages()).isEmpty();
        }

        @Test
        @DisplayName("no exam picked is its own sentence, before anything is sent")
        void noVersionPicked() {
            session.create(0, NOW.plus(Duration.ofHours(1)),
                    NOW.plus(Duration.ofHours(2))).join();

            assertThat(session.lastError()).isEqualTo(ReleaseCopy.VERSION_REQUIRED);
            assertThat(connection.sentMessages()).isEmpty();
        }

        @Test
        @DisplayName("the request carries the version, the window and the code she chose")
        void requestShape() {
            connection.replyOk(Verb.RELEASE_CREATE, row(EXECUTION, ReleaseState.SCHEDULED));

            session.create(VERSION, NOW.plus(Duration.ofHours(1)),
                    NOW.plus(Duration.ofHours(2)), "4821").join();

            Message sent = connection.lastSent();
            assertThat(sent.getVerb()).isEqualTo(Verb.RELEASE_CREATE);
            assertThat(sent.getPayload()).isInstanceOf(ReleaseCreateRequest.class);
            ReleaseCreateRequest ask = (ReleaseCreateRequest) sent.getPayload();
            assertThat(ask.examVersionId()).isEqualTo(VERSION);
            assertThat(ask.code()).isEqualTo("4821");
        }

        @Test
        @DisplayName("⚑ leaving the code blank sends null, which is the 'you pick one' request")
        void blankCodeTravelsAsNull() {
            connection.replyOk(Verb.RELEASE_CREATE, row(EXECUTION, ReleaseState.SCHEDULED));

            session.create(VERSION, NOW.plus(Duration.ofHours(1)),
                    NOW.plus(Duration.ofHours(2)), "   ").join();

            ReleaseCreateRequest ask = (ReleaseCreateRequest) connection.lastSent().getPayload();
            assertThat(ask.hasCode()).isFalse();
            assertThat(ask.code()).isNull();
        }

        @Test
        @DisplayName("the three-argument create still means 'you pick one'")
        void shortFormGeneratesServerSide() {
            connection.replyOk(Verb.RELEASE_CREATE, row(EXECUTION, ReleaseState.SCHEDULED));

            session.create(VERSION, NOW.plus(Duration.ofHours(1)),
                    NOW.plus(Duration.ofHours(2))).join();

            assertThat(((ReleaseCreateRequest) connection.lastSent().getPayload()).hasCode())
                    .isFalse();
        }

        @Test
        @DisplayName("⚑ a badly shaped code is refused locally, with the wire's own sentence")
        void malformedCodeNeverReachesTheWire() {
            connection.replyOk(Verb.RELEASE_CREATE, row(EXECUTION, ReleaseState.SCHEDULED));

            // Acceptance case 5.3's first refusal.
            session.create(VERSION, NOW.plus(Duration.ofHours(1)),
                    NOW.plus(Duration.ofHours(2)), "12").join();

            assertThat(session.lastError()).isEqualTo(ReleaseCodeIssue.MALFORMED.sentence());
            assertThat(connection.sentMessages()).isEmpty();
        }

        @Test
        @DisplayName("⚑ but 'is it taken' is never pre-judged here: that answer is the server's")
        void takenIsAServerAnswer() {
            connection.replyError(Verb.RELEASE_CREATE, ErrorCode.VALIDATION,
                    ReleaseCodeIssue.TAKEN.sentence());

            session.create(VERSION, NOW.plus(Duration.ofHours(1)),
                    NOW.plus(Duration.ofHours(2)), "4821").join();

            // The code is well formed, so it goes out; only the inserting transaction can
            // know it clashes, and the sentence comes back from there.
            assertThat(connection.sentMessages()).hasSize(1);
            assertThat(session.lastError()).isEqualTo(ReleaseCodeIssue.TAKEN.sentence());
            assertThat(session.lastCreated()).isEmpty();
        }

        @Test
        @DisplayName("⚑ a refusal is the server's own sentence, not a second copy invented here")
        void serverSentenceWins() {
            connection.replyError(Verb.RELEASE_CREATE, ErrorCode.VALIDATION,
                    "Only an approved exam can be released. Ask your subject coordinator to "
                            + "approve this version, then release it.");

            session.create(VERSION, NOW.plus(Duration.ofHours(1)),
                    NOW.plus(Duration.ofHours(2))).join();

            assertThat(session.lastError()).contains("approved").contains("coordinator");
            assertThat(session.lastCreated()).isEmpty();
        }

        @Test
        @DisplayName("a round trip that never came back says so, and says what to do")
        void offline() throws IOException {
            connection.failSendsWith(new IOException("socket closed"));

            session.create(VERSION, NOW.plus(Duration.ofHours(1)),
                    NOW.plus(Duration.ofHours(2))).join();

            assertThat(session.lastError()).isEqualTo(ReleaseCopy.OFFLINE);
        }
    }

    // ===================== Acting ========================================

    @Nested
    @DisplayName("cancelling and closing early (F5.5)")
    class Acting {

        @Test
        @DisplayName("cancelling replaces the row with the one the server sent back")
        void cancel() {
            connection.replyOk(Verb.RELEASE_LIST_GET, listOf(row(EXECUTION, ReleaseState.SCHEDULED)));
            connection.replyOk(Verb.RELEASE_OPTIONS_GET, options());
            connection.replyOk(Verb.RELEASE_CANCEL, row(EXECUTION, ReleaseState.CANCELLED));
            session.start().join();

            session.cancel(EXECUTION).join();

            assertThat(session.rowOf(EXECUTION).orElseThrow().state())
                    .isEqualTo(ReleaseState.CANCELLED);
            assertThat(connection.lastSent().getPayload())
                    .isEqualTo(new ReleaseActionRequest(EXECUTION));
        }

        @Test
        @DisplayName("closing early replaces the row too, with the counts the server froze")
        void closeEarly() {
            connection.replyOk(Verb.RELEASE_LIST_GET, listOf(row(EXECUTION, ReleaseState.LIVE)));
            connection.replyOk(Verb.RELEASE_OPTIONS_GET, options());
            connection.replyOk(Verb.RELEASE_CLOSE_EARLY, new ReleaseRow(EXECUTION, VERSION,
                    "Midterm", "11", "Algebra", "4B7Q", NOW, NOW.plus(Duration.ofHours(1)),
                    0, 45, ReleaseState.CLOSED, new MonitorCounts(12, 8, 4)));
            session.start().join();

            session.closeEarly(EXECUTION).join();

            ReleaseRow closed = session.rowOf(EXECUTION).orElseThrow();
            assertThat(closed.state()).isEqualTo(ReleaseState.CLOSED);
            assertThat(closed.counts().timedOut()).isEqualTo(4);
        }

        @Test
        @DisplayName("a refused action leaves the row alone and shows the server's sentence")
        void refusedActionChangesNothing() {
            connection.replyOk(Verb.RELEASE_LIST_GET, listOf(row(EXECUTION, ReleaseState.LIVE)));
            connection.replyOk(Verb.RELEASE_OPTIONS_GET, options());
            connection.replyError(Verb.RELEASE_CANCEL, ErrorCode.CONFLICT,
                    "This exam has already opened, so it cannot be cancelled. Use close early "
                            + "to end it now.");
            session.start().join();

            session.cancel(EXECUTION).join();

            assertThat(session.rowOf(EXECUTION).orElseThrow().state()).isEqualTo(ReleaseState.LIVE);
            assertThat(session.lastError()).contains("close early");
        }
    }

    // ===================== Pushes ========================================

    @Nested
    @DisplayName("live updates (F5.4, NFR-18)")
    class Pushes {

        @Test
        @DisplayName("a pushed row replaces the one it is about, with nobody pressing anything")
        void pushReplaces() {
            connection.replyOk(Verb.RELEASE_LIST_GET, listOf(row(EXECUTION, ReleaseState.SCHEDULED)));
            connection.replyOk(Verb.RELEASE_OPTIONS_GET, options());
            session.start().join();
            int before = updates.size();

            connection.pushToClient(Verb.PUSH_EXECUTION_STATUS, row(EXECUTION, ReleaseState.LIVE));

            assertThat(session.rowOf(EXECUTION).orElseThrow().state()).isEqualTo(ReleaseState.LIVE);
            assertThat(updates).hasSizeGreaterThan(before);
        }

        @Test
        @DisplayName("⚑ a pushed row this list has never seen is an insert, not a mistake")
        void pushInserts() {
            connection.replyOk(Verb.RELEASE_LIST_GET, ReleaseList.empty(NOW));
            connection.replyOk(Verb.RELEASE_OPTIONS_GET, options());
            session.start().join();

            // Created on her other machine, or by the exam's author.
            connection.pushToClient(Verb.PUSH_EXECUTION_STATUS, row(9001, ReleaseState.SCHEDULED));

            assertThat(session.rows()).extracting(ReleaseRow::executionId).containsExactly(9001L);
        }

        @Test
        @DisplayName("a push of another kind passes straight through")
        void otherPushesAreIgnored() {
            connection.replyOk(Verb.RELEASE_LIST_GET, listOf(row(EXECUTION, ReleaseState.LIVE)));
            connection.replyOk(Verb.RELEASE_OPTIONS_GET, options());
            session.start().join();

            connection.pushToClient(Verb.PUSH_NOTIFICATION, "something else");
            connection.pushToClient(Verb.PUSH_EXECUTION_STATUS, "not a row");

            assertThat(session.rowOf(EXECUTION).orElseThrow().state()).isEqualTo(ReleaseState.LIVE);
        }
    }

    // ===================== The clock =====================================

    @Test
    @DisplayName("countdowns are drawn from the server's clock, carried forward locally")
    void serverClockIsCarriedForward() {
        connection.replyOk(Verb.RELEASE_LIST_GET, listOf(row(EXECUTION, ReleaseState.SCHEDULED)));
        connection.replyOk(Verb.RELEASE_OPTIONS_GET, options());
        session.start().join();

        // The list said the server's now was NOW, and the local clock has not moved.
        assertThat(session.now()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("before any answer arrives, 'now' is the local clock rather than the epoch")
    void nowBeforeTheFirstAnswer() {
        assertThat(session.now()).isEqualTo(NOW);
    }

    // ===================== Fixture =======================================

    private static ReleaseList listOf(ReleaseRow... rows) {
        return new ReleaseList(NOW, List.of(rows));
    }

    private static ReleaseRow row(long executionId, ReleaseState state) {
        return new ReleaseRow(executionId, VERSION, "Midterm", "11", "Algebra 11", "4B7Q",
                NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(2)), 0, 45,
                state, MonitorCounts.NONE);
    }

    private static ReleaseOptions options() {
        return new ReleaseOptions(List.of(new ReleasableVersion(VERSION, "101101", "Midterm",
                1, "11", "Algebra 11", 45, 12)), true);
    }
}
