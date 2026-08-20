package common.dto.exam;

import java.io.Serializable;

/**
 * What the server did with one autosave (Common tier, E10.3/E10.11 — F6.3).
 *
 * <p>Three jobs in one small answer, and each of them removes a way for the client to be
 * wrong:
 *
 * <ul>
 *   <li>it echoes what is now stored, so the "All changes saved" indicator reflects the
 *       server's state rather than the client's optimism;</li>
 *   <li>it carries the answered count the <b>server</b> counted, so the progress line
 *       ("answered 7/20") cannot drift from the paper that will actually be marked;</li>
 *   <li>it carries {@link #timing}, so every keystroke a student makes is also a clock
 *       re-sync. An extension that landed while she was typing is applied without her
 *       screen having had to receive the push.</li>
 * </ul>
 *
 * @param questionVersionId which question this answers for
 * @param selected          what is now stored: 1..4, or {@code null} for cleared
 * @param answeredCount     how many questions of this attempt now carry a choice
 * @param questionCount     the paper's length
 * @param timing            the authoritative clock, as of this write
 */
public record SaveAnswerResult(long questionVersionId,
                               Integer selected,
                               int answeredCount,
                               int questionCount,
                               AttemptTiming timing) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** @return the fraction of the paper answered, 0.0–1.0; drives the progress bar. */
    public double progress() {
        return questionCount <= 0 ? 0 : (double) answeredCount / questionCount;
    }
}
