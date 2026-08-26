package server.db.seed;

import org.hibernate.Session;
import server.db.entities.ExamExecution;
import server.db.entities.ExecutionStats;
import server.db.entities.ExecutionStatus;
import server.db.entities.Participation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Seed §9: the four executions (E2.15).
 *
 * <h2>Why the windows are relative and what that costs</h2>
 *
 * <p>Every window is resolved from the load anchor rather than hardcoded, which is the whole
 * reason {@link SeedTimes} exists. A database seeded a fortnight ago presents a live exam whose
 * window closed two weeks earlier, which is why {@link SeedMode#RESEED} is documented as the
 * standard pre-demo step.
 *
 * <p><b>"Relative to the load" has two meanings, and B-10 was the gap between them.</b> Executions
 * 1 and 2 are historical: a wall-clock hour on a date some days before the anchor is the right
 * shape for them, and {@link SeedTimes#dayOffsetAt} is what they use. Executions 3 and 4 are the
 * two the demo needs to be <em>happening</em>, and they now resolve from the anchor <em>instant</em>
 * through {@link SeedTimes#fromNow}: execution 4 opens 30 minutes ago and closes 90 minutes out, so
 * it is live; execution 3 opens 3 hours out and runs 2 hours, so it is scheduled. Execution 3 used
 * to be "today at 14:00", which is a description of the past for any load after lunch - see
 * {@link #SCHEDULED_OPENS}.
 *
 * <h2>Executions 1 and 4 run the same exam version, deliberately</h2>
 *
 * <p>Both release exam 1 v2. That is S-2, "the same exam can be taken out of the drawer many
 * times": one exam version, two releases, separate codes, windows, participants and statistics.
 * It is also why {@code exam_executions} carries no participation counter columns, per §5 and
 * ADR-016: counts are derived from {@code exam_attempts} while an execution is live and frozen
 * into JSON only at close, so two executions of one version can never contaminate each other's
 * numbers.
 *
 * <h2>The code uniqueness rule is a service rule, not a constraint</h2>
 *
 * <p>Codes are unique among <em>non-closed</em> executions (E9, C-1). Executions 3 and 4 are the
 * only non-closed ones here and their codes differ, so the rule holds on the seed as loaded. The
 * database does not enforce it and must not: closed executions keep their codes forever, and a
 * unique index would eventually refuse a legitimate new release.
 *
 * <h2>created_by is a stated rule, not invention</h2>
 *
 * <p>§9's rules table names the releasing teacher for each execution, so this section transcribes
 * rather than infers. That was not true before the 2026-08-20 amendment, and the difference is
 * the point: the rule lives in the document, where a reader can check it, instead of in a code
 * comment nobody reads.
 */
final class ExecutionsSection implements SeedSection {

    /**
     * Frozen only for the closed, fully graded execution. See the record for why.
     *
     * @param opensFromNow when non-null, the window opens this far from the load anchor and the
     *                     wall-clock fields are unused. Null means the historical form: a
     *                     wall-clock hour on a day relative to the anchor's <em>date</em>
     */
    private record SeedExecution(String exam, int examVersion, String code,
                                 int openDaysBefore, int openHour, int openMinute,
                                 int windowMinutes, ExecutionStatus status, String releasedBy,
                                 Duration opensFromNow) { }

    /**
     * Execution 4 opens half an hour ago, so it is live the moment the load finishes.
     *
     * <p>Half an hour rather than the hour it used to be, so that a demo has a plausible amount of
     * the window left rather than exactly half of it, and so the two relative windows below do not
     * touch.
     */
    private static final Duration LIVE_OPENED = Duration.ofMinutes(-30);

    /**
     * Execution 4's window is three and a half hours long. ⚑ <b>This is B-14's seed half.</b>
     *
     * <p>It used to be two hours, which is longer than the 75-minute paper it releases and
     * therefore looks safe — but the window <em>straddles</em> the anchor, so what a student
     * actually gets is whatever is left of it when she joins. Thirty minutes of it are already
     * gone at load time, and the demo does not begin the moment the loader finishes: a
     * walkthrough that reaches the take-exam step forty minutes in used to hand its student a
     * sitting shorter than the paper, and the attempt would close at the bell with her
     * countdown still showing time left. That is the fixture reproducing B-14 rather than
     * demonstrating S-2.
     *
     * <p>Thirty minutes behind the anchor and three hours ahead of it: the window is live the
     * instant the load finishes (the S-2 proof needs that), and it outlasts the paper by a
     * comfortable margin for the whole of any plausible defence slot, so the reconciliation in
     * {@code ExecutionContext.deadlineFor} never has to truncate anybody on seeded data.
     *
     * <p>The code fix stands on its own — a truncated sitting is now disclosed at entry and an
     * extension widens the window it needs — and this is still worth doing, because a fixture
     * that only ever exercises the sad path is a fixture nobody can demonstrate the happy one
     * from.
     */
    private static final int LIVE_WINDOW_MINUTES = 210;

    /**
     * Execution 3 opens three hours out. ⚑ <b>This is B-10's fix.</b>
     *
     * <p>It used to be {@code dayOffsetAt(0, 14, 0)} - 14:00 UTC on the anchor's <em>date</em> -
     * which is "scheduled for later today" only if the seed is loaded before 14:00 UTC. Loaded any
     * afternoon, the row is stored {@code SCHEDULED} with a window that closed hours earlier, and
     * one {@code ReleaseScheduler} tick takes it {@code SCHEDULED -> LIVE -> CLOSED} inside a single
     * pass. Nothing was wrong with the scheduler; the fixture was describing the past.
     *
     * <p>In local terms that made the demo fixture correct only before 17:00 Israel time, which is
     * not a safe assumption for a defence slot, and it took four acceptance cases with it - 5.6
     * (cancel a SCHEDULED release), 6.4 (enter a code before its open time), 10.5 (results for a
     * sitting with no attempts) and hardening item H14.1.
     *
     * <p>Four hours, not one: far enough ahead that a demo which starts late still finds the
     * sitting scheduled, and far enough past execution 4's close that the two never overlap and
     * the release list keeps showing one LIVE row and one SCHEDULED row.
     *
     * <p>It was three hours until B-14's seed fix, which is exactly where execution 4's widened
     * window now ends ({@link #LIVE_WINDOW_MINUTES}). Two adjacent windows touching is not an
     * overlap, but {@code SeedLoadedDbContract} asserts the stronger property on purpose — a
     * fixture whose two demo rows meet at an instant is one clock skew away from the release
     * list showing something nobody designed — so the scheduled sitting moved rather than the
     * assertion loosening.
     */
    private static final Duration SCHEDULED_OPENS = Duration.ofHours(4);

    private static final List<SeedExecution> EXECUTIONS = List.of(
            // Fully graded, stats frozen. The F9.3 histogram's data. Genuinely historical, so a
            // wall-clock hour on a past date is the right shape and stays.
            new SeedExecution("101101", 2, "4821", 14, 9, 0, 120,
                    ExecutionStatus.CLOSED, "dana.cohen", null),
            // Closed but awaiting grading: the T-8.2 fixture, nothing approved. Also historical.
            new SeedExecution("202101", 1, "7390", 3, 10, 0, 90,
                    ExecutionStatus.CLOSED, "avi.mizrahi", null),
            // Scheduled, opening later today: now+3h for two hours. Relative, per B-10.
            new SeedExecution("202201", 1, "5164", 0, 0, 0, 120,
                    ExecutionStatus.SCHEDULED, "michal.sharon", SCHEDULED_OPENS),
            // Live right now: the S-2 proof, and the take-exam demo's target. now-30m to now+3h.
            new SeedExecution("101101", 2, "2075", 0, 0, 0, LIVE_WINDOW_MINUTES,
                    ExecutionStatus.LIVE, "dana.cohen", LIVE_OPENED));

    /**
     * §9.1's frozen participation, from its own eight attempt rows.
     *
     * <p>Seven SUBMITTED and one TIMED_OUT, so started 8, finished 7, timed out 1. Frozen at
     * close (S-21) rather than counted live, which is what the JSON column is for.
     */
    private static final Participation EXECUTION_1_PARTICIPATION = new Participation(8, 7, 1);

    /**
     * §9.1's frozen statistics, computed from the <em>final</em> column.
     *
     * <p>Finals are 45, 55, 60, 70, 75, 85, 90, 100. <b>The standard deviation is the population
     * form</b>, divisor n: the class is the whole population, not a sample of one. That gives
     * exactly 17.5, since the squared deviations from 72.5 sum to 2450 and 2450/8 = 306.25.
     * The sample form would give 18.71, and E14 recomputing with the wrong divisor would
     * disagree with these numbers by about a point and look like a bug rather than a convention.
     *
     * <p>Deciles are ten buckets, 0-9 through 90-100, which is what {@code ScoreStatistics}
     * produces. §9.1 lists only the six that are populated.
     */
    private static final ExecutionStats EXECUTION_1_STATS = new ExecutionStats(
            72.5, 72.5, 17.5, 45, 100, 0.875,
            List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2));

    @Override
    public String name() {
        return "9 executions";
    }

    @Override
    public void load(SeedContext context) {
        Session session = context.session();
        int inserted = 0;

        for (SeedExecution execution : EXECUTIONS) {
            if (!SeedLookup.findExecutionByCode(session, execution.code()).isEmpty()) {
                continue;
            }
            long examVersionId = SeedLookup.requireExamVersionId(
                    session, execution.exam(), execution.examVersion());

            Instant opens = opensAt(context, execution);
            Instant closes = closesAt(context, execution, opens);

            ExamExecution row = new ExamExecution(examVersionId, execution.code(), opens, closes,
                    execution.status(),
                    SeedLookup.requireUserId(session, execution.releasedBy()));

            if (execution.code().equals("4821")) {
                row.setParticipation(EXECUTION_1_PARTICIPATION);
                row.setStats(EXECUTION_1_STATS);
            }

            session.persist(row);
            inserted++;
        }

        context.recordInserts("exam_executions", inserted);
    }

    /**
     * Where the window opens: an offset from the anchor instant, or a wall-clock hour on a date.
     *
     * <p>The two forms are not interchangeable and the difference is what B-10 was.
     * {@link SeedTimes#dayOffsetAt} discards the anchor's time of day, which is exactly right for a
     * sitting that happened a fortnight ago and exactly wrong for one that has not happened yet: it
     * describes a wall-clock hour that may already have gone past. Anything the demo needs to be in
     * the future takes {@link SeedTimes#fromNow}.
     */
    private static Instant opensAt(SeedContext context, SeedExecution execution) {
        return execution.opensFromNow() != null
                ? context.times().fromNow(execution.opensFromNow())
                : context.times().dayOffsetAt(-execution.openDaysBefore(),
                        execution.openHour(), execution.openMinute());
    }

    /**
     * Where it closes: always its own length after it opened.
     *
     * <p>One expression for all four now, where the live window used to be resolved from the anchor
     * a second time. Two independent {@code fromNow} calls made the live window's <em>length</em> a
     * consequence of two offsets rather than a stated duration, so the shape assertion in
     * {@code SeedLoadedDbContract} was checking arithmetic instead of a decision.
     */
    private static Instant closesAt(SeedContext context, SeedExecution execution, Instant opens) {
        return opens.plus(Duration.ofMinutes(execution.windowMinutes()));
    }
}
