package common.dto.notify;

import java.io.Serializable;

/**
 * The {@code NOTIFICATIONS_GET} payload (Common tier, E17.3).
 *
 * <p>"Last N", not an offset page. The bell panel shows a short recent list and
 * nothing in the product pages back through history, so an offset would be a
 * parameter with no caller and one more thing to get wrong under a concurrent
 * insert. The count that matters — unread — is answered in full regardless of
 * this limit (see {@link NotificationsPage}).
 *
 * <p>The limit is clamped rather than rejected: a client asking for a million
 * rows is a bug in that client, not a reason to fail a user's bell.
 *
 * @param limit how many of the newest notifications to return
 */
public record NotificationsGetRequest(int limit) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** What the bell panel asks for: comfortably more than fits on screen. */
    public static final int DEFAULT_LIMIT = 30;

    /** Server-side ceiling; a larger request is served as this many. */
    public static final int MAX_LIMIT = 200;

    public NotificationsGetRequest {
        limit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }

    /** @return a request for the default page size. */
    public static NotificationsGetRequest defaults() {
        return new NotificationsGetRequest(DEFAULT_LIMIT);
    }
}
