package server.features.exam;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The timer that ends exams whether or not anybody is watching (E10.5 ⚑).
 *
 * <p>Every case runs on a {@link TestClock} and a {@link ManualScheduler}, so "it fires at
 * the deadline" and, just as importantly, "it does <em>not</em> fire after an extension
 * moved the deadline" are exact assertions rather than timed waits.
 */
class TimerServiceTest {

    private static final Instant T0 = Instant.parse("2026-08-20T09:00:00Z");
    private static final long ATTEMPT = 4242L;

    private TestClock clock;
    private ManualScheduler scheduler;
    private List<Long> expired;
    private TimerService timers;

    @BeforeEach
    void setUp() {
        clock = new TestClock(T0);
        scheduler = new ManualScheduler();
        expired = new java.util.ArrayList<>();
        timers = new TimerService(clock, scheduler, expired::add);
    }

    @Nested
    @DisplayName("arming")
    class Arming {

        @Test
        @DisplayName("an armed attempt fires its expiry when the task runs")
        void firesOnSchedule() {
            timers.arm(ATTEMPT, T0.plus(Duration.ofMinutes(45)));

            scheduler.runAll();

            assertThat(expired).containsExactly(ATTEMPT);
        }

        @Test
        @DisplayName("the delay is the time from now to the deadline")
        void delayIsTheDistanceToTheDeadline() {
            timers.arm(ATTEMPT, T0.plus(Duration.ofMinutes(45)));

            assertThat(scheduler.all()).hasSize(1);
            assertThat(scheduler.all().get(0).delay()).isEqualTo(Duration.ofMinutes(45));
        }

        @Test
        @DisplayName("a deadline already in the past is armed with a negative delay, not skipped")
        void pastDeadlineStillArms() {
            // The restart case: the bell went while the process was down, and the attempt
            // must be closed at once rather than left open forever (the v1 bug).
            timers.arm(ATTEMPT, T0.minus(Duration.ofMinutes(5)));

            assertThat(scheduler.all().get(0).delay()).isNegative();
            scheduler.runAll();
            assertThat(expired).containsExactly(ATTEMPT);
        }

