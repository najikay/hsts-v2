package client.ui.components;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.IkonHandler;
import org.kordamp.ikonli.javafx.IkonResolver;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every icon literal {@link Icons} declares resolves in the material2 pack ⚑ (B-38).
 *
 * <p><b>The bug this exists to stop, stated once.</b> {@code Icons.of} catches the resolver's
 * exception by design and renders an invisible spacer, which is right for a data-driven
 * literal — a typo in a config file must not throw in the middle of building the shell. For
 * the constants it is the opposite of right: a constant is a claim that a glyph exists, and
 * the swallow turned four false claims into a {@code WARN} line nobody read and a hole in a
 * layout nobody noticed. {@code BOT} was caught by hand in E16, {@code MONITOR} by batch C
 * when enabling the Live Monitor rail item would have put a blank icon in front of every
 * teacher, and {@code LOGOUT} and {@code WARNING} by this test the day it was written. Three
 * sightings of one bug is what a ten-line guard is for.
 *
 * <p><b>Why it resolves twice.</b> {@code IkonResolver.getInstance().resolve(literal)} answers
 * on the {@code mdoal-} / {@code mdomz-} <em>prefix</em> alone: it hands back the pack's
 * handler and says nothing about whether the pack has that name. Both broken literals passed
 * that check. The claim only has teeth when the handler is then asked for the glyph, which is
 * what {@code FontIcon}'s constructor does and what fails at runtime.
 *
 * <p>Scanning rather than naming, so a constant added next month is covered the moment it is
 * written — the shape {@code BotCopyTest} and {@code ExamCopyTest} use over copy. No JavaFX
 * toolkit is needed: this never builds a node, only resolves a name.
 */
class IconsTest {

    /** @return every public static String constant on {@link Icons}. */
    private static List<Field> literalFields() {
        List<Field> fields = new ArrayList<>();
        for (Field field : Icons.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers())
                    && field.getType() == String.class) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static String valueOf(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("the scan actually finds the literals")
    void theScanHasTeeth() {
        assertThat(literalFields()).hasSizeGreaterThan(25);
    }

    @Test
    @DisplayName("every icon constant resolves to a real glyph in the pack ⚑")
    void everyConstantResolvesInThePack() {
        for (Field field : literalFields()) {
            String literal = valueOf(field);
            assertThat(literal)
                    .as("Icons.%s", field.getName())
                    .isNotBlank();

            IkonHandler handler = IkonResolver.getInstance().resolve(literal);
            // The whole way: the handler answers on the prefix, the glyph is the claim.
            Ikon glyph = handler.resolve(literal);

            assertThat(glyph)
                    .as("Icons.%s = \"%s\" — the pack has no such glyph, so this constant "
                            + "renders an invisible spacer and a WARN line", field.getName(), literal)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("the two literals B-38 found are the ones that now work")
    void theTwoBrokenLiteralsAreFixed() {
        // Named as well as scanned. The scan would catch a regression; these say what was
        // wrong, so the next reader does not have to dig the reason out of a report.
        assertThat(Icons.LOGOUT)
                .as("the pack has LOGIN and EXIT_TO_APP; it has never had LOGOUT")
                .isEqualTo("mdoal-exit_to_app");
        assertThat(Icons.WARNING)
                .as("the pack has WARNING; WARNING_AMBER postdates it")
                .isEqualTo("mdomz-warning");
    }

    /**
     * The four marks the compact exam chip is drawn from (2026-08-29, manual round 3, U-32).
     *
     * <p>Named as well as scanned, because what the scan proves is that each literal exists and
     * what matters here is that the four are DIFFERENT. A compact chip drops the word, so shape
     * is the only thing left distinguishing an approved version from a rejected one for anyone
     * who cannot tell the green from the red; two of these resolving to one glyph would make the
     * panel unreadable while passing every other assertion in this file.
     */
    @Test
    @DisplayName("⚑ U-32: the four exam-status glyphs are four different marks")
    void theExamStatusGlyphsAreDistinct() {
        assertThat(List.of(Icons.CHECK, Icons.CROSS, Icons.CLOCK, Icons.EDIT))
                .doesNotHaveDuplicates();
        assertThat(Icons.CROSS)
                .as("the outlined twin of CHECK; mdoal-cancel is the same mark filled in, and a "
                        + "solid disc beside an outlined tick reads as two icon sets")
                .isEqualTo("mdoal-highlight_off");
    }

    @Test
    @DisplayName("an unknown literal still renders a spacer rather than throwing")
    void anUnknownLiteralIsStillSurvivable() {
        // The guard above is about constants. The swallow in Icons.of is about data, and it
        // stays: a typo in a NavItem's config must not take the shell down with it.
        assertThat(Icons.of("mdoal-there_is_no_such_icon", Icons.SIZE_DEFAULT, "nav-icon"))
                .isNotNull();
    }
}
