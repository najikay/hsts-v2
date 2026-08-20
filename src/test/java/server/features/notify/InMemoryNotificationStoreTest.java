package server.features.notify;

import common.dto.notify.NavRef;
import common.dto.notify.NotificationDto;
import common.dto.notify.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the store contract (E17.1/E17.6).
 *
 * <p>Written against the interface's rules rather than the map behind them, so
 * the JPA implementation that replaces this one after E2 PR 2a can be dropped
 * into the same suite and must pass it unchanged. The ownership rules in
 * particular are the reason those rules live in the store at all: a store that
 * answered by id alone would make them unenforceable one layer up.
 */
class InMemoryNotificationStoreTest {

    private static final long DANA = 1001L;
    private static final long RINA = 1002L;
    private static final Instant T0 = Instant.parse("2026-08-19T09:00:00Z");

    private InMemoryNotificationStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryNotificationStore();
    }

    @Test
    @DisplayName("a saved notification comes back with every field intact")
    void persistenceRoundTrip() {
        long id = store.save(DANA, NotificationType.APPROVAL_APPROVED, "Exam approved",
                "Rina Barak approved Midterm.", NavRef.to("exams", 55L), T0);

        List<NotificationDto> rows = store.listRecent(DANA, 10);

        assertThat(rows).hasSize(1);
        NotificationDto row = rows.get(0);
        assertThat(row.id()).isEqualTo(id);
        assertThat(row.type()).isEqualTo(NotificationType.APPROVAL_APPROVED);
        assertThat(row.title()).isEqualTo("Exam approved");
        assertThat(row.body()).isEqualTo("Rina Barak approved Midterm.");
        assertThat(row.ref()).isEqualTo(NavRef.to("exams", 55L));
        assertThat(row.createdAt()).isEqualTo(T0);
        assertThat(row.isUnread()).isTrue();
    }

    @Test
    @DisplayName("ids are unique across users, so a mark-read can never be ambiguous")
    void idsAreGloballyUnique() {
        long first = store.save(DANA, NotificationType.TIME_EXTENDED, "a", "", NavRef.none(), T0);
        long second = store.save(RINA, NotificationType.TIME_EXTENDED, "b", "", NavRef.none(), T0);

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    @DisplayName("the list is newest first")
    void newestFirst() {
        store.save(DANA, NotificationType.TIME_EXTENDED, "oldest", "", NavRef.none(), T0);
        store.save(DANA, NotificationType.TIME_EXTENDED, "middle", "", NavRef.none(), T0.plusSeconds(60));
        store.save(DANA, NotificationType.TIME_EXTENDED, "newest", "", NavRef.none(), T0.plusSeconds(120));

        assertThat(store.listRecent(DANA, 10))
                .extracting(NotificationDto::title)
                .containsExactly("newest", "middle", "oldest");
    }

    @Test
    @DisplayName("rows created in the same millisecond still have a stable order")
    void tiesBreakOnId() {
        store.save(DANA, NotificationType.TIME_EXTENDED, "first", "", NavRef.none(), T0);
        store.save(DANA, NotificationType.TIME_EXTENDED, "second", "", NavRef.none(), T0);

        assertThat(store.listRecent(DANA, 10))
                .extracting(NotificationDto::title)
                .containsExactly("second", "first");
    }

    @Test
    @DisplayName("the limit truncates the list but never the unread count")
    void limitTruncatesTheListOnly() {
        for (int i = 0; i < 5; i++) {
            store.save(DANA, NotificationType.TIME_EXTENDED, "n" + i, "", NavRef.none(), T0.plusSeconds(i));
        }

        assertThat(store.listRecent(DANA, 2)).hasSize(2);
        assertThat(store.unreadCount(DANA)).isEqualTo(5);
        assertThat(store.listRecent(DANA, 0)).isEmpty();
    }

    @Test
    @DisplayName("a user with nothing gets an empty list and a zero count, not a failure")
    void unknownUserIsEmpty() {
        assertThat(store.listRecent(9999L, 10)).isEmpty();
        assertThat(store.unreadCount(9999L)).isZero();
        assertThat(store.markAllRead(9999L, T0)).isZero();
        assertThat(store.markRead(9999L, 1L, T0)).isFalse();
        assertThat(store.size(9999L)).isZero();
    }

    @Test
    @DisplayName("marking read is scoped to the owner and is idempotent")
    void markReadSemantics() {
        long danas = store.save(DANA, NotificationType.GRADE_PUBLISHED, "yours", "", NavRef.none(), T0);

        assertThat(store.markRead(DANA, danas, T0.plusSeconds(1))).isTrue();
        assertThat(store.unreadCount(DANA)).isZero();
        assertThat(store.listRecent(DANA, 10).get(0).readAt()).isEqualTo(T0.plusSeconds(1));
        // Second time changes nothing: a double-click, or two windows open.
        assertThat(store.markRead(DANA, danas, T0.plusSeconds(2))).isFalse();
        assertThat(store.listRecent(DANA, 10).get(0).readAt()).isEqualTo(T0.plusSeconds(1));
    }

    @Test
    @DisplayName("one user cannot mark another user's notification read (E17.6)")
    void markReadCannotCrossUsers() {
        long danas = store.save(DANA, NotificationType.GRADE_PUBLISHED, "yours", "", NavRef.none(), T0);
        store.save(RINA, NotificationType.GRADE_PUBLISHED, "hers", "", NavRef.none(), T0);

        assertThat(store.markRead(RINA, danas, T0.plusSeconds(1))).isFalse();
        assertThat(store.unreadCount(DANA))
                .as("Dana's row is untouched by Rina naming its id")
                .isEqualTo(1);
        assertThat(store.unreadCount(RINA)).isEqualTo(1);
    }

    @Test
    @DisplayName("mark-all touches only the caller's unread rows")
    void markAllIsScopedAndCountsChanges() {
        store.save(DANA, NotificationType.TIME_EXTENDED, "a", "", NavRef.none(), T0);
        long read = store.save(DANA, NotificationType.TIME_EXTENDED, "b", "", NavRef.none(), T0);
        store.save(RINA, NotificationType.TIME_EXTENDED, "c", "", NavRef.none(), T0);
        store.markRead(DANA, read, T0);

        assertThat(store.markAllRead(DANA, T0.plusSeconds(5)))
                .as("only the still-unread row is changed")
                .isEqualTo(1);
        assertThat(store.unreadCount(DANA)).isZero();
        assertThat(store.unreadCount(RINA)).isEqualTo(1);
        assertThat(store.markAllRead(DANA, T0.plusSeconds(6))).isZero();
    }

    @Test
    @DisplayName("clear() empties every user")
    void clearEmptiesEverything() {
        store.save(DANA, NotificationType.TIME_EXTENDED, "a", "", NavRef.none(), T0);
        store.save(RINA, NotificationType.TIME_EXTENDED, "b", "", NavRef.none(), T0);

        store.clear();

        assertThat(store.size(DANA)).isZero();
        assertThat(store.size(RINA)).isZero();
    }

    @Test
    @DisplayName("concurrent writers for one user lose nothing")
    void concurrentSavesAreSafe() throws Exception {
        int writers = 8;
        int perWriter = 50;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int w = 0; w < writers; w++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        store.save(DANA, NotificationType.TIME_EXTENDED, "n", "", NavRef.none(), T0);
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(store.size(DANA)).isEqualTo(writers * perWriter);
        assertThat(store.unreadCount(DANA)).isEqualTo(writers * perWriter);
    }
}
