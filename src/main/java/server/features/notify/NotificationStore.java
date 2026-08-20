package server.features.notify;

import common.dto.notify.NavRef;
import common.dto.notify.NotificationDto;
import common.dto.notify.NotificationType;

import java.time.Instant;
import java.util.List;

/**
 * Where notifications are kept (Logic tier, E17.1).
 *
 * <p><b>This interface is the seam for E2's persistence layer</b>, exactly as
 * {@code UserDirectory} is for authentication. {@link NotificationService}
 * expresses every rule it enforces against these five methods and knows nothing
 * about how they are answered.
 *
 * <ul>
 *   <li><b>today:</b> {@link InMemoryNotificationStore} — a thread-safe map, used
 *       by the running server and by every test in this feature.</li>
 *   <li><b>next:</b> a {@code JpaNotificationStore} of about thirty lines, landing
 *       as soon as E2 PR 2a merges: each method becomes one
 *       {@code Transactions.inTx(...)} block over the {@code Notification} entity
 *       and the {@code notifications} table (V7). No line of
 *       {@link NotificationService}, and no test of it, changes — which is the
 *       point of writing the seam first.</li>
 * </ul>
 *
 * <p>The signatures are deliberately scalar and DTO-shaped: no entity, no
 * {@code Session}, no {@code Optional<Notification>} leaks through, so the JPA
 * implementation is a drop-in rather than a refactor. The field set lines up one
 * to one with the entity's columns — {@code user_id}, {@code type},
 * {@code title}, {@code body}, {@code ref_type} ({@link NavRef#route()}),
 * {@code ref_id} ({@link NavRef#entityId()}), {@code created_at},
 * {@code read_at}.
 *
 * <p><b>Contract for every implementation:</b>
 * <ol>
 *   <li>Safe to call from many OCSF read threads at once.</li>
 *   <li>Every read and every write is scoped by {@code userId}. A store that
 *       answered by id alone would make the service's ownership rule
 *       unenforceable, so ownership lives in the query, not in a check above
 *       it.</li>
 *   <li>{@link #listRecent} returns newest first.</li>
 *   <li>Marking an already-read row read again is a no-op, not an error
 *       (double-click, two open windows).</li>
 * </ol>
 */
public interface NotificationStore {

    /**
     * Persists one notification for one recipient.
     *
     * @return the new row's id
     */
    long save(long userId, NotificationType type, String title, String body, NavRef ref, Instant createdAt);

    /** @return how many of this user's notifications are unread (the whole table, not a page). */
    int unreadCount(long userId);

    /**
     * @param limit maximum rows to return; callers pass an already-clamped value
     * @return this user's newest notifications, newest first, at most {@code limit} of them
     */
    List<NotificationDto> listRecent(long userId, int limit);

    /**
     * Marks one row read, but only if it belongs to {@code userId}.
     *
     * @return {@code true} when a row of this user's was actually changed;
     *         {@code false} when the id is unknown, belongs to someone else, or
     *         was already read
     */
    boolean markRead(long userId, long notificationId, Instant at);

    /**
     * Marks every unread row of this user read.
     *
     * @return how many rows changed
     */
    int markAllRead(long userId, Instant at);
}
