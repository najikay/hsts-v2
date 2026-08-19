package client.ui.components.logic;

import java.util.Locale;
import java.util.Objects;

/**
 * Everything a {@code StatusChip} needs to render, decided without JavaFX
 * (Presentation tier, E4.15).
 *
 * <p>Splitting the decision ("what does {@code PENDING_APPROVAL} look like?")
 * from the drawing ("build an HBox with these style classes") is what lets the
 * whole status vocabulary be unit-tested — including the two things that
 * actually bite: a state the client has never heard of (a server one version
 * ahead) must render as a readable neutral chip rather than blank or crash, and
 * every state must map to exactly one tone.
 *
 * @param label human text shown in the chip
 * @param tone  the colour treatment
 * @param dot   whether to draw the leading status dot (LIVE chips do)
 */
public record ChipSpec(String label, ChipTone tone, boolean dot) {

    public ChipSpec {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(tone, "tone");
    }

    /** @return a chip with no dot. */
    public static ChipSpec of(String label, ChipTone tone) {
        return new ChipSpec(label, tone, false);
    }

    /** @return this chip's spec with the leading dot enabled. */
    public ChipSpec withDot() {
        return new ChipSpec(label, tone, true);
    }

    /**
     * Turns an enum-style constant into display text:
     * {@code PENDING_APPROVAL} → {@code "Pending approval"}.
     *
     * <p>This is the fallback that keeps an unknown server state readable.
     */
    public static String humanize(String constant) {
        if (constant == null || constant.isBlank()) {
            return "Unknown";
        }
        String cleaned = constant.trim().replace('_', ' ').replace('-', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
    }
}
