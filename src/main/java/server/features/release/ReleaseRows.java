package server.features.release;

import common.dto.exam.MonitorCounts;
import common.dto.release.ReleasableVersion;
import common.dto.release.ReleaseRow;
import common.dto.release.ReleaseState;
import server.db.projections.ExamVersionContext;
import server.db.projections.ExecutionContext;
import server.db.projections.ParticipationCounts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turning stored releases into the rows a teacher sees (Logic tier, E9.4 — F5.4).
 *
 * <p>A mapper with one interesting decision in it, which is why it is a class of its own
 * rather than three private methods: the derived state. Everything else here is copying
 * fields, and copying fields in one place is how the list, the create answer and every
 * {@code PUSH_EXECUTION_STATUS} stay the same shape.
 *
 * <h2>The state is derived, and the derivation is asymmetric</h2>
 *
 * <p>A release carries a status column <em>and</em> a window, and they can disagree for a
 * few seconds at either end because the transition is scheduled work. The rule this class
 * applies is: <b>never show a release as more open than the server will actually treat it</b>.
 *
 * <ul>
 *   <li><b>Scheduled whose opening moment has passed stays Scheduled.</b> The status column
 *       is what {@code EXAM_JOIN} reads, so until the scheduled check has flipped it, a
 *       student typing the code is refused. Showing Live would have a teacher read the code
 *       out to a room that cannot get in, which is the single worst thing this screen can
 *       do.</li>
 *   <li><b>Live whose window has ended reads Closed</b>, before the check gets to it. Here
 *       the server is <em>already</em> refusing joins ({@code ExecutionContext.isOpenAt}
 *       compares the window, not only the column) and every attempt's own timer has fired.
 *       Reporting Live would be the screen claiming something is running that nothing can be
 *       done with, and would offer a "close early" button whose work is already finished.</li>
 * </ul>
 *
 * <p>Extensions are part of that second answer: the end compared against is
 * {@link ExecutionContext#effectiveCloseAt()}, so fifteen minutes added to a live release
 * keeps it Live for fifteen more minutes on the teacher's screen, which is what she just
 * asked for (S-20).
 */
final class ReleaseRows {

    private ReleaseRows() {
    }

    /**
     * The state this release should be shown in, right now (F5.4).
     *
     * @param context the stored release
     * @param now     the server's clock reading
     * @return the derived state
     */
    static ReleaseState stateOf(ExecutionContext context, Instant now) {
        return switch (context.status()) {
            case CANCELLED -> ReleaseState.CANCELLED;
            case CLOSED -> ReleaseState.CLOSED;
            // Never optimistic: students cannot enter until the column says LIVE.
            case SCHEDULED -> ReleaseState.SCHEDULED;
            // Already effectively over, whatever the sweeper has got round to.
            case LIVE -> now.isBefore(context.effectiveCloseAt())
                    ? ReleaseState.LIVE
                    : ReleaseState.CLOSED;
        };
    }

    /**
     * One row.
     *
     * @param context the stored release
     * @param counts  its participation, or {@code null} for a release nobody joined
     * @param now     the server's clock reading, for the derived state
     * @return the wire row
     */
    static ReleaseRow toRow(ExecutionContext context, ParticipationCounts counts, Instant now) {
        return new ReleaseRow(context.executionId(), context.examVersionId(),
                context.examName(), context.courseCode(),
                context.courseName(), context.code(), context.openAt(), context.closeAt(),
                context.extraMinutes(), context.durationMinutes(),
                stateOf(context, now), toWire(counts));
    }

    /**
     * A whole list, with participation looked up per row from one batched read.
     *
     * @param contexts the stored releases, already ordered
     * @param counts   execution id → counts; releases nobody joined are absent by convention
     * @param now      the server's clock reading
     * @return the wire rows, in the order given
     */
    static List<ReleaseRow> toRows(List<ExecutionContext> contexts,
                                   Map<Long, ParticipationCounts> counts, Instant now) {
        List<ReleaseRow> rows = new ArrayList<>(contexts.size());
        for (ExecutionContext context : contexts) {
            rows.add(toRow(context, counts.get(context.executionId()), now));
        }
        return rows;
    }

    /**
     * One picker row (F5.1).
     *
     * @param version       the approved version
     * @param questionCount how many questions are on it; 0 when the count was not asked for
     * @return the wire row
     */
    static ReleasableVersion toOption(ExamVersionContext version, int questionCount) {
        return new ReleasableVersion(version.examVersionId(), version.examDisplayId(),
                version.examName(), version.versionNo(), version.courseCode(),
                version.courseName(), version.durationMinutes(), questionCount);
    }

    /**
     * Who this release belongs to: the teacher who released it, and the exam's author.
     *
     * <p>The same pair {@link ExecutionContext#isOwnedBy(long)} answers, listed rather than
     * asked, because the push has to reach both and the guard has to admit both. Deriving
     * the recipients from the same fact as the guard means a teacher who may act on a
     * release is exactly a teacher who is told when it changes.
     *
     * @param context the release
     * @return the owners' ids, without duplicates when they are the same person
     */
    static List<Long> ownersOf(ExecutionContext context) {
        if (context.executingTeacherId() == context.authorId()) {
            return List.of(context.executingTeacherId());
        }
        return List.of(context.executingTeacherId(), context.authorId());
    }

    private static MonitorCounts toWire(ParticipationCounts counts) {
        return counts == null
                ? MonitorCounts.NONE
                : new MonitorCounts(counts.started(), counts.finished(), counts.timedOut());
    }

}
