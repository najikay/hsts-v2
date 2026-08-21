package server.discovery;

import common.dto.discovery.Fingerprints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The server's identity: generated once, kept, and regenerable (E19.8 / E19.9,
 * F13.3).
 *
 * <p>The persistence is the whole feature. A fingerprint that changed on every
 * boot would make every pinned client warn on every launch, which would train
 * people to click through the one warning that is supposed to mean something.
 */
class ServerFingerprintTest {

    private static ServerFingerprint store(Path directory, String... ids) {
        AtomicInteger next = new AtomicInteger();
        return new ServerFingerprint(directory.resolve(ServerFingerprint.FILE_NAME),
                () -> ids[Math.min(next.getAndIncrement(), ids.length - 1)]);
    }

    @Test
    @DisplayName("first boot generates an id and writes it beside server.properties")
    void firstBoot(@TempDir Path directory) throws IOException {
        ServerFingerprint.Identity identity = store(directory, "abc-123").loadOrCreate();

        assertThat(identity.fingerprint()).isEqualTo("abc-123");
        assertThat(identity.name()).isEqualTo(ServerFingerprint.DEFAULT_NAME);

        Path file = directory.resolve(ServerFingerprint.FILE_NAME);
        assertThat(file).exists();
        Properties saved = new Properties();
        try (var in = Files.newInputStream(file)) {
            saved.load(in);
        }
        assertThat(saved.getProperty(ServerFingerprint.KEY_FINGERPRINT)).isEqualTo("abc-123");
    }

    @Test
    @DisplayName("a second boot reads the same id back rather than minting a new one")
    void secondBootIsStable(@TempDir Path directory) {
        ServerFingerprint store = store(directory, "first", "second");

        String once = store.loadOrCreate().fingerprint();
        String twice = store.loadOrCreate().fingerprint();

        assertThat(twice)
                .as("a pinned client must not see a mismatch after every restart")
                .isEqualTo(once)
                .isEqualTo("first");
    }

    @Test
    @DisplayName("a separate process reading the same file gets the same id")
    void survivesAcrossInstances(@TempDir Path directory) {
        store(directory, "persisted").loadOrCreate();

        assertThat(store(directory, "would-be-new").loadOrCreate().fingerprint())
                .isEqualTo("persisted");
    }

    @Test
    @DisplayName("regenerating mints a new id and keeps the name")
    void regenerate(@TempDir Path directory) {
        ServerFingerprint store = store(directory, "old", "new");
        store.rename("Room 12 server");

        ServerFingerprint.Identity regenerated = store.regenerate();

        assertThat(regenerated.fingerprint())
                .as("the deliberate version of a reinstall, for a cloned disk image")
                .isEqualTo("new");
        assertThat(regenerated.name()).isEqualTo("Room 12 server");
        assertThat(store.loadOrCreate().fingerprint()).isEqualTo("new");
    }

    @Test
    @DisplayName("renaming keeps the id, so a rename is not a mismatch")
    void rename(@TempDir Path directory) {
        ServerFingerprint store = store(directory, "keep-me");
        store.loadOrCreate();

        ServerFingerprint.Identity renamed = store.rename("Lab B");

        assertThat(renamed.fingerprint()).isEqualTo("keep-me");
        assertThat(renamed.name()).isEqualTo("Lab B");
        assertThat(store.loadOrCreate().name()).isEqualTo("Lab B");
    }

    @Test
    @DisplayName("a blank name falls back rather than showing an empty picker row")
    void blankName(@TempDir Path directory) {
        assertThat(store(directory, "id").rename("   ").name())
                .isEqualTo(ServerFingerprint.DEFAULT_NAME);
        assertThat(new ServerFingerprint.Identity(null, "id").name())
                .isEqualTo(ServerFingerprint.DEFAULT_NAME);
    }

    @Test
    @DisplayName("a stored file with a blank id is treated as no id at all")
    void blankStoredId(@TempDir Path directory) throws IOException {
        Path file = directory.resolve(ServerFingerprint.FILE_NAME);
        Files.writeString(file, ServerFingerprint.KEY_FINGERPRINT + "=   \n");

        assertThat(store(directory, "fresh").loadOrCreate().fingerprint()).isEqualTo("fresh");
    }

    @Test
    @DisplayName("an unwritable location degrades to a per-run id rather than refusing to start")
    void unwritableLocation(@TempDir Path directory) throws IOException {
        // A file where the directory should be: creating the parent must fail.
        Path blocker = directory.resolve("blocked");
        Files.writeString(blocker, "not a directory");
        ServerFingerprint store = new ServerFingerprint(
                blocker.resolve("sub").resolve(ServerFingerprint.FILE_NAME), () -> "temporary");

        ServerFingerprint.Identity identity = store.loadOrCreate();

        assertThat(identity.fingerprint())
                .as("refusing to boot over a file permission would be the worse failure")
                .isEqualTo("temporary");
    }

    @Test
    @DisplayName("the production store puts the file beside server.properties")
    void productionStore(@TempDir Path directory) {
        assertThat(ServerFingerprint.in(directory).file())
                .isEqualTo(directory.resolve(ServerFingerprint.FILE_NAME));
        assertThat(ServerFingerprint.in(directory).loadOrCreate().fingerprint())
                .as("a real random id, not a fixture")
                .satisfies(id -> UUID.fromString(id));
    }

    @Test
    @DisplayName("the display form is the grouped one both tiers render")
    void shortForm() {
        assertThat(ServerFingerprint.shortForm("7f3a2b91-1111-2222-3333-444444444444"))
                .isEqualTo("7F3A-2B91");
        assertThat(new ServerFingerprint.Identity("n", "7f3a2b91-0000-0000-0000-000000000000")
                .shortFingerprint()).isEqualTo("7F3A-2B91");
        assertThat(ServerFingerprint.shortForm(null)).isEqualTo(Fingerprints.UNKNOWN);
    }

    @Test
    @DisplayName("collaborators are required")
    void required(@TempDir Path directory) {
        assertThatNullPointerException()
                .isThrownBy(() -> new ServerFingerprint(null, () -> "x"));
        assertThatNullPointerException()
                .isThrownBy(() -> new ServerFingerprint(directory.resolve("f"), null));
        assertThatNullPointerException()
                .isThrownBy(() -> new ServerFingerprint.Identity("name", null));
    }
}
