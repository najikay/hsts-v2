package common.dto.authoring;

import java.io.Serializable;
import java.util.List;

/**
 * The {@code EXAM_VERSION_SAVE} payload: metadata and composition, replaced together (Common
 * tier, E7.2/E7.3 — F3.1).
 *
 * <h2>A full replace, not a patch</h2>
 *
 * <p>Everything the version holds travels on every save, matching the storage rule
 * ARCHITECTURE §5 already fixed: "E7 composition updates are full-replace within one
 * transaction (delete rows + reinsert), so no reorder dance is ever needed". A partial save
 * would need a diff, and a diff of a list whose {@code ord} is unique per version
 * ({@code uq_exam_version_questions_ord}) is precisely the reorder dance that decision exists to
 * avoid. It also means the points rule is checked against the whole paper on every save rather
 * than against a fragment, which is what makes contract section 1's invariant hold on the write
 * path with no exceptions.
 *
 * <h2>Only a {@code DRAFT} is savable</h2>
 *
 * <p>A save against {@code PENDING}, {@code APPROVED} or {@code REJECTED} answers
 * {@code CONFLICT} and not {@code VALIDATION} (section 5.4): the request was well formed and the
 * world moved. Editing one of those is {@code EXAM_VERSION_REVISE}, which produces a new
 * {@code DRAFT} — the reason "edit" is never a lie here (F3.5, C-2).
 *
 * <p>{@code expectedLockVersion} is the same optimistic token
 * {@link ExamVersionAction#expectedLockVersion()} and {@code ExamApproveRequest} carry against
 * the same row. One row, one convention; a stale value answers {@code CONFLICT} rather than
 * overwriting a decision somebody else took while this screen was open.
 *
 * <h2>What the handler checks, and this record does not</h2>
 *
 * <p>Identical to {@link ExamCreateRequest}'s list, deliberately: {@code ExamValidator} is
 * shared by create and save <b>so the two cannot diverge</b> (E7.8), and the constants below are
 * the same numbers under the same names so a validator can cite either record and be right. The
 * compact constructor normalises and never throws, for the reason
 * {@link ExamCreateRequest#tolerantCopy(List)} gives.
 *
 * @param examVersionId       the {@code DRAFT} to overwrite; a version the caller did not author
 *                            answers {@code NOT_FOUND}, never {@code FORBIDDEN}
 * @param expectedLockVersion the {@code lockVersion} the caller was editing against
 * @param name                what the exam is called; stripped, never {@code null}-folded
 * @param durationMinutes     how long students get, {@link #MIN_DURATION_MINUTES}..{@link
 *                            #MAX_DURATION_MINUTES} (checked by the handler)
 * @param studentText         instructions printed on the paper, or {@code null}; blank is
 *                            {@code null}
 * @param teacherText         notes only staff ever read, or {@code null}; blank is {@code null}
 * @param questions           the WHOLE composition in paper order, {@code ord} being the index;
 *                            never {@code null} after construction, tolerantly copied
 */
public record ExamVersionSave(long examVersionId,
                              int expectedLockVersion,
                              String name,
                              int durationMinutes,
                              String studentText,
                              String teacherText,
                              List<QuestionPin> questions) implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Longest exam name the handler accepts.
     *
     * <p>An alias of {@link ExamCreateRequest#MAX_NAME_LENGTH} rather than a second literal.
     * The number is on both records because a validator shared by two verbs should be able to
     * cite the record it is validating; it is written down once because two literals that mean
     * one rule are a rule waiting to be half-changed.
     */
    public static final int MAX_NAME_LENGTH = ExamCreateRequest.MAX_NAME_LENGTH;

    /** Shortest exam, in minutes. Aliases {@link ExamCreateRequest#MIN_DURATION_MINUTES}. */
    public static final int MIN_DURATION_MINUTES = ExamCreateRequest.MIN_DURATION_MINUTES;

    /**
     * Longest exam, in minutes: eight hours (lead's ruling 3 of 2026-08-23). Aliases
     * {@link ExamCreateRequest#MAX_DURATION_MINUTES}.
     */
    public static final int MAX_DURATION_MINUTES = ExamCreateRequest.MAX_DURATION_MINUTES;

    /** Longest student or teacher text. Aliases {@link ExamCreateRequest#MAX_TEXT_LENGTH}. */
    public static final int MAX_TEXT_LENGTH = ExamCreateRequest.MAX_TEXT_LENGTH;

    /** What the points of a composition must sum to. Aliases
     *  {@link ExamCreateRequest#POINTS_TOTAL}. */
    public static final int POINTS_TOTAL = ExamCreateRequest.POINTS_TOTAL;

    /** Normalises text and takes a tolerant copy of the composition; never throws. */
    public ExamVersionSave {
        name = ExamCreateRequest.strip(name);
        studentText = ExamCreateRequest.blankToNull(studentText);
        teacherText = ExamCreateRequest.blankToNull(teacherText);
        questions = ExamCreateRequest.tolerantCopy(questions);
    }

    /** @return {@code true} when instructions for the students travel with this save. */
    public boolean hasStudentText() {
        return studentText != null;
    }

    /** @return {@code true} when staff-only notes travel with this save. */
    public boolean hasTeacherText() {
        return teacherText != null;
    }
}
