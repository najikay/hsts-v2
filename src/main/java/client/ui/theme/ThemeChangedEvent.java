package client.ui.theme;

/**
 * Broadcast whenever the effective appearance changes (Presentation tier, E4.7,
 * ARCHITECTURE §6).
 *
 * <p>CSS handles almost everything, but not quite everything: nodes painted in
 * Java — the StatChart histogram (F9.2), the logo gradient, canvas-drawn
 * illustrations — have to re-pick their colours themselves. They subscribe to
 * this on the {@code ClientEventBus} and repaint, which is why the event carries
 * the resolved appearance ({@link #effectiveMode()}, {@link #dark()}) and not
 * just the user's preference.
 *
 * @param mode          what the user selected (may be {@link ThemeMode#SYSTEM})
 * @param effectiveMode the appearance actually applied — never {@code SYSTEM}
 * @param palette       the accent palette in force
 */
public record ThemeChangedEvent(ThemeMode mode, ThemeMode effectiveMode, AccentPalette palette) {

    /** @return {@code true} when the dark palette is what is on screen. */
    public boolean dark() {
        return effectiveMode == ThemeMode.DARK;
    }

    /** @return the accent emphasis colour currently in force, as a hex string. */
    public String accent() {
        return palette.accent(dark());
    }

    /** @return the accent soft tint currently in force, as a hex string. */
    public String accentSoft() {
        return palette.accentSoft(dark());
    }
}
