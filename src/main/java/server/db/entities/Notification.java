package server.db.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One thing a user should know about — {@code notifications} (V7, §5, F11).
 *
 * <p>Persisted first, pushed second: a notification for someone who is offline is
 * waiting for them at their next login rather than lost, which is the half of E17.1 that
 * is easy to forget and impossible to retrofit.
 *
 * <p>{@link #refType} and {@link #refId} are what make a notification clickable — "your
 * exam was approved" navigates to that exam version. They are a loose reference by
 * design, not a foreign key: notifications outlive the things they point at, and a
 * dangling link that renders as plain text is better than a delete that fails because
 * something once mentioned the row.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "type", length = 50, nullable = false)
    private String type;

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    @Column(name = "body", length = ColumnSizes.TEXT)
    private String body;

    @Column(name = "ref_type", length = 50)
    private String refType;

    @Column(name = "ref_id")
    private Long refId;

    @Column(name = "created_at", nullable = false, precision = 3)
    private Instant createdAt;

    @Column(name = "read_at", precision = 3)
    private Instant readAt;

    /** Required by JPA. */
    protected Notification() {
    }

    public Notification(long userId, String type, String title, String body,
                        String refType, Long refId, Instant createdAt) {
        this.userId = userId;
        this.type = type;
        this.title = title;
        this.body = body;
        this.refType = refType;
        this.refId = refId;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getRefType() {
        return refType;
    }

    public Long getRefId() {
        return refId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReadAt() {
        return readAt;
    }

    /** Drives the unread badge in the navbar (E17.4). */
    public boolean isUnread() {
        return readAt == null;
    }

    public void markRead(Instant at) {
        if (readAt == null) {
            this.readAt = at;
        }
    }
}
