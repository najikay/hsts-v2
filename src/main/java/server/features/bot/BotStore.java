package server.features.bot;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The seam between the study bot and the database (Logic tier, E16 — ADR-002).
 *
 * <p>One method, handing out a {@link BotData} for the length of one transaction.
 * The shape is deliberately E10's {@code ExamStore}: services phrase every rule as
 * "in one transaction, read the current truth and act on it", and the transaction
 * is the unit a rule is written <em>inside</em> rather than something wrapped
 * around it afterwards.
 *
 * <p>Two things in this feature actually need that. The F12.9 dual write is one
 * unit of work by definition — the JSON transcript and the {@code bot_messages}
 * row must both land or neither — and creating a bot for a course races the second
 * teacher of that course doing the same thing, which the unique key resolves
 * inside the transaction that hit it.
 *
 * <p>It also gives every rule two homes to be tested in: {@code InMemoryBotStore}
 * for the fast unit tests of the guards and the C-4 branches, and
 * {@link JpaBotStore} driven against H2 and real MySQL for the queries.
 */
@FunctionalInterface
public interface BotStore {

    /**
     * Runs one unit of work in one transaction and returns its result.
     *
     * @param work what to do; the {@link BotData} it receives is valid only for
     *             this call
     * @param <T>  the result type
     * @return whatever the work returned
     */
    <T> T inTx(Function<BotData, T> work);

    /**
     * Runs one unit of work in one transaction, discarding the result.
     *
     * @param work what to do
     */
    default void runInTx(Consumer<BotData> work) {
        inTx(data -> {
            work.accept(data);
            return null;
        });
    }
}
