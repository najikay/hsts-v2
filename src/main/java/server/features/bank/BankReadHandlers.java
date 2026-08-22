package server.features.bank;

import common.dto.auth.Role;
import common.dto.bank.BankListRequest;
import common.dto.bank.QuestionImageRequest;
import common.dto.bank.QuestionRequest;
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
import java.util.function.Function;

/**
 * The four question bank read verbs, on the router (Logic tier, E6.3/E6.5/E6.6).
 *
 * <p>Deliberately not {@link BankHandlers}. The contract's section 3 makes the verb-to-guard
 * mapping the thing a reviewer checks, and the two classes carry different guards over different
 * role sets: writes are teacher and coordinator behind {@code requireTeachesCourse}, reads are
 * teacher, coordinator <b>and principal</b> behind {@code reachesCourse}. Two sets of verbs with
 * two different answers to "who may" in one class is how the wrong one eventually gets called.
 *
 * <h2>The role set is wider here, and that is the whole difference</h2>
 *
 * <p>F9.3 gives the principal the school's question bank read-only. She is admitted by
 * {@link #asReader} and refused by {@code BankHandlers.asAuthor}, and those two lines are the
 * entire boundary between "may look at every course" and "may change nothing". A principal added
 * to the write gate would be one role-list entry from school-wide write access, which is the
 * defect the write PR's audit found when nothing tested it.
 *
 * <h2>The shape, and why the order is fixed</h2>
 *
 * <ol>
 *   <li><b>Role</b>, before anything about the request is examined. A student sending a bank
 *       payload learns the verb is not hers and nothing about what it should have contained.</li>
 *   <li><b>Payload type.</b> Wrong type or missing is {@code VALIDATION}, never an exception.
 *       {@code BAD_REQUEST} stays unused in this contract.</li>
 *   <li><b>Shape</b>, still outside any transaction: a request naming no question is malformed
 *       and costs no database round trip to refuse.</li>
 *   <li><b>One transaction</b>, inside which the service applies the scope guard.</li>
 * </ol>
 *
 * <p><b>No validator runs here and that is not an omission.</b> The write verbs carry a teacher's
 * typed content and need {@link QuestionValidator}; these carry an id and a page number. What
 * would be validation is a shape check on the id, and everything else about the request is a
 * filter, where a value that matches nothing is an empty screen rather than a refusal (contract
 * section 8).
 *
 * <h2>Every miss is the same NOT_FOUND</h2>
 *
 * <p>Unknown, soft-deleted and out-of-reach are one answer with one sentence, decided in the
 * service which returns an empty {@link java.util.Optional} for all three. Naming which it was
 * would tell a caller walking display ids that a question exists and which course owns it
 * (contract section 6).
 */
public class BankReadHandlers {

    private static final Logger log = LoggerFactory.getLogger(BankReadHandlers.class);

    /** Work that has passed every gate: a session, the checked caller, and a checked payload. */
    @FunctionalInterface
    private interface ReadWork<T> {
        Message apply(Session session, CallerContext caller, T payload);
    }

    private final SessionFactory sessionFactory;
    private final BankBrowseService browse;

    public BankReadHandlers(SessionFactory sessionFactory, BankBrowseService browse) {
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.browse = Objects.requireNonNull(browse, "browse");
    }

    /**
     * Registers the four read verbs.
     *
     * @param router the router to register on
     */
    public void registerOn(MessageRouter router) {
        Objects.requireNonNull(router, "router");
        router.register(Verb.BANK_LIST, this::list);
        router.register(Verb.QUESTION_GET, this::get);
        router.register(Verb.QUESTION_VERSIONS, this::versions);
        router.register(Verb.QUESTION_IMAGE_GET, this::image);
    }

    // ===================== The shared gate ===============================

    /**
     * The authorization shape every bank read verb wears.
     *
     * @param request the incoming request, for its payload and to answer against
     * @param caller  the authenticated caller
     * @param type    the payload type this verb takes
     * @param shape   per-verb shape check; returns the sentence to answer with, or {@code null}
     *                when the payload is usable. Runs before any transaction opens
     * @param work    what to do once every gate has passed
     * @param <T>     the payload type
     * @return the work's response, or the first gate's refusal
     */
    private <T> Message asReader(Message request,
                                 CallerContext caller,
                                 Class<T> type,
                                 Function<T, String> shape,
                                 ReadWork<T> work) {
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR, Role.PRINCIPAL);

        Object payload = request.getPayload();
        if (!type.isInstance(payload)) {
            log.debug("{} from {} carried {} rather than {}", request.getVerb(), caller,
                    payload == null ? "no payload" : payload.getClass().getName(),
                    type.getSimpleName());
            return Message.error(request, ErrorCode.VALIDATION, BankMessages.MALFORMED_REQUEST);
        }
        T checked = type.cast(payload);

