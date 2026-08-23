package client.features.bank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The legacy screen writes nothing and locks nothing (E6.14 condition ⚑).
 *
 * <p>The lead's condition for splitting the retirement out of PR-B: the prototype stays
 * <b>readable</b> and stops taking edit locks. The reason is the one the {@code EntityRef} ruling
 * exists for. Locks are keyed {@code (type, long id)} and there is no third numbering scheme
 * going spare: this screen keyed {@code EntityRef.QUESTION} by the {@code questions} primary key,
 * while the versioned editor keys the same type by {@code displayId5}. With both screens live and
 * both locking, two teachers on one question can hold different keys and never see each other,
 * and two teachers on different questions can collide on one key.
 *
 * <p>So this is the guard on a property rather than a test of a behaviour, and it holds only
 * until the retirement PR deletes the screen and takes this file with it.
 *
 * <h2>Why it reads the source rather than driving the screen</h2>
 *
 * <p>The property is an <b>absence</b>, and absences are what a running test is worst at proving:
 * a screen that fails to take a lock and a screen that has no lock code look identical from the
 * outside, so a driven test would pass just as happily if the wiring were merely broken. Scanning
 * for the mechanism is the honest check here, and it is the same shape
 * {@code BankWiringGuardTest} uses on the server assembly for the same reason.
 *
 * <p>It replaces {@code client.ui.QuestionEditorLockInteractionTest}, which tested the edit path
 * this condition removes and was deleted with it (the lead opened that file for PR-B). No unique
 * E18 coverage went with it: {@code LockAwareEditorTest}, {@code EditLockStateTest}, the three
 * server-side lock suites and {@code BotInteractionTest}'s live consumer all remain.
 */
class LegacyScreenIsReadOnlyTest {

    private static final Path LEGACY =
            Path.of("src/main/java/client/features/bank/QuestionsView.java");

    private static String source() throws IOException {
        assertThat(LEGACY).as("the legacy screen is still here until the retirement PR").exists();
        return Files.readString(LEGACY);
    }

    /** Comments explain the removal, so the check has to ignore them or it guards nothing. */
    private static String code() throws IOException {
        return source()
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("//[^\\n]*", "");
    }

    @Test
    @DisplayName("⚑ it takes no edit lock, so only one key scheme is ever live")
    void takesNoLock() throws IOException {
        String code = code();

        assertThat(code)
                .as("a second live lock scheme is exactly what the EntityRef ruling refuses, and "
                        + "it would be reintroduced by re-adding any of these")
                .doesNotContain("LockAwareEditor")
                .doesNotContain("EntityRef.question(")
                .doesNotContain("LockBanner");
    }

    @Test
    @DisplayName("⚑ it sends no write verb")
    void sendsNoWrite() throws IOException {
        assertThat(code())
                .as("UPDATE_QUESTION is the prototype's only write. Reading is what this screen "
                        + "keeps until retirement; writing moved to the versioned editor.")
                .doesNotContain("UPDATE_QUESTION");
    }

    @Test
    @DisplayName("the read path is deliberately still there, so this is read-only and not dead")
    void stillReads() throws IOException {
        assertThat(code())
                .as("the condition was read-only, not removal. A screen that had lost its read "
                        + "as well would be a rail item showing nothing, which is the retirement "
                        + "PR's job rather than this one's.")
                .contains("GET_ALL_QUESTIONS");
    }

    @Test
    @DisplayName("the FXML-bound handlers survive, because the loader binds them by name")
    void handlersSurvive() throws IOException {
        String code = code();

        assertThat(code)
                .as("QuestionsView.fxml has onAction=\"#onSave\" and \"#onRevert\". FXMLLoader "
                        + "fails on a controller missing either, and the FXML is not Member A's "
                        + "to edit, so the methods outlive their buttons until retirement.")
                .contains("private void onSave()")
                .contains("private void onRevert()");
    }
}
