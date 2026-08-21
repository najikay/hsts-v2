package common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The external-config search order (E20.4).
 *
 * <p>The whole point of this class is the order, so the order is what is asserted:
 * beside the JAR first, the working directory second, and neither of them twice
 * when they are the same directory. The classpath step is not here because it
 * belongs to the two config classes that own their bundled resource.
 */
class ExternalConfigTest {

    private static final String FILE = "demo.properties";

    @TempDir
    Path tempDir;

    /** A file that stands in for the running JAR. Its contents never matter. */
    private Path fakeJar() throws IOException {
        Path jar = tempDir.resolve("G13_Server.jar");
        Files.writeString(jar, "not really a jar");
        return jar;
    }

    @Nested
    @DisplayName("candidates")
    class Candidates {

        @Test
        @DisplayName("packaged run: beside the JAR first, then the working directory")
        void packagedRunSearchesBothInOrder() throws IOException {
            List<Path> candidates = ExternalConfig.candidates(fakeJar(), FILE);

            assertThat(candidates).containsExactly(
                    tempDir.resolve(FILE),
                    Path.of(FILE));
        }

        @Test
        @DisplayName("IDE run (exploded classes): only the working directory")
        void explodedClassesSearchTheWorkingDirectory() {
            assertThat(ExternalConfig.candidates(tempDir, FILE))
                    .containsExactly(Path.of(FILE));
        }

        @Test
        @DisplayName("an unknown code source is not an error: the working directory still applies")
        void nullCodeSourceStillSearchesTheWorkingDirectory() {
            assertThat(ExternalConfig.candidates(null, FILE))
                    .containsExactly(Path.of(FILE));
        }

        @Test
        @DisplayName("a JAR run from its own directory is not searched twice")
        void sameDirectoryIsNotSearchedTwice() {
            // Any regular file in the working directory will do; this build always
            // runs from the directory its POM is in.
            Path jarInWorkingDirectory = Path.of("pom.xml").toAbsolutePath();
            assertThat(Files.isRegularFile(jarInWorkingDirectory)).isTrue();

            assertThat(ExternalConfig.candidates(jarInWorkingDirectory, FILE))
                    .containsExactly(jarInWorkingDirectory.getParent().resolve(FILE));
        }

        @Test
        @DisplayName("a code source with no parent directory degrades to the working directory")
        void codeSourceWithoutParentIsSkipped() {
            // A bare relative file name has a null parent; nothing to look beside.
            Path bare = Path.of("pom.xml");
            assertThat(bare.getParent()).isNull();

            assertThat(ExternalConfig.candidates(bare, FILE))
                    .containsExactly(Path.of(FILE));
        }
    }

    @Nested
    @DisplayName("resolve")
    class Resolve {

        @Test
        @DisplayName("takes the first candidate that exists")
        void firstExistingWins() throws IOException {
            Path second = tempDir.resolve(FILE);
            Files.writeString(second, "chosen=true\n");

            Path resolved = ExternalConfig.resolve(
                    List.of(tempDir.resolve("absent.properties"), second));

            assertThat(resolved).isEqualTo(second);
        }

        @Test
        @DisplayName("beside the JAR wins even when the working directory has one too")
        void besideTheJarBeatsTheWorkingDirectory() throws IOException {
            Path besideJar = tempDir.resolve(FILE);
            Files.writeString(besideJar, "chosen=true\n");
            Path workingDirectory = Path.of("pom.xml"); // exists, but comes second

            assertThat(ExternalConfig.resolve(List.of(besideJar, workingDirectory)))
                    .isEqualTo(besideJar);
        }

        @Test
        @DisplayName("a directory of the right name is not a config file")
        void directoriesAreNotConfigFiles() throws IOException {
            Path directory = Files.createDirectory(tempDir.resolve(FILE));
            Path fallback = tempDir.resolve("fallback.properties");

            assertThat(ExternalConfig.resolve(List.of(directory, fallback)))
                    .isEqualTo(fallback);
        }

        @Test
        @DisplayName("when nothing exists it answers the last candidate, so the caller can name it")
        void nothingFoundAnswersTheLastCandidate() {
            Path last = Path.of(FILE);

            assertThat(ExternalConfig.resolve(List.of(tempDir.resolve("absent.properties"), last)))
                    .isEqualTo(last);
        }

        @Test
        @DisplayName("no candidates at all is a programming error, not a fallback")
        void emptyCandidatesIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ExternalConfig.resolve(List.of()));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ExternalConfig.resolve(null));
        }
    }

    @Nested
    @DisplayName("locate")
    class Locate {

        @Test
        @DisplayName("reads the file beside the JAR when it is there")
        void locatesTheFileBesideTheJar() throws IOException {
            Path jar = fakeJar();
            Path besideJar = tempDir.resolve(FILE);
            Files.writeString(besideJar, "db.user=beside_the_jar\n");

            assertThat(ExternalConfig.locate(jar, FILE)).isEqualTo(besideJar);
        }

        @Test
        @DisplayName("falls through to the working-directory name when it is not")
        void fallsThroughToTheWorkingDirectory() throws IOException {
            assertThat(ExternalConfig.locate(fakeJar(), FILE)).isEqualTo(Path.of(FILE));
        }
    }
}
