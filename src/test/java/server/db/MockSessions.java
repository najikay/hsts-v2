package server.db;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * A {@link SessionFactory} that hands out one session, for testing handlers.
 *
 * <p>{@link Transactions#inTx} is the transaction boundary of every handler in the product, so
 * a handler test has to go through it or it is not testing the handler. It calls exactly three
 * things — {@code openSession}, {@code beginTransaction} and then {@code commit} or
 * {@code rollback} — and this stubs those three so a test can supply the session its
 * repositories are mocked against and get the handler's real control flow for free.
 *
 * <p>The alternative was to give every handler a store interface with an {@code inTx} of its
 * own, which is the shape E8 and E14 use. E12's services take a {@link Session} and hold no
 * transaction, so there was nothing to put behind such an interface except the factory itself;
 * ten lines of test double is cheaper than a production indirection that exists only to be
 * mocked.
 *
 * <p>{@link #commitsOn} additionally records whether the transaction committed or rolled back,
 * which is what lets a test assert that a handler answering an error still ended its
 * transaction cleanly rather than leaving one open.
 */
public final class MockSessions {

    private MockSessions() {
        // test helper — no instances
    }

    /** A transaction that remembers what happened to it. */
    public static final class RecordingTransaction {

        private boolean committed;
        private boolean rolledBack;

        public boolean committed() {
            return committed;
        }

        public boolean rolledBack() {
            return rolledBack;
        }
    }

    /**
     * A factory whose only session is {@code session}.
     *
     * @param session the session every {@code inTx} should run against
     * @return the factory
     */
    public static SessionFactory factoryFor(Session session) {
        return commitsOn(session).factory();
    }

    /**
     * The same, with the transaction's outcome observable.
     *
     * @param session the session every {@code inTx} should run against
     * @return the factory and the record of what its transaction did
     */
    public static Wiring commitsOn(Session session) {
        SessionFactory factory = mock(SessionFactory.class);
        Transaction tx = mock(Transaction.class);
        RecordingTransaction record = new RecordingTransaction();

        // Lenient throughout: a handler that refuses a request before opening a
        // transaction is a correct handler, not an unused stub.
        lenient().when(factory.openSession()).thenReturn(session);
        lenient().when(session.beginTransaction()).thenReturn(tx);
        // isActive has to answer truthfully or rollbackQuietly skips the rollback it is
        // being asked to record.
        lenient().when(tx.isActive()).thenAnswer(call -> !record.committed && !record.rolledBack);
        lenient().doAnswer(call -> {
            record.committed = true;
            return null;
        }).when(tx).commit();
        lenient().doAnswer(call -> {
            record.rolledBack = true;
            return null;
        }).when(tx).rollback();

        return new Wiring(factory, record);
    }

    /**
     * A stubbed factory and the record of its transaction.
     *
     * @param factory the factory to hand a handler
     * @param tx      what its transaction did
     */
    public record Wiring(SessionFactory factory, RecordingTransaction tx) {
    }
}
