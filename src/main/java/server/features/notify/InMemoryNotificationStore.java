package server.features.notify;

import common.dto.notify.NavRef;
import common.dto.notify.NotificationDto;
import common.dto.notify.NotificationType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The in-memory {@link NotificationStore} — <b>a test fixture, no longer
 * production wiring</b> (Logic tier, E17.1).
 *
 * <p>It ran the server until E2's repositories landed;
 * {@code HSTSServer.defaultRouter} now builds a {@link JpaNotificationStore}
 * instead. The class stays because the tests want it: {@link NotificationService}
 * and every rule it enforces remain unit-testable in milliseconds with no
 * database, and this implementation is held to the same
 * {@code NotificationStoreContract} the JPA one passes, so it is a genuine
 * stand-in rather than a mock that agrees with whatever it is asked.
 *
 * <p>A map from user id to that user's rows, newest last, plus one shared id
 * sequence standing in for {@code AUTO_INCREMENT}. It is a real implementation,
 * not a stub: it enforces the whole store contract, ownership scoping included,
 * so the service's rules are genuinely exercised by every test in this feature
 * rather than merely mocked.
 *
 * <p>Thread safety mirrors what MySQL would give: the outer map is concurrent,
 * and every read or write of one user's list happens inside that list's monitor.
 * Two teachers being notified at once therefore never interleave into a
 * half-written list, and a mark-all racing an insert either includes the new row
 * or does not — it cannot corrupt the list.
 *
 * <p>Rows are held as immutable {@link NotificationDto} values and replaced
 * rather than mutated, so a list handed to a caller can never change underneath
 * it while the caller is serialising it onto a socket.
 *
 * <p>Deliberate non-goal: durability. Restarting the JVM empties it. That is
 * exactly the gap {@link JpaNotificationStore} closes, and the reason this class
 * is a fixture rather than a deployment option.
 */
public final class InMemoryNotificationStore implements NotificationStore {

    /** Newest first, which is the order every caller wants and the DB index gives. */
    private static final Comparator<NotificationDto> NEWEST_FIRST =
            Comparator.comparing(NotificationDto::createdAt).reversed()
                    .thenComparing(Comparator.comparingLong(NotificationDto::id).reversed());

    private final Map<Long, List<NotificationDto>> byUser = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    @Override
    public long save(long userId, NotificationType type, String title, String body,
                     NavRef ref, Instant createdAt) {
        long id = ids.incrementAndGet();
        NotificationDto row = new NotificationDto(id, type, title, body, ref, createdAt, null);
        List<NotificationDto> rows = rowsOf(userId);
        synchronized (rows) {
            rows.add(row);
        }
        return id;
    }

    @Override
    public int unreadCount(long userId) {
        List<NotificationDto> rows = byUser.get(userId);
        if (rows == null) {
            return 0;
        }
        synchronized (rows) {
            return (int) rows.stream().filter(NotificationDto::isUnread).count();
        }
    }

    @Override
    public List<NotificationDto> listRecent(long userId, int limit) {
        List<NotificationDto> rows = byUser.get(userId);
        if (rows == null || limit <= 0) {
            return List.of();
        }
        synchronized (rows) {
            return rows.stream().sorted(NEWEST_FIRST).limit(limit).toList();
        }
    }

    @Override
    public boolean markRead(long userId, long notificationId, Instant at) {
        List<NotificationDto> rows = byUser.get(userId);
        if (rows == null) {
            // No rows for this user: an id that exists for somebody else lands here
            // and changes nothing, which is the whole ownership rule (E17.6).
            return false;
        }
        synchronized (rows) {
            for (int i = 0; i < rows.size(); i++) {
                NotificationDto row = rows.get(i);
                if (row.id() == notificationId && row.isUnread()) {
                    rows.set(i, row.readAt(at));
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public int markAllRead(long userId, Instant at) {
        List<NotificationDto> rows = byUser.get(userId);
        if (rows == null) {
            return 0;
        }
        int changed = 0;
        synchronized (rows) {
            for (int i = 0; i < rows.size(); i++) {
                NotificationDto row = rows.get(i);
                if (row.isUnread()) {
                    rows.set(i, row.readAt(at));
                    changed++;
                }
            }
        }
        return changed;
    }

    /** @return how many rows are stored for this user, read or unread (diagnostics and tests). */
    public int size(long userId) {
        List<NotificationDto> rows = byUser.get(userId);
        if (rows == null) {
            return 0;
        }
        synchronized (rows) {
            return rows.size();
        }
    }

    /** Empties the store; used by tests and by the server console's reset action. */
    public void clear() {
        byUser.clear();
    }

    private List<NotificationDto> rowsOf(long userId) {
        return byUser.computeIfAbsent(userId, key -> new ArrayList<>());
    }
}