        @Test
        @DisplayName("the deadline it is armed for can be read back")
        void deadlineIsReadable() {
            Instant deadline = T0.plus(Duration.ofMinutes(30));
            timers.arm(ATTEMPT, deadline);

            assertThat(timers.deadlineOf(ATTEMPT)).contains(deadline);
            assertThat(timers.isArmed(ATTEMPT)).isTrue();
            assertThat(timers.armedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("an unarmed attempt has no deadline")
        void unarmedHasNoDeadline() {
            assertThat(timers.deadlineOf(ATTEMPT)).isEmpty();
            assertThat(timers.isArmed(ATTEMPT)).isFalse();
        }

        @Test
        @DisplayName("a null deadline is rejected at the boundary")
        void nullDeadlineRejected() {
            assertThatNullPointerException().isThrownBy(() -> timers.arm(ATTEMPT, null));
        }
    }

    @Nested
    @DisplayName("re-arming after an extension")
    class Rearming {

        @Test
        @DisplayName("re-arming replaces the task rather than adding a second")
        void replacesTheTask() {
            timers.arm(ATTEMPT, T0.plus(Duration.ofMinutes(45)));

            timers.arm(ATTEMPT, T0.plus(Duration.ofMinutes(60)));

            assertThat(timers.armedCount()).isEqualTo(1);
            assertThat(timers.deadlineOf(ATTEMPT)).contains(T0.plus(Duration.ofMinutes(60)));
            assertThat(scheduler.cancelledCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a stale task that runs anyway does not expire the extended attempt ⚑")
        void staleTaskIsFenced() {
            // The window the cancel cannot close: the old task had already started running
            // when the extension landed. Without the generation token this ends an exam
            // that has just been given fifteen more minutes.
            timers.arm(ATTEMPT, T0.plus(Duration.ofMinutes(45)));
            Runnable stale = scheduler.all().get(0).task();
            timers.arm(ATTEMPT, T0.plus(Duration.ofMinutes(60)));

            stale.run();

            assertThat(expired).isEmpty();
            assertThat(timers.isArmed(ATTEMPT))
                    .as("and the live task is still there")
                    .isTrue();
        }

        @Test
        @DisplayName("re-arming a whole set arms each one")
        void rearmAll() {
            int armed = timers.rearmAll(List.of(
                    new TimerService.Armed(1, T0.plusSeconds(60)),
                    new TimerService.Armed(2, T0.plusSeconds(120))));

            assertThat(armed).isEqualTo(2);
            assertThat(timers.armedCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("re-arming is additive, so extending one execution cannot disarm another")
        void rearmIsAdditive() {
            timers.arm(99, T0.plusSeconds(600));

            timers.rearmAll(List.of(new TimerService.Armed(1, T0.plusSeconds(60))));

            assertThat(timers.isArmed(99)).isTrue();
        }

        @Test
        @DisplayName("re-arming nothing is a no-op")
        void rearmNothing() {
            assertThat(timers.rearmAll(List.of())).isZero();
            assertThat(timers.rearmAll(null)).isZero();
        }
    }

    @Nested
    @DisplayName("disarming")
    class Disarming {

        @Test
        @DisplayName("a disarmed attempt does not fire")
        void disarmedDoesNotFire() {
            timers.arm(ATTEMPT, T0.plus(Duration.ofMinutes(45)));

            assertThat(timers.disarm(ATTEMPT)).isTrue();
            scheduler.runAll();

            assertThat(expired).isEmpty();
        }

        @Test
        @DisplayName("disarming something that is not armed answers false, never throws")
        void disarmUnarmed() {
            // A submit arriving after the timer already fired is the common case, not an
            // error, and the caller must not have to know which got there first.
            assertThat(timers.disarm(ATTEMPT)).isFalse();
        }

        @Test
        @DisplayName("disarmAll clears everything")
        void disarmAll() {
            timers.arm(1, T0.plusSeconds(60));
            timers.arm(2, T0.plusSeconds(60));

            timers.disarmAll();

            assertThat(timers.armedCount()).isZero();
        }
    }

    @Nested
    @DisplayName("the backstop sweep")
    class Sweep {

        @Test
        @DisplayName("a sweep expires everything overdue and leaves the rest alone")
        void sweepsTheOverdue() {
            timers.arm(1, T0.plus(Duration.ofMinutes(10)));
            timers.arm(2, T0.plus(Duration.ofMinutes(50)));
            clock.advance(Duration.ofMinutes(20));

            int fired = timers.sweep();

            assertThat(fired).isEqualTo(1);
            assertThat(expired).containsExactly(1L);
            assertThat(timers.isArmed(2)).isTrue();
        }

        @Test
        @DisplayName("an attempt exactly at its deadline is overdue")
        void deadlineIsInclusive() {
            timers.arm(ATTEMPT, T0.plus(Duration.ofMinutes(10)));
            clock.moveTo(T0.plus(Duration.ofMinutes(10)));

            assertThat(timers.sweep()).isEqualTo(1);
        }

        @Test
        @DisplayName("a sweep with nothing overdue does nothing")
        void quietSweep() {
            timers.arm(ATTEMPT, T0.plus(Duration.ofMinutes(45)));

            assertThat(timers.sweep()).isZero();
            assertThat(expired).isEmpty();
        }

        @Test
        @DisplayName("sweeping twice does not fire the same attempt twice")
        void sweepIsIdempotent() {
            timers.arm(ATTEMPT, T0.plus(Duration.ofMinutes(10)));
            clock.advance(Duration.ofMinutes(20));

            timers.sweep();
            int second = timers.sweep();

            assertThat(second).isZero();
            assertThat(expired).containsExactly(ATTEMPT);
        }
    }

    @Nested
    @DisplayName("failure handling")
    class Failures {

        @Test
        @DisplayName("an expiry that throws leaves the attempt armed for the next sweep ⚑")
        void failedExpiryStaysArmed() {
            AtomicBoolean first = new AtomicBoolean(true);
            List<Long> succeeded = new java.util.ArrayList<>();
            TimerService fragile = new TimerService(clock, scheduler, attemptId -> {
                if (first.getAndSet(false)) {
                    throw new IllegalStateException("database went away");
                }
                succeeded.add(attemptId);
            });
            fragile.arm(ATTEMPT, T0.plus(Duration.ofMinutes(10)));
            clock.advance(Duration.ofMinutes(20));

            fragile.sweep();

            // A timer thread that died on one bad row would silently stop ending every
            // other exam in the building.
            assertThat(succeeded).isEmpty();
            assertThat(fragile.isArmed(ATTEMPT)).isTrue();

            fragile.sweep();
            assertThat(succeeded).containsExactly(ATTEMPT);
        }

        @Test
        @DisplayName("constructor arguments are all required")
        void constructorGuards() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TimerService(null, scheduler, expired::add));
            assertThatNullPointerException()
                    .isThrownBy(() -> new TimerService(clock, null, expired::add));
            assertThatNullPointerException()
                    .isThrownBy(() -> new TimerService(clock, scheduler, null));
        }
    }

    @Nested
    @DisplayName("the production scheduler")
    class ProductionScheduler {

        @Test
        @DisplayName("it really runs the task on the executor it was given")
        void runsOnTheExecutor() throws Exception {
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
            try {
                java.util.concurrent.CountDownLatch ran = new java.util.concurrent.CountDownLatch(1);
                TimerService.Scheduler production = TimerService.Scheduler.on(executor);

                production.schedule(ran::countDown, Duration.ZERO);

                assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("cancelling a scheduled task stops it running")
        void cancelStopsIt() throws Exception {
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
            try {
                AtomicBoolean ran = new AtomicBoolean();
                TimerService.Scheduler production = TimerService.Scheduler.on(executor);

                TimerService.Scheduler.Handle handle =
                        production.schedule(() -> ran.set(true), Duration.ofSeconds(30));
                handle.cancel();

                Thread.sleep(50);
                assertThat(ran).isFalse();
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("a negative delay is clamped rather than rejected by the executor")
        void negativeDelayIsClamped() throws Exception {
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
            try {
                java.util.concurrent.CountDownLatch ran = new java.util.concurrent.CountDownLatch(1);

                TimerService.Scheduler.on(executor)
                        .schedule(ran::countDown, Duration.ofMinutes(-5));

                assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("the executor is required")
        void executorRequired() {
            assertThatNullPointerException().isThrownBy(() -> TimerService.Scheduler.on(null));
        }
    }
}
