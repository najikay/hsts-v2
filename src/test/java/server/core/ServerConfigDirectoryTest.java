package server.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the server keeps its identity file (U-22, 2026-08-29).
 *
 * <p>The deliverable keeps {@code server-id.properties} beside the jar. A dev build keeps
 * the jar under {@code target}, which {@code clean} empties, so the id was reborn on every
 * rebuild and every pinned client warned of a replaced server. Under {@code target} the id
 * goes one level up; everywhere else the rule is unchanged.
 */
class ServerConfigDirectoryTest {

    @Test
    @DisplayName("beside the jar, as the deliverable ships")
    void besideTheJar() {
        Path folder = Path.of("C:", "HSTS");
        assertThat(ServerMain.configDirectoryFor(folder)).isEqualTo(folder);
    }

    @Test
    @DisplayName("one level up when the jar sits under a Maven target directory")
    void aboveTarget() {
        Path target = Path.of("C:", "dev", "hsts-v2", "target");
        assertThat(ServerMain.configDirectoryFor(target)).isEqualTo(target.getParent());
    }

    @Test
    @DisplayName("a bare 'target' with no parent, and the working directory, are left alone")
    void edges() {
        assertThat(ServerMain.configDirectoryFor(Path.of("target"))).isEqualTo(Path.of("target"));
        assertThat(ServerMain.configDirectoryFor(Path.of(""))).isEqualTo(Path.of(""));
    }
}
