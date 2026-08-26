package server.features.exambuild;

import common.dto.authoring.TopicQuota;
import common.dto.authoring.AutoComposeRequest;
import common.dto.ErrorPayload;
import common.dto.auth.Role;
import common.dto.authoring.ComposedQuestion;
import common.dto.authoring.ExamComposition;
import common.dto.authoring.ExamCreateRequest;
import common.dto.authoring.ExamList;
import common.dto.authoring.ExamVersionAction;
import common.dto.authoring.ExamVersionRequest;
import common.dto.authoring.ExamVersionSave;
import common.dto.approval.ApprovalState;
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
import server.features.approval.ApprovalService;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ExamHandlers} - the seven builder verbs, their shared gate, and the one call in this epic
 * whose <em>placement</em> is the feature (E7.1, E7.2, E7.3, E7.4, E7.5, E7.6, E7.10).
 *
 * <p>Written against the gate and the seams rather than the happy paths, which are
 * {@link ExamServiceTest}'s. Three things here are load-bearing:
 *
 * <ul>
 *   <li>{@code TheSubmitHook} - {@code EXAM_SUBMIT} must call E8 <b>after the transaction
 *       commits</b>. The test does not check that the call happened; it checks what had happened
 *       to the transaction <em>at the moment</em> it did, because the bug this replaced was a
 *       call that happened and notified nobody. Moving the call inside the lambda leaves every
 *       other assertion in this file green.</li>
 *   <li>{@code TheGate} - role before payload on every verb, so a caller who may not use one
 *       cannot read its refusals to learn what it expects.</li>
 *   <li>{@code Registration} - the seven verbs, asserted as a set. An eighth added here without a
 *       decision fails this rather than passing quietly. It named six until 2026-08-25 and that
 *       is what held {@code EXAM_AUTO_COMPOSE} out while contract §7 was undecided: the absence
 *       was a decision somebody had to change a test to reverse, which is what §7.3a then did.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ExamHandlersTest {

    private static final long TEACHER_ID = 3;
    private static final long OTHER_TEACHER_ID = 9;
    private static final long VERSION_ID = 4100;
    private static final int LOCK_VERSION = 2;
    private static final String COURSE = "11";

    @Mock
    private Session session;
    @Mock
    private ExamService exams;
    @Mock
    private ApprovalService approvals;

    private ExamHandlers handlers;
    private MockSessions.Wiring wiring;

    @BeforeEach
    void setUp() {
        wiring = MockSessions.commitsOn(session);
        handlers = new ExamHandlers(wiring.factory(), exams, approvals);
    }

    // ===================== Fixtures =======================================

    private static CallerContext teacher() {
        return CallerContext.authenticated(null, TEACHER_ID, Role.TEACHER);
    }

    private static CallerContext coordinator() {
        return CallerContext.authenticated(null, TEACHER_ID, Role.COORDINATOR);
    }

    private static CallerContext student() {
        return CallerContext.authenticated(null, OTHER_TEACHER_ID, Role.STUDENT);
    }

    private static Message request(Verb verb, Object payload) {
        return Message.request(verb, payload);
    }

    private static ExamComposition composition() {
        return new ExamComposition(70, "110001", COURSE, "Java", VERSION_ID, 1,
                ApprovalState.DRAFT, "Midterm", 90, "", "", "Dana Levi", Instant.EPOCH, "",
                List.of(new ComposedQuestion(11, "11007", 1, 100, "What is encapsulation?",
                        "OOP", common.dto.bank.Difficulty.EASY, false, 1, 1, 11)),
                LOCK_VERSION);
    }

    private static ExamService.BuildOutcome ok() {
        return ExamService.BuildOutcome.ok(composition());
    }

    private static ExamVersionAction action() {
        return new ExamVersionAction(VERSION_ID, LOCK_VERSION);
    }

    private static String sentenceOf(Message answer) {
        return ((ErrorPayload) answer.getPayload()).message();
    }

    // ===================== Registration ===================================

    @Nested
    @DisplayName("registration")
    class Registration {

        @Test
        @DisplayName("exactly the seven verbs, and none of them open")
        void exactlyTheSevenVerbs() {
            MessageRouter router = new MessageRouter(new SessionManager());

            handlers.registerOn(router);

            // A set rather than seven isRegistered calls: this fails when an eighth verb is
            // added here, which is the case that matters.
            //
            // It named six until 2026-08-25, and it did its job: EXAM_AUTO_COMPOSE was held back
            // while contract section 7 left the crossing-pool report undecided, and this
            // assertion is what made that absence a decision somebody had to change a test to
            // reverse rather than an oversight. Section 7.3a settled it by making crossing pools
            // unrepresentable, so the verb is registered and the count moves with it.
            assertThat(router.registeredVerbs()).containsExactlyInAnyOrder(
                    Verb.EXAM_LIST, Verb.EXAM_VERSION_GET, Verb.EXAM_CREATE,
                    Verb.EXAM_VERSION_SAVE, Verb.EXAM_VERSION_REVISE, Verb.EXAM_SUBMIT,
                    Verb.EXAM_AUTO_COMPOSE);
            assertThat(router.registeredVerbs())
                    .allSatisfy(verb -> assertThat(router.isOpen(verb)).isFalse());
        }

        @Test
        @DisplayName("⚑ auto-compose wears the same role gate as the rest")
        void autoComposeIsGated() {
            assertThatExceptionOfType(AuthorizationException.class)
                    .as("a student who could auto-compose would be reading the bank's topics and "
                            + "difficulty spread through a verb nobody gated")
                    .isThrownBy(() -> handlers.autoCompose(student(),
                            request(Verb.EXAM_AUTO_COMPOSE,
                                    new AutoComposeRequest("11",
                                            List.of(TopicQuota.ofAnyDifficulty(null, 3))))));
            verifyNoInteractions(exams);
        }
    }

    // ===================== The gate =======================================

    @Nested
    @DisplayName("the gate every verb wears")
    class TheGate {

        @Test
        @DisplayName("a student is refused by all six, and the service is never reached")
        void aStudentIsRefusedByAllSix() {
            CallerContext student = student();

            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> handlers.list(student, request(Verb.EXAM_LIST, null)));
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> handlers.get(student,
                            request(Verb.EXAM_VERSION_GET, new ExamVersionRequest(VERSION_ID))));
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> handlers.create(student,
                            request(Verb.EXAM_CREATE, null)));
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> handlers.save(student, request(Verb.EXAM_VERSION_SAVE, null)));
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> handlers.revise(student,
                            request(Verb.EXAM_VERSION_REVISE, action())));
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> handlers.submit(student,
                            request(Verb.EXAM_SUBMIT, action())));

            verifyNoInteractions(exams);
            verifyNoInteractions(approvals);
        }

        @Test
        @DisplayName("a coordinator is staff here: the role gate lets her through")
        void aCoordinatorIsStaff() {
            when(exams.list(any(), any())).thenReturn(new ExamList(List.of()));

            Message answer = handlers.list(coordinator(), request(Verb.EXAM_LIST, null));

            assertThat(answer.isOk()).isTrue();
        }

        @Test
        @DisplayName("a student sending rubbish learns nothing about the payload")
        void aStudentSendingRubbishLearnsNothingAboutThePayload() {
            // Role first, payload second. If the order inverted, this would answer VALIDATION and
            // hand a caller who may not use the verb a description of what it wanted.
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> handlers.save(student(),
                            request(Verb.EXAM_VERSION_SAVE, "not a save at all")));

            verifyNoInteractions(exams);
        }

        @Test
        @DisplayName("the wrong payload type is a sentence, not an exception, and opens no "
                + "transaction")
        void theWrongPayloadTypeIsASentence() {
            Message answer = handlers.get(teacher(),
                    request(Verb.EXAM_VERSION_GET, "an ExamVersionRequest, but as a string"));

            assertThat(answer.isOk()).isFalse();
            assertThat(answer.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(sentenceOf(answer)).isEqualTo(ExamBuildMessages.MALFORMED_REQUEST);
            // The session, not the transaction flag. `committed() == false` is also what an
            // opened-and-rolled-back transaction leaves behind, so it does not distinguish
            // "no transaction" from "a transaction that went wrong" - which is what the name
            // of this test claims. Touching the session at all is the thing being refused.
            verifyNoInteractions(session);
            verifyNoInteractions(exams);
        }

        @Test
        @DisplayName("a missing payload is the same sentence, on every verb that takes one")
        void aMissingPayloadIsTheSameSentence() {
            assertThat(sentenceOf(handlers.get(teacher(), request(Verb.EXAM_VERSION_GET, null))))
                    .isEqualTo(ExamBuildMessages.MALFORMED_REQUEST);
            assertThat(sentenceOf(handlers.create(teacher(), request(Verb.EXAM_CREATE, null))))
                    .isEqualTo(ExamBuildMessages.MALFORMED_REQUEST);
            assertThat(sentenceOf(handlers.save(teacher(), request(Verb.EXAM_VERSION_SAVE, null))))
                    .isEqualTo(ExamBuildMessages.MALFORMED_REQUEST);
            assertThat(sentenceOf(handlers.revise(teacher(),
                    request(Verb.EXAM_VERSION_REVISE, null))))
                    .isEqualTo(ExamBuildMessages.MALFORMED_REQUEST);
            assertThat(sentenceOf(handlers.submit(teacher(), request(Verb.EXAM_SUBMIT, null))))
                    .isEqualTo(ExamBuildMessages.MALFORMED_REQUEST);

            verifyNoInteractions(exams);
            verifyNoInteractions(approvals);
        }
    }

    // ===================== Outcome mapping ================================

    @Nested
    @DisplayName("one outcome, one code")
    class OutcomeMapping {

        @Test
        @DisplayName("OK answers with the composition the service read back")
        void okAnswersWithTheComposition() {
            when(exams.get(any(), any(), any())).thenReturn(ok());

            Message answer = handlers.get(teacher(),
                    request(Verb.EXAM_VERSION_GET, new ExamVersionRequest(VERSION_ID)));

            assertThat(answer.isOk()).isTrue();
            assertThat(answer.getPayload()).isEqualTo(composition());
            assertThat(wiring.tx().committed()).isTrue();
        }

        @Test
        @DisplayName("NOT_FOUND stays NOT_FOUND, so a probe cannot tell hers from missing")
        void notFoundStaysNotFound() {
            when(exams.get(any(), any(), any()))
                    .thenReturn(ExamService.BuildOutcome.notFound());

            Message answer = handlers.get(teacher(),
                    request(Verb.EXAM_VERSION_GET, new ExamVersionRequest(VERSION_ID)));

            assertThat(answer.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(sentenceOf(answer)).isEqualTo(ExamBuildMessages.EXAM_NOT_FOUND);
        }

        @Test
        @DisplayName("INVALID is VALIDATION and carries the service's own sentence")
        void invalidIsValidation() {
            when(exams.save(any(), any(), any())).thenReturn(
                    ExamService.BuildOutcome.invalid(new ExamValidator.Violation(
                            ExamValidator.FIELD_QUESTIONS, ExamBuildMessages.pointsShort(90))));

            Message answer = handlers.save(teacher(),
                    request(Verb.EXAM_VERSION_SAVE, saveOf()));

            assertThat(answer.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(sentenceOf(answer)).isEqualTo(ExamBuildMessages.pointsShort(90));
        }

        @Test
        @DisplayName("CONFLICT is CONFLICT, and the sentence is what separates its three causes")
        void conflictIsConflict() {
            when(exams.revise(any(), any(), any())).thenReturn(
                    ExamService.BuildOutcome.conflict(ExamBuildMessages.ALREADY_A_DRAFT));

            Message answer = handlers.revise(teacher(),
                    request(Verb.EXAM_VERSION_REVISE, action()));

            assertThat(answer.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(sentenceOf(answer)).isEqualTo(ExamBuildMessages.ALREADY_A_DRAFT);
        }

        @Test
        @DisplayName("the create guard's FORBIDDEN travels out rather than becoming NOT_FOUND")
        void theCreateGuardsForbiddenTravels() {
            // EXAM_CREATE is the one verb whose scope refusal names the course, because she
            // supplied it. Folding it into NOT_FOUND here would hide a real answer.
            when(exams.create(any(), any(), any()))
                    .thenThrow(new AuthorizationException(ErrorCode.FORBIDDEN, "not your course"));

            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> handlers.create(teacher(),
                            request(Verb.EXAM_CREATE, createOf())));
        }
    }

    // ===================== Forwarding =====================================

    @Nested
    @DisplayName("what reaches the service is what arrived")
    class Forwarding {

        @Test
        @DisplayName("each verb hands the service the payload it was sent and the calling teacher")
        void eachVerbForwardsItsOwnPayloadAndCaller() {
            // The hole this closes: every other test in this file stubs with any() and asserts
            // on the answer, so a handler that passed a DIFFERENT payload instance - a fresh
            // ExamVersionRequest(0), or another caller's context - would leave all of them
            // green. The answer is the service's mock return either way. Identity is the only
            // thing that can tell a correct forward from a plausible one.
            CallerContext caller = teacher();
            ExamVersionRequest getPayload = new ExamVersionRequest(VERSION_ID);
            ExamVersionSave savePayload = saveOf();
            ExamVersionAction revisePayload = action();
            ExamCreateRequest createPayload = createOf();
            ExamVersionAction submitPayload = action();

            when(exams.get(any(), any(), any())).thenReturn(ok());
            when(exams.save(any(), any(), any())).thenReturn(ok());
            when(exams.revise(any(), any(), any())).thenReturn(ok());
            when(exams.create(any(), any(), any())).thenReturn(ok());
            when(exams.submitForApproval(any(), any(), any())).thenReturn(ok());

            handlers.get(caller, request(Verb.EXAM_VERSION_GET, getPayload));
            handlers.save(caller, request(Verb.EXAM_VERSION_SAVE, savePayload));
            handlers.revise(caller, request(Verb.EXAM_VERSION_REVISE, revisePayload));
            handlers.create(caller, request(Verb.EXAM_CREATE, createPayload));
            handlers.submit(caller, request(Verb.EXAM_SUBMIT, submitPayload));

            verify(exams).get(same(session), same(caller), same(getPayload));
            verify(exams).save(same(session), same(caller), same(savePayload));
            verify(exams).revise(same(session), same(caller), same(revisePayload));
            verify(exams).create(same(session), same(caller), same(createPayload));
            verify(exams).submitForApproval(same(session), same(caller), same(submitPayload));
        }

        @Test
        @DisplayName("EXAM_LIST hands the service the session's caller, since it has no payload")
        void listForwardsTheCaller() {
            CallerContext caller = teacher();
            when(exams.list(any(), any())).thenReturn(new ExamList(List.of()));

            handlers.list(caller, request(Verb.EXAM_LIST, null));

            verify(exams).list(same(session), same(caller));
        }
    }

    // ===================== EXAM_LIST ======================================

    @Nested
    @DisplayName("EXAM_LIST")
    class TheList {

        @Test
        @DisplayName("takes no payload, and answers from the session's own caller")
        void takesNoPayload() {
            ExamList list = new ExamList(List.of());
            when(exams.list(any(), any())).thenReturn(list);

            Message answer = handlers.list(teacher(), request(Verb.EXAM_LIST, null));

            assertThat(answer.isOk()).isTrue();
            assertThat(answer.getPayload()).isSameAs(list);
            verify(exams).list(any(), any());
        }

        @Test
        @DisplayName("a payload sent anyway is ignored rather than refused")
        void aPayloadSentAnywayIsIgnored() {
            when(exams.list(any(), any())).thenReturn(new ExamList(List.of()));

            Message answer = handlers.list(teacher(),
                    request(Verb.EXAM_LIST, "whatever the client felt like sending"));

            assertThat(answer.isOk()).isTrue();
        }
    }

    // ===================== The submit hook =================================

    @Nested
    @DisplayName("the submit hook, whose placement is the feature")
    class TheSubmitHook {

        @Test
        @DisplayName("E8's hook runs, and the transaction had already committed when it did")
        void theHookRunsAfterTheCommit() {
            // THE TEST THAT MATTERS IN THIS FILE. Not "was versionSubmitted called" - the bug it
            // replaced called it and notified nobody, because from inside the caller's
            // transaction the hook opens a fresh session, reads the row as still DRAFT and
            // returns at its own isPending guard. So the assertion is about the state of the
            // transaction AT THE MOMENT OF THE CALL, which is the mechanism the property lives
            // in. Move the call inside Transactions.inTx and this is the only test that fails.
            AtomicBoolean committedWhenTheHookRan = new AtomicBoolean();
            when(exams.submitForApproval(any(), any(), any())).thenReturn(ok());
            when(approvals.versionSubmitted(VERSION_ID)).thenAnswer(invocation -> {
                committedWhenTheHookRan.set(wiring.tx().committed());
                return 1;
            });

            Message answer = handlers.submit(teacher(), request(Verb.EXAM_SUBMIT, action()));

            assertThat(answer.isOk()).isTrue();
            assertThat(committedWhenTheHookRan)
                    .as("the approval hook must run after the submit has committed, or it reads "
                            + "the version as DRAFT and notifies nobody")
                    .isTrue();
        }

        @Test
        @DisplayName("the hook is called with the version id off the request, once")
        void theHookIsCalledWithTheVersionId() {
            when(exams.submitForApproval(any(), any(), any())).thenReturn(ok());

            handlers.submit(teacher(), request(Verb.EXAM_SUBMIT, action()));

            verify(approvals).versionSubmitted(VERSION_ID);
        }

        @Test
        @DisplayName("a refused submit notifies nobody: NOT_FOUND")
        void aRefusedSubmitNotifiesNobodyNotFound() {
            when(exams.submitForApproval(any(), any(), any()))
                    .thenReturn(ExamService.BuildOutcome.notFound());

            Message answer = handlers.submit(teacher(), request(Verb.EXAM_SUBMIT, action()));

            assertThat(answer.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            verify(approvals, never()).versionSubmitted(anyLong());
        }

        @Test
        @DisplayName("a refused submit notifies nobody: CONFLICT on a version that is not a DRAFT")
        void aRefusedSubmitNotifiesNobodyConflict() {
            when(exams.submitForApproval(any(), any(), any()))
                    .thenReturn(ExamService.BuildOutcome.conflict(ExamBuildMessages.NOT_A_DRAFT));

            Message answer = handlers.submit(teacher(), request(Verb.EXAM_SUBMIT, action()));

            assertThat(answer.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            verify(approvals, never()).versionSubmitted(anyLong());
        }

        @Test
        @DisplayName("a refused submit notifies nobody: VALIDATION")
        void aRefusedSubmitNotifiesNobodyValidation() {
            when(exams.submitForApproval(any(), any(), any())).thenReturn(
                    ExamService.BuildOutcome.invalid(new ExamValidator.Violation(
                            ExamValidator.FIELD_QUESTIONS, ExamBuildMessages.pointsShort(90))));

            Message answer = handlers.submit(teacher(), request(Verb.EXAM_SUBMIT, action()));

            assertThat(answer.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            verify(approvals, never()).versionSubmitted(anyLong());
        }

        @Test
        @DisplayName("a hook that throws does not turn a committed submission into an error")
        void aHookThatThrowsDoesNotFailTheSubmit() {
            // She is told the submit worked, because it did: the transaction is committed and the
            // coordinator's queue reads status rather than notifications. Telling her it failed
            // would have her submit again over a version that is already PENDING.
            when(exams.submitForApproval(any(), any(), any())).thenReturn(ok());
            when(approvals.versionSubmitted(VERSION_ID))
                    .thenThrow(new IllegalStateException("the notifier is down"));

            Message answer = handlers.submit(teacher(), request(Verb.EXAM_SUBMIT, action()));

            assertThat(answer.isOk()).isTrue();
            assertThat(answer.getPayload()).isEqualTo(composition());
        }

        @Test
        @DisplayName("no other verb calls the hook")
        void noOtherVerbCallsTheHook() {
            // The supersede is EXAM_SUBMIT's alone. A save or a revise that fired it would send
            // a coordinator's other pending versions back on behalf of a submission nobody made.
            when(exams.save(any(), any(), any())).thenReturn(ok());
            when(exams.revise(any(), any(), any())).thenReturn(ok());
            when(exams.create(any(), any(), any())).thenReturn(ok());
            when(exams.get(any(), any(), any())).thenReturn(ok());

            handlers.save(teacher(), request(Verb.EXAM_VERSION_SAVE, saveOf()));
            handlers.revise(teacher(), request(Verb.EXAM_VERSION_REVISE, action()));
            handlers.create(teacher(), request(Verb.EXAM_CREATE, createOf()));
            handlers.get(teacher(), request(Verb.EXAM_VERSION_GET,
                    new ExamVersionRequest(VERSION_ID)));

            verifyNoInteractions(approvals);
        }
    }

    // ===================== Payload fixtures ===============================

    private static ExamVersionSave saveOf() {
        return new ExamVersionSave(VERSION_ID, LOCK_VERSION, "Midterm", 90, "", "", List.of());
    }

    private static ExamCreateRequest createOf() {
        return new ExamCreateRequest(COURSE, "Midterm", 90, "", "", List.of());
    }
}
