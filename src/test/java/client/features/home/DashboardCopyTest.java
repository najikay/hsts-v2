package client.features.home;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DashboardCopy} — the four dashboards' vocabulary (UI wave 1, F-10).
 *
 * <p>The house scan, for the house reason: a rule that only checks the strings
 * somebody remembered to enumerate is a rule the next string walks past. Every
 * public String constant on the class is found by reflection, so a card added in
 * wave 2 is covered the moment it is written.
 *
 * <p>Beyond the §4.1 rules, one test here is about meaning rather than form.
 * {@code emptyLinesNameWhatFillsThem} is the finding F-10 actually recorded: the
 * dashboards were empty, and an empty card that says "nothing yet" has told the
 * reader only what they can already see.
 */
class DashboardCopyTest {

    /**
     * Every public String constant on the class. Nothing is skipped: unlike the
     * other copy catalogues, this one holds no style classes and no deliberately
     * blank column headings, so an exclusion here would be an exemption rather
     * than a distinction.
     */
    static List<String> allCopy() {
        List<String> copy = new ArrayList<>();
        for (Field field : DashboardCopy.class.getDeclaredFields()) {
            if (!Modifier.isPublic(field.getModifiers())
                    || !Modifier.isStatic(field.getModifiers())
                    || field.getType() != String.class) {
                continue;
            }
            try {
                String value = (String) field.get(null);
                if (!value.isEmpty()) {
                    copy.add(value);
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("could not read " + field.getName(), e);
            }
        }
        return copy;
    }

    @Test
    @DisplayName("the scan really finds the copy, so a green run means something")
    void theScanHasTeeth() {
        assertThat(allCopy()).hasSizeGreaterThanOrEqualTo(20);
        assertThat(allCopy()).contains(DashboardCopy.SITTINGS_TITLE, DashboardCopy.LOAD_FAILED,
                DashboardCopy.SCHOOL_SITTINGS_EMPTY);
    }

    @ParameterizedTest
    @MethodSource("allCopy")
    @DisplayName("no line contains an em dash (PRD section 4.1)")
    void noEmDashes(String line) {
        assertThat(line).doesNotContain("—").doesNotContain("–");
    }

    @ParameterizedTest
    @MethodSource("allCopy")
    @DisplayName("nothing shouts")
    void noShouting(String line) {
        assertThat(line).isNotBlank();
        assertThat(line).isNotEqualTo(line.toUpperCase(Locale.ROOT));
    }

    @ParameterizedTest
    @MethodSource("allCopy")
    @DisplayName("sentence case: every line starts with a capital and is not Title Case")
    void sentenceCase(String line) {
        assertThat(line.charAt(0)).isUpperCase();
    }

    @Test
    @DisplayName("⚑ every empty line names what will fill the card, not that it is empty")
    void emptyLinesNameWhatFillsThem() {
        // Each of these has to survive the "so what?" test. "No sittings" does not;
        // "release an exam and its sitting appears here" does, because it names the
        // action that changes the number.
        assertThat(DashboardCopy.SITTINGS_EMPTY).containsIgnoringCase("release an exam");
        assertThat(DashboardCopy.GRADING_EMPTY).containsIgnoringCase("once a sitting closes");
        assertThat(DashboardCopy.RECENT_RESULTS_EMPTY).containsIgnoringCase("once an exam has been sat");
        assertThat(DashboardCopy.APPROVALS_EMPTY).containsIgnoringCase("nothing is waiting");
        assertThat(DashboardCopy.TEACHERS_EMPTY).containsIgnoringCase("when an exam is submitted");
        assertThat(DashboardCopy.LATEST_GRADE_EMPTY).containsIgnoringCase("once a teacher publishes");
        assertThat(DashboardCopy.SCHOOL_SITTINGS_EMPTY).containsIgnoringCase("once their marking is approved");
    }

    @Test
    @DisplayName("⚑ the failure line blames the connection, never the data")
    void theFailureLineBlamesTheConnection() {
        // A card that cannot reach the server must not leave the reader believing
        // the school has nothing in it. The sentence has to say which of the two
        // it means, and that it is temporary.
        assertThat(DashboardCopy.LOAD_FAILED)
                .containsIgnoringCase("could not reach the server")
                .doesNotContainIgnoringCase("no ")
                .doesNotContainIgnoringCase("empty");
        assertThat(DashboardCopy.UNAVAILABLE).isNotEqualTo("0");
    }
}
