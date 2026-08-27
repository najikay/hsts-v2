package client.features.connect;

import client.core.ServerEndpoint;
import client.core.ServerPin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The connect decision table (E19.10 / E19.11, F13.4).
 *
 * <p>Every row of {@link ConnectFlow}'s table has a test here, because the lead's
 * ruling is a promise about what a student sees on launch and the promise is only
 * as good as the state machine that keeps it. The pure shape is what makes that
 * affordable: a pin, a list, and an assertion.
 */
class ConnectFlowTest {

    private static final ServerEndpoint ROOM_12 = new ServerEndpoint("192.168.1.42", 5555);
    private static final ServerEndpoint LAB_B = new ServerEndpoint("192.168.1.43", 5555);

    private static final String ID_12 = "7f3a2b91-1111-2222-3333-444444444444";
    private static final String ID_OTHER = "aabbccdd-9999-8888-7777-666666666666";

    private static final DiscoveredServer ROOM_12_SERVER =
            new DiscoveredServer("Room 12 server", ROOM_12, ID_12);
    private static final DiscoveredServer ROOM_12_REPLACED =
            new DiscoveredServer("Room 12 server", ROOM_12, ID_OTHER);
    private static final DiscoveredServer LAB_B_SERVER =
            new DiscoveredServer("Lab B", LAB_B, ID_OTHER);

    private static Optional<ServerPin> pinnedToRoom12() {
        return Optional.of(new ServerPin(ROOM_12, ID_12));
    }

    @Nested
    @DisplayName("with a pinned server")
    class Pinned {

        @Test
        @DisplayName("the pinned server answering with its own id connects silently")
        void silentAutoConnect() {
            ConnectFlow.Decision decision =
                    ConnectFlow.decide(pinnedToRoom12(), List.of(ROOM_12_SERVER));

            assertThat(decision.step()).isEqualTo(ConnectFlow.Step.CONNECT);
            assertThat(decision.isSilent())
                    .as("E19.11: with a pinned reachable server the first screen is Login")
                    .isTrue();
            assertThat(decision.target()).contains(ROOM_12);
            assertThat(decision.serverName()).isEqualTo("Room 12 server");
            assertThat(decision.fingerprintToPin()).contains(ID_12);
            assertThat(decision.message())
                    .as("the silent path shows nothing, so it says nothing")
                    .isEmpty();
        }

        @Test
        @DisplayName("the pinned server answering with a different id stops and warns")
        void mismatchWarns() {
            ConnectFlow.Decision decision =
                    ConnectFlow.decide(pinnedToRoom12(), List.of(ROOM_12_REPLACED));

            assertThat(decision.step()).isEqualTo(ConnectFlow.Step.CONFIRM_CHANGED_SERVER);
            assertThat(decision.target()).contains(ROOM_12);
            assertThat(decision.fingerprintToPin()).contains(ID_OTHER);
            assertThat(decision.message())
                    .contains("192.168.1.42:5555")
                    .contains("AABB-CCDD")
                    .contains("7F3A-2B91")
                    .contains("Check with your teacher");
        }

        @Test
        @DisplayName("the warning claims change, never impersonation")
        void honestWording() {
            String message = ConnectFlow.decide(pinnedToRoom12(), List.of(ROOM_12_REPLACED))
                    .message();

            assertThat(message)
                    .as("the id cannot detect an impostor, so the sentence must not claim it can")
                    .doesNotContainIgnoringCase("attacker")
                    .doesNotContainIgnoringCase("impersonat")
                    .contains("reinstalled or replaced");
            assertThat(ConnectFlow.CHANGED_SERVER_TITLE).isEqualTo("This may not be your usual server");
        }

        @Test
        @DisplayName("hearing nothing still tries the pinned address")
        void blindConnectWhenDiscoveryIsSilent() {
            ConnectFlow.Decision decision = ConnectFlow.decide(pinnedToRoom12(), List.of());

            assertThat(decision.step())
                    .as("a filtered broadcast is not evidence the server is gone")
                    .isEqualTo(ConnectFlow.Step.CONNECT);
            assertThat(decision.target()).contains(ROOM_12);
            assertThat(decision.fingerprintToPin()).contains(ID_12);
        }

        @Test
        @DisplayName("others answering but not the pinned one shows the picker")
        void pinnedMissing() {
            ConnectFlow.Decision decision =
                    ConnectFlow.decide(pinnedToRoom12(), List.of(LAB_B_SERVER));

            assertThat(decision.step()).isEqualTo(ConnectFlow.Step.CHOOSE_SERVER);
            assertThat(decision.choices()).containsExactly(LAB_B_SERVER);
            assertThat(decision.message()).isEqualTo(ConnectFlow.PINNED_MISSING);
            assertThat(decision.target()).isEmpty();
        }

