package server.db.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import server.db.seed.tools.SeedImageGenerator;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The build-time guard for B-8: a question marked {@code img} has an illustration that loads.
 *
 * <h2>What this catches, and what it does not ⚑</h2>
 *
 * <p>It resolves every flagged question's image <b>through {@code getResourceAsStream}, the same
 * call {@code QuestionBankSection} makes</b>. That is deliberate and is the whole reason this is
 * worth writing: a test that looked for the file on disk would pass from a working copy and prove
 * nothing about what ships, and "worked locally, missing from the jar" is a failure this project
 * has already met once - it is why the lead ruled the bytes into {@code src/main/resources}
 * rather than {@code docs/seed/img/}.
 *
 * <p><b>It does NOT check that a committed PNG is what {@link SeedImageGenerator} would draw
 * today.</b> Four of the ten carry text, and Java2D maps {@code SANS_SERIF} to whatever physical
 * font the host has, so the bytes differ between a Windows author and CI's Linux. A byte-equality
 * assertion would pass for whoever generated the images and fail for everyone else, which is a
 * guard that only works on one machine. So: edit a drawing, rerun the generator, commit the
 * output - <b>nothing here will catch you if you skip the middle step</b>.
 *
 * <p>Three drift directions are covered, because the pair can disagree either way and a third
 * list exists:
 *
 * <ol>
 *   <li>a question flagged {@code img} with no resource - {@link #everyFlaggedQuestionHasAnImage}</li>
 *   <li>a resource with no question flagged for it - {@link #noOrphanImages}</li>
 *   <li>the generator drawing a set that is not the flagged set - {@link #generatorMatchesTheSeed}</li>
 * </ol>
 *
 * <p><b>The first two read from different places on purpose, and the difference was measured
 * rather than reasoned about.</b> Deleting {@code q11007.png} and running without {@code clean}
 * failed <em>only</em> {@link #noOrphanImages}: Maven had already copied the old file into
 * {@code target/classes}, so the classpath lookup found a stale copy and passed. After
 * {@code clean} both fail. So {@link #everyFlaggedQuestionHasAnImage} answers "will the packaged
 * jar have this?" and is trustworthy on CI, which runs {@code clean verify}; on an incremental
 * local build it can be reading yesterday's resources. {@link #noOrphanImages} reads the source
 * tree and is immediate. Neither is redundant.
 */
@DisplayName("B-8: the seeded illustrations")
class SeedImagesTest {

    /**
     * The questions <b>the seed flags</b>, which is the only list with authority here.
     *
     * <p>This read {@code SeedImageGenerator.drawings().keySet()} until a cold read caught it.
     * Every test in this class was then driven by the generator's list, so the class never once
     * consulted the seed: moving an {@code img} flag from {@code 21006} to {@code 21007} left the
     * count at ten, left {@code q21006.png} resolvable, and left {@link #generatorMatchesTheSeed}
     * comparing an expression to itself. All green, on a seed that throws at load.
     */
    private static List<String> illustratedIds() {
        return QuestionBankSection.illustratedIds();
    }

    static Stream<String> flaggedQuestions() {
        return illustratedIds().stream();
    }

    @ParameterizedTest(name = "question {0} has a loadable illustration")
    @MethodSource("flaggedQuestions")
    void everyFlaggedQuestionHasAnImage(String displayId) throws IOException {
        String resource = QuestionBankSection.illustrationResource(displayId);

        byte[] bytes;
        try (InputStream in = QuestionBankSection.class.getResourceAsStream(resource)) {
            assertThat(in)
                    .as("%s must be on the classpath, not merely in the working copy", resource)
                    .isNotNull();
            bytes = in.readAllBytes();
        }

        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        assertThat(image).as("%s must decode as an image", resource).isNotNull();
        assertThat(image.getWidth()).isEqualTo(SeedImageGenerator.WIDTH);
        assertThat(image.getHeight()).isEqualTo(SeedImageGenerator.HEIGHT);

        // Not blank. A correctly sized sheet of white would satisfy everything above it and show
        // the examiner nothing, which is the failure this whole ticket is about.
        Set<Integer> colours = new HashSet<>();
        for (int x = 0; x < image.getWidth(); x += 3) {
            for (int y = 0; y < image.getHeight(); y += 3) {
                colours.add(image.getRGB(x, y));
                if (colours.size() > 8) {
                    return;
                }
            }
        }
        assertThat(colours)
                .as("%s renders almost nothing - it is blank or near blank", resource)
                .hasSizeGreaterThan(8);
    }

    @Test
    @DisplayName("exactly ten questions are illustrated, and the seed agrees")
    void tenQuestionsAreIllustrated() {
        assertThat(illustratedIds()).hasSize(10);
        assertThat(QuestionBankSection.illustratedCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("no image is left behind for a question that is no longer illustrated")
    void noOrphanImages() throws IOException {
        // The other direction of drift: a flag removed and the PNG forgotten. Reads the source
        // tree rather than the classpath on purpose - an orphan is a committed file, and the
        // question is what the repository holds, not what got packaged.
        Path dir = SeedImageGenerator.OUTPUT_DIR;
        assertThat(dir).as("the image directory must exist").exists();

        try (Stream<Path> files = Files.list(dir)) {
            List<String> present = files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".png"))
                    .sorted()
                    .toList();
            List<String> expected = illustratedIds().stream()
                    .map(id -> "q" + id + ".png")
                    .sorted()
                    .toList();
            assertThat(present)
                    .as("every PNG must belong to a question the seed still marks as illustrated")
                    .containsExactlyElementsOf(expected);
        }
    }

    @Test
    @DisplayName("the generator draws exactly the questions the seed flags")
    void generatorMatchesTheSeed() {
        // Third list, third drift axis. The generator is where a new illustration is added, and
        // adding one there without flagging the question would produce an image nothing loads.
        // Both sides were the generator's own key set until 2026-08-26, which made this pass for
        // every possible input. It now compares the generator against QuestionBankSection.
        assertThat(SeedImageGenerator.drawings().keySet())
                .containsExactlyInAnyOrderElementsOf(QuestionBankSection.illustratedIds());
    }

    @Test
    @DisplayName("a question flagged for an image that has no resource fails the load, loudly")
    void aMissingResourceIsNotSilentlyNull() {
        // This test used to assert that getResourceAsStream returns null for a bogus id and that
        // calling readAllBytes() on null throws NPE. That tested the JDK. illustrationFor was
        // private and took a private record, so the refusal it describes could not be called at
        // all, and "fails loudly" was asserted in a javadoc and a PR report having never once
        // been executed. The signature was widened so this could be written.
        assertThatThrownBy(() -> QuestionBankSection.illustrationFor("99999", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("99999")
                .as("the message must name the question and the resource, because whoever meets "
                        + "this is looking at a seed that refused and needs to know which file")
                .hasMessageContaining("/seed/img/q99999.png");
    }

    @Test
    @DisplayName("an unillustrated question asks for nothing and gets null")
    void anUnillustratedQuestionLoadsNoBytes() {
        // The other branch. Thirty of the forty questions take this path, so a refusal that fired
        // for them would break the whole seed rather than one picture.
        assertThat(QuestionBankSection.illustrationFor("11001", false)).isNull();
        assertThat(QuestionBankSection.illustrationFor("99999", false))
                .as("not illustrated is answered before the resource is ever looked for")
                .isNull();
    }

    @Test
    @DisplayName("an illustrated question returns the bytes that are on the classpath")
    void anIllustratedQuestionLoadsItsBytes() {
        byte[] bytes = QuestionBankSection.illustrationFor("11005", true);
        assertThat(bytes).isNotNull().isNotEmpty();
        // PNG magic. Proves it returned the file rather than something that merely had length.
        assertThat(new byte[] {bytes[0], bytes[1], bytes[2], bytes[3]})
                .containsExactly((byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47);
    }
}
