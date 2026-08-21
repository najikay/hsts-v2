package server.features.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Try the providers in order, remember which ones are down (Logic tier, E16.4 —
 * ADR-009, S-32).
 *
 * <p>The chain is the reason the v1 bot's single point of failure is gone. Ask it
 * a question and it walks its providers in order — DeepSeek first because it is
 * cheap, Anthropic second because it is dependable — and answers with the first
 * one that manages it. If none does, it answers {@link Optional#empty()} and the
 * service turns that into the one S-32 sentence a student ever sees.
 *
 * <h2>Health memory, and why sixty seconds</h2>
 *
 * <p>A provider that just failed will almost certainly fail again in the next few
 * seconds, and the student pays for the discovery in latency: every ask would sit
 * through a dead provider's timeout before reaching the live one. So a failure
 * marks that provider unhealthy for {@link #UNHEALTHY_WINDOW}, and asks in that
 * window skip straight past it.
 *
 * <p>Sixty seconds is chosen against the two failures either side of it. Shorter,
 * and a provider having a bad minute is retried inside it, which is the latency
 * problem again. Much longer, and a provider that recovered in ten seconds stays
 * benched through a demo. It is also short enough that no operator ever has to
 * know this mechanism exists: a provider that comes back is used again within a
 * minute, without a restart and without a switch to flip.
 *
 * <p>The window is measured against an injected {@link Clock}, so
 * {@code ProviderChainTest} proves the skip <em>and</em> the recovery by moving
 * time rather than by sleeping. A test suite that sleeps for a minute to check a
 * timeout is a test suite people start skipping.
 *
 * <h2>What gets logged</h2>
 *
 * <p>One structured line per ask, naming the provider that answered and how long
 * it took, plus one per provider that failed with its {@link
 * BotProviderException.Kind}. That is the whole observability story for ADR-009:
 * "DeepSeek went down and Anthropic silently took over" is a thing somebody can
 * <em>see</em> in the terminal during the defence, which is exactly the scenario
 * PRD §6 asks us to be able to demonstrate.
 */
public final class ProviderChain {

    private static final Logger log = LoggerFactory.getLogger(ProviderChain.class);

    /** How long a provider stays benched after a failure. */
    public static final Duration UNHEALTHY_WINDOW = Duration.ofSeconds(60);

    /**
     * A successful answer and where it came from.
     *
     * @param text     the model's answer
     * @param provider the adapter that produced it, stored per row so the fallback
     *                 chain is measurable after the fact
     * @param latency  how long that provider took
     */
    public record Reply(String text, String provider, Duration latency) {

        public Reply {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(provider, "provider");
            latency = latency == null ? Duration.ZERO : latency;
        }
    }

    private final List<BotProvider> providers;
    private final Clock clock;

    /** Provider name to the instant it may be tried again; absent means healthy. */
    private final Map<String, Instant> benched = new ConcurrentHashMap<>();

    /**
     * @param providers the adapters to try, in order; may be empty, in which case
     *                  every ask answers S-32
     * @param clock     the server's clock; the only clock this class has
     */
    public ProviderChain(List<BotProvider> providers, Clock clock) {
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The production chain: DeepSeek, then Anthropic (F12.6).
     *
     * @param config the resolved settings
     * @param clock  the server's clock
     * @return a chain over both adapters; unconfigured ones report themselves so
     *         and are skipped without a round trip
     */
    public static ProviderChain of(BotConfig config, Clock clock) {
        Objects.requireNonNull(config, "config");
        return new ProviderChain(
                List.of(new DeepSeekProvider(config), new AnthropicProvider(config)), clock);
    }

    /**
     * Asks the first provider that can answer.
     *
     * @param systemPrompt  the guardrail prompt
     * @param contextBlocks the fenced course material
     * @param history       earlier turns of this conversation
     * @param question      what the student asked
     * @return the answer, or empty when nothing could answer — the S-32 path
     */
    public Optional<Reply> ask(String systemPrompt, List<String> contextBlocks,
                               List<ChatTurn> history, String question) {
        Instant now = clock.instant();
        int attempted = 0;
        for (BotProvider provider : providers) {
            if (!provider.isConfigured()) {
                continue;
            }
            if (isBenched(provider.name(), now)) {
                log.debug("Skipping {}: benched until {}", provider.name(), benched.get(provider.name()));
                continue;
            }
            attempted++;
            long startedAt = System.nanoTime();
            try {
                String text = provider.ask(systemPrompt, contextBlocks, history, question);
                Duration latency = Duration.ofNanos(System.nanoTime() - startedAt);
                // A provider that answers is trusted again immediately, even if it
                // was benched a moment ago by a different thread: the evidence in
                // front of us beats the memory.
                benched.remove(provider.name());
                log.info("bot.answer provider={} latency_ms={} context_blocks={} history_turns={}",
                        provider.name(), latency.toMillis(),
                        contextBlocks == null ? 0 : contextBlocks.size(),
                        history == null ? 0 : history.size());
                return Optional.of(new Reply(text, provider.name(), latency));
            } catch (BotProviderException failure) {
                Duration latency = Duration.ofNanos(System.nanoTime() - startedAt);
                bench(provider.name(), clock.instant());
                log.warn("bot.provider_failed provider={} kind={} latency_ms={} reason={}",
                        provider.name(), failure.kind(), latency.toMillis(), failure.getMessage());
            } catch (RuntimeException unexpected) {
                // An adapter that throws something unclassified is still a provider
                // that did not answer, and the chain's job is to keep going. Letting
                // it out would turn a provider bug into a failed student request.
                bench(provider.name(), clock.instant());
                log.error("bot.provider_crashed provider={}", provider.name(), unexpected);
            }
        }
        log.warn("bot.no_answer attempted={} configured={} benched={}",
                attempted, configuredCount(), benched.size());
        return Optional.empty();
    }

    /** @return how many providers have what they need to be tried at all. */
    public int configuredCount() {
        return (int) providers.stream().filter(BotProvider::isConfigured).count();
    }

    /** @return {@code true} when no provider could ever answer, key or health aside. */
    public boolean isEmpty() {
        return configuredCount() == 0;
    }

    /**
     * @return the providers currently benched and until when — read by the server
     *         console's health card (F13.1) and by this feature's tests
     */
    public Map<String, Instant> benchedProviders() {
        Instant now = clock.instant();
        Map<String, Instant> live = new LinkedHashMap<>();
        benched.forEach((name, until) -> {
            if (now.isBefore(until)) {
                live.put(name, until);
            }
        });
        return Map.copyOf(live);
    }

    /** @return the names of the providers in chain order, for diagnostics. */
    public List<String> providerNames() {
        return providers.stream().map(BotProvider::name).toList();
    }

    /**
     * The providers that could answer at all, in chain order (E19.2).
     *
     * <p>Read-only, and added for the server console's health card, which must
     * distinguish the two ways a provider is not answering: benched after a
     * failure (temporary, self-healing, worth showing amber) and missing its key
     * (permanent until somebody edits {@code server.properties}, and not this
     * card's news, because {@link BotConfig#logSummary()} already said so at boot).
     * An unconfigured provider in a card reading "0 of 2 answering" would send an
     * operator hunting a network fault that is really a blank line in a file.
     *
     * @return the names of the providers with what they need to be tried
     */
    public List<String> configuredProviderNames() {
        return providers.stream().filter(BotProvider::isConfigured).map(BotProvider::name).toList();
    }

    private boolean isBenched(String provider, Instant now) {
        Instant until = benched.get(provider);
        if (until == null) {
            return false;
        }
        if (now.isBefore(until)) {
            return true;
        }
        // The window has passed. Clear it here rather than in a sweep: the only
        // thing that cares is the next ask, and it is the one asking.
        benched.remove(provider, until);
        return false;
    }

    private void bench(String provider, Instant now) {
        benched.put(provider, now.plus(UNHEALTHY_WINDOW));
    }
}
