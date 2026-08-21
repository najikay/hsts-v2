package server.core;

import common.dto.discovery.DiscoveryProtocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The server's command-line switches, parsed (Logic tier, E19.5 / F13.2).
 *
 * <p>A record rather than a block of {@code if}s inside {@link ServerMain},
 * because {@code --headless} is the switch the whole console feature hangs off:
 * the promise is that the flag runs the server exactly as it ran before E19, and
 * "exactly as before" is a claim worth a test rather than an inspection.
 * {@code ServerMain} is excluded from the coverage gate as transport glue; this
 * is not, and it is where the decisions are.
 *
 * <h2>Accepted forms</h2>
 *
 * <pre>
 *   --headless              run terminal-only, open no window
 *   --port 5555             the OCSF listening port
 *   --port=5555             the same, in the other common spelling
 *   --discovery-port 5556   the UDP port the discovery responder answers on
 *   --no-discovery          start with the discovery responder off
 *   5555                    a bare port, kept because that is what the pre-E19
 *                           server accepted as args[0] and somebody's shortcut
 *                           still has it
 * </pre>
 *
 * <h2>Bad input warns, it does not stop</h2>
 *
 * <p>An unparseable port produces a warning in {@link #warnings()} and the
 * default, rather than an exit. The one machine this rule is written for is the
 * demo laptop: a server that starts on the standard port having said "I ignored
 * '--port five thousand'" is recoverable in the ten seconds before a defence,
 * and one that refuses to start is not.
 */
public record ServerArgs(boolean headless,
                         int port,
                         int discoveryPort,
                         boolean discoveryEnabled,
                         List<String> warnings) {

    /** The OCSF port used when nobody says otherwise. */
    public static final int DEFAULT_PORT = 5555;

    /** Run without the JavaFX console. */
    public static final String FLAG_HEADLESS = "--headless";

    /** Start with discovery off (F13.3's toggle, in its boot form). */
    public static final String FLAG_NO_DISCOVERY = "--no-discovery";

    /** The OCSF port switch. */
    public static final String OPT_PORT = "--port";

    /** The discovery port switch. */
    public static final String OPT_DISCOVERY_PORT = "--discovery-port";

    public ServerArgs {
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    /** @return the defaults: console up, standard ports, discovery on. */
    public static ServerArgs defaults() {
        return new ServerArgs(false, DEFAULT_PORT,
                DiscoveryProtocol.DEFAULT_DISCOVERY_PORT, true, List.of());
    }

    /**
     * Parses real program arguments.
     *
     * @param args the array from {@code main}; {@code null} is treated as empty
     * @return the parsed switches, with a warning per argument that could not be
     *         used and never an exception
     */
    public static ServerArgs parse(String[] args) {
        return parse(args == null ? List.of() : java.util.Arrays.asList(args));
    }

    /** @see #parse(String[]) */
    public static ServerArgs parse(List<String> args) {
        Objects.requireNonNull(args, "args");
        List<String> warnings = new ArrayList<>();
        boolean headless = false;
        boolean discovery = true;
        int port = DEFAULT_PORT;
        int discoveryPort = DiscoveryProtocol.DEFAULT_DISCOVERY_PORT;

        for (int i = 0; i < args.size(); i++) {
            String raw = args.get(i);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String arg = raw.trim();
            String lower = arg.toLowerCase(Locale.ROOT);

            if (FLAG_HEADLESS.equals(lower)) {
                headless = true;
            } else if (FLAG_NO_DISCOVERY.equals(lower)) {
                discovery = false;
            } else if (lower.startsWith(OPT_DISCOVERY_PORT)) {
                ValueAt value = valueOf(args, i, arg, OPT_DISCOVERY_PORT, warnings);
                i = value.nextIndex();
                discoveryPort = parsePort(value.value(), OPT_DISCOVERY_PORT, discoveryPort, warnings);
            } else if (lower.startsWith(OPT_PORT)) {
                ValueAt value = valueOf(args, i, arg, OPT_PORT, warnings);
                i = value.nextIndex();
                port = parsePort(value.value(), OPT_PORT, port, warnings);
            } else if (isNumeric(arg)) {
                // The pre-E19 form: `java -jar hsts-server.jar 5556`.
                port = parsePort(arg, "port", port, warnings);
            } else {
                warnings.add("Ignored unknown option '" + arg + "'. Supported options are "
                        + FLAG_HEADLESS + ", " + OPT_PORT + " <n>, "
                        + OPT_DISCOVERY_PORT + " <n> and " + FLAG_NO_DISCOVERY + ".");
            }
        }
        return new ServerArgs(headless, port, discoveryPort, discovery, warnings);
    }

    /** @return {@code true} when no argument was rejected. */
    public boolean isClean() {
        return warnings.isEmpty();
    }

    /** A parsed option value and the index the loop should continue from. */
    private record ValueAt(String value, int nextIndex) {
    }

    /**
     * Reads {@code --opt=value} or {@code --opt value}.
     *
     * @return the text after the option, which may be blank when it was the last
     *         argument; {@link #parsePort} then warns about it
     */
    private static ValueAt valueOf(List<String> args, int index, String arg,
                                   String option, List<String> warnings) {
        if (arg.length() > option.length() && arg.charAt(option.length()) == '=') {
            return new ValueAt(arg.substring(option.length() + 1), index);
        }
        if (!arg.equalsIgnoreCase(option)) {
            warnings.add("Ignored unknown option '" + arg + "'. Did you mean "
                    + option + " <n>?");
            return new ValueAt("", index);
        }
        if (index + 1 >= args.size()) {
            return new ValueAt("", index);
        }
        return new ValueAt(args.get(index + 1), index + 1);
    }

    private static int parsePort(String raw, String option, int fallback, List<String> warnings) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            warnings.add(option + " needs a number after it. Using " + fallback + ".");
            return fallback;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            warnings.add("Port '" + value + "' is not a number. Using " + fallback + ".");
            return fallback;
        }
        if (parsed < 1 || parsed > 65535) {
            warnings.add("Port " + parsed + " is outside 1 to 65535. Using " + fallback + ".");
            return fallback;
        }
        return parsed;
    }

    private static boolean isNumeric(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
