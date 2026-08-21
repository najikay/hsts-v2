package server.features.grading;

import common.dto.ErrorPayload;
import common.dto.auth.Role;
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
import server.db.entities.AttemptStatus;
import server.db.entities.ExecutionStatus;
import server.db.entities.Grade;
import server.db.projections.AttemptRecord;
import server.db.projections.ExecutionContext;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GradingHandlers} — the three teacher verbs and the one gate they share (E12).
 *
 * <p>This is the class where a security rule would be easiest to lose, so the tests are written
 * against the <em>gate</em> rather than against each verb's happy path. {@code TheGate} runs the
 * same assertions across all three verbs on purpose: the value of writing an authorization shape
 * once is only real if all three actually wear it, and a test per verb is what proves a future
 * fourth verb was added to the shared path rather than beside it.
 *
 * <p>{@code studentSendingRubbishLearnsNothingAboutThePayload} is the ordering test. Role is
 * checked before the payload is examined, so a caller who may not use the verb at all cannot
 * use its error messages to discover what it expects.
 */
@ExtendWith(MockitoExtension.class)
class GradingHandlersTest {

    private static final long GRADE_ID = 900;
    private static final long ATTEMPT_ID = 500;
    private static final long EXECUTION_ID = 4821;
    private static final long TEACHER_ID = 3;
    private static final long OTHER_TEACHER = 99;

    @Mock
    private Session session;
    @Mock
    private ApprovalService approvals;
    @Mock
    private OverrideService overrides;
    @Mock
    private GradeReviewService reviews;

    private GradingHandlers handlers;
    private MockSessions.Wiring wiring;

    @BeforeEach
    void setUp() {
        wiring = MockSessions.commitsOn(session);
        handlers = new GradingHandlers(wiring.factory(), approvals, overrides, reviews);
    }

    // ===================== Fixtures =======================================

    private static CallerContext teacher() {
        return CallerContext.authenticated(null, TEACHER_ID, Role.TEACHER);
    }

    private static CallerContext coordinator() {
        return CallerContext.authenticated(null, TEACHER_ID, Role.COORDINATOR);
    }

    private static CallerContext student() {
        return CallerContext.authenticated(null, 11, Role.STUDENT);
    }

    private static Message request(Verb verb, Object payload) {
        return Message.request(verb, payload);
    }

    /** The sentence inside an error response, which travels as an {@link ErrorPayload}. */
    private static String errorText(Message response) {
        return ((ErrorPayload) response.getPayload()).message();
    }

    private static GradeReview aReview() {
        return new GradeReview(new StudentGradeRow(GRADE_ID, 11, "מאיה לוי", 75, null, 75,
                GradeState.AUTO, null, null, null), List.of());
    }

    private static GradeReviewService.ReviewContext contextOwnedBy(long teacherId) {
        Grade grade = new Grade(ATTEMPT_ID, 75);
        return new GradeReviewService.ReviewContext(
                grade,
                new AttemptRecord(ATTEMPT_ID, EXECUTION_ID, 11,
                        Instant.parse("2026-06-01T08:00:00Z"),
                        Instant.parse("2026-06-01T09:00:00Z"), 60, AttemptStatus.SUBMITTED),
                new ExecutionContext(EXECUTION_ID, 77, 12, "01", "Java", "Java midterm",
                        60, null, "4821", ExecutionStatus.CLOSED,
                        Instant.parse("2026-06-01T08:00:00Z"),
                        Instant.parse("2026-06-01T10:00:00Z"), 0, teacherId, teacherId));
    }

    // ===================== Registration ===================================

