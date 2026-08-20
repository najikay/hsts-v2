package common.dto.notify;

import java.io.Serializable;

/**
 * The {@code NOTIFICATIONS_MARK_READ} payload (Common tier, E17.3).
 *
 * <p>Two shapes in one DTO because they are one user gesture with two scopes:
 * clicking a row, and pressing "Mark all read". Splitting them into two verbs
 * would double the handler, the guard and the test surface for a boolean.
 *
 * <p>Note what is <b>not</b> here: a user id. Marking is always scoped to the
 * calling session's own rows — the server matches on {@code (id, user_id)}, so
 * pointing this at another user's notification id updates nothing and answers a
 * normal, unchanged page (E17.6 proves it).
 *
 * @param notificationId the row to mark; ignored when {@code all} is true
 * @param all            {@code true} to mark every unread row of the caller
 */
public record MarkReadRequest(long notificationId, boolean all) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** @return a request marking one notification read. */
    public static MarkReadRequest one(long notificationId) {
        return new MarkReadRequest(notificationId, false);
    }

    /**
     * @return a request marking every unread notification of the caller read
     *         (named {@code markAll} rather than {@code all} because a record's
     *         component accessor already owns that name)
     */
    public static MarkReadRequest markAll() {
        return new MarkReadRequest(0L, true);
    }
}
