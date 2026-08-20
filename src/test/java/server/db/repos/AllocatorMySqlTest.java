package server.db.repos;

import org.hibernate.Session;
import org.hibernate.Transaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import server.db.TestDatabase;
import server.db.TestDatabases;
import server.db.entities.Question;
import server.db.ids.AllocatedId;
import server.db.ids.QuestionIdAllocator;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The allocator contract on real MySQL, plus the concurrency tests that only mean something
 * here.
 *
 * <p>H2 is excluded from these deliberately. Its in-memory engine does not reproduce InnoDB
 * row locking, so a concurrency test there would pass against an allocator with no locking at
 * all — the most expensive kind of false confidence, because the failure it hides only appears
 * when two teachers author at the same time in a live demo.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class AllocatorMySqlTest extends AllocatorContract {

    private static final int THREADS = 8;

    /** Long enough that a second transaction has certainly reached the allocator. */
    private static final long WINDOW_MILLIS = 500;

    private final QuestionIdAllocator questions = new QuestionIdAllocator();

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }

    @Test
    @DisplayName("eight simultaneous allocations produce eight different ids")
    void concurrentAllocationsDoNotCollide() throws Exception {
        CountDownLatch startTogether = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<String>> jobs = java.util.stream.IntStream.range(0, THREADS)
                    .<Callable<String>>mapToObj(i -> () -> {
                        startTogether.await();
                        return allocateAndInsert();
                    })
                    .toList();

            List<Future<String>> futures = jobs.stream().map(pool::submit).toList();
            startTogether.countDown();

            List<String> ids = futures.stream().map(AllocatorMySqlTest::get).toList();

            assertThat(ids).doesNotHaveDuplicates().hasSize(THREADS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a second allocation cannot read the serial while the first still holds it")
    void theRowLockIsWhatPreventsTheCollision() throws Exception {
        AtomicReference<AllocatedId> secondResult = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        CountDownLatch firstHasAllocated = new CountDownLatch(1);

        Thread second = new Thread(() -> {
            try {
                firstHasAllocated.await();
                secondResult.set(inTx(session -> allocateAndInsert(session)));
            } catch (Throwable t) {
                secondFailure.set(t);
            }
        }, "second-allocator");

        AllocatedId first;
        try (Session session = factory().openSession()) {
            Transaction tx = session.beginTransaction();
            first = allocateAndInsert(session);
            session.flush();

            second.start();
            firstHasAllocated.countDown();
            // The second thread is now inside allocate(). With the row lock it is blocked
            // here; without it, it has already read the same MAX this transaction read - the
            // uncommitted insert above is invisible to it - and is about to reuse the serial.
            Thread.sleep(WINDOW_MILLIS);

            tx.commit();
        }
        second.join(TimeUnit.SECONDS.toMillis(30));

        assertThat(secondFailure.get()).as("the second allocation should succeed, not collide").isNull();
        assertThat(secondResult.get().serial())
                .as("the second allocation must see the first one's row")
                .isEqualTo(first.serial() + 1);
    }

    private String allocateAndInsert() {
        return inTx(session -> allocateAndInsert(session).displayId());
    }

    private AllocatedId allocateAndInsert(Session session) {
        AllocatedId allocated = questions.allocate(session, COURSE_ALGEBRA);
        session.persist(new Question(COURSE_ALGEBRA, (short) allocated.serial(), allocated.displayId()));
        return allocated;
    }

    private static String get(Future<String> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException("an allocation failed", e);
        }
    }
}
