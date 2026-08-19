package server.db;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Runs a unit of work inside one transaction (E2.10).
 *
 * <p>Every write path in the server goes through here:
 *
 * <pre>{@code
 * Grade saved = Transactions.inTx(session -> {
 *     Grade grade = new Grade(attemptId, score);
 *     session.persist(grade);
 *     return grade;
 * });
 * }</pre>
 *
 * <p>The point is not brevity. It is that commit, rollback and close happen on every
 * path including the ones nobody thought about — an exception thrown halfway, an
 * {@link Error}, a constraint violation surfacing at flush time. Hand-written
 * begin/commit blocks get the happy path right and leak or half-commit on the rest, and
 * a half-committed exam submission is the kind of bug that only ever appears during a
 * demo.
 *
 * <h2>Why several small transactions rather than one big one</h2>
 *
 * <p>Each call is its own transaction. Nesting is not supported and is not wanted: the
 * dual-write in F12.9 (JSON transcript and normalised {@code bot_messages} row) and the
 * grade-plus-statistics write both need to be atomic, and both are expressed as a single
 * {@code inTx} that does two things — not two {@code inTx} calls that hope to join.
 * If you find yourself wanting to nest, the unit of work is drawn in the wrong place.
 *
 * <h2>Why the void form has a different name</h2>
 *
 * <p>{@link #runInTx} rather than another {@code inTx} overload, because
 * {@code Consumer} and {@code Function} are ambiguous to the compiler for any lambda
 * whose body is a method call — {@code session -> session.persist(x)} matches both. The
 * overloaded version compiled fine until a caller wrote exactly that, and then produced
 * an "ambiguous reference" error pointing at the call site rather than the design. Two
 * names cost one word and remove the trap entirely.
 */
public final class Transactions {

    private Transactions() {
        // static helper — no instances
    }

    /**
     * Runs work in a transaction on the server's shared factory and returns its result.
     *
     * @param work what to do with the session; must not return the session itself
     * @param <T>  the result type
     * @return whatever the work returned
     * @throws RuntimeException whatever the work threw, after the transaction is rolled
     *                          back
     */
    public static <T> T inTx(Function<Session, T> work) {
        // Checked before sessionFactory(), which would otherwise boot a real MySQL pool
        // just to discover the caller passed null.
        Objects.requireNonNull(work, "work");
        return inTx(HibernateUtil.sessionFactory(), work);
    }

    /**
     * Runs work in a transaction on the server's shared factory.
     *
     * @param work what to do with the session
     */
    public static void runInTx(Consumer<Session> work) {
        Objects.requireNonNull(work, "work");
        inTx(session -> {
            work.accept(session);
            return null;
        });
    }

    /**
     * Runs work against an explicit factory, discarding any result.
     *
     * @param factory the factory to open a session on
     * @param work    what to do with the session
     */
    public static void runInTx(SessionFactory factory, Consumer<Session> work) {
        Objects.requireNonNull(work, "work");
        inTx(factory, session -> {
            work.accept(session);
            return null;
        });
    }

    /**
     * Runs work against an explicit factory and returns its result — the seam the tests
     * use, so a suite can drive H2 or a throwaway MySQL schema without touching the
     * singleton.
     *
     * @param factory the factory to open a session on
     * @param work    what to do with the session
     * @param <T>     the result type
     * @return whatever the work returned
     */
    public static <T> T inTx(SessionFactory factory, Function<Session, T> work) {
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(work, "work");

        try (Session session = factory.openSession()) {
            Transaction tx = session.beginTransaction();
            try {
                T result = work.apply(session);
                tx.commit();
                return result;
            } catch (RuntimeException | Error e) {
                rollbackQuietly(tx, e);
                throw e;
            }
        }
    }

    /**
     * Rolls back without losing the original failure.
     *
     * <p>A rollback can itself fail — a dropped connection is the usual reason — and if
     * that exception were allowed to propagate it would replace the one that actually
     * explains what went wrong. So it is attached as a suppressed exception instead:
     * visible in the stack trace, but never the headline.
     */
    private static void rollbackQuietly(Transaction tx, Throwable cause) {
        if (tx == null || !tx.isActive()) {
            return;
        }
        try {
            tx.rollback();
        } catch (RuntimeException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }
}
