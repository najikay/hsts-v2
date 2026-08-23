package client.features.data;

import common.dto.bank.BankQuestionRow;
import common.dto.bank.Difficulty;
import common.dto.report.DataExamRow;
import common.dto.report.ReportRow;
import common.dto.results.ResultStatistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DataCopy} — every sentence and every figure the principal's Data screen prints (E15.2).
 *
 * <p>The house copy rules are checked by a <b>scan</b> rather than by a list, for the reason
 * {@code ReleaseCopyTest} scans: a rule that only checks the strings somebody remembered to
 * enumerate is a rule the next string walks past. The empty panels are added to the scan by
 * hand, because they are records rather than bare constants and reflection over the fields alone
 * would miss the six sentences inside them.
 */
class DataCopyTest {

    private static final Instant SPRING = Instant.parse("2026-03-10T07:00:00Z");
    private static final Instant SUMMER = Instant.parse("2026-08-07T06:00:00Z");
    private static final ZoneId UTC = ZoneId.of("UTC");

    /** SEED_CONTENT section 9.1's frozen record. */
    private static ResultStatistics seeded() {
        return new ResultStatistics(8, 72.5, 72.5, 17.5, 45, 100, 7, 0.875,
                List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2));
    }

    private static ReportRow sitting() {
        return new ReportRow(1, "4821", "מבחן אמצע: אלגברה", "11", "אלגברה", SPRING,
                SPRING.plusSeconds(7200), 8, seeded());
    }

    private static BankQuestionRow question() {
        return new BankQuestionRow("11005", "11", "אלגברה", "Solve the linear equation",
                "Equations", Difficulty.MEDIUM, 2, false, SPRING);
    }

    // ===================== The scan =======================================

    /** Every public String constant on the catalogue, found by scanning. */
    static List<String> allMessages() {
        List<String> messages = new ArrayList<>();
        for (Field field : DataCopy.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers())
                    && field.getType() == String.class) {
                try {
                    messages.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("could not read " + field.getName(), e);
                }
            }
        }
        for (DataTab tab : DataTab.values()) {
            messages.add(DataCopy.loadFailed(tab));
            messages.add(DataCopy.nothingHere(tab).title());
            messages.add(DataCopy.nothingHere(tab).hint());
        }
        messages.add(DataCopy.NO_MATCHES.title());
        messages.add(DataCopy.NO_MATCHES.hint());
        return messages;
    }

    @Test
    @DisplayName("the scan really finds the catalogue, so a green run means something")
    void theScanHasTeeth() {
        assertThat(allMessages()).hasSizeGreaterThanOrEqualTo(18);
        assertThat(allMessages()).contains(DataCopy.TITLE, DataCopy.SCOPE_HINT);
    }

    @ParameterizedTest
    @MethodSource("allMessages")
    @DisplayName("no message contains an em dash (PRD section 4.1)")
    void noEmDashes(String message) {
        assertThat(message).doesNotContain("—").doesNotContain("–");
    }

    @ParameterizedTest
    @MethodSource("allMessages")
    @DisplayName("nothing shouts")
    void noShouting(String message) {
        assertThat(message).isNotBlank();
        assertThat(message).isNotEqualTo(message.toUpperCase(Locale.ROOT));
    }

    @ParameterizedTest
    @MethodSource("allMessages")
    @DisplayName("sentence case: every sentence starts with a capital letter")
    void sentenceCase(String message) {
        assertThat(message.charAt(0)).isUpperCase();
    }

    // ===================== The empty states ==============================

    @Nested
    @DisplayName("the empty states")
    class Empties {

        @Test
        @DisplayName("every empty state says what would make it go away")
        void everyPanelExplains() {
            List<DataCopy.EmptyPanel> panels = new ArrayList<>(List.of(DataCopy.NO_MATCHES));
            for (DataTab tab : DataTab.values()) {
                panels.add(DataCopy.nothingHere(tab));
            }

            assertThat(panels).allSatisfy(panel -> {
                assertThat(panel.title()).isNotBlank();
                assertThat(panel.hint()).isNotBlank();
                assertThat(panel.hint().length())
                        .as("a hint that only restates the title is a dead end with two lines")
                        .isGreaterThan(panel.title().length());
            });
        }

        @ParameterizedTest
        @EnumSource(DataTab.class)
        @DisplayName("each tab has its own panel, and no two tabs share one")
        void onePanelPerTab(DataTab tab) {
            assertThat(DataCopy.nothingHere(tab)).isNotEqualTo(DataCopy.NO_MATCHES);
            for (DataTab other : DataTab.values()) {
                if (other != tab) {
                    assertThat(DataCopy.nothingHere(tab))
                            .as("one generic 'nothing here' for three different facts is the "
                                    + "dead end section 4.1 forbids")
                            .isNotEqualTo(DataCopy.nothingHere(other));
                }
            }
        }

        @Test
        @DisplayName("the Results panel names the event that fills it")
        void resultsPanelNamesTheEvent() {
            assertThat(DataCopy.NO_RESULTS.hint()).contains("last grade is approved");
        }

        @Test
        @DisplayName("the no-matches panel says which two controls to reach for")
        void noMatchesNamesTheFix() {
            assertThat(DataCopy.NO_MATCHES.hint())
                    .contains("text box")
                    .contains("course");
        }
    }

    // ===================== The count line ================================

    @Nested
    @DisplayName("the count line")
    class Counts {

        @Test
        @DisplayName("the whole list is one number; a narrowed list is both")
        void bothForms() {
            assertThat(DataCopy.countLine(DataTab.QUESTIONS, 40, 40)).isEqualTo("40 questions");
            assertThat(DataCopy.countLine(DataTab.QUESTIONS, 12, 40))
                    .isEqualTo("12 of 40 questions");
            assertThat(DataCopy.countLine(DataTab.RESULTS, 1, 2))
                    .as("counted in rows: the Results tab lists sittings, and '1 of 2 results' "
                            + "would be counting the figures rather than the rows")
                    .isEqualTo("1 of 2 sittings");
        }

        @Test
        @DisplayName("one row reads in the singular, per tab")
        void singulars() {
            assertThat(DataCopy.countLine(DataTab.QUESTIONS, 1, 1)).isEqualTo("1 question");
            assertThat(DataCopy.countLine(DataTab.EXAMS, 1, 1)).isEqualTo("1 exam");
            assertThat(DataCopy.countLine(DataTab.RESULTS, 1, 1))
                    .as("a row on the Results tab is a sitting; the results are the figures on it")
                    .isEqualTo("1 sitting");
        }

        @Test
        @DisplayName("an empty list prints nothing rather than a zero")
        void emptyPrintsNothing() {
            assertThat(DataCopy.countLine(DataTab.EXAMS, 0, 0)).isEmpty();
        }
    }

    // ===================== Row labels ====================================

    @Nested
    @DisplayName("row labels")
    class Rows {

        @Test
        @DisplayName("a question is named by a prefixed id, so it cannot be read as a score")
        void questionId() {
            assertThat(DataCopy.questionId(question())).isEqualTo("Q11005");
            assertThat(DataCopy.questionVersion(question())).isEqualTo("v2");
        }

        @Test
        @DisplayName("difficulty comes from the one catalogue that already names it")
        void difficulty() {
            assertThat(DataCopy.difficulty(Difficulty.EASY)).isEqualTo("Easy");
            assertThat(DataCopy.difficulty(Difficulty.MEDIUM)).isEqualTo("Medium");
            assertThat(DataCopy.difficulty(Difficulty.HARD)).isEqualTo("Hard");
        }

        @Test
        @DisplayName("an exam says how many versions it has, and says it only when it has more")
        void examVersions() {
            DataExamRow once = new DataExamRow("101201", "בוחן", "12", "חדו\"א", "רינה ברק", 1,
                    SPRING);
            DataExamRow thrice = new DataExamRow("101101", "מבחן", "11", "אלגברה", "דנה כהן", 3,
                    SUMMER);

            assertThat(DataCopy.examVersions(once)).isEqualTo("v1");
            assertThat(DataCopy.examVersions(thrice)).isEqualTo("v3 of 3");
        }

        @Test
        @DisplayName("a sitting is named exactly as the reports screen names it")
        void sittingLabel() {
            assertThat(DataCopy.sittingLabel(sitting())).isEqualTo("מבחן אמצע: אלגברה · 4821");
        }

        @Test
        @DisplayName("a course carries its code, so two similarly named ones are distinguishable")
        void course() {
            assertThat(DataCopy.course("11", "אלגברה")).isEqualTo("אלגברה (11)");
            assertThat(DataCopy.course("11", null))
                    .as("a course row with no name is still identified rather than blank")
                    .isEqualTo("11");
            assertThat(DataCopy.course("11", "  ")).isEqualTo("11");
        }

        @Test
        @DisplayName("dates are dates, and figures round the one way this application rounds")
        void datesAndFigures() {
            assertThat(DataCopy.rowDate(SUMMER, UTC)).isEqualTo("7 Aug 2026");
            assertThat(DataCopy.number(72.5)).isEqualTo("72.5");
            assertThat(DataCopy.number(17.0)).isEqualTo("17");
        }

        @Test
        @DisplayName("the pass rate reads as both halves, exactly as E14 and E15.4 print it")
        void passRate() {
            assertThat(DataCopy.passRate(seeded())).isEqualTo("7 of 8 (87.5%)");
        }
    }

    // ===================== The rules said out loud =======================

    @Nested
    @DisplayName("what the screen says about itself")
    class Claims {

        @Test
        @DisplayName("⚑ the read-only note is on screen, because T-11.3 looks for its absence")
        void readOnlyIsStated() {
            assertThat(DataCopy.READ_ONLY_NOTE)
                    .as("a screen that simply has no buttons is indistinguishable from one whose "
                            + "buttons are not built yet")
                    .contains("read only")
                    .contains("change");
        }

        @Test
        @DisplayName("the scope hint names the sittings the Results tab leaves out, once")
        void scopeIsStated() {
            assertThat(DataCopy.SCOPE_HINT).contains("closed").contains("cancelled");
        }

        @Test
        @DisplayName("each failure sentence names its own list and says what to try")
        void failuresNameTheirList() {
            assertThat(DataCopy.loadFailed(DataTab.QUESTIONS)).contains("questions")
                    .contains("try again");
            assertThat(DataCopy.loadFailed(DataTab.EXAMS)).contains("exams");
            assertThat(DataCopy.loadFailed(DataTab.RESULTS)).contains("results");
        }
    }

    // ===================== The tab enum ==================================

    @ParameterizedTest
    @EnumSource(DataTab.class)
    @DisplayName("every tab carries the three words the screen builds its sentences from")
    void everyTabIsNamed(DataTab tab) {
        assertThat(tab.segment()).isNotBlank();
        assertThat(tab.segment().charAt(0)).isUpperCase();
        assertThat(tab.listNoun()).isNotBlank().isLowerCase();
        assertThat(tab.rowNoun()).isNotBlank().isLowerCase();
        assertThat(tab.rowNounPlural()).isNotBlank().isLowerCase()
                .as("the plural is a word, not the singular with an s stuck on it")
                .isNotEqualTo(tab.rowNoun());
    }

    @Test
    @DisplayName("the screen opens on the bank, which is the first question T-11 asks")
    void defaultTab() {
        assertThat(DataTab.defaultTab()).isEqualTo(DataTab.QUESTIONS);
        assertThat(DataTab.values()).containsExactly(DataTab.QUESTIONS, DataTab.EXAMS,
                DataTab.RESULTS);
    }
}
