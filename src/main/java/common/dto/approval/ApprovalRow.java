package common.dto.approval;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * One exam version in an approval list (Common tier, E8.1/E8.6 — F4.1, F4.2).
 *
 * <p>The row a coordinator scans in her queue, and the same row a teacher reads on her own
 * exams to find out what happened to them. One shape for both audiences on purpose: the two
 * lists show the same facts about the same object, and a second near-identical record would
 * be the one that drifted the day a column was added.
 *
 * <p>Only two components differ in meaning between the two audiences, and both are honest in
 * either: in the coordinator's queue {@link #state} is always {@link ApprovalState#PENDING}
 * and {@link #rejectedReason} is empty, because a queue by definition holds only pending
 * work; on the teacher's own list every state appears.
 *
 * <p><b>No answer key, and no questions at all.</b> A list row is metadata; the paper arrives
 * only from {@code EXAM_PREVIEW_GET}, which is a separate verb with a separate guard. That
 * split is the same one {@code ExamHeader} makes for a student, for the same reason.
 *
 * <p>{@link #lockVersion} travels because approving and rejecting are optimistic-locked
 * writes (ARCHITECTURE §5): the client sends back the version it was looking at, and a
 * decision taken against a stale row is refused with {@code CONFLICT} rather than silently
 * overwriting whatever landed in between. It is not a secret and not an identifier; it is
 * the "this is what I read" marker of a compare-and-set.
 *
 * @param examVersionId   the version this row is about, and the id every verb takes
 * @param examDisplayId   the 6-digit id people quote (S-10)
 * @param examName        the exam's name as its author wrote it
 * @param courseCode      the 2-character course code
 * @param courseName      the course's display name, for the header line
 * @param versionNo       the 1-based version number; approval binds to a version (S-14)
 * @param authorName      who wrote and submitted it
 * @param submittedAt     when this version was created, UTC; clients render local
 * @param questionCount   how many questions the paper has, so the queue can be scanned
 *                        without opening every row
 * @param durationMinutes the exam's stored duration
 * @param state           where this version stands
 * @param rejectedReason  why it was sent back; empty unless {@link #state} is
 *                        {@link ApprovalState#REJECTED}
 * @param selfAuthored    {@code true} when the caller reading this row is also its author.
 *                        Computed by the server against the session, never by the client, and
 *                        it drives nothing but a label: F4.3 allows a coordinator to approve
 *                        her own exam, so this is information, not a warning
 * @param lockVersion     the optimistic-locking value this row was read at
 */
public record ApprovalRow(long examVersionId,
                          String examDisplayId,
                          String examName,
                          String courseCode,
                          String courseName,
                          int versionNo,
                          String authorName,
                          Instant submittedAt,
                          int questionCount,
                          int durationMinutes,
                          ApprovalState state,
                          String rejectedReason,
                          boolean selfAuthored,
                          int lockVersion) implements Serializable {

    private static final long serialVersionUID = 1L;

    public ApprovalRow {
        Objects.requireNonNull(submittedAt, "submittedAt");
        examDisplayId = examDisplayId == null ? "" : examDisplayId;
        examName = examName == null ? "" : examName;
        courseCode = courseCode == null ? "" : courseCode;
        courseName = courseName == null ? "" : courseName;
        authorName = authorName == null ? "" : authorName;
        state = state == null ? ApprovalState.DRAFT : state;
        // A screen should render an absent reason as an absent block, never as the word
        // "null" — the same normalisation ExamHeader makes for its optional texts.
        rejectedReason = rejectedReason == null ? "" : rejectedReason;
    }

    /** @return the course line the screens show, "12 · Calculus". */
    public String courseLabel() {
        return courseName.isBlank() ? courseCode : courseCode + " · " + courseName;
    }

    /** @return the exam line the screens show, "101201 · Calculus midterm (v2)". */
    public String examLabel() {
        return examDisplayId + " · " + examName + " (v" + versionNo + ')';
    }

    /** @return {@code true} when there is a rejection reason worth rendering a panel for. */
    public boolean hasRejectedReason() {
        return !rejectedReason.isBlank();
    }
}
