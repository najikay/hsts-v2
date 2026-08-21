package common.dto.approval;

import java.io.Serializable;
import java.util.List;

/**
 * The answer to {@code MY_APPROVALS_GET}: what happened to the exams I submitted
 * (Common tier, E8.6 — F4.2).
 *
 * <p>The author's side of the same story the coordinator's queue tells. F4.2 requires a
 * rejection reason to be "stored and pushed to the authoring teacher as a notification
 * <em>and</em> visible on the exam", and the second half of that sentence needs a surface
 * the teacher can open: a notification the reader dismissed is not a record.
 *
 * <p><b>Scope note for E7.</b> This is deliberately the narrow read — approval state and
 * rejection reasons for versions this caller wrote — and not an exam list. E7 owns the exam
 * list, its verb and its screen; when that lands, its richer payload absorbs this one and
 * this verb retires. Naming it after approvals rather than after exams is what keeps the two
 * from colliding in the meantime (docs/contracts/APPROVAL_WIRE_CONTRACT.md).
 *
 * @param rows the caller's own versions that have been submitted at least once, newest
 *             first, every state included
 */
public record MyApprovals(List<ApprovalRow> rows) implements Serializable {

    private static final long serialVersionUID = 1L;

    public MyApprovals {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    /** @return the answer for a teacher who has submitted nothing yet. */
    public static MyApprovals empty() {
        return new MyApprovals(List.of());
    }

    /** @return how many versions are listed. */
    public int size() {
        return rows.size();
    }

    /** @return {@code true} when nothing has been submitted yet. */
    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /** @return only the versions that were sent back, which is what the screen leads with. */
    public List<ApprovalRow> rejected() {
        return rows.stream().filter(row -> row.state().isRejected()).toList();
    }
}
