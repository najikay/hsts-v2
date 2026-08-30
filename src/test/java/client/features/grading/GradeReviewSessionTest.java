package client.features.grading;

import client.events.DirectFxThreadPoster;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.grading.AnswerReviewRow;
import common.dto.grading.ApproveRequest;
import common.dto.grading.ApproveResult;
import common.dto.grading.GradeOverrideRequest;
import common.dto.grading.GradeReview;
import common.dto.grading.GradeReviewRequest;
import common.dto.grading.GradeState;
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
 * {@link GradeReviewSession} — the teacher's marked paper, without a toolkit (E12.6 — F8.2).
 *
 * <p>2026-08-30, live session, U-38. The screen this proves is the one F8.2 was PARTIAL for:
 * until it existed a teacher could approve a paper and change its score without being able to
 * read it. Three verbs, and the tests that carry weight are about what each of their answers is
 * allowed to do to the screen.
 *
 * <p>{@code overrideAdoptsTheAnswer} and {@code approvalRereads} are the pair. The contract has
 * {@code GRADE_OVERRIDE} answer with the refreshed paper, which on this screen is the entire
 * state, so adopting it is correct and a second round trip would be waste. {@code
 * GRADES_APPROVE} answers with a tally, which is not a paper, so it must ask again rather than
 * patch the state to APPROVED itself — the moment it patched would be the moment the server had
 * refused it.
 *
 * <p>{@code aRefusalKeepsThePaperOnScreen} is the third: unlike the student's checked form, a
 * refusal here normally arrives while a paper is being read, and answering "that could not be
 * done" by clearing the screen takes away the thing she was looking at.
 */
class GradeReviewSessionTest {

    private static final long GRADE = 4001L;
    private static final long OTHER_GRADE = 4002L;

    private FakeClientConnection connection;
    private GradeReviewSession session;
    private int renders;

    @BeforeEach
    void setUp() {
        connection = new FakeClientConnection();
        RequestDispatcher dispatcher = new RequestDispatcher(connection);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
        session = new GradeReviewSession(dispatcher, new DirectFxThreadPoster())
                .onChange(() -> renders++);
    }

    // ===================== Fixture ========================================

    private static StudentGradeRow grade(long gradeId, String name, int auto, Integer finalScore,
                                         GradeState state) {
        int effective = finalScore == null ? auto : finalScore;
        return new StudentGradeRow(gradeId, gradeId, name, auto, finalScore, effective, state,
                finalScore == null ? null : "Question 3 was ambiguous.",
                "Strong work on the inequalities.",
                state == GradeState.APPROVED ? Instant.parse("2026-06-03T09:00:00Z") : null);
    }

    private static AnswerReviewRow answer(int ordinal, Byte chosen, byte correct, boolean right) {
        return new AnswerReviewRow(ordinal, "1100" + ordinal,
                "What are the roots of x squared minus 5x plus 6?",
                "1 and 6", "2 and 3", "minus 2 and minus 3", "0 and 5",
                15, chosen, correct, right, right ? 15 : 0);
    }

    private static GradeReview paper() {
        return new GradeReview(grade(GRADE, "מאיה לוי", 70, null, GradeState.AUTO),
                List.of(answer(1, (byte) 2, (byte) 2, true),
                        answer(2, (byte) 1, (byte) 3, false),
                        answer(3, null, (byte) 4, false)));
    }

    private static GradeReview adjustedPaper() {
        return new GradeReview(grade(GRADE, "מאיה לוי", 70, 85, GradeState.AUTO),
                paper().answers());
    }

    private static GradeReview approvedPaper() {
        return new GradeReview(grade(GRADE, "מאיה לוי", 70, 70, GradeState.APPROVED),
                paper().answers());
    }

    /** Loads the paper, leaving the session ready to act on it. */
    private void givenOpenPaper() {
        connection.replyOk(Verb.GRADE_REVIEW_GET, paper());
        session.open(GRADE);
        connection.clearSent();
    }

    // ===================== Loading ========================================

    @Nested
    @DisplayName("Opening a paper")
    class Opening {

