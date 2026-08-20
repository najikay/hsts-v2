package server.features.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fallback chain and its health memory (E16.4 — ADR-009, S-32).
 *
 * <p>"DeepSeek goes down and Anthropic silently takes over" is a PRD §6 scenario
 * that has to be demonstrable, so it is tested as one: a chain of two scripted
 * providers, one of them failing, and an assertion about which one answered.
 */
class ProviderChainTest {

    private static final Instant T0 = Instant.parse("2026-08-20T10:00:00Z");
    private static final String PROMPT = "system";
    private static final List<String> BLOCKS = List.of("material");
    private static final List<ChatTurn> HISTORY = List.of();

    /** A provider whose behaviour a test scripts. */
    private static final class ScriptedProvider implements BotProvider {

        private final String name;
        private final boolean configured;
        private final List<String> calls = new ArrayList<>();
        private String answer;
        private BotProviderException failure;
        private RuntimeException crash;

        ScriptedProvider(String name, boolean configured) {
            this.name = name;
            this.configured = configured;
        }

        static ScriptedProvider answering(String name, String answer) {
            ScriptedProvider provider = new ScriptedProvider(name, true);
            provider.answer = answer;
            return provider;
        }

        static ScriptedProvider failing(String name, BotProviderException.Kind kind) {
            ScriptedProvider provider = new ScriptedProvider(name, true);
            provider.failure = new BotProviderException(name, kind, "scripted failure");
            return provider;
        }

        static ScriptedProvider crashing(String name) {
            ScriptedProvider provider = new ScriptedProvider(name, true);
            provider.crash = new IllegalStateException("adapter bug");
            return provider;
        }

        void nowAnswers(String text) {
            this.answer = text;
            this.failure = null;
            this.crash = null;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public String ask(String systemPrompt, List<String> contextBlocks,
                          List<ChatTurn> history, String question) throws BotProviderException {
            calls.add(question);
            if (crash != null) {
                throw crash;
            }
            if (failure != null) {
                throw failure;
            }
            return answer;
        }

        int callCount() {
            return calls.size();
        }
    }

    private static ProviderChain chainOf(BotTestClock clock, BotProvider... providers) {
        return new ProviderChain(List.of(providers), clock);
    }

    @Test
    @DisplayName("the first configured provider answers, and the second is never asked")
    void firstProviderWins() {
        ScriptedProvider deepseek = ScriptedProvider.answering("deepseek", "from deepseek");
        ScriptedProvider anthropic = ScriptedProvider.answering("anthropic", "from anthropic");

        Optional<ProviderChain.Reply> reply =
                chainOf(new BotTestClock(T0), deepseek, anthropic).ask(PROMPT, BLOCKS, HISTORY, "q");

        assertThat(reply).isPresent();
        assertThat(reply.orElseThrow().text()).isEqualTo("from deepseek");
        assertThat(reply.orElseThrow().provider()).isEqualTo("deepseek");
        assertThat(anthropic.callCount()).isZero();
    }

