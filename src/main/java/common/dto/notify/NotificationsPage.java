package common.dto.notify;

import java.io.Serializable;
import java.util.List;

/**
 * The answer to {@code NOTIFICATIONS_GET} and {@code NOTIFICATIONS_MARK_READ}
 * (Common tier, E17.3).
 *
 * <p>List and count travel together on purpose. The badge and the panel are two
 * views of one truth, and computing the count on the client from a truncated
 * list would make the badge quietly wrong the moment a user has more unread
 * notifications than the page holds. The server counts every unread row; the
 * list is the newest {@code n}.
 *
 * <p>Both verbs answer with this same shape, so marking one read updates the
 * badge and the rows in a single round trip — no follow-up fetch, no window
 * where the two disagree (NFR-18: nothing in this app is refreshed by hand).
 *
 * @param items       newest first; never {@code null}, defensively copied
 * @param unreadCount unread rows for this user across the whole table, not just
 *                    the ones in {@code items}
 */
public record NotificationsPage(List<NotificationDto> items, int unreadCount) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The page a user with no notifications gets. */
    public static final NotificationsPage EMPTY = new NotificationsPage(List.of(), 0);

    public NotificationsPage {
        // List.copyOf yields an immutable, Serializable list — safe on the wire.
        items = items == null ? List.of() : List.copyOf(items);
        unreadCount = Math.max(0, unreadCount);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int size() {
        return items.size();
    }
}
