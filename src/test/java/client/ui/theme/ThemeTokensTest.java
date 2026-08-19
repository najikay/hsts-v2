package client.ui.theme;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ThemeMode}, {@link AccentPalette} and {@link SystemAppearance} — the
 * design-token contract (E4.7, E4.8).
 *
 * <p>The stylesheet checks below are the ones that matter operationally: the
 * Java enum and {@code css/accent-*.css} carry the same five palettes twice, and
 * a silent drift between them is invisible until someone eyeballs a screenshot.
 */
class ThemeTokensTest {

    @Nested
    @DisplayName("ThemeMode")
    class Modes {

        @Test
        void carriesDisplayNames() {
            assertThat(ThemeMode.LIGHT.displayName()).isEqualTo("Light");
            assertThat(ThemeMode.DARK.displayName()).isEqualTo("Dark");
            assertThat(ThemeMode.SYSTEM.displayName()).isEqualTo("System");
        }

        @Test
        void onlySystemNeedsResolving() {
            assertThat(ThemeMode.LIGHT.isExplicit()).isTrue();
            assertThat(ThemeMode.DARK.isExplicit()).isTrue();
            assertThat(ThemeMode.SYSTEM.isExplicit()).isFalse();
        }

        @ParameterizedTest
        @EnumSource(ThemeMode.class)
        void parsesItsOwnPersistedForm(ThemeMode mode) {
            assertThat(ThemeMode.parse(mode.name())).contains(mode);
        }

        @Test
        void parsingIsTolerantOfCaseAndWhitespace() {
            assertThat(ThemeMode.parse("  dark  ")).contains(ThemeMode.DARK);
        }

        @Test
        void parsingUnknownTextIsEmptyRatherThanFatal() {
            assertThat(ThemeMode.parse("midnight")).isEmpty();
            assertThat(ThemeMode.parse(null)).isEmpty();
            assertThat(ThemeMode.parse("")).isEmpty();
        }
    }

    @Nested
    @DisplayName("AccentPalette")
    class Palettes {

        @Test
        void thereAreExactlyTheFivePalettesThePrdNames() {
            assertThat(AccentPalette.values())
                    .extracting(AccentPalette::displayName)
                    .containsExactly("Indigo", "Emerald", "Amber", "Rose", "Slate");
        }

        @Test
        void indigoIsTheDefault() {
            assertThat(AccentPalette.DEFAULT).isEqualTo(AccentPalette.INDIGO);
        }

        @Test
        void carriesTheApprovedHexValues() {
            assertThat(AccentPalette.INDIGO.light()).isEqualTo("#4f46e5");
            assertThat(AccentPalette.INDIGO.lightSoft()).isEqualTo("#eef2ff");
            assertThat(AccentPalette.INDIGO.dark()).isEqualTo("#818cf8");
            assertThat(AccentPalette.INDIGO.darkSoft()).isEqualTo("#2b2a5e");
            assertThat(AccentPalette.EMERALD.light()).isEqualTo("#047857");
            assertThat(AccentPalette.AMBER.light()).isEqualTo("#b45309");
            assertThat(AccentPalette.ROSE.light()).isEqualTo("#be123c");
            assertThat(AccentPalette.SLATE.light()).isEqualTo("#475569");
        }

        @ParameterizedTest
        @EnumSource(AccentPalette.class)
        void accentSelectionFollowsTheMode(AccentPalette palette) {
            assertThat(palette.accent(false)).isEqualTo(palette.light());
            assertThat(palette.accent(true)).isEqualTo(palette.dark());
            assertThat(palette.accentSoft(false)).isEqualTo(palette.lightSoft());
            assertThat(palette.accentSoft(true)).isEqualTo(palette.darkSoft());
        }

        @Test
        void onAccentIsModeDependentNotPaletteDependent() {
            assertThat(AccentPalette.onAccent(false)).isEqualTo("#ffffff");
            assertThat(AccentPalette.onAccent(true)).isEqualTo("#0d1117");
        }

        @ParameterizedTest
        @EnumSource(AccentPalette.class)
        void keyAndStylesheetNameAgree(AccentPalette palette) {
            assertThat(palette.key()).isEqualTo(palette.name().toLowerCase(java.util.Locale.ROOT));
            assertThat(palette.stylesheet()).isEqualTo("/css/accent-" + palette.key() + ".css");
        }

