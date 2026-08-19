package client.ui.shell;

import java.util.Locale;

/**
 * Derives the two-letter monogram shown in the avatar disc (Presentation tier,
 * E4.10).
 *
 * <p>Small, but every input it will actually meet is awkward: the seed data has
 * Hebrew names (X-I18N), a co-teacher may be "Dr. Anat Levi-Ben Ari", and a
 * user-management import can hand over a single word or a stray double space.
 * A monogram that renders "??" or three characters in a 30px circle is a visible
 * defect on every screen, so the rules are pinned down here and tested.
 */
public final class Initials {

    /** Shown when there is no usable name at all. */
    public static final String FALLBACK = "?";

    private Initials() {
    }

    /**
     * @param fullName the user's display name
     * @return one or two uppercase letters — first letter of the first word plus
     *         first letter of the last word, or just the first letter for a
     *         single-word name, or {@link #FALLBACK} for nothing usable
     */
    public static String of(String fullName) {
        if (fullName == null) {
            return FALLBACK;
        }
        String[] words = fullName.trim().split("[\\s]+");
        StringBuilder letters = new StringBuilder();
        String first = firstLetter(words[0]);
        if (first.isEmpty()) {
            return FALLBACK;
        }
        letters.append(first);
        for (int i = words.length - 1; i > 0; i--) {
            String last = firstLetter(words[i]);
            if (!last.isEmpty()) {
                letters.append(last);
                break;
            }
        }
        return letters.toString();
    }

    /**
     * Strips leading punctuation ("-Ben") and honorific dots ("Dr.") before
     * taking a letter, and uppercases only when the script has a case — Hebrew
     * has none, so {@code toUpperCase} is a harmless no-op there.
     */
    private static String firstLetter(String word) {
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return String.valueOf(c).toUpperCase(Locale.ROOT);
            }
        }
        return "";
    }
}
