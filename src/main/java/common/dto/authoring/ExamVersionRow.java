package common.dto.authoring;

import common.dto.approval.ApprovalState;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * One version of one exam, as the exam list shows it (Common tier, E7.10 — F3.6, F4.2).
 *
 * <p>The expandable half of {@link ExamListRow}: every version an exam has ever had, newest
 * first, drafts included. Retaining and listing them is C-2 and F3.5, and it is what makes the
 * builder's history panel (E7.14) a read of the same rows the list already showed.
 *
 * <h2>{@code rejectedReason} is on the row, not only in a dialog</h2>
 *
 * <p>F4.2 requires the reason to be visible <b>on the exam</b>, which a notification the teacher
 * has dismissed cannot provide. It is {@code ""} unless {@link #state()} is {@code REJECTED},
 * never {@code null}, so a screen never has to decide which kind of empty it is looking at. It
 * also carries E8's superseded sentence, which is a rejection with a different cause and the
 * same need to be readable a week later.
 *
 * <h2>This row is why {@code MY_APPROVALS_GET} retired</h2>
 *
 * <p>Contract section 8: {@code ApprovalRow} carried {@code state}, {@code rejectedReason},
 * {@code questionCount}, {@code durationMinutes} and {@code versionNo}, and every one of them is
 * here. The two facts that do not cross over are {@code submittedAt}, replaced by
 * {@link #createdAt()}, and {@code selfAuthored}, which on a screen that only ever shows the
 * caller's own exams is true on every row and therefore says nothing. The verb was removed in the
 * same change as the screen swap, so there was never a window where two overlapping reads of one
 * fact were both live.
 *
 * @param examVersionId   this version's id, and what {@link ExamVersionRequest} opens
 * @param versionNo       which version this is, 1-based ({@code uq_exam_versions_no})
 * @param state           where it sits in F3.6's lifecycle, driving the row's status chip
 * @param rejectedReason  {@code ""} unless {@code state} is {@code REJECTED}; never {@code null}
 * @param questionCount   how many questions are on it, so the list need not carry compositions
 * @param durationMinutes how long students get
 * @param createdAt       when this version was written, UTC (ADR-010)
 * @param lockVersion     the optimistic token, so an action can be taken straight off the list
 *                        without opening the version first
 */
public record ExamVersionRow(long examVersionId,
                             int versionNo,
                             ApprovalState state,
                             String rejectedReason,
                             int questionCount,
                             int durationMinutes,
                             Instant createdAt,
                             int lockVersion) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Outbound: a null in any of these is a server bug and surfaces as one. */
    public ExamVersionRow {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(rejectedReason, "rejectedReason");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** @return {@code true} when this version may still be changed, which is {@code DRAFT} and
     *          nothing else (F3.6). What the list greys its Edit action off. */
    public boolean isEditable() {
        return state == ApprovalState.DRAFT;
    }

    /** @return {@code true} when there is a rejection sentence worth showing on the row (F4.2). */
    public boolean hasRejectedReason() {
        return !rejectedReason.isBlank();
    }
}
