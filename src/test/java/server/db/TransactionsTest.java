package server.db;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.entities.Subject;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Covers the transaction boundary (E2.10).
 *
 * <p>The commit path is the easy half and the least interesting. What matters is that
 * every <em>other</em> exit rolls back — a thrown exception, an {@link Error}, a
 * constraint violation surfacing at flush — because a half-written exam submission is
 * exactly the failure that only ever appears when someone is watching.
 */
class TransactionsTest {

    private static H2Support.H2Db db;
    private static SessionFactory factory;

    @BeforeAll
    static void startDatabase() {
        db = H2Support.fresh();
        factory = db.factory();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName("work that succeeds is committed and visible to the next transaction")
    void successCommits() {
        Transactions.inTx(factory, session -> {
            session.persist(new Subject("90", "פילוסופיה"));
            return null;
        });

        Subject reloaded = Transactions.inTx(factory, session -> session.find(Subject.class, "90"));

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getName()).isEqualTo("פילוסופיה");
    }

    @Test
    @DisplayName("a thrown exception rolls the whole unit of work back")
    void exceptionRollsBack() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> Transactions.inTx(factory, session -> {
                    session.persist(new Subject("91", "כימיה"));
                    session.flush();
                    throw new IllegalStateException("something went wrong halfway");
                }))
                .withMessage("something went wrong halfway");

        Subject afterFailure = Transactions.inTx(factory, session -> session.find(Subject.class, "91"));
        assertThat(afterFailure)
                .as("the row written before the failure must not survive")
                .isNull();
    }

    @Test
    @DisplayName("an Error rolls back too — not just RuntimeException")
    void errorRollsBack() {
        // Easy to get wrong: catching RuntimeException alone leaves an Error to unwind
        // past the commit with the transaction still open.
        assertThatExceptionOfType(StackOverflowError.class)
                .isThrownBy(() -> Transactions.inTx(factory, session -> {
                    session.persist(new Subject("92", "היסטוריה"));
                    session.flush();
                    throw new StackOverflowError();
                }));

        Subject afterError = Transactions.inTx(factory, session -> session.find(Subject.class, "92"));
        assertThat(afterError).isNull();
    }

    @Test
    @DisplayName("the void form runs the work and commits it")
    void voidFormCommits() {
        AtomicBoolean ran = new AtomicBoolean(false);

        Transactions.runInTx(factory, session -> {
            session.persist(new Subject("93", "ביולוגיה"));
            ran.set(true);
        });

        assertThat(ran).isTrue();
        Subject persisted = Transactions.inTx(factory, session -> session.find(Subject.class, "93"));
        assertThat(persisted).isNotNull();
    }

    @Test
    @DisplayName("the session is closed even when the work fails")
    void sessionIsClosedOnFailure() {
        var leaked = new org.hibernate.Session[1];

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> Transactions.inTx(factory, session -> {
                    leaked[0] = session;
                    throw new IllegalStateException("boom");
                }));

        assertThat(leaked[0].isOpen())
                .as("a session left open leaks a pooled connection for the life of the JVM")
                .isFalse();
    }

    @Test
    @DisplayName("null arguments are rejected rather than failing later inside a session")
    void nullArgumentsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> Transactions.inTx((SessionFactory) null, session -> null));
        assertThatNullPointerException()
                .isThrownBy(() -> Transactions.inTx(factory, (Function<Session, Object>) null));
        assertThatNullPointerException()
                .isThrownBy(() -> Transactions.runInTx(factory, (Consumer<Session>) null));
    }
}