        String problem = shape.apply(checked);
        if (problem != null) {
            return Message.error(request, ErrorCode.VALIDATION, problem);
        }
        return Transactions.inTx(sessionFactory, session -> work.apply(session, caller, checked));
    }

    // ===================== BANK_LIST (E6.5) ==============================

    /**
     * {@code BANK_LIST} - one page of the browse, filtered to what the caller reaches (E6.5).
     *
     * <p>The one read verb with no guard, because there is no single course for a guard to
     * check: the browse intersects her reachable set with whatever she filtered by, so scope is
     * a filter here and never a refusal.
     *
     * @param caller  the authenticated caller
     * @param request the request, carrying a {@link BankListRequest}
     * @return {@code OK} with a {@code BankPage}, possibly empty; {@code VALIDATION} for a bad
     *         payload
     */
    Message list(CallerContext caller, Message request) {
        return asReader(request, caller, BankListRequest.class, ask -> null,
                (session, reader, ask) ->
                        Message.ok(request, browse.list(session, reader, ask)));
    }

    // ===================== QUESTION_GET (E6.3) ===========================

    /**
     * {@code QUESTION_GET} - one question at its latest version (E6.3).
     *
     * @param caller  the authenticated caller
     * @param request the request, carrying a {@link QuestionRequest}
     * @return {@code OK} with a {@code QuestionDetail}; {@code NOT_FOUND} when it is unknown,
     *         deleted or out of her reach, indistinguishably
     */
    Message get(CallerContext caller, Message request) {
        return asReader(request, caller, QuestionRequest.class, BankReadHandlers::checkQuestionId,
                (session, reader, ask) -> browse.get(session, reader, ask.displayId5())
                        .map(detail -> Message.ok(request, detail))
                        .orElseGet(() -> Message.error(request, ErrorCode.NOT_FOUND,
                                BankMessages.QUESTION_NOT_FOUND)));
    }

    // ===================== QUESTION_VERSIONS (E6.3) ======================

    /**
     * {@code QUESTION_VERSIONS} - the whole history, newest first (E6.3).
     *
     * @param caller  the authenticated caller
     * @param request the request, carrying a {@link QuestionRequest}
     * @return {@code OK} with a {@code VersionHistory}; {@code NOT_FOUND} on the same three
     *         indistinguishable misses as {@code QUESTION_GET}
     */
    Message versions(CallerContext caller, Message request) {
        return asReader(request, caller, QuestionRequest.class, BankReadHandlers::checkQuestionId,
                (session, reader, ask) -> browse.versions(session, reader, ask.displayId5())
                        .map(history -> Message.ok(request, history))
                        .orElseGet(() -> Message.error(request, ErrorCode.NOT_FOUND,
                                BankMessages.QUESTION_NOT_FOUND)));
    }

    // ===================== QUESTION_IMAGE_GET (E6.6) =====================

    /**
     * {@code QUESTION_IMAGE_GET} - one version's illustration, on demand (E6.6).
     *
     * <p>Its own verb rather than a field on {@code QuestionDetail} so that listing or opening a
     * question never moves up to 2MB nobody asked to see.
     *
     * @param caller  the authenticated caller
     * @param request the request, carrying a {@link QuestionImageRequest}
     * @return {@code OK} with a {@code QuestionImage}; {@code NOT_FOUND} when the question is
     *         unknown, deleted or out of reach, when there is no such version, or when that
     *         version carries no picture, all with one sentence
     */
    Message image(CallerContext caller, Message request) {
        return asReader(request, caller, QuestionImageRequest.class, BankReadHandlers::checkImageId,
                (session, reader, ask) ->
                        browse.image(session, reader, ask.displayId5(), ask.versionNo())
                                .map(image -> Message.ok(request, image))
                                .orElseGet(() -> Message.error(request, ErrorCode.NOT_FOUND,
                                        BankMessages.IMAGE_NOT_FOUND)));
    }

    // ===================== shape checks ==================================

    /**
     * A request naming no question at all.
     *
     * <p>{@code VALIDATION} rather than {@code NOT_FOUND}, and the difference does not leak
     * anything: an absent id names no question, so refusing it discloses nothing about which
     * questions exist. Section 6's rule binds requests that name one.
     */
    private static String checkQuestionId(QuestionRequest ask) {
        return blank(ask.displayId5()) ? BankMessages.MALFORMED_REQUEST : null;
    }

    private static String checkImageId(QuestionImageRequest ask) {
        return blank(ask.displayId5()) ? BankMessages.MALFORMED_REQUEST : null;
    }

    private static boolean blank(String displayId) {
        return displayId == null || displayId.isBlank();
    }
}
