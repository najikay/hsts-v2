package server.db;

import jakarta.persistence.Entity;
import org.mockito.Mockito;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Guards the entity registry (E2.10). No database needed.
 *
 * <p>{@link HibernateUtil#ENTITY_CLASSES} is written out by hand rather than discovered
 * by scanning, because scanning fails silently. The cost of that choice is that someone
 * will one day add an entity and forget to register it — and the symptom would be an
 * "unknown entity" error from a query, far from the cause.
 *
 * <p>{@link #everyEntityInThePackageIsRegistered()} is what makes the choice safe: it
 * does the scan the production code deliberately avoids, and fails the build the moment
 * the two disagree.
 */
class HibernateUtilTest {

    private static final Path COMPILED_ENTITIES = Path.of("target", "classes", "server", "db", "entities");

    @Test
    @DisplayName("every @Entity in the package is registered — and nothing else is")
    void everyEntityInThePackageIsRegistered() {
        assertThat(entitiesOnDisk())
                .as("HibernateUtil.ENTITY_CLASSES must list exactly the @Entity classes in "
                        + "server.db.entities — add yours there when you add a table")
                .containsExactlyInAnyOrderElementsOf(HibernateUtil.ENTITY_CLASSES);
    }

    @Test
    @DisplayName("the registry lists each entity once")
    void registryHasNoDuplicates() {
        assertThat(HibernateUtil.ENTITY_CLASSES).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("one entity per table in ARCHITECTURE §5")
    void registryCoversTheWholeSchema() {
        // 20 tables in V1..V7. FlywayCleanRunTest asserts the same number from the other
        // side, against the real database.
        assertThat(HibernateUtil.ENTITY_CLASSES).hasSize(20);
    }

    @Test
    @DisplayName("building a factory without a data source is rejected outright")
    void nullArgumentsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> HibernateUtil.build(null, java.util.Map.of()));
        // A mock rather than a real pool: the check must happen before anything is opened,
        // so a DataSource that cannot connect is the honest argument here.
        assertThatNullPointerException()
                .isThrownBy(() -> HibernateUtil.build(Mockito.mock(DataSource.class), null));
    }

    /** The classpath scan {@link HibernateUtil} deliberately does not do at runtime. */
    private static List<Class<?>> entitiesOnDisk() {
        try (Stream<Path> files = Files.list(COMPILED_ENTITIES)) {
            return files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".class"))
                    // Skip nested classes such as the @Embeddable composite ids.
                    .filter(name -> !name.contains("$"))
                    .map(name -> "server.db.entities." + name.substring(0, name.length() - ".class".length()))
                    .map(HibernateUtilTest::load)
                    .filter(type -> type.isAnnotationPresent(Entity.class))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not scan " + COMPILED_ENTITIES.toAbsolutePath(), e);
        }
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Compiled class not loadable: " + name, e);
        }
    }
}
