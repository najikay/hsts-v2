package server.console;

import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.core.SessionManager;
import server.db.Transactions;
import server.features.bot.ProviderChain;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Assembles the console's status cards (Logic tier, E19.2 / F13.1).
 *
 * <p>Four independent facts, four injected seams, one {@link HealthSnapshot}. The
 * seams exist because none of the four is testable in place: a database probe
 * needs MySQL, a client count needs sockets, memory needs a heap under pressure
 * and provider health needs a failed API call. Injected, all four are ordinary
 * values and every branch of the snapshot is asserted without any of it.
 *
 * <h2>The database probe is deliberately cheap</h2>
 *
 * <p>{@code SELECT 1} and nothing else, at whatever interval the console
 * refreshes. It answers the question the card actually asks, which is "can the
 * pool still hand out a working connection", and it answers it without competing
 * with the server for the pool: a heavier probe on a timer would be a background
 * load that exists only so a window can be green.
 *
 * <p>A failure is caught and reported, never rethrown. The console exists to show
 * that the database is down; a console that itself fell over when the database
 * went down would be useless in exactly the situation it was built for.
 */
public final class ConsoleHealth {

    private static final Logger log = LoggerFactory.getLogger(ConsoleHealth.class);

    /** The cheap liveness check. */
    @FunctionalInterface
    public interface DatabaseProbe {

        /** A probe for a console wired without a database (tests, headless). */
        DatabaseProbe UNAVAILABLE = () -> false;

        /** @return {@code true} when a connection could be taken and used */
        boolean isUp();
    }

    /** The JVM heap, as two numbers. */
    public interface MemoryGauge {

        /** The real heap. */
        MemoryGauge RUNTIME = new MemoryGauge() {
            @Override
            public long usedBytes() {
                Runtime runtime = Runtime.getRuntime();
                return runtime.totalMemory() - runtime.freeMemory();
            }

            @Override
            public long maxBytes() {
                return Runtime.getRuntime().maxMemory();
            }
        };

        long usedBytes();

        long maxBytes();
    }

    /** Where the bot providers' health comes from (E16.4). */
    @FunctionalInterface
    public interface ProviderHealth {

        /** A source for a console with no bot configured. */
        ProviderHealth NONE = List::of;

        /** @return one status per provider, in chain order */
        List<HealthSnapshot.ProviderStatus> statuses();
    }

    private final DatabaseProbe database;
    private final IntSupplier clientCount;
    private final MemoryGauge memory;
    private final ProviderHealth providers;
    private final Clock clock;

    public ConsoleHealth(DatabaseProbe database, IntSupplier clientCount,
                         MemoryGauge memory, ProviderHealth providers, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.clientCount = Objects.requireNonNull(clientCount, "clientCount");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Production wiring for a running server.
     *
     * @param factory  the live session factory, or {@code null} when the server
     *                 has not opened a pool (which the card then reports honestly
     *                 rather than by throwing)
     * @param sessions the session map the client count comes from
     * @param chain    the bot provider chain, or {@code null} when none is wired
     * @return a health probe over the real server
     */
    public static ConsoleHealth of(SessionFactory factory, SessionManager sessions,
                                   ProviderChain chain, Clock clock) {
        Objects.requireNonNull(sessions, "sessions");
        return new ConsoleHealth(
                factory == null ? DatabaseProbe.UNAVAILABLE : selectOne(factory),
                sessions::onlineCount,
                MemoryGauge.RUNTIME,
                chain == null ? ProviderHealth.NONE : chainHealth(chain, clock),
                clock);
    }

    /** @return the assembled cards, all sampled at one instant. */
    public HealthSnapshot probe() {
        Instant at = clock.instant();
        boolean up = safeProbe();
        return new HealthSnapshot(at, up, detailFor(up), clientCount.getAsInt(),
                memory.usedBytes(), memory.maxBytes(), providers.statuses());
    }

    private boolean safeProbe() {
        try {
            return database.isUp();
        } catch (RuntimeException e) {
            log.debug("Database probe failed: {}", e.toString());
            return false;
        }
    }

    /** The card's subtitle. Down says what to do about it, per the house rule. */
    private static String detailFor(boolean up) {
        return up
                ? "Connection pool answering SELECT 1"
                : "The pool could not answer. Check MySQL is running and the "
                        + "credentials in server.properties are right";
    }

    /** @return a probe that takes one pooled connection and runs {@code SELECT 1}. */
    static DatabaseProbe selectOne(SessionFactory factory) {
        Objects.requireNonNull(factory, "factory");
        return () -> {
            try {
                return Boolean.TRUE.equals(Transactions.inTx(factory, session ->
                        session.createNativeQuery("select 1", Integer.class).getSingleResult() != null));
            } catch (RuntimeException e) {
                log.debug("SELECT 1 failed: {}", e.toString());
                return false;
            }
        };
    }

    /**
     * Reads {@link ProviderChain}'s bench memory, read-only (E19.2).
     *
     * @return a source reporting every provider in chain order, benched or not
     */
    static ProviderHealth chainHealth(ProviderChain chain, Clock clock) {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(clock, "clock");
        return () -> {
            Map<String, Instant> benched = chain.benchedProviders();
            List<HealthSnapshot.ProviderStatus> statuses = new ArrayList<>();
            for (String name : chain.configuredProviderNames()) {
                Instant until = benched.get(name);
                statuses.add(until == null
                        ? HealthSnapshot.ProviderStatus.up(name)
                        : HealthSnapshot.ProviderStatus.benched(name, until));
            }
            return List.copyOf(statuses);
        };
    }
}
