package common.dto.release;

import java.io.Serializable;
import java.util.Objects;

/**
 * One approved exam version a teacher may take out of the drawer (Common tier, E9 — F5.1).
 *
 * <p>A picker row, and the reason the create dialog cannot offer an unapproved exam: the
 * server builds this list from a query that filters on {@code APPROVED}, so an exam that is
 * still a draft is not fetched, not counted and not returned. PRD §6's "release unapproved
 * version → impossible (not listed)" is that {@code where} clause. The service checks it
 * again on create, because a list is a courtesy and never a gate.
 *
 * <p><b>No questions and no answer key.</b> A teacher scheduling an exam needs its name, its
 * course, how long it runs and how many questions it has; the paper itself is E7's preview
 * and E10's take-exam projection. This record has nowhere to put one.
 *
 * @param examVersionId   the version being released; scheduling binds to a version (S-14)
 * @param examDisplayId   the six-digit id people quote (S-10)
 * @param examName        the exam's name, as its author wrote it
 * @param versionNo       the 1-based version number, so two approved versions are tellable apart
 * @param courseCode      the two-character course code
 * @param courseName      the course's display name
 * @param durationMinutes how long a student gets, before any extension
 * @param questionCount   how many questions are on the paper
 */
public record ReleasableVersion(long examVersionId,
                                String examDisplayId,
                                String examName,
                                int versionNo,
                                String courseCode,
                                String courseName,
                                int durationMinutes,
                                int questionCount) implements Serializable {

    private static final long serialVersionUID = 1L;

    public ReleasableVersion {
        examDisplayId = examDisplayId == null ? "" : examDisplayId;
        examName = examName == null ? "" : examName;
        courseCode = courseCode == null ? "" : courseCode;
        courseName = courseName == null ? "" : courseName;
    }

    /** @return the label a picker shows, e.g. "Midterm (v2) · Algebra 11 · 12 questions". */
    public String label() {
        return examName + " (v" + versionNo + ") · " + courseName
                + " · " + questionCount + " question" + (questionCount == 1 ? "" : "s");
    }

    /** @return whether this row and that one are the same version. */
    public boolean is(long candidateVersionId) {
        return examVersionId == candidateVersionId;
    }

    @Override
    public String toString() {
        return "ReleasableVersion[" + examVersionId + " " + Objects.toString(examName, "") + "]";
    }
}
