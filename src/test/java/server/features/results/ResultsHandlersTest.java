package server.features.results;

import common.dto.auth.Role;
import common.dto.grading.GradeState;
import common.dto.grading.MyGrades;
import common.dto.grading.StudentGradeRow;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
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

    private ResultsHandlers handlers;
    private MockSessions.Wiring wiring;

    @BeforeEach
    void setUp() {
        wiring = MockSessions.commitsOn(session);
        handlers = new ResultsHandlers(wiring.factory(), results);
    }

    private static Message request() {
        return Message.request(Verb.MY_GRADES_GET, null);
    }

    private static MyGrades oneGrade() {
        return new MyGrades(List.of(new StudentGradeRow(900, STUDENT_ID, "מאיה לוי", 71, null, 71,
                GradeState.APPROVED, null, "well done", null, "Java midterm", "01")));
    }

    @Test
    @DisplayName("registers MY_GRADES_GET, behind a session")
    void registers() {
        MessageRouter router = new MessageRouter(new SessionManager());

        handlers.registerOn(router);

        assertThat(router.isRegistered(Verb.MY_GRADES_GET)).isTrue();
        assertThat(router.isOpen(Verb.MY_GRADES_GET)).isFalse();
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

    @Test
    @DisplayName("rejects null collaborators at construction")
    void rejectsNulls() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new ResultsHandlers(null, results));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new ResultsHandlers(wiring.factory(), null));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> handlers.registerOn(null));
    }
}
