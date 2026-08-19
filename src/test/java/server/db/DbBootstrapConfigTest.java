package server.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link DbBootstrap}'s configuration — no database required, so these
 * run on every machine and in every CI job.
 *
 * <p>The Flyway settings asserted here are the ones with consequences: pointing at the
 * wrong location silently migrates nothing, and the validate/baseline pair is what stops
 * two machines from drifting onto different schemas without anyone noticing.
 */
class DbBootstrapConfigTest {

    @Test
    @DisplayName("jdbcUrl assembles host, port, schema and the shared parameters")
    void jdbcUrlIsAssembledFromItsParts() {
        String url = DbBootstrap.jdbcUrl("db.local", 3307, "some_schema");

        assertThat(url).isEqualTo("jdbc:mysql://db.local:3307/some_schema?" + DbBootstrap.JDBC_PARAMS);
    }

    @Test
    @DisplayName("the default URL points at hsts_db on the local MySQL")
    void defaultUrlTargetsTheLocalDatabase() {
        assertThat(DbBootstrap.defaultJdbcUrl())
                .isEqualTo("jdbc:mysql://localhost:3306/hsts_db?" + DbBootstrap.JDBC_PARAMS);
    }

    @Test
    @DisplayName("the connection carries UTF-8 and UTC — Hebrew and server-authoritative time")
    void connectionParametersCoverEncodingAndTimezone() {
        assertThat(DbBootstrap.JDBC_PARAMS)
                .contains("characterEncoding=UTF-8")
                .contains("serverTimezone=UTC");
    }

    @Test
    @DisplayName("jdbcUrl rejects a null host or schema rather than building a broken URL")
    void jdbcUrlValidatesItsArguments() {
        assertThatNullPointerException()
                .isThrownBy(() -> DbBootstrap.jdbcUrl(null, 3306, "s"))
                .withMessage("host");
        assertThatNullPointerException()
                .isThrownBy(() -> DbBootstrap.jdbcUrl("h", 3306, null))
                .withMessage("database");
    }

    @Test
    @DisplayName("Flyway reads the versioned migrations from classpath:db/migration")
    void flywayReadsTheMigrationsFolder() {
        Configuration config = flywayConfiguration();

        assertThat(config.getLocations())
                .extracting(Object::toString)
                .containsExactly(DbBootstrap.MIGRATIONS_LOCATION);
    }

    @Test
    @DisplayName("validation is on, so an edited applied migration fails loudly")
    void validationIsEnabled() {
        assertThat(flywayConfiguration().isValidateOnMigrate()).isTrue();
    }

    @Test
    @DisplayName("baselineOnMigrate is off, so a non-empty unmanaged schema is an error")
    void baseliningIsDisabled() {
        assertThat(flywayConfiguration().isBaselineOnMigrate()).isFalse();
    }

    @Test
    @DisplayName("a null DataSource is rejected before Flyway is built")
    void nullDataSourceIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> DbBootstrap.flywayFor(null))
                .withMessage("dataSource");
    }

    /** Flyway does not touch the DataSource until it runs, so a mock is enough here. */
    private static Configuration flywayConfiguration() {
        Flyway flyway = DbBootstrap.flywayFor(Mockito.mock(DataSource.class));
        return flyway.getConfiguration();
    }
}
