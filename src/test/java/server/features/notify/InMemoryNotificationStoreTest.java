package server.features.notify;

import common.dto.notify.NavRef;
import common.dto.notify.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The store contract on the in-memory fixture, plus what only this implementation has.
 *
 * <p>The shared assertions live in {@link NotificationStoreContract} and are the same ones
 * {@code JpaNotificationStoreH2Test} and {@code JpaNotificationStoreMySqlTest} run, which is
 * what makes this class a genuine stand-in for the database rather than a mock that agrees
 * with whatever it is asked.
 *
 * <p>What stays here: {@link InMemoryNotificationStore#size} and
 * {@link InMemoryNotificationStore#clear}, which have no counterpart in a table, and the
 * concurrency probe, which is about this class's own locking. The equivalent question for the
 * JPA store is answered by MySQL's transactions, not by a test of ours.
 */
class InMemoryNotificationStoreTest extends NotificationStoreContract {

    private static final long DANA = 1001L;
    private static final long RINA = 1002L;

    private InMemoryNotificationStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryNotificationStore();
    }

    @Override
    protected NotificationStore store() {
        return store;
    }

    @Override
    protected long userA() {
        return DANA;
    }

    @Override
    protected long userB() {
        return RINA;
    }

    @Test
    @DisplayName("size() counts read and unread rows alike")
    void sizeCountsEverything() {
        long read = store.save(DANA, NotificationType.TIME_EXTENDED, "a", "", NavRef.none(), T0);
        store.save(DANA, NotificationType.TIME_EXTENDED, "b", "", NavRef.none(), T0);
        store.markRead(DANA, read, T0);

        assertThat(store.size(DANA)).isEqualTo(2);
        assertThat(store.unreadCount(DANA)).isEqualTo(1);
        assertThat(store.size(9999L)).isZero();
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
