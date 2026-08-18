package client.core;

import client.core.ClientConfig.Settings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ClientConfig}'s three-step lookup:
 * external file → bundled classpath resource → hard-coded defaults.
 *
 * <p>Both sources are injected through the package-private
 * {@code load(Path, String)} seam so every branch is reachable without touching
 * the real working directory or the packaged JAR.
 */
class ClientConfigTest {

    private static final String MISSING_RESOURCE = "/no-such-client.properties";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("external file wins over the bundled defaults")
    void externalFileTakesPrecedence() throws IOException {
        Path file = write("server.host=192.168.1.50\nserver.port=6000\n");

        Settings settings = ClientConfig.load(file, "/client.properties");

        assertThat(settings.host()).isEqualTo("192.168.1.50");
        assertThat(settings.port()).isEqualTo(6000);
    }

    @Test
    @DisplayName("surrounding whitespace in the host is trimmed")
    void hostIsTrimmed() throws IOException {
        Path file = write("server.host=   10.0.0.7   \nserver.port=  5555  \n");

        Settings settings = ClientConfig.load(file, MISSING_RESOURCE);

        assertThat(settings.host()).isEqualTo("10.0.0.7");
        assertThat(settings.port()).isEqualTo(5555);
    }

    @Test
    @DisplayName("a non-numeric port falls back to 5555 instead of crashing the client")
    void invalidPortFallsBackToDefault() throws IOException {
        Path file = write("server.host=demo-host\nserver.port=not-a-number\n");

        Settings settings = ClientConfig.load(file, MISSING_RESOURCE);

        assertThat(settings.host()).isEqualTo("demo-host");
        assertThat(settings.port()).isEqualTo(5555);
    }

    @Test
    @DisplayName("keys missing from an existing file fall back per-key")
    void missingKeysFallBackIndividually() throws IOException {
        Path file = write("server.port=7777\n");

        Settings settings = ClientConfig.load(file, MISSING_RESOURCE);

        assertThat(settings.host()).isEqualTo("localhost");
        assertThat(settings.port()).isEqualTo(7777);
    }

    @Test
    @DisplayName("an empty external file yields the built-in defaults")
    void emptyFileYieldsDefaults() throws IOException {
        Path file = write("");

        Settings settings = ClientConfig.load(file, MISSING_RESOURCE);

        assertThat(settings).isEqualTo(new Settings("localhost", 5555));
    }

    @Test
    @DisplayName("no external file → the bundled classpath resource is used")
    void classpathResourceIsUsedWhenNoExternalFile() {
        Path absent = tempDir.resolve("does-not-exist.properties");

        Settings settings = ClientConfig.load(absent, "/client.properties");

        // src/main/resources/client.properties ships localhost:5555
        assertThat(settings.host()).isEqualTo("localhost");
        assertThat(settings.port()).isEqualTo(5555);
    }

    @Test
    @DisplayName("a directory is not mistaken for a config file")
    void directoryIsIgnored() {
        Settings settings = ClientConfig.load(tempDir, "/client.properties");

        assertThat(settings).isEqualTo(new Settings("localhost", 5555));
    }

    @Test
    @DisplayName("null external path is tolerated and falls through")
    void nullExternalPathIsTolerated() {
        Settings settings = ClientConfig.load(null, MISSING_RESOURCE);

        assertThat(settings).isEqualTo(new Settings("localhost", 5555));
    }

    @Test
    @DisplayName("no external file and no bundled resource → hard-coded defaults")
    void hardCodedDefaultsAreTheLastResort() {
        Path absent = tempDir.resolve("nothing-here.properties");

        Settings settings = ClientConfig.load(absent, MISSING_RESOURCE);

        assertThat(settings.host()).isEqualTo("localhost");
        assertThat(settings.port()).isEqualTo(5555);
    }

    @Test
    @DisplayName("the public load() resolves its own sources and always returns usable settings")
    void publicLoadResolvesSourcesItself() {
        Settings settings = ClientConfig.load();

        assertThat(settings).isNotNull();
        assertThat(settings.host()).isNotBlank();
        assertThat(settings.port()).isBetween(1, 65535);
    }

    @Test
    @DisplayName("packaged run: the config file is looked up beside the JAR")
    void externalPathIsResolvedBesideTheJar() throws IOException {
        Path fakeJar = tempDir.resolve("hsts-client.jar");
        Files.writeString(fakeJar, "not really a jar");

        assertThat(ClientConfig.externalPathFor(fakeJar))
                .isEqualTo(tempDir.resolve("client.properties"));
    }

    @Test
    @DisplayName("IDE run (exploded classes): the config file is looked up in the working directory")
    void externalPathFallsBackToWorkingDirectory() {
        assertThat(ClientConfig.externalPathFor(tempDir))
                .isEqualTo(Path.of("client.properties"));
    }

    @Test
    @DisplayName("an unreadable config file is reported, not thrown — defaults still apply")
    void unreadableFileIsSwallowed() {
        Properties props = new Properties();

        // A directory can never be opened as a stream → IOException inside loadFromFile.
        ClientConfig.loadFromFile(props, tempDir);

        assertThat(props).isEmpty();
    }

    @Test
    @DisplayName("Settings is a value object (equality + readable toString)")
    void settingsIsAValueObject() {
        Settings a = new Settings("localhost", 5555);
        Settings b = new Settings("localhost", 5555);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(new Settings("localhost", 5556));
        assertThat(a.toString()).contains("localhost").contains("5555");
    }

    private Path write(String content) throws IOException {
        Path file = tempDir.resolve("client.properties");
        Files.writeString(file, content);
        return file;
    }
}
