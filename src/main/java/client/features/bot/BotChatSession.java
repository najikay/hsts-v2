package client.features.bot;

import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import common.dto.bot.BotAnswer;
import common.dto.bot.BotAskRequest;
import common.dto.bot.BotConversation;
import common.dto.bot.BotIntegrityNotice;
import common.dto.bot.BotSessionRequest;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * The chat screen's conversation with the server (Presentation tier, E16.13).
 *
 * <p>Everything that is not a node. It speaks only to {@link RequestDispatcher}
 * and a {@link BotChatModel}, so the whole of it is unit-tested against
 * {@code FakeClientConnection} without a JavaFX toolkit — the pattern every screen
 * in this project follows (ARCHITECTURE §6).
 *
 * <h2>Three answers to one verb</h2>
 *
 * <p>{@code BOT_ASK} can come back three ways, and telling them apart is this
 * class's main job:
 *
 * <ul>
 *   <li>a {@link BotAnswer} — the ordinary case, including the S-32 sentence,
 *       which is an answer and is shown as one;</li>
 *   <li>a {@link BotIntegrityNotice} — a successful response that is a question
 *       rather than an answer (C-4, ADR-018). It arrives as its own type precisely
 *       so this code does not have to recognise a sentence;</li>
 *   <li>an error — {@code FORBIDDEN}, {@code NOT_FOUND} and {@code CONFLICT} mean
 *       the bot is unusable and carry a sentence that says why; anything else is
 *       worth trying again.</li>
 * </ul>
 *
 * <h2>Futures that do not fail</h2>
 *
 * <p>Every future returned here completes normally. A screen that had to catch
 * exceptions from its own session class would eventually not, and the failure
 * would surface as a chat that silently stopped responding. Failures land in the
 * model as a state, which is the thing the view already renders.
 */
public final class BotChatSession {

    private static final Logger log = LoggerFactory.getLogger(BotChatSession.class);

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;
    private final BotChatModel model;
    private final Clock clock;

    /**
     * The past conversation {@link #reopen} is currently asking for ⚑.
     *
     * <p>The generation-guard sweep. {@code BotChatView.onShow} calls {@code reopen} whenever it
     * is navigated to with a session parameter, and the view holds one session per course, so two
     * deep links to two conversations can overlap.
     */
    private long requestedSessionId;

    /**
     * @param dispatcher the shared request correlator
     * @param poster     the FX-thread seam (M-4, 2026-08-28). Responses arrive on OCSF's
     *                   read thread and every settle here ends in a bubble being drawn, so
     *                   answers are applied through the poster rather than on the socket
     * @param model      the state this session drives
     * @param clock      the client's clock; only ever used for optimistic bubble
     *                   timestamps, since every stored time comes from the server
     */
    public BotChatSession(RequestDispatcher dispatcher, FxThreadPoster poster,
                          BotChatModel model, Clock clock) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
        this.model = Objects.requireNonNull(model, "model");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @return the state every view of this chat reads. */
    public BotChatModel model() {
        return model;
    }

    /**
     * Sends a question (F12.5).
     *
     * <p>⚑ <b>B-20.</b> This used to pass {@code false} unconditionally, which is why the
     * C-4 notice came back on every message rather than once per attempt: the server returns
     * it whenever the request says she has not acknowledged, and nothing on either side
     * remembered that she had. It now asks the model, which records the consent when she
     * gives it and discards it whenever the situation it belonged to ends.
     *
     * @param question what she typed; blank is ignored rather than sent
     * @return a future that completes when the model has been updated
     */
    public CompletableFuture<Void> ask(String question) {
        return ask(question, model.hasAcknowledged());
    }