    @Test
    @DisplayName("registers exactly the three teacher grading verbs")
    void registersTheThreeVerbs() {
        MessageRouter router = new MessageRouter(new SessionManager());

        handlers.registerOn(router);

        assertThat(router.isRegistered(Verb.GRADES_APPROVE)).isTrue();
        assertThat(router.isRegistered(Verb.GRADE_OVERRIDE)).isTrue();
        assertThat(router.isRegistered(Verb.GRADE_REVIEW_GET)).isTrue();
        // None of them is reachable without a session.
        assertThat(router.isOpen(Verb.GRADES_APPROVE)).isFalse();
        assertThat(router.isOpen(Verb.GRADE_OVERRIDE)).isFalse();
        assertThat(router.isOpen(Verb.GRADE_REVIEW_GET)).isFalse();
    }

    // ===================== The shared gate ================================

    @Nested
    @DisplayName("The gate, on every verb")
    class TheGate {

        @Test
        @DisplayName("refuses a student on all three")
        void refusesStudents() {
            assertThatExceptionOfType(AuthorizationException.class).isThrownBy(() ->
                    handlers.approve(student(), request(Verb.GRADES_APPROVE,
                            new ApproveRequest(List.of(GRADE_ID)))));
            assertThatExceptionOfType(AuthorizationException.class).isThrownBy(() ->
                    handlers.override(student(), request(Verb.GRADE_OVERRIDE,
                            new GradeOverrideRequest(GRADE_ID, 80, "because"))));
            assertThatExceptionOfType(AuthorizationException.class).isThrownBy(() ->
                    handlers.review(student(), request(Verb.GRADE_REVIEW_GET,
                            new GradeReviewRequest(GRADE_ID))));
        }

        @Test
        @DisplayName("accepts a coordinator, who is a teacher with an extra hat")
        void acceptsCoordinators() {
            when(approvals.approve(any(), anyLong(), any()))
                    .thenReturn(new ApproveResult(1, 0, List.of()));

            Message response = handlers.approve(coordinator(),
                    request(Verb.GRADES_APPROVE, new ApproveRequest(List.of(GRADE_ID))));

            assertThat(response.isOk()).isTrue();
        }

