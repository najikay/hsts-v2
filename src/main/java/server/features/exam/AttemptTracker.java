package server.features.exam;

import common.dto.exam.IntegrityFlag;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * <b>The integration point between take-exam and the study bot</b> (Logic tier, E10.7 —
 * C-4, F6.8, F12.4).
 *
 * <p>This interface is the whole contract E16's {@code BotService} codes against. It asks
 * two questions and reports one event, and it deliberately exposes nothing else about
 * attempts: the bot has no business knowing about papers, answers or deadlines.
 *
 * <h2>The two C-4 branches, and which method serves each</h2>
 *
 * <ol>
 *   <li><b>Same course: locked.</b> The bot of course X is unavailable while the student
 *       has a live attempt at an exam of course X. {@link #activeAttemptFor(long, String)}
 *       answers it and hands back the attempt, so the message can say <i>which</i> exam and
 *       therefore when the lock lifts — a bare boolean would force the bot to say "you
 *       cannot do that" with no way to explain itself, which PRD §4.1 forbids.</li>
 *   <li><b>Another course: allowed, and reported.</b> The spec does not forbid it, so HSTS
 *       does not either. The student is warned first that continuing will inform the exam's
 *       teacher, and if she proceeds the bot calls
 *       {@link #reportCrossCourseBotUse(long, String, String)} — which raises the
 *       {@code INTEGRITY_ALERT} notification to the teacher running that execution and
 *       flags her row in the live monitor.</li>
 * </ol>
 *
 * <h2>How E16 uses it</h2>
 *
 * <pre>{@code
 * // 1. take the seam
 * public BotService(BotRepository bots, AttemptTracker attempts, ...) { ... }
 *
 * // 2. before answering, ask
 * attempts.activeAttemptFor(studentId, courseCode).ifPresent(sitting -> {
 *     throw lockedOut(sitting.examName());          // same-course lockout, C-4
 * });
 *
 * // 3. if she confirmed the notice for another course, say so
 * attempts.reportCrossCourseBotUse(studentId, courseCode, courseName);
 *
 * // 4. wire the real one where the server is assembled: it IS the attempt service
 * new BotService(bots, attemptService, ...);
 * }</pre>
 *
 * <p>The lifecycle callbacks are here for the same reason: a bot chat open on screen when
 * its course's exam starts should lock live rather than at the next message.
 */
public interface AttemptTracker {

    /** Told when a sitting starts and when it ends. */
    interface Listener {

        /** A student has just started an attempt. */
        default void attemptStarted(ActiveAttempt attempt) {
        }

        /** An attempt has ended, by submit or by expiry; its course's bot unlocks. */
        default void attemptFinished(ActiveAttempt attempt) {
        }
    }

    /**
     * @param studentId the student
     * @return the course codes she is currently mid-attempt in; empty when she is not
     *         sitting anything. Normally at most one, but the type does not promise that:
     *         nothing in the product forbids two live executions, and a bot that assumed
     *         one would pick the wrong lockout message the day it happened
     */
    Set<String> coursesInProgressFor(long studentId);

    /**
     * @param studentId the student
     * @return every attempt she has live right now
     */
    List<ActiveAttempt> activeAttemptsFor(long studentId);

    /**
     * The same-course lockout question (C-4).
     *
     * @param studentId  the student
     * @param courseCode the course whose bot she is trying to open
     * @return her live attempt at an exam of that course, or empty when there is none.
     *         Present means locked
     */
    Optional<ActiveAttempt> activeAttemptFor(long studentId, String courseCode);

    /**
     * The cross-course integrity report (C-4, F6.8) — what E16 calls after the student has
     * seen the notice and chosen to continue.
     *
     * <p>Raises the {@code INTEGRITY_ALERT} notification to the teacher running the
     * execution she is sitting and flags her row in the live monitor. Calling it when she
     * is not mid-attempt does nothing and is not an error: the bot should not have to check
     * first, and a race between her submitting and her sending a message must not become
     * an alert about an exam that is already over.
     *
     * <p>Repeat reports for the same attempt keep the first time. A student who asks the
     * bot forty questions gets one flag with the moment it started, which is the fact the
     * teacher is actually interested in.
     *
     * @param studentId  the student
     * @param courseCode the <em>other</em> course, whose bot she used
     * @param courseName that course's display name, which is what the flag shows
     * @return {@code true} when an alert was raised, i.e. she really was mid-attempt
     */
    boolean reportCrossCourseBotUse(long studentId, String courseCode, String courseName);

    /**
     * @param attemptId an attempt
     * @return its integrity flag, or empty when nothing fired for it
     */
    Optional<IntegrityFlag> flagOf(long attemptId);

    /**
     * Subscribes to sittings starting and ending.
     *
     * @param listener the subscriber; never removed, because the only subscriber is a
     *                 long-lived service assembled at boot
     */
    void addListener(Listener listener);
}
