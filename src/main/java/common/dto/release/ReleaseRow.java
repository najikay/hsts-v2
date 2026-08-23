package common.dto.release;

import common.dto.exam.MonitorCounts;

import java.io.Serializable;
import java.time.Instant;

/**
 * One release, as the teacher's list shows it (Common tier, E9 — F5.4).
 *
 * <p>Everything one line of the Release Manager needs, and the same record is what
 * {@code PUSH_EXECUTION_STATUS} carries when anything about that line changes. A whole row
 * rather than a delta, on the same reasoning as {@code ExecutionMonitor}: a list that
 * patched fields from events drifts the first time one is missed, and a release shown as
 * Scheduled twenty minutes after it opened is worse than one that is simply late, because a
 * teacher acts on it.
 *
 * <h2>The state is derived, and derived once</h2>
 *
 * <p>{@link #state} is the server's answer, computed from the stored status <em>and</em> the
 * window against the server's clock. The client never re-derives it. That matters because
 * the transition itself is scheduled work: a release whose opening moment has passed but
 * whose row has not been flipped yet is still Scheduled to the server, so it must look
 * Scheduled on screen too, or the teacher reads the code out to a room that cannot enter.
 *
 * <h2>The code is teacher-facing, always</h2>
 *
 * <p>{@link #code} never appears on a student's wire (S-17: it is delivered orally), and
 * nothing on this record travels to a student — the Release Manager is a teaching-role
 * screen and every verb behind it is role-gated and ownership-gated.
 *
 * @param executionId     the release
 * @param examVersionId   the pinned version being released (S-14)
 * @param examName        the name of the version that was released, so a row is labelled with
 *                        what the students actually saw even after the exam is renamed
 * @param courseCode      the two-character course code
 * @param courseName      the course's display name
 * @param code            the 4-character entry code (C-1), teacher-facing only
 * @param openAt          when the window opens (S-15)
 * @param closeAt         when it shuts, <b>without</b> extensions
 * @param extraMinutes    minutes granted by extensions so far (S-20)
 * @param durationMinutes how long one student gets, without extensions
 * @param state           the derived state (F5.4)
 * @param counts          participation, counted from attempts and never accumulated (S-21, §5)
 */
public record ReleaseRow(long executionId,
                         long examVersionId,
                         String examName,
                         String courseCode,
                         String courseName,
                         String code,
                         Instant openAt,
                         Instant closeAt,
                         int extraMinutes,
                         int durationMinutes,
                         ReleaseState state,
                         MonitorCounts counts) implements Serializable {

    private static final long serialVersionUID = 1L;

    public ReleaseRow {
        examName = examName == null ? "" : examName;
        courseCode = courseCode == null ? "" : courseCode;
        courseName = courseName == null ? "" : courseName;
        code = code == null ? "" : code;
        state = state == null ? ReleaseState.SCHEDULED : state;
        counts = counts == null ? MonitorCounts.NONE : counts;
    }

    /** @return when the window actually shuts, extensions included (S-20). */
    public Instant effectiveCloseAt() {
        return closeAt.plusSeconds(extraMinutes * 60L);
    }

    /** @return the duration one student actually gets, extensions included. */
    public int allottedMinutes() {
        return durationMinutes + extraMinutes;
    }

    /** @return {@code true} while students may be sitting it. */
    public boolean isLive() {
        return state.isLive();
    }

    /** @return {@code true} when this release may still be called off (F5.5). */
    public boolean canCancel() {
        return state.canCancel();
    }

    /** @return {@code true} when this release may be closed early (F5.5). */
    public boolean canCloseEarly() {
        return state.canCloseEarly();
    }

    /**
     * @return {@code true} when this release has a monitor worth opening, i.e. somebody has
     *         sat it. A monitor of a scheduled release is an empty table with nothing to say
     */
    public boolean hasParticipants() {
        return counts.started() > 0;
    }
}
