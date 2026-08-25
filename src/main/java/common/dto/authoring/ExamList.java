package common.dto.authoring;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * The answer to {@code EXAM_LIST}: every exam the calling teacher wrote (Common tier, E7.10 —
 * F3.6, F9.2).
 *
 * <p>Newest exam first. The scope is <b>author-only</b> and it is applied in the query rather
 * than checked afterwards (section 2, S-12), so a coordinator sees the exams she wrote and not
 * the exams of the subject she coordinates — her read of somebody else's exam already exists and
 * is E8's {@code EXAM_PREVIEW_GET}.
 *
 * <h2>An empty list is a real answer</h2>
 *
 * <p>The screen has a designed panel for it, and there is deliberately no second empty state
 * meaning "she teaches nothing": a teacher who teaches nothing cannot reach this screen at all.
 * One empty state, so the panel says one true thing.
 *
 * <p>This is the payload that retired {@code MY_APPROVALS_GET} (contract section 8, executed
 * 2026-08-25). It is a strict superset of what {@code MyApprovals} showed, so the swap behind
 * route id {@code exams} lost nothing, and F4.2's "reason visible on the exam" keeps working
 * because
 * {@link ExamVersionRow#rejectedReason()} came across.
 *
 * @param rows the caller's exams, newest first; never {@code null}, defensively copied
 */
public record ExamList(List<ExamListRow> rows) implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Outbound: a strict copy of a list the server just assembled.
     *
     * <p>{@link List#copyOf} throws on a null element, which is exactly right here — a null row
     * is a defect in the assembler, not a payload to be refused politely, and the request
     * records' tolerance is for the other direction of travel.
     */
    public ExamList {
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
    }

    /** @return the answer for a teacher who has written no exams yet, which is a panel to draw
     *          rather than an error. */
    public static ExamList empty() {
        return new ExamList(List.of());
    }

    /** @return how many exams the caller has written. */
    public int rowCount() {
        return rows.size();
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }
}
