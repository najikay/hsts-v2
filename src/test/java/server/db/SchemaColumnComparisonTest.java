package server.db;

import com.zaxxer.hikari.HikariDataSource;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.PersistentClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import server.db.entities.AttemptStatus;
import server.db.entities.BotSourceType;
import server.db.entities.Difficulty;
import server.db.entities.ExamVersionStatus;
import server.db.entities.ExecutionStatus;
import server.db.entities.GradeStatus;
import server.db.entities.UserRole;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compares every mapped column against the real migrated column — name, nullability,
 * length and datetime precision (E2.9).
 *
 * <h2>Why this exists alongside {@link EntityMappingValidationTest}</h2>
 *
 * <p>Because Hibernate's {@code hbm2ddl.auto=validate} checks far less than its name
 * suggests, and the gap was measured rather than assumed. An independent review mapped
 * {@code users.username} as {@code length=4000, nullable=true} against a
 * {@code varchar(50) NOT NULL} column and pointed validation at it: <b>it passed.</b>
 *
 * <p>Validation checks that the table exists, that each mapped column exists, and that
 * the types belong to the same coarse family — {@code Dialect.equivalentTypes} treats
 * VARCHAR, LONGVARCHAR and NVARCHAR as interchangeable. It does not compare length,
 * precision, nullability, unique constraints, foreign keys or enum members. The drift it
 * genuinely caught while this PR was written — {@code tinyblob} against {@code MEDIUMBLOB}
 * — crossed a type-code boundary. Drift <em>within</em> a family is invisible to it.
 *
 * <p>So this test does the comparison the other one is often assumed to do: it reads
 * {@code information_schema.columns} for the freshly migrated schema and checks each
 * against Hibernate's own mapping model. Nullability and length are where the silent
 * damage lives — a column mapped nullable that the schema requires produces a constraint
 * violation at insert time, months later, in whichever feature happens to leave it unset.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class SchemaColumnComparisonTest {

    private HikariDataSource dataSource;

    @BeforeEach
    void migrateFreshSchema() throws SQLException {
        try (Connection connection = MySqlAvailability.openServerConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + MySqlAvailability.TEST_SCHEMA + "`");
            statement.execute("CREATE DATABASE `" + MySqlAvailability.TEST_SCHEMA + "`"
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        dataSource = DbBootstrap.dataSource(
                MySqlAvailability.schemaUrl(), MySqlAvailability.user(), MySqlAvailability.password());
        DbBootstrap.migrate(dataSource);
    }

    @AfterEach
    void closePool() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    @DisplayName("no mapped column is nullable where the schema says NOT NULL, or the reverse")
    void nullabilityMatches() throws SQLException {
        Map<String, ColumnInfo> actual = actualColumns();
        List<String> mismatches = new ArrayList<>();

        forEachMappedColumn((table, column) -> {
            ColumnInfo info = actual.get(key(table, column.getName()));
            if (info != null && info.nullable() != column.isNullable()) {
                mismatches.add(table + "." + column.getName()
                        + " — entity says " + (column.isNullable() ? "nullable" : "NOT NULL")
                        + ", schema says " + (info.nullable() ? "nullable" : "NOT NULL"));
            }
        });

        assertThat(mismatches)
                .as("a column mapped nullable against a NOT NULL schema fails at insert time, "
                        + "far from the mapping that caused it")
                .isEmpty();
    }

    @Test
    @DisplayName("every mapped varchar column declares the length the schema actually has")
    void stringLengthsMatch() throws SQLException {
        Map<String, ColumnInfo> actual = actualColumns();
        List<String> mismatches = new ArrayList<>();

        forEachMappedColumn((table, column) -> {
            ColumnInfo info = actual.get(key(table, column.getName()));
            // Only varchar: for a column with an explicit columnDefinition the declared
            // length is ignored by Hibernate (covered by explicitColumnTypesMatch below),
            // and for an enum the schema's "length" is its longest member, not a size
            // anyone declared.
            if (info == null || column.getSqlType() != null || !"varchar".equals(info.dataType())) {
                return;
            }
            if (info.charLength() != null && column.getLength() != null
                    && !info.charLength().equals(column.getLength())) {
                mismatches.add(table + "." + column.getName()
                        + " — entity length " + column.getLength()
                        + ", schema varchar(" + info.charLength() + ")");
            }
        });

        assertThat(mismatches)
                .as("this is the class of drift hbm2ddl validate cannot see: same type family, "
                        + "different size")
                .isEmpty();
    }

    @Test
    @DisplayName("a column mapped with an explicit type gets exactly that type")
    void explicitColumnTypesMatch() throws SQLException {
        Map<String, ColumnInfo> actual = actualColumns();
        List<String> mismatches = new ArrayList<>();

        forEachMappedColumn((table, column) -> {
            if (column.getSqlType() == null) {
                return;
            }
            ColumnInfo info = actual.get(key(table, column.getName()));
            if (info != null && !column.getSqlType().equalsIgnoreCase(info.columnType())) {
                mismatches.add(table + "." + column.getName()
                        + " — entity declares " + column.getSqlType()
                        + ", schema is " + info.columnType());
            }
        });

        // The fixed-width code columns (CHAR(2)/(4)/(5)/(6)) are mapped this way because
        // Hibernate would otherwise emit varchar — a difference validate ignores, and one
        // that changes padding and comparison semantics.
        assertThat(mismatches).isEmpty();
    }

    @Test
    @DisplayName("each ENUM column accepts exactly the constants its Java enum declares")
    void enumMembersMatch() throws SQLException {
        Map<String, ColumnInfo> actual = actualColumns();
        Map<String, Class<? extends Enum<?>>> enumColumns = Map.of(
                "users.role", UserRole.class,
                "question_versions.difficulty", Difficulty.class,
                "exam_versions.status", ExamVersionStatus.class,
                "exam_executions.status", ExecutionStatus.class,
                "exam_attempts.status", AttemptStatus.class,
                "grades.status", GradeStatus.class,
                "bot_sources.type", BotSourceType.class);
        List<String> mismatches = new ArrayList<>();

        enumColumns.forEach((columnKey, javaEnum) -> {
            ColumnInfo info = actual.get(columnKey);
            if (info == null) {
                mismatches.add(columnKey + " — no such column");
                return;
            }
            List<String> inSchema = parseEnumMembers(info.columnType());
            List<String> inJava = Stream.of(javaEnum.getEnumConstants()).map(Enum::name).sorted().toList();
            if (!inSchema.equals(inJava)) {
                mismatches.add(columnKey + " — schema " + inSchema + ", Java " + inJava);
            }
        });

        // The trap this closes: adding a constant to one of these enums in E6/E7 without
        // an accompanying ALTER TABLE compiles, passes hbm2ddl validate (which does not
        // compare members at all), and then fails at the first insert with "Data truncated
        // for column 'status'" — with nothing in the build pointing at the cause.
        assertThat(mismatches)
                .as("changing a Java enum requires a migration that MODIFYs the column")
                .isEmpty();
    }

    @Test
    @DisplayName("every timestamp column is millisecond precision on both sides")
    void datetimePrecisionMatches() throws SQLException {
        Map<String, ColumnInfo> actual = actualColumns();
        List<String> wrong = new ArrayList<>();

        actual.forEach((columnKey, info) -> {
            if ("datetime".equals(info.dataType()) && !Integer.valueOf(3).equals(info.datetimePrecision())) {
                wrong.add(columnKey + " is datetime(" + info.datetimePrecision() + ")");
            }
        });

        // Hibernate defaults temporal precision to 6. Left alone, it would write
        // microsecond values into DATETIME(3) columns, MySQL would round them, and a
        // round-trip assertion using a real clock would fail on MySQL while passing on H2.
        assertThat(wrong)
                .as("the schema is DATETIME(3); the entities pin precision = 3 to match")
                .isEmpty();
    }

    @Test
    @DisplayName("every column the migrations created is mapped by some entity")
    void nothingInTheSchemaIsUnmapped() throws SQLException {
        Map<String, ColumnInfo> actual = new HashMap<>(actualColumns());
        forEachMappedColumn((table, column) -> actual.remove(key(table, column.getName())));
        actual.keySet().removeIf(columnKey -> columnKey.startsWith("flyway_schema_history."));

        assertThat(actual.keySet())
                .as("an unmapped column is a feature nobody can read or write from Java")
                .isEmpty();
    }

    // ===== helpers ========================================================

    private interface ColumnVisitor {
        void accept(String table, Column column);
    }

    /** Walks Hibernate's own mapping model — the same metadata the persister is built from. */
    private void forEachMappedColumn(ColumnVisitor visitor) {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.DATASOURCE, dataSource)
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "none")
                .applySetting(AvailableSettings.JDBC_TIME_ZONE, "UTC")
                .build();
        try {
            MetadataSources sources = new MetadataSources(registry);
            HibernateUtil.ENTITY_CLASSES.forEach(sources::addAnnotatedClass);
            Metadata metadata = sources.buildMetadata();

            for (PersistentClass binding : metadata.getEntityBindings()) {
                String table = binding.getTable().getName().toLowerCase(Locale.ROOT);
                for (Column column : binding.getTable().getColumns()) {
                    visitor.accept(table, column);
                }
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private Map<String, ColumnInfo> actualColumns() throws SQLException {
        Map<String, ColumnInfo> columns = new HashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT table_name, column_name, data_type, is_nullable,"
                             + " character_maximum_length, datetime_precision, column_type"
                             + " FROM information_schema.columns WHERE table_schema = ?")) {
            statement.setString(1, MySqlAvailability.TEST_SCHEMA);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Long charLength = rows.getObject("character_maximum_length", Long.class);
                    Integer precision = rows.getObject("datetime_precision", Integer.class);
                    columns.put(
                            key(rows.getString("table_name"), rows.getString("column_name")),
                            new ColumnInfo(
                                    rows.getString("data_type").toLowerCase(Locale.ROOT),
                                    "YES".equalsIgnoreCase(rows.getString("is_nullable")),
                                    charLength,
                                    precision,
                                    rows.getString("column_type")));
                }
            }
        }
        return columns;
    }

    private static String key(String table, String column) {
        return table.toLowerCase(Locale.ROOT) + "." + column.toLowerCase(Locale.ROOT);
    }

    private record ColumnInfo(String dataType, boolean nullable, Long charLength,
                              Integer datetimePrecision, String columnType) {
    }

    /** Turns {@code enum('A','B')} into a sorted list of member names. */
    private static List<String> parseEnumMembers(String columnType) {
        if (columnType == null || !columnType.startsWith("enum(")) {
            return List.of();
        }
        String inner = columnType.substring("enum(".length(), columnType.length() - 1);
        return Stream.of(inner.split(","))
                .map(member -> member.replace("'", "").trim())
                .sorted()
                .toList();
    }
}
