package server.db.repos;

import org.hibernate.Session;
import server.db.entities.Notification;

import java.util.List;

/** Reads over {@code notifications} (E2.11). */
public final class NotificationRepository {

    /**
     * A user's unread notifications, newest first.
     *
     * <p>Consumer: E12's notification panel and the bell badge.
     *
     * @param session the current session
     * @param userId  the recipient
     * @return unread notifications, newest first
     */
    public List<Notification> findUnread(Session session, long userId) {
        return session.createQuery("""
                        from Notification
                        where userId = :userId and readAt is null
                        order by createdAt desc, id desc
                        """, Notification.class)
                .setParameter("userId", userId)
                .getResultList();
    }

    /**
     * How many notifications a user has not read.
     *
     * <p>Counted rather than derived from {@link #findUnread} so the badge does not load
     * every row's body to show a number.
     *
     * <p>Consumer: E12's bell badge.
     *
     * @param session the current session
     * @param userId  the recipient
     * @return the unread count
     */
    public long countUnread(Session session, long userId) {
        return session.createQuery("""
                        select count(n) from Notification n
                        where n.userId = :userId and n.readAt is null
                        """, Long.class)
                .setParameter("userId", userId)
                .getSingleResult();
    }
}
