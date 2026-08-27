package client.features.connect;

import client.core.ServerEndpoint;
import client.core.ServerPin;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the client should do about connecting, before it shows anybody anything
 * (Presentation tier, E19.10 / E19.11, F13.4).
 *
 * <h2>The ruling this implements</h2>
 *
 * <p>E19.11: <b>with a pinned reachable server the first screen is Login.</b> Not
 * a connect screen, not a picker, not a spinner the user has to dismiss. The host
 * and port belong on the server console, and a student opening a client on a
 * classroom machine has no business being asked for an IP address. Login carries
 * one subtle line saying which server it is on, with "change server" beside it,
 * and that line is the entire connection user interface in the normal case.
 *
 * <p>The host and port editor appears in exactly three situations, and this class
 * is where all three are decided: discovery found nothing and there is nothing
 * pinned, the pinned server could not be reached, or the user asked for it.
 *
 * <h2>The decision table</h2>
 *
 * <pre>
 *   pinned?  discovery result                        → step
 *   ------------------------------------------------------------------------
 *   yes      the pinned address answered, same id    → CONNECT      (silent)
 *   yes      the pinned address answered, other id   → CONFIRM_CHANGED_SERVER
 *   yes      nothing answered at all                 → CONNECT      (silent, blind)
 *   yes      others answered, the pinned one did not → CHOOSE_SERVER
 *   no       exactly one answered                    → CONNECT      (then pin: TOFU)
 *   no       several answered                        → CHOOSE_SERVER
 *   no       none answered                           → MANUAL_ENTRY
 * </pre>
 *
 * <p>The third row is the one worth defending. A pinned client that hears nothing
 * still tries the pinned address, because "discovery found nothing" and "the
 * server is gone" are different facts and the common cause of the first is a
 * network that drops broadcasts. Client-isolation Wi-Fi is normal in schools, and
 * it would be absurd for a client to refuse to connect to a server it connected to
 * yesterday because a broadcast was filtered. If the connect then fails,
 * {@link #afterFailedConnect} turns it into manual entry with a sentence saying
 * what happened.
 *
 * <p>Row five is trust on first use: one server, nothing pinned, so connect and
 * remember it. Row six declines to guess between several strangers, which is the
 * only case where a first-run user is asked anything.
 *
 * <p>Every method here is pure. The whole flow is a function of a pin and a list,
 * which is why the state machine is unit-tested exhaustively while the screen
 * that renders it stays a thin view.
 */
public final class ConnectFlow {

    /** What the client does next. */
    public enum Step {
        /** Connect straight away and show Login. Nothing is asked. */
        CONNECT,
        /** Show the picker: several servers, or the pinned one is missing. */
        CHOOSE_SERVER,
        /**
         * Stop and warn: this address is answering with a different id than the one
         * pinned. Requires an explicit confirm, and re-pins on it.
         */
        CONFIRM_CHANGED_SERVER,
        /** Show the host and port editor. The fallback, never the default. */
        MANUAL_ENTRY
    }

    /**
     * The decision, with everything the screen needs to act on it.
     *
     * @param step        what to do
     * @param endpoint    where to connect, present for {@link Step#CONNECT} and
     *                    {@link Step#CONFIRM_CHANGED_SERVER}
     * @param serverName  the friendly name for Login's status line, may be blank
     * @param fingerprint the id to pin once the connect succeeds, may be
     *                    {@code null} when the server was not discovered
     * @param choices     the picker's rows, only for {@link Step#CHOOSE_SERVER}
     * @param message     what to say on screen; blank for the silent path, because
     *                    the silent path shows nothing
     */
    public record Decision(Step step,
                           ServerEndpoint endpoint,
                           String serverName,
                           String fingerprint,
                           List<DiscoveredServer> choices,
                           String message) {

        public Decision {
            Objects.requireNonNull(step, "step");
            serverName = serverName == null ? "" : serverName;
            message = message == null ? "" : message;
            choices = List.copyOf(choices == null ? List.of() : choices);
        }

        /** @return where to connect, empty for the two steps that ask first. */
        public Optional<ServerEndpoint> target() {
            return Optional.ofNullable(endpoint);
        }

        /** @return the id to pin on a successful connect, empty when unknown. */
        public Optional<String> fingerprintToPin() {
            return Optional.ofNullable(fingerprint);
        }

        /** @return {@code true} when the user is not asked anything. */
        public boolean isSilent() {
            return step == Step.CONNECT;
        }
    }

    /** Shown when a pinned address answers with an id nobody has seen before. */
    public static final String CHANGED_SERVER_TITLE = "This may not be your usual server";

    /** The manual-entry sentence when nothing was found on the network. */
    public static final String NOTHING_FOUND =
            "No server answered on this network. Enter the address shown on the server console.";

    /** The picker's sentence when the pinned server is not among the answers. */
    public static final String PINNED_MISSING =
            "The server you used last time did not answer. Choose one of these, "
                    + "or enter an address by hand.";

    /** The picker's sentence when several strangers answered. */
    public static final String SEVERAL_FOUND =
            "More than one server answered. Choose the one your teacher named.";

    // --- why a connect failed, in the user's language (B-37) --------------

    /** The connect was refused: something is at that address, nothing is listening on the port. */
    public static final String UNREACHABLE_REFUSED = "Nothing is listening on that address.";

    /** The connect timed out: nothing answered in time. */
    public static final String UNREACHABLE_TIMEOUT = "That address did not answer.";

    /** The host name could not be resolved. */
    public static final String UNREACHABLE_UNKNOWN_HOST =
            "That name could not be found on this network.";

    /** There is no route to that address from here — usually a different subnet. */
    public static final String UNREACHABLE_NO_ROUTE =
            "That address cannot be reached from this network.";

    private ConnectFlow() {
    }

    /**
     * The decision table above, as a function.
     *
     * @param pin   what this client trusts, empty on a first ever run
     * @param found what discovery heard back, possibly empty
     * @return what to do next
     */
    public static Decision decide(Optional<ServerPin> pin, List<DiscoveredServer> found) {
        Objects.requireNonNull(pin, "pin");
        List<DiscoveredServer> servers = found == null ? List.of() : found;

        if (pin.isPresent()) {
            return withPin(pin.get(), servers);
        }
        return withoutPin(servers);
    }

    /** @return a silent connect to a discovered server, pinning its id on success. */
    private static Decision connect(DiscoveredServer server) {
        return new Decision(Step.CONNECT, server.endpoint(), server.name(),
                server.fingerprint(), List.of(), "");
    }

    private static Decision withPin(ServerPin pin, List<DiscoveredServer> servers) {
        Optional<DiscoveredServer> atPinnedAddress = servers.stream()
                .filter(server -> pin.isSameEndpoint(server.endpoint()))
                .findFirst();

        if (atPinnedAddress.isPresent()) {
            DiscoveredServer server = atPinnedAddress.get();
            if (pin.matches(server.fingerprint())) {
                return connect(server);
            }
            return new Decision(Step.CONFIRM_CHANGED_SERVER, server.endpoint(), server.name(),
                    server.fingerprint(), servers, changedServerMessage(pin, server));
        }
        if (servers.isEmpty()) {
            // Discovery heard nothing, which on a school network usually means
            // broadcasts are filtered rather than that the server is gone.
            return new Decision(Step.CONNECT, pin.endpoint(), "", pin.fingerprint(),
                    List.of(), "");
        }
        return new Decision(Step.CHOOSE_SERVER, null, "", null, servers, PINNED_MISSING);
    }

    private static Decision withoutPin(List<DiscoveredServer> servers) {
        if (servers.size() == 1) {
            // Trust on first use: one candidate, nothing to disambiguate.
            return connect(servers.get(0));
        }
        if (servers.isEmpty()) {
            return new Decision(Step.MANUAL_ENTRY, null, "", null, List.of(), NOTHING_FOUND);
        }
        return new Decision(Step.CHOOSE_SERVER, null, "", null, servers, SEVERAL_FOUND);
    }

    /**
     * What to do when the user picks a row in the picker.
     *
     * <p>Not simply "connect": a chosen server can still be the pinned address
     * with a changed id, and picking it by hand does not make that less worth
     * asking about.
     *
     * @param pin    what this client trusts
     * @param chosen the row the user clicked
     * @return {@link Step#CONNECT} or {@link Step#CONFIRM_CHANGED_SERVER}
     */
    public static Decision select(Optional<ServerPin> pin, DiscoveredServer chosen) {
        Objects.requireNonNull(pin, "pin");
        Objects.requireNonNull(chosen, "chosen");
        if (pin.isPresent() && pin.get().isSameEndpoint(chosen.endpoint())
                && !pin.get().matches(chosen.fingerprint())) {
            return new Decision(Step.CONFIRM_CHANGED_SERVER, chosen.endpoint(), chosen.name(),
                    chosen.fingerprint(), List.of(), changedServerMessage(pin.get(), chosen));
        }
        return connect(chosen);
    }

    /**
     * What to do after the user confirms a changed server.
     *
     * @param confirmed the server they accepted
     * @return a connect decision that will re-pin the new id on success
     */
    public static Decision confirmChangedServer(DiscoveredServer confirmed) {
        Objects.requireNonNull(confirmed, "confirmed");
        return connect(confirmed);
    }

    /**
     * What to do when a connect attempt failed (E19.11's "unreachable" case).
     *
     * <h2>It takes the throwable, and that is the fix ⚑ (B-37)</h2>
     *
     * <p>This used to take a {@code String reason} and fold it into the sentence in brackets,
     * and {@code ConnectView} computed that string as {@code cause.getMessage() == null ?
     * cause.getClass().getSimpleName() : cause.getMessage()}. A throwable with no message —
     * {@code SocketTimeoutException} is the ordinary one here — therefore produced <i>"Could
     * not reach 192.168.1.5:5555 (SocketTimeoutException). Check the server is running…"</i>
     * on the first screen anyone sees at the defence. PRD §4.1 says a user never meets an
     * error code or a stack trace, and a Java class name is one.
     *
     * <p><b>Taking the {@link Throwable} rather than a string is what makes that
     * unrepresentable</b> instead of merely fixed: there is no longer a parameter a caller
     * could pass a JDK string to. {@link #reasonFor(Throwable)} maps the small closed set of
     * causes to product copy and answers {@code ""} for everything else, and the throwable
     * itself goes to the log where it belongs.
     *
     * <p><b>And there are no brackets any more.</b> A cause the product has a sentence for
     * gets a sentence of its own between the address and the instruction; a cause it does not
     * recognise leaves the message two sentences long rather than a bracket with a class name
     * in it. That is the reconnect banner's own discipline — {@code showDisconnected(String
     * serverLabel)} takes no detail parameter at all, and {@code ConnectionLostEvent}'s
     * javadoc says the technical reason is "never shown as the primary message".
     *
     * @param attempted where the client tried to go
     * @param cause     what the connect attempt threw, unwrapped or not; may be {@code null}
     * @return manual entry, with a sentence that names the address and the next step
     */
    public static Decision afterFailedConnect(ServerEndpoint attempted, Throwable cause) {
        String where = attempted == null ? "the remembered server" : attempted.display();
        String reason = reasonFor(cause);
        return new Decision(Step.MANUAL_ENTRY, null, "", null, List.of(),
                "Could not reach " + where + "."
                        + (reason.isEmpty() ? "" : " " + reason)
                        + " Check the server is running, then enter the address "
                        + "shown on its console.");
    }

    /**
     * The product's sentence for why a connect failed (B-37).
     *
     * <p>Walks the cause chain, because the interesting exception arrives wrapped — a
     * {@code CompletionException} around an {@code IOException} around the real one — and
     * matching only the outermost type works until it does not. That is
     * {@code ExtendService.isStaleWrite}'s reasoning, applied to a screen.
     *
     * <p><b>Anything unrecognised answers {@code ""}, deliberately.</b> The alternative is a
     * default that leaks: "an unexpected error" is noise, and the throwable's own text is how
     * B-37 happened. A user who is told the address could not be reached and what to do about
     * it has everything the screen can honestly give her; the rest is in the log.
     *
     * @param cause the failure, or {@code null}
     * @return a whole sentence, or {@code ""} when the product has nothing true to say
     */
    public static String reasonFor(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof java.net.UnknownHostException) {
                return UNREACHABLE_UNKNOWN_HOST;
            }
            if (current instanceof java.net.SocketTimeoutException) {
                return UNREACHABLE_TIMEOUT;
            }
            if (current instanceof java.net.NoRouteToHostException) {
                return UNREACHABLE_NO_ROUTE;
            }
            if (current instanceof java.net.ConnectException) {
                return UNREACHABLE_REFUSED;
            }
            if (current.getCause() == current) {
                // A self-referential cause chain would loop forever. Rare, and cheap to refuse.
                break;
            }
        }
        return "";
    }

    /**
     * What to do when the user clicks "change server" on Login (E19.11).
     *
     * @return manual entry with no scolding message; this is a deliberate action,
     *         not a failure
     */
    public static Decision changeServerRequested() {
        return new Decision(Step.MANUAL_ENTRY, null, "", null, List.of(),
                "Enter the address shown on the server console.");
    }

    /**
     * The status line Login carries when the client connected by itself (E19.11).
     *
     * @param serverName the pinned or discovered name, may be blank
     * @param endpoint   where it connected
     * @return {@code "Connected to Room 12 server"}, falling back to the address
     *         when the server announced no name and to a bare sentence when
     *         neither is known
     */
    public static String statusLine(String serverName, ServerEndpoint endpoint) {
        if (serverName != null && !serverName.isBlank()) {
            return "Connected to " + serverName.trim();
        }
        return endpoint == null ? "Connected" : "Connected to " + endpoint.display();
    }

    /** @return the "change server" affordance's label, kept in one place. */
    public static String changeServerLabel() {
        return "change server";
    }

    /**
     * The mismatch warning's body.
     *
     * <p>Says what changed, in the terms the pin can actually support: the machine
     * at this address is not the one that was here. It does not say "an attacker",
     * because the id cannot tell the difference and the sentence would be a claim
     * the product cannot keep.
     */
    static String changedServerMessage(ServerPin pin, DiscoveredServer found) {
        return "The server at " + found.endpoint().display() + " now identifies itself as "
                + found.shortFingerprint() + ", but this computer connected to "
                + pin.shortFingerprint() + " at that address before. That usually means the "
                + "server was reinstalled or replaced. Check with your teacher that this is "
                + "the right server before you continue.";
    }
}
