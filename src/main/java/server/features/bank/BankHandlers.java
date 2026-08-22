package server.features.bank;

import common.dto.auth.Role;
import common.dto.bank.QuestionDeleteRequest;
import common.dto.bank.QuestionDraft;
import common.dto.bank.QuestionEdit;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.core.Authorization;
import server.core.CallerContext;
import server.core.MessageRouter;
import server.db.Transactions;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The three question bank write verbs, on the router (Logic tier, E6.1/E6.3/E6.4).
 *
 * <p>The read verbs are deliberately not here. They carry a different guard, and the contract's
 * section 3 makes the verb-to-guard mapping the thing a reviewer checks; two sets of verbs with
 * two different guards in one class is how the wrong one eventually gets called.
 *
 * <h2>The shape, and why the order is fixed</h2>
 *
 * <p>{@link #asAuthor} does the half that is identical across all three verbs:
 *
 * <ol>
 *   <li><b>Role.</b> Teacher or coordinator, before anything about the request is examined. A
 *       student sending a malformed bank payload learns the verb is not hers and nothing about
 *       what the payload should have looked like.</li>
 *   <li><b>Payload type.</b> Wrong type or missing is {@code VALIDATION}, never an exception.
 *       {@code BAD_REQUEST} is deliberately unused in this contract.</li>
 *   <li><b>Validation</b>, still outside any transaction. Nothing is read on behalf of a request
 *       that was never going to be honoured, and the commonest mistakes on the editor screen cost
 *       a sentence rather than a database round trip.</li>
 *   <li><b>One transaction</b>, inside which the service applies the scope guard.</li>
 * </ol>
 *
 * <p><b>Validation before the transaction is not only an optimisation.</b>
 * {@code QuestionDraft} and {@code QuestionEdit} let a null answer element survive construction so
 * that a hostile payload cannot kill the socket read thread (E1.11), which means the null is still
 * there when this class receives it. {@link QuestionValidator}'s structural rules are what turn it
 * into a named sentence, and {@link QuestionService} reads the four answers positionally on the
 * assumption they have run. The order here is what makes that assumption true.
 *
 * <h2>Where the scope guard is, and why it is not here</h2>
 *
 * <p>Only the role check lives in this class. The per-question scope guard lives in the service,
 * because two of the three verbs resolve the course from a <em>stored</em> question and cannot
 * decide anything before a transaction is open. The service applies exactly one guard per verb,
 * fixed by the contract's table and never chosen at runtime.
 *
 * <p><b>The caller's id reaches the service as a {@link CallerContext} and never as a payload
 * field.</b> None of the three request records carries an author id, so there is no field a client
 * could put somebody else's id in (ARCHITECTURE section 3, P-5).
 */
public class BankHandlers {

    private static final Logger log = LoggerFactory.getLogger(BankHandlers.class);

    /** Work that has passed every gate: a session, the checked caller, and a checked payload. */
    @FunctionalInterface
    private interface AuthorWork<T> {
        Message apply(Session session, CallerContext caller, T payload);
    }

    private final SessionFactory sessionFactory;
    private final QuestionService questions;

    public BankHandlers(SessionFactory sessionFactory, QuestionService questions) {
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.questions = Objects.requireNonNull(questions, "questions");
    }

    /**
     * Registers the three write verbs.
     *
     * @param router the router to register on
     */
    public void registerOn(MessageRouter router) {
        Objects.requireNonNull(router, "router");
        router.register(Verb.QUESTION_CREATE, this::create);
        router.register(Verb.QUESTION_UPDATE, this::update);
        router.register(Verb.QUESTION_DELETE, this::delete);
    }

    // ===================== The shared gate ===============================

    /**
     * The authorization shape every bank write verb wears.
     *
     * @param request  the incoming request, for its payload and to answer against
     * @param caller   the authenticated caller
     * @param type     the payload type this verb takes
     * @param validate per-verb checks; returns the sentence to answer with, or {@code null} when
     *                 the payload is acceptable. Runs before any transaction opens
     * @param work     what to do once every gate has passed
     * @param <T>      the payload type
     * @return the work's response, or the first gate's refusal
     */
    private <T> Message asAuthor(Message request,
                                 CallerContext caller,
                                 Class<T> type,
                                 Function<T, String> validate,
                                 AuthorWork<T> work) {
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);

        Object payload = request.getPayload();
        if (!type.isInstance(payload)) {
            log.debug("{} from {} carried {} rather than {}", request.getVerb(), caller,
                    payload == null ? "no payload" : payload.getClass().getName(),
                    type.getSimpleName());
            return Message.error(request, ErrorCode.VALIDATION, BankMessages.MALFORMED_REQUEST);
        }
        T checked = type.cast(payload);

        String problem = validate.apply(checked);
        if (problem != null) {
            return Message.error(request, ErrorCode.VALIDATION, problem);
        }
        return Transactions.inTx(sessionFactory, session -> work.apply(session, caller, checked));
    }

    // ===================== QUESTION_CREATE ===============================

    /**
     * {@code QUESTION_CREATE} - a new question and its first version (E6.1).
     *
     * <p>The one verb whose scope guard throws: the caller supplied the course, so
     * {@code FORBIDDEN} naming it discloses nothing she did not type.
     *
     * @param caller  the authenticated author
     * @param request the request, carrying a {@link QuestionDraft}
     * @return {@code OK} with the new question's detail; {@code VALIDATION} for a bad payload
     */
    Message create(CallerContext caller, Message request) {
        return asAuthor(request, caller, QuestionDraft.class, BankHandlers::checkDraft,
                (session, author, draft) ->
                        Message.ok(request, questions.create(session, author, draft)));
    }

    // ===================== QUESTION_UPDATE ===============================

    /**
     * {@code QUESTION_UPDATE} - version n+1, leaving version n untouched (E6.3).
     *
     * @param caller  the authenticated author
     * @param request the request, carrying a {@link QuestionEdit}
     * @return {@code OK} with the new version; {@code VALIDATION} for a bad payload;
     *         {@code NOT_FOUND} for a question she may not edit or that is not there;
     *         {@code CONFLICT} when somebody else has written a version since she opened hers
     */
    Message update(CallerContext caller, Message request) {
        return asAuthor(request, caller, QuestionEdit.class, BankHandlers::checkEdit,
                (session, author, edit) -> {
                    QuestionService.EditOutcome outcome = questions.update(session, author, edit);
                    return switch (outcome.status()) {
                        case UPDATED -> Message.ok(request, outcome.detail());
                        case NOT_FOUND -> Message.error(request, ErrorCode.NOT_FOUND,
                                BankMessages.QUESTION_NOT_FOUND);
                        case STALE -> Message.error(request, ErrorCode.CONFLICT,
                                BankMessages.STALE_EDIT);
                    };
                });
    }

    // ===================== QUESTION_DELETE ===============================

    /**
     * {@code QUESTION_DELETE} - soft delete, or a refusal naming the exams that use it (E6.4).
     *
     * <p>Blocked is {@code OK}, not an error. Being told which exams pin the question is a
     * successful answer to "may I delete this", and T-2.7's dialog is built from the list.
     *
     * @param caller  the authenticated author
     * @param request the request, carrying a {@link QuestionDeleteRequest}
     * @return {@code OK} with a {@code DeleteOutcome} whether it went through or was blocked;
     *         {@code NOT_FOUND} for a question she may not delete or that is not there;
     *         {@code CONFLICT} for a stale base version
     */
    Message delete(CallerContext caller, Message request) {
        return asAuthor(request, caller, QuestionDeleteRequest.class, BankHandlers::noExtraChecks,
                (session, author, ask) -> {
                    QuestionService.DeleteResolution resolved =
                            questions.delete(session, author, ask);
                    return switch (resolved.status()) {
                        case RESOLVED -> Message.ok(request, resolved.outcome());
                        case NOT_FOUND -> Message.error(request, ErrorCode.NOT_FOUND,
                                BankMessages.QUESTION_NOT_FOUND);
                        case STALE -> Message.error(request, ErrorCode.CONFLICT,
                                BankMessages.STALE_EDIT);
                    };
                });
    }

    // ===================== Validation ====================================

    /**
     * Everything a create payload must satisfy before anything is read.
     *
     * @param draft the submitted question
     * @return the sentence to answer with, or {@code null} when it is acceptable
     */
    private static String checkDraft(QuestionDraft draft) {
        // The course is checked here and not in QuestionValidator, whose Fields deliberately
        // holds only what create and edit have in common: an edit resolves its course from the
        // stored question and never carries one. Without this line a draft with no course
        // reached the scope guard, which refused it as FORBIDDEN with a sentence written for
        // the edit screen. Section 6 gives a malformed payload VALIDATION with the field named.
        if (draft.courseCode() == null || draft.courseCode().isBlank()) {
            return BankMessages.COURSE_REQUIRED;
        }
        String fields = fieldProblem(new QuestionValidator.Fields(
                draft.text(), draft.answers(), draft.correctAnswer(), draft.topic(),
                QuestionService.entityDifficulty(draft.difficulty())));
        return fields != null ? fields : QuestionImages.problemWith(draft.image());
    }

    /**
     * Everything an edit payload must satisfy before anything is read.
     *
     * <p>The same field rules as {@link #checkDraft}, through the same validator, which is the
     * point of {@code Fields} existing: in v1 the add form checked duplicate answers and the edit
     * form did not, so a question could be edited into a state it could never have been created
     * in.
     *
     * <p>The image is examined only on {@code REPLACE}. A {@code KEEP} copies what is already
     * stored and a {@code REMOVE} carries nothing, and refusing either for a null image would
     * make every edit of an unillustrated question fail.
     *
     * <p><b>REPLACE carrying no file is refused rather than treated as a removal.</b> It used to
     * pass, because "no image" is acceptable to {@link QuestionImages#problemWith} and has to be
     * for a draft. The result was that a teacher whose file picker returned nothing pressed
     * Replace, got {@code OK}, and lost the illustration from version n+1 and from every exam
     * built on it. Section 4 gives {@code ImageAction} three states so that clearing is never
     * implicit; this is the branch that keeps the third one meaningful.
     *
     * @param edit the submitted edit
     * @return the sentence to answer with, or {@code null} when it is acceptable
     */
    private static String checkEdit(QuestionEdit edit) {
        String fields = fieldProblem(new QuestionValidator.Fields(
                edit.text(), edit.answers(), edit.correctAnswer(), edit.topic(),
                QuestionService.entityDifficulty(edit.difficulty())));
        if (fields != null) {
            return fields;
        }
        if (edit.imageAction() != common.dto.bank.ImageAction.REPLACE) {
            return null;
        }
        byte[] replacement = edit.image();
        if (replacement == null || replacement.length == 0) {
            return BankMessages.IMAGE_REPLACE_WITHOUT_FILE;
        }
        return QuestionImages.problemWith(replacement);
    }

    /**
     * The validator's answer as a sentence.
     *
     * @param fields the values to check
     * @return the violation's message, or {@code null} when there is none
     */
    private static String fieldProblem(QuestionValidator.Fields fields) {
        Optional<QuestionValidator.Violation> violation = QuestionValidator.validate(fields);
        return violation.map(QuestionValidator.Violation::message).orElse(null);
    }

    /** The validator for a verb whose payload needs nothing checked beyond its type. */
    private static <T> String noExtraChecks(T payload) {
        return null;
    }
}
