package client.ui.components.logic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KickerText} — the kicker's uppercase and its faked tracking (UI wave 2).
 *
 * <p>This class exists because JavaFX CSS has neither {@code text-transform} nor
 * {@code letter-spacing}, so a stylesheet asked for them parses them as unknown
 * and silently does nothing. Doing it in Java means it can be wrong, which means
 * it needs tests, and the one that matters is
 * {@code trackingIsLosslessAndReversible}: a transform that drops a character is
 * a column heading that says something other than what the copy catalogue holds.
 */
class KickerTextTest {

    @Test
    @DisplayName("uppercases and puts a hair space between letters")
    void tracksLetters() {
        assertThat(KickerText.track("Live now"))
                .isEqualTo("L I V E  N O W");
    }

    @Test
    @DisplayName("⚑ the tracking is lossless: the words survive it exactly")
    void trackingIsLosslessAndReversible() {
        // The transform is applied to copy constants that other tests assert on.
        // If it can drop or add a character, a heading on screen and the string
        // in the catalogue stop being the same thing, and no test would see it.
        for (String words : new String[]{"Live now", "Awaiting grading", "Last closed sitting",
                "Teacher's note", "Id", "A"}) {
            assertThat(KickerText.untrack(KickerText.track(words)))
                    .isEqualTo(KickerText.plain(words));
        }
    }

    @Test
    @DisplayName("word gaps stay word gaps rather than becoming more tracking")
    void wordsStaySeparate() {
        // A hair space between words and between letters would make
        // "AWAITING GRADING" read as one very long word.
        assertThat(KickerText.track("Next release")).contains("  ");
        assertThat(KickerText.track("Next release")).doesNotContain("   ");
    }

    @Test
    @DisplayName("⚑ the accessible form is the plain words, never the spelled-out one")
    void theAccessibleFormIsReadable() {
        // A screen reader handed the tracked string spells it out letter by
        // letter. Kicker sets this as the node's accessible text, which is the
        // whole reason the two forms are separate methods.
        assertThat(KickerText.plain("Live now")).isEqualTo("LIVE NOW");
        assertThat(KickerText.plain("Live now")).doesNotContain(" ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    @DisplayName("nothing to track renders as nothing, not as a stray gap")
    void blankIsEmpty(String blank) {
        assertThat(KickerText.track(blank)).isEmpty();
        assertThat(KickerText.plain(blank)).isEmpty();
    }

    @Test
    @DisplayName("a null kicker is empty rather than an exception on a dashboard")
    void nullIsSurvivable() {
        assertThat(KickerText.track(null)).isEmpty();
        assertThat(KickerText.plain(null)).isEmpty();
    }

    @Test
    @DisplayName("runs of whitespace collapse, so a stray double space is not a double gap")
    void whitespaceCollapses() {
        assertThat(KickerText.plain("  Live   now  ")).isEqualTo("LIVE NOW");
    }
}
