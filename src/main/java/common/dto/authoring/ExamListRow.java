package common.dto.authoring;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * One exam on the teacher's exam list, with all of its versions (Common tier, E7.10 — F3.6).
 *
 * <h2>{@code name} is the LATEST version's name</h2>
 *
 * <p>Matching {@code AuthoredExam}'s rule, and for the reason that rule exists: F3.5 makes a
 * rename a new version, so an exam renamed in v3 is an exam a teacher looks for under that name.
 * Showing the v1 name because it is the oldest would be showing her the name she stopped using.
 * The per-version names are still reachable, one {@code EXAM_VERSION_GET} away, which is where a
 * history panel wants them.
 *
 * <h2>Every version travels, and that is what makes the row expandable</h2>
 *
 * <p>{@link #versions()} is newest first and holds all of them, drafts included — this list is
 * the screen behind route id {@code exams} after E7.10 lands, and the retirement of
 * {@code MY_APPROVALS_GET} into it (contract section 8) is only honest if the drafts that verb
 * never showed are here. Versions carry counts rather than compositions, so expanding a row is
 * free and opening one is a deliberate second call.
 *
 * @param examId           the exam's id
 * @param displayId6       the 6-digit id staff quote when they talk about an exam (S-10)
 * @param courseCode       the owning course's code
 * @param courseName       the owning course's name, so a row is readable without a second lookup
 * @param name             the LATEST version's name; see above
 * @param latestVersionNo  the newest version number, which is the one {@code name} came from
 * @param versions         every version, newest first; never {@code null}
 */
public record ExamListRow(long examId,
                          String displayId6,
                          String courseCode,
                          String courseName,
                          String name,
                          int latestVersionNo,
                          List<ExamVersionRow> versions) implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Outbound: null-checks and a strict copy.
     *
     * <p>{@link List#copyOf} rather than the tolerant copy the request records use. The server
     * assembles this from rows it just read, so a null element is a defect in the assembler
     * rather than a malformed payload to be refused politely.
     */
    public ExamListRow {
        Objects.requireNonNull(displayId6, "displayId6");
        Objects.requireNonNull(courseCode, "courseCode");
        Objects.requireNonNull(courseName, "courseName");
        Objects.requireNonNull(name, "name");
        versions = List.copyOf(Objects.requireNonNull(versions, "versions"));
    }

    /** @return how many versions this exam has had (C-2: none are ever removed). */
    public int versionCount() {
        return versions.size();
    }

    /**
     * @return the newest version, which is the one the row's chip and actions describe, or
     *         {@code null} when the list is somehow empty. Written once here so the screen does
     *         not index into a list whose order is a contract term
     */
    public ExamVersionRow latestVersion() {
        return versions.isEmpty() ? null : versions.get(0);
    }
}
