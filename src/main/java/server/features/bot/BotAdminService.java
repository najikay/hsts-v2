package server.features.bot;

import common.dto.auth.Role;
import common.dto.bot.BotActiveRequest;
import common.dto.bot.BotActivityPoint;
import common.dto.bot.BotAnalytics;
import common.dto.bot.BotCourseRequest;
import common.dto.bot.BotCreateRequest;
import common.dto.bot.BotManagerPage;
import common.dto.bot.BotProfile;
import common.dto.bot.BotSourceRow;
import common.dto.bot.BotTopQuestion;
import common.dto.bot.SourceAddRequest;
import common.dto.bot.SourceRemoveRequest;
import common.dto.bot.SourceUpdateRequest;
import common.dto.lock.LockHolder;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.core.Authorization;
import server.core.CallerContext;
import server.db.projections.BotActivityCount;
import server.db.projections.BotSourceInfo;
import server.features.notify.NotificationCatalog;
import server.features.notify.Notifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Managing a course's study bot, server-side (Logic tier, E16.9/E16.10 —
 * F12.1/F12.2/F12.3/F12.4/F12.11).
 *
 * <p>The teacher's half of the feature. Eight verbs, one authorisation rule, and one
 * rule about what a teacher-facing aggregate is allowed to contain.
 *
 * <h2>Authorisation: role plus ownership, on every verb (P-5)</h2>
 *
 * <p>Every handler here is {@code requireRole(TEACHER, COORDINATOR)} <b>and</b> a
 * {@code teaches(caller, course)} check resolved from the repositories. The role
 * gate alone would let any teacher in the school manage any course's bot; the
 * ownership check is what makes the answer "your courses". Neither is ever
 * resolved from the payload — the caller is the session bound to the socket.
 *
 * <h2>One bot per course, and what "create" really means (S-30)</h2>
 *
 * <p>{@code BOT_CREATE} is idempotent. A course has one bot; the second teacher of
 * that course pressing Create gets the existing bot back and starts contributing
 * sources to it. That is the requirement read literally, and it also removes a
 * whole class of race: two co-teachers creating at once cannot produce two bots,
 * because the unique key would not allow it and this path does not try.
 *
 * <h2>And what "delete" really means ⚑ (U-39)</h2>
 *
 * <p>{@code BOT_DELETE} is the counterpart, and it is deliberately not the mirror image.
 * Creating is idempotent and cheap; deleting is neither, because a bot that students have
 * used holds their transcripts (S-33). So a bot with any conversation is refused with the
 * count and pointed at the F12.4 switch, and only a bot nobody has talked to actually goes.
 * See {@link #delete}.
 *
 * <h2>Parse first, store second (F12.2)</h2>
 *
 * <p>A source is extracted <b>before</b> any row is written. A PDF that cannot be
 * read answers the uploader with a sentence she can act on, and nothing is
 * persisted — which is what keeps {@code bot_sources}' two NOT NULL text columns
 * honest and stops a source that contributes nothing to a prompt from sitting in
 * the table looking healthy.
 *
 * <h2>S-34, at the point it is produced ⚑</h2>
 *
 * <p>{@link #analytics} builds {@link BotAnalytics} from reads that never select an
 * identifying column, into types that have nowhere to put one. There is no
 * filtering step in this class that a future edit could skip, because there is
 * nothing to filter.
 */
public final class BotAdminService {

    private static final Logger log = LoggerFactory.getLogger(BotAdminService.class);

    /** How far back the activity chart looks. */
    public static final Duration ACTIVITY_WINDOW = Duration.ofDays(30);

    /** How many question rows the frequent-questions fold reads. */
    public static final int QUESTION_SAMPLE = 2000;

    /** How many frequent questions the teacher sees. */
    public static final int TOP_QUESTIONS = 10;

    /**
     * Whether a teacher may edit one source right now (E18.5, F10.4).
     *
     * <p>A seam rather than a direct {@code EditLockService} dependency, so this
     * service's tests do not need a push gateway and a session manager to prove an
     * authorisation rule. Production wires the real lock service in
     * {@code HSTSServer}; {@link #OPEN} is the "no locking configured" default, used
     * by unit tests of everything that is not the lock.
     */
    @FunctionalInterface
    public interface SourceLocks {

        /** Everything is editable; the default when no lock service is wired. */
        SourceLocks OPEN = (sourceId, userId) -> Optional.empty();

        /**
         * The consult, in {@code EditLockGuard}'s own shape (E18, B-21).
         *
         * <p>Answers <em>who</em> rather than only <em>whether</em>, because a refusal that
         * names its holder is one a teacher can act on: "Avi Mizrahi is editing this" tells
         * her whose door to knock on, and a bare "somebody is" tells her to keep clicking.
         *
         * @param sourceId the {@code bot_sources} row
         * @param userId   the teacher asking
         * @return the other teacher holding a live lock on it, or empty when the row is
         *         unlocked, the hold has lapsed, or the caller holds it herself
         */
        Optional<LockHolder> heldByAnother(long sourceId, long userId);

        /**
         * @param sourceId the {@code bot_sources} row
         * @param userId   the teacher asking
         * @return {@code true} when nobody else holds the advisory lock on it
         */
        default boolean mayEdit(long sourceId, long userId) {
            return heldByAnother(sourceId, userId).isEmpty();
        }
    }

    private final BotStore store;
    private final SourceExtractor extractor;
    private final Notifier notifier;
    private final SourceLocks locks;
    private final Clock clock;

    /**
     * @param store     the transactional data seam
     * @param extractor the PDF/DOCX/text parser (E16.5)
     * @param notifier  durable notifications, for the F12.3 co-teacher message
     * @param locks     the advisory edit-lock seam; {@link SourceLocks#OPEN} in tests
     * @param clock     the server's clock
     */
    public BotAdminService(BotStore store, SourceExtractor extractor, Notifier notifier,
                           SourceLocks locks, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Registers the eight teacher verbs; all authenticated, none open. */
    public void registerOn(server.core.MessageRouter router) {
        Objects.requireNonNull(router, "router");
        router.register(Verb.BOT_MANAGER_GET, this::managerPage);
        router.register(Verb.BOT_CREATE, this::create);
        router.register(Verb.BOT_ACTIVE_SET, this::setActive);
        router.register(Verb.BOT_DELETE, this::delete);
        router.register(Verb.BOT_SOURCE_ADD, this::addSource);
        router.register(Verb.BOT_SOURCE_UPDATE, this::updateSource);
        router.register(Verb.BOT_SOURCE_REMOVE, this::removeSource);
        router.register(Verb.BOT_ANALYTICS_GET, this::analytics);
    }

    // ===================== BOT_MANAGER_GET ===============================

    /** The Bot Manager's whole view of one taught course (F12.1/F12.3). */
    Message managerPage(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);
        if (!(request.getPayload() instanceof BotCourseRequest ask) || !ask.isWellFormed()) {
            return Message.error(request, ErrorCode.VALIDATION, BotMessages.MALFORMED_REQUEST);
        }
        long teacherId = caller.userId();
        return store.inTx(data -> {
            Message refusal = refuseUnlessTeaches(data, request, teacherId, ask.courseCode());
            return refusal != null ? refusal : Message.ok(request, page(data, ask.courseCode()));
        });
    }

    // ===================== BOT_CREATE ====================================

    /** Creates the course's bot, or hands back the one that is already there (S-30). */
    Message create(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);
        if (!(request.getPayload() instanceof BotCreateRequest ask) || !ask.isWellFormed()) {
            return Message.error(request, ErrorCode.VALIDATION, BotMessages.MALFORMED_REQUEST);
        }
        long teacherId = caller.userId();
        return store.inTx(data -> {
            Message refusal = refuseUnlessTeaches(data, request, teacherId, ask.courseCode());
            if (refusal != null) {
                return refusal;
            }
            String courseName = data.courseName(ask.courseCode()).orElse(ask.courseCode());
            String name = ask.name().isBlank() ? courseName + " study bot" : ask.name();
            data.createBot(ask.courseCode(), name);
            log.info("Teacher {} created or joined the study bot for course {}",
                    teacherId, ask.courseCode());
            return Message.ok(request, page(data, ask.courseCode()));
        });
    }

    // ===================== BOT_ACTIVE_SET ================================

    /** Switches the bot on or off (F12.4, S-31). */
    Message setActive(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);
        if (!(request.getPayload() instanceof BotActiveRequest ask) || !ask.isWellFormed()) {
            return Message.error(request, ErrorCode.VALIDATION, BotMessages.MALFORMED_REQUEST);
        }
        long teacherId = caller.userId();
        return store.inTx(data -> {
            Message refusal = refuseUnlessTeaches(data, request, teacherId, ask.courseCode());
            if (refusal != null) {
                return refusal;
            }
            Optional<BotData.BotRecord> bot = data.botForCourse(ask.courseCode());
            if (bot.isEmpty()) {
                return Message.error(request, ErrorCode.NOT_FOUND, BotMessages.BOT_NOT_CREATED);
            }
            data.setActive(bot.get().botId(), ask.active());
            log.info("Teacher {} switched the {} study bot {}",
                    teacherId, ask.courseCode(), ask.active() ? "on" : "off");
            return Message.ok(request, page(data, ask.courseCode()));
        });
    }

    // ===================== BOT_DELETE ====================================

    /**
     * Deletes a course's bot and everything it answered from ⚑ (F12.1, U-39, amendment A3).
     *
     * <p>The lead's ruling of 2026-08-30, and the whole of it is in what this refuses. A bot
     * that no student has ever talked to is a mistake a teacher made and should be able to
     * unmake: the wrong name, the wrong course, a demo bot from a training session. A bot that
     * students <em>have</em> talked to is something else, because {@code bot_sessions} holds
     * their transcripts and S-33 makes those the students' own records. Nobody's record is
     * collateral in somebody else's tidy-up, so the second case answers {@code CONFLICT} with
     * the count and points at the switch that does what she actually wanted (F12.4). V6 says
     * the same thing in the schema: {@code bot_sessions.bot_id} is {@code RESTRICT} while
     * {@code bot_sources.bot_id} is {@code CASCADE}, and this handler is that pair of rules
     * written where a teacher can read the reason.
     *
     * <p><b>The gate order is E6.14's, the one {@code BOT_SOURCE_UPDATE} established:</b> the
     * course, then the scope, then the bot, then the conversations, then the advisory locks,
     * then the write. Scope first means a teacher who does not teach the course learns nothing
     * about it, not even that somebody is editing something in it.
     *
     * <p>The lock consult runs over <b>every</b> source, because that is what the delete
     * touches. {@code BOT_SOURCE_REMOVE} refuses one held row; this would remove all of them at
     * once, so a colleague holding any one of them is the same refusal with the same sentence
     * naming her (B-21). Deleting the bot out from under an open editor is exactly the outcome
     * E18.5's advisory lock exists to prevent, and it is worse here than on a single row: she
     * would come back to a screen with no bot on it at all.
     *
     * <p>The answer is the refreshed {@link BotManagerPage} every mutating verb here answers
     * with, which for a course that now has no bot is {@link BotManagerPage#none()} — the empty
     * state the screen already draws, offering Create. So the client needs no new shape and no
     * special case: it replaces the page it holds, exactly as it does after a toggle.
     */
    Message delete(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);
        if (!(request.getPayload() instanceof BotCourseRequest ask) || !ask.isWellFormed()) {
            return Message.error(request, ErrorCode.VALIDATION, BotMessages.MALFORMED_REQUEST);
        }
        long teacherId = caller.userId();

        Outcome outcome = store.inTx(data -> {
            if (data.courseName(ask.courseCode()).isEmpty()) {
                return Outcome.refused(ErrorCode.NOT_FOUND, BotMessages.NO_SUCH_COURSE);
            }
            if (!data.teaches(teacherId, ask.courseCode())) {
                return Outcome.refused(ErrorCode.FORBIDDEN, BotMessages.NOT_YOUR_COURSE);
            }
            Optional<BotData.BotRecord> bot = data.botForCourse(ask.courseCode());
            if (bot.isEmpty()) {
                return Outcome.refused(ErrorCode.NOT_FOUND, BotMessages.BOT_NOT_CREATED);
            }
            BotData.BotRecord record = bot.get();
            long conversations = data.sessionCount(record.botId());
            if (conversations > 0) {
                // S-33. The transcripts are the students' records, so the bot stays and the
                // sentence counts them.
                return Outcome.refused(ErrorCode.CONFLICT,
                        BotMessages.botHasConversations(conversations));
            }
            String held = heldSourceMessage(data, record.botId(), teacherId);
            if (held != null) {
                return Outcome.refused(ErrorCode.CONFLICT, held);
            }
            // Read before the delete: after it there is no bot to ask who else teaches its
            // course, and a notification about something that rolled back would be a lie.
            List<Long> recipients = data.otherTeachersOf(ask.courseCode(), teacherId);
            String editorName = data.displayNames(Set.of(teacherId))
                    .getOrDefault(teacherId, "A colleague");
            data.deleteBot(record.botId());
            return Outcome.done(page(data, ask.courseCode()), record, recipients, editorName);
        });
        if (outcome.refusal() != null) {
            return Message.error(request, outcome.refusalCode(), outcome.refusal());
        }
        notifyDeleted(outcome);
        log.info("Teacher {} deleted the {} study bot", teacherId, ask.courseCode());
        return Message.ok(request, outcome.page());
    }

    /**
     * The advisory-lock consult for a whole bot's worth of sources (E18.5, U-39).
     *
     * @param data      the open transaction
     * @param botId     the bot about to be deleted
     * @param teacherId the teacher asking; her own holds do not block her
     * @return the refusal sentence naming the first colleague found holding one, or
     *         {@code null} when nobody else is holding anything
     */
    private String heldSourceMessage(BotData data, long botId, long teacherId) {
        for (BotSourceInfo info : data.sourceInfos(botId)) {
            Optional<LockHolder> holder = locks.heldByAnother(info.sourceId(), teacherId);
            if (holder.isPresent()) {
                return BotMessages.sourceLockedBy(holder.get().displayName());
            }
        }
        return null;
    }

    // ===================== BOT_SOURCE_ADD ================================

    /**
     * Adds one piece of material (F12.2/F12.3).
     *
     * <p>Extraction happens outside the transaction and before it. Parsing a large
     * PDF is CPU work measured in hundreds of milliseconds, and doing it with a
     * pool connection held open would make one teacher's upload everybody else's
     * latency. It also means the failure path writes nothing at all rather than
     * rolling something back.
     */
    Message addSource(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);
        if (!(request.getPayload() instanceof SourceAddRequest ask)) {
            return Message.error(request, ErrorCode.VALIDATION, BotMessages.MALFORMED_REQUEST);
        }
        if (!ask.isWellFormed()) {
            return Message.error(request, ErrorCode.VALIDATION, BotMessages.SOURCE_INCOMPLETE);
        }
        if (!ask.isWithinSizeLimit()) {
            return Message.error(request, ErrorCode.VALIDATION, BotMessages.SOURCE_TOO_LARGE);
        }
        long teacherId = caller.userId();

        String text;
        try {
            text = extractor.extract(ask.kind(), ask.content());
        } catch (SourceExtractionException e) {
            // The message is written for the uploader; the cause stays in the log.
            log.info("Source extraction refused for teacher {}: {}", teacherId, e.getMessage());
            return Message.error(request, ErrorCode.VALIDATION, e.getMessage());
        }

        Outcome outcome = store.inTx(data -> {
            if (!data.teaches(teacherId, ask.courseCode())) {
                return Outcome.refused(ErrorCode.FORBIDDEN, BotMessages.NOT_YOUR_COURSE);
            }
            Optional<BotData.BotRecord> bot = data.botForCourse(ask.courseCode());
            if (bot.isEmpty()) {
                return Outcome.refused(ErrorCode.NOT_FOUND, BotMessages.BOT_NOT_CREATED);
            }
            data.addSource(bot.get().botId(), ask.kind(), ask.title(),
                    ask.content(), text, teacherId, clock.instant());
            return Outcome.done(page(data, ask.courseCode()), bot.get(),
                    data.otherTeachersOf(ask.courseCode(), teacherId),
                    data.displayNames(Set.of(teacherId)).getOrDefault(teacherId, "A colleague"));
        });
        if (outcome.refusal() != null) {
            return Message.error(request, outcome.refusalCode(), outcome.refusal());
        }
        notifyCoTeachers(outcome, "added");
        log.info("Teacher {} added a {} source of {} characters to the {} study bot",
                teacherId, ask.kind(), text.length(), ask.courseCode());
        return Message.ok(request, outcome.page());
    }

    // ===================== BOT_SOURCE_UPDATE =============================

    /**
     * Replaces one source's title and content in place ⚑ (F12.3, B-21).
     *
     * <p>The third of F12.3's "add/<b>edit</b>/remove", which until B-21 was the one nothing
     * implemented anywhere in the stack. Correcting a typo meant removing the row and adding
     * it again, which lost the source id, its author, its {@code updated_at} and its version —
     * and lost them <b>silently</b>, because the remove notified the course's other teachers
     * as a removal and the re-add as an addition, so one correction read to a colleague as two
     * unrelated events. This is one event, on one row, and the row survives it.
     *
     * <p><b>The gate order is deliberate and it is E6.14's, not this class's remove path's.</b>
     * Scope first — a teacher who does not teach the course is refused before anything about
     * the source is read or reported — then the advisory lock, then the row's own existence.
     * {@code BOT_SOURCE_REMOVE} consults its lock before its transaction, which is a shape
     * that predates the ruling; the write path everywhere else consults <em>after</em> the
     * scope check, so a lock refusal cannot become a way for an outsider to learn that a
     * source exists and who is holding it.
     *
     * <p>The lock refusal names its holder, which is what makes it act on rather than a wall:
     * a teacher told "Avi Mizrahi is editing this" knows whose door to knock on. That is also
     * the first thing on this screen the {@code EntityRef.BOT_SOURCE} lock has ever had an
     * actual <em>editor</em> to protect (F10.2, and case 13.6's outstanding half).
     *
     * <p>Extraction happens outside the transaction and before it, exactly as on the add path,
     * so a replacement that cannot be parsed leaves the stored source exactly as it was rather
     * than half-overwritten.
     */
    Message updateSource(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);
        if (!(request.getPayload() instanceof SourceUpdateRequest ask)) {
            return Message.error(request, ErrorCode.VALIDATION, BotMessages.MALFORMED_REQUEST);
        }
        if (!ask.isWellFormed()) {
            return Message.error(request, ErrorCode.VALIDATION, BotMessages.SOURCE_INCOMPLETE);
        }
        if (!ask.isWithinSizeLimit()) {
            return Message.error(request, ErrorCode.VALIDATION, BotMessages.SOURCE_TOO_LARGE);
        }
        long teacherId = caller.userId();

        String text;
        try {
            text = extractor.extract(ask.kind(), ask.content());
        } catch (SourceExtractionException e) {
            log.info("Source extraction refused for teacher {}: {}", teacherId, e.getMessage());
            return Message.error(request, ErrorCode.VALIDATION, e.getMessage());
        }

        Outcome outcome = store.inTx(data -> {
            if (!data.teaches(teacherId, ask.courseCode())) {
                return Outcome.refused(ErrorCode.FORBIDDEN, BotMessages.NOT_YOUR_COURSE);
            }
            Optional<BotData.BotRecord> bot = data.botForCourse(ask.courseCode());
            if (bot.isEmpty()) {
                return Outcome.refused(ErrorCode.NOT_FOUND, BotMessages.BOT_NOT_CREATED);
            }
            // The lock consult, between the scope check and the write. Inside the transaction
            // rather than before it, so an outsider cannot use a CONFLICT to learn that a
            // source exists in a course that is none of hers.
            if (!locks.mayEdit(ask.sourceId(), teacherId)) {
                return Outcome.refused(ErrorCode.CONFLICT, lockedMessage(ask.sourceId(), teacherId));
            }
            if (!data.updateSource(bot.get().botId(), ask.sourceId(), ask.kind(), ask.title(),
                    ask.content(), text, clock.instant())) {
                return Outcome.refused(ErrorCode.NOT_FOUND, BotMessages.SOURCE_NOT_FOUND);
            }
            return Outcome.done(page(data, ask.courseCode()), bot.get(),
                    data.otherTeachersOf(ask.courseCode(), teacherId),
                    data.displayNames(Set.of(teacherId)).getOrDefault(teacherId, "A colleague"));
        });
        if (outcome.refusal() != null) {
            return Message.error(request, outcome.refusalCode(), outcome.refusal());
        }
        notifyCoTeachers(outcome, "changed");
        log.info("Teacher {} replaced source {} of the {} study bot with {} characters",
                teacherId, ask.sourceId(), ask.courseCode(), text.length());
        return Message.ok(request, outcome.page());
    }

    // ===================== BOT_SOURCE_REMOVE =============================

    /** Removes one source, subject to the advisory edit lock (F12.3, E18.5). */
    Message removeSource(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);
        if (!(request.getPayload() instanceof SourceRemoveRequest ask) || !ask.isWellFormed()) {
            return Message.error(request, ErrorCode.VALIDATION, BotMessages.MALFORMED_REQUEST);
        }
        long teacherId = caller.userId();
        if (!locks.mayEdit(ask.sourceId(), teacherId)) {
            // The advisory lock is the UX half of ADR-008; refusing here is what
            // makes it more than a banner, without a pessimistic database lock.
            return Message.error(request, ErrorCode.CONFLICT, BotMessages.SOURCE_LOCKED);
        }
        Outcome outcome = store.inTx(data -> {
            if (!data.teaches(teacherId, ask.courseCode())) {
                return Outcome.refused(ErrorCode.FORBIDDEN, BotMessages.NOT_YOUR_COURSE);
            }
            Optional<BotData.BotRecord> bot = data.botForCourse(ask.courseCode());
            if (bot.isEmpty()) {
                return Outcome.refused(ErrorCode.NOT_FOUND, BotMessages.BOT_NOT_CREATED);
            }
            if (!data.removeSource(bot.get().botId(), ask.sourceId())) {
                return Outcome.refused(ErrorCode.NOT_FOUND, BotMessages.SOURCE_NOT_FOUND);
            }
            return Outcome.done(page(data, ask.courseCode()), bot.get(),
                    data.otherTeachersOf(ask.courseCode(), teacherId),
                    data.displayNames(Set.of(teacherId)).getOrDefault(teacherId, "A colleague"));
        });
        if (outcome.refusal() != null) {
            return Message.error(request, outcome.refusalCode(), outcome.refusal());
        }
        notifyCoTeachers(outcome, "removed");
        log.info("Teacher {} removed source {} from the {} study bot",
                teacherId, ask.sourceId(), ask.courseCode());
        return Message.ok(request, outcome.page());
    }

    // ===================== BOT_ANALYTICS_GET =============================

    /** The anonymised usage aggregate (F12.11, S-34 ⚑). */
    Message analytics(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);
        if (!(request.getPayload() instanceof BotCourseRequest ask) || !ask.isWellFormed()) {
            return Message.error(request, ErrorCode.VALIDATION, BotMessages.MALFORMED_REQUEST);
        }
        long teacherId = caller.userId();
        Instant since = clock.instant().minus(ACTIVITY_WINDOW);
        return store.inTx(data -> {
            Message refusal = refuseUnlessTeaches(data, request, teacherId, ask.courseCode());
            if (refusal != null) {
                return refusal;
            }
            String courseName = data.courseName(ask.courseCode()).orElse(ask.courseCode());
            Optional<BotData.BotRecord> bot = data.botForCourse(ask.courseCode());
            if (bot.isEmpty()) {
                return Message.ok(request, BotAnalytics.empty(courseName));
            }
            long botId = bot.get().botId();
            long total = data.countMessages(botId);
            List<BotActivityPoint> activity = data.activity(botId, since).stream()
                    .map(count -> new BotActivityPoint(count.day(), count.count()))
                    .toList();
            List<BotTopQuestion> frequent = fold(data.recentQuestions(botId, QUESTION_SAMPLE));
            return Message.ok(request, new BotAnalytics(courseName,
                    (int) Math.min(Integer.MAX_VALUE, total), activity, frequent));
        });
    }

    /**
     * Groups question texts into the frequent-questions list (S-34).
     *
     * <p>Folded in Java rather than in SQL because the normalisation that makes
     * "What is a foreign key?" and "what is a foreign key" one row is ours, and a
     * per-engine SQL equivalent would drift from what the screen says it is
     * showing. The fold reads no identifying column because the read that fed it
     * never selected one.
     *
     * <p>Ties are broken by the question text so the list is stable between two
     * loads of the same screen; a list that reordered itself on refresh looks
     * broken even when the numbers are right.
     */
    static List<BotTopQuestion> fold(List<String> questions) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String question : questions == null ? List.<String>of() : questions) {
            String key = TextNormaliser.groupingKey(question);
            if (!key.isEmpty()) {
                counts.merge(key, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .map(entry -> new BotTopQuestion(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(BotTopQuestion::count).reversed()
                        .thenComparing(BotTopQuestion::question))
                .limit(TOP_QUESTIONS)
                .collect(Collectors.toList());
    }

    // ===================== Shared internals ==============================

    /**
     * The lock refusal, naming its holder when the lock service can say who it is (B-21).
     *
     * @param sourceId  the source somebody else is editing
     * @param teacherId the teacher who was refused
     * @return the sentence to answer {@code CONFLICT} with
     */
    private String lockedMessage(long sourceId, long teacherId) {
        return locks.heldByAnother(sourceId, teacherId)
                .map(LockHolder::displayName)
                .map(BotMessages::sourceLockedBy)
                .orElse(BotMessages.SOURCE_LOCKED);
    }

    /** @return a refusal message, or {@code null} when the caller teaches the course. */
    private Message refuseUnlessTeaches(BotData data, Message request, long teacherId, String courseCode) {
        if (data.courseName(courseCode).isEmpty()) {
            return Message.error(request, ErrorCode.NOT_FOUND, BotMessages.NO_SUCH_COURSE);
        }
        if (!data.teaches(teacherId, courseCode)) {
            return Message.error(request, ErrorCode.FORBIDDEN, BotMessages.NOT_YOUR_COURSE);
        }
        return null;
    }

    /**
     * Builds the whole manager page.
     *
     * <p>Every mutating verb answers with one of these rather than an
     * acknowledgement, for the reason the notifications feature answers with a
     * whole page: the screen re-renders from the server's own read, so it cannot
     * drift from the truth by patching a row it guessed at.
     */
    private BotManagerPage page(BotData data, String courseCode) {
        Optional<BotData.BotRecord> bot = data.botForCourse(courseCode);
        if (bot.isEmpty()) {
            return BotManagerPage.none();
        }
        BotData.BotRecord record = bot.get();
        List<BotSourceInfo> infos = data.sourceInfos(record.botId());
        Map<Long, String> names = data.displayNames(
                infos.stream().map(BotSourceInfo::addedBy).collect(Collectors.toSet()));
        // B-21: the bodies of the free-text sources only, so Edit opens on what is stored.
        // One extra scalar read for the whole page rather than a round trip per dialog, and
        // it never touches a PDF's bytes or its extraction - see BotRepository's own javadoc.
        Map<Long, String> bodies = data.textSourceBodies(record.botId());
        List<BotSourceRow> rows = new ArrayList<>();
        for (BotSourceInfo info : infos) {
            rows.add(new BotSourceRow(info.sourceId(),
                    JpaBotStore.toWireKind(info.type()),
                    info.title(),
                    names.getOrDefault(info.addedBy(), "A colleague"),
                    info.updatedAt(),
                    info.version(),
                    info.characters(),
                    bodies.get(info.sourceId())));
        }
        return BotManagerPage.of(new BotProfile(record.botId(), record.courseCode(),
                record.courseName(), record.name(), record.active()), rows);
    }

    /**
     * Tells the course's other teachers that the material changed (F12.3).
     *
     * <p>Outside the transaction, and after it: a notification for a change that
     * rolled back would be a lie, and a dead socket must not be able to roll back
     * a teacher's upload. The wording comes from {@code NotificationCatalog} rather
     * than from here, so this feature cannot invent a sentence nobody reviewed.
     */
    private void notifyCoTeachers(Outcome outcome, String verbForLog) {
        if (outcome.recipients().isEmpty()) {
            log.debug("No co-teachers to tell that a source was {}", verbForLog);
            return;
        }
        notifier.notify(outcome.recipients(), NotificationCatalog.botSourceChanged(
                outcome.bot().courseName(), outcome.editorName(), outcome.bot().botId()));
    }

    /**
     * Tells the course's other teachers that the bot itself is gone (U-39).
     *
     * <p>Its own sentence rather than the source one, because "Dana Cohen changed the study bot
     * sources for Java 21" said of a bot that no longer exists would send a colleague looking
     * for a table that is not there. Not its own {@code NotificationType}, because a
     * co-teacher does the same thing about both, which is open the manager and look; the
     * reasoning is on {@code NotificationCatalog.botDeleted}.
     *
     * <p>Outside the transaction and after it, on the same rule {@link #notifyCoTeachers}
     * obeys.
     */
    private void notifyDeleted(Outcome outcome) {
        if (outcome.recipients().isEmpty()) {
            log.debug("No co-teachers to tell that a study bot was deleted");
            return;
        }
        notifier.notify(outcome.recipients(), NotificationCatalog.botDeleted(
                outcome.bot().courseName(), outcome.editorName(), outcome.bot().botId()));
    }

    /**
     * What one mutating verb produced, or why it could not.
     *
     * @param page         the refreshed manager page
     * @param bot          the bot that changed
     * @param recipients   the co-teachers to notify
     * @param editorName   who made the change, for the notification sentence
     * @param refusalCode  the error code, when refused
     * @param refusal      the sentence, when refused
     */
    private record Outcome(BotManagerPage page, BotData.BotRecord bot, List<Long> recipients,
                           String editorName, ErrorCode refusalCode, String refusal) {

        static Outcome refused(ErrorCode code, String message) {
            return new Outcome(null, null, List.of(), "", code, message);
        }

        static Outcome done(BotManagerPage page, BotData.BotRecord bot,
                            List<Long> recipients, String editorName) {
            return new Outcome(page, bot, List.copyOf(recipients), editorName, null, null);
        }
    }
}
