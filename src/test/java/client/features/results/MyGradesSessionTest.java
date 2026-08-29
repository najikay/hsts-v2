package client.features.results;

import client.events.ClientEventBus;
import client.events.DirectFxThreadPoster;
import client.events.ServerPushEvent;
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
    @DisplayName("the push subscription")
    class PushSubscription {

        private ClientEventBus eventBus;

        @BeforeEach
        void subscribe() {
            eventBus = new ClientEventBus(ClientEventBus.newBus(), new DirectFxThreadPoster());
            session.subscribeTo(eventBus);
        }

        @Test
        @DisplayName("a PUSH_GRADE_PUBLISHED on the bus refreshes the list with no user action")
        void pushRefreshes() {
            connection.replyOk(Verb.MY_GRADES_GET, MyGrades.EMPTY);
            session.load();
            assertThat(session.state()).isEqualTo(AsyncViewState.EMPTY);

            connection.replyOk(Verb.MY_GRADES_GET, new MyGrades(List.of(MAYA_ALGEBRA)));
            eventBus.post(new ServerPushEvent(Verb.PUSH_GRADE_PUBLISHED, MAYA_ALGEBRA));

            // NFR-18: the student pressed nothing. Before this subscription existed,
            // onGradePublished() was a hook nothing called and the screen only ever
            // updated when it was reopened.
            assertThat(session.state()).isEqualTo(AsyncViewState.READY);
            assertThat(session.grades()).containsExactly(MAYA_ALGEBRA);
        }

        @Test
        @DisplayName("the pushed payload is ignored — the list is re-read, never appended to")
        void payloadIsNotTrusted() {
            connection.replyOk(Verb.MY_GRADES_GET, new MyGrades(List.of(MAYA_ALGEBRA)));
            session.load();
            connection.clearSent();

            // A push carrying somebody else's row must not be able to put it on this screen.
            connection.replyOk(Verb.MY_GRADES_GET, new MyGrades(List.of(MAYA_ALGEBRA)));
            eventBus.post(new ServerPushEvent(Verb.PUSH_GRADE_PUBLISHED, ADJUSTED));

            assertThat(connection.sentCount()).isEqualTo(1);
            assertThat(session.grades()).containsExactly(MAYA_ALGEBRA);
        }

        @Test
        @DisplayName("every other push verb is ignored, since one event type carries them all")
        void ignoresOtherVerbs() {
            connection.replyOk(Verb.MY_GRADES_GET, new MyGrades(List.of(MAYA_ALGEBRA)));
            session.load();
            connection.clearSent();

            eventBus.post(new ServerPushEvent(Verb.PUSH_NOTIFICATION, null));

            assertThat(connection.sentCount()).isZero();
        }

        @Test
        @DisplayName("a push carrying no payload still refreshes, because the payload is unused")
        void nullPayloadStillRefreshes() {
            connection.replyOk(Verb.MY_GRADES_GET, MyGrades.EMPTY);
            session.load();

            connection.replyOk(Verb.MY_GRADES_GET, new MyGrades(List.of(MAYA_ALGEBRA)));
            eventBus.post(new ServerPushEvent(Verb.PUSH_GRADE_PUBLISHED, null));

            assertThat(session.grades()).containsExactly(MAYA_ALGEBRA);
        }

        @Test
        @DisplayName("subscribing refuses a null bus rather than silently never refreshing")
        void rejectsNullBus() {
            org.assertj.core.api.Assertions
                    .assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> session.subscribeTo(null));
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

    // ===================== UI wave 2: the hero band ======================

    @Nested
    @DisplayName("The hero band")
    class Hero {

        private static StudentGradeRow scored(long id, int score, String courseCode) {
            return new StudentGradeRow(id, 11, "Maya Levi", score, score, score,
                    GradeState.APPROVED, null, null, Instant.parse("2026-08-06T09:00:00Z"),
                    "Algebra midterm", courseCode, "Dana Cohen");
        }

        @Test
        @DisplayName("the term average is the plain mean of the effective scores")
        void theAverageIsUnweighted() {
            // Unweighted deliberately: weighting by credit, hours or difficulty
            // would be inventing a rule the school has not given us, and a number
            // a student quotes at a teacher must be one the school recognises.
            assertThat(MyGradesSession.termAverage(List.of(
                    scored(1, 60, "11"), scored(2, 80, "11"), scored(3, 70, "12"))))
                    .isEqualTo(70.0);
        }

        @Test
        @DisplayName("⚑ an average of nothing is zero, not a NaN in the ring")
        void anEmptyTranscriptAveragesZero() {
            // A student with no grades is the first-run case, and it is the one
            // that would put "NaN" inside the hero of the screen that is entirely
            // hero.
            assertThat(MyGradesSession.termAverage(List.of())).isZero();
        }

        @Test
        @DisplayName("an overridden grade counts at the score that counts")
        void theEffectiveScoreIsWhatAverages() {
            assertThat(MyGradesSession.termAverage(List.of(ADJUSTED))).isEqualTo(55.0);
        }

        @Test
        @DisplayName("the session's own average follows what it loaded")
        void theSessionAveragesWhatItHas() {
            connection.replyOk(Verb.MY_GRADES_GET, new MyGrades(List.of(
                    scored(1, 60, "11"), scored(2, 90, "11"))));

            session.load();

            assertThat(session.termAverage()).isEqualTo(75.0);
        }

        @Test
        @DisplayName("courses are counted distinctly, case insensitively, blanks ignored")
        void courseCountIsHonest() {
            connection.replyOk(Verb.MY_GRADES_GET, new MyGrades(List.of(
                    scored(1, 60, "11"), scored(2, 70, "11 "), scored(3, 80, "12"),
                    scored(4, 90, "  "), scored(5, 55, null))));

            session.load();

            // A row that arrived unlabelled is a data problem, not a course.
            assertThat(session.courseCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("⚑ the next-exam slot is empty, because no verb answers it")
        void theNextExamSlotIsHonestlyEmpty() {
            // Recorded as a test rather than only as a comment: the day a verb
            // exists, this is the assertion that has to change, which is how the
            // next person finds out the slot was built and waiting.
            connection.replyOk(Verb.MY_GRADES_GET, new MyGrades(List.of(MAYA_ALGEBRA)));
            session.load();

            assertThat(session.nextExam())
                    .as("EXAM_JOIN takes a code a teacher reads out; there is no "
                            + "'list the sittings I could join' read on the wire")
                    .isEmpty();
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
