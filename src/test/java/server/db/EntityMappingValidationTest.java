package server.db;

import com.zaxxer.hikari.HikariDataSource;
import org.hibernate.SessionFactory;
import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Proves the JPA mappings describe the <em>real</em> schema (E2.9), by migrating a
 * throwaway database with Flyway and then asking Hibernate to validate against it.
 *
 * <h2>Why this test carries the weight</h2>
 *
 * <p>The agreed division of labour is that H2 validates mappings and only the MySQL
 * suite validates the real schema. There is a trap hidden in the first half: when H2
 * builds its schema <em>from these entities</em> via Hibernate schema-generation, the
 * entities are being compared against themselves. That test cannot fail. It would stay
 * green through a renamed column, a wrong type, a forgotten {@code lock_version} — every
 * kind of drift it appears to be guarding.
 *
 * <p>This one can fail, because the two sides come from different places: the schema
 * from {@code V1__core.sql}…{@code V7__notifications.sql}, the expectations from
 * {@code server.db.entities}. That is the whole reason {@link HibernateUtil#build} takes
 * a settings override.
 *
 * <h2>What it actually checks — less than the name suggests</h2>
 *
 * <p>Hibernate's {@code validate} confirms that every mapped table exists, that every
 * mapped column exists, and that the types belong to the same coarse family. It does
 * <b>not</b> compare length, precision, nullability, unique constraints, foreign keys or
 * enum members: an independent review mapped {@code users.username} as
 * {@code length=4000, nullable=true} against a {@code varchar(50) NOT NULL} column and
 * validation passed. The drift it did catch while this PR was written —
 * {@code tinyblob} against {@code MEDIUMBLOB} — crossed a type-code boundary.
 *
 * <p>So this test is the coarse net, and {@link SchemaColumnComparisonTest} is the fine
 * one: that class reads {@code information_schema} and compares nullability, length,
 * explicit column types and enum members itself. Neither is redundant — this one catches
 * a whole missing table, which the other would report as twenty separate findings.
 *
 * <p>{@link #validationHasTeeth()} exists so even the coarse claim is not taken on trust:
 * it points the same validation at an empty database and requires it to fail.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class EntityMappingValidationTest {

    private HikariDataSource dataSource;

    @BeforeEach
    void recreateEmptySchema() throws SQLException {
        try (Connection connection = MySqlAvailability.openServerConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + MySqlAvailability.TEST_SCHEMA + "`");
            statement.execute("CREATE DATABASE `" + MySqlAvailability.TEST_SCHEMA + "`"
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        dataSource = DbBootstrap.dataSource(
                MySqlAvailability.schemaUrl(), MySqlAvailability.user(), MySqlAvailability.password());
    }

    @AfterEach
    void closePool() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    @DisplayName("every entity matches the column the migrations actually created")
    void mappingsMatchTheMigratedSchema() {
        DbBootstrap.migrate(dataSource);

        // Throws SchemaManagementException listing the offending table and column if any
        // mapping disagrees with the migrated schema.
        try (SessionFactory factory = HibernateUtil.build(dataSource, validating())) {
            assertThat(factory.getMetamodel().getEntities())
                    .as("one JPA entity per table in ARCHITECTURE §5")
                    .hasSize(HibernateUtil.ENTITY_CLASSES.size());
        }
    }

    @Test
    @DisplayName("that validation can actually fail — an empty database is rejected")
    void validationHasTeeth() {
        // Deliberately NOT migrated. If validate passed here it would be inert, and the
        // test above would be proving nothing at all.
        assertThatExceptionOfType(SchemaManagementException.class)
                .isThrownBy(() -> HibernateUtil.build(dataSource, validating()).close());
    }

    private static Map<String, Object> validating() {
        return Map.of("hibernate.hbm2ddl.auto", "validate");
    }
}
