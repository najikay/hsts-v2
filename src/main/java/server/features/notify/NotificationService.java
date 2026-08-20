package server.features.notify;

import common.dto.notify.MarkReadRequest;
import common.dto.notify.NavRef;
import common.dto.notify.NotificationDto;
import common.dto.notify.NotificationType;
import common.dto.notify.NotificationsGetRequest;
import common.dto.notify.NotificationsPage;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.core.CallerContext;
import server.core.MessageRouter;
import server.realtime.PushGateway;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Persistent notifications and their live delivery (Logic tier, E17.1/E17.3 —
 * F11.1, F11.2).
 *
 * <p>Four rules, and all four live here:
 *
 * <ol>
 *   <li><b>Persist first, push second.</b> {@link #notify} writes one row per
 *       recipient and only then asks {@link PushGateway} to deliver. A recipient
 *       who is signed out is not skipped: the row is waiting in their bell at
 *       their next login, and their unread count arrives in the
 *       {@code LoginResult} (E17.5). Pushing first and persisting after would
 *       make a crash between the two lose the notification for everyone who was
 *       offline, which is precisely the case the table exists for.</li>
 *   <li><b>One row per recipient.</b> Notifications are read state, and read
 *       state is per person. A shared row with a read-by set would make
 *       "unread count" a join and "mark read" a race; a row each makes both a
 *       primary-key operation.</li>
 *   <li><b>A user only ever touches their own rows.</b> Neither read verb takes
 *       a user id — the recipient is the session on the socket, always. Marking
 *       is scoped in the query itself ({@code (id, user_id)}), not by a check
 *       above it, so pointing {@code NOTIFICATIONS_MARK_READ} at somebody else's
 *       id changes nothing and answers a normal, unchanged page. That is
 *       deliberate: an {@code ERROR} would confirm the id exists.</li>
 *   <li><b>Delivery never fails the caller.</b> A dead socket is logged and
 *       counted, never thrown. The transaction that raised the notification (an
 *       approval, a published grade) must not roll back because a laptop closed
 *       its lid.</li>
 * </ol>
 *
 * <p>Everything is constructor-injected — a {@link NotificationStore} (the E2
 * seam), the {@link PushGateway} and a {@link Clock} — so every rule above is
 * unit-testable without a socket, a database, or waiting for a clock to move.
 *
 * <p><b>Who calls this:</b> features raise notifications through the
 * {@link Notifier} interface this class implements, with sentences from
 * {@link NotificationCatalog}. See {@code Notifier}'s javadoc for the wiring
 * recipe E7/E8/E12/E16 follow.
 */
public class NotificationService implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** Answer to a notifications verb whose payload is not the expected DTO. */
    public static final String MALFORMED_REQUEST =
            "That request could not be read. Please reopen the notifications panel.";

    private final NotificationStore store;
    private final PushGateway pushGateway;
    private final Clock clock;

    /** Production wiring: system clock. */
    public NotificationService(NotificationStore store, PushGateway pushGateway) {
        this(store, pushGateway, Clock.systemUTC());
    }

    /** @param clock time source for {@code created_at} / {@code read_at}; a test clock in tests */
    public NotificationService(NotificationStore store, PushGateway pushGateway, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.pushGateway = Objects.requireNonNull(pushGateway, "pushGateway");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Registers both notification verbs as authenticated (there is no anonymous bell). */
    public void registerOn(MessageRouter router) {
        Objects.requireNonNull(router, "router");
        router.register(Verb.NOTIFICATIONS_GET, this::handleGet);
        router.register(Verb.NOTIFICATIONS_MARK_READ, this::handleMarkRead);
    }

    // ===================== Emitting ======================================

    @Override
    public Outcome notify(Collection<Long> userIds, NotificationType type,
                          String title, String body, NavRef ref) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(title, "title");
        Set<Long> recipients = distinct(userIds);
        if (recipients.isEmpty()) {
            // A rule that fired for nobody (an exam with no co-teachers) is normal.
            log.debug("Notification {} had no recipients", type);
            return Outcome.NONE;
        }

        NavRef target = ref == null ? NavRef.none() : ref;
        Instant now = clock.instant();
        int pushed = 0;
        for (Long userId : recipients) {
            long id = store.save(userId, type, title, body, target, now);
            NotificationDto row = new NotificationDto(id, type, title, body, target, now, null);
            // Per-recipient push: each client gets its own row id, which is what it
            // will send back to mark it read.
            if (pushGateway.toUser(userId, Verb.PUSH_NOTIFICATION, row)) {
                pushed++;
            }
        }
        log.info("Notification {} persisted for {} user(s), delivered live to {}",
                type, recipients.size(), pushed);
        return new Outcome(recipients.size(), pushed);
    }

    // ===================== Reading =======================================

    /**
     * @param userId the recipient, always resolved from the session by the caller
     * @return this user's unread count, for the bell badge and for
     *         {@code LoginResult} (E17.5)
     */
    public int unreadCount(long userId) {
        return store.unreadCount(userId);
    }

    /**
     * @param limit how many of the newest rows to include; clamped to the
     *              {@link NotificationsGetRequest} bounds
     * @return the newest notifications plus the full unread count
     */
    public NotificationsPage page(long userId, int limit) {
        return new NotificationsPage(store.listRecent(userId, clamp(limit)), store.unreadCount(userId));
    }

    /**
     * Marks one of {@code userId}'s notifications read.
     *
     * @return the refreshed page, whether or not anything changed — an id that is
     *         unknown or belongs to somebody else simply changes nothing
     */
    public NotificationsPage markRead(long userId, long notificationId, int limit) {
        boolean changed = store.markRead(userId, notificationId, clock.instant());
        if (!changed) {
            // Not an error, and deliberately not distinguishable from "already read":
            // saying "no such notification" would confirm that some other user has one.
            log.debug("Mark-read for user {} changed nothing (id={})", userId, notificationId);
        }
        return page(userId, limit);
    }

    /**
     * Marks every unread notification of {@code userId} read.
     *
     * @return the refreshed page, whose unread count is now zero
     */
    public NotificationsPage markAllRead(long userId, int limit) {
        int changed = store.markAllRead(userId, clock.instant());
        log.debug("Marked {} notification(s) read for user {}", changed, userId);
        return page(userId, limit);
    }

    // ===================== Handlers ======================================

    /** {@code NOTIFICATIONS_GET} → OK with a {@link NotificationsPage}. */
    Message handleGet(CallerContext caller, Message request) {
        int limit = request.getPayload() instanceof NotificationsGetRequest get
                ? get.limit()
                : NotificationsGetRequest.DEFAULT_LIMIT;
        // A missing or wrong payload is served with the default page rather than
        // refused: the bell must open even for a client that sent nothing useful.
        return Message.ok(request, page(caller.userId(), limit));
    }

    /** {@code NOTIFICATIONS_MARK_READ} → OK with the refreshed {@link NotificationsPage}. */
    Message handleMarkRead(CallerContext caller, Message request) {
        if (!(request.getPayload() instanceof MarkReadRequest mark)) {
            log.warn("NOTIFICATIONS_MARK_READ with a {} payload", describe(request.getPayload()));
            return Message.error(request, ErrorCode.VALIDATION, MALFORMED_REQUEST);
        }
        long userId = caller.userId();
        NotificationsPage page = mark.all()
                ? markAllRead(userId, NotificationsGetRequest.DEFAULT_LIMIT)
                : markRead(userId, mark.notificationId(), NotificationsGetRequest.DEFAULT_LIMIT);
        return Message.ok(request, page);
    }

    // ===================== Helpers =======================================

    /** Collapses duplicates and drops nulls, preserving the caller's order. */
    private static Set<Long> distinct(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> recipients = new LinkedHashSet<>(userIds);
        recipients.remove(null);
        return recipients;
    }

    private static int clamp(int limit) {
        return new NotificationsGetRequest(limit).limit();
    }

    private static String describe(Object payload) {
        return payload == null ? "null" : payload.getClass().getName();
    }
}
