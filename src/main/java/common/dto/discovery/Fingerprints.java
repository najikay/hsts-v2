package common.dto.discovery;

import java.util.Locale;

/**
 * How a server's discovery id is shown to a person (Common tier, E19.8/E19.10).
 *
 * <p>In {@code common} rather than beside the server's generator because both
 * tiers render the same id and they must render it identically. The whole value
 * of the short form is that an operator can read {@code "ID 7F3A-2B91"} off the
 * server console and a student can see the same nine characters in the client's
 * picker; two implementations of "shorten a fingerprint" is how those two strings
 * eventually stop matching.
 */
public final class Fingerprints {

    /** How many hex characters of the id are shown. */
    public static final int SHORT_LENGTH = 8;

    /** What an absent id renders as. */
    public static final String UNKNOWN = "unknown";

    private Fingerprints() {
    }

    /**
     * The grouped display form: {@code "7F3A-2B91"}.
     *
     * <p>Uppercase and hyphenated because it is read aloud and compared by eye
     * during a demo. Thirty-two bits is far too few to be a security boundary and
     * is not used as one (see {@code server.discovery.ServerFingerprint} for what
     * the id does and does not prove); it is enough that two servers in one room
     * will not collide, and short enough that a person can check it.
     *
     * <p><b>Comparison is never done on this form.</b> The client pins and
     * compares the full id; this is for eyes only.
     *
     * @param fingerprint the full id, or {@code null}
     * @return the display form, or {@link #UNKNOWN}
     */
    public static String shortForm(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return UNKNOWN;
        }
        String hex = fingerprint.replace("-", "").trim().toUpperCase(Locale.ROOT);
        if (hex.isEmpty()) {
            return UNKNOWN;
        }
        String head = hex.length() >= SHORT_LENGTH ? hex.substring(0, SHORT_LENGTH) : hex;
        return head.length() > 4 ? head.substring(0, 4) + '-' + head.substring(4) : head;
    }

    /**
     * @param left  one id
     * @param right another
     * @return {@code true} when they are the same id, comparing the full value and
     *         ignoring case and surrounding space, never the short form
     */
    public static boolean sameFingerprint(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }
}
