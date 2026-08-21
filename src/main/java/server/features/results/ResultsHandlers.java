package server.features.results;

import common.dto.grading.MyGrades;
import common.protocol.Message;
import common.protocol.Verb;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.core.Authorization;
import server.core.CallerContext;
import server.core.MessageRouter;
import server.db.Transactions;

import java.util.Objects;

/**
 * The student results verbs, on the router (Logic tier, E13.3).
 *
 * <p>Deliberately a separate class from {@code server.features.grading.GradingHandlers}, and
 * the separation is the security design rather than a filing decision. The teacher verbs are
 * gated by role <b>and</b> per-row ownership resolved from repositories; the student verbs are
 * gated by the query itself, which filters on the caller's own id in SQL. Those are two
 * different guarantees, and putting them in one class would invite a future verb to be added
 * next to the wrong one and inherit the wrong shape.
 *
 * <h2>The student shape</h2>
 *
 * <p><b>No role check at all, and that is correct.</b> Any authenticated caller may ask for
 * their own grades — a teacher who once sat an exam has grades too, and refusing them by role
 * would be a rule about who people are rather than about whose data this is. What makes the
 * verb safe is the next line: the caller's id comes from the session and goes straight into
 * {@link ResultsService}, whose reads filter on it. There is no payload here for a client to
 * put somebody else's id in, because {@code MY_GRADES_GET} takes no payload at all.
 *
 * <p>That is why the request record is {@code null} rather than a {@code MyGradesRequest}
 * carrying a student id. A verb with nothing to lie about cannot be lied to.
 */
public class ResultsHandlers {

    private static final Logger log = LoggerFactory.getLogger(ResultsHandlers.class);

    private final SessionFactory sessionFactory;
    private final ResultsService results;

    public ResultsHandlers(SessionFactory sessionFactory, ResultsService results) {
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.results = Objects.requireNonNull(results, "results");
    }

    /**
     * Registers the student results verbs.
     *
     * @param router the router to register on
     */
    public void registerOn(MessageRouter router) {
        Objects.requireNonNull(router, "router");
        router.register(Verb.MY_GRADES_GET, this::myGrades);
    }

    /**
     * {@code MY_GRADES_GET} — the caller's own approved grades (E13.3 — F8.4, T-9).
     *
     * <p>Only approved rows, because auto-checking publishes nothing on its own (C-3, S-24). A
     * student refreshing this while their teacher is still marking sees the same list they saw
     * before the exam was graded, which is the intended experience and not a bug: a score they
     * can see is a score a teacher has stood behind.
     *
     * <p>An empty list is {@code OK} with {@link MyGrades#EMPTY}, never an error. "You have no
     * grades yet" is a state the screen renders, and turning it into an {@code ERROR} would
     * make a new student's first visit look like a failure.
     *
     * <p>Each row carries its own {@code examName} and {@code courseCode} (contract amendment
     * v1.1): unlike a teacher's table, every row in this list is a different exam.
     *
     * @param caller  the authenticated caller, whoever they are
     * @param request the request; it carries no payload, on purpose
     * @return {@code OK} with a {@link MyGrades}
     */
    Message myGrades(CallerContext caller, Message request) {
        Authorization.requireAuthenticated(caller);
        long studentId = caller.userId();

        MyGrades grades = Transactions.inTx(sessionFactory,
                session -> results.myGrades(session, studentId));
        log.debug("Served {} approved grade(s) to user {}", grades.grades().size(), studentId);
        return Message.ok(request, grades);
    }
}