        @ParameterizedTest
        @EnumSource(AccentPalette.class)
        void parsesItsOwnPersistedForm(AccentPalette palette) {
            assertThat(AccentPalette.parse(palette.name())).contains(palette);
            assertThat(AccentPalette.parse("  " + palette.key() + " ")).contains(palette);
        }

        @Test
        void parsingUnknownTextIsEmptyRatherThanFatal() {
            assertThat(AccentPalette.parse("teal")).isEmpty();
            assertThat(AccentPalette.parse(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("stylesheets ship and match the enum")
    class Stylesheets {

        @Test
        void theTokenLayerIsOnTheClasspath() {
            assertThat(read(ThemeManager.TOKENS_STYLESHEET))
                    .contains("-hsts-bg")
                    .contains("-hsts-danger")
                    .contains(".root.dark");
        }

        @ParameterizedTest
        @EnumSource(AccentPalette.class)
        void eachPaletteHasAStylesheetWithTheSameValues(AccentPalette palette) {
            String css = read(palette.stylesheet());

            assertThat(css).contains(palette.light());
            assertThat(css).contains(palette.lightSoft());
            assertThat(css).contains(palette.dark());
            assertThat(css).contains(palette.darkSoft());
        }

        @ParameterizedTest
        @EnumSource(AccentPalette.class)
        void eachPaletteStylesheetDefinesOnlyTheThreeAccentTokens(AccentPalette palette) {
            List<String> declared = read(palette.stylesheet()).lines()
                    .map(String::trim)
                    .filter(line -> line.startsWith("-"))
                    .map(line -> line.substring(0, line.indexOf(':')).trim())
                    .distinct()
                    .toList();

            assertThat(declared)
                    .containsExactlyInAnyOrder("-hsts-accent", "-hsts-accent-soft", "-hsts-on-accent");
        }

        @ParameterizedTest
        @EnumSource(AccentPalette.class)
        void eachPaletteStylesheetCarriesTheModeCorrectOnAccent(AccentPalette palette) {
            String css = read(palette.stylesheet());

            assertThat(css).contains(AccentPalette.ON_ACCENT_LIGHT);
            assertThat(css).contains(AccentPalette.ON_ACCENT_DARK);
        }

        private String read(String resource) {
            try (InputStream in = ThemeTokensTest.class.getResourceAsStream(resource)) {
                assertThat(in).as("stylesheet %s must ship in the JAR", resource).isNotNull();
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new AssertionError("Could not read " + resource, e);
            }
        }
    }

    @Nested
    @DisplayName("SystemAppearance")
    class OsProbe {

        private static final Map<String, String> NO_PROPS = Map.of();

        @Test
        void theSystemPropertyOverrideWinsOverTheOsProbe() {
            Map<String, String> forcedDark = Map.of(SystemAppearance.OVERRIDE_PROPERTY, "true");
            Map<String, String> forcedLight = Map.of(SystemAppearance.OVERRIDE_PROPERTY, "FALSE");

            assertThat(SystemAppearance.isDark("Windows 11", forcedDark::get, cmd -> {
                throw new AssertionError("must not probe when overridden");
            })).isTrue();

            assertThat(SystemAppearance.isDark("Mac OS X", forcedLight::get, cmd -> {
                throw new AssertionError("must not probe when overridden");
            })).isFalse();
        }

        @Test
        void anUnparseableOverrideFallsThroughToTheProbe() {
            Map<String, String> junk = Map.of(SystemAppearance.OVERRIDE_PROPERTY, "maybe");

            assertThat(SystemAppearance.isDark("Linux", junk::get, cmd -> Optional.empty())).isFalse();
            assertThat(SystemAppearance.parseOverride("maybe")).isEmpty();
            assertThat(SystemAppearance.parseOverride(null)).isEmpty();
            assertThat(SystemAppearance.parseOverride(" TRUE ")).contains(true);
        }

        @Test
        void windowsReportsDarkWhenAppsUseLightThemeIsZero() {
            boolean dark = SystemAppearance.isDark("Windows 11", NO_PROPS::get,
                    cmd -> Optional.of("\nHKEY_CURRENT_USER\\...\\Personalize\n"
                            + "    AppsUseLightTheme    REG_DWORD    0x0\n"));

            assertThat(dark).isTrue();
        }

        @Test
        void windowsReportsLightWhenAppsUseLightThemeIsOne() {
            boolean dark = SystemAppearance.isDark("Windows 10", NO_PROPS::get,
                    cmd -> Optional.of("    AppsUseLightTheme    REG_DWORD    0x1\n"));

            assertThat(dark).isFalse();
        }

        @Test
        void windowsProbeIssuesTheExpectedRegQuery() {
            SystemAppearance.isDark("Windows 11", NO_PROPS::get, cmd -> {
                assertThat(cmd).startsWith("reg", "query");
                assertThat(cmd).anySatisfy(arg -> assertThat(arg).contains("Themes\\Personalize"));
                return Optional.empty();
            });
        }

        @Test
        void aFailedWindowsProbeMeansLight() {
            assertThat(SystemAppearance.isDark("Windows 11", NO_PROPS::get, cmd -> Optional.empty()))
                    .isFalse();
        }

        @Test
        void macReportsDarkOnlyWhenAppleInterfaceStyleSaysSo() {
            assertThat(SystemAppearance.isDark("Mac OS X", NO_PROPS::get,
                    cmd -> Optional.of("Dark\n"))).isTrue();
            assertThat(SystemAppearance.isDark("Mac OS X", NO_PROPS::get,
                    cmd -> Optional.empty())).isFalse();
        }

        @Test
        void everyOtherPlatformIsAssumedLight() {
            assertThat(SystemAppearance.isDark("Linux", NO_PROPS::get, cmd -> Optional.of("Dark")))
                    .isFalse();
            assertThat(SystemAppearance.isDark(null, NO_PROPS::get, cmd -> Optional.empty()))
                    .isFalse();
        }

        @Test
        void registryOutputParsingHandlesDecimalMissingAndMalformedValues() {
            assertThat(SystemAppearance.parseWindowsAppsUseLightTheme(
                    "    AppsUseLightTheme    REG_DWORD    0")).contains(true);
            assertThat(SystemAppearance.parseWindowsAppsUseLightTheme(
                    "    AppsUseLightTheme    REG_DWORD    1")).contains(false);
            assertThat(SystemAppearance.parseWindowsAppsUseLightTheme(
                    "    SystemUsesLightTheme    REG_DWORD    0x0")).isEmpty();
            assertThat(SystemAppearance.parseWindowsAppsUseLightTheme(
                    "    AppsUseLightTheme    REG_SZ    banana")).isEmpty();
            assertThat(SystemAppearance.parseWindowsAppsUseLightTheme("")).isEmpty();
            assertThat(SystemAppearance.parseWindowsAppsUseLightTheme(null)).isEmpty();
        }

        @Test
        void theRealProbeNeverThrowsOnThisMachine() {
            // Whatever the CI box is, isDark() must return a boolean, not blow up.
            assertThat(SystemAppearance.isDark()).isIn(true, false);
        }

        @Test
        void aMissingProbeBinaryYieldsEmptyInsteadOfThrowing() {
            // The macOS probe on Windows, or the Windows probe on Linux: the
            // binary simply is not there, and startup must not care.
            assertThat(SystemAppearance.runCommand(
                    List.of("hsts-no-such-appearance-tool", "--please"))).isEmpty();
        }

        @Test
        void aNonZeroExitYieldsEmpty() {
            // `defaults read` exits non-zero when the key is unset, which is
            // precisely how macOS reports "light mode".
            assertThat(SystemAppearance.runCommand(shell("exit 3"))).isEmpty();
        }

        @Test
        void aSuccessfulProbeReturnsItsOutput() {
            assertThat(SystemAppearance.runCommand(shell("echo Dark")))
                    .hasValueSatisfying(out -> assertThat(out).contains("Dark"));
        }

        /** A tiny command that works on both the Windows and the Linux build boxes. */
        private List<String> shell(String script) {
            return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                    ? List.of("cmd", "/c", script)
                    : List.of("sh", "-c", script);
        }
    }
}
