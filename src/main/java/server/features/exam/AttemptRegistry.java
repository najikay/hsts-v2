package server.features.exam;

import common.dto.exam.IntegrityFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is sitting what, right now (Logic tier, E10.7 — C-4).
 *
 * <p>The state behind {@link AttemptTracker}, lifted into its own class so the bookkeeping
 * is unit-testable without a database, a socket or a service: start two attempts, finish
 * one, assert what the bot would be told. It is an in-memory index, deliberately, on the
 * same reasoning as {@code EditLockService}'s map — it is a cache of a fact the database
 * owns, rebuilt from {@code exam_attempts} at boot, and nothing security-relevant is
 * decided from it alone.
 *
 * <h2>Keyed by student, and by attempt</h2>
 *
 * <p>Two maps, because the two questions are asked from different directions: the bot asks
 * "what is <em>this student</em> sitting", and the monitor asks "does <em>this attempt</em>
 * carry a flag". Deriving either from the other would mean a scan on a path that runs on
 * every chat message and on every monitor repaint.
 *
 * <p>Integrity flags outlive the attempt on purpose: the alert fired during a live exam,
 * and the teacher opening the monitor after everyone has submitted must still see who
 * tripped it. They are evicted only when the execution's monitor stops being interesting,
 * which for a server that restarts between exam days is "at restart".
 */
public final class AttemptRegistry {

    private static final Logger log = LoggerFactory.getLogger(AttemptRegistry.class);

    private final Map<Long, ActiveAttempt> byAttempt = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> byStudent = new ConcurrentHashMap<>();
    private final Map<Long, IntegrityFlag> flags = new ConcurrentHashMap<>();
    private final List<AttemptTracker.Listener> listeners = new ArrayList<>();

    // ===================== Lifecycle =====================================

    /**
     * Records that a sitting has begun and tells the listeners.
     *
     * <p>Idempotent: re-registering the same attempt (a resume, a re-arm at boot) replaces
     * the entry and does <b>not</b> fire {@code attemptStarted} again, because a bot chat
     * must not re-lock itself every time a student's client reconnects.
     *
     * @param attempt the sitting
     * @return {@code true} when this was genuinely new
     */
    public boolean started(ActiveAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        boolean isNew = byAttempt.put(attempt.attemptId(), attempt) == null;
        byStudent.computeIfAbsent(attempt.studentId(), key -> ConcurrentHashMap.newKeySet())
                .add(attempt.attemptId());
        if (isNew) {
            log.debug("Student {} is now sitting {} (course {})",
                    attempt.studentId(), attempt.examName(), attempt.courseCode());
            fire(listener -> listener.attemptStarted(attempt));
        }
        return isNew;
    }

    /**
     * Records that a sitting has ended, by submit or by expiry, and tells the listeners.
     *
     * <p>The course's bot unlocks here. Any integrity flag stays: the teacher looking at
     * the monitor after the exam must still see it.
     *
     * @param attemptId the attempt
     * @return the sitting that ended, or empty when it was not being tracked
     */
    public Optional<ActiveAttempt> finished(long attemptId) {
        ActiveAttempt attempt = byAttempt.remove(attemptId);
        if (attempt == null) {
            return Optional.empty();
        }
        Set<Long> mine = byStudent.get(attempt.studentId());
        if (mine != null) {
            mine.remove(attemptId);
            if (mine.isEmpty()) {
                // Otherwise every student who ever sat an exam leaves an empty set behind
                // and the map grows for the life of the server.
                byStudent.remove(attempt.studentId(), mine);
            }
        }
        log.debug("Student {} finished {}", attempt.studentId(), attempt.examName());
        fire(listener -> listener.attemptFinished(attempt));
        return Optional.of(attempt);
    }

    /** Subscribes to sittings starting and ending. */
    public void addListener(AttemptTracker.Listener listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    // ===================== Queries =======================================

    /** @return the course codes this student is currently mid-attempt in. */
    public Set<String> coursesInProgressFor(long studentId) {
        Set<String> courses = new LinkedHashSet<>();
        for (ActiveAttempt attempt : activeAttemptsFor(studentId)) {
            courses.add(attempt.courseCode());
        }
        return Set.copyOf(courses);
    }

    /** @return every attempt this student has live, oldest first. */
    public List<ActiveAttempt> activeAttemptsFor(long studentId) {
        Set<Long> mine = byStudent.get(studentId);
        if (mine == null || mine.isEmpty()) {
            return List.of();
        }
        return mine.stream()
                .map(byAttempt::get)
                .filter(Objects::nonNull)
                .sorted(java.util.Comparator.comparing(ActiveAttempt::startedAt)
                        .thenComparing(ActiveAttempt::attemptId))
                .toList();
    }

    /** @return her live attempt at an exam of {@code courseCode}, or empty (C-4 lockout). */
    public Optional<ActiveAttempt> activeAttemptFor(long studentId, String courseCode) {
        return activeAttemptsFor(studentId).stream()
                .filter(attempt -> attempt.isSameCourseAs(courseCode))
                .findFirst();
    }

    /** @return the attempt by id, while it is live. */
    public Optional<ActiveAttempt> byId(long attemptId) {
        return Optional.ofNullable(byAttempt.get(attemptId));
    }

    /** @return how many sittings are being tracked. */
    public int activeCount() {
        return byAttempt.size();
    }

    // ===================== Integrity flags ===============================

    /**
     * Flags an attempt, keeping the first report.
     *
     * <p>A student who asks the bot forty questions produces one flag carrying the moment
     * it started, because "when did this begin" is the fact a teacher acts on, and a flag
     * that kept updating would quietly erase it.
     *
     * @param attemptId  the attempt
     * @param courseCode the other course whose bot was used
     * @param courseName that course's display name
     * @param at         when it happened
     * @return {@code true} when this was the first report for that attempt
     */
    public boolean flag(long attemptId, String courseCode, String courseName, Instant at) {
        return flags.putIfAbsent(attemptId,
                new IntegrityFlag(courseCode, courseName, at)) == null;
    }

    /** @return the flag on this attempt, or empty. */
    public Optional<IntegrityFlag> flagOf(long attemptId) {
        return Optional.ofNullable(flags.get(attemptId));
    }

    /** @return how many attempts carry a flag. */
    public int flagCount() {
        return flags.size();
    }

    /** Drops every flag. Used when a monitor's execution is closed and archived. */
    public void clearFlags() {
        flags.clear();
    }

    private void fire(java.util.function.Consumer<AttemptTracker.Listener> event) {
        List<AttemptTracker.Listener> snapshot;
        synchronized (listeners) {
            snapshot = List.copyOf(listeners);
        }
        for (AttemptTracker.Listener listener : snapshot) {
            try {
                event.accept(listener);
            } catch (RuntimeException e) {
                // A misbehaving subscriber must not stop an exam from starting or ending.
                log.error("Attempt lifecycle listener {} threw",
                        listener.getClass().getName(), e);
            }
        }
    }
}
