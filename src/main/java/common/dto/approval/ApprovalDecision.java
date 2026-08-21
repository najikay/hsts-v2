package common.dto.approval;

import java.io.Serializable;
import java.util.Objects;

/**
 * What a decision did (Common tier, E8.5 — F4.2).
 *
 * <p>The answer to both {@code EXAM_APPROVE} and {@code EXAM_REJECT}, and deliberately one
 * type for both: the screen's job afterwards is identical either way — say what happened,
 * drop the row from the queue, go back to it — so one shape means one rendering path.
 *
 * <p>It carries the decided {@link #row} rather than an acknowledgement, for the reason
 * {@code GRADE_OVERRIDE} answers with a refreshed review: a client that patched its own row
 * from an "ok" would be guessing at the new state and the new {@code lockVersion}, and would
 * eventually guess wrong.
 *
 * <p>{@link #selfApproved} is the F4.3 case travelling back so the screen can say it out
 * loud. The rule is that a coordinator <b>may</b> approve her own exam; the accountability is
 * that it is recorded, and telling her it was recorded is the honest half of that. The
 * authoritative record is the server's WARN log line, which is what acceptance case 4.6
 * inspects; this flag is the courtesy copy.
 *
 * @param row          the version as it now stands, re-read after the write
 * @param selfApproved {@code true} when the approver was also this version's author (F4.3);
 *                     always {@code false} on a rejection, where the case does not arise
 */
public record ApprovalDecision(ApprovalRow row, boolean selfApproved) implements Serializable {

    private static final long serialVersionUID = 1L;

    public ApprovalDecision {
        Objects.requireNonNull(row, "row");
    }

    /** @return the state the version landed in. */
    public ApprovalState state() {
        return row.state();
    }

    /** @return the version that was decided. */
    public long examVersionId() {
        return row.examVersionId();
    }

    /** @return the one-line confirmation the screen shows, naming the exam. */
    public String confirmation() {
        return switch (row.state()) {
            case APPROVED -> row.examName() + " is approved. It can be released now.";
            case REJECTED -> row.examName() + " was sent back to " + row.authorName() + ".";
            default -> row.examName() + " is now " + row.state().label().toLowerCase(
                    java.util.Locale.ROOT) + ".";
        };
    }
}
