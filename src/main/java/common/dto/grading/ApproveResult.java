package common.dto.grading;

import java.io.Serializable;
import java.util.List;

/**
 * The answer to {@code GRADES_APPROVE} (Common tier, E12.2/E12.7 — §6).
 *
 * <p><b>Approval is idempotent.</b> Re-approving a grade that is already
 * {@link GradeState#APPROVED} counts in {@code alreadyApproved} and never errors: two teachers
 * on one execution, a double-click, a retry after a dropped socket, and a stale queue tab are
 * all ordinary, and an error on any of them would teach users to be afraid of the button.
 *
 * <p>{@code refused} is the honest half of a bulk approve: ids that were not this caller's to
 * approve are listed rather than failing the request, so approving twenty-eight grades of
 * which one belongs to another teacher's execution still approves twenty-seven.
 *
 * <p>Side effect worth knowing about: when an approval completes an execution — every grade in
 * it {@code APPROVED} — the server computes {@code ScoreStatistics} and freezes it into
 * {@code exam_executions.stats} <b>in the same transaction</b> (E12.4, "→ stored"). Statistics
 * are therefore never recomputed later from grades that may have moved, and nothing about that
 * write appears on this wire.
 *
 * @param approved        how many grades this call moved to {@code APPROVED}
 * @param alreadyApproved how many were already approved, and so were left alone
 * @param refused         ids the caller was not allowed to approve; never {@code null},
 *                        defensively copied
 */
public record ApproveResult(int approved, int alreadyApproved, List<Long> refused) implements Serializable {

    private static final long serialVersionUID = 1L;

    public ApproveResult {
        approved = Math.max(0, approved);
        alreadyApproved = Math.max(0, alreadyApproved);
        // List.copyOf yields an immutable, Serializable list — safe on the wire.
        refused = refused == null ? List.of() : List.copyOf(refused);
    }

    /** @return {@code true} when every id in the request was accepted or already approved. */
    public boolean isComplete() {
        return refused.isEmpty();
    }
}