    /**
     * Re-sends the question she confirmed the C-4 notice for (ADR-018).
     *
     * @return a future that completes when the model has been updated
     */
    public CompletableFuture<Void> acknowledgeAndAsk() {
        String held = model.acknowledged();
        if (held.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        return ask(held, true);
    }

    /**
     * She declined the C-4 notice.
     *
     * @return the question, so the view can put it back in the box unsent
     */
    public String decline() {
        return model.declined();
    }

    private CompletableFuture<Void> ask(String question, boolean acknowledged) {
        String text = question == null ? "" : question.trim();
        if (text.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        model.asking(text, clock.instant());
        long sessionId = model.sessionId();
        BotAskRequest payload = new BotAskRequest(model.courseCode(),
                sessionId > 0 ? sessionId : null, text, acknowledged);
        CompletableFuture<Void> applied = new CompletableFuture<>();
        dispatcher.send(Verb.BOT_ASK, payload)
                .whenComplete((response, failure) -> poster.run(() -> {
                    applyAsk(response, failure);
                    applied.complete(null);
                }));
        return applied;
    }

    private void applyAsk(Message response, Throwable failure) {
        if (failure != null) {
            log.warn("BOT_ASK failed: {}", failure.toString());
            model.failed(BotCopy.ASK_FAILED);
            return;
        }
        if (response.isError()) {
            ErrorCode code = response.getErrorCode();
            String message = response.errorMessage();
            if (code == ErrorCode.FORBIDDEN || code == ErrorCode.NOT_FOUND) {
                // The bot is unusable for a reason that will not change while she sits
                // here: not enrolled, no bot, switched off. Blocked disables the input.
                model.blocked(message);
            } else if (code == ErrorCode.CONFLICT) {
                // C-4's lockout - and unlike the two above, it is transient: it ends
                // when her attempt does, which can happen while this screen is open
                // (her submit, the bell, a teacher closing the execution early). It
                // used to land in blocked() with the terminal pair, and the lock then
                // outlived its cause: the input stayed disabled after the exam closed,
                // with navigation the only way out. Found by the lead's manual round,
                // 2026-08-28 (B-47). The model keeps the composer editable and drops
                // any cross-course consent; a retry simply asks the server again, and
                // the server is the decider for as long as the attempt is live.
                model.lockedOut(message == null || message.isBlank() ? BotCopy.ASK_FAILED : message);
            } else {
                model.failed(message == null || message.isBlank() ? BotCopy.ASK_FAILED : message);
            }
            return;
        }
        if (response.getPayload() instanceof BotIntegrityNotice notice) {
            // The whole notice, not just its sentence: the model keys her consent on it
            // (B-20), and a bare String had nothing to key on.
            model.needsAcknowledgement(notice);
            return;
        }
        if (!(response.getPayload() instanceof BotAnswer answer)) {
            log.warn("BOT_ASK answered with an unexpected payload");
            model.failed(BotCopy.ASK_FAILED);
            return;
        }
        model.answered(answer);
    }

    /**
     * Reopens one of her own past conversations (F12.10).
     *
     * @param sessionId the conversation
     * @return a future that completes when the model has been updated, or when the
     *         attempt failed and the model was left alone
     */
    public CompletableFuture<Void> reopen(long sessionId) {
        requestedSessionId = sessionId;
        CompletableFuture<Void> applied = new CompletableFuture<>();
        dispatcher.send(Verb.BOT_SESSION_GET, new BotSessionRequest(sessionId))
                .whenComplete((response, failure) -> poster.run(() -> {
                    applyReopen(sessionId, response, failure);
                    applied.complete(null);
                }));
        return applied;
    }

    private void applyReopen(long sessionId, Message response, Throwable failure) {
        if (sessionId != requestedSessionId) {
            // She reopened another conversation while this was in flight. Adopting it
            // would load one conversation's turns into a screen headed by another.
            // BotChatView calls this from onShow, which runs on every navigation.
            return;
        }
        if (failure != null) {
            log.warn("BOT_SESSION_GET failed: {}", failure.toString());
            model.failed(BotCopy.HISTORY_FAILED);
            return;
        }
        if (response.isError()) {
            model.failed(response.errorMessage());
            return;
        }
        if (response.getPayload() instanceof BotConversation conversation) {
            model.load(conversation);
        } else {
            log.warn("BOT_SESSION_GET answered with an unexpected payload");
            model.failed(BotCopy.HISTORY_FAILED);
        }
    }
}
