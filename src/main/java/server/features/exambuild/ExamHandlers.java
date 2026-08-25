package server.features.exambuild;

import common.dto.authoring.AutoComposeRequest;
import common.dto.auth.Role;
import common.dto.authoring.ExamCreateRequest;
import common.dto.authoring.ExamVersionAction;
import common.dto.authoring.ExamVersionRequest;
import common.dto.authoring.ExamVersionSave;
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
import server.features.approval.ApprovalService;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * The seven exam builder verbs that read and write one teacher's own exams (Logic tier, E7.1,
 * E7.2, E7.3, E7.4, E7.5, E7.6, E7.10).
 *
 * <p><b>{@code EXAM_AUTO_COMPOSE} is now here too, and this note used to say why it was not.</b>
 * It was held back because contract §7 did not determine what to report for quotas whose
 * candidate pools cross rather than nest. §7.3a settled that on 2026-08-24 by making crossing
 * pools <em>unrepresentable</em> rather than by naming a row for them:
 * {@link ExamValidator#quotaProblem} refuses a graded course-wide quota beside topic quotas, so
 * every remaining family nests and the bucket comparisons in {@link AutoComposer} are exact. The
 * rule had been written into the contract and not into the validator; both now agree.
 *
 * <p>Its guard is the other one, like {@code EXAM_CREATE}: the caller supplies the course, so
 * {@code requireTeachesCourse} throws rather than folding into {@code NOT_FOUND}.
 *
 * <h2>Two guards, chosen by verb, never composed</h2>
 *
 * <p>The contract's §3 table makes the verb-to-guard mapping the thing a reviewer checks, so a
 * handler wearing the wrong guard has to be visibly wrong rather than subtly wrong:
 *
 * <ul>
 *   <li><b>{@code EXAM_CREATE}</b> resolves scope from a course the caller <em>supplied</em>, and
 *       refuses with {@code FORBIDDEN} naming it. A refusal naming a course she just typed
 *       discloses nothing she did not already know.</li>
 *   <li><b>The four that address one stored version</b> - {@code EXAM_VERSION_GET},
 *       {@code EXAM_VERSION_SAVE}, {@code EXAM_VERSION_REVISE}, {@code EXAM_SUBMIT} - check
 *       authorship against the <em>stored</em> row and answer {@code NOT_FOUND}. Unknown id,
 *       another teacher's exam, and an exam she may not reach are one answer, indistinguishable
 *       on purpose: naming the exam would tell a caller probing ids that it exists and who owns
 *       it, which is the existence oracle {@code docs/PROBLEMS.md} P-5 is about.</li>
 *   <li><b>{@code EXAM_LIST} addresses no row and so refuses nothing.</b> Its scope is an author
 *       predicate in the SQL of both its reads, and it has no {@code NOT_FOUND} path at all: a
 *       teacher who has written nothing gets an empty list, which is a real answer. Counting it
 *       among the {@code NOT_FOUND} verbs, as an earlier draft of this javadoc did, describes a
 *       refusal that does not exist and cannot be reached.</li>
 * </ul>
 *
 * <p><b>Neither guard is applied in this class.</b> Only the role gate is. Both scope guards need
 * a row or a course lookup, so they live in {@link ExamService} where a transaction is open, and
 * that class applies exactly one of them per verb. This class must therefore not re-check scope:
 * a second, differently-written check is a second expression of one rule, and the first time the
 * two disagreed the disagreement would be invisible from either side.
 *
 * <p>The caller's id reaches the service as a {@link CallerContext} and never as a payload field.
 * No request record in {@code common.dto.authoring} carries an author id, so there is no field a
 * client could put somebody else's id in (ARCHITECTURE §3, P-5).
 *
 * <h2>Where validation is, and why it is not out here</h2>
 *
 * <p>{@link server.features.bank.BankHandlers} validates before opening its transaction. This
 * class does not, and the difference is not an oversight: E7's rules are not separable that way.
 * Three of the five rules with no schema backstop - no soft-deleted question, no duplicate
 * question through two versions of it, every question in the exam's own course - can only be
 * decided against rows the request names, so {@link ExamValidator}'s composition half needs a
 * session. {@link ExamService} runs metadata and composition as one ordered pass for that reason,
 * and splitting the half that could run out here would put two validation entry points on one
 * write path. The cost is a transaction opened for a payload that was never going to be honoured,
 * which is a round trip rather than a rule.
 *
 * <p>What this class does refuse before any transaction opens is a payload of the wrong
 * <em>type</em>, which is {@code VALIDATION} and never an exception. {@code BAD_REQUEST} is
 * deliberately unused in this contract.
 */
public class ExamHandlers {

    private static final Logger log = LoggerFactory.getLogger(ExamHandlers.class);

    private final SessionFactory sessionFactory;
    private final ExamService exams;

    /**
     * E8's hook, called by {@code EXAM_SUBMIT} after its transaction commits.
     *
     * <p>Required rather than optional. A null would make "submitting notifies nobody" a runtime
     * state nobody declares, and the deployment where it was null is the one where a coordinator
     * never learns an exam is waiting and every symptom is silence.
     */
    private final ApprovalService approvals;

    public ExamHandlers(SessionFactory sessionFactory, ExamService exams,
                        ApprovalService approvals) {
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.exams = Objects.requireNonNull(exams, "exams");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
    }

    /**
     * Puts the seven verbs on the router.
     *
     * @param router the router to register on
     */
    public void registerOn(MessageRouter router) {
        Objects.requireNonNull(router, "router");
        router.register(Verb.EXAM_LIST, this::list);
        router.register(Verb.EXAM_VERSION_GET, this::get);
        router.register(Verb.EXAM_CREATE, this::create);
        router.register(Verb.EXAM_VERSION_SAVE, this::save);
        router.register(Verb.EXAM_VERSION_REVISE, this::revise);
        router.register(Verb.EXAM_SUBMIT, this::submit);
        router.register(Verb.EXAM_AUTO_COMPOSE, this::autoCompose);
    }

    // ===================== The shared gates ===============================

    /**
     * The role gate every verb in this group wears, spelled once.
     *
     * <p>Staff only, and the principal is absent from this group entirely: F9.3 gives her a read
     * of data as entered, {@code DATA_EXAMS_GET} already serves her the school's exams, and an
     * authoring surface is not a read of entered data.
     *
     * @param caller the authenticated caller
     */
    private static void requireStaff(CallerContext caller) {
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);
    }

    /**
     * Role, payload type, then one transaction. The shape of five of the six verbs.
     *
     * @param request the incoming request, for its payload and to answer against
     * @param caller  the authenticated caller
     * @param type    the payload type this verb takes
     * @param work    what to do once both gates have passed
     * @param <T>     the payload type
     * @return the work's outcome as a message, or the first gate's refusal
     */
    private <T> Message withPayload(Message request, CallerContext caller, Class<T> type,
                                    BiFunction<Session, T, ExamService.BuildOutcome> work) {
        requireStaff(caller);

        Object payload = request.getPayload();
        if (!type.isInstance(payload)) {
            log.debug("{} from {} carried {} rather than {}", request.getVerb(), caller,
                    payload == null ? "no payload" : payload.getClass().getName(),
                    type.getSimpleName());
            return Message.error(request, ErrorCode.VALIDATION,
                    ExamBuildMessages.MALFORMED_REQUEST);
        }
        T checked = type.cast(payload);

        return answer(request, Transactions.inTx(sessionFactory,
                session -> work.apply(session, checked)));
    }

    /**
     * One {@link ExamService.BuildOutcome} as one wire answer.
     *
     * <p>The mapping is exhaustive and has no default, so a sixth status could not be added to
     * the service without this switch refusing to compile. That is the moment somebody should be
     * deciding what the wire says about it, rather than the moment a teacher meets a status the
     * handler silently treated as an error.
     *
     * @param request the request being answered
     * @param outcome what the service did
     * @return {@code OK} with the stored composition, or the refusal with its sentence
     */
    private static Message answer(Message request, ExamService.BuildOutcome outcome) {
        return switch (outcome.status()) {
            case OK -> Message.ok(request, outcome.composition());
            // Unknown version and one she did not author are one answer (P-5).
            case NOT_FOUND -> Message.error(request, ErrorCode.NOT_FOUND, outcome.message());
            case INVALID -> Message.error(request, ErrorCode.VALIDATION, outcome.message());
            // Wrong state for the verb, a stale lock token, or another teacher holding the edit
            // lock. Three events, three sentences, one code: the sentence is what tells her
            // whether to reopen the exam or to wait for somebody.
            case CONFLICT -> Message.error(request, ErrorCode.CONFLICT, outcome.message());
        };
    }

    // ===================== EXAM_LIST (E7.10) ==============================

    /**
     * {@code EXAM_LIST} - every exam the calling teacher wrote, versions included (E7.10).
     *
     * <p>The one verb here with no payload at all, and the only one that does not go through
     * {@link #withPayload}. Whose exams these are is resolved from the session, never from a
     * field, so there is nothing to type-check and nothing a client could put another teacher's
     * id into. A payload sent anyway is ignored rather than refused: the request is complete
     * without it and refusing would be a rule with no purpose behind it.
     *
     * @param caller  the authenticated teacher
     * @param request the request, whose payload is {@code null}
     * @return {@code OK} with an {@code ExamList}, empty when she has written nothing
     */
    Message list(CallerContext caller, Message request) {
        requireStaff(caller);
        return Message.ok(request, Transactions.inTx(sessionFactory,
                session -> exams.list(session, caller)));
    }

    // ===================== EXAM_VERSION_GET (E7.14) =======================

    /**
     * {@code EXAM_VERSION_GET} - one exam version opened, whole (E7.14, F3.5).
     *
     * <p>Serves two screens: it opens the builder on a DRAFT and renders a past version read-only
     * in the history panel. One payload, and the client decides what is editable from
     * {@code state}, so a past version and a live draft can never render from two shapes that
     * drift.
     *
     * @param caller  the authenticated author
     * @param request the request, carrying an {@link ExamVersionRequest}
     * @return {@code OK} with the composition; {@code NOT_FOUND} for a version that is not hers
     *         or not there; {@code VALIDATION} for a bad payload
     */
    Message get(CallerContext caller, Message request) {
        return withPayload(request, caller, ExamVersionRequest.class,
                (session, ask) -> exams.get(session, caller, ask));
    }

    // ===================== EXAM_CREATE (E7.1) =============================

    /**
     * {@code EXAM_CREATE} - a whole exam, composed, in one message (E7.1, E7.2, E7.3, S-11).
     *
     * <p>The one verb whose scope guard throws. The caller supplied the course, so
     * {@code FORBIDDEN} naming it discloses nothing she did not type. The guard runs inside
     * {@link ExamService#create}, against the course directory, not here.
     *
     * <p>There is no half-composed row to create: the whole composition arrives at once because
     * the points-sum-to-100 rule has no DDL backstop and is enforced on the write path with no
     * exceptions. A work-in-progress exam lives in the teacher's client and nowhere else.
     *
     * @param caller  the authenticated author
     * @param request the request, carrying an {@link ExamCreateRequest}
     * @return {@code OK} with the stored exam at version 1; {@code VALIDATION} for a broken rule
     *         or a bad payload
     * @throws server.core.AuthorizationException {@code FORBIDDEN} when the course is not one she
     *                                            teaches
     */
    Message create(CallerContext caller, Message request) {
        return withPayload(request, caller, ExamCreateRequest.class,
                (session, draft) -> exams.create(session, caller, draft));
    }

    // ===================== EXAM_AUTO_COMPOSE (E7.4) =======================

    /**
     * {@code EXAM_AUTO_COMPOSE} - a proposal, or exactly what is missing (E7.4 ⚑, F3.2, F3.3).
     *
     * <p><b>An infeasible request answers {@code OK}, and that is the important line here.</b>
     * She asked what her bank could do and was told precisely; nothing failed. Mapping it to an
     * error code would put F3.3's whole report behind a red banner and drop the shortfall rows on
     * the way, which is the one outcome that would make the feature pointless. Only criteria that
     * are malformed - a negative bucket, a topic twice, the two shapes mixed, more questions than
     * 100 points can cover - are a {@code VALIDATION}.
     *
     * <p>Not routed through {@link #withPayload}, deliberately: that helper answers a
     * {@code BuildOutcome} whose {@code OK} payload is a stored {@code ExamComposition}, and this
     * verb stores nothing and answers a different type. Bending the helper to carry both would
     * make the write path's answer shape depend on a read-only verb.
     *
     * @param caller  the authenticated teacher or coordinator
     * @param request the request, carrying an {@link AutoComposeRequest}
     * @return {@code OK} with an {@code AutoComposeResult}, feasible or not; {@code VALIDATION}
     *         for criteria that cannot be interpreted
     * @throws server.core.AuthorizationException {@code FORBIDDEN} when the course is not hers
     */
    Message autoCompose(CallerContext caller, Message request) {
        requireStaff(caller);

        if (!(request.getPayload() instanceof AutoComposeRequest criteria)) {
            log.debug("{} from {} carried {} rather than AutoComposeRequest", request.getVerb(),
                    caller, request.getPayload() == null ? "no payload"
                            : request.getPayload().getClass().getName());
            return Message.error(request, ErrorCode.VALIDATION,
                    ExamBuildMessages.MALFORMED_REQUEST);
        }

        ExamService.AutoOutcome outcome = Transactions.inTx(sessionFactory,
                session -> exams.autoCompose(session, caller, criteria));

        return outcome.status() == ExamService.BuildStatus.OK
                ? Message.ok(request, outcome.result())
                : Message.error(request, ErrorCode.VALIDATION, outcome.message());
    }

    // ===================== EXAM_VERSION_SAVE (E7.2, E7.3) =================

    /**
     * {@code EXAM_VERSION_SAVE} - a DRAFT rewritten in place (E7.2, E7.3, F3.1).
     *
     * @param caller  the authenticated author
     * @param request the request, carrying an {@link ExamVersionSave}
     * @return {@code OK} with the version as stored; {@code NOT_FOUND} for a version that is not
     *         hers or not there; {@code VALIDATION} for a broken rule; {@code CONFLICT} when it
     *         is not a DRAFT, when her lock token is stale, or when another teacher holds the
     *         edit lock
     */
    Message save(CallerContext caller, Message request) {
        return withPayload(request, caller, ExamVersionSave.class,
                (session, save) -> exams.save(session, caller, save));
    }

    // ===================== EXAM_VERSION_REVISE (E7.5) =====================

    /**
     * {@code EXAM_VERSION_REVISE} - version n+1 as a DRAFT, leaving n untouched (E7.5, C-2).
     *
     * <p>Refuses a DRAFT with {@code CONFLICT} rather than {@code VALIDATION}: nothing about her
     * request was malformed, the version is simply already the thing she asked to be made.
     *
     * @param caller  the authenticated author
     * @param request the request, carrying an {@link ExamVersionAction}
     * @return {@code OK} with the new DRAFT; {@code NOT_FOUND} for a version that is not hers or
     *         not there; {@code CONFLICT} when it is already a DRAFT, when her lock token is
     *         stale, or when another teacher holds the edit lock; {@code VALIDATION} when a
     *         question the copy would carry has since been deleted from the bank
     */
    Message revise(CallerContext caller, Message request) {
        return withPayload(request, caller, ExamVersionAction.class,
                (session, action) -> exams.revise(session, caller, action));
    }

    // ===================== EXAM_SUBMIT (E7.6) =============================

    /**
     * {@code EXAM_SUBMIT} - DRAFT to PENDING, and the hand-off to E8 (E7.6, F4.1).
     *
     * <p><b>The hook runs here, after the transaction commits, and that placement is the whole
     * point of it</b> (contract §5.5, amended 2026-08-24). {@link ExamService#submitForApproval}
     * owns the transition and sends no notification of its own;
     * {@link ApprovalService#versionSubmitted} supersedes the older pending versions and raises
     * both notifications.
     *
     * <p>It cannot be called from inside the transaction, and the failure if it were is silent:
     * {@code versionSubmitted} opens its own session through {@code Transactions.inTx}, so it
     * would not see the uncommitted status flip, would read the row as still DRAFT, and would
     * return at its own {@code isPending} guard before either notification. Nothing would throw.
     * The exam would sit PENDING with nobody told. <b>Do not move this call inside the lambda
     * above, and note that "after the service returned OK" is not sufficient on its own - inside
     * {@code Transactions.inTx} the service has returned and nothing has committed.</b>
     *
     * <p><b>A hook that throws does not turn a committed submission into an error.</b> The
     * transaction is already committed when it runs, so an exception here would tell her the
     * submit failed when it did not, and she would submit again. It is logged at error instead.
     * The window this leaves is the one §5.5 states rather than hides: a crash between the commit
     * and the hook loses the supersede and the bells, and never loses the submission, because the
     * version is PENDING in a committed transaction and the coordinator's queue reads status
     * rather than notifications. A re-submit re-fires the hook.
     *
     * @param caller  the authenticated author
     * @param request the request, carrying an {@link ExamVersionAction}
     * @return {@code OK} with the version, now PENDING; {@code NOT_FOUND} for a version that is
     *         not hers or not there; {@code CONFLICT} when it is not a DRAFT, when her lock token
     *         is stale, or when another teacher holds the edit lock; {@code VALIDATION} when the
     *         draft does not satisfy the rules a submittable exam has to satisfy
     */
    Message submit(CallerContext caller, Message request) {
        requireStaff(caller);

        Object payload = request.getPayload();
        if (!(payload instanceof ExamVersionAction action)) {
            log.debug("EXAM_SUBMIT from {} carried {} rather than ExamVersionAction", caller,
                    payload == null ? "no payload" : payload.getClass().getName());
            return Message.error(request, ErrorCode.VALIDATION,
                    ExamBuildMessages.MALFORMED_REQUEST);
        }

        ExamService.BuildOutcome outcome = Transactions.inTx(sessionFactory,
                session -> exams.submitForApproval(session, caller, action));

        if (outcome.status() == ExamService.BuildStatus.OK) {
            notifyApproval(action.examVersionId());
        }
        return answer(request, outcome);
    }

    /**
     * Runs E8's hook and refuses to let its failure reach the teacher.
     *
     * @param examVersionId the version that has just been committed as PENDING
     */
    private void notifyApproval(long examVersionId) {
        try {
            int superseded = approvals.versionSubmitted(examVersionId);
            log.debug("Exam version {} submitted; {} older version(s) superseded", examVersionId,
                    superseded);
        } catch (RuntimeException failure) {
            // Deliberately swallowed, and this is the only place in the group where anything is.
            // The submission is committed; the coordinator's queue reads status and will show it.
            // What is lost is the supersede and the bells, which a re-submit re-fires.
            log.error("Exam version {} is submitted and committed, but the approval hook failed: "
                    + "no notification was sent and older versions were not superseded. The "
                    + "version is PENDING and the coordinator's queue still shows it.",
                    examVersionId, failure);
        }
    }
}
