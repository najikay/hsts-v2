package common.dto.notify;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * One notification, as the bell panel needs it (Common tier, E17.3 — F11.2).
 *
 * <p>The wire shape of a {@code notifications} row, minus {@code user_id}: the
 * recipient is always the caller, so putting the id on the wire would only
 * create something for a client to tamper with. Every list the server sends is
 * built from rows it selected by the session's own user id.
 *
 * <p>{@code createdAt} is UTC (ADR-010) and rendered relative on the client;
 * {@code readAt} being {@code null} is the entire definition of "unread", both
 * here and in the column.
 *
 * @param id        row id, used by {@code NOTIFICATIONS_MARK_READ}
 * @param type      what happened, driving the panel icon
 * @param title     one short line, already composed for this recipient
 * @param body      optional detail line; empty rather than null on the wire
 * @param ref       where clicking it goes; never {@code null} (see {@link NavRef#none()})
 * @param createdAt when it happened, UTC
 * @param readAt    when this user read it, or {@code null} while unread
 */
public record NotificationDto(long id,
                              NotificationType type,
                              String title,
                              String body,
                              NavRef ref,
                              Instant createdAt,
                              Instant readAt) implements Serializable {

    private static final long serialVersionUID = 1L;

    public NotificationDto {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(createdAt, "createdAt");
        body = body == null ? "" : body;
        ref = ref == null ? NavRef.none() : ref;
    }

    /** @return {@code true} while this notification has not been read. */
    public boolean isUnread() {
        return readAt == null;
    }

    /** @return the same notification marked read at {@code at}; already-read rows are unchanged. */
    public NotificationDto readAt(Instant at) {
        return readAt == null ? new NotificationDto(id, type, title, body, ref, createdAt, at) : this;
    }
}
