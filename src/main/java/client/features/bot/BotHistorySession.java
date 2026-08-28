package client.features.bot;

import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import common.dto.bot.BotCourseRequest;
import common.dto.bot.BotSessionRow;
import common.dto.bot.BotSessionsPage;
import common.protocol.Message;
import common.protocol.Verb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * The Bot History screen's conversation with the server (Presentation tier,
 * E16.14 — F12.10).
 *
 * <p>The smallest of the four sessions, because the screen is: fetch the
 * student's own conversations for one course, render them, and hand a session id
 * to the chat when she reopens one. Reopening itself belongs to
 * {@link BotChatSession#reopen}, which is where the transcript is rendered — this
 * screen never needs a whole conversation, only the list.
 *
 * <p>"Her own" is not enforced here and could not be: the server scopes the read
 * to the caller in the query itself (P-5). This class simply has no way to ask for
 * anybody else's, because the request carries no user id.
 */
public final class BotHistorySession {

    private static final Logger log = LoggerFactory.getLogger(BotHistorySession.class);

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;
    private final String courseCode;
    private final List<Runnable> listeners = new ArrayList<>();

    private BotSessionsPage page;
    private String status = "";
    private boolean busy;
    private boolean loaded;

    /**
     * @param dispatcher the shared request correlator
     * @param poster     the FX-thread seam (M-4, 2026-08-28). The answer arrives on OCSF's
     *                   read thread and settling it redraws the list of conversations, so it
     *                   is applied through the poster rather than on the socket
     * @param courseCode the course whose history to show
     */
    public BotHistorySession(RequestDispatcher dispatcher, FxThreadPoster poster,
                             String courseCode) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
        this.courseCode = Objects.requireNonNull(courseCode, "courseCode");
        this.page = BotSessionsPage.empty(courseCode, courseCode);
    }

    public String courseCode() {
        return courseCode;
    }

    /** @return the last page the server sent; empty until the first fetch lands. */
    public BotSessionsPage page() {
        return page;
    }

    /** @return her conversations, most recently used first. */
    public List<BotSessionRow> rows() {
        return page.sessions();
    }

    /** @return the last status line, or empty. */
    public String status() {
        return status;
    }

    /** @return {@code true} while a fetch is in flight. */
    public boolean isBusy() {
        return busy;
    }

    /** @return {@code true} once a page has arrived, so the view can stop drawing skeletons. */
    public boolean isLoaded() {
        return loaded;
    }

    /** Fetches her conversations for this course (F12.10). */
    public CompletableFuture<Void> refresh() {
        busy = true;
        status = "";
        changed();
        CompletableFuture<Void> applied = new CompletableFuture<>();
        dispatcher.send(Verb.BOT_SESSIONS_GET, new BotCourseRequest(courseCode))
                .whenComplete((response, failure) -> poster.run(() -> {
                    apply(response, failure);
                    applied.complete(null);
                }));
        return applied;
    }

    /** Subscribes a renderer; called on every change. */
    public void onChange(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private void apply(Message response, Throwable failure) {
        busy = false;
        if (failure != null) {
            log.warn("BOT_SESSIONS_GET failed: {}", failure.toString());
            status = BotCopy.HISTORY_FAILED;
            changed();
            return;
        }
        if (response.isError()) {
            String message = response.errorMessage();
            status = message == null || message.isBlank() ? BotCopy.HISTORY_FAILED : message;
            changed();
            return;
        }
        if (!(response.getPayload() instanceof BotSessionsPage fresh)) {
            log.warn("BOT_SESSIONS_GET answered with an unexpected payload");
            status = BotCopy.HISTORY_FAILED;
            changed();
            return;
        }
        page = fresh;
        loaded = true;
        status = "";
        changed();
    }

    private void changed() {
        for (Runnable listener : List.copyOf(listeners)) {
            listener.run();
        }
    }
}
