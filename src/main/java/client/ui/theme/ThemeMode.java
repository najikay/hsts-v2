package client.ui.theme;

import java.util.Locale;
import java.util.Optional;

/**
 * The three theme modes a user can choose (Presentation tier, PRD §4.1, E4.7).
 *
 * <p>{@link #SYSTEM} is a <i>preference</i>, not an appearance: it has to be
 * resolved against the OS before anything can be painted. That resolution lives
 * in {@link ThemeState#effectiveMode()} so the distinction between "what the
 * user picked" and "what is on screen" is explicit everywhere — the settings
 * screen shows the former, the stylesheets follow the latter.
 */
public enum ThemeMode {

    /** Always the light palette. */
    LIGHT("Light"),

    /** Always the dark palette. */
    DARK("Dark"),

    /** Follow the operating system's appearance setting. */
    SYSTEM("System");

    private final String displayName;

    ThemeMode(String displayName) {
        this.displayName = displayName;
    }

    /** @return the label shown in the settings screen. */
    public String displayName() {
        return displayName;
    }

    /** @return {@code true} when this mode resolves without asking the OS. */
    public boolean isExplicit() {
        return this != SYSTEM;
    }

    /**
     * Parses a persisted value tolerantly.
     *
     * @return the matching mode, empty for {@code null}/blank/unknown text — a
     *         hand-edited or future-version preferences file must not crash startup
     */
    public static Optional<ThemeMode> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        for (ThemeMode mode : values()) {
            if (mode.name().equals(key)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