    @Test
    @DisplayName("when the first fails, the second silently takes over (PRD §6)")
    void fallsBackSilently() {
        ScriptedProvider deepseek = ScriptedProvider.failing("deepseek", BotProviderException.Kind.SERVER);
        ScriptedProvider anthropic = ScriptedProvider.answering("anthropic", "from anthropic");

        Optional<ProviderChain.Reply> reply =
                chainOf(new BotTestClock(T0), deepseek, anthropic).ask(PROMPT, BLOCKS, HISTORY, "q");

        assertThat(reply).isPresent();
        assertThat(reply.orElseThrow().provider()).isEqualTo("anthropic");
        assertThat(deepseek.callCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("when every provider fails the chain answers nothing, which becomes the S-32 sentence")
    void bothDownIsTheS32Path() {
        ProviderChain chain = chainOf(new BotTestClock(T0),
                ScriptedProvider.failing("deepseek", BotProviderException.Kind.SERVER),
                ScriptedProvider.failing("anthropic", BotProviderException.Kind.RATE_LIMITED));

        assertThat(chain.ask(PROMPT, BLOCKS, HISTORY, "q")).isEmpty();
    }

    @Test
    @DisplayName("an adapter that throws something unclassified does not take the request down with it")
    void anAdapterCrashIsContained() {
        ScriptedProvider broken = ScriptedProvider.crashing("deepseek");
        ScriptedProvider healthy = ScriptedProvider.answering("anthropic", "still here");

        Optional<ProviderChain.Reply> reply =
                chainOf(new BotTestClock(T0), broken, healthy).ask(PROMPT, BLOCKS, HISTORY, "q");

        assertThat(reply).isPresent();
        assertThat(reply.orElseThrow().provider()).isEqualTo("anthropic");
    }

    @Test
    @DisplayName("an unconfigured provider is skipped without a round trip")
    void skipsUnconfigured() {
        ScriptedProvider unconfigured = new ScriptedProvider("deepseek", false);
        ScriptedProvider configured = ScriptedProvider.answering("anthropic", "hello");

        ProviderChain chain = chainOf(new BotTestClock(T0), unconfigured, configured);

        assertThat(chain.configuredCount()).isEqualTo(1);
        assertThat(chain.isEmpty()).isFalse();
        assertThat(chain.ask(PROMPT, BLOCKS, HISTORY, "q")).isPresent();
        assertThat(unconfigured.callCount()).isZero();
    }

    @Test
    @DisplayName("a chain with nothing configured answers nothing, and says so")
    void emptyChain() {
        ProviderChain chain = chainOf(new BotTestClock(T0), new ScriptedProvider("deepseek", false));

        assertThat(chain.isEmpty()).isTrue();
        assertThat(chain.ask(PROMPT, BLOCKS, HISTORY, "q")).isEmpty();
    }

    @Test
    @DisplayName("a failed provider is benched, so the next ask does not pay its timeout again")
    void benchesAFailedProvider() {
        ScriptedProvider deepseek = ScriptedProvider.failing("deepseek", BotProviderException.Kind.TIMEOUT);
        ScriptedProvider anthropic = ScriptedProvider.answering("anthropic", "from anthropic");
        ProviderChain chain = chainOf(new BotTestClock(T0), deepseek, anthropic);

        chain.ask(PROMPT, BLOCKS, HISTORY, "first");
        chain.ask(PROMPT, BLOCKS, HISTORY, "second");

        assertThat(deepseek.callCount())
                .as("tried once, then skipped inside the window")
                .isEqualTo(1);
        assertThat(chain.benchedProviders()).containsKey("deepseek");
    }

    @Test
    @DisplayName("the bench lasts exactly the window, and the provider is tried again after it")
    void recoversAfterTheWindow() {
        ScriptedProvider deepseek = ScriptedProvider.failing("deepseek", BotProviderException.Kind.SERVER);
        ScriptedProvider anthropic = ScriptedProvider.answering("anthropic", "from anthropic");
        BotTestClock clock = new BotTestClock(T0);
        ProviderChain chain = chainOf(clock, deepseek, anthropic);

        chain.ask(PROMPT, BLOCKS, HISTORY, "first");
        clock.advance(ProviderChain.UNHEALTHY_WINDOW.minusSeconds(1));
        chain.ask(PROMPT, BLOCKS, HISTORY, "still benched");
        assertThat(deepseek.callCount()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(2));
        deepseek.nowAnswers("deepseek is back");
        Optional<ProviderChain.Reply> reply = chain.ask(PROMPT, BLOCKS, HISTORY, "after the window");

        assertThat(deepseek.callCount()).isEqualTo(2);
        assertThat(reply.orElseThrow().provider()).isEqualTo("deepseek");
        assertThat(chain.benchedProviders()).isEmpty();
    }

    @Test
    @DisplayName("a provider that answers is trusted again immediately")
    void successClearsTheBench() {
        ScriptedProvider deepseek = ScriptedProvider.failing("deepseek", BotProviderException.Kind.SERVER);
        ScriptedProvider anthropic = ScriptedProvider.answering("anthropic", "from anthropic");
        BotTestClock clock = new BotTestClock(T0);
        ProviderChain chain = chainOf(clock, deepseek, anthropic);

        chain.ask(PROMPT, BLOCKS, HISTORY, "first");
        assertThat(chain.benchedProviders()).containsKey("deepseek");

        clock.advance(ProviderChain.UNHEALTHY_WINDOW.plusSeconds(1));
        deepseek.nowAnswers("recovered");
        chain.ask(PROMPT, BLOCKS, HISTORY, "second");

        assertThat(chain.benchedProviders()).doesNotContainKey("deepseek");
    }

    @Test
    @DisplayName("a benched provider that never recovers is reported, so the console can show it")
    void benchedProvidersAreVisible() {
        BotTestClock clock = new BotTestClock(T0);
        ProviderChain chain = chainOf(clock,
                ScriptedProvider.failing("deepseek", BotProviderException.Kind.AUTH),
                ScriptedProvider.failing("anthropic", BotProviderException.Kind.AUTH));

        chain.ask(PROMPT, BLOCKS, HISTORY, "q");

        assertThat(chain.benchedProviders()).containsOnlyKeys("deepseek", "anthropic");
        assertThat(chain.providerNames()).containsExactly("deepseek", "anthropic");
    }

    @Test
    @DisplayName("the reply carries which provider answered and how long it took")
    void replyCarriesProvenance() {
        Optional<ProviderChain.Reply> reply = chainOf(new BotTestClock(T0),
                ScriptedProvider.answering("deepseek", "hello"))
                .ask(PROMPT, BLOCKS, HISTORY, "q");

        ProviderChain.Reply value = reply.orElseThrow();
        assertThat(value.provider()).isEqualTo("deepseek");
        assertThat(value.latency()).isNotNull();
        assertThat(value.latency().isNegative()).isFalse();
    }

    @Test
    @DisplayName("the production chain is DeepSeek then Anthropic, in that order")
    void productionOrder() {
        ProviderChain chain = ProviderChain.of(BotConfig.unconfigured(), new BotTestClock(T0));

        assertThat(chain.providerNames()).containsExactly("deepseek", "anthropic");
        assertThat(chain.isEmpty())
                .as("no keys configured, so nothing to try")
                .isTrue();
    }
}
