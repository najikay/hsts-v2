package server.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.core.ServerConfig.Credentials;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ServerConfig}'s three-step lookup:
 * external file → bundled classpath resource → hard-coded {@code root/root}.
 *
 * <p>Credentials never appear in source, so the fallback chain is the only thing
 * standing between a fresh machine and a failed demo — every branch is covered
 * through the package-private {@code load(Path, String)} seam.
 */
class ServerConfigTest {

    private static final String MISSING_RESOURCE = "/no-such-server.properties";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("external file wins over the bundled defaults")
    void externalFileTakesPrecedence() throws IOException {
        Path file = write("db.user=hsts_app\ndb.password=s3cr3t\n");

        Credentials creds = ServerConfig.load(file, "/server.properties");

        assertThat(creds.user()).isEqualTo("hsts_app");
        assertThat(creds.password()).isEqualTo("s3cr3t");
    }

    @Test
    @DisplayName("the user is trimmed but the password is taken verbatim")
    void userIsTrimmedPasswordIsNot() throws IOException {
        Path file = write("db.user=  dba  \ndb.password= keep me \n");

        Credentials creds = ServerConfig.load(file, MISSING_RESOURCE);

        assertThat(creds.user()).isEqualTo("dba");
        // Properties strips leading whitespace of a value; trailing spaces are significant.
        assertThat(creds.password()).isEqualTo("keep me ");
    }

    @Test
    @DisplayName("an empty password is honoured, not replaced by the default")
    void emptyPasswordIsHonoured() throws IOException {
        Path file = write("db.user=root\ndb.password=\n");

        Credentials creds = ServerConfig.load(file, MISSING_RESOURCE);

        assertThat(creds.user()).isEqualTo("root");
        assertThat(creds.password()).isEmpty();
    }

    @Test
    @DisplayName("keys missing from an existing file fall back per-key")
    void missingKeysFallBackIndividually() throws IOException {
        Path file = write("db.user=only_user\n");

        Credentials creds = ServerConfig.load(file, MISSING_RESOURCE);

        assertThat(creds.user()).isEqualTo("only_user");
        assertThat(creds.password()).isEqualTo("root");
    }

    @Test
    @DisplayName("no external file → the bundled classpath resource is used")
    void classpathResourceIsUsedWhenNoExternalFile() {
        Path absent = tempDir.resolve("does-not-exist.properties");

        Credentials creds = ServerConfig.load(absent, "/server.properties");

        // src/main/resources/server.properties ships the dummy root/root pair
        assertThat(creds).isEqualTo(new Credentials("root", "root"));
    }

    @Test
    @DisplayName("a directory is not mistaken for a config file")
    void directoryIsIgnored() {
        Credentials creds = ServerConfig.load(tempDir, MISSING_RESOURCE);

        assertThat(creds).isEqualTo(new Credentials("root", "root"));
    }

    @Test
    @DisplayName("null external path is tolerated and falls through")
    void nullExternalPathIsTolerated() {
        Credentials creds = ServerConfig.load(null, MISSING_RESOURCE);

        assertThat(creds).isEqualTo(new Credentials("root", "root"));
    }

    @Test
    @DisplayName("no external file and no bundled resource → hard-coded root/root")
    void hardCodedDefaultsAreTheLastResort() {
        Path absent = tempDir.resolve("nothing-here.properties");

        Credentials creds = ServerConfig.load(absent, MISSING_RESOURCE);

        assertThat(creds.user()).isEqualTo("root");
        assertThat(creds.password()).isEqualTo("root");
    }

    @Test
    @DisplayName("the public load() resolves its own sources and always returns credentials")
    void publicLoadResolvesSourcesItself() {
        Credentials creds = ServerConfig.load();

        assertThat(creds).isNotNull();
        assertThat(creds.user()).isNotBlank();
        assertThat(creds.password()).isNotNull();
    }

    @Test
    @DisplayName("packaged run: the config file is looked up beside the JAR")
    void externalPathIsResolvedBesideTheJar() throws IOException {
        Path fakeJar = tempDir.resolve("hsts-server.jar");
        Files.writeString(fakeJar, "not really a jar");

        assertThat(ServerConfig.externalPathFor(fakeJar))
                .isEqualTo(tempDir.resolve("server.properties"));
    }

    @Test
    @DisplayName("IDE run (exploded classes): the config file is looked up in the working directory")
    void externalPathFallsBackToWorkingDirectory() {
        assertThat(ServerConfig.externalPathFor(tempDir))
                .isEqualTo(Path.of("server.properties"));
    }

    @Test
    @DisplayName("an unreadable config file is reported, not thrown — defaults still apply")
    void unreadableFileIsSwallowed() {
        Properties props = new Properties();

        // A directory can never be opened as a stream -> IOException inside loadFromFile.
        ServerConfig.loadFromFile(props, tempDir);

        assertThat(props).isEmpty();
    }

    @Test
    @DisplayName("the whole file is readable too, for the settings that are not credentials (E16.1)")
    void loadsEveryProperty() throws IOException {
        Path file = write("db.user=hsts_app\nbot.deepseek.key=sk-test\nbot.rate.per.minute=7\n");

        Properties props = ServerConfig.loadProperties(file, MISSING_RESOURCE);

        assertThat(props.getProperty("db.user")).isEqualTo("hsts_app");
        assertThat(props.getProperty("bot.deepseek.key")).isEqualTo("sk-test");
        assertThat(props.getProperty("bot.rate.per.minute")).isEqualTo("7");
    }

    @Test
    @DisplayName("the whole-file read falls back to the classpath, then to nothing")
    void loadsEveryPropertyFallsBack() {
        // The bundled resource exists in this build, so it resolves; the third
        // branch is the one that matters on a fresh machine with no file anywhere.
        assertThat(ServerConfig.loadProperties(null, "/server.properties")).isNotNull();
        assertThat(ServerConfig.loadProperties(tempDir.resolve("absent.properties"),
                MISSING_RESOURCE)).isEmpty();
    }

    @Test
    @DisplayName("the real lookup runs without a file argument")
    void loadsEveryPropertyFromTheRealSources() {
        assertThat(ServerConfig.loadProperties()).isNotNull();
    }

    @Test
    @DisplayName("Credentials is a value object (equality + hashCode)")
    void credentialsIsAValueObject() {
        Credentials a = new Credentials("root", "root");
        Credentials b = new Credentials("root", "root");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(new Credentials("root", "other"));
        assertThat(a.toString()).contains("root");
    }

    private Path write(String content) throws IOException {
        Path file = tempDir.resolve("server.properties");
        Files.writeString(file, content);
        return file;
    }
}
