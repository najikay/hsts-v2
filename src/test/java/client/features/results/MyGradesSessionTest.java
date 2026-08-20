package client.features.results;

import client.events.DirectFxThreadPoster;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.grading.GradeState;
import common.dto.grading.MyGrades;
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
 * {@link MyGradesSession} — E13.3's screen behaviour, proven without a JavaFX toolkit.
 *
 * <p>The session talks to a {@link FakeClientConnection} through a real
 * {@link RequestDispatcher}, and the FX hop is a {@link DirectFxThreadPoster}, so every
 * transition settles synchronously (TEAM_SPLIT §3.2, "client sessions test against
 * FakeClientConnection").
 *
 * <p>The rows are the seeded execution 4821 result for {@code maya.levi}, so the fixture is the
 * same dataset the acceptance table's scenario 9 walks by hand.
 */
class MyGradesSessionTest {

    private static final StudentGradeRow MAYA_ALGEBRA = new StudentGradeRow(
            1, 11, "מאיה לוי", 71, null, 71, GradeState.APPROVED,
            null, null, Instant.parse("2026-08-06T09:00:00Z"));

    /** yael.azulay's seeded row: auto 51, overridden to 55 — the adjusted case. */
    private static final StudentGradeRow ADJUSTED = new StudentGradeRow(
            2, 13, "יעל אזולאי", 51, 55, 55, GradeState.APPROVED,
            null, "שיפור ניכר באי-שוויונות.", Instant.parse("2026-08-06T09:05:00Z"));

    private FakeClientConnection connection;
    private RequestDispatcher dispatcher;
    private MyGradesSession session;
    private int renders;

    @BeforeEach
    void setUp() {
        connection = new FakeClientConnection();
        dispatcher = new RequestDispatcher(connection);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
        session = new MyGradesSession(dispatcher, new DirectFxThreadPoster())
                .onChange(() -> renders++);
    }

    @Nested
    @DisplayName("loading")
    class Loading {

        @Test
        @DisplayName("a loaded list renders as content and asks the server exactly once")
        void loadsGrades() {
            connection.replyOk(Verb.MY_GRADES_GET, new MyGrades(List.of(MAYA_ALGEBRA, ADJUSTED)));

            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.READY);
            assertThat(session.grades()).hasSize(2);
            assertThat(session.error()).isEmpty();
            assertThat(connection.sentCount()).isEqualTo(1);
            assertThat(connection.lastSent().getVerb()).isEqualTo(Verb.MY_GRADES_GET);
        }

        @Test
        @DisplayName("the screen shows a skeleton while the request is in flight")
        void showsSkeletonWhileLoading() {
            // No canned reply: the future stays pending, so the session stays LOADING.
            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.LOADING);
            assertThat(session.isLoading()).isTrue();
            assertThat(session.state().showsSkeleton()).isTrue();
        }

        @Test
        @DisplayName("H13.2 — a student who has sat nothing gets an explanation, not a blank panel")
        void emptyStateExplains() {
            connection.replyOk(Verb.MY_GRADES_GET, MyGrades.EMPTY);

            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.EMPTY);
            assertThat(session.grades()).isEmpty();
            assertThat(session.emptyMessage()).contains(MyGradesSession.NOTHING_YET);
        }

        @Test
        @DisplayName("a second load while one is in flight is ignored, so answers cannot settle out of order")
        void doesNotStackRequests() {
            session.load();
            session.load();

            assertThat(connection.sentCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("failure")
    class Failure {

        @Test
        @DisplayName("a server error shows a human sentence and no rows")
        void serverError() {
            connection.replyError(Verb.MY_GRADES_GET, ErrorCode.INTERNAL, "boom");

            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.error()).contains(MyGradesSession.LOAD_FAILED);
            assertThat(session.grades()).isEmpty();
        }

        @Test
        @DisplayName("the error sentence never leaks the server's wording")
        void errorSaysNothingUseful() {
            connection.replyError(Verb.MY_GRADES_GET, ErrorCode.INTERNAL,
                    "ORA-00942: table or view does not exist");

            session.load();

            assertThat(session.error()).contains(MyGradesSession.LOAD_FAILED);
            assertThat(session.error().orElseThrow()).doesNotContain("ORA-");
        }

        @Test
        @DisplayName("an OK carrying the wrong payload type fails cleanly rather than throwing")
        void wrongPayloadType() {
            connection.replyOk(Verb.MY_GRADES_GET, "not a MyGrades");

            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.grades()).isEmpty();
        }

        @Test
        @DisplayName("a failure can be retried, and the retry succeeds")
        void retryAfterFailure() {
            connection.replyError(Verb.MY_GRADES_GET, ErrorCode.INTERNAL, "boom");
            session.load();
            assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);

            connection.replyOk(Verb.MY_GRADES_GET, new MyGrades(List.of(MAYA_ALGEBRA)));
            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.READY);
            assertThat(session.error()).isEmpty();
        }
    }

    @Nested
    @DisplayName("live refresh — NFR-18, no refresh button anywhere")
    class LiveRefresh {

        @Test
        @DisplayName("H13.5 — a published grade appears without the student doing anything")
        void gradePublishedRefreshes() {
            connection.replyOk(Verb.MY_GRADES_GET, MyGrades.EMPTY);
            session.load();
            assertThat(session.state()).isEqualTo(AsyncViewState.EMPTY);

            connection.replyOk(Verb.MY_GRADES_GET, new MyGrades(List.of(MAYA_ALGEBRA)));
            session.onGradePublished();

            assertThat(session.state()).isEqualTo(AsyncViewState.READY);
            assertThat(session.grades()).containsExactly(MAYA_ALGEBRA);
        }

        @Test
        @DisplayName("the push triggers a re-query rather than appending the pushed row")
        void refreshRequeries() {
            connection.replyOk(Verb.MY_GRADES_GET, new MyGrades(List.of(MAYA_ALGEBRA)));
            session.load();
            connection.clearSent();

            session.onGradePublished();

            // Re-asking is what stops the list drifting from the server's answer.
            assertThat(connection.sentCount()).isEqualTo(1);
            assertThat(connection.lastSent().getVerb()).isEqualTo(Verb.MY_GRADES_GET);
        }
    }

    @Nested
    @DisplayName("what the row tells the screen")
    class RowRendering {

        @Test
        @DisplayName("an adjusted grade is recognisable, and carries the comment but no justification")
        void adjustedGrade() {
            assertThat(session.wasAdjusted(ADJUSTED)).isTrue();
            assertThat(ADJUSTED.effectiveScore()).isEqualTo(55);
            assertThat(ADJUSTED.teacherComment()).isNotBlank();
            // The DTO strips it structurally; the screen has nothing to hide by hand.
            assertThat(ADJUSTED.overrideReason()).isNull();
        }

        @Test
        @DisplayName("an untouched grade is not reported as adjusted")
        void untouchedGrade() {
            assertThat(session.wasAdjusted(MAYA_ALGEBRA)).isFalse();
        }

        @Test
        @DisplayName("MyGrades strips the justification even if a server tried to send one")
        void justificationIsStrippedByTheContainer() {
            StudentGradeRow leaky = new StudentGradeRow(
                    3, 13, "יעל אזולאי", 51, 55, 55, GradeState.APPROVED,
                    "teacher-only audit text", "comment", Instant.now());

            MyGrades wrapped = new MyGrades(List.of(leaky));

            assertThat(wrapped.grades().get(0).overrideReason()).isNull();
        }
    }

    @Test
    @DisplayName("every state change asks the screen to re-render exactly once")
    void notifiesOnEveryTransition() {
        connection.replyOk(Verb.MY_GRADES_GET, new MyGrades(List.of(MAYA_ALGEBRA)));

        session.load();

        // LOADING, then READY.
        assertThat(renders).isEqualTo(2);
    }
}
