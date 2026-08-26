package server.features.notify;

import common.dto.notify.NavRef;
import common.dto.notify.NotificationDto;
import common.dto.notify.NotificationType;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.db.Transactions;
import server.db.entities.Notification;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The durable {@link NotificationStore}, over the {@code notifications} table (Logic tier,
 * E17.1 — E2 integration).
 *
 * <p>What {@code NotificationStore}'s javadoc promised as "about thirty lines landing as soon
 * as E2 PR 2a merges": each method is one {@link Transactions#inTx} block over the
 * {@link Notification} entity, and nothing above it changed. {@link NotificationService} was
 * written against the interface, so swapping {@link InMemoryNotificationStore} for this class
 * is one line in {@code HSTSServer.defaultRouter} — which is what the seam was for.
 *
 * <h2>Ownership lives in the queries, not above them</h2>
 *
 * <p>Every statement here names {@code userId}, including the two writes. {@code markRead} is
 * a single {@code UPDATE … WHERE id = :id AND userId = :userId AND readAt IS NULL}, so an id
 * belonging to somebody else updates zero rows and answers {@code false} — the same answer an
 * unknown id gets, and the same answer an already-read row gets. A load-then-check would have
 * been three lines longer and one race wider, and it would put the rule somewhere a future
 * caller could forget to apply (E17.6).
 *
 * <p>The same statement is why marking twice is a no-op rather than an error: the
 * {@code readAt IS NULL} clause is the idempotence, so a double-click or two open windows
 * cost one UPDATE that matches nothing.
 *
 * <h2>Column mapping</h2>
 *
 * <p>The DTO field set lines up one to one with the columns, with two names that differ:
 * {@link NavRef#route()} is stored in {@code ref_type} and {@link NavRef#entityId()} in
 * {@code ref_id}. Both are nullable, which is exactly {@link NavRef#none()} — a notification
 * with nowhere to go. The type is stored as its {@link Enum#name()}, so constants may be added
 * freely and must never be renamed (see {@link NotificationType}).
 *
 * <p>Thread safety: it holds no mutable state, and every call opens its own short transaction,
 * so it is safe on as many OCSF read threads as the server has.
 */
public final class JpaNotificationStore implements NotificationStore {

    private static final Logger log = LoggerFactory.getLogger(JpaNotificationStore.class);

    private final SessionFactory factory;

    /**
     * @param factory the session factory this store reads and writes through
     */
    public JpaNotificationStore(SessionFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public long save(long userId, NotificationType type, String title, String body,
                     NavRef ref, Instant createdAt) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(createdAt, "createdAt");
        NavRef target = ref == null ? NavRef.none() : ref;

        return Transactions.inTx(factory, session -> {
            Notification row = new Notification(userId, type.name(), title, body,
                    target.route(), target.entityId(), createdAt);
            session.persist(row);
            // Flushed inside the transaction because the caller needs the generated id
            // now: it is what the per-recipient push carries, and what that client will
            // send back to mark the row read.
            session.flush();
            return row.getId();
        });
    }

    @Override
    public int unreadCount(long userId) {
        Long count = Transactions.inTx(factory, session -> session.createQuery("""
                        select count(n) from Notification n
                        where n.userId = :userId and n.readAt is null
                        """, Long.class)
                .setParameter("userId", userId)
                .getSingleResult());
        // COUNT is a long in SQL and an int on the wire. A user with more than two billion
        // unread notifications has a different problem than a wrong badge.
        return (int) Math.min(count, Integer.MAX_VALUE);
    }

    @Override
    public List<NotificationDto> listRecent(long userId, int limit) {
        if (limit <= 0) {
            // Callers clamp, but a zero here must not turn into "no limit" at the driver.
            return List.of();
        }
        return Transactions.inTx(factory, session -> session.createQuery("""
                        from Notification
                        where userId = :userId
                        order by createdAt desc, id desc
                        """, Notification.class)
                .setParameter("userId", userId)
                .setMaxResults(limit)
                .getResultList()
                .stream()
                .map(JpaNotificationStore::toDtoOrSkip)
                .filter(Objects::nonNull)
                .toList());
    }

    @Override
    public boolean markRead(long userId, long notificationId, Instant at) {
        Objects.requireNonNull(at, "at");
        return Transactions.inTx(factory, session -> markOne(session, userId, notificationId, at)) == 1;
    }

    @Override
    public int markAllRead(long userId, Instant at) {
        Objects.requireNonNull(at, "at");
        return Transactions.inTx(factory, session -> session.createMutationQuery("""
                        update Notification
                        set readAt = :at
                        where userId = :userId and readAt is null
                        """)
                .setParameter("at", at)
                .setParameter("userId", userId)
                .executeUpdate());
    }

    private static int markOne(Session session, long userId, long notificationId, Instant at) {
        return session.createMutationQuery("""
                        update Notification
                        set readAt = :at
                        where id = :id and userId = :userId and readAt is null
                        """)
                .setParameter("at", at)
                .setParameter("id", notificationId)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    /**
     * Entity to wire shape, or {@code null} when the stored type is not one this build knows ⚑.
     *
     * <p><b>One bad row must never take a page down.</b> This method used to be a bare
     * {@code NotificationType.valueOf(row.getType())}, and an {@code IllegalArgumentException}
     * from inside a {@code map} aborts the whole stream: the caller got no page at all, the
     * handler turned it into {@code INTERNAL}, and the user's bell simply did not open. That is
     * not hypothetical — it is B-11, found by driving {@code NOTIFICATIONS_GET} against a freshly
     * seeded database, where two malformed seeded rows made a perfectly well-formed
     * {@code APPROVAL_REJECTED} row unreadable beside them.
     *
     * <p>It is the same lesson as #49 and #51: a read path assembles rows it did not write, and
     * the blast radius of one unreadable row has to be that row. Skipping degrades the page by
     * exactly what is broken and leaves the rest of the bell working, which is the difference
     * between a user missing one notification and a user having no notifications.
     *
     * <p><b>Skipped loudly, not quietly.</b> The row id and the offending string both go into an
     * ERROR line, because a silently short page is a defect that hides: the count and the list
     * disagree and nobody can tell why. ERROR rather than WARN because an unknown type means
     * either a constant was renamed - which {@link NotificationType} forbids precisely so this
     * cannot happen - or something wrote the column without going through
     * {@link #save(long, NotificationType, String, String, NavRef, Instant)}. Both are bugs
     * somewhere else, and this line is the only place they surface.
     *
     * <p>The unread badge is deliberately <em>not</em> filtered to match: {@link #unreadCount}
     * counts rows in SQL and never parses a type, so a skipped row still counts. A badge of 3
     * over a list of 2 is a visible sign that something is wrong, which is better than a number
     * quietly adjusted to agree with a page that is missing something.
     *
     * <p>The DTO's own constructor normalises a null body to empty.
     *
     * @param row the stored notification
     * @return the wire shape, or {@code null} when {@code row}'s type does not parse
     */
    private static NotificationDto toDtoOrSkip(Notification row) {
        NotificationType type;
        try {
            type = NotificationType.valueOf(row.getType());
        } catch (IllegalArgumentException | NullPointerException unknown) {
            log.error("Notification {} carries type '{}', which is not a NotificationType constant "
                            + "in this build. Skipping the row so the rest of the page still "
                            + "loads; the badge count still includes it.",
                    row.getId(), row.getType());
            return null;
        }
        return new NotificationDto(
                row.getId(),
                type,
                row.getTitle(),
                row.getBody(),
                new NavRef(row.getRefType(), row.getRefId()),
                row.getCreatedAt(),
                row.getReadAt());
    }
}
