package server.features.results;

import common.dto.ErrorPayload;
import common.dto.auth.Role;
import common.dto.exam.AttemptState;
import common.dto.grading.CheckedForm;
import common.dto.grading.CheckedFormRequest;
import common.dto.grading.GradeState;
import common.dto.grading.MyGrades;
import common.dto.grading.StudentGradeRow;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import server.core.AuthorizationException;
import server.core.CallerContext;
import server.core.MessageRouter;
import server.core.SessionManager;
import server.db.MockSessions;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ResultsHandlers} — {@code MY_GRADES_GET} (E13.3 ⚑).
 *
 * <p>Defence-critical, and the tests say why in the two that look almost trivial.
 * {@code asksForTheCallersOwnIdAndNothingElse} is the whole security property of this verb:
 * the id handed to the service is the session's, and there is no other id anywhere in the
 * request for it to have come from. {@code anyAuthenticatedCallerMayAskForTheirOwn} asserts
 * the deliberate <em>absence</em> of a role gate — a rule that looks like a missing check
 * until you notice that a teacher who once sat an exam has grades too.
 */
@ExtendWith(MockitoExtension.class)
class ResultsHandlersTest {

    private static final long STUDENT_ID = 11;

    @Mock
    private Session session;
    @Mock
    private ResultsService results;
    @Mock
    private CheckedFormService checkedForms;

    private ResultsHandlers handlers;
    private MockSessions.Wiring wiring;

    @BeforeEach
    void setUp() {
        wiring = MockSessions.commitsOn(session);
        handlers = new ResultsHandlers(wiring.factory(), results, checkedForms);
    }

    private static Message request() {
        return Message.request(Verb.MY_GRADES_GET, null);
    }

    private static MyGrades oneGrade() {
        return new MyGrades(List.of(new StudentGradeRow(900, STUDENT_ID, "מאיה לוי", 71, null, 71,
                GradeState.APPROVED, null, "well done", null, "Java midterm", "01")));
    }

    @Test
    @DisplayName("registers both student verbs, behind a session")
    void registers() {
        MessageRouter router = new MessageRouter(new SessionManager());

        handlers.registerOn(router);

        assertThat(router.isRegistered(Verb.MY_GRADES_GET)).isTrue();
        assertThat(router.isRegistered(Verb.CHECKED_FORM_GET)).isTrue();
        assertThat(router.isOpen(Verb.MY_GRADES_GET)).isFalse();
        assertThat(router.isOpen(Verb.CHECKED_FORM_GET)).isFalse();
    }

    @Test
    @DisplayName("asks for the caller's own id and nothing else")
    void asksForTheCallersOwnIdAndNothingElse() {
        when(results.myGrades(session, STUDENT_ID)).thenReturn(oneGrade());

        handlers.myGrades(CallerContext.authenticated(null, STUDENT_ID, Role.STUDENT), request());

        verify(results).myGrades(session, STUDENT_ID);
    }

    @Test
    @DisplayName("refuses an anonymous caller")
    void refusesAnonymous() {
        assertThatExceptionOfType(AuthorizationException.class).isThrownBy(() ->
                handlers.myGrades(CallerContext.anonymous(null), request()));
    }

