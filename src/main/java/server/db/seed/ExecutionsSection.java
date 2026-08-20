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
 * reason {@link SeedTimes} exists. Execution 3 is "today at 14:00" and execution 4 straddles
 * "right now", and those two are what the release demo and the take-exam demo need. A database
 * seeded a fortnight ago presents a live exam whose window closed two weeks earlier, which is
 * why {@link SeedMode#RESEED} is documented as the standard pre-demo step.
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

    /** Frozen only for the closed, fully graded execution. See the record for why. */
    private record SeedExecution(String exam, int examVersion, String code,
                                 int openDaysBefore, int openHour, int openMinute,
                                 int windowMinutes, ExecutionStatus status, String releasedBy,
                                 boolean liveAroundNow) { }

    private static final List<SeedExecution> EXECUTIONS = List.of(
            // Fully graded, stats frozen. The F9.3 histogram's data.
            new SeedExecution("101101", 2, "4821", 14, 9, 0, 120,
                    ExecutionStatus.CLOSED, "dana.cohen", false),
            // Closed but awaiting grading: the T-8.2 fixture, nothing approved.
            new SeedExecution("202101", 1, "7390", 3, 10, 0, 90,
                    ExecutionStatus.CLOSED, "avi.mizrahi", false),
            // "Today", for the live release demo.
            new SeedExecution("202201", 1, "5164", 0, 14, 0, 120,
                    ExecutionStatus.SCHEDULED, "michal.sharon", false),
            // Live right now: the S-2 proof, and the take-exam demo's target.
            new SeedExecution("101101", 2, "2075", 0, 0, 0, 0,
                    ExecutionStatus.LIVE, "dana.cohen", true));

    /** Execution 4 straddles the anchor: an hour behind to an hour ahead. */
    private static final Duration LIVE_OPENS_BEFORE = Duration.ofHours(-1);
    private static final Duration LIVE_CLOSES_AFTER = Duration.ofHours(1);

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

    private static Instant opensAt(SeedContext context, SeedExecution execution) {
        return execution.liveAroundNow()
                ? context.times().fromNow(LIVE_OPENS_BEFORE)
                : context.times().dayOffsetAt(-execution.openDaysBefore(),
                        execution.openHour(), execution.openMinute());
    }

    private static Instant closesAt(SeedContext context, SeedExecution execution, Instant opens) {
        return execution.liveAroundNow()
                ? context.times().fromNow(LIVE_CLOSES_AFTER)
                : opens.plus(Duration.ofMinutes(execution.windowMinutes()));
    }
}
