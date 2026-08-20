package common.dto.exam;

import java.io.Serializable;

/**
 * One cell of the answer-summary grid (Common tier, E10.13/E10.14 — F6.9, F6.10, F6.4).
 *
 * <p>The same row shape serves three moments: the confirm dialog before a manual submit
 * (where a chip is clickable and jumps to its question), the Submitted screen, and the
 * Time Up takeover. One shape, because they are the same information with different tone,
 * and a second one would drift.
 *
 * <p>It says whether the question was answered and nothing about whether the answer was
 * right — this is a summary a student sees while the paper is still unmarked, and there is
 * no correctness anywhere in this package to leak into it.
 *
 * @param ordinal   position in the paper, 1-based; what the chip is labelled with
 * @param displayId the 5-digit question id (S-8), for the tooltip
 * @param answered  whether a choice was saved for it
 */
public record AttemptSummaryEntry(int ordinal, String displayId, boolean answered)
        implements Serializable {

    private static final long serialVersionUID = 1L;

    public AttemptSummaryEntry {
        displayId = displayId == null ? "" : displayId;
    }
}
