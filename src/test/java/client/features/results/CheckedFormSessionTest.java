package client.features.results;

import client.events.DirectFxThreadPoster;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.exam.AttemptState;
import common.dto.grading.AnswerReviewRow;
import common.dto.grading.CheckedForm;
import common.dto.grading.CheckedFormRequest;
import common.dto.grading.GradeState;
import common.dto.grading.StudentGradeRow;
import common.protocol.ErrorCode;
import common.protocol.Verb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CheckedFormSession} — opening a marked paper (E13.4 ⚑).
 *
 * <p>The interesting tests are about the refusal. {@code NOT_FOUND} is the contract's single
 * answer to four different situations, so it is an expected outcome on this screen rather than
 * a fault, and it must read as one: {@code refusalReadsAsUnavailableNotAsBroken} is what stops
 * a student being told the application failed when in fact her teacher simply has not approved
 * the grade yet.
 */
class CheckedFormSessionTest {

    private static final long GRADE_ID = 900;

    private FakeClientConnection connection;
    private CheckedFormSession session;
    private int renders;

    @BeforeEach
    void setUp() {
        connection = new FakeClientConnection();
        RequestDispatcher dispatcher = new RequestDispatcher(connection);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
        session = new CheckedFormSession(dispatcher, new DirectFxThreadPoster())
                .onChange(() -> renders++);
    }

    private static CheckedForm aForm() {
        return new CheckedForm(
                new StudentGradeRow(GRADE_ID, 11, "מאיה לוי", 71, 71, 71, GradeState.APPROVED,
                        null, null, Instant.parse("2026-08-20T09:00:00Z"),
                        "Algebra midterm", "11"),
                "Algebra midterm", "11", AttemptState.SUBMITTED, 70,
                List.of(new AnswerReviewRow(1, "11001", "q", "a", "b", "c", "d",
                        15, (byte) 1, (byte) 1, true, 15)));
    }

    @Test
    @DisplayName("a loaded paper renders as content, asked for by grade id")
    void opensTheForm() {
        connection.replyOk(Verb.CHECKED_FORM_GET, aForm());

        session.open(GRADE_ID);

        assertThat(session.state()).isEqualTo(AsyncViewState.READY);
        assertThat(session.form()).isPresent();
        assertThat(session.answers()).hasSize(1);
        assertThat(connection.lastSent().getVerb()).isEqualTo(Verb.CHECKED_FORM_GET);
        assertThat(connection.lastSent().getPayload())
                .isEqualTo(new CheckedFormRequest(GRADE_ID));
    }

    @Test
    @DisplayName("a refusal reads as 'not available yet', not as something broken")
    void refusalReadsAsUnavailableNotAsBroken() {
        connection.replyError(Verb.CHECKED_FORM_GET, ErrorCode.NOT_FOUND,
                "That result is not available.");

        session.open(GRADE_ID);

        // The commonest cause is innocent: grading is not finished. Telling her the app failed
        // would be both wrong and alarming.
        assertThat(session.error()).contains(CheckedFormSession.NOT_AVAILABLE);
        assertThat(session.error().orElseThrow()).isNotEqualTo(CheckedFormSession.LOAD_FAILED);
        assertThat(session.form()).isEmpty();
    }

    @Test
    @DisplayName("every refusal produces the same sentence, whatever the server said")
    void doesNotGuessTheReason() {
        // The server withholds which of the four conditions failed. The session must not
        // reconstruct it - not from the error text, and not by branching on anything else.
        // Stating the general rule is fine and helpful; naming the gate that fired is not.
        for (String serverSaid : List.of("anything", "not yours", "not approved yet", "")) {
            setUp();
            connection.replyError(Verb.CHECKED_FORM_GET, ErrorCode.NOT_FOUND, serverSaid);

            session.open(GRADE_ID);

            assertThat(session.error())
                    .as("server said '%s'", serverSaid)
                    .contains(CheckedFormSession.NOT_AVAILABLE);
        }
    }

    @Test
    @DisplayName("a genuine failure says something different from a refusal")
    void realFailureIsDistinct() {
        connection.replyError(Verb.CHECKED_FORM_GET, ErrorCode.INTERNAL, "boom");

        session.open(GRADE_ID);

        assertThat(session.error()).contains(CheckedFormSession.LOAD_FAILED);
    }

    @Test
    @DisplayName("an OK carrying the wrong type fails rather than rendering a blank paper")
    void wrongPayloadType() {
        connection.replyOk(Verb.CHECKED_FORM_GET, "nonsense");

        session.open(GRADE_ID);

        assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);
        assertThat(session.form()).isEmpty();
    }

    @Test
    @DisplayName("a second open while one is in flight is ignored rather than raced")
    void concurrentOpenIsIgnored() {
        session.open(GRADE_ID);
        connection.clearSent();

        session.open(GRADE_ID);

        assertThat(connection.sentCount()).isZero();
    }

    @Test
    @DisplayName("opening clears the previous paper, so a refusal never shows stale answers")
    void openingClearsThePreviousForm() {
        connection.replyOk(Verb.CHECKED_FORM_GET, aForm());
        session.open(GRADE_ID);
        assertThat(session.answers()).hasSize(1);

        connection.replyError(Verb.CHECKED_FORM_GET, ErrorCode.NOT_FOUND, "no");
        session.open(901);

        // Leaving the old answers on screen under a new refusal would show one student's paper
        // in the frame of another request.
        assertThat(session.answers()).isEmpty();
        assertThat(session.form()).isEmpty();
    }

    @Test
    @DisplayName("the screen is told to re-render on every transition")
    void notifiesOnEveryTransition() {
        connection.replyOk(Verb.CHECKED_FORM_GET, aForm());

        session.open(GRADE_ID);

        // Loading, then settled.
        assertThat(renders).isEqualTo(2);
    }
}