        @Test
        @DisplayName("asks GRADE_REVIEW_GET for the id it was given and shows what came back")
        void opens() {
            connection.replyOk(Verb.GRADE_REVIEW_GET, paper());

            session.open(GRADE);

            assertThat(connection.lastSent().getVerb()).isEqualTo(Verb.GRADE_REVIEW_GET);
            assertThat(connection.lastSent().getPayload())
                    .isEqualTo(new GradeReviewRequest(GRADE));
            assertThat(session.state()).isEqualTo(AsyncViewState.READY);
            assertThat(session.grade()).isPresent();
            assertThat(session.grade().orElseThrow().studentName()).isEqualTo("מאיה לוי");
            assertThat(session.answers()).hasSize(3);
            assertThat(renders).isPositive();
        }

        @Test
        @DisplayName("a refusal says the paper could not be opened, without saying why")
        void refusal() {
            connection.replyError(Verb.GRADE_REVIEW_GET, ErrorCode.NOT_FOUND, "no");

            session.open(GRADE);

            assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.error()).contains(GradingCopy.REVIEW_FAILED);
            assertThat(session.grade()).isEmpty();
        }

        @Test
        @DisplayName("asking twice for the same paper while it is in flight is ignored")
        void concurrentOpenIgnored() {
            session.open(GRADE);
            connection.clearSent();

            session.open(GRADE);

            assertThat(connection.sentCount()).isZero();
        }

        /**
         * ⚑ The generation-guard sweep. The view builds one session in {@code build()} and calls
         * {@code open} from {@code onShow}, so two Review clicks on two rows share it and the
         * second can begin before the first has answered. Adopting whichever arrived last shows
         * one student's answers under another student's name, with an Approve button beneath
         * them carrying the id of a paper nobody is reading.
         */
        @Test
        @DisplayName("⚑ the answer for the paper she left loses to the one she opened")
        void aLateAnswerForAnotherPaperIsDropped() {
            // No responder, so both futures stay pending until they are delivered by hand.
            session.open(GRADE);
            session.open(OTHER_GRADE);

            GradeReview other = new GradeReview(
                    grade(OTHER_GRADE, "עומר כץ", 40, null, GradeState.AUTO), List.of());
            connection.deliver(Message.ok(connection.sentMessages().get(1), other));
            connection.deliver(Message.ok(connection.sentMessages().get(0), paper()));

            assertThat(session.grade().orElseThrow().gradeId()).isEqualTo(OTHER_GRADE);
            assertThat(session.grade().orElseThrow().studentName()).isEqualTo("עומר כץ");
        }

