package server.features.bank;

import common.dto.auth.Role;
import common.dto.bank.Question;
import common.dto.bank.QuestionUpdate;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.core.Authorization;
import server.core.CallerContext;
import server.core.MessageRouter;
import server.db.QuestionDAO;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The prototype's question list/edit flow, re-expressed as protocol v2 handlers
 * (Logic tier).
 *
 * <p>This is deliberately a thin port, not a redesign: the phase-3 demo must
 * keep working unchanged while E1 replaces the transport underneath it. The real
 * bank — immutable question versions, validators, display ids — arrives with E6
 * and retires this class together with {@link QuestionDAO}.
 */
public class LegacyQuestionHandlers {

    private static final Logger log = LoggerFactory.getLogger(LegacyQuestionHandlers.class);

    /**
     * The sentence behind a {@link ErrorCode#CONFLICT} on a save (E18.4). It says
     * what happened and what the user can do next, because the client turns it
     * into a dialog with a "Reload" button rather than an apology.
     */
    public static final String STALE_WRITE_MESSAGE =
            "This question was changed by someone else while you were editing it. "
                    + "Reload the latest version to see their changes.";

    private final QuestionDAO questionDAO;

    public LegacyQuestionHandlers(QuestionDAO questionDAO) {
        this.questionDAO = Objects.requireNonNull(questionDAO, "questionDAO");
    }

    /**
     * Registers both verbs as <b>authenticated and role-gated</b>.
     *
     * <p>The gate is a blanket role check, not the course-scoped guard these
     * really want — {@code Authorization.requireTeachesCourse} stays with E6,
     * whose versioned verbs know their course. But "any signed-in student can
     * read every answer and rewrite every question" (found by Member A's E2.12
     * red-team, PROBLEMS.md P-5) needed no course data to fix: reading the bank
     * is for staff, writing it is for teachers and coordinators. The client
     * hiding the screen from students is not a control; this is.
     */
    public void registerOn(MessageRouter router) {
        router.register(Verb.GET_ALL_QUESTIONS, this::getAllQuestions);
        router.register(Verb.UPDATE_QUESTION, this::updateQuestion);
    }

    /** {@code GET_ALL_QUESTIONS} → OK with the full list. Staff only: the legacy list carries answers. */
    Message getAllQuestions(CallerContext caller, Message request) {
        // PRINCIPAL included: PRD F2 gives the principal the bank read-only.
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR, Role.PRINCIPAL);
        List<Question> all = questionDAO.getAll();
        log.debug("GET_ALL_QUESTIONS → {} question(s)", all.size());
        return Message.ok(request, new ArrayList<>(all));
    }

    /**
     * {@code UPDATE_QUESTION} → OK with the refreshed list, so the client
     * re-renders from the server's source of truth rather than its own guess.
     *
     * <p>Two payload shapes, one handler (E18.4):
     * <ul>
     *   <li>a {@link QuestionUpdate} carries the values the client read as well as
     *       the ones it wants written, and gets the optimistic guard: if somebody
     *       else saved in between, nothing is written and the answer is
     *       {@link ErrorCode#CONFLICT}, which the client turns into the
     *       "reload the latest version?" dialog;</li>
     *   <li>a bare {@link Question} is the pre-E18 shape and still writes
     *       unguarded, so an older client keeps working unchanged.</li>
     * </ul>
     */
    Message updateQuestion(CallerContext caller, Message request) {
        // Writing the bank is authoring: teachers and coordinators only. The
        // principal reads; students get FORBIDDEN before any payload is looked at.
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);
        Object payload = request.getPayload();
        if (payload instanceof QuestionUpdate update) {
            return updateGuarded(request, update);
        }
        if (!(payload instanceof Question question)) {
            log.warn("UPDATE_QUESTION with a {} payload", describe(payload));
            return Message.error(request, ErrorCode.VALIDATION,
                    "This update could not be read. Please reopen the question and try again.");
        }
        if (!questionDAO.update(question)) {
            log.warn("UPDATE_QUESTION id={} matched no row", question.getId());
            return Message.error(request, ErrorCode.NOT_FOUND,
                    "Question #" + question.getId() + " could not be updated. It may have been removed.");
        }
        log.info("UPDATE_QUESTION id={} saved", question.getId());
        return Message.ok(request, new ArrayList<>(questionDAO.getAll()));
    }

    /** The E18.4 path: write only if the row still says what the client read. */
    private Message updateGuarded(Message request, QuestionUpdate update) {
        QuestionDAO.UpdateOutcome outcome = questionDAO.updateGuarded(update);
        return switch (outcome) {
            case SAVED -> {
                log.info("UPDATE_QUESTION id={} saved (guarded)", update.id());
                yield Message.ok(request, new ArrayList<>(questionDAO.getAll()));
            }
            case STALE -> {
                log.warn("UPDATE_QUESTION id={} rejected as stale", update.id());
                yield Message.error(request, ErrorCode.CONFLICT, STALE_WRITE_MESSAGE);
            }
            case MISSING -> {
                log.warn("UPDATE_QUESTION id={} matched no row", update.id());
                yield Message.error(request, ErrorCode.NOT_FOUND,
                        "Question #" + update.id() + " could not be updated. It may have been removed.");
            }
            case FAILED -> {
                log.error("UPDATE_QUESTION id={} failed against the database", update.id());
                yield Message.error(request, ErrorCode.INTERNAL,
                        "Saving failed because the database could not be reached. Your text is still in the editor, try again.");
            }
        };
    }

    private static String describe(Object payload) {
        return payload == null ? "null" : payload.getClass().getName();
    }
}
