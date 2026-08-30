package common.dto.report;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * One exam as the principal's data browser lists it (Common tier, E15.2 — F9.3, S-7).
 *
 * <p>The drawer's index, school-wide. It answers "which exams exist, for what, written by whom,
 * and how many times has each been rewritten" and it answers nothing else.
 *
 * <h2>What this row deliberately does not carry ⚑</h2>
 *
 * <p>No questions, no answer key, no teacher instructions, and <b>no approval status</b>. The
 * first two are the rule every wire record in this application obeys; the last is a decision,
 * and it is the conservative one. Approval is a workflow between an author and her coordinator
 * (F4.1), and "rejected, twice" is a fact about two members of staff rather than a fact about
 * the school's exam catalogue. F9.3 asks that the principal be able to <em>read</em> the data,
 * and this row is what reading the exam catalogue means. If the lead rules that she should see
 * where an exam stands, one component appends here and nothing else moves.
 *
 * <p>The name is the <b>latest</b> version's, because this row answers "which exams exist" —
 * a question about the exam rather than about any sitting of it. A sitting is labelled with the
 * released version's name instead ({@link ReportRow#examName()}), so the two lists deliberately
 * disagree about a renamed exam, and each is right about its own question.
 *
 * @param displayId6    the six-digit id staff quote when they talk about an exam (S-10)
 * @param examName      the name on the exam's most recent version
 * @param courseCode    the two-character course code
 * @param courseName    the course's display name, so a row reads without a second lookup
 * @param authorName    the teacher who wrote it, by display name. No id: this row is read, and
 *                      an id would be the beginning of a request that acted on her
 * @param versions      how many versions the exam has, which is its latest version number
 * @param lastVersionAt when that version was written, UTC (ADR-010). Named for what it is:
 *                      {@code exams} rows carry no timestamp of their own
 * @param latestVersionId the primary key of that latest version, so the row can be
 *                      <b>opened</b> (REPORTS amendment A2, 2026-08-30, live session, U-44). The
 *                      one component here a principal never reads: {@code EXAM_PREVIEW_GET} is
 *                      addressed by version, so without it this row could be listed and not
 *                      opened. An id that identifies a <em>version</em>, never a person - the
 *                      author still travels by name alone, for the reason stated above
 */
public record DataExamRow(String displayId6,
                          String examName,
                          String courseCode,
                          String courseName,
                          String authorName,
                          int versions,
                          Instant lastVersionAt,
                          long latestVersionId) implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * @throws NullPointerException     when the display id or the course code is missing, both
     *                                  of which are stored not-null and neither of which a
     *                                  screen could render around
     * @throws IllegalArgumentException when {@code versions} is not positive: an exam always
     *                                  has at least one version, so a zero here is a broken
     *                                  query rather than a state to draw
     */
    public DataExamRow {
        Objects.requireNonNull(displayId6, "displayId6");
        Objects.requireNonNull(courseCode, "courseCode");
        if (versions <= 0) {
            throw new IllegalArgumentException(
                    "An exam cannot have " + versions + " versions.");
        }
        examName = examName == null ? "" : examName;
        courseName = courseName == null ? "" : courseName;
        authorName = authorName == null ? "" : authorName;
    }

    /** @return {@code true} when this exam has been rewritten at least once (F2.3's history). */
    public boolean hasBeenRevised() {
        return versions > 1;
    }

    /**
     * @return {@code true} when this row can be opened (2026-08-30, live session, U-44). False
     *         only for a row built by an older server that never carried the id, in which case
     *         the browser leaves the row unopenable rather than sending a request for version 0
     */
    public boolean isOpenable() {
        return latestVersionId > 0;
    }
}
