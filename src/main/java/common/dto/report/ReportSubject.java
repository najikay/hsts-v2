package common.dto.report;

import java.io.Serializable;
import java.util.Objects;

/**
 * One pickable subject of a report (Common tier, E15.3 — F9.4).
 *
 * <p>A teacher, a course or a student, in the one shape the picker renders whichever dimension
 * is selected. The screen therefore has one combo box and one cell factory rather than three,
 * and a fourth dimension needs neither.
 *
 * <p><b>{@code executions} is on the subject on purpose.</b> A principal choosing a teacher who
 * has never had a sitting close should learn that before she clicks, not after the table comes
 * back empty. Carrying the count in the picker turns E15.5's degenerate case into a label
 * instead of a dead end (§4.1), and it costs one grouped query per dimension rather than one
 * per subject.
 *
 * @param id        the subject's id: a user id in decimal for a teacher or a student, a
 *                  two-character course code for a course. Opaque to everything except the
 *                  strategy that issued it
 * @param label     what the principal reads: a person's full name, or a course's name
 * @param detail    the second line that tells two subjects with the same label apart: a
 *                  username for a person, the course code for a course
 * @param executions how many reportable sittings this subject has right now: closed, with
 *                  statistics frozen. Zero is a legitimate and useful answer
 */
public record ReportSubject(String id, String label, String detail, int executions)
        implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * @throws NullPointerException     when {@code id} or {@code label} is null
     * @throws IllegalArgumentException when {@code id} is blank or {@code executions} is
     *                                  negative, both of which are server-side faults rather
     *                                  than states a screen should try to render
     */
    public ReportSubject {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        if (id.isBlank()) {
            throw new IllegalArgumentException("A report subject needs an id to be asked about.");
        }
        if (executions < 0) {
            throw new IllegalArgumentException(
                    "A subject cannot have " + executions + " reportable executions.");
        }
        detail = detail == null ? "" : detail;
    }

    /** @return {@code true} when there is nothing to compare for this subject yet. */
    public boolean hasNothingToReport() {
        return executions == 0;
    }
}
