package server.features.bot;

import java.text.Normalizer;
import java.util.Locale;

/**
 * The one definition of "the same text" this feature has (Logic tier, E16.5/E16.10).
 *
 * <p>Two jobs that look alike and are not:
 *
 * <ul>
 *   <li>{@link #normalise} tidies extracted material so it is worth putting in a
 *       prompt. PDF extraction produces ragged line breaks mid-sentence, page
 *       furniture, non-breaking spaces and the occasional form feed; left alone
 *       they cost prompt budget and make chunk boundaries meaningless;</li>
 *   <li>{@link #groupingKey} reduces a question to something two students can
 *       arrive at independently, which is what makes "frequent questions" (S-34) a
 *       real aggregate rather than a list of every distinct spelling.</li>
 * </ul>
 *
 * <p>They are in one class because they must never drift into each other's job:
 * normalising must not destroy meaning (it is what the model reads), and grouping
 * must destroy quite a lot of it (it is only ever a key).
 */
public final class TextNormaliser {

    private TextNormaliser() {
        // static helper - no instances
    }

    /**
     * Tidies extracted text without changing what it says.
     *
     * <p>Paragraph structure is kept deliberately: it is the only signal
     * {@link Chunker} has about where one idea ends and the next begins, so a
     * normaliser that collapsed everything to one line would leave the chunker
     * cutting sentences in half at arbitrary offsets.
     *
     * @param text raw extracted text; {@code null} is empty
     * @return normalised text, trimmed
     */
    public static String normalise(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String unified = Normalizer.normalize(text, Normalizer.Form.NFKC)
                // Windows and old Mac line endings, before anything counts newlines.
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                // Non-breaking and other exotic spaces, which PDFs are full of and
                // which no split on \s+ would otherwise catch consistently.
                .replace('\u00A0', ' ')
                .replace('\u2007', ' ')
                .replace('\u202F', ' ')
                // Form feeds mark page breaks in extracted PDFs; they are paragraph
                // breaks for our purposes, not characters.
                .replace('\f', '\n');

        StringBuilder cleaned = new StringBuilder(unified.length());
        for (int i = 0; i < unified.length(); i++) {
            char c = unified.charAt(i);
            // Drop control characters, keeping the two that carry structure.
            if (c == '\n' || c == '\t' || !Character.isISOControl(c)) {
                cleaned.append(c == '\t' ? ' ' : c);
            }
        }
        return cleaned.toString()
                // Trailing spaces before a newline, then runs of blank lines down to
                // exactly one blank line: one paragraph separator, consistently.
                .replaceAll("[ ]+\n", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .replaceAll("[ ]{2,}", " ")
                .trim();
    }

    /**
     * Reduces a question to a grouping key for the S-34 aggregate.
     *
     * <p>Case folded, whitespace collapsed, and trailing punctuation dropped, so
     * "What is a foreign key?", "what is a foreign key" and "What Is A Foreign
     * Key ?" are one row in the teacher's list. It stops there on purpose: no
     * stemming, no stop-word removal, no synonym table. Those would merge
     * questions that are genuinely different and would make the teacher's list
     * something she has to trust rather than something she can read.
     *
     * <p>The key is also what the analytics DTO shows, which is why it must stay
     * readable — and why it is a fold of the question rather than anything derived
     * from who asked it.
     *
     * @param question a question as typed
     * @return its grouping key; empty for an empty question
     */
    public static String groupingKey(String question) {
        if (question == null) {
            return "";
        }
        String folded = question.trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
        // Trailing punctuation only: internal punctuation can be the question
        // ("what does a[i] mean?"), and stripping it would merge distinct asks.
        return folded.replaceAll("[\\p{Punct}]+$", "").trim();
    }
}
