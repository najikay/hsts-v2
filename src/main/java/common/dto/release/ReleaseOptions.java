package common.dto.release;

import java.io.Serializable;
import java.util.List;

/**
 * Everything the create dialog needs to open (Common tier, E9 — F5.1).
 *
 * <p>One answer rather than a bare list, so the empty case can say why it is empty. A
 * teacher who has written exams but had none approved yet, and a teacher who has written
 * none at all, both see zero rows and need different sentences; a {@code List} on the wire
 * would leave the client guessing which of the two happened, and guessing wrong is how a
 * screen ends up telling somebody to go and get an exam approved that she never wrote.
 *
 * @param versions the approved versions she may release, newest exam id first; possibly empty
 * @param anyExams whether she has any exam at all in the courses she teaches, approved or not
 */
public record ReleaseOptions(List<ReleasableVersion> versions, boolean anyExams)
        implements Serializable {

    private static final long serialVersionUID = 1L;

    public ReleaseOptions {
        versions = versions == null ? List.of() : List.copyOf(versions);
    }

    /** @return an answer for a teacher with nothing to release and nothing in the drawer. */
    public static ReleaseOptions empty() {
        return new ReleaseOptions(List.of(), false);
    }

    /** @return {@code true} when there is nothing to put in the picker. */
    public boolean isEmpty() {
        return versions.isEmpty();
    }

    /**
     * @return {@code true} when the drawer has exams but none of them is approved yet. The
     *         one case whose next step is "ask your coordinator" rather than "write an exam"
     */
    public boolean waitingOnApproval() {
        return versions.isEmpty() && anyExams;
    }
}