        @Test
        @DisplayName("a different paper is asked for even while the first is still loading")
        void asecondPaperIsNotSwallowedByTheGuard() {
            session.open(GRADE);
            connection.clearSent();

            session.open(OTHER_GRADE);

            assertThat(connection.lastSent().getPayload())
                    .isEqualTo(new GradeReviewRequest(OTHER_GRADE));
        }
    }

    // ===================== Approving ======================================

    @Nested
    @DisplayName("Approving")
    class Approving {

        @Test
        @DisplayName("sends the one grade on screen through the bulk verb")
        void sendsOneId() {
            givenOpenPaper();
            connection.replyOk(Verb.GRADES_APPROVE, new ApproveResult(1, 0, List.of()));

            session.approve();

            // One verb for one grade and for a whole execution (E12.2/E12.7). A second
            // single-grade verb would be a second place for the approval rules to live.
            assertThat(connection.sentMessages().get(0).getVerb()).isEqualTo(Verb.GRADES_APPROVE);
            assertThat(connection.sentMessages().get(0).getPayload())
                    .isEqualTo(ApproveRequest.one(GRADE));
        }

        @Test
        @DisplayName("re-reads the paper rather than marking it approved itself")
        void approvalRereads() {
            givenOpenPaper();
            connection.replyOk(Verb.GRADES_APPROVE, new ApproveResult(1, 0, List.of()));
            connection.replyOk(Verb.GRADE_REVIEW_GET, approvedPaper());

            session.approve();

            assertThat(connection.sentMessages()).extracting(Message::getVerb)
                    .containsSubsequence(Verb.GRADES_APPROVE, Verb.GRADE_REVIEW_GET);
            assertThat(session.grade().orElseThrow().state()).isEqualTo(GradeState.APPROVED);
            assertThat(session.canAct())
                    .as("an approved grade can no longer be changed, and the buttons must say so")
                    .isFalse();
        }

        @Test
        @DisplayName("a refused id is reported, because the re-read could never show it")
        void refusedIdIsReported() {
            givenOpenPaper();
            connection.replyOk(Verb.GRADES_APPROVE, new ApproveResult(0, 0, List.of(GRADE)));

            session.approve();

            assertThat(session.error()).contains(GradingCopy.APPROVE_REFUSED);
            // A refused grade and an untouched grade come back from the server identical.
            assertThat(connection.sentMessages()).extracting(Message::getVerb)
                    .doesNotContain(Verb.GRADE_REVIEW_GET);
        }

        @Test
        @DisplayName("a failed approval says so")
        void failure() {
            givenOpenPaper();
            connection.replyError(Verb.GRADES_APPROVE, ErrorCode.INTERNAL, "boom");

            session.approve();

            assertThat(session.error()).contains(GradingCopy.APPROVE_FAILED);
        }

        @Test
        @DisplayName("approving before a paper has loaded sends nothing")
        void nothingToApprove() {
            session.approve();

            assertThat(connection.sentCount()).isZero();
        }
    }

    // ===================== Overriding =====================================

    @Nested
    @DisplayName("Changing the score")
    class Overriding {

        @Test
        @DisplayName("sends the grade on screen with the reason and the comment")
        void sendsTheOverride() {
            givenOpenPaper();
            connection.replyOk(Verb.GRADE_OVERRIDE, adjustedPaper());

            assertThat(session.override(85, "Question 3 was ambiguous.", "Well argued.")).isTrue();

            assertThat(connection.lastSent().getPayload()).isEqualTo(
                    new GradeOverrideRequest(GRADE, 85, "Question 3 was ambiguous.",
                            "Well argued."));
        }

        @Test
        @DisplayName("adopts the refreshed paper the server answers with, and asks nothing more")
        void overrideAdoptsTheAnswer() {
            givenOpenPaper();
            connection.replyOk(Verb.GRADE_OVERRIDE, adjustedPaper());

            session.override(85, "Question 3 was ambiguous.", null);

            // The contract answers with the server's own read, and on this screen that object
            // IS the whole state. The queue cannot do this: there, one row's new score also
            // moves the sitting's counts, which a review knows nothing about.
            assertThat(session.grade().orElseThrow().effectiveScore()).isEqualTo(85);
            assertThat(connection.sentMessages()).extracting(Message::getVerb)
                    .containsExactly(Verb.GRADE_OVERRIDE);
        }

        @Test
        @DisplayName("a blank reason is refused here, before the request travels (S-23)")
        void blankReasonIsRefusedLocally() {
            givenOpenPaper();

            assertThat(session.override(85, "   ", null)).isFalse();

            assertThat(session.error()).contains(GradingCopy.JUSTIFICATION_REQUIRED);
            assertThat(connection.sentCount())
                    .as("the server's check is the one that matters; this one saves her the trip")
                    .isZero();
        }

        @Test
        @DisplayName("a score outside 0..100 is refused here too")
        void scoreOutOfRange() {
            givenOpenPaper();

            assertThat(session.override(101, "Generous marking.", null)).isFalse();

            assertThat(session.error()).contains(GradingCopy.SCORE_OUT_OF_RANGE);
            assertThat(connection.sentCount()).isZero();
        }

        @Test
        @DisplayName("CONFLICT is told as what happened, not as a fault")
        void conflictIsAState() {
            givenOpenPaper();
            connection.replyError(Verb.GRADE_OVERRIDE, ErrorCode.CONFLICT, "approved");

            session.override(85, "Question 3 was ambiguous.", null);

            assertThat(session.error()).contains(GradingCopy.OVERRIDE_CONFLICT);
        }

        /**
         * ⚑ The student's checked form clears itself on a refusal because it has nothing else to
         * show. This screen does have something: the refusal normally arrives while she is
         * reading the paper, and answering "that could not be done" by taking the paper away is
         * the worst reading of a CONFLICT there is.
         */
        @Test
        @DisplayName("⚑ a refusal keeps the paper she is reading on screen")
        void aRefusalKeepsThePaperOnScreen() {
            givenOpenPaper();
            connection.replyError(Verb.GRADE_OVERRIDE, ErrorCode.CONFLICT, "approved");

            session.override(85, "Question 3 was ambiguous.", null);

            assertThat(session.grade()).isPresent();
            assertThat(session.answers()).hasSize(3);
            assertThat(session.state()).isEqualTo(AsyncViewState.READY);
        }

        @Test
        @DisplayName("an override before a paper has loaded sends nothing")
        void nothingToOverride() {
            assertThat(session.override(85, "Generous marking.", null)).isFalse();

            assertThat(connection.sentCount()).isZero();
        }
    }
}
