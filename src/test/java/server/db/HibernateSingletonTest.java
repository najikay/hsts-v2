package server.db;

import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import server.db.entities.Subject;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Covers the shared-factory lifecycle and the single-argument {@link Transactions} forms
 * (E2.10).
 *
 * <p>Those forms are the ones service code will actually call — {@code inTx(session ->
 * …)} with no factory argument — so leaving them untested would mean the convenient path
 * is the unproven one. They resolve the factory through {@link HibernateUtil}'s
 * singleton, which is why {@link HibernateUtil#install} exists.
 */
class HibernateSingletonTest {

    private H2Support.H2Db db;

    @AfterEach
    void clearSingleton() {
        // Leaving an installed factory behind would hand the next test a connection pool
        // pointing at a database that has already been dropped — and install() now refuses
        // to replace one, so the next test would fail outright rather than mysteriously.
        HibernateUtil.shutdown();
        if (db != null) {
            db.close();
            db = null;
        }
    }

    /** Installs a fresh in-memory database as the shared factory, and remembers to close it. */
    private SessionFactory installFreshDatabase() {
        db = H2Support.fresh();
        HibernateUtil.install(db.factory());
        return db.factory();
    }

    @Test
    @DisplayName("the single-argument inTx runs against the shared factory and returns a value")
    void singleArgumentInTxUsesTheSharedFactory() {
        installFreshDatabase();

        Transactions.runInTx(session -> session.persist(new Subject("70", "אזרחות")));

        Subject found = Transactions.inTx(session -> session.find(Subject.class, "70"));
        assertThat(found).isNotNull();
        assertThat(found.getName()).isEqualTo("אזרחות");
    }

    @Test
    @DisplayName("the single-argument runInTx commits its work")
    void singleArgumentRunInTxCommits() {
        installFreshDatabase();
        AtomicReference<String> seen = new AtomicReference<>();

        Transactions.runInTx(session -> {
            session.persist(new Subject("71", "גאוגרפיה"));
            seen.set("ran");
        });

        assertThat(seen).hasValue("ran");
        Subject persisted = Transactions.inTx(session -> session.find(Subject.class, "71"));
        assertThat(persisted).isNotNull();
    }

    @Test
    @EnabledIf("server.db.MySqlAvailability#defaultSchemaExists")
    @DisplayName("the server can build its shared factory from real configuration")
    void bootsFromServerConfig() {
        // The production path: credentials from ServerConfig, URL from DbBootstrap, its
        // own pool. Worth exercising for real, because this is what fails on a machine
        // where MySQL is installed but the login in server.properties is wrong — and it
        // fails at server start, in front of whoever is running the demo.
        SessionFactory first = HibernateUtil.sessionFactory();

        assertThat(first).isNotNull();
        assertThat(first.isClosed()).isFalse();
        assertThat(HibernateUtil.sessionFactory())
                .as("a SessionFactory is expensive and thread-safe — build one, share it")
                .isSameAs(first);

        HibernateUtil.shutdown();
        assertThat(first.isClosed()).isTrue();

        assertThat(HibernateUtil.sessionFactory())
                .as("after shutdown the next caller gets a working factory, not a closed one")
                .isNotSameAs(first);
    }

    @Test
    @DisplayName("installing over an existing factory is refused, not silently accepted")
    void installRefusesToReplace() {
        installFreshDatabase();
        H2Support.H2Db second = H2Support.fresh();

        try {
            // Overwriting would orphan whatever was there: still open, still holding
            // connections, and no longer reachable to be closed. If the incumbent were the
            // production factory, that is ten leaked MySQL connections and a shutdown that
            // closes the wrong thing.
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> HibernateUtil.install(second.factory()))
                    .withMessageContaining("already installed");
        } finally {
            second.close();
        }
    }

    @Test
    @DisplayName("shutdown closes the shared factory and is safe to repeat")
    void shutdownIsIdempotent() {
        SessionFactory installed = installFreshDatabase();

        HibernateUtil.shutdown();
        assertThat(installed.isClosed()).isTrue();

        // Called again on the way out of every test, and on server shutdown paths that
        // may run twice — it must not throw the second time.
        HibernateUtil.shutdown();
    }
}
