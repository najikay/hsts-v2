package server.features.bank;

import common.dto.bank.Question;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final QuestionDAO questionDAO;

    public LegacyQuestionHandlers(QuestionDAO questionDAO) {
        this.questionDAO = Objects.requireNonNull(questionDAO, "questionDAO");
    }

    /**
     * Registers both verbs as <b>authenticated</b> (E5: login exists now, so
     * nothing but {@code LOGIN} is reachable from an anonymous connection).
     *
     * <p>They stay role-agnostic for one more epic: the course-scoped guard these
     * really want — {@code Authorization.requireTeachesCourse} — needs the course
     * repositories from E2, and the legacy {@link common.dto.bank.Question} has no
     * course on it to guard with. The client only offers the screen to teachers
     * and coordinators; E6 replaces both verbs with the versioned bank verbs and
     * their real guards.
     */
    public void registerOn(MessageRouter router) {
        router.register(Verb.GET_ALL_QUESTIONS, this::getAllQuestions);
        router.register(Verb.UPDATE_QUESTION, this::updateQuestion);
    }

    /** {@code GET_ALL_QUESTIONS} → OK with the full list. */
    Message getAllQuestions(CallerContext caller, Message request) {
        List<Question> all = questionDAO.getAll();
        log.debug("GET_ALL_QUESTIONS → {} question(s)", all.size());
        return Message.ok(request, new ArrayList<>(all));
    }

    /**
     * {@code UPDATE_QUESTION} → OK with the refreshed list, so the client
     * re-renders from the server's source of truth rather than its own guess.
     */
    Message updateQuestion(CallerContext caller, Message request) {
        if (!(request.getPayload() instanceof Question question)) {
            log.warn("UPDATE_QUESTION with a {} payload", describe(request.getPayload()));
            return Message.error(request, ErrorCode.VALIDATION,
                    "This update could not be read — please reopen the question and try again.");
        }
        if (!questionDAO.update(question)) {
            log.warn("UPDATE_QUESTION id={} matched no row", question.getId());
            return Message.error(request, ErrorCode.NOT_FOUND,
                    "Question #" + question.getId() + " could not be updated — it may have been removed.");
        }
        log.info("UPDATE_QUESTION id={} saved", question.getId());
        return Message.ok(request, new ArrayList<>(questionDAO.getAll()));
    }

    private static String describe(Object payload) {
        return payload == null ? "null" : payload.getClass().getName();
    }
}
