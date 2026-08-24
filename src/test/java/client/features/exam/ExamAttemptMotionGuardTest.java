package client.features.exam;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Rule 5 of the UI wave 2 motion spec: the take-exam screens get zero
 * entrance motion.</b>
 *
 * <h2>Why this is a test and not a review note</h2>
 *
 * <p>Wave 2 put motion on almost every surface in the app — routes rise, cards
 * stagger, rows fade in, empty states breathe. All of it is house API, all of it
 * is one import away, and the screens it is wrong on look exactly like the
 * screens it is right on. A student sitting an exam is under a clock she cannot
 * stop; a question card that fades in as she pages to it is the application
 * making her wait for a decoration, and a strip of chips that staggers is the
 * application drawing her eye away from the paper. Nobody would add it on
 * purpose. Somebody would add it by consistency.
 *
 * <p>The manual checklist cannot catch this either: a 180ms fade on a screen a
 * reviewer expects to be lively is invisible to a reviewer who is not timing it.
 * So the rule is enforced by reading the source, the way
 * {@code BankWiringGuardTest} enforces registration.
 *
 * <h2>What is in scope, stated so nobody widens it by accident</h2>
 *
 * <p>The <b>attempt</b> screens: everything a student has open while the clock
 * is running. Two neighbours in this package are deliberately <i>not</i> in
 * scope, and both exclusions are about who is under time pressure:
 *
 * <ul>
 *   <li>{@code ExamDoneView} — shown after the paper is submitted or has timed
 *       out. The clock has stopped, the student has nothing left to do, and the
 *       scale-in that marks the moment is the one piece of motion on this
 *       journey that is unambiguously welcome.</li>
 *   <li>{@code ExecutionMonitorView} — the teacher's screen, not the student's.
 *       Its {@code Timeline} is a once-a-second ticker, which is a clock and not
 *       an animation.</li>
 * </ul>
 *
 * <p>Comments are stripped before the scan, so commenting a line out fails
 * exactly like deleting it, and a javadoc that merely <i>mentions</i>
 * {@code Animations} does not fail the build.
 */
class ExamAttemptMotionGuardTest {

    private static final Path SOURCE_DIR =
            Path.of("src", "main", "java", "client", "features", "exam");

    /**
     * The screens a student has open while her clock is running.
     *
     * <p>Named one by one rather than globbed, for the reason every list in this
     * build is: a glob would quietly cover a class somebody adds later, and
     * "covered by a pattern" is not the same as "somebody decided".
     */
    static List<String> attemptScreens() {
        return List.of(
                "TakeExamView.java",
                "ExamEntryView.java",
                "ExamFormView.java",
                "QuestionCardView.java",
                "AnswerGridView.java",
                "QuestionChip.java");
    }

    /** Every API that starts something moving. */
    private static final List<String> ENTRANCE_APIS = List.of(
            "client.ui.anim.Animations",
            "Animations.",
            "javafx.animation.",
            "FadeTransition",
            "TranslateTransition",
            "ScaleTransition",
            "ParallelTransition",
            "SequentialTransition",
            "RotateTransition",
            "PathTransition",
            "FillTransition");

    @Test
    @DisplayName("the scan really reads the screens, so a green run means something")
    void theScanHasTeeth() {
        // A guard that silently passes because it read nothing is worse than no
        // guard: it is a guard everybody trusts.
        assertThat(SOURCE_DIR).exists();
        for (String screen : attemptScreens()) {
            assertThat(SOURCE_DIR.resolve(screen))
                    .as("%s is named in this guard and must exist", screen)
                    .exists();
            assertThat(read(SOURCE_DIR.resolve(screen))).isNotBlank();
        }
    }

    @ParameterizedTest
    @MethodSource("attemptScreens")
    @DisplayName("⚑ rule 5: an attempt screen references no entrance animation API")
    void noEntranceMotionOnTheAttemptScreens(String screen) {
        String source = stripComments(read(SOURCE_DIR.resolve(screen)));

        for (String api : ENTRANCE_APIS) {
            assertThat(source)
                    .as("%s must stay still: a student under a clock gets a screen that "
                            + "is already there, not one that arrives", screen)
                    .doesNotContain(api);
        }
    }

    @Test
    @DisplayName("the two exclusions are real files, and are excluded for stated reasons")
    void theExclusionsAreDeliberate() {
        // Recorded as an assertion rather than only in prose, so a reader who
        // wonders why ExamDoneView is missing from the list above finds the
        // answer in the same place they found the question.
        assertThat(SOURCE_DIR.resolve("ExamDoneView.java")).exists();
        assertThat(SOURCE_DIR.resolve("ExecutionMonitorView.java")).exists();
        assertThat(attemptScreens())
                .doesNotContain("ExamDoneView.java", "ExecutionMonitorView.java");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path, e);
        }
    }

    /**
     * @return the source with block and line comments removed, so a javadoc that
     *         explains why there is no motion here does not itself fail the scan
     */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }
}
