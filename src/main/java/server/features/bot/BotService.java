package server.features.bot;

import common.dto.bot.BotAnswer;
import common.dto.bot.BotAskRequest;
import common.dto.bot.BotConversation;
import common.dto.bot.BotCourseRequest;
import common.dto.bot.BotIntegrityNotice;
import common.dto.bot.BotSessionRequest;
import common.dto.bot.BotSessionRow;
import common.dto.bot.BotSessionsPage;
import common.dto.bot.BotTurn;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.core.Authorization;
import server.core.CallerContext;
import server.core.MessageRouter;
import server.db.projections.BotBankQuestion;
import server.db.projections.BotSourceText;
import server.features.exam.ActiveAttempt;
import server.features.exam.AttemptTracker;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Asking the study bot, server-side (Logic tier, E16.8 ⚑ — F12.4/F12.5/F12.7/F12.9,
 * C-4).
 *
 * <p>The feature that was dead at v1's defence. This class is the whole of what a
 * student's question does, and it is written so that every rule in it is a
 * statement someone can check.
 *
 * <h2>The guards, in order, and why the order is the order</h2>
 *
 * <ol>
 *   <li><b>Enrolment</b> (S-31). Cheapest, and the one whose failure means she is
 *       looking at the wrong course entirely.</li>
 *   <li><b>The bot exists, and is switched on</b> (F12.1/F12.4). Two different
 *       sentences, because "your teacher has not made one" and "your teacher
 *       turned it off" are different things to do next about.</li>
 *   <li><b>Rate limit</b> (E16.8). Before the C-4 work, because refusing a flood
 *       should not cost a registry scan per message.</li>
 *   <li><b>C-4</b>, last, because it is the one that can still let the ask
 *       through.</li>
 * </ol>
 *
 * <h2>C-4, the two branches (ADR-018) ⚑</h2>
 *
 * <p><b>Same course: locked.</b> She is sitting an exam of this very course, so
 * this bot is unavailable until she hands it in. Refused from
 * {@link AttemptTracker}'s view of her live attempts, so there is no field on the
 * request that can affect it.
 *
 * <p><b>Another course: allowed, and reported.</b> The specification does not
 * forbid it, so neither do we. The first ask answers with the integrity notice and
 * a {@code CONFLICT}; the client shows it as a calm confirmation, and the resent
 * request with {@code integrityAcknowledged} goes through — and tells the teacher
 * running her exam, exactly once per attempt, through
 * {@link AttemptTracker#reportCrossCourseBotUse}. Possible cheating is surfaced to
 * the person who can judge it instead of being silently permitted or
 * over-blocked.
 *
 * <h2>Two transactions, and the provider call between them</h2>
 *
 * <p>An ask is: read what the prompt needs (one transaction), call the provider
 * (no transaction), write the exchange (one transaction). The provider call takes
 * up to twenty seconds, and a database transaction held open across it would pin a
 * pool connection per waiting student — twenty of them would be the whole pool,
 * and the symptom would be the <em>rest</em> of the system stalling whenever the
 * bot was slow. The read is a snapshot and nothing in it needs to be atomic with
 * the write; the write itself is atomic, which is the part F12.9 actually requires.
 */
public final class BotService {

    private static final Logger log = LoggerFactory.getLogger(BotService.class);

    /**
     * How many earlier turns of a conversation go to the provider.
     *
     * <p>Six is three exchanges: enough for "and why is that?" to make sense,
     * little enough that a long study session does not spend its whole context
     * budget re-reading itself instead of the course material.
     */
    public static final int HISTORY_TURNS = 6;

    /** How many bank questions are read as candidate study material per ask. */
    public static final int BANK_CANDIDATES = 200;

    private final BotStore store;
    private final ProviderChain chain;
    private final ContextBuilder context;
    private final AttemptTracker attempts;
    private final AskRateLimiter limiter;
    private final Clock clock;

    /**
     * @param store    the transactional data seam
     * @param chain    the provider chain (E16.4)
     * @param context  the prompt context builder (E16.6)
     * @param attempts the take-exam seam, for C-4; {@code AttemptService} in production
     * @param limiter  the per-student rate limit
     * @param clock    the server's clock; the only clock this feature has
     */
    public BotService(BotStore store, ProviderChain chain, ContextBuilder context,
                      AttemptTracker attempts, AskRateLimiter limiter, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.chain = Objects.requireNonNull(chain, "chain");
        this.context = Objects.requireNonNull(context, "context");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Registers the three student verbs; all authenticated, none open. */
    public void registerOn(MessageRouter router) {
        Objects.requireNonNull(router, "router");
        router.register(Verb.BOT_ASK, this::ask);
        router.register(Verb.BOT_SESSIONS_GET, this::sessions);
        router.register(Verb.BOT_SESSION_GET, this::session);
    }

    // ===================== BOT_ASK =======================================

    /**
     * The whole of a student's question (F12.5, C-4 ⚑).
     */
    Message ask(CallerContext caller, Message request) {
        Authorization.requireAuthenticated(caller);
        if (!(request.getPayload() instanceof BotAskRequest asked)) {
            return Message.error(request, ErrorCode.VALIDATION, BotMessages.MALFORMED_REQUEST);
        }
        if (asked.courseCode().isBlank() || asked.question().isBlank()) {
            return Message.error(request, ErrorCode.VALIDATION, BotMessages.QUESTION_EMPTY);
        }
        if (!asked.isWithinLengthLimit()) {
            return Message.error(request, ErrorCode.VALIDATION, BotMessages.QUESTION_TOO_LONG);
        }
        long studentId = caller.userId();

        // --- Transaction one: everything the prompt needs, in one snapshot. ---
        AskContext prompt;
        try {
            prompt = store.inTx(data -> gather(data, studentId, asked));
        } catch (RuntimeException e) {
            log.error("Could not read the bot context for student {}", studentId, e);
            return Message.error(request, ErrorCode.INTERNAL, MessageRouter.GENERIC_INTERNAL_MESSAGE);
        }
        if (prompt.refusal() != null) {
            return Message.error(request, prompt.refusalCode(), prompt.refusal());
        }
        if (!limiter.tryAcquire(studentId)) {
            log.info("Rate limited bot ask from student {}", studentId);
            return Message.error(request, ErrorCode.VALIDATION, BotMessages.TOO_FAST);
        }

        // --- C-4, from the live attempt registry rather than from the payload. ---
        Optional<ActiveAttempt> sameCourse =
                attempts.activeAttemptFor(studentId, asked.courseCode());
        if (sameCourse.isPresent()) {
            log.info("C-4 lockout: student {} asked the {} bot while sitting attempt {}",
                    studentId, asked.courseCode(), sameCourse.get().attemptId());
            return Message.error(request, ErrorCode.CONFLICT,
                    BotMessages.lockedOut(prompt.courseName(), sameCourse.get().examName()));
        }
        boolean midOtherExam = !attempts.activeAttemptsFor(studentId).isEmpty();
        if (midOtherExam && !asked.integrityAcknowledged()) {
            // Not a refusal: a question. It travels as a successful response with
            // its own payload type rather than as a third kind of CONFLICT, so the
            // client can tell "confirm this" apart from "you cannot do this"
            // without matching on a sentence (ADR-018).
            return Message.ok(request, new BotIntegrityNotice(
                    prompt.courseName(), BotMessages.integrityNotice(prompt.courseName())));
        }
        if (midOtherExam) {
            // She saw the notice and chose to continue. Telling the teacher is the
            // other half of the bargain, and the tracker makes it once per attempt.
            boolean raised = attempts.reportCrossCourseBotUse(
                    studentId, asked.courseCode(), prompt.courseName());
            log.info("C-4 cross-course use by student {} on the {} bot (alert raised: {})",
                    studentId, asked.courseCode(), raised);
        }

        // --- No transaction is open across this call. See the class javadoc. ---
        Optional<ProviderChain.Reply> reply = chain.ask(
                prompt.systemPrompt(), prompt.blocks(), prompt.history(), asked.question());

        Instant now = clock.instant();
        String answer = reply.map(ProviderChain.Reply::text).orElse(BotAnswer.S32_FALLBACK);
        String provider = reply.map(ProviderChain.Reply::provider).orElse("none");

        // --- Transaction two: the F12.9 dual write, atomically. ---
        long sessionId;
        try {
            sessionId = store.inTx(data -> data.appendExchange(
                    prompt.sessionId(), prompt.botId(), studentId,
                    asked.question(), answer, provider, now));
        } catch (RuntimeException e) {
            // The answer exists and she is waiting for it. Losing the transcript is
            // worse than showing her the answer we already paid for, so this is
            // logged loudly and the answer still goes out - with session id 0, which
            // the client treats as "not resumable" rather than as an error.
            log.error("Could not persist the bot exchange for student {}", studentId, e);
            return Message.ok(request, new BotAnswer(0L, asked.question(), answer, now));
        }
        return Message.ok(request, new BotAnswer(sessionId, asked.question(), answer, now));
    }

    /**
     * The read half of an ask: guards that need the database, plus the prompt.
     *
     * <p>Everything here happens in one transaction, so the enrolment check, the
     * active flag and the material the answer is built from are all one consistent
     * view. A teacher switching the bot off between the check and the read cannot
     * produce an answer from a bot that was off.
     */
    private AskContext gather(BotData data, long studentId, BotAskRequest asked) {
        if (!data.isEnrolled(studentId, asked.courseCode())) {
            return AskContext.refused(ErrorCode.FORBIDDEN, BotMessages.NOT_ENROLLED);
        }
        Optional<BotData.BotRecord> found = data.botForCourse(asked.courseCode());
        if (found.isEmpty()) {
            return AskContext.refused(ErrorCode.NOT_FOUND, BotMessages.NO_BOT);
        }
        BotData.BotRecord bot = found.get();
        if (!bot.active()) {
            return AskContext.refused(ErrorCode.CONFLICT, BotMessages.BOT_INACTIVE);
        }

        Long sessionId = null;
        List<ChatTurn> history = List.of();
        if (asked.continuesSession()) {
            Optional<BotData.StoredSession> conversation =
                    data.ownSession(asked.sessionId(), studentId);
            if (conversation.isEmpty() || conversation.get().botId() != bot.botId()) {
                // Somebody else's session, a session from another course, or one that
                // never existed. All the same answer, so none of them is a probe.
                return AskContext.refused(ErrorCode.NOT_FOUND, BotMessages.SESSION_NOT_FOUND);
            }
            sessionId = conversation.get().sessionId();
            history = recentTurns(conversation.get().turns());
        }

        List<BotSourceText> sources = data.sourceTexts(bot.botId());
        List<BotBankQuestion> bank = data.bankQuestions(bot.courseCode(), BANK_CANDIDATES);
        List<String> blocks = context.build(asked.question(), sources, bank);

        return new AskContext(bot.botId(), bot.courseName(), sessionId,
                Guardrails.systemPrompt(bot.courseName()), blocks, history, null, null);
    }

    /** @return the last {@link #HISTORY_TURNS} turns, oldest first. */
    private static List<ChatTurn> recentTurns(List<BotTurn> turns) {
        int from = Math.max(0, turns.size() - HISTORY_TURNS);
        List<ChatTurn> recent = new ArrayList<>();
        for (BotTurn turn : turns.subList(from, turns.size())) {
            recent.add(new ChatTurn(turn.isFromStudent(), turn.text()));
        }
        return List.copyOf(recent);
    }

    // ===================== BOT_SESSIONS_GET ==============================

    /**
     * The caller's own conversations with one course's bot (F12.10).
     */
    Message sessions(CallerContext caller, Message request) {
        Authorization.requireAuthenticated(caller);
        if (!(request.getPayload() instanceof BotCourseRequest ask) || !ask.isWellFormed()) {
            return Message.error(request, ErrorCode.VALIDATION, BotMessages.MALFORMED_REQUEST);
        }
        long studentId = caller.userId();
        return store.inTx(data -> {
            if (!data.isEnrolled(studentId, ask.courseCode())) {
                return Message.error(request, ErrorCode.FORBIDDEN, BotMessages.NOT_ENROLLED);
            }
            Optional<BotData.BotRecord> bot = data.botForCourse(ask.courseCode());
            if (bot.isEmpty()) {
                // No bot means no history, which is an empty list rather than an
                // error: the screen draws its empty state and offers nothing else.
                return Message.ok(request, BotSessionsPage.empty(ask.courseCode(),
                        data.courseName(ask.courseCode()).orElse(ask.courseCode())));
            }
            List<BotSessionRow> rows = data.ownSessions(bot.get().botId(), studentId).stream()
                    .map(BotService::toRow)
                    .toList();
            return Message.ok(request, new BotSessionsPage(
                    bot.get().courseCode(), bot.get().courseName(), rows));
        });
    }

    // ===================== BOT_SESSION_GET ===============================

    /**
     * One of the caller's own conversations, reopened (F12.10, S-33).
     */
    Message session(CallerContext caller, Message request) {
        Authorization.requireAuthenticated(caller);
        if (!(request.getPayload() instanceof BotSessionRequest ask) || !ask.isWellFormed()) {
            return Message.error(request, ErrorCode.VALIDATION, BotMessages.MALFORMED_REQUEST);
        }
        long studentId = caller.userId();
        return store.inTx(data -> data.ownSession(ask.sessionId(), studentId)
                .map(stored -> Message.ok(request, new BotConversation(
                        stored.sessionId(), stored.courseCode(),
                        data.courseName(stored.courseCode()).orElse(stored.courseCode()),
                        stored.startedAt(), stored.updatedAt(), stored.turns())))
                .orElseGet(() -> Message.error(request, ErrorCode.NOT_FOUND,
                        BotMessages.SESSION_NOT_FOUND)));
    }

    /** @return the list row for one stored conversation. */
    private static BotSessionRow toRow(BotData.StoredSession stored) {
        String preview = stored.turns().stream()
                .filter(BotTurn::isFromStudent)
                .map(BotTurn::text)
                .findFirst()
                .orElse("");
        int questions = (int) stored.turns().stream().filter(BotTurn::isFromStudent).count();
        return new BotSessionRow(stored.sessionId(), stored.startedAt(), stored.updatedAt(),
                questions, preview);
    }

    /**
     * Everything an ask needs, or the reason it cannot happen.
     *
     * <p>One record with a refusal field rather than an exception, for the same
     * reason {@code AttemptService} does it: a refusal here is an ordinary,
     * expected outcome with its own sentence, and expected outcomes that travel as
     * exceptions end up caught by something generic.
     */
    private record AskContext(long botId,
                              String courseName,
                              Long sessionId,
                              String systemPrompt,
                              List<String> blocks,
                              List<ChatTurn> history,
                              ErrorCode refusalCode,
                              String refusal) {

        static AskContext refused(ErrorCode code, String message) {
            return new AskContext(0L, "", null, "", List.of(), List.of(), code, message);
        }
    }
}