        @Test
        @DisplayName("the pinned server among several is connected to without asking")
        void pinnedAmongSeveral() {
            ConnectFlow.Decision decision =
                    ConnectFlow.decide(pinnedToRoom12(), List.of(LAB_B_SERVER, ROOM_12_SERVER));

            assertThat(decision.step())
                    .as("exactly one known pinned server found, so no question is needed")
                    .isEqualTo(ConnectFlow.Step.CONNECT);
            assertThat(decision.target()).contains(ROOM_12);
        }

        @Test
        @DisplayName("a pin matches its address case-insensitively but not a different port")
        void endpointMatching() {
            ServerPin pin = new ServerPin(new ServerEndpoint("MyServer.local", 5555), ID_12);

            assertThat(pin.isSameEndpoint(new ServerEndpoint("myserver.local", 5555))).isTrue();
            assertThat(pin.isSameEndpoint(new ServerEndpoint("myserver.local", 5556))).isFalse();
            assertThat(pin.isSameEndpoint(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("with nothing pinned")
    class FirstRun {

        @Test
        @DisplayName("exactly one server found is trust on first use")
        void trustOnFirstUse() {
            ConnectFlow.Decision decision =
                    ConnectFlow.decide(Optional.empty(), List.of(ROOM_12_SERVER));

            assertThat(decision.step()).isEqualTo(ConnectFlow.Step.CONNECT);
            assertThat(decision.fingerprintToPin())
                    .as("the id is pinned once the connect succeeds")
                    .contains(ID_12);
        }

        @Test
        @DisplayName("several strangers means asking, which is the only first-run question")
        void severalStrangers() {
            ConnectFlow.Decision decision =
                    ConnectFlow.decide(Optional.empty(), List.of(ROOM_12_SERVER, LAB_B_SERVER));

            assertThat(decision.step()).isEqualTo(ConnectFlow.Step.CHOOSE_SERVER);
            assertThat(decision.message()).isEqualTo(ConnectFlow.SEVERAL_FOUND);
            assertThat(decision.choices()).hasSize(2);
        }

        @Test
        @DisplayName("nothing found falls back to the host and port editor")
        void nothingFound() {
            ConnectFlow.Decision decision = ConnectFlow.decide(Optional.empty(), List.of());

            assertThat(decision.step()).isEqualTo(ConnectFlow.Step.MANUAL_ENTRY);
            assertThat(decision.message())
                    .isEqualTo(ConnectFlow.NOTHING_FOUND)
                    .contains("shown on the server console");
        }

        @Test
        @DisplayName("a null list is treated as nothing found")
        void nullList() {
            assertThat(ConnectFlow.decide(Optional.empty(), null).step())
                    .isEqualTo(ConnectFlow.Step.MANUAL_ENTRY);
        }

        @Test
        @DisplayName("a pin is required, even an empty one")
        void pinRequired() {
            assertThatNullPointerException().isThrownBy(() -> ConnectFlow.decide(null, List.of()));
        }
    }

    @Nested
    @DisplayName("picking, confirming and failing")
    class Interactions {

        @Test
        @DisplayName("picking a stranger connects to it")
        void pickAStranger() {
            ConnectFlow.Decision decision =
                    ConnectFlow.select(Optional.empty(), LAB_B_SERVER);

            assertThat(decision.step()).isEqualTo(ConnectFlow.Step.CONNECT);
            assertThat(decision.target()).contains(LAB_B);
        }

        @Test
        @DisplayName("picking the pinned address with a changed id still warns")
        void pickingDoesNotBypassTheWarning() {
            ConnectFlow.Decision decision =
                    ConnectFlow.select(pinnedToRoom12(), ROOM_12_REPLACED);

            assertThat(decision.step())
                    .as("choosing it by hand does not make the change less worth asking about")
                    .isEqualTo(ConnectFlow.Step.CONFIRM_CHANGED_SERVER);
        }

        @Test
        @DisplayName("picking the pinned server with its own id connects")
        void pickingThePinnedServer() {
            assertThat(ConnectFlow.select(pinnedToRoom12(), ROOM_12_SERVER).step())
                    .isEqualTo(ConnectFlow.Step.CONNECT);
        }

        @Test
        @DisplayName("confirming a changed server connects and carries the new id to re-pin")
        void confirming() {
            ConnectFlow.Decision decision = ConnectFlow.confirmChangedServer(ROOM_12_REPLACED);

            assertThat(decision.step()).isEqualTo(ConnectFlow.Step.CONNECT);
            assertThat(decision.fingerprintToPin())
                    .as("the user has said this machine is the right one now")
                    .contains(ID_OTHER);
        }

        @Test
        @DisplayName("a failed connect falls back to the editor, naming the address")
        void failedConnect() {
            ConnectFlow.Decision decision = ConnectFlow.afterFailedConnect(
                    ROOM_12, new java.net.ConnectException("Connection refused"));

            assertThat(decision.step()).isEqualTo(ConnectFlow.Step.MANUAL_ENTRY);
            assertThat(decision.message())
                    .contains("Could not reach 192.168.1.42:5555")
                    .contains(ConnectFlow.UNREACHABLE_REFUSED)
                    .contains("shown on its console");
        }

        @Test
        @DisplayName("a failed connect with no recognised cause still says what to do")
        void failedConnectWithoutDetail() {
            assertThat(ConnectFlow.afterFailedConnect(null, null).message())
                    .contains("the remembered server")
                    .contains("shown on its console")
                    .doesNotContain("()");
            assertThat(ConnectFlow.afterFailedConnect(ROOM_12, new IllegalStateException()).message())
                    .doesNotContain("()");
        }

        // ===== B-37: no Java class name reaches the first screen anyone sees =====

        @Test
        @DisplayName("a throwable with no message never renders its class name ⚑ (B-37)")
        void aMessagelessThrowableIsNotRenderedAsItsClassName() {
            // The ordinary one on a school network: a socket that never answers. Before B-37
            // this produced "Could not reach 192.168.1.42:5555 (SocketTimeoutException)."
            String message = ConnectFlow.afterFailedConnect(
                    ROOM_12, new java.net.SocketTimeoutException()).message();

            assertThat(message)
                    .contains("Could not reach 192.168.1.42:5555")
                    .contains(ConnectFlow.UNREACHABLE_TIMEOUT)
                    .doesNotContain("SocketTimeoutException")
                    .doesNotContain("Exception")
                    .doesNotContain("(")
                    .doesNotContain("java.");
        }

        @Test
        @DisplayName("no cause the product does not know produces a bracket or a leak")
        void unknownCausesLeakNothing() {
            List<Throwable> causes = new java.util.ArrayList<>();
            causes.add(null);
            causes.add(new IllegalStateException("some internal detail"));
            causes.add(new java.io.IOException());
            causes.add(new RuntimeException(new IllegalArgumentException("nested detail")));

            for (Throwable cause : causes) {
                assertThat(ConnectFlow.afterFailedConnect(ROOM_12, cause).message())
                        .as("cause %s", cause)
                        .doesNotContain("(")
                        .doesNotContain("Exception")
                        .doesNotContain("java.")
                        .doesNotContain("some internal detail")
                        .doesNotContain("nested detail")
                        .contains("Check the server is running");
            }
        }

        @Test
        @DisplayName("the four causes the product has words for, each mapped once")
        void everyKnownCauseHasProductCopy() {
            assertThat(ConnectFlow.reasonFor(new java.net.ConnectException("Connection refused")))
                    .isEqualTo(ConnectFlow.UNREACHABLE_REFUSED);
            assertThat(ConnectFlow.reasonFor(new java.net.SocketTimeoutException()))
                    .isEqualTo(ConnectFlow.UNREACHABLE_TIMEOUT);
            assertThat(ConnectFlow.reasonFor(new java.net.UnknownHostException("room12")))
                    .isEqualTo(ConnectFlow.UNREACHABLE_UNKNOWN_HOST);
            assertThat(ConnectFlow.reasonFor(new java.net.NoRouteToHostException()))
                    .isEqualTo(ConnectFlow.UNREACHABLE_NO_ROUTE);
            assertThat(ConnectFlow.reasonFor(null)).isEmpty();
            assertThat(ConnectFlow.reasonFor(new IllegalStateException())).isEmpty();
        }

        @Test
        @DisplayName("the cause is found through the wrappers it arrives in")
        void walksTheCauseChain() {
            // The connect worker hands ConnectView whatever the adapter threw, and the
            // interesting exception is two or three wrappers down.
            Throwable wrapped = new java.util.concurrent.CompletionException(
                    new java.io.IOException("connect failed",
                            new java.net.ConnectException("Connection refused")));

            assertThat(ConnectFlow.reasonFor(wrapped)).isEqualTo(ConnectFlow.UNREACHABLE_REFUSED);
        }

        @Test
        @DisplayName("a self-referential cause chain terminates rather than hanging")
        void selfReferentialCauseTerminates() {
            Throwable loop = new IllegalStateException("round and round") {
                @Override
                public synchronized Throwable getCause() {
                    return this;
                }
            };

            assertThat(ConnectFlow.reasonFor(loop)).isEmpty();
        }

        @Test
        @DisplayName("every unreachable sentence is product copy: capitalised, ended, no code")
        void unreachableCopyFollowsTheHouseRules() {
            assertThat(List.of(ConnectFlow.UNREACHABLE_REFUSED, ConnectFlow.UNREACHABLE_TIMEOUT,
                            ConnectFlow.UNREACHABLE_UNKNOWN_HOST, ConnectFlow.UNREACHABLE_NO_ROUTE))
                    .allSatisfy(sentence -> {
                        assertThat(sentence).isNotBlank();
                        assertThat(Character.isUpperCase(sentence.charAt(0))).isTrue();
                        assertThat(sentence).endsWith(".");
                        assertThat(sentence).doesNotContain("—")
                                .doesNotContain("Exception").doesNotContain("java.")
                                .doesNotContain("[").doesNotContain("]");
                    });
        }

        @Test
        @DisplayName("change server opens the editor without scolding anybody")
        void changeServer() {
            ConnectFlow.Decision decision = ConnectFlow.changeServerRequested();

            assertThat(decision.step()).isEqualTo(ConnectFlow.Step.MANUAL_ENTRY);
            assertThat(decision.message())
                    .as("a deliberate action, not a failure")
                    .isEqualTo("Enter the address shown on the server console.");
        }

        @Test
        @DisplayName("arguments are required")
        void required() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ConnectFlow.select(null, ROOM_12_SERVER));
            assertThatNullPointerException()
                    .isThrownBy(() -> ConnectFlow.select(Optional.empty(), null));
            assertThatNullPointerException()
                    .isThrownBy(() -> ConnectFlow.confirmChangedServer(null));
        }
    }

    @Nested
    @DisplayName("the Login status line")
    class StatusLine {

        @Test
        @DisplayName("names the server when it announced one")
        void named() {
            assertThat(ConnectFlow.statusLine("Room 12 server", ROOM_12))
                    .isEqualTo("Connected to Room 12 server");
        }

        @Test
        @DisplayName("falls back to the address, then to a bare sentence")
        void fallbacks() {
            assertThat(ConnectFlow.statusLine(null, ROOM_12))
                    .isEqualTo("Connected to 192.168.1.42:5555");
            assertThat(ConnectFlow.statusLine("  ", ROOM_12))
                    .isEqualTo("Connected to 192.168.1.42:5555");
            assertThat(ConnectFlow.statusLine(null, null)).isEqualTo("Connected");
        }

        @Test
        @DisplayName("the change-server affordance is worded once, in one place")
        void changeServerLabel() {
            assertThat(ConnectFlow.changeServerLabel()).isEqualTo("change server");
        }

        @Test
        @DisplayName("no user-visible sentence in this class uses an em dash")
        void houseCopyRule() {
            assertThat(List.of(ConnectFlow.NOTHING_FOUND, ConnectFlow.PINNED_MISSING,
                            ConnectFlow.SEVERAL_FOUND, ConnectFlow.CHANGED_SERVER_TITLE,
                            ConnectFlow.changeServerRequested().message(),
                            ConnectFlow.afterFailedConnect(ROOM_12, new java.net.ConnectException())
                                    .message(),
                            ConnectFlow.decide(pinnedToRoom12(), List.of(ROOM_12_REPLACED)).message()))
                    .allSatisfy(message -> assertThat(message).doesNotContain("—"));
        }
    }

    @Nested
    @DisplayName("the pin itself")
    class Pin {

        @Test
        @DisplayName("matching is on the full id, never the short display form")
        void matching() {
            ServerPin pin = new ServerPin(ROOM_12, ID_12);

            assertThat(pin.matches(ID_12.toUpperCase(java.util.Locale.ROOT))).isTrue();
            assertThat(pin.matches("  " + ID_12 + "  ")).isTrue();
            assertThat(pin.matches("7f3a2b91-aaaa-2222-3333-444444444444"))
                    .as("two ids sharing a short form are still two ids")
                    .isFalse();
            assertThat(pin.matches(null)).isFalse();
            assertThat(pin.shortFingerprint()).isEqualTo("7F3A-2B91");
        }

        @Test
        @DisplayName("a pin needs both halves")
        void required() {
            assertThatNullPointerException().isThrownBy(() -> new ServerPin(null, ID_12));
            assertThatNullPointerException().isThrownBy(() -> new ServerPin(ROOM_12, null));
            assertThatIllegalArgumentException().isThrownBy(() -> new ServerPin(ROOM_12, "  "));
        }
    }
}
