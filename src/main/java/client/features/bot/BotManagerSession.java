package client.features.bot;

import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import common.dto.bot.BotActiveRequest;
import common.dto.bot.BotCourseRequest;
import common.dto.bot.BotCreateRequest;
import common.dto.bot.BotManagerPage;
import common.dto.bot.BotSourceKind;
import common.dto.bot.BotSourceRow;
import common.dto.bot.SourceAddRequest;
import common.dto.bot.SourceRemoveRequest;
import common.dto.bot.SourceUpdateRequest;
import common.protocol.Message;
import common.protocol.Verb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The Bot Manager's conversation with the server (Presentation tier, E16.12 —
 * F12.1/F12.3/F12.4).
 *
 * <p>FX-free, so every rule the manager screen obeys is a plain JUnit assertion.
 * It holds the page it last received and a one-line status, and the view renders
 * from those two.
 *
 * <h2>Every verb answers with the whole page</h2>
 *
 * <p>Create, toggle, add and remove all come back as a fresh
 * {@link BotManagerPage}, and this class simply replaces what it holds. That is
 * the same choice the notifications feature made and it is worth naming: the
 * alternative is patching a local list from an acknowledgement, which works right
 * up until two co-teachers are editing at once and one of them ends up looking at
 * a table that no longer matches the database. Here there is no window in which
 * the screen and the server disagree, and no refresh button anywhere (NFR-18).
 *
 * <h2>Failures are a status line, not an exception</h2>
 *
 * <p>Every future completes normally. A failed call leaves the page it had, sets
 * {@link #status()}, and says so — a manager screen that threw would take the
 * teacher's whole session down over a dropped packet.
 */
public final class BotManagerSession {

    private static final Logger log = LoggerFactory.getLogger(BotManagerSession.class);

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;
    private final String courseCode;
    private final List<Runnable> listeners = new ArrayList<>();

    private BotManagerPage page = BotManagerPage.none();
    private String status = "";
    private boolean busy;
    private boolean loaded;

    /**
     * @param dispatcher the shared request correlator
     * @param poster     the FX-thread seam (M-4, 2026-08-28). Responses arrive on OCSF's
     *                   read thread and every settle here redraws the sources table, so
     *                   answers are applied through the poster rather than on the socket
     * @param courseCode the taught course this screen manages
     */
    public BotManagerSession(RequestDispatcher dispatcher, FxThreadPoster poster,
                             String courseCode) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
        this.courseCode = Objects.requireNonNull(courseCode, "courseCode");
    }

    // ===================== Reads =========================================

    public String courseCode() {
        return courseCode;
    }

    /** @return the last page the server sent; never {@code null}. */
    public BotManagerPage page() {
        return page;
    }

    /** @return the sources table's rows. */
    public List<BotSourceRow> sources() {
        return page.sources();
    }

    /** @return {@code true} when this course already has a bot (S-30). */
    public boolean hasBot() {
        return page.exists();
    }

    /** @return the last status line, or empty. */
    public String status() {
        return status;
    }

    /** @return {@code true} while a request is in flight. */
    public boolean isBusy() {
        return busy;
    }

    /** @return {@code true} once a page has arrived, so the view can stop drawing skeletons. */
    public boolean isLoaded() {
        return loaded;
    }

    /**
     * @param sourceId a source
     * @return that row, or empty; the view uses it to name the source in a confirm
     */
    public Optional<BotSourceRow> source(long sourceId) {
        return page.sources().stream().filter(row -> row.sourceId() == sourceId).findFirst();
    }

    // ===================== Requests ======================================

    /** Fetches the page (F12.1/F12.3). */
    public CompletableFuture<Void> refresh() {
        return send(Verb.BOT_MANAGER_GET, new BotCourseRequest(courseCode), BotCopy.MANAGER_FAILED);
    }

    /**
     * Creates the bot, or joins the one a co-teacher already made (S-30).
     *
     * @param name what to call it; blank lets the server name it after the course
     */
    public CompletableFuture<Void> create(String name) {
        return send(Verb.BOT_CREATE, new BotCreateRequest(courseCode, name), BotCopy.MANAGER_FAILED);
    }

    /**
     * Deletes this course's bot and its sources ⚑ (U-39, 2026-08-30, live session).
     *
     * <p>Answers with the same {@link BotManagerPage} every other manager verb answers with,
     * which for a course that now has no bot is the empty one. So there is nothing to patch
     * and nothing to clear: {@link #hasBot()} goes false because the server said so, and the
     * card flips to Create off the same page the detail pane redraws from.
     *
     * <p>The failure sentence is deliberately not overridden, for {@link #addSource}'s reason
     * and one more. The server's {@code CONFLICT} for a bot with student conversations counts
     * them ("This bot has 4 student conversations..."), and the count is the whole point of the
     * refusal: it tells her how many records she was about to take and what to do instead. A
     * generic client sentence would throw away the only part she can act on.
     *
     * @return a future completing when the answer has been applied
     */
    public CompletableFuture<Void> deleteBot() {
        return send(Verb.BOT_DELETE, new BotCourseRequest(courseCode), null);
    }

    /**
     * Switches the bot on or off (F12.4).
     *
     * @param active the state to put it in
     */
    public CompletableFuture<Void> setActive(boolean active) {
        return send(Verb.BOT_ACTIVE_SET, new BotActiveRequest(courseCode, active),
                BotCopy.MANAGER_FAILED);
    }

    /**
     * Adds one piece of material (F12.2).
     *
     * <p>The failure sentence here is deliberately not overridden: a parse failure
     * comes back from the server already written for the uploader ("this PDF has no
     * text in it, it may be a scan"), and replacing it with a generic client
     * message would throw away the only part of the answer she can act on.
     *
     * @param kind    what the bytes are
     * @param title   what to call it
     * @param content the file, or UTF-8 text
     */
    public CompletableFuture<Void> addSource(BotSourceKind kind, String title, byte[] content) {
        return send(Verb.BOT_SOURCE_ADD,
                new SourceAddRequest(courseCode, kind, title, content), null);
    }

    /**
     * Replaces one source's title and content in place ⚑ (F12.3, B-21).
     *
     * <p>The row keeps its id and its author, which is the whole difference between this and
     * removing it and adding it again: a correction reaches the course's other teachers as one
     * change rather than as an unexplained removal followed by an unexplained addition.
     *
     * <p>Refusals carry the server's own sentence, exactly as {@link #addSource} does — most
     * of all the {@code CONFLICT} that names the colleague whose edit lock is in the way.
     *
     * @param sourceId the row to replace
     * @param kind     what the new content is
     * @param title    what to call it now
     * @param content  the new bytes, or UTF-8 text for a free-text source
     * @return a future completing when the answer has been applied
     */
    public CompletableFuture<Void> updateSource(long sourceId, BotSourceKind kind,
                                                String title, byte[] content) {
        return send(Verb.BOT_SOURCE_UPDATE,
                new SourceUpdateRequest(courseCode, sourceId, kind, title, content), null);
    }

    /**
     * Removes one source (F12.3).
     *
     * @param sourceId the row to remove
     */
    public CompletableFuture<Void> removeSource(long sourceId) {
        return send(Verb.BOT_SOURCE_REMOVE, new SourceRemoveRequest(courseCode, sourceId), null);
    }

    // ===================== Change notification ===========================

    /** Subscribes a renderer; called on every change. */
    public void onChange(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private void changed() {
        for (Runnable listener : List.copyOf(listeners)) {
            listener.run();
        }
    }

    // ===================== Internals =====================================

    /**
     * Sends one manager verb and folds the answer in.
     *
     * @param fallbackMessage what to say when the failure has no sentence of its
     *                        own; {@code null} keeps the server's, which is the
     *                        right choice whenever the server writes a better one
     */
    private CompletableFuture<Void> send(Verb verb, Object payload, String fallbackMessage) {
        busy = true;
        status = "";
        changed();
        CompletableFuture<Void> applied = new CompletableFuture<>();
        dispatcher.send(verb, payload)
                .whenComplete((response, failure) -> poster.run(() -> {
                    apply(verb, response, failure, fallbackMessage);
                    applied.complete(null);
                }));
        return applied;
    }

    private void apply(Verb verb, Message response, Throwable failure, String fallbackMessage) {
        busy = false;
        if (failure != null) {
            log.warn("{} failed: {}", verb, failure.toString());
            status = fallbackMessage == null ? BotCopy.MANAGER_FAILED : fallbackMessage;
            changed();
            return;
        }
        if (response.isError()) {
            log.info("{} refused: {} {}", verb, response.getErrorCode(), response.errorMessage());
            String message = response.errorMessage();
            status = message == null || message.isBlank()
                    ? (fallbackMessage == null ? BotCopy.MANAGER_FAILED : fallbackMessage)
                    : message;
            changed();
            return;
        }
        if (!(response.getPayload() instanceof BotManagerPage fresh)) {
            log.warn("{} answered with an unexpected payload", verb);
            status = BotCopy.MANAGER_FAILED;
            changed();
            return;
        }
        page = fresh;
        loaded = true;
        status = "";
        changed();
    }

    /** Convenience for a view that wants the page handed to it rather than pulled. */
    public void onPage(Consumer<BotManagerPage> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        onChange(() -> consumer.accept(page));
    }
}
