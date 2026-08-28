package client.features.bot;

import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import common.dto.bot.BotActivityPoint;
import common.dto.bot.BotAnalytics;
import common.dto.bot.BotCourseRequest;
import common.dto.bot.BotTopQuestion;
import common.protocol.Message;
import common.protocol.Verb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The Bot Analytics screen's conversation with the server (Presentation tier,
 * E16.15 — F12.11, S-34 ⚑).
 *
 * <p>It fetches one {@link BotAnalytics} and holds it. There is deliberately
 * nothing else here: no drill-down, no per-student view, no "who asked this"
 * affordance — because the DTO has no field that could answer one, and a session
 * class that tried to build such a thing would have nowhere to get it from.
 *
 * <p>That is worth stating rather than leaving implicit. S-34's anonymity is
 * enforced three times over: the SQL never selects an identifying column, the DTO
 * has nowhere to put one, and this screen never asks. The first two are proven by
 * tests; this one is proven by the size of this file.
 */
public final class BotAnalyticsSession {

    private static final Logger log = LoggerFactory.getLogger(BotAnalyticsSession.class);

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;
    private final String courseCode;
    private final List<Runnable> listeners = new ArrayList<>();

    private BotAnalytics analytics;
    private String status = "";
    private boolean busy;
    private boolean loaded;

    /**
     * @param dispatcher the shared request correlator
     * @param poster     the FX-thread seam (M-4, 2026-08-28). The aggregate arrives on OCSF's
     *                   read thread and settling it redraws the chart and the table, so it is
     *                   applied through the poster rather than on the socket
     * @param courseCode the taught course whose bot to report on
     */
    public BotAnalyticsSession(RequestDispatcher dispatcher, FxThreadPoster poster,
                               String courseCode) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
        this.courseCode = Objects.requireNonNull(courseCode, "courseCode");
        this.analytics = BotAnalytics.empty(courseCode);
    }

    public String courseCode() {
        return courseCode;
    }

    /** @return the last aggregate the server sent; an empty one until the first fetch. */
    public BotAnalytics analytics() {
        return analytics;
    }

    /** @return the questions-per-day series, oldest first. */
    public List<BotActivityPoint> activity() {
        return analytics.activity();
    }

    /** @return the most frequently asked questions, most frequent first. */
    public List<BotTopQuestion> frequent() {
        return analytics.frequent();
    }

    /** @return the busiest day, or empty when nothing has been asked. */
    public Optional<BotActivityPoint> busiestDay() {
        return analytics.activity().stream()
                .max(java.util.Comparator.comparingInt(BotActivityPoint::count));
    }

    /** @return the last status line, or empty. */
    public String status() {
        return status;
    }

    /** @return {@code true} while a fetch is in flight. */
    public boolean isBusy() {
        return busy;
    }

    /** @return {@code true} once an aggregate has arrived. */
    public boolean isLoaded() {
        return loaded;
    }

    /** Fetches the aggregate (F12.11). */
    public CompletableFuture<Void> refresh() {
        busy = true;
        status = "";
        changed();
        CompletableFuture<Void> applied = new CompletableFuture<>();
        dispatcher.send(Verb.BOT_ANALYTICS_GET, new BotCourseRequest(courseCode))
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
            log.warn("BOT_ANALYTICS_GET failed: {}", failure.toString());
            status = BotCopy.ANALYTICS_FAILED;
            changed();
            return;
        }
        if (response.isError()) {
            String message = response.errorMessage();
            status = message == null || message.isBlank() ? BotCopy.ANALYTICS_FAILED : message;
            changed();
            return;
        }
        if (!(response.getPayload() instanceof BotAnalytics fresh)) {
            log.warn("BOT_ANALYTICS_GET answered with an unexpected payload");
            status = BotCopy.ANALYTICS_FAILED;
            changed();
            return;
        }
        analytics = fresh;
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
