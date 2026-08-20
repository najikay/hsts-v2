package server.db;

import org.hibernate.SessionFactory;

/**
 * A database a repository test can run against, together with whatever resources sit
 * behind it (E2.13).
 *
 * <p>This exists so {@link RepositoryTestBase} can be written once against "a database"
 * rather than twice, once per engine. The two implementations differ in more than their
 * JDBC URL — see {@link TestDatabases} for what each one can and cannot prove.
 *
 * <p>{@link #close()} deliberately declares no checked exception: a test that has to
 * wrap cleanup in a try/catch is a test people stop writing.
 */
public interface TestDatabase extends AutoCloseable {

    /** @return the session factory bound to this database */
    SessionFactory factory();

    /** Releases the factory and the pool underneath it. */
    @Override
    void close();
}
