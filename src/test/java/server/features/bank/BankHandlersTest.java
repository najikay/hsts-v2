package server.features.bank;

import common.dto.ErrorPayload;
import common.dto.auth.Role;
import common.dto.bank.BankChanged;
import common.dto.bank.DeleteOutcome;
import common.dto.bank.Difficulty;
import common.dto.bank.ImageAction;
import common.dto.bank.QuestionDeleteRequest;
import common.dto.bank.QuestionDetail;
import common.dto.bank.QuestionDraft;
import common.dto.bank.QuestionEdit;
import common.dto.lock.LockHolder;
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
import server.db.repos.CourseRepository;
import server.realtime.PushGateway;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BankHandlers} - the three write verbs and the gate they share (E6.1, E6.3, E6.4).
 *
 * <p>Written against the gate rather than the happy paths, for the reason {@code GradingHandlers}
 * is: an authorization shape written once is only worth anything if every verb actually wears it,
 * and a test per verb is what proves a future fourth verb was added to the shared path rather than
 * beside it.
 *
 * <p>{@code TheGate} carries two ordering tests that are the point of this class:
 *
 * <ul>
 *   <li>{@code studentSendingRubbishLearnsNothingAboutThePayload} - role is checked before the
 *       payload is examined, so a caller who may not use the verb cannot read its error messages
 *       to discover what it expects.</li>
 *   <li>{@code aNullAnswerElementIsASentenceAndNotAnException} - the E1.11 seam. The inbound
 *       records let a null element survive construction on purpose, so this is where it has to
 *       become a named refusal. If validation moved after the service call, or if the validator's
 *       structural rules stopped running first, this test throws instead of answering.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class BankHandlersTest {

    private static final long TEACHER_ID = 3;
    private static final String COURSE = "11";
    private static final String DISPLAY_ID = "11007";
    private static final List<String> ANSWERS =
            List.of("Encapsulation", "Inheritance", "Polymorphism", "Abstraction");

    @Mock
    private Session session;
    @Mock
    private QuestionService questions;
    @Mock
    private CourseRepository courses;
    @Mock
    private PushGateway pushGateway;

    private BankHandlers handlers;
    private MockSessions.Wiring wiring;

    @BeforeEach
    void setUp() {
        wiring = MockSessions.commitsOn(session);
        // A mocked gateway rather than a real one over an empty SessionManager: U-63's push
        // is a thing this class does, so the tests below assert on it by name.
        handlers = new BankHandlers(wiring.factory(), questions, courses, pushGateway);
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

    private static CallerContext principal() {
        return CallerContext.authenticated(null, 900, Role.PRINCIPAL);
    }

    private static Message request(Verb verb, Object payload) {
        return Message.request(verb, payload);
    }

    private static String errorText(Message response) {
        return ((ErrorPayload) response.getPayload()).message();
    }

    private static QuestionDraft aDraft() {
        return new QuestionDraft(COURSE, "What is encapsulation?", ANSWERS, 1, "OOP",
                Difficulty.MEDIUM, null);
    }

    private static QuestionEdit anEdit() {
        return new QuestionEdit(DISPLAY_ID, 2, "What is encapsulation?", ANSWERS, 1, "OOP",
                Difficulty.MEDIUM, ImageAction.KEEP, null);
    }

    private static QuestionDetail aDetail() {
        return new QuestionDetail(DISPLAY_ID, COURSE, "Java", 1, 1, "What is encapsulation?",
                ANSWERS, 1, "OOP", Difficulty.MEDIUM, false, "Dana Cohen",
                Instant.parse("2026-08-21T20:00:00Z"));
    }

    // ===================== Registration ===================================

    @Test
    @DisplayName("registers exactly the three write verbs, none of them open")
    void registersTheThreeWriteVerbs() {
        MessageRouter router = new MessageRouter(new SessionManager());

        handlers.registerOn(router);

        assertThat(router.isRegistered(Verb.QUESTION_CREATE)).isTrue();
        assertThat(router.isRegistered(Verb.QUESTION_UPDATE)).isTrue();
        assertThat(router.isRegistered(Verb.QUESTION_DELETE)).isTrue();
        assertThat(router.isOpen(Verb.QUESTION_CREATE)).isFalse();
        assertThat(router.isOpen(Verb.QUESTION_UPDATE)).isFalse();
        assertThat(router.isOpen(Verb.QUESTION_DELETE)).isFalse();

        // The read verbs carry a different guard and are not this class's to answer. Adding
        // one here rather than beside its own guard is the mistake section 3's table exists
        // to make visible, so it is worth a failing test rather than a review comment.
        assertThat(router.isRegistered(Verb.BANK_LIST)).isFalse();
        assertThat(router.isRegistered(Verb.QUESTION_GET)).isFalse();
    }

    // ===================== The shared gate ================================

    @Nested
    @DisplayName("The gate, on every verb")
    class TheGate {

        @Test
        @DisplayName("refuses a student on all three")
        void refusesStudents() {
            assertThatExceptionOfType(AuthorizationException.class).isThrownBy(() ->
                    handlers.create(student(), request(Verb.QUESTION_CREATE, aDraft())));
            assertThatExceptionOfType(AuthorizationException.class).isThrownBy(() ->
                    handlers.update(student(), request(Verb.QUESTION_UPDATE, anEdit())));
            assertThatExceptionOfType(AuthorizationException.class).isThrownBy(() ->
                    handlers.delete(student(), request(Verb.QUESTION_DELETE,
                            new QuestionDeleteRequest(DISPLAY_ID, 1))));
        }

        @Test
        @DisplayName("refuses the principal on all three, because F9.3 gives her zero writes")
        void refusesThePrincipal() {
            // Section 2 states this as the strongest role claim in the contract: the principal
            // reads every course and mutates nothing, ever. On QUESTION_CREATE it is gated
            // twice, because requireTeachesCourse runs requireRole itself. On UPDATE and
            // DELETE the boolean teachesCourse performs no role check by design, so the ONLY
            // thing standing between a principal and editing any question in the school is the
            // role list in asAuthor. Adding PRINCIPAL to that list is exactly the edit someone
            // makes when a principal reports the bank screen half-works, and until now nothing
            // would have gone red.
            assertThatExceptionOfType(AuthorizationException.class).isThrownBy(() ->
                    handlers.create(principal(), request(Verb.QUESTION_CREATE, aDraft())));
            assertThatExceptionOfType(AuthorizationException.class).isThrownBy(() ->
                    handlers.update(principal(), request(Verb.QUESTION_UPDATE, anEdit())));
            assertThatExceptionOfType(AuthorizationException.class).isThrownBy(() ->
                    handlers.delete(principal(), request(Verb.QUESTION_DELETE,
                            new QuestionDeleteRequest(DISPLAY_ID, 1))));

            verify(questions, never()).create(any(), any(), any());
            verify(questions, never()).update(any(), any(), any());
            verify(questions, never()).delete(any(), any(), any());
        }

        @Test
        @DisplayName("a draft with no course is VALIDATION naming the course, not FORBIDDEN")
        void draftWithoutACourseIsValidation() {
            // Section 6: a malformed payload answers VALIDATION with the field named, and
            // BAD_REQUEST is deliberately unused. Before this rule existed the draft passed
            // validation, opened a transaction and hit the scope guard, which refused it as
            // FORBIDDEN with a sentence written for the edit screen: "that question is not
            // linked to a course, so it cannot be changed", on a create, naming no field.
            for (String noCourse : new String[]{null, "", "   "}) {
                QuestionDraft draft = new QuestionDraft(noCourse, "What is encapsulation?",
                        ANSWERS, 1, "OOP", Difficulty.MEDIUM, null);

                Message response =
                        handlers.create(teacher(), request(Verb.QUESTION_CREATE, draft));

                assertThat(response.getErrorCode())
                        .as("course %s", noCourse).isEqualTo(ErrorCode.VALIDATION);
                assertThat(errorText(response)).isEqualTo(BankMessages.COURSE_REQUIRED);
            }
            verify(questions, never()).create(any(), any(), any());
        }

        @Test
        @DisplayName("accepts a coordinator, who authors in the courses she teaches")
        void acceptsCoordinators() {
            when(questions.create(any(), any(), any())).thenReturn(aDetail());

            Message response =
                    handlers.create(coordinator(), request(Verb.QUESTION_CREATE, aDraft()));

            assertThat(response.isOk()).isTrue();
        }

        @Test
        @DisplayName("a student sending rubbish learns nothing about the payload")
        void studentSendingRubbishLearnsNothingAboutThePayload() {
            // Role first, payload second. Reverse the two and this throws a validation
            // response instead of an authorization exception, telling a caller who may not
            // use the verb what it expects.
            assertThatExceptionOfType(AuthorizationException.class).isThrownBy(() ->
                    handlers.create(student(), request(Verb.QUESTION_CREATE, "not a draft")));
        }

        @Test
        @DisplayName("a wrong payload type is VALIDATION, never an exception")
        void wrongPayloadTypeIsValidation() {
            Message response =
                    handlers.create(teacher(), request(Verb.QUESTION_CREATE, "not a draft"));

            assertThat(response.isOk()).isFalse();
            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(errorText(response)).isEqualTo(BankMessages.MALFORMED_REQUEST);
            verify(questions, never()).create(any(), any(), any());
        }

        @Test
        @DisplayName("a refused payload never opens a transaction")
        void refusedPayloadNeverOpensATransaction() {
            handlers.create(teacher(), request(Verb.QUESTION_CREATE, "not a draft"));

            // Nothing is read on behalf of a request that was never going to be honoured.
            assertThat(wiring.tx().committed()).isFalse();
            assertThat(wiring.tx().rolledBack()).isFalse();
        }

        @Test
        @DisplayName("a null answer element is a sentence and not an exception")
        void aNullAnswerElementIsASentenceAndNotAnException() {
            // The E1.11 seam. QuestionDraft deliberately lets a null element through
            // construction so a hostile payload cannot kill the socket read thread; this is
            // the layer that has to turn it into a named refusal. Delete the validator call
            // and this becomes a NullPointerException out of QuestionService.
            List<String> withNull = new ArrayList<>(ANSWERS);
            withNull.set(2, null);
            QuestionDraft hostile = new QuestionDraft(COURSE, "What is encapsulation?", withNull,
                    1, "OOP", Difficulty.MEDIUM, null);

            Message response = handlers.create(teacher(), request(Verb.QUESTION_CREATE, hostile));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(errorText(response)).isEqualTo(BankMessages.answerBlank(3));
            verify(questions, never()).create(any(), any(), any());
        }

        @Test
        @DisplayName("a field violation answers with the sentence naming that field")
        void fieldViolationNamesTheField() {
            QuestionDraft blankTopic = new QuestionDraft(COURSE, "What is encapsulation?",
                    ANSWERS, 1, "   ", Difficulty.MEDIUM, null);

            Message response =
                    handlers.create(teacher(), request(Verb.QUESTION_CREATE, blankTopic));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(errorText(response)).isEqualTo(BankMessages.TOPIC_REQUIRED);
            verify(questions, never()).create(any(), any(), any());
        }

        @Test
        @DisplayName("a missing difficulty is a sentence, not a mapping exception")
        void missingDifficultyIsASentence() {
            // The wire-to-stored enum mapping runs while building the validator's Fields,
            // one step before the rule that would name the missing value. A mapper that
            // called name() on null would throw here instead of answering.
            QuestionDraft noDifficulty = new QuestionDraft(COURSE, "What is encapsulation?",
                    ANSWERS, 1, "OOP", null, null);

            Message response =
                    handlers.create(teacher(), request(Verb.QUESTION_CREATE, noDifficulty));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(errorText(response)).isEqualTo(BankMessages.DIFFICULTY_REQUIRED);
        }
    }

    // ===================== QUESTION_CREATE ===============================

    @Nested
    @DisplayName("QUESTION_CREATE")
    class Create {

        @Test
        @DisplayName("answers OK with the new question's detail")
        void answersWithTheDetail() {
            when(questions.create(any(), any(), any())).thenReturn(aDetail());

            Message response = handlers.create(teacher(), request(Verb.QUESTION_CREATE, aDraft()));

            assertThat(response.isOk()).isTrue();
            assertThat(response.getPayload()).isEqualTo(aDetail());
            assertThat(wiring.tx().committed()).isTrue();
        }

        @Test
        @DisplayName("an oversized image is refused before anything is read")
        void oversizedImageIsRefused() {
            byte[] huge = new byte[QuestionImages.MAX_BYTES + 1];
            huge[0] = (byte) 0x89;
            huge[1] = 'P';
            huge[2] = 'N';
            huge[3] = 'G';
            QuestionDraft withImage = new QuestionDraft(COURSE, "What is encapsulation?", ANSWERS,
                    1, "OOP", Difficulty.MEDIUM, huge);

            Message response =
                    handlers.create(teacher(), request(Verb.QUESTION_CREATE, withImage));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(errorText(response)).isEqualTo(BankMessages.IMAGE_TOO_LARGE);
            verify(questions, never()).create(any(), any(), any());
        }
    }

    // ===================== QUESTION_UPDATE ===============================

    @Nested
    @DisplayName("QUESTION_UPDATE")
    class Update {

        @Test
        @DisplayName("answers OK with the new version")
        void answersWithTheNewVersion() {
            when(questions.update(any(), any(), any())).thenReturn(new QuestionService.EditOutcome(
                    QuestionService.EditStatus.UPDATED, aDetail()));

            Message response = handlers.update(teacher(), request(Verb.QUESTION_UPDATE, anEdit()));

            assertThat(response.isOk()).isTrue();
            assertThat(response.getPayload()).isEqualTo(aDetail());
        }

        @Test
        @DisplayName("NOT_FOUND from the service is NOT_FOUND on the wire")
        void notFoundTravels() {
            when(questions.update(any(), any(), any())).thenReturn(new QuestionService.EditOutcome(
                    QuestionService.EditStatus.NOT_FOUND, null));

            Message response = handlers.update(teacher(), request(Verb.QUESTION_UPDATE, anEdit()));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(errorText(response)).isEqualTo(BankMessages.QUESTION_NOT_FOUND);
        }

        @Test
        @DisplayName("a stale base version is CONFLICT, not VALIDATION")
        void staleIsConflict() {
            // CONFLICT is what the client turns into a dialog with a Reload button; a
            // VALIDATION would put the sentence beside a form field that is not wrong.
            when(questions.update(any(), any(), any())).thenReturn(new QuestionService.EditOutcome(
                    QuestionService.EditStatus.STALE, null));

            Message response = handlers.update(teacher(), request(Verb.QUESTION_UPDATE, anEdit()));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(errorText(response)).isEqualTo(BankMessages.STALE_EDIT);
        }

        @Test
        @DisplayName("⚑ a locked question is CONFLICT, and a DIFFERENT sentence from stale")
        void lockedIsConflictWithItsOwnSentence() {
            // Both are CONFLICT and they are not the same event. STALE_EDIT tells her to reopen
            // the question and edit the newest version, which is an instruction she cannot
            // follow while somebody else has it open. Collapsing the two would be invisible to a
            // test that checked only the error code, which is why this one checks the text.
            when(questions.update(any(), any(), any())).thenReturn(
                    QuestionService.EditOutcome.lockedBy(new LockHolder(9, "Rina Barak")));

            Message response = handlers.update(teacher(), request(Verb.QUESTION_UPDATE, anEdit()));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(errorText(response))
                    .isEqualTo(BankMessages.lockedBy("Rina Barak"))
                    .isNotEqualTo(BankMessages.STALE_EDIT)
                    .as("and it names her, because a refusal with nobody in it has no route "
                            + "forward")
                    .contains("Rina Barak");
        }

        @Test
        @DisplayName("a field problem on an EDIT is VALIDATION, the same as on a draft")
        void aFieldProblemOnAnEditIsRefused() {
            // Found by reading the JaCoCo report rather than by reasoning: checkEdit's field
            // branch was the one uncovered line in this class. The create path had this case
            // and the update path did not, so the two verbs' shared validator was proven on
            // one of them only - and QUESTION_UPDATE is the verb a teacher uses far more often.
            QuestionEdit blankText = new QuestionEdit(DISPLAY_ID, 2, "   ", ANSWERS, 1, "OOP",
                    Difficulty.MEDIUM, ImageAction.KEEP, null);

            Message response = handlers.update(teacher(),
                    request(Verb.QUESTION_UPDATE, blankText));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(errorText(response)).isEqualTo(BankMessages.TEXT_REQUIRED);
            verify(questions, never()).update(any(), any(), any());
        }

        @Test
        @DisplayName("KEEP with no bytes is not an image violation")
        void keepWithoutBytesIsFine() {
            // Every edit of an unillustrated question arrives this way. Check the image
            // unconditionally instead of only on REPLACE and this is the test that fails.
            when(questions.update(any(), any(), any())).thenReturn(new QuestionService.EditOutcome(
                    QuestionService.EditStatus.UPDATED, aDetail()));

            Message response = handlers.update(teacher(), request(Verb.QUESTION_UPDATE, anEdit()));

            assertThat(response.isOk()).isTrue();
        }

        @Test
        @DisplayName("REPLACE carrying no file is refused, not treated as a removal")
        void replaceWithoutAFileIsRefused() {
            // The silent-destruction case. "No image" has to be acceptable to
            // QuestionImages.problemWith, because most drafts carry none, so a REPLACE with
            // null bytes used to pass validation and reach imageFor, which returned null and
            // wrote version n+1 with no picture. The teacher pressed Replace, her file picker
            // returned nothing, the server said OK, and the diagram was gone from the bank and
            // from every exam built on the new version. Section 4 gives ImageAction three
            // states so that clearing is never implicit; this is what keeps the third one real.
            for (byte[] nothing : new byte[][]{null, new byte[0]}) {
                QuestionEdit edit = new QuestionEdit(DISPLAY_ID, 2, "What is encapsulation?",
                        ANSWERS, 1, "OOP", Difficulty.MEDIUM, ImageAction.REPLACE, nothing);

                Message response = handlers.update(teacher(), request(Verb.QUESTION_UPDATE, edit));

                assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
                assertThat(errorText(response))
                        .isEqualTo(BankMessages.IMAGE_REPLACE_WITHOUT_FILE);
            }
            verify(questions, never()).update(any(), any(), any());
        }

        @Test
        @DisplayName("REMOVE carrying no file is still fine, which is the whole point of it")
        void removeWithoutAFileIsFine() {
            // The other side of the rule above: REMOVE arrives with no bytes by design, and a
            // check that refused every empty image would refuse the only way to clear one.
            when(questions.update(any(), any(), any())).thenReturn(new QuestionService.EditOutcome(
                    QuestionService.EditStatus.UPDATED, aDetail()));
            QuestionEdit edit = new QuestionEdit(DISPLAY_ID, 2, "What is encapsulation?",
                    ANSWERS, 1, "OOP", Difficulty.MEDIUM, ImageAction.REMOVE, null);

            assertThat(handlers.update(teacher(), request(Verb.QUESTION_UPDATE, edit)).isOk())
                    .isTrue();
        }

        @Test
        @DisplayName("REPLACE with the wrong type is refused")
        void replaceWithWrongTypeIsRefused() {
            byte[] heic = {0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'};
            QuestionEdit edit = new QuestionEdit(DISPLAY_ID, 2, "What is encapsulation?", ANSWERS,
                    1, "OOP", Difficulty.MEDIUM, ImageAction.REPLACE, heic);

            Message response = handlers.update(teacher(), request(Verb.QUESTION_UPDATE, edit));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(errorText(response)).isEqualTo(BankMessages.IMAGE_WRONG_TYPE);
            verify(questions, never()).update(any(), any(), any());
        }
    }

    // ===================== QUESTION_DELETE ===============================

    @Nested
    @DisplayName("QUESTION_DELETE")
    class Delete {

        private Message send() {
            return handlers.delete(teacher(),
                    request(Verb.QUESTION_DELETE, new QuestionDeleteRequest(DISPLAY_ID, 2)));
        }

        @Test
        @DisplayName("a completed delete is OK")
        void deletedIsOk() {
            when(questions.delete(any(), any(), any()))
                    .thenReturn(new QuestionService.DeleteResolution(
                            QuestionService.DeleteStatus.RESOLVED,
                            new DeleteOutcome(true, List.of())));

            Message response = send();

            assertThat(response.isOk()).isTrue();
            assertThat(((DeleteOutcome) response.getPayload()).deleted()).isTrue();
        }

        @Test
        @DisplayName("a blocked delete is OK carrying the exams, not an error")
        void blockedIsStillOk() {
            // Being told which exams pin the question is a successful answer to "may I
            // delete this". An ERROR here would leave T-2.7's dialog with nothing to list.
            DeleteOutcome blocked = new DeleteOutcome(false,
                    List.of(new common.dto.bank.BlockingExam("101101", "Algebra Midterm")));
            when(questions.delete(any(), any(), any()))
                    .thenReturn(new QuestionService.DeleteResolution(
                            QuestionService.DeleteStatus.RESOLVED, blocked));

            Message response = send();

            assertThat(response.isOk()).isTrue();
            DeleteOutcome outcome = (DeleteOutcome) response.getPayload();
            assertThat(outcome.deleted()).isFalse();
            assertThat(outcome.blockingExams()).hasSize(1);
        }

        @Test
        @DisplayName("NOT_FOUND travels, and covers out of scope as well as absent")
        void notFoundTravels() {
            when(questions.delete(any(), any(), any()))
                    .thenReturn(new QuestionService.DeleteResolution(
                            QuestionService.DeleteStatus.NOT_FOUND, null));

            assertThat(send().getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("a stale base version is CONFLICT")
        void staleIsConflict() {
            when(questions.delete(any(), any(), any()))
                    .thenReturn(new QuestionService.DeleteResolution(
                            QuestionService.DeleteStatus.STALE, null));

            assertThat(send().getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        }

        @Test
        @DisplayName("⚑ deleting a question somebody has open is CONFLICT, and names her")
        void lockedIsConflictWithItsOwnSentence() {
            when(questions.delete(any(), any(), any()))
                    .thenReturn(QuestionService.DeleteResolution.lockedBy(
                            new LockHolder(9, "Rina Barak")));

            Message response = send();

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(errorText(response))
                    .isEqualTo(BankMessages.lockedBy("Rina Barak"))
                    .isNotEqualTo(BankMessages.STALE_EDIT);
        }
    }

    // ===================== The bank push (U-63) ===========================

    /**
     * {@code PUSH_BANK_CHANGED}, the notice that makes every bank screen able to update itself
     * ⚑ (U-63, finding 11, NFR-18).
     *
     * <p>Until this there was no bank push at all, which is the whole of Omar's finding: a
     * teacher with the bank open never saw a colleague's new question, and touching a filter
     * was doing the job a push should have been doing. These tests pin the four rules that make
     * the push safe to trust rather than merely present.
     *
     * <p><b>After the commit, only on success, addressed to readers, never fatal.</b> The
     * ordering is {@code GradingHandlers.approve}'s, and the reason is the same: every
     * subscriber answers this by re-reading, so a push that overtook its own commit would be
     * answered from a database without the new version in it.
     */
    @Nested
    @DisplayName("⚑ U-63: PUSH_BANK_CHANGED, after the commit and only on success")
    class AnnouncingBankChanges {

        /**
         * Dana and a co-teacher, plus the principal: the course's readers as
         * {@code findBankReaderIds} answers them.
         *
         * <p>Called by the tests that get as far as announcing, rather than stubbed in a
         * {@code @BeforeEach}. Mockito's strict stubs are right to object to the other shape:
         * a test proving that a refusal announces nothing must not also be declaring who
         * would have been told, because then "nobody was told" could be true for the wrong
         * reason.
         */
        private void theCourseHasReaders() {
            when(courses.findBankReaderIds(any(), any())).thenReturn(List.of(501L, 502L, 900L));
        }

        @Test
        @DisplayName("a create announces the course and the new question to its readers")
        void createAnnounces() {
            theCourseHasReaders();
            when(questions.create(any(), any(), any())).thenReturn(aDetail());

            handlers.create(teacher(), request(Verb.QUESTION_CREATE, aDraft()));

            org.mockito.ArgumentCaptor<BankChanged> notice =
                    org.mockito.ArgumentCaptor.forClass(BankChanged.class);
            verify(pushGateway).toUsers(org.mockito.ArgumentMatchers.eq(List.of(501L, 502L, 900L)),
                    org.mockito.ArgumentMatchers.eq(Verb.PUSH_BANK_CHANGED), notice.capture());
            assertThat(notice.getValue().courseCode())
                    .as("the detail's course, not the draft's: the service resolved and "
                            + "stripped it, and the push must name where the row landed")
                    .isEqualTo(COURSE);
            assertThat(notice.getValue().displayId5()).isEqualTo(DISPLAY_ID);
            assertThat(notice.getValue().change()).isEqualTo(BankChanged.Change.CREATED);
        }

        @Test
        @DisplayName("an update announces, carrying the revised question")
        void updateAnnounces() {
            theCourseHasReaders();
            when(questions.update(any(), any(), any())).thenReturn(new QuestionService.EditOutcome(
                    QuestionService.EditStatus.UPDATED, aDetail()));

            handlers.update(teacher(), request(Verb.QUESTION_UPDATE, anEdit()));

            org.mockito.ArgumentCaptor<BankChanged> notice =
                    org.mockito.ArgumentCaptor.forClass(BankChanged.class);
            verify(pushGateway).toUsers(any(), org.mockito.ArgumentMatchers.eq(
                    Verb.PUSH_BANK_CHANGED), notice.capture());
            assertThat(notice.getValue().change()).isEqualTo(BankChanged.Change.UPDATED);
            assertThat(notice.getValue().displayId5()).isEqualTo(DISPLAY_ID);
        }

        @Test
        @DisplayName("a delete announces, so the row leaves everybody's list")
        void deleteAnnounces() {
            theCourseHasReaders();
            when(questions.delete(any(), any(), any())).thenReturn(
                    QuestionService.DeleteResolution.resolved(DeleteOutcome.succeeded(), COURSE));

            handlers.delete(teacher(),
                    request(Verb.QUESTION_DELETE, new QuestionDeleteRequest(DISPLAY_ID, 2)));

            org.mockito.ArgumentCaptor<BankChanged> notice =
                    org.mockito.ArgumentCaptor.forClass(BankChanged.class);
            verify(pushGateway).toUsers(any(), org.mockito.ArgumentMatchers.eq(
                    Verb.PUSH_BANK_CHANGED), notice.capture());
            assertThat(notice.getValue().change()).isEqualTo(BankChanged.Change.DELETED);
            assertThat(notice.getValue().courseCode()).isEqualTo(COURSE);
        }

        @Test
        @DisplayName("⚑ a delete BLOCKED by the exams that pin it announces nothing")
        void aBlockedDeleteIsSilent() {
            // No theCourseHasReaders() here, deliberately: Mockito's strict stubs would flag it
            // unused, and it is right to. Declaring who WOULD have been told, in the test that
            // proves nobody was, is how "nobody was told" passes for the wrong reason.
            when(questions.delete(any(), any(), any())).thenReturn(
                    QuestionService.DeleteResolution.resolved(
                            DeleteOutcome.blockedBy(List.of(
                                    new common.dto.bank.BlockingExam("101101", "Algebra"))),
                            COURSE));

            Message response = handlers.delete(teacher(),
                    request(Verb.QUESTION_DELETE, new QuestionDeleteRequest(DISPLAY_ID, 2)));

            assertThat(response.isOk())
                    .as("being told which exams pin it is a successful answer to 'may I'")
                    .isTrue();
            verify(pushGateway, never()).toUsers(any(), any(), any());
        }

        @Test
        @DisplayName("every refusal announces nothing, because nothing was committed")
        void refusalsAreSilent() {
            when(questions.update(any(), any(), any())).thenReturn(
                    new QuestionService.EditOutcome(QuestionService.EditStatus.STALE, null));
            handlers.update(teacher(), request(Verb.QUESTION_UPDATE, anEdit()));

            when(questions.delete(any(), any(), any())).thenReturn(
                    new QuestionService.DeleteResolution(
                            QuestionService.DeleteStatus.NOT_FOUND, null));
            handlers.delete(teacher(),
                    request(Verb.QUESTION_DELETE, new QuestionDeleteRequest(DISPLAY_ID, 2)));

            verify(pushGateway, never()).toUsers(any(), any(), any());
        }

        @Test
        @DisplayName("a validation refusal never even asks who the readers are")
        void aValidationRefusalReadsNothing() {
            QuestionDraft noCourse = new QuestionDraft(null, "What is encapsulation?", ANSWERS,
                    1, "OOP", Difficulty.MEDIUM, null);

            handlers.create(teacher(), request(Verb.QUESTION_CREATE, noCourse));

            verify(courses, never()).findBankReaderIds(any(), any());
            verify(pushGateway, never()).toUsers(any(), any(), any());
        }

        @Test
        @DisplayName("⚑ a push that blows up does not turn a saved question into an error")
        void aFailedPushNeverFailsTheVerb() {
            theCourseHasReaders();
            when(questions.create(any(), any(), any())).thenReturn(aDetail());
            when(pushGateway.toUsers(any(), any(), any()))
                    .thenThrow(new IllegalStateException("the socket went away"));

            Message response = handlers.create(teacher(), request(Verb.QUESTION_CREATE, aDraft()));

            assertThat(response.isOk())
                    .as("the version is committed by the time the push runs; telling her the "
                            + "save failed would be a lie she has to act on")
                    .isTrue();
            assertThat(response.getPayload()).isEqualTo(aDetail());
        }

        @Test
        @DisplayName("a course nobody staffs is a push to nobody, not a failure")
        void noReadersIsNotAnError() {
            when(courses.findBankReaderIds(any(), any())).thenReturn(List.of());
            when(questions.create(any(), any(), any())).thenReturn(aDetail());

            Message response = handlers.create(teacher(), request(Verb.QUESTION_CREATE, aDraft()));

            assertThat(response.isOk()).isTrue();
            verify(pushGateway, never()).toUsers(any(), any(), any());
        }
    }
}