    @Test
    @DisplayName("any authenticated caller may ask for their own — there is no role gate")
    void anyAuthenticatedCallerMayAskForTheirOwn() {
        when(results.myGrades(session, STUDENT_ID)).thenReturn(MyGrades.EMPTY);

        for (Role role : Role.values()) {
            Message response = handlers.myGrades(
                    CallerContext.authenticated(null, STUDENT_ID, role), request());
            assertThat(response.isOk())
                    .as("%s asking for their own grades", role)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("an empty list is OK, not an error — a new student has simply not sat anything")
    void emptyIsOk() {
        when(results.myGrades(session, STUDENT_ID)).thenReturn(MyGrades.EMPTY);

        Message response = handlers.myGrades(
                CallerContext.authenticated(null, STUDENT_ID, Role.STUDENT), request());

        assertThat(response.isOk()).isTrue();
        assertThat(((MyGrades) response.getPayload()).grades()).isEmpty();
    }

    @Test
    @DisplayName("rows carry their exam label, because every row is a different exam (v1.1)")
    void rowsCarryTheirExamLabel() {
        when(results.myGrades(session, STUDENT_ID)).thenReturn(oneGrade());

        Message response = handlers.myGrades(
                CallerContext.authenticated(null, STUDENT_ID, Role.STUDENT), request());

        StudentGradeRow row = ((MyGrades) response.getPayload()).grades().get(0);
        assertThat(row.examName()).isEqualTo("Java midterm");
        assertThat(row.courseCode()).isEqualTo("01");
    }

    @Test
    @DisplayName("ignores whatever a client puts in the payload — the verb takes none")
    void ignoresAnyPayload() {
        when(results.myGrades(session, STUDENT_ID)).thenReturn(MyGrades.EMPTY);

        Message response = handlers.myGrades(
                CallerContext.authenticated(null, STUDENT_ID, Role.STUDENT),
                Message.request(Verb.MY_GRADES_GET, 999L));

        // A student id smuggled into the payload changes nothing: the read used the session's.
        assertThat(response.isOk()).isTrue();
        verify(results).myGrades(session, STUDENT_ID);
    }

    @Test
    @DisplayName("commits its transaction")
    void commits() {
        when(results.myGrades(session, STUDENT_ID)).thenReturn(MyGrades.EMPTY);

        handlers.myGrades(CallerContext.authenticated(null, STUDENT_ID, Role.STUDENT), request());

        assertThat(wiring.tx().committed()).isTrue();
    }

    // ===================== CHECKED_FORM_GET ==============================

    @Test
    @DisplayName("serves the caller's own marked paper, asking with the session's id")
    void servesTheCheckedForm() {
        CheckedForm form = new CheckedForm(
                new StudentGradeRow(900, STUDENT_ID, "מאיה לוי", 71, 71, 71,
                        GradeState.APPROVED, null, null, null, "Algebra midterm", "11"),
                "Algebra midterm", "11", AttemptState.SUBMITTED, 70, List.of());
        when(checkedForms.checkedForm(session, STUDENT_ID, 900)).thenReturn(Optional.of(form));

        Message response = handlers.checkedForm(
                CallerContext.authenticated(null, STUDENT_ID, Role.STUDENT),
                Message.request(Verb.CHECKED_FORM_GET, new CheckedFormRequest(900)));

        assertThat(response.isOk()).isTrue();
        assertThat(response.getPayload()).isSameAs(form);
        // The id came from the session, and the payload carries no student id to have used.
        verify(checkedForms).checkedForm(session, STUDENT_ID, 900);
    }

    @Test
    @DisplayName("every refusal is one NOT_FOUND with one sentence, whatever the reason was")
    void refusalsAreOneAnswer() {
        when(checkedForms.checkedForm(session, STUDENT_ID, 900)).thenReturn(Optional.empty());

        Message response = handlers.checkedForm(
                CallerContext.authenticated(null, STUDENT_ID, Role.STUDENT),
                Message.request(Verb.CHECKED_FORM_GET, new CheckedFormRequest(900)));

        // The service returns an Optional rather than a reason precisely so this handler has
        // nothing to accidentally report. Four causes, one answer.
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(((ErrorPayload) response.getPayload()).message())
                .isEqualTo(ResultsHandlers.ResultsCopyMessages.NO_SUCH_FORM);
    }

    @Test
    @DisplayName("refuses an anonymous caller before reading anything")
    void checkedFormRefusesAnonymous() {
        assertThatExceptionOfType(AuthorizationException.class).isThrownBy(() ->
                handlers.checkedForm(CallerContext.anonymous(null),
                        Message.request(Verb.CHECKED_FORM_GET, new CheckedFormRequest(900))));
    }

    @Test
    @DisplayName("a malformed payload is VALIDATION, and reads nothing")
    void checkedFormMalformedPayload() {
        Message response = handlers.checkedForm(
                CallerContext.authenticated(null, STUDENT_ID, Role.STUDENT),
                Message.request(Verb.CHECKED_FORM_GET, "nonsense"));

        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
        verify(checkedForms, org.mockito.Mockito.never())
                .checkedForm(any(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("any authenticated caller may open their own paper — no role gate here either")
    void checkedFormHasNoRoleGate() {
        when(checkedForms.checkedForm(any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(Optional.empty());

        for (Role role : Role.values()) {
            Message response = handlers.checkedForm(
                    CallerContext.authenticated(null, STUDENT_ID, role),
                    Message.request(Verb.CHECKED_FORM_GET, new CheckedFormRequest(900)));
            // NOT_FOUND rather than FORBIDDEN: the gate is whose grade it is, not who she is.
            assertThat(response.getErrorCode()).as("%s", role).isEqualTo(ErrorCode.NOT_FOUND);
        }
    }

    @Test
    @DisplayName("rejects null collaborators at construction")
    void rejectsNulls() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new ResultsHandlers(null, results, checkedForms));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new ResultsHandlers(wiring.factory(), null, checkedForms));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new ResultsHandlers(wiring.factory(), results, null));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> handlers.registerOn(null));
    }
}
