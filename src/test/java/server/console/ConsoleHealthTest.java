package server.console;

import common.dto.auth.Role;
import ocsf.server.ConnectionToClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import server.core.SessionManager;
import server.features.bot.BotProvider;
import server.features.bot.BotProviderException;
import server.features.bot.ChatTurn;
import server.features.bot.ProviderChain;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The four status cards (E19.2, F13.1).
 *
 * <p>Each probe is a seam, so the cards are asserted for states that would
 * otherwise need a stopped MySQL, a full heap and a failed API call to reach.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConsoleHealthTest {

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static ConsoleHealth.MemoryGauge gauge(long used, long max) {
        return new ConsoleHealth.MemoryGauge() {
            @Override
            public long usedBytes() {
                return used;
            }

            @Override
            public long maxBytes() {
                return max;
            }
        };
    }

    @Nested
    @DisplayName("probing")
    class Probing {

        @Test
        @DisplayName("all four facts are sampled at one instant")
        void oneInstant() {
            ConsoleHealth health = new ConsoleHealth(() -> true, () -> 4,
                    gauge(256L * 1024 * 1024, 2048L * 1024 * 1024),
                    () -> List.of(HealthSnapshot.ProviderStatus.up("deepseek")), CLOCK);

            HealthSnapshot snapshot = health.probe();

            assertThat(snapshot.at()).isEqualTo(NOW);
            assertThat(snapshot.databaseUp()).isTrue();
            assertThat(snapshot.connectedClients()).isEqualTo(4);
            assertThat(snapshot.memoryText()).isEqualTo("256 MB");
            assertThat(snapshot.providers()).hasSize(1);
        }

        @Test
        @DisplayName("a probe that throws is reported down, never propagated")
        void probeThrows() {
            ConsoleHealth health = new ConsoleHealth(() -> {
                throw new IllegalStateException("pool exhausted");
            }, () -> 0, ConsoleHealth.MemoryGauge.RUNTIME, ConsoleHealth.ProviderHealth.NONE, CLOCK);

            HealthSnapshot snapshot = health.probe();

            assertThat(snapshot.databaseUp())
                    .as("a console that fell over when the database did would be useless")
                    .isFalse();
            assertThat(snapshot.databaseDetail()).contains("Check MySQL is running");
        }

        @Test
        @DisplayName("a healthy database says what was actually checked")
        void healthyDetail() {
            HealthSnapshot snapshot = new ConsoleHealth(() -> true, () -> 0,
                    ConsoleHealth.MemoryGauge.RUNTIME, ConsoleHealth.ProviderHealth.NONE, CLOCK)
                    .probe();

            assertThat(snapshot.databaseDetail()).isEqualTo("Connection pool answering SELECT 1");
        }

        @Test
        @DisplayName("collaborators are required")
        void required() {
            assertThatNullPointerException().isThrownBy(() -> new ConsoleHealth(null, () -> 0,
                    ConsoleHealth.MemoryGauge.RUNTIME, ConsoleHealth.ProviderHealth.NONE, CLOCK));
            assertThatNullPointerException().isThrownBy(() -> new ConsoleHealth(() -> true, null,
                    ConsoleHealth.MemoryGauge.RUNTIME, ConsoleHealth.ProviderHealth.NONE, CLOCK));
            assertThatNullPointerException().isThrownBy(() -> new ConsoleHealth(() -> true, () -> 0,
                    null, ConsoleHealth.ProviderHealth.NONE, CLOCK));
            assertThatNullPointerException().isThrownBy(() -> new ConsoleHealth(() -> true, () -> 0,
                    ConsoleHealth.MemoryGauge.RUNTIME, null, CLOCK));
            assertThatNullPointerException().isThrownBy(() -> new ConsoleHealth(() -> true, () -> 0,
                    ConsoleHealth.MemoryGauge.RUNTIME, ConsoleHealth.ProviderHealth.NONE, null));
        }

        @Test
        @DisplayName("the real memory gauge reads the real heap")
        void runtimeGauge() {
            assertThat(ConsoleHealth.MemoryGauge.RUNTIME.usedBytes()).isPositive();
            assertThat(ConsoleHealth.MemoryGauge.RUNTIME.maxBytes()).isPositive();
        }

        @Test
        @DisplayName("the no-database and no-bot defaults answer rather than throw")
        void unavailableDefaults() {
            assertThat(ConsoleHealth.DatabaseProbe.UNAVAILABLE.isUp()).isFalse();
            assertThat(ConsoleHealth.ProviderHealth.NONE.statuses()).isEmpty();
        }
    }

    @Nested
    @DisplayName("production wiring")
    class Wiring {

        @Mock
        private ConnectionToClient socket;

        @Test
        @DisplayName("a server with no pool reports the database down rather than failing")
        void noSessionFactory() {
            SessionManager sessions = new SessionManager(CLOCK);
            sessions.attach(1001L, Role.TEACHER, socket);

            HealthSnapshot snapshot = ConsoleHealth.of(null, sessions, null, CLOCK).probe();

            assertThat(snapshot.databaseUp()).isFalse();
            assertThat(snapshot.connectedClients()).isEqualTo(1);
            assertThat(snapshot.providers()).isEmpty();
        }

        @Test
        @DisplayName("the session map is required")
        void sessionsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ConsoleHealth.of(null, null, null, CLOCK));
        }

        @Test
        @DisplayName("only configured providers appear, and a benched one is marked")
        void providerHealthReadsTheChain() {
            ProviderChain chain = new ProviderChain(
                    List.of(new StubBotProvider("deepseek", true, true),
                            new StubBotProvider("anthropic", true, false),
                            new StubBotProvider("unconfigured", false, false)),
                    CLOCK);
            // Provoke a bench on the first provider by asking once.
            chain.ask("system", List.of(), List.of(), "question");

            List<HealthSnapshot.ProviderStatus> statuses =
                    ConsoleHealth.chainHealth(chain, CLOCK).statuses();

            assertThat(statuses).extracting(HealthSnapshot.ProviderStatus::name)
                    .as("a provider with no key is not news for this card")
                    .containsExactly("deepseek", "anthropic");
            assertThat(statuses.get(0).available()).isFalse();
            assertThat(statuses.get(0).benchedUntil()).isNotNull();
            assertThat(statuses.get(1).available()).isTrue();
        }

        @Test
        @DisplayName("the chain accessor is required")
        void chainRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ConsoleHealth.chainHealth(null, CLOCK));
            assertThatNullPointerException().isThrownBy(() ->
                    ConsoleHealth.chainHealth(new ProviderChain(List.of(), CLOCK), null));
        }

        @Test
        @DisplayName("the select-one probe needs a factory")
        void selectOneRequiresFactory() {
            assertThatNullPointerException().isThrownBy(() -> ConsoleHealth.selectOne(null));
        }
    }

    @Nested
    @DisplayName("card text")
    class Text {

        private HealthSnapshot snapshot(boolean up, int clients, long used, long max,
                                        List<HealthSnapshot.ProviderStatus> providers) {
            return new HealthSnapshot(NOW, up, "detail", clients, used, max, providers);
        }

        @Test
        @DisplayName("the database card is one word plus a next step")
        void database() {
            assertThat(snapshot(true, 0, 1, 1, List.of()).databaseText()).isEqualTo("Up");
            assertThat(snapshot(false, 0, 1, 1, List.of()).databaseText()).isEqualTo("Down");
            assertThat(snapshot(false, 0, 1, 1, List.of()).hasProblem()).isTrue();
            assertThat(snapshot(true, 0, 1, 1, List.of()).hasProblem()).isFalse();
        }

        @Test
        @DisplayName("the clients card is phrased for zero, one and many")
        void clients() {
            assertThat(snapshot(true, 0, 1, 1, List.of()).clientsDetail())
                    .isEqualTo("Nobody is signed in yet");
            assertThat(snapshot(true, 1, 1, 1, List.of()).clientsDetail())
                    .isEqualTo("1 signed-in session");
            assertThat(snapshot(true, 7, 1, 1, List.of()).clientsDetail())
                    .isEqualTo("7 signed-in sessions");
            assertThat(snapshot(true, 7, 1, 1, List.of()).clientsText()).isEqualTo("7");
        }

        @Test
        @DisplayName("the memory card is megabytes and a percentage")
        void memory() {
            HealthSnapshot snapshot = snapshot(true, 0,
                    512L * 1024 * 1024, 2048L * 1024 * 1024, List.of());

            assertThat(snapshot.memoryText()).isEqualTo("512 MB");
            assertThat(snapshot.memoryDetail()).isEqualTo("of 2048 MB (25%)");
        }

        @Test
        @DisplayName("an unknown heap ceiling says so rather than dividing by zero")
        void unknownCeiling() {
            assertThat(snapshot(true, 0, 100, 0, List.of()).memoryDetail())
                    .isEqualTo("heap ceiling unknown");
        }

        @Test
        @DisplayName("no provider configured says what that means for a student")
        void noProviders() {
            HealthSnapshot snapshot = snapshot(true, 0, 1, 1, List.of());

            assertThat(snapshot.providersText()).isEqualTo("None configured");
            assertThat(snapshot.providersDetail())
                    .contains("fallback message")
                    .contains("server.properties");
        }

        @Test
        @DisplayName("providers are counted and named")
        void someProviders() {
            HealthSnapshot snapshot = snapshot(true, 0, 1, 1, List.of(
                    HealthSnapshot.ProviderStatus.benched("deepseek", NOW.plusSeconds(60)),
                    HealthSnapshot.ProviderStatus.up("anthropic")));

            assertThat(snapshot.providersText()).isEqualTo("1 of 2 answering");
            assertThat(snapshot.providersDetail()).isEqualTo("deepseek: benched · anthropic: up");
        }

        @Test
        @DisplayName("a benched provider is called benched, not down")
        void benchedWording() {
            assertThat(HealthSnapshot.ProviderStatus.benched("deepseek", NOW).detail())
                    .as("the chain stopped trying it; that is not a claim it is unreachable")
                    .startsWith("Benched");
            assertThat(HealthSnapshot.ProviderStatus.up("deepseek").detail()).isEqualTo("Answering");
        }

        @Test
        @DisplayName("the snapshot defends its own fields")
        void defensive() {
            HealthSnapshot snapshot = new HealthSnapshot(NOW, true, null, 0, 0, 0, null);

            assertThat(snapshot.databaseDetail()).isEmpty();
            assertThat(snapshot.providers()).isEmpty();
            assertThatNullPointerException().isThrownBy(() ->
                    new HealthSnapshot(null, true, "", 0, 0, 0, List.of()));
            assertThatNullPointerException().isThrownBy(() ->
                    new HealthSnapshot.ProviderStatus(null, true, null));
        }
    }

    /** A provider whose configured-ness and success are both fixed. */
    private record StubBotProvider(String providerName, boolean configured, boolean fails)
            implements BotProvider {

        @Override
        public String name() {
            return providerName;
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public String ask(String systemPrompt, List<String> contextBlocks,
                          List<ChatTurn> history, String question) throws BotProviderException {
            if (fails) {
                throw new BotProviderException(providerName,
                        BotProviderException.Kind.TIMEOUT, "slow");
            }
            return "answer";
        }
    }
}
