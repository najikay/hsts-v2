package server.features.exam;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link TimerService.Scheduler} that runs nothing until a test says so.
 *
 * <p>The point of testing an expiry timer is to prove it fires at the right moment and
 * does not fire at the wrong one. A real executor can only demonstrate the first, slowly
 * and flakily; this one demonstrates both, instantly, because the test decides when the
 * clock moves and when tasks run.
 *
 * <p>Cancelled tasks are remembered rather than removed, so a test can assert that a
 * re-arm actually cancelled the task it replaced — the subtle half of the extension path,
 * where a stale task firing would end an exam that has just been given more time.
 */
final class ManualScheduler implements TimerService.Scheduler {

    /** One scheduled task and what happened to it. */
    record Scheduled(Runnable task, Duration delay, boolean[] cancelled) {

        boolean isCancelled() {
            return cancelled[0];
        }
    }

    private final List<Scheduled> scheduled = new ArrayList<>();

    @Override
    public Handle schedule(Runnable task, Duration delay) {
        boolean[] cancelled = {false};
        scheduled.add(new Scheduled(task, delay, cancelled));
        return () -> cancelled[0] = true;
    }

    /** Runs every task that has not been cancelled, oldest first. */
    void runAll() {
        for (Scheduled entry : List.copyOf(scheduled)) {
            if (!entry.isCancelled()) {
                entry.task().run();
            }
        }
    }

    /** Runs the most recently scheduled live task, which is the one a re-arm just created. */
    void runLatest() {
        for (int index = scheduled.size() - 1; index >= 0; index--) {
            Scheduled entry = scheduled.get(index);
            if (!entry.isCancelled()) {
                entry.task().run();
                return;
            }
        }
    }

    /** @return every task ever scheduled, cancelled ones included. */
    List<Scheduled> all() {
        return List.copyOf(scheduled);
    }

    /** @return how many tasks are still live. */
    long liveCount() {
        return scheduled.stream().filter(entry -> !entry.isCancelled()).count();
    }

    /** @return how many were cancelled, which is how a re-arm proves it replaced something. */
    long cancelledCount() {
        return scheduled.stream().filter(Scheduled::isCancelled).count();
    }

    /** Forgets everything, so a test can assert about one phase at a time. */
    void clear() {
        scheduled.clear();
    }
}
