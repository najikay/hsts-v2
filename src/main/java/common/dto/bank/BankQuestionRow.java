package common.dto.bank;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * One row of the bank list (Common tier, E6.5 — F2.6).
 *
 * <p><b>Keyless on purpose, even though only staff ever receive it.</b> The list is the
 * high-volume payload of this feature: forty rows for a browse, one row per refresh, and the
 * thing on screen when somebody shares a screenshot. The key is fetched one question at a time
 * by {@code QUESTION_GET}, which names a single question, so the blast radius of the answer key
 * is a pane a teacher deliberately opened rather than everything she scrolled past. The extra
 * round trip is one the detail pane was already paying for its lazy image fetch (F2.4, NFR-18).
 *
 * <p>It carries no answers at all, not merely no correctness, and no image bytes.
 * {@code BankWireLeakGuardTest} scans this package and this record is the one it is most
 * interested in staying keyless.
 *
 * <h2>{@code text} is truncated, and the detail is where the rest lives</h2>
 *
 * <p>The stem here is cut to a display length <b>server-side</b> (lead's ruling of 2026-08-21),
 * because forty full stems is the payload that makes a bank browse feel slow and a table cell
 * shows one line of it either way. A screen that needs the whole question already has a reason
 * to call {@code QUESTION_GET}: {@link QuestionDetail#text()} is never truncated.
 *
 * <h2>No lock field</h2>
 *
 * <p>The live "Editing · &lt;name&gt;" badge is E18.8's, merged onto these rows client-side from
 * {@code LOCKS_SNAPSHOT} and {@code PUSH_LOCK_CHANGED} (F10.0). Carrying a lock field here would
 * let a stale badge disagree with a real lock, and would make looking at a list contend for
 * forty locks.
 *
 * @param displayId5       the 5-digit id staff quote when they talk about a question (S-8)
 * @param courseCode       the owning course's code
 * @param courseName       the owning course's name, so a row is readable without a second lookup
 * @param text             the stem, <b>truncated server-side</b>; the full stem is on the detail
 * @param topic            the question's topic
 * @param difficulty       how hard it is
 * @param latestVersionNo  the newest version number, which is the one this row describes
 * @param hasImage         whether the latest version has an illustration, so the row can show a
 *                         marker without fetching bytes nobody asked for
 * @param lastVersionAt    when the latest version was written, UTC (ADR-010). Named for what it
 *                         is: {@code questions} rows never change and there is no
 *                         {@code updated_at} column, so an {@code updatedAt} here would promise
 *                         something the schema does not have
 */
public record BankQuestionRow(String displayId5,
                              String courseCode,
                              String courseName,
                              String text,
                              String topic,
                              Difficulty difficulty,
                              long latestVersionId,
                              int latestVersionNo,
                              boolean hasImage,
                              Instant lastVersionAt) implements Serializable {

    private static final long serialVersionUID = 2L;

    /**
     * How much stem a row carries.
     *
     * <p>The contract fixes the truncation length as a constant rather than a per-request
     * parameter, and it lives here so the server that cuts and the client that renders the cut
     * agree on one number instead of two. Wide enough that a normal question arrives whole and a
     * pasted paragraph does not.
     */
    public static final int STEM_PREVIEW_CHARS = 160;

    public BankQuestionRow {
        Objects.requireNonNull(displayId5, "displayId5");
        Objects.requireNonNull(courseCode, "courseCode");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(difficulty, "difficulty");
    }
}
