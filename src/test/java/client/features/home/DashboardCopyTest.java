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
    @DisplayName("⚑ every card's kicker is stored in sentence case, never shouting")
    void kickersAreStoredQuietly() {
        // The uppercase is a rendering decision, made once by KickerText. A
        // constant stored as "LIVE NOW" would fail the house scan above, and
        // would also be un-reusable anywhere the caps are wrong.
        for (String kicker : List.of(DashboardCopy.LIVE_KICKER, DashboardCopy.GRADING_KICKER,
                DashboardCopy.NEXT_RELEASE_KICKER, DashboardCopy.LAST_CLOSED_KICKER,
                DashboardCopy.APPROVALS_KICKER, DashboardCopy.TEACHERS_KICKER,
                DashboardCopy.LATEST_GRADE_KICKER, DashboardCopy.BOT_KICKER,
                DashboardCopy.SCHOOL_EXAMS_KICKER, DashboardCopy.SCHOOL_SITTINGS_KICKER)) {
            assertThat(kicker).isNotEqualTo(kicker.toUpperCase(Locale.ROOT));
        }
    }

    @Test
    @DisplayName("every card's link line names a screen rather than saying 'open'")
    void linksNameTheirDestination() {
        for (String link : List.of(DashboardCopy.LIVE_LINK, DashboardCopy.GRADING_LINK,
                DashboardCopy.NEXT_RELEASE_LINK, DashboardCopy.LAST_CLOSED_LINK,
                DashboardCopy.APPROVALS_LINK, DashboardCopy.TEACHERS_LINK,
                DashboardCopy.LATEST_GRADE_LINK, DashboardCopy.BOT_LINK,
                DashboardCopy.SCHOOL_EXAMS_LINK, DashboardCopy.SCHOOL_SITTINGS_LINK)) {
            assertThat(link).startsWith("Open ").hasSizeGreaterThan("Open ".length());
        }
    }

    // ===================== The live card's composed lines =================

    @Test
    @DisplayName("the code line reads as the canvas wrote it")
    void theCodeLineMatchesTheCanvas() {
        assertThat(DashboardCopy.codeLine("4B7Q", "10:30"))
                .isEqualTo("Code 4B7Q · closes 10:30")
                .doesNotContain("—");
    }

    @Test
    @DisplayName("the progress caption counts submitted out of sitting")
    void theSubmittedLineCounts() {
        assertThat(DashboardCopy.submittedLine(3, 8)).isEqualTo("3 of 8 submitted");
    }

    @Test
    @DisplayName("a negative count is clamped rather than printed onto a card")
    void countsAreClamped() {
        assertThat(DashboardCopy.submittedLine(-2, -5)).isEqualTo("0 of 0 submitted");
        assertThat(DashboardCopy.passedLine(-1, -1)).isEqualTo("0 of 0 passed");
    }

    @Test
    @DisplayName("⚑ zero minutes left reads as closing, not as a number to act on")
    void theLastMinuteIsNotAZero() {
        // "0 minutes left" is a number a teacher would walk on. A sitting that
        // is closing is not a sitting with no time left.
        assertThat(DashboardCopy.timeLeftLine(0)).isEqualTo(DashboardCopy.LIVE_CLOSING);
        assertThat(DashboardCopy.timeLeftLine(-4)).isEqualTo(DashboardCopy.LIVE_CLOSING);
    }

    @Test
    @DisplayName("the time-left line agrees with itself in the singular")
    void oneMinuteIsAMinute() {
        assertThat(DashboardCopy.timeLeftLine(1)).isEqualTo("1 minute left");
        assertThat(DashboardCopy.timeLeftLine(18)).isEqualTo("18 minutes left");
    }

    @Test
    @DisplayName("the pass line counts passes out of papers marked")
    void thePassedLineCounts() {
        assertThat(DashboardCopy.passedLine(12, 18)).isEqualTo("12 of 18 passed");
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
