package client.ui.components.logic;

import java.util.Locale;
import java.util.Objects;

/**
 * The kicker label's text transform (Presentation tier, UI wave 2).
 *
 * <p>A "kicker" is the small uppercase, widely tracked label above a number —
 * {@code LIVE NOW}, {@code AWAITING GRADING}, and every table column heading
 * after this wave. Two of its three properties are ordinary CSS (11.5px, faint
 * colour). The other two are not, and that is why this class exists.
 *
 * <h2>JavaFX CSS has neither of the properties this needs</h2>
 *
 * <p>There is <b>no {@code -fx-text-transform}</b> and <b>no
 * {@code -fx-letter-spacing}</b> in the JavaFX CSS reference. Both are web
 * properties, and a stylesheet that writes them parses them as unknown and
 * silently does nothing — which is the failure mode where a design ships
 * half-applied and nobody notices until the artboards are held next to a
 * screenshot. So the transform is done in Java, once, here, where it is
 * measurable.
 *
 * <h2>Tracking, and the accessibility cost of it</h2>
 *
 * <p>Letter spacing is faked the only way a toolkit with no tracking allows:
 * a {@link #HAIR_SPACE} between characters. That has a real cost — a screen
 * reader handed {@code "L I V E   N O W"} spells it out — so
 * {@link #plain(String)} gives every caller the untracked string to put on the
 * node's accessible text. <b>Both are used together at every call site</b>;
 * the component that renders a kicker is responsible for setting the accessible
 * text, and the one place that could forget is
 * {@code client.ui.components.Kicker}, which is four lines long.
 *
 * <p>Word gaps are widened rather than hair-spaced, because a hair space
 * between words and between letters would make {@code AWAITING GRADING} read as
 * one long word.
 */
public final class KickerText {

    /**
     * U+200A, the narrowest space Unicode defines.
     *
     * <p>A thin space (U+2009) at 11.5px is wide enough to read as a gap
     * between letters rather than as tracking, which is the difference between
     * a kicker and a ransom note.
     */
    public static final String HAIR_SPACE = " ";

    /** What separates two words: the hair space plus a real one. */
    public static final String WORD_GAP = HAIR_SPACE + " ";

    private KickerText() {
    }

    /**
     * The tracked, uppercased form to display.
     *
     * @param text the copy constant, in its ordinary sentence case
     * @return for example {@code "LIVE NOW"} with hair spaces between letters;
     *         {@code ""} for null or blank input, so a missing kicker renders as
     *         nothing rather than as a stray gap
     */
    public static String track(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String upper = plain(text);
        StringBuilder tracked = new StringBuilder(upper.length() * 2);
        for (int i = 0; i < upper.length(); i++) {
            char current = upper.charAt(i);
            if (i > 0) {
                tracked.append(current == ' ' || upper.charAt(i - 1) == ' ' ? "" : HAIR_SPACE);
            }
            tracked.append(current == ' ' ? WORD_GAP : String.valueOf(current));
        }
        return tracked.toString();
    }

    /**
     * The same words with no tracking, for the accessible text.
     *
     * <p>Uppercased, because that part <i>is</i> the label: the kicker for the
     * teacher's live card says LIVE NOW, and a listener should hear the same
     * words a reader sees. What they must not receive is the spelling-out that
     * {@link #track} would give them.
     *
     * @param text the copy constant
     * @return the trimmed, collapsed, uppercased words; {@code ""} for null or blank
     */
    public static String plain(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ENGLISH);
    }

    /**
     * @param text a kicker's display form
     * @return the words back, with the tracking removed. The inverse of
     *         {@link #track}, and it exists so a test can prove the transform
     *         loses nothing rather than merely that it produced some string
     */
    public static String untrack(String text) {
        Objects.requireNonNull(text, "text");
        return text.replace(HAIR_SPACE, "");
    }
}