        @Test
        @DisplayName("answers VALIDATION for a payload of the wrong type, on all three")
        void refusesWrongPayloadType() {
            assertThat(handlers.approve(teacher(), request(Verb.GRADES_APPROVE, "nonsense"))
                    .getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(handlers.override(teacher(), request(Verb.GRADE_OVERRIDE, 42))
                    .getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(handlers.review(teacher(), request(Verb.GRADE_REVIEW_GET, null))
                    .getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
        }

        @Test
        @DisplayName("checks the role before it looks at the payload, so rubbish teaches nothing")
        void studentSendingRubbishLearnsNothingAboutThePayload() {
            // A student sending a malformed payload must be refused for being a student, not
            // told that the payload was the problem.
            assertThatExceptionOfType(AuthorizationException.class).isThrownBy(() ->
                    handlers.approve(student(), request(Verb.GRADES_APPROVE, "nonsense")));
        }

        @Test
        @DisplayName("opens no transaction for a request it refuses on the payload")
        void noTransactionForARefusedPayload() {
            handlers.review(teacher(), request(Verb.GRADE_REVIEW_GET, "nonsense"));

            assertThat(wiring.tx().committed()).isFalse();
            assertThat(wiring.tx().rolledBack()).isFalse();
        }

        @Test
        @DisplayName("commits the transaction it did open, even when answering NOT_FOUND")
        void commitsEvenOnANotFoundAnswer() {
            when(reviews.contextOf(session, GRADE_ID)).thenReturn(Optional.empty());

            Message response = handlers.review(teacher(),
                    request(Verb.GRADE_REVIEW_GET, new GradeReviewRequest(GRADE_ID)));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(wiring.tx().committed()).isTrue();
        }
    }

    // ===================== GRADES_APPROVE =================================

    @Nested
    @DisplayName("GRADES_APPROVE")
    class Approve {

        @Test
        @DisplayName("passes the caller's own id, never anything from the payload")
        void passesTheSessionId() {
            when(approvals.approve(any(), anyLong(), any()))
                    .thenReturn(new ApproveResult(1, 0, List.of()));

            handlers.approve(teacher(),
                    request(Verb.GRADES_APPROVE, new ApproveRequest(List.of(GRADE_ID))));

            verify(approvals).approve(session, TEACHER_ID,
                    new ApproveRequest(List.of(GRADE_ID)));
        }

        @Test
        @DisplayName("is OK even when part of the batch was refused")
        void partialSuccessIsStillOk() {
            when(approvals.approve(any(), anyLong(), any()))
                    .thenReturn(new ApproveResult(8, 0, List.of(1L, 2L)));

            Message response = handlers.approve(teacher(),
                    request(Verb.GRADES_APPROVE, new ApproveRequest(List.of(GRADE_ID))));

            assertThat(response.isOk()).isTrue();
            ApproveResult result = (ApproveResult) response.getPayload();
            assertThat(result.approved()).isEqualTo(8);
            assertThat(result.refused()).containsExactly(1L, 2L);
        }
    }

    // ===================== GRADE_OVERRIDE =================================

    @Nested
    @DisplayName("GRADE_OVERRIDE")
    class Override {

        @Test
        @DisplayName("refuses a blank justification without reading anything (S-23)")
        void blankJustificationIsValidation() {
            Message response = handlers.override(teacher(), request(Verb.GRADE_OVERRIDE,
                    new GradeOverrideRequest(GRADE_ID, 80, "   ")));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(errorText(response)).isEqualTo(GradingMessages.JUSTIFICATION_REQUIRED);
            verify(overrides, never()).override(any(), anyLong(), any());
            assertThat(wiring.tx().committed()).isFalse();
        }

        @Test
        @DisplayName("refuses a null justification the same way as a blank one")
        void nullJustificationIsValidation() {
            Message response = handlers.override(teacher(), request(Verb.GRADE_OVERRIDE,
                    new GradeOverrideRequest(GRADE_ID, 80, null)));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            verify(overrides, never()).override(any(), anyLong(), any());
        }

        @Test
        @DisplayName("refuses a score outside 0..100 before reading anything")
        void scoreOutOfRange() {
            assertThat(errorText(handlers.override(teacher(), request(Verb.GRADE_OVERRIDE,
                    new GradeOverrideRequest(GRADE_ID, 101, "because")))))
                    .isEqualTo(GradingMessages.SCORE_OUT_OF_RANGE);
            assertThat(handlers.override(teacher(), request(Verb.GRADE_OVERRIDE,
                    new GradeOverrideRequest(GRADE_ID, -1, "because"))).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION);
            verify(overrides, never()).override(any(), anyLong(), any());
        }

        @Test
        @DisplayName("accepts the boundary scores, which are legitimate results")
        void boundaryScoresAreAccepted() {
            when(overrides.override(any(), anyLong(), any())).thenReturn(
                    new OverrideService.OverrideOutcome(
                            OverrideService.Outcome.OVERRIDDEN, aReview()));

            assertThat(handlers.override(teacher(), request(Verb.GRADE_OVERRIDE,
                    new GradeOverrideRequest(GRADE_ID, 0, "nothing was answered"))).isOk())
                    .isTrue();
            assertThat(handlers.override(teacher(), request(Verb.GRADE_OVERRIDE,
                    new GradeOverrideRequest(GRADE_ID, 100, "full marks after review"))).isOk())
                    .isTrue();
        }

        @Test
        @DisplayName("answers with the refreshed review")
        void answersWithTheReview() {
            GradeReview refreshed = aReview();
            when(overrides.override(any(), anyLong(), any())).thenReturn(
                    new OverrideService.OverrideOutcome(
                            OverrideService.Outcome.OVERRIDDEN, refreshed));

            Message response = handlers.override(teacher(), request(Verb.GRADE_OVERRIDE,
                    new GradeOverrideRequest(GRADE_ID, 80, "question 3 was ambiguous")));

            assertThat(response.isOk()).isTrue();
            assertThat(response.getPayload()).isSameAs(refreshed);
        }

        @Test
        @DisplayName("an already-approved grade is CONFLICT, with a sentence that explains why")
        void approvedIsConflict() {
            when(overrides.override(any(), anyLong(), any())).thenReturn(
                    new OverrideService.OverrideOutcome(
                            OverrideService.Outcome.ALREADY_APPROVED, null));

            Message response = handlers.override(teacher(), request(Verb.GRADE_OVERRIDE,
                    new GradeOverrideRequest(GRADE_ID, 80, "question 3 was ambiguous")));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(errorText(response)).isEqualTo(GradingMessages.ALREADY_APPROVED);
        }

        @Test
        @DisplayName("an unknown or unowned grade is NOT_FOUND, with one sentence for both")
        void unknownIsNotFound() {
            when(overrides.override(any(), anyLong(), any())).thenReturn(
                    new OverrideService.OverrideOutcome(OverrideService.Outcome.NOT_FOUND, null));

            Message response = handlers.override(teacher(), request(Verb.GRADE_OVERRIDE,
                    new GradeOverrideRequest(GRADE_ID, 80, "question 3 was ambiguous")));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(errorText(response)).isEqualTo(GradingMessages.NO_SUCH_GRADE);
        }
    }

    // ===================== GRADE_REVIEW_GET ===============================

    @Nested
    @DisplayName("GRADE_REVIEW_GET")
    class Review {

        @Test
        @DisplayName("serves the paper to the teacher who owns the execution")
        void servesTheOwner() {
            GradeReviewService.ReviewContext context = contextOwnedBy(TEACHER_ID);
            when(reviews.contextOf(session, GRADE_ID)).thenReturn(Optional.of(context));
            when(reviews.review(session, context)).thenReturn(aReview());

            Message response = handlers.review(teacher(),
                    request(Verb.GRADE_REVIEW_GET, new GradeReviewRequest(GRADE_ID)));

            assertThat(response.isOk()).isTrue();
            assertThat(response.getPayload()).isInstanceOf(GradeReview.class);
        }

        @Test
        @DisplayName("refuses another teacher's grade, and never assembles it")
        void refusesAnotherTeachersGrade() {
            when(reviews.contextOf(session, GRADE_ID))
                    .thenReturn(Optional.of(contextOwnedBy(OTHER_TEACHER)));

            Message response = handlers.review(teacher(),
                    request(Verb.GRADE_REVIEW_GET, new GradeReviewRequest(GRADE_ID)));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            // The answer key was never built, let alone serialized.
            verify(reviews, never()).review(any(), any());
        }

        @Test
        @DisplayName("answers an unknown grade exactly as it answers somebody else's")
        void unknownAndUnownedAreIndistinguishable() {
            when(reviews.contextOf(session, GRADE_ID)).thenReturn(Optional.empty());
            Message missing = handlers.review(teacher(),
                    request(Verb.GRADE_REVIEW_GET, new GradeReviewRequest(GRADE_ID)));

            when(reviews.contextOf(session, GRADE_ID))
                    .thenReturn(Optional.of(contextOwnedBy(OTHER_TEACHER)));
            Message notMine = handlers.review(teacher(),
                    request(Verb.GRADE_REVIEW_GET, new GradeReviewRequest(GRADE_ID)));

            assertThat(notMine.getErrorCode()).isEqualTo(missing.getErrorCode());
            assertThat(errorText(notMine)).isEqualTo(errorText(missing));
        }
    }

    @Test
    @DisplayName("rejects null collaborators at construction rather than at first request")
    void rejectsNullCollaborators() {
        assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                new GradingHandlers(null, approvals, overrides, reviews));
        assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                new GradingHandlers(wiring.factory(), null, overrides, reviews));
        assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                new GradingHandlers(wiring.factory(), approvals, null, reviews));
        assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                new GradingHandlers(wiring.factory(), approvals, overrides, null));
        assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                handlers.registerOn(null));
    }
}
