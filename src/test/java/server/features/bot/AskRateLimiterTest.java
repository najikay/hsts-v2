package server.features.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-student ask limit (E16.8).
 *
 * <p>Both the refusal and the recovery are driven by moving the injected clock. A
 * test that slept for a minute to prove a one-minute window is a test that gets
 * commented out the first time somebody is in a hurry.
 */
class AskRateLimiterTest {

    private static final Instant T0 = Instant.parse("2026-08-20T10:00:00Z");
    private static final long MAYA = 3001L;
    private static final long NOAM = 3002L;

    @Test
    @DisplayName("asks up to the limit are allowed and the next one is not")
    void allowsUpToTheLimit() {
        AskRateLimiter limiter = new AskRateLimiter(3, new BotTestClock(T0));

        assertThat(limiter.tryAcquire(MAYA)).isTrue();
        assertThat(limiter.tryAcquire(MAYA)).isTrue();
        assertThat(limiter.tryAcquire(MAYA)).isTrue();
        assertThat(limiter.tryAcquire(MAYA)).isFalse();
    }

    @Test
    @DisplayName("the window slides, so an ask a minute later is allowed again")
    void theWindowSlides() {
        BotTestClock clock = new BotTestClock(T0);
        AskRateLimiter limiter = new AskRateLimiter(2, clock);

        assertThat(limiter.tryAcquire(MAYA)).isTrue();
        assertThat(limiter.tryAcquire(MAYA)).isTrue();
        assertThat(limiter.tryAcquire(MAYA)).isFalse();

        clock.advance(AskRateLimiter.WINDOW.plusSeconds(1));

        assertThat(limiter.tryAcquire(MAYA)).isTrue();
    }

    @Test
    @DisplayName("it slides rather than resetting: half a window later, only the old asks expire")
    void slidesRatherThanResetting() {
        BotTestClock clock = new BotTestClock(T0);
        AskRateLimiter limiter = new AskRateLimiter(2, clock);

        limiter.tryAcquire(MAYA);
        clock.advance(Duration.ofSeconds(40));
        limiter.tryAcquire(MAYA);
        assertThat(limiter.tryAcquire(MAYA)).isFalse();

        // 21 more seconds: the first ask is now over a minute old, the second is not.
        clock.advance(Duration.ofSeconds(21));
        assertThat(limiter.tryAcquire(MAYA)).isTrue();
        assertThat(limiter.tryAcquire(MAYA)).isFalse();
    }

    @Test
    @DisplayName("one student's flood does not affect another's")
    void perStudent() {
        AskRateLimiter limiter = new AskRateLimiter(1, new BotTestClock(T0));

        assertThat(limiter.tryAcquire(MAYA)).isTrue();
        assertThat(limiter.tryAcquire(MAYA)).isFalse();
        assertThat(limiter.tryAcquire(NOAM)).isTrue();
    }

    @Test
    @DisplayName("a limit below one is treated as one, so the feature can never be switched off by config")
    void refusesToDisableItself() {
        AskRateLimiter limiter = new AskRateLimiter(0, new BotTestClock(T0));

        assertThat(limiter.maxPerWindow()).isEqualTo(1);
        assertThat(limiter.tryAcquire(MAYA)).isTrue();
        assertThat(limiter.tryAcquire(MAYA)).isFalse();
    }

    @Test
    @DisplayName("forgetting a student drops her window")
    void forget() {
        AskRateLimiter limiter = new AskRateLimiter(1, new BotTestClock(T0));

        limiter.tryAcquire(MAYA);
        assertThat(limiter.trackedStudents()).isEqualTo(1);

        limiter.forget(MAYA);

        assertThat(limiter.trackedStudents()).isZero();
        assertThat(limiter.tryAcquire(MAYA)).isTrue();
    }

    @Test
    @DisplayName("two threads asking at the same instant cannot both slip past the last slot")
    void isThreadSafe() throws Exception {
        AskRateLimiter limiter = new AskRateLimiter(5, new BotTestClock(T0));
        int threads = 16;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (limiter.tryAcquire(MAYA)) {
                        allowed.incrementAndGet();
                    }
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(allowed.get())
                .as("exactly the limit, never more: test-and-record is one atomic step")
                .isEqualTo(5);
    }
}
