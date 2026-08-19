package client.core;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Parsed client command-line switches (Presentation tier, E4.1).
 *
 * <p>Only development switches live here; real configuration is
 * {@code client.properties} (F14.1). Each switch is accepted both as an argument
 * and as a {@code -D} system property, because a double-clicked Fat JAR has no
 * convenient place to put arguments while an IDE run configuration has no
 * convenient place to put anything else.
 *
 * @param gallery boot straight into the component gallery instead of Connect
 */
public record AppArgs(boolean gallery) {

    /** Command-line form of the gallery switch. */
    public static final String FLAG_GALLERY = "--gallery";

    /** System-property form of the gallery switch. */
    public static final String PROP_GALLERY = "hsts.gallery";

    /** @return args with every switch off. */
    public static AppArgs none() {
        return new AppArgs(false);
    }

    /** Parses real program arguments against real system properties. */
    public static AppArgs parse(String[] args) {
        return parse(args == null ? List.of() : List.of(args), System::getProperty);
    }

    /**
     * Parsing core with the property source injected — visible for testing so the
     * argument branch and the system-property branch are each exercised without
     * mutating global state.
     *
     * @param args      program arguments (never {@code null})
     * @param properties lookup for system properties, may return {@code null}
     */
    public static AppArgs parse(List<String> args, Function<String, String> properties) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(properties, "properties");
        boolean gallery = hasFlag(args, FLAG_GALLERY) || isTrue(properties.apply(PROP_GALLERY));
        return new AppArgs(gallery);
    }

    private static boolean hasFlag(List<String> args, String flag) {
        for (String arg : args) {
            if (arg != null && flag.equalsIgnoreCase(arg.trim())) {
                return true;
            }
        }
        return false;
    }

    /** A bare {@code -Dhsts.gallery} (empty value) counts as "on", like most CLIs. */
    private static boolean isTrue(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() || Boolean.parseBoolean(trimmed);
    }
}
