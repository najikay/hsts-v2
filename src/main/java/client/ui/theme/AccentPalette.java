package client.ui.theme;

import java.util.Locale;
import java.util.Optional;

/**
 * The five selectable accent palettes (Presentation tier, PRD §4.1, E4.8).
 *
 * <p>Each palette is four hex values — an emphasis colour and a soft tint, once
 * for light mode and once for dark. The Java constants exist so the settings
 * swatches and the gallery can paint a live preview without parsing CSS; the
 * authoritative values used by every styled node come from the matching
 * {@code css/accent-*.css}, which defines nothing but the three looked-up
 * colours {@code -hsts-accent}, {@code -hsts-accent-soft} and
 * {@code -hsts-on-accent}. Adding a sixth palette is a new enum constant plus a
 * new 12-line stylesheet — no component changes (NFR-19).
 *
 * <p>{@code onAccent} — the colour of text drawn on top of the accent — is not
 * per-palette: white in light mode, near-black in dark mode, matching the
 * approved mockups.
 */
public enum AccentPalette {

    /** Default palette. */
    INDIGO("Indigo", "#4f46e5", "#eef2ff", "#818cf8", "#2b2a5e"),
    EMERALD("Emerald", "#047857", "#ecfdf5", "#34d399", "#0b3d2e"),
    AMBER("Amber", "#b45309", "#fffbeb", "#fbbf24", "#4a3313"),
    ROSE("Rose", "#be123c", "#fff1f2", "#fb7185", "#4c1626"),
    SLATE("Slate", "#475569", "#f1f5f9", "#94a3b8", "#26313f");

    /** Text/icon colour drawn on an accent-filled surface, light mode. */
    public static final String ON_ACCENT_LIGHT = "#ffffff";

    /** Text/icon colour drawn on an accent-filled surface, dark mode. */
    public static final String ON_ACCENT_DARK = "#0d1117";

    /** The palette used when nothing has been chosen or the stored value is unreadable. */
    public static final AccentPalette DEFAULT = INDIGO;

    private final String displayName;
    private final String light;
    private final String lightSoft;
    private final String dark;
    private final String darkSoft;

    AccentPalette(String displayName, String light, String lightSoft, String dark, String darkSoft) {
        this.displayName = displayName;
        this.light = light;
        this.lightSoft = lightSoft;
        this.dark = dark;
        this.darkSoft = darkSoft;
    }

    /** @return the label shown beside the settings swatch. */
    public String displayName() {
        return displayName;
    }

    /** @return the emphasis colour in light mode. */
    public String light() {
        return light;
    }

    /** @return the soft tint (chip / active-nav backgrounds) in light mode. */
    public String lightSoft() {
        return lightSoft;
    }

    /** @return the emphasis colour in dark mode. */
    public String dark() {
        return dark;
    }

    /** @return the soft tint in dark mode. */
    public String darkSoft() {
        return darkSoft;
    }

    /** @return the emphasis colour for the given appearance. */
    public String accent(boolean darkMode) {
        return darkMode ? dark : light;
    }

    /** @return the soft tint for the given appearance. */
    public String accentSoft(boolean darkMode) {
        return darkMode ? darkSoft : lightSoft;
    }

    /** @return the on-accent text colour for the given appearance. */
    public static String onAccent(boolean darkMode) {
        return darkMode ? ON_ACCENT_DARK : ON_ACCENT_LIGHT;
    }

    /** @return the lowercase key used in the preferences file and the stylesheet name. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** @return the classpath location of this palette's accent stylesheet. */
    public String stylesheet() {
        return "/css/accent-" + key() + ".css";
    }

    /**
     * Parses a persisted value tolerantly.
     *
     * @return the matching palette, empty for {@code null}/blank/unknown text
     */
    public static Optional<AccentPalette> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        for (AccentPalette palette : values()) {
            if (palette.name().equals(key)) {
                return Optional.of(palette);
            }
        }
        return Optional.empty();
    }
}
