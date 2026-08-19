package client.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link FilePropertiesStore} and {@link InMemoryPropertiesStore} (E4.7). */
class PropertiesStoreTest {

    @Nested
    @DisplayName("FilePropertiesStore")
    class FileStore {

        @Test
        void anAbsentFileLoadsAsEmptyRatherThanFailing(@TempDir Path dir) {
            FilePropertiesStore store = new FilePropertiesStore(dir.resolve("nothing-here.properties"));

            assertThat(store.load()).isEmpty();
        }

        @Test
        void savesAndReloads(@TempDir Path dir) {
            FilePropertiesStore store = new FilePropertiesStore(dir.resolve("ui.properties"));
            Properties props = new Properties();
            props.setProperty("ui.theme.mode", "DARK");
            store.save(props);

            assertThat(store.load().getProperty("ui.theme.mode")).isEqualTo("DARK");
            assertThat(store.file()).exists();
        }

        @Test
        void createsMissingParentDirectories(@TempDir Path dir) {
            Path nested = dir.resolve("deep").resolve("deeper").resolve("ui.properties");
            FilePropertiesStore store = new FilePropertiesStore(nested);

            Properties props = new Properties();
            props.setProperty("k", "v");
            store.save(props);

            assertThat(nested).exists();
            assertThat(store.load().getProperty("k")).isEqualTo("v");
        }

        @Test
        void saveReplacesPreviousContent(@TempDir Path dir) {
            FilePropertiesStore store = new FilePropertiesStore(dir.resolve("ui.properties"));
            Properties first = new Properties();
            first.setProperty("a", "1");
            store.save(first);

            Properties second = new Properties();
            second.setProperty("b", "2");
            store.save(second);

            Properties loaded = store.load();
            assertThat(loaded.getProperty("b")).isEqualTo("2");
            assertThat(loaded.getProperty("a")).isNull();
        }

        @Test
        void aCorruptFileLoadsAsEmptyInsteadOfCrashingStartup(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("ui.properties");
            // A malformed unicode escape is what a hand-edited file looks like
            // when someone types a Windows path into it.
            Files.writeString(file, "ui.theme.mode=\\uZZZZ\n");

            assertThat(new FilePropertiesStore(file).load()).isEmpty();
        }

        @Test
        void anUnreadableDirectoryPathLoadsAsEmpty(@TempDir Path dir) throws IOException {
            // Pointing the store at a directory: load() must degrade, not throw.
            Path asDirectory = Files.createDirectory(dir.resolve("ui.properties"));

            assertThat(new FilePropertiesStore(asDirectory).load()).isEmpty();
        }

        @Test
        void aFailedWriteIsSwallowedSoPreferencesNeverBlockTheApp(@TempDir Path dir) throws IOException {
            // The parent exists as a *file*, so createDirectories/newOutputStream fail.
            Path blocker = Files.createFile(dir.resolve("blocker"));
            FilePropertiesStore store = new FilePropertiesStore(blocker.resolve("ui.properties"));

            Properties props = new Properties();
            props.setProperty("k", "v");
            store.save(props);   // must not throw

            assertThat(store.load()).isEmpty();
        }

        @Test
        void resolvesUnderTheUserHomeAppDirectory() {
            // Compared as text, not with Path.endsWith: the real file need not
            // exist on a build machine, and AssertJ's path assertion resolves it.
            Path uiFile = FilePropertiesStore.userHomeFile(FilePropertiesStore.UI_FILE);
            Path connectFile = FilePropertiesStore.inUserHome(FilePropertiesStore.CONNECT_FILE).file();

            assertThat(uiFile.toString())
                    .endsWith(Path.of(FilePropertiesStore.APP_DIR, FilePropertiesStore.UI_FILE).toString());
            assertThat(connectFile.toString())
                    .endsWith(Path.of(FilePropertiesStore.APP_DIR, FilePropertiesStore.CONNECT_FILE).toString());
            assertThat(uiFile.isAbsolute()).isTrue();
        }

        @Test
        void rejectsNulls(@TempDir Path dir) {
            assertThatThrownBy(() -> new FilePropertiesStore(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new FilePropertiesStore(dir.resolve("x")).save(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("InMemoryPropertiesStore")
    class MemoryStore {

        @Test
        void startsEmpty() {
            InMemoryPropertiesStore store = new InMemoryPropertiesStore();

            assertThat(store.isEmpty()).isTrue();
            assertThat(store.load()).isEmpty();
        }

        @Test
        void seedsFromInitialProperties() {
            Properties initial = new Properties();
            initial.setProperty("k", "v");

            assertThat(new InMemoryPropertiesStore(initial).load().getProperty("k")).isEqualTo("v");
            assertThat(new InMemoryPropertiesStore(null).isEmpty()).isTrue();
        }

        @Test
        void copiesOnTheWayInSoTheCallerCannotMutateTheStore() {
            Properties props = new Properties();
            props.setProperty("k", "v");
            InMemoryPropertiesStore store = new InMemoryPropertiesStore();
            store.save(props);

            props.setProperty("k", "changed");

            assertThat(store.load().getProperty("k")).isEqualTo("v");
        }

        @Test
        void copiesOnTheWayOutSoTheCallerCannotMutateTheStore() {
            InMemoryPropertiesStore store = new InMemoryPropertiesStore();
            Properties seed = new Properties();
            seed.setProperty("k", "v");
            store.save(seed);

            store.load().setProperty("k", "changed");

            assertThat(store.load().getProperty("k")).isEqualTo("v");
        }

        @Test
        void saveReplacesEverything() {
            InMemoryPropertiesStore store = new InMemoryPropertiesStore();
            Properties first = new Properties();
            first.setProperty("a", "1");
            store.save(first);

            store.save(new Properties());

            assertThat(store.isEmpty()).isTrue();
        }

        @Test
        void savingNullClearsTheStore() {
            InMemoryPropertiesStore store = new InMemoryPropertiesStore();
            Properties props = new Properties();
            props.setProperty("a", "1");
            store.save(props);

            store.save(null);

            assertThat(store.isEmpty()).isTrue();
        }
    }
}
