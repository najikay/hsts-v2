package server.console;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The four status cards of the server console, as one immutable value (Logic
 * tier, E19.2 / F13.1).
 *
 * <p>One record rather than four probes the view calls separately, so the cards
 * on screen always describe the same instant. Four independent reads would let an
 * operator see "database down" beside "12 clients connected" sampled a second
 * apart and try to reason about it.
 *
 * <p>Every card also carries its own display text. That is the part with rules in
 * it (what counts as healthy, how bytes become megabytes, what a benched provider
 * is called), so it lives here where it is unit-tested rather than in the view
 * where it would be eyeballed.
 *
 * @param at              when the probe ran
 * @param databaseUp      whether the cheap {@code SELECT 1} came back
 * @param databaseDetail  what to say underneath, healthy or not
 * @param connectedClients how many signed-in sessions the server holds
 * @param usedMemoryBytes  JVM heap in use
 * @param maxMemoryBytes   JVM heap ceiling
 * @param providers        one entry per configured bot provider
 */
public record HealthSnapshot(Instant at,
                             boolean databaseUp,
                             String databaseDetail,
                             int connectedClients,
                             long usedMemoryBytes,
                             long maxMemoryBytes,
                             List<ProviderStatus> providers) {

    private static final long MEGABYTE = 1024L * 1024L;

    /** One bot provider's health, as the console's card renders it (E16.4). */
    public record ProviderStatus(String name, boolean available, Instant benchedUntil) {

        public ProviderStatus {
            Objects.requireNonNull(name, "name");
        }

        /** @return a provider that answered the last time it was tried. */
        public static ProviderStatus up(String name) {
            return new ProviderStatus(name, true, null);
        }

        /** @return a provider skipped until its bench window ends. */
        public static ProviderStatus benched(String name, Instant until) {
            return new ProviderStatus(name, false, until);
        }

        /**
         * @return the line under the provider's name. "Benched" rather than "down"
         *         on purpose: the chain has stopped trying it for a minute, which
         *         is not the same claim as the provider being unreachable, and the
         *         console should not assert more than it knows.
         */
        public String detail() {
            return available ? "Answering" : "Benched after a failure, retried within a minute";
        }
    }

    public HealthSnapshot {
        Objects.requireNonNull(at, "at");
        databaseDetail = databaseDetail == null ? "" : databaseDetail;
        providers = List.copyOf(providers == null ? List.of() : providers);
    }

    /** @return {@code "Up"} or {@code "Down"} for the database card's headline. */
    public String databaseText() {
        return databaseUp ? "Up" : "Down";
    }

    /** @return {@code "3"}, the clients card's headline. */
    public String clientsText() {
        return Integer.toString(connectedClients);
    }

    /** @return the clients card's subtitle, phrased for zero as well as many. */
    public String clientsDetail() {
        return switch (connectedClients) {
            case 0 -> "Nobody is signed in yet";
            case 1 -> "1 signed-in session";
            default -> connectedClients + " signed-in sessions";
        };
    }

    /** @return {@code "412 MB"}, the memory card's headline. */
    public String memoryText() {
        return (usedMemoryBytes / MEGABYTE) + " MB";
    }

    /** @return {@code "of 4096 MB (10%)"}, the memory card's subtitle. */
    public String memoryDetail() {
        if (maxMemoryBytes <= 0) {
            return "heap ceiling unknown";
        }
        long percent = Math.round(100.0 * usedMemoryBytes / maxMemoryBytes);
        return "of " + (maxMemoryBytes / MEGABYTE) + " MB (" + percent + "%)";
    }

    /** @return {@code "1 of 2 answering"}, the provider card's headline. */
    public String providersText() {
        if (providers.isEmpty()) {
            return "None configured";
        }
        long up = providers.stream().filter(ProviderStatus::available).count();
        return up + " of " + providers.size() + " answering";
    }

    /**
     * @return the provider card's subtitle. With no provider at all the sentence
     *         says what that means for a student rather than leaving the operator
     *         to work it out from an empty card.
     */
    public String providersDetail() {
        if (providers.isEmpty()) {
            return "The study bot answers with its fallback message. Add a key to server.properties";
        }
        return providers.stream()
                .map(provider -> provider.name() + ": " + (provider.available() ? "up" : "benched"))
                .reduce((left, right) -> left + " · " + right)
                .orElse("");
    }

    /** @return {@code true} when a card should be tinted as a problem. */
    public boolean hasProblem() {
        return !databaseUp;
    }
}
