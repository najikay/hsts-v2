package client.ui.theme;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Best-effort detection of the operating system's light/dark preference
 * (Presentation tier, E4.7 — the OS half of {@link ThemeMode#SYSTEM}).
 *
 * <p>JavaFX 21 has no appearance API ({@code Platform.getPreferences()} lands in
 * JavaFX 22), so this reads the platform directly:
 * <ul>
 *   <li><b>Windows</b> — {@code HKCU\...\Themes\Personalize\AppsUseLightTheme};
 *       {@code 0x0} means dark. This is the demo platform (ARCHITECTURE §9).</li>
 *   <li><b>macOS</b> — {@code defaults read -g AppleInterfaceStyle} prints
 *       {@code Dark} only when dark mode is on.</li>
 *   <li><b>anything else</b> — light, which is the safe assumption.</li>
 * </ul>
 *
 * <p>The system property {@code hsts.ui.systemDark} overrides the probe entirely
 * ({@code true}/{@code false}), which is how the gallery demonstrates SYSTEM mode
 * on a machine whose OS says otherwise.
 *
 * <p>Detection is a one-shot probe, not a live subscription: an OS appearance
 * change mid-session is picked up on the next {@code ThemeState.refreshSystem()}
 * (called on window focus). Every failure path returns "light" — this is a
 * cosmetic preference and must never be able to stop the client starting.
 */
public final class SystemAppearance {

    private static final Logger log = LoggerFactory.getLogger(SystemAppearance.class);

    /** Overrides the OS probe; useful for demos, screenshots and tests. */
    public static final String OVERRIDE_PROPERTY = "hsts.ui.systemDark";

    /** The probe must never make startup feel slow. */
    private static final long PROBE_TIMEOUT_SECONDS = 2;

    private SystemAppearance() {
    }

    /** @return {@code true} when the OS is currently in dark mode. */
    public static boolean isDark() {
        return isDark(System.getProperty("os.name", ""), System::getProperty, SystemAppearance::runCommand);
    }

    /**
     * Detection core with every environment touch injected — visible for testing
     * so the Windows, macOS, override and fallback branches are all exercised
     * without depending on the machine running the build.
     *
     * @param osName     value of {@code os.name}
     * @param properties system-property lookup (may return {@code null})
     * @param commands   runs a command and returns its stdout, empty on any failure
     */
    static boolean isDark(String osName,
                          Function<String, String> properties,
                          Function<List<String>, Optional<String>> commands) {
        Optional<Boolean> override = parseOverride(properties.apply(OVERRIDE_PROPERTY));
        if (override.isPresent()) {
            return override.get();
        }
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return commands.apply(List.of("reg", "query",
                            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                            "/v", "AppsUseLightTheme"))
                    .flatMap(SystemAppearance::parseWindowsAppsUseLightTheme)
                    .orElse(false);
        }
        if (os.contains("mac")) {
            return commands.apply(List.of("defaults", "read", "-g", "AppleInterfaceStyle"))
                    .map(out -> out.toLowerCase(Locale.ROOT).contains("dark"))
                    .orElse(false);
        }
        return false;
    }

    /** @return the override value, empty when unset or not a boolean literal. */
    static Optional<Boolean> parseOverride(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value)) {
            return Optional.of(true);
        }
        if ("false".equals(value)) {
            return Optional.of(false);
        }
        return Optional.empty();
    }

    /**
     * Reads {@code reg query} output such as
     * {@code "    AppsUseLightTheme    REG_DWORD    0x0"}.
     *
     * @return {@code true} for dark (value 0), {@code false} for light (non-zero),
     *         empty when the value is missing or unparseable
     */
    static Optional<Boolean> parseWindowsAppsUseLightTheme(String output) {
        if (output == null) {
            return Optional.empty();
        }
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.toLowerCase(Locale.ROOT).startsWith("appsuselighttheme")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            String value = parts[parts.length - 1];
            try {
                int parsed = value.toLowerCase(Locale.ROOT).startsWith("0x")
                        ? Integer.parseInt(value.substring(2), 16)
                        : Integer.parseInt(value);
                return Optional.of(parsed == 0);
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Runs a probe command; any failure (missing binary, timeout, non-zero exit)
     * yields empty. Package-private so the failure paths can be exercised
     * directly rather than only through a platform that happens to have the tool.
     */
    static Optional<String> runCommand(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            return process.exitValue() == 0 ? Optional.of(output) : Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.debug("OS appearance probe {} failed: {}", command.get(0), e.getMessage());
            return Optional.empty();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
