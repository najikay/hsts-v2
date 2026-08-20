package client.features.locks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Copy-rule tests for the edit-lock UI text (E18.3/E18.4 — PRD §4.1).
 *
 * <p>Every user-visible string in this feature is reachable from one class, so
 * the rules can be checked over the whole set at once by reflection rather than
 * string by string. That is the only way a rule like "no em dashes anywhere"
 * stays true after somebody adds a sentence next epic.
 */
class LockCopyTest {

    @Test
    @DisplayName("the read-only banner is the PRD's exact sentence")
    void readOnlyBannerWording() {
        assertThat(LockCopy.readOnlyBanner("Rina Barak", "question"))
                .isEqualTo("Rina Barak is editing this question. It is read-only for you.");
    }

    @Test
    @DisplayName("the noun is a parameter, so the same helper wraps any editor")
    void bannerIsGeneric() {
        assertThat(LockCopy.readOnlyBanner("Dana Cohen", "exam version"))
                .isEqualTo("Dana Cohen is editing this exam version. It is read-only for you.");
    }

    @ParameterizedTest
    @EnumSource(TakeoverReason.class)
    @DisplayName("both takeover reasons have their own explanation, and they differ")
    void bothReasonsAreExplained(TakeoverReason reason) {
        String text = LockCopy.takeoverExplanation(reason, "question");

        assertThat(text).isNotBlank().contains("question").endsWith(".");
        assertThat(text).isEqualTo(LockCopy.takeoverBanner(reason, "question"));
    }

    @Test
    @DisplayName("losing your own lock is not described as an opportunity")
    void theTwoReasonsSayDifferentThings() {
        String lost = LockCopy.takeoverExplanation(TakeoverReason.LOST, "question");
        String available = LockCopy.takeoverExplanation(TakeoverReason.AVAILABLE, "question");

        assertThat(lost).isNotEqualTo(available);
        assertThat(lost)
                .as("the user has to learn that their own editing session ended")
                .contains("Your editing lock expired");
        assertThat(available).contains("Nobody is editing");
    }

    @Test
    @DisplayName("the conflict dialog says what happened and what reloading costs")
    void conflictCopy() {
        assertThat(LockCopy.CONFLICT_TITLE)
                .isEqualTo("This was changed by someone else while you were editing. "
                        + "Reload the latest version?");
        assertThat(LockCopy.CONFLICT_EXPLANATION)
                .as("reloading is destructive, so it must not be presented as free")
                .contains("Your unsaved text is lost.");
        assertThat(LockCopy.CONFLICT_CONFIRM).isEqualTo("Reload");
        assertThat(LockCopy.CONFLICT_CANCEL).isEqualTo("Keep my text");
    }

    @Test
    @DisplayName("buttons are labelled with what they do, never 'OK'")
    void buttonsAreVerbs() {
        assertThat(List.of(LockCopy.TAKEOVER_CONFIRM, LockCopy.TAKEOVER_CANCEL,
                        LockCopy.CONFLICT_CONFIRM, LockCopy.CONFLICT_CANCEL))
                .doesNotContain("OK", "Yes", "No");
    }

    @Test
    @DisplayName("no user-visible string in the feature contains an em dash (PRD §4.1)")
    void noEmDashesAnywhere() {
        for (String text : allStrings()) {
            assertThat(text).doesNotContain("—").doesNotContain("–");
        }
    }

    @Test
    @DisplayName("no user-visible string is blank")
    void nothingIsBlank() {
        assertThat(allStrings()).isNotEmpty().allSatisfy(text -> assertThat(text).isNotBlank());
    }

    @Test
    @DisplayName("arguments are required")
    void argumentsAreRequired() {
        assertThatNullPointerException().isThrownBy(() -> LockCopy.readOnlyBanner(null, "question"));
        assertThatNullPointerException().isThrownBy(() -> LockCopy.readOnlyBanner("Rina", null));
        assertThatNullPointerException()
                .isThrownBy(() -> LockCopy.takeoverExplanation(null, "question"));
        assertThatNullPointerException()
                .isThrownBy(() -> LockCopy.takeoverExplanation(TakeoverReason.LOST, null));
    }

    /** Every constant plus every composed sentence this class can produce. */
    private static List<String> allStrings() {
        List<String> texts = new ArrayList<>();
        for (Field field : LockCopy.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                try {
                    texts.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError("Cannot read " + field.getName(), e);
                }
            }
        }
        texts.add(LockCopy.readOnlyBanner("Rina Barak", "question"));
        for (TakeoverReason reason : TakeoverReason.values()) {
            texts.add(LockCopy.takeoverExplanation(reason, "question"));
        }
        return texts;
    }
}
