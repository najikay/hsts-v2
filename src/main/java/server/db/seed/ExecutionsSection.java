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
 * Seed §9: the seven executions (E2.15).
 *
 * <h2>Why the windows are relative and what that costs</h2>
 *
 * <p>Every window is resolved from the load anchor rather than hardcoded, which is the whole
 * reason {@link SeedTimes} exists. A database seeded a fortnight ago presents a live exam whose
 * window closed two weeks earlier, which is why {@link SeedMode#RESEED} is documented as the
 * standard pre-demo step.
 *
 * <p><b>"Relative to the load" has two meanings, and B-10 was the gap between them.</b> Executions
 * 1, 2 and 5 are historical: a wall-clock hour on a date some days before the anchor is the right
 * shape for them, and {@link SeedTimes#dayOffsetAt} is what they use. Executions 3 and 4 are the
 * two the demo needs to be <em>happening</em>, and they now resolve from the anchor <em>instant</em>
 * through {@link SeedTimes#fromNow}: execution 4 opens 30 minutes ago and closes 90 minutes out, so
 * it is live; execution 3 opens 3 hours out and runs 2 hours, so it is scheduled. Execution 3 used
 * to be "today at 14:00", which is a description of the past for any load after lunch - see
 * {@link #SCHEDULED_OPENS}.
 *
 * <h2>⚑ U-43: two more frozen sittings, because reports had one row to compare</h2>
 *
 * <p>2026-08-30, live session. E15's report reads only sittings that are CLOSED <b>and</b> carry
 * frozen statistics, which was one sitting: {@code 4821}. {@code 7390} and {@code 3318} are
 * closed and unmarked, {@code 5164} has not opened and {@code 2075} is running, so all four are
 * correctly excluded and the principal's three dimensions had one row between them. A report
 * screen whose every answer is a single row cannot demonstrate a comparison, and it cannot show
 * that the exclusion rule is doing anything either, because there is nothing on the other side
 * of it.
 *
 * <p>{@code 6120} is exam 4 released a week earlier, so course 21 and {@code avi.mizrahi} each
 * acquire a reportable sitting while {@code 7390} stays unmarked. {@code 7745} is the Biology
 * exam, whose author, course and subject are all new in U-42, so BY_TEACHER and BY_COURSE each
 * gain a third entry with data behind it. <b>BY_STUDENT is where a real multi-row comparison
 * lives:</b> {@code noa.friedman} and {@code omer.katz} sat all three frozen sittings, in three
 * different courses.
 *
 * <h2>Executions 1, 4 and 5 run the same exam version, deliberately</h2>
 *
 * <p>All three release exam 1 v2. That is S-2, "the same exam can be taken out of the drawer many
 * times": one exam version, three releases, separate codes, windows, participants and statistics.
 * It is also why {@code exam_executions} carries no participation counter columns, per §5 and
 * ADR-016: counts are derived from {@code exam_attempts} while an execution is live and frozen
 * into JSON only at close, so two executions of one version can never contaminate each other's
 * numbers.
 *
 * <h2>The code uniqueness rule is a service rule, not a constraint</h2>
 *
 * <p>Codes are unique among <em>non-closed</em> executions (E9, C-1). Executions 3 and 4 are the
 * only non-closed ones here and their codes differ, so the rule holds on the seed as loaded.
 * Execution 5 is CLOSED and therefore outside the rule entirely, which is the same reason 1 and
 * 2 are. The database does not enforce it and must not: closed executions keep their codes
 * forever, and a unique index would eventually refuse a legitimate new release.
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
                    ExecutionStatus.LIVE, "dana.cohen", LIVE_OPENED),
            // ⚑ U-34. Closed yesterday, nothing approved, and released by dana.cohen: the demo
            // teacher's own awaiting-grading sitting. Her Grading screen read "Nothing to grade"
            // on a freshly seeded database because 4821 is fully approved and 7390 is avi's, and
            // both of those are the queue's rules working rather than a defect. Historical, so
            // the wall-clock form, exactly as 1 and 2. No participation and no stats: freezing
            // happens once grading is done (§9.4), and this one's has not started.
            new SeedExecution("101101", 2, "3318", 1, 9, 0, 90,
                    ExecutionStatus.CLOSED, "dana.cohen", null),
            // ⚑ U-43. Exam 4 released a week before 7390 did, closed, fully graded and frozen.
            // The reports screen read only 4821 before this: E15 compares CLOSED sittings that
            // carry frozen statistics, and there was exactly one in the dataset. This one gives
            // course 21 and avi.mizrahi a reportable sitting while 7390 stays what it is, so
            // "reports read only what is finished" is visible rather than asserted. Historical,
            // so the wall-clock form. Its window is exactly as long as its paper, which is a
            // shape none of the other four had.
            new SeedExecution("202101", 1, "6120", 7, 10, 0, 60,
                    ExecutionStatus.CLOSED, "avi.mizrahi", null),
            // ⚑ U-43. The Biology sitting: a third frozen record, on a course, a teacher and a
            // subject that are all new in U-42, and on a paper whose points are not flat.
            new SeedExecution("303101", 1, "7745", 5, 9, 0, 60,
                    ExecutionStatus.CLOSED, "galit.stern", null));

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

    /**
     * §9.5's frozen participation and statistics for {@code 6120} ⚑ (U-43).
     *
     * <p>Six SUBMITTED and nobody timed out. Finals are 30, 40, 45, 55, 70, 90 - no override on
     * this sitting, so every final equals its auto. They sum to 330, so the mean is exactly
     * <b>55</b>, which is also the pass mark, so three of the six are at or above it and the pass
     * rate is exactly <b>0.5</b>. The median is (45 + 55) / 2 = <b>50</b>. Population sigma,
     * divisor n, the same convention §9.1 fixes: Σ(x−55)² = 2400, so σ = √(2400/6) = √400 =
     * exactly <b>20</b>. Every figure is hand-checkable, which is what E12.4 asks for, and
     * {@code SeedDatasetContract} recomputes all of them with {@code ScoreStatistics} rather than
     * trusting this list.
     *
     * <p><b>Deliberately the weak sitting.</b> 4821 reads mean 72.5, σ 17.5, pass 0.875; this
     * reads 55, 20 and 0.5. A report putting the two side by side shows two genuinely different
     * classes rather than two roundings of one.
     */
    private static final Participation EXECUTION_6_PARTICIPATION = new Participation(6, 6, 0);

    private static final ExecutionStats EXECUTION_6_STATS = new ExecutionStats(
            55, 50, 20, 30, 90, 0.5,
            List.of(0, 0, 0, 1, 2, 1, 0, 1, 0, 1));

    /**
     * §9.6's frozen participation and statistics for {@code 7745} ⚑ (U-43).
     *
     * <p>Five SUBMITTED and nobody timed out, from a roster of six: {@code maya.levi} is enrolled
     * in Biology and did not sit it, so the roster and the attempt list differ here where §9.1's
     * are identical. Finals are 50, 55, 70, 80, 100, summing to 355, so the mean is exactly
     * <b>71</b>; five scores, so the median is the third, <b>70</b>. Σ(x−71)² = 1620, so
     * σ = √(1620/5) = √324 = exactly <b>18</b>. Four of the five are at or above the pass mark of
     * 55, so the pass rate is exactly <b>0.8</b>.
     *
     * <p><b>No two frozen sittings share a number.</b> 4821 is 8 students at 72.5 / 17.5 / 0.875,
     * 6120 is 6 at 55 / 20 / 0.5 and this is 5 at 71 / 18 / 0.8, so no report row can be mistaken
     * for another and no aggregate over the three can be reproduced by weighting them wrongly.
     */
    private static final Participation EXECUTION_7_PARTICIPATION = new Participation(5, 5, 0);

    private static final ExecutionStats EXECUTION_7_STATS = new ExecutionStats(
            71, 70, 18, 50, 100, 0.8,
            List.of(0, 0, 0, 0, 0, 2, 0, 1, 1, 1));

    /**
     * What a closed, fully graded sitting freezes at the point its last grade is approved
     * (S-21, S-25).
     *
     * @param participation the three attempt counts
     * @param stats         the statistics computed from the <em>final</em> scores
     */
    private record Frozen(Participation participation, ExecutionStats stats) { }

    /**
     * The sittings that carry frozen columns, by execution code.
     *
     * <p><b>A map rather than the {@code if (code.equals("4821"))} this used to be</b>, which was
     * correct while exactly one sitting was frozen and would have silently kept freezing exactly
     * one when U-43 added two more. A sitting absent from this map stores null in both columns,
     * which is what "grading has not finished" means: {@code 7390} and {@code 3318} are closed
     * and unmarked, {@code 5164} has not opened and {@code 2075} is running.
     */
    private static final java.util.Map<String, Frozen> FROZEN = java.util.Map.of(
            "4821", new Frozen(EXECUTION_1_PARTICIPATION, EXECUTION_1_STATS),
            "6120", new Frozen(EXECUTION_6_PARTICIPATION, EXECUTION_6_STATS),
            "7745", new Frozen(EXECUTION_7_PARTICIPATION, EXECUTION_7_STATS));

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

            Frozen frozen = FROZEN.get(execution.code());
            if (frozen != null) {
                row.setParticipation(frozen.participation());
                row.setStats(frozen.stats());
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
