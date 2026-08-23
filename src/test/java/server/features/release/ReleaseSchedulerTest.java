package server.features.release;

import common.dto.notify.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import server.db.entities.ExamVersionStatus;
import server.db.entities.ExecutionStatus;
import server.features.exam.ExecutionCloseService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ReleaseScheduler} — E9.2 (F5.2, F5.4, F11.1).
 *
 * <p>Everything here is decided from an injected {@link Clock} that this test moves by hand,
 * which is the whole reason the scheduler is written as a {@code tick()} rather than as a
 * thread that measures elapsed time. "The notice goes out at T minus 30 and not again at T
 * minus 29" is a three-line test instead of a half-hour one, and "an exam that was extended
 * is not closed" is exact instead of flaky.
 *
 * <p>Four rules are worth the file:
 *
 * <ol>
 *   <li>a release opens when its window begins, and the transition is guarded, so a release
 *       cancelled a second earlier is not reopened;</li>
 *   <li>the "opens soon" notice reaches the enrolled students and the releasing teacher,
 *       <b>once</b>, however many times the check runs;</li>
 *   <li>a release whose window has run out is closed through the same seam the teacher's
 *       button uses, so both ways of ending produce the same row;</li>
 *   <li>an extension is honoured: fifteen more minutes means fifteen more minutes, not a
 *       close at the originally stored time (S-20).</li>
 * </ol>
 */
class ReleaseSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");
    private static final long DANA = 101;
    private static final long AUTHOR = 102;
    private static final long MAYA = 201;
    private static final long NOAM = 202;
    private static final long VERSION = 7001;
    private static final String ALGEBRA = "11";

    private InMemoryReleaseStore store;
    private ExecutionCloseService closeService;
    private RecordingNotifier notifier;
    private List<Long> announced;
    private MovableClock clock;
    private ReleaseScheduler scheduler;

    @BeforeEach
    void setUp() {
        store = new InMemoryReleaseStore();
        store.withTeacher(DANA, ALGEBRA);
        store.enrols(ALGEBRA, MAYA, NOAM);
        store.version(VERSION, 900, ALGEBRA, ExamVersionStatus.APPROVED, AUTHOR);

        closeService = Mockito.mock(ExecutionCloseService.class);
        // The real seam force-submits the stragglers and sets CLOSED. The double does the
        // status half, so "closing twice is not possible" is a real assertion here rather
        // than one that passes because nothing ever changed.
        Mockito.doAnswer(invocation -> {
            store.markClosed(invocation.getArgument(0));
            return java.util.Optional.empty();
        }).when(closeService).close(anyLong());
        notifier = new RecordingNotifier();
        announced = new ArrayList<>();
        clock = new MovableClock(NOW);
        scheduler = new ReleaseScheduler(store, closeService, notifier, announced::add, clock);
    }

    // ===================== Opening =======================================

    @Nested
    @DisplayName("opening a release when its window begins (F5.2)")
    class Opening {

        @Test
        @DisplayName("a release opens the moment its window does, and its owners are told")
        void opensOnTime() {
            long executionId = scheduled(NOW.plus(Duration.ofMinutes(10)));

            scheduler.tick();
            assertThat(store.statusOf(executionId))
                    .as("nine minutes early is still early")
                    .isEqualTo(ExecutionStatus.SCHEDULED);

            clock.advance(Duration.ofMinutes(10));
            int changed = scheduler.tick();

            assertThat(changed).isEqualTo(1);
            assertThat(store.statusOf(executionId)).isEqualTo(ExecutionStatus.LIVE);
            assertThat(announced).containsExactly(executionId);
        }

        @Test
        @DisplayName("a release cancelled a moment earlier is not reopened ⚑")
        void aCancelledReleaseStaysCancelled() {
            long executionId = scheduled(NOW.minus(Duration.ofMinutes(1)));
            store.transition(executionId, ExecutionStatus.SCHEDULED, ExecutionStatus.CANCELLED);

            scheduler.tick();

            // The guarded transition is what makes this true: the check reads a release and
            // writes it a moment later, and the teacher's decision in between has to win.
            assertThat(store.statusOf(executionId)).isEqualTo(ExecutionStatus.CANCELLED);
            assertThat(announced).isEmpty();
        }

        @Test
        @DisplayName("opening twice is not possible: the second pass finds nothing to do")
        void idempotent() {
            long executionId = scheduled(NOW.minus(Duration.ofMinutes(1)));

            scheduler.tick();
            announced.clear();
            int second = scheduler.tick();

            assertThat(second).isZero();
            assertThat(announced).isEmpty();
            assertThat(store.statusOf(executionId)).isEqualTo(ExecutionStatus.LIVE);
        }
    }

    // ===================== The opens-soon notice =========================

    @Nested
    @DisplayName("telling people it opens soon (F11.1)")
    class OpeningSoon {

        @Test
        @DisplayName("the notice goes out inside the lead window and not before ⚑")
        void warnsAtTheRightMoment() {
            scheduled(NOW.plus(ReleaseScheduler.OPENING_SOON).plus(Duration.ofMinutes(5)));

            scheduler.tick();
            assertThat(notifier.all())
                    .as("thirty-five minutes away is not yet news")
                    .isEmpty();

            clock.advance(Duration.ofMinutes(5));
            scheduler.tick();

            assertThat(notifier.of(NotificationType.RELEASE_OPENING_SOON)).hasSize(1);
        }

        @Test
        @DisplayName("it reaches the enrolled students and the teacher who released it")
        void recipients() {
            scheduled(NOW.plus(Duration.ofMinutes(20)));

            scheduler.tick();

            assertThat(notifier.of(NotificationType.RELEASE_OPENING_SOON))
                    .singleElement()
                    .satisfies(sent -> assertThat(sent.userIds())
                            .containsExactlyInAnyOrder(MAYA, NOAM, DANA));
        }

        @Test
        @DisplayName("⚑ it goes out once, however many times the check runs")
        void warnsOnlyOnce() {
            scheduled(NOW.plus(Duration.ofMinutes(20)));

            // The check runs every thirty seconds and the window is thirty minutes: the naive
            // version sends this sixty times.
            for (int pass = 0; pass < 10; pass++) {
                scheduler.tick();
                clock.advance(ReleaseScheduler.INTERVAL);
            }

            assertThat(notifier.of(NotificationType.RELEASE_OPENING_SOON)).hasSize(1);
        }

        @Test
        @DisplayName("the sentence says how many minutes away it is, and never zero")
        void saysHowLong() {
            scheduled(NOW.plus(Duration.ofSeconds(50)));

            scheduler.tick();

            assertThat(notifier.of(NotificationType.RELEASE_OPENING_SOON))
                    .singleElement()
                    .satisfies(sent -> assertThat(sent.body())
                            .as("fifty seconds rounds down to zero, which would be nonsense")
                            .contains("1 minute"));
        }

        @Test
        @DisplayName("a release that has already opened is not warned about")
        void noNoticeForSomethingAlreadyOpen() {
            scheduled(NOW.minus(Duration.ofMinutes(1)));

            scheduler.tick();

            assertThat(notifier.all()).isEmpty();
        }

        @Test
        @DisplayName("the already-warned set is pruned, so it cannot grow for the life of the server")
        void warnedSetIsPruned() {
            long executionId = scheduled(NOW.plus(Duration.ofMinutes(20)));
            scheduler.tick();
            assertThat(scheduler.warnedCount()).isEqualTo(1);

            store.transition(executionId, ExecutionStatus.SCHEDULED, ExecutionStatus.CANCELLED);
            scheduler.tick();

            assertThat(scheduler.warnedCount()).isZero();
        }
    }

    // ===================== Closing =======================================

    @Nested
    @DisplayName("closing a release when its window runs out (F5.4)")
    class Closing {

        @Test
        @DisplayName("an expired release is closed through the same seam the button uses ⚑")
        void closesThroughTheSeam() {
            long executionId = store.execution("AAAA", ExecutionStatus.LIVE,
                    NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofMinutes(10)),
                    DANA, VERSION);

            scheduler.tick();
            verify(closeService, never()).close(anyLong());

            clock.advance(Duration.ofMinutes(11));
            int changed = scheduler.tick();

            assertThat(changed).isEqualTo(1);
            // Not a second close implementation: the same call, so a release ended by the
            // clock and one ended by hand produce identical rows and identical attempts.
            verify(closeService, times(1)).close(executionId);
            assertThat(announced).containsExactly(executionId);
        }

        @Test
        @DisplayName("⚑ a release that was just extended is not closed at its old time (S-20)")
        void extensionIsHonoured() {
            long executionId = store.execution("AAAA", ExecutionStatus.LIVE,
                    NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofMinutes(5)),
                    DANA, VERSION);
            store.extend(executionId, 15);

            clock.advance(Duration.ofMinutes(6));
            scheduler.tick();

            // The read filters on the stored close time because no portable query adds
            // minutes to a timestamp; the scheduler is what keeps the promise.
            verify(closeService, never()).close(anyLong());

            clock.advance(Duration.ofMinutes(15));
            scheduler.tick();
            verify(closeService).close(executionId);
        }

        @Test
        @DisplayName("a window that passed entirely while the server was down opens and then closes ⚑")
        void aMissedWindowIsResolvedInOnePass() {
            long executionId = store.execution("AAAA", ExecutionStatus.SCHEDULED,
                    NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(1)), DANA, VERSION);

            scheduler.tick();

            // The three jobs run in this order for exactly this case. Leaving it scheduled
            // for ever would be a release nobody can act on, holding a code nobody can use;
            // opening it and closing it lands it where it would have been had the server
            // stayed up, with a frozen record saying nobody sat it.
            assertThat(store.statusOf(executionId)).isEqualTo(ExecutionStatus.CLOSED);
            verify(closeService, times(1)).close(executionId);
        }

        @Test
        @DisplayName("a cancelled release is never closed: it was never open")
        void cancelledIsNotClosed() {
            long executionId = store.execution("AAAA", ExecutionStatus.CANCELLED,
                    NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(1)), DANA, VERSION);

            scheduler.tick();

            // Freezing counts for a sitting that never happened would put a zero-participant
            // row where PRD §6 says cancelled releases must not appear.
            verify(closeService, never()).close(anyLong());
            assertThat(store.statusOf(executionId)).isEqualTo(ExecutionStatus.CANCELLED);
        }
    }

    // ===================== Robustness ====================================

    @Test
    @DisplayName("a failing pass is logged and the next one carries on")
    void aFailingPassDoesNotKillTheThread() {
        ReleaseScheduler broken = new ReleaseScheduler(new ExplodingStore(), closeService,
                notifier, announced::add, clock);

        assertThat(broken.tick()).isZero();
        // The point: it returned rather than threw, so the executor keeps scheduling it and
        // every other exam in the school still opens.
        assertThat(broken.tick()).isZero();
    }

    @Test
    @DisplayName("a scheduler with nowhere to announce still opens releases")
    void announcerIsOptional() {
        ReleaseScheduler silent =
                new ReleaseScheduler(store, closeService, notifier, null, clock);
        long executionId = scheduled(NOW.minus(Duration.ofMinutes(1)));

        // A server assembled without a push channel is a legitimate configuration (the
        // console's, and every test's); a release must still go live in the database.
        assertThat(silent.tick()).isEqualTo(1);
        assertThat(store.statusOf(executionId)).isEqualTo(ExecutionStatus.LIVE);
    }

    @Test
    @DisplayName("a pass with nothing to do writes nothing and tells nobody")
    void quietPass() {
        assertThat(scheduler.tick()).isZero();

        assertThat(notifier.all()).isEmpty();
        assertThat(announced).isEmpty();
    }

    // ===================== Fixture =======================================

    private long scheduled(Instant openAt) {
        return store.execution("AAAA", ExecutionStatus.SCHEDULED, openAt,
                openAt.plus(Duration.ofHours(1)), DANA, VERSION);
    }

    /** A store whose every transaction fails, for the "nothing throws at the caller" rule. */
    private static final class ExplodingStore implements ReleaseStore {

        @Override
        public <T> T inTx(java.util.function.Function<ReleaseData, T> work) {
            throw new IllegalStateException("the database is on fire");
        }
    }

    /** A clock a test moves by hand. */
    private static final class MovableClock extends Clock {

        private Instant now;

        private MovableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }
}
