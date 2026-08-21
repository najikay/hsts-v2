package server.console;

import common.dto.discovery.Fingerprints;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything the console header knows and decides, with no JavaFX in sight
 * (Logic tier, E19.2 / E19.5 / F13.2).
 *
 * <p>The header is the single most important thing the server jar draws: it is
 * the sentence an operator reads aloud to a room, and the string a student types
 * into a client. So the address it shows, the addresses it offers instead, what
 * "copy" puts on the clipboard and what the start/stop button says are all
 * decisions, and they are all made here, where they are asserted.
 *
 * <h2>Detected address versus manual override</h2>
 *
 * <p>{@link NetworkDetector} ranks well and can still be wrong: a machine with two
 * plausible interfaces, a demo network whose gateway the probe cannot reach, a
 * room where the projector laptop is on a different VLAN from the students. The
 * override exists for those, and it accepts free text as well as the detected
 * list, because the case where the operator knows better than the heuristic is
 * exactly the case where the right value is not in the list.
 *
 * <p>An override is validated but not verified. This class can tell that
 * {@code "192.168.1.42"} is a usable-looking address and cannot tell whether any
 * packet will reach it, and pretending otherwise would be worse than the honest
 * answer the room provides within seconds.
 */
public final class ConsoleModel {

    /** What the header says before any interface has been detected. */
    public static final String NO_ADDRESS = "no network address";

    /** Rejection message for an override that is empty. */
    public static final String ADDRESS_REQUIRED =
            "Enter the address clients should use, for example 192.168.1.42";

    /** Rejection message for an override with a port glued on. */
    public static final String ADDRESS_HAS_PORT =
            "Enter the address only. The port is set separately, below the header";

    /** Rejection message for an override with whitespace in it. */
    public static final String ADDRESS_HAS_SPACES = "An address cannot contain spaces";

    private final List<NetworkAddress> detected;
    private final int port;
    private final String fingerprint;

    private String selectedIp;
    private boolean listening;
    private boolean discoveryEnabled = true;

    /**
     * @param detected    the ranked addresses; may be empty on a machine with no
     *                    network at all, which is a state and not a failure
     * @param port        the OCSF listening port
     * @param fingerprint this server's persisted discovery id (E19.8), or
     *                    {@code null} when discovery is not configured
     */
    public ConsoleModel(List<NetworkAddress> detected, int port, String fingerprint) {
        this.detected = List.copyOf(Objects.requireNonNull(detected, "detected"));
        this.port = port;
        this.fingerprint = fingerprint;
        this.selectedIp = this.detected.isEmpty() ? null : this.detected.get(0).ip();
    }

    // -------------------------------------------------------------- header

    /**
     * @return {@code "192.168.1.42:5555"}, the string shown at 40 points and the
     *         string {@link #clipboardText()} copies. On a machine with no address
     *         it says so in words rather than showing {@code "null:5555"}
     */
    public String headerText() {
        return selectedIp == null ? NO_ADDRESS : selectedIp + ':' + port;
    }

    /**
     * @return what the copy button puts on the clipboard (E19.5). The same string
     *         the header shows, because an operator who copies what they can see
     *         and gets something else has been lied to
     */
    public String clipboardText() {
        return headerText();
    }

    /** @return the currently chosen address, empty when none was detected. */
    public Optional<String> selectedIp() {
        return Optional.ofNullable(selectedIp);
    }

    public int port() {
        return port;
    }

    /**
     * @return the header's second line: the discovery id, or a note that discovery
     *         is off. Never blank, because an empty gap under a big address reads
     *         as a rendering bug
     */
    public String fingerprintText() {
        if (fingerprint == null || fingerprint.isBlank()) {
            return "Discovery is not configured on this server";
        }
        return "ID " + shortFingerprint();
    }

    /**
     * @return the eight-character grouped form shown beside the address, per
     *         F13.3's {@code "192.168.1.42:5555 · ID 7F3A-2B91"}
     */
    public String shortFingerprint() {
        return Fingerprints.shortForm(fingerprint);
    }

    // ------------------------------------------------------------ addresses

    /**
     * @return every address the picker offers, best first, with the current
     *         selection included even when it was typed by hand
     */
    public List<NetworkAddress> addressChoices() {
        List<NetworkAddress> choices = new ArrayList<>(detected);
        if (selectedIp != null && detected.stream().noneMatch(a -> a.ip().equals(selectedIp))) {
            // A manual override belongs in the list it is not a member of, or the
            // picker would show a different address from the header.
            choices.add(0, new NetworkAddress(selectedIp, "entered by hand", false));
        }
        return List.copyOf(new LinkedHashSet<>(choices));
    }

    /** @return the ranked addresses exactly as detected, for diagnostics and tests. */
    public List<NetworkAddress> detectedAddresses() {
        return detected;
    }

    /**
     * Applies a manual override (E19.5).
     *
     * @param raw whatever the operator typed or picked
     * @return the rejection message when it is unusable, empty when it was applied
     */
    public Optional<String> selectAddress(String raw) {
        Optional<String> rejection = validateAddress(raw);
        if (rejection.isPresent()) {
            return rejection;
        }
        this.selectedIp = raw.trim();
        return Optional.empty();
    }

    /** Returns the header to the best detected address. */
    public void resetAddress() {
        this.selectedIp = detected.isEmpty() ? null : detected.get(0).ip();
    }

    /**
     * @param raw the candidate override
     * @return why it cannot be used, empty when it can. Every message names the
     *         fix, per the house copy rule
     */
    public static Optional<String> validateAddress(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Optional.of(ADDRESS_REQUIRED);
        }
        String address = raw.trim();
        if (address.contains(" ")) {
            return Optional.of(ADDRESS_HAS_SPACES);
        }
        if (address.contains(":")) {
            return Optional.of(ADDRESS_HAS_PORT);
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------- state

    public boolean isListening() {
        return listening;
    }

    public void setListening(boolean listening) {
        this.listening = listening;
    }

    /** @return the verb on the start/stop button; a verb, never "OK" or "Toggle". */
    public String listenButtonText() {
        return listening ? "Stop listening" : "Start listening";
    }

    /**
     * @return the sentence beside the button. Stopped says what it means for
     *         people in the room, because "stopped" alone invites the operator to
     *         wonder whether the exams in progress are gone
     */
    public String listenStatusText() {
        return listening
                ? "Listening on port " + port + ". Clients can connect."
                : "Not listening. New clients cannot connect. Exams already in progress "
                        + "keep running and their timers keep counting.";
    }

    public boolean isDiscoveryEnabled() {
        return discoveryEnabled;
    }

    public void setDiscoveryEnabled(boolean enabled) {
        this.discoveryEnabled = enabled;
    }

    /** @return the label beside the discovery toggle (E19.8). */
    public String discoveryStatusText() {
        return discoveryEnabled
                ? "Answering discovery broadcasts. Clients find this server by themselves."
                : "Discovery is off. Clients need the address above typed in by hand.";
    }
}
