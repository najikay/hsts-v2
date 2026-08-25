package client.features.exambuild;

import common.dto.approval.ApprovalState;
import common.dto.authoring.ExamListRow;
import common.dto.authoring.ExamVersionRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExamListCopy} — every sentence the exam list shows, checked without a window.
 *
 * <p>The point of a copy class is that its wording is testable, so the failures below are the
 * ones a screenshot review would have to catch by eye: "1 questions", a state label that has
 * drifted from the wire enum, a summary describing a version other than the one passed in, and
 * the house rule on em dashes.
 */
class ExamListCopyTest {

    private static final Instant WHEN = Instant.parse("2026-08-07T06:00:00Z");

    private static ExamVersionRow version(int no, ApprovalState state, String reason,
                                          int questions, int minutes) {
        return new ExamVersionRow(9000L + no, no, state, reason, questions, minutes, WHEN, 1);
    }

    private static ExamListRow exam(String courseName, ExamVersionRow... versions) {
        return new ExamListRow(900L, "110101", "11", courseName, "Algebra midterm",
                versions.length == 0 ? 0 : versions[0].versionNo(), List.of(versions));
    }

    // ===================== The house rule =================================

    /**
     * PRD §4.1, and the one rule in this file that a reviewer cannot check by reading.
     *
     * <p>Every public constant and every string these helpers return goes on screen, so the ban
     * covers both. Reflection over the constants rather than a hand-kept list: a list is a rule
     * somebody has to remember to extend, and the next constant added is exactly the one that
     * would be missed.
     */
    @Nested
    @DisplayName("PRD §4.1: no em dashes in user-visible text")
    class HouseRule {

        @Test
        @DisplayName("no constant on this class contains an em dash or an en dash")
        void constantsAreClean() throws IllegalAccessException {
            List<String> offenders = new ArrayList<>();
            for (Field field : ExamListCopy.class.getDeclaredFields()) {
                if (!Modifier.isPublic(field.getModifiers())
                        || !Modifier.isStatic(field.getModifiers())
                        || field.getType() != String.class) {
                    continue;
                }
                String value = (String) field.get(null);
                if (value.indexOf('—') >= 0 || value.indexOf('–') >= 0) {
                    offenders.add(field.getName());
                }
            }

            assertThat(offenders)
                    .as("PRD §4.1 bans em dashes in user-visible text, which every public "
                            + "constant on a copy class is")
                    .isEmpty();
        }

        @Test
        @DisplayName("guard against the guard: the reflection actually sees the constants")
        void theScanIsNotVacuous() {
            long visible = List.of(ExamListCopy.class.getDeclaredFields()).stream()
                    .filter(field -> Modifier.isPublic(field.getModifiers())
                            && Modifier.isStatic(field.getModifiers())
                            && field.getType() == String.class)
                    .count();

            assertThat(visible)
                    .as("if this is zero the scan above passes by finding nothing at all")
                    .isGreaterThan(10);
        }

        @Test
        @DisplayName("no derived sentence contains an em dash either")
        void derivedTextIsClean() {
            List<String> derived = List.of(
                    ExamListCopy.questions(1),
                    ExamListCopy.minutes(90),
                    ExamListCopy.versions(3),
                    ExamListCopy.versionLabel(2),
                    ExamListCopy.createdAt(WHEN),
                    ExamListCopy.revisedNotice(4),
                    ExamListCopy.stateLabel(ApprovalState.REJECTED),
                    ExamListCopy.courseLabel(exam("אלגברה")),
                    ExamListCopy.examSummary(exam("אלגברה", version(1, ApprovalState.DRAFT, "",
                            10, 60))),
                    ExamListCopy.versionSummary(version(1, ApprovalState.DRAFT, "", 10, 60)),
                    ExamListCopy.submitSummary(exam("אלגברה", version(1, ApprovalState.DRAFT, "",
                            10, 60)), version(1, ApprovalState.DRAFT, "", 10, 60)),
                    ExamListCopy.reviseSummary(exam("אלגברה", version(1, ApprovalState.APPROVED,
                            "", 10, 60)), version(1, ApprovalState.APPROVED, "", 10, 60)));

            assertThat(derived).allSatisfy(sentence ->
                    assertThat(sentence).doesNotContain("—").doesNotContain("–"));
        }
    }

    // ===================== Pluralisation ==================================

    @Nested
    @DisplayName("counting")
    class Counting {

        @Test
        @DisplayName("one is singular, everything else is plural")
        void singulars() {
            assertThat(ExamListCopy.questions(1)).isEqualTo("1 question");
            assertThat(ExamListCopy.questions(12)).isEqualTo("12 questions");
            assertThat(ExamListCopy.questions(0)).isEqualTo("0 questions");

            assertThat(ExamListCopy.minutes(1)).isEqualTo("1 minute");
            assertThat(ExamListCopy.minutes(90)).isEqualTo("90 minutes");

            assertThat(ExamListCopy.versions(1)).isEqualTo("1 version");
            assertThat(ExamListCopy.versions(3)).isEqualTo("3 versions");
        }

        @Test
        @DisplayName("a version is named one way, everywhere")
        void versionLabel() {
            assertThat(ExamListCopy.versionLabel(2)).isEqualTo("Version 2");
        }
    }

    // ===================== State labels ===================================

    @Nested
    @DisplayName("state labels come from the wire enum")
    class States {

        @ParameterizedTest
        @EnumSource(ApprovalState.class)
        @DisplayName("every state delegates rather than spelling its own word ⚑")
        void delegates(ApprovalState state) {
            // Copying the four words into the copy class is what would let this screen and the
            // coordinator's queue disagree about what a state is called.
            assertThat(ExamListCopy.stateLabel(state)).isEqualTo(state.label());
        }

        @Test
        @DisplayName("a null state is an empty label, not the word null")
        void nullState() {
            assertThat(ExamListCopy.stateLabel(null)).isEmpty();
        }
    }

    // ===================== Summaries ======================================

    @Nested
    @DisplayName("summaries")
    class Summaries {

        @Test
        @DisplayName("the course label matches the spelling the approval queue uses")
        void courseLabel() {
            assertThat(ExamListCopy.courseLabel(exam("אלגברה"))).isEqualTo("11 · אלגברה");
        }

        @Test
        @DisplayName("a blank course name leaves the code standing alone")
        void blankCourseName() {
            assertThat(ExamListCopy.courseLabel(exam("   "))).isEqualTo("11");
        }

        @Test
        @DisplayName("an exam summary names its course and how many versions it has had")
        void examSummary() {
            ExamListRow row = exam("אלגברה",
                    version(3, ApprovalState.DRAFT, "", 12, 90),
                    version(2, ApprovalState.APPROVED, "", 12, 90));

            assertThat(ExamListCopy.examSummary(row)).isEqualTo("11 · אלגברה · 2 versions");
        }

        @Test
        @DisplayName("a version summary reads number, size, length, date")
        void versionSummary() {
            String summary = ExamListCopy.versionSummary(version(3, ApprovalState.DRAFT, "",
                    12, 90));

            assertThat(summary).startsWith("Version 3 · 12 questions · 90 minutes · ");
            assertThat(summary).contains(ExamListCopy.createdAt(WHEN));
        }

        @Test
        @DisplayName("the submit summary describes the version passed in, not the latest ⚑")
        void submitSummaryDescribesItsArgument() {
            ExamListRow row = exam("אלגברה",
                    version(3, ApprovalState.DRAFT, "", 12, 90),
                    version(1, ApprovalState.APPROVED, "", 4, 30));
            ExamVersionRow older = row.versions().get(1);

            String summary = ExamListCopy.submitSummary(row, older);

            assertThat(summary).isEqualTo("Algebra midterm, version 1: 4 questions, 30 minutes.");
            assertThat(summary).doesNotContain("12 questions");
        }

        @Test
        @DisplayName("the revise summary does not predict the new version's number ⚑")
        void reviseSummaryPredictsNothing() {
            ExamListRow row = exam("אלגברה", version(3, ApprovalState.APPROVED, "", 12, 90));

            String summary = ExamListCopy.reviseSummary(row, row.versions().get(0));

            // The number is allocated server-side against uq_exam_versions_no, so any number
            // this sentence names is one concurrent revise away from being false.
            assertThat(summary).contains("Version 3");
            assertThat(summary).doesNotContain("Version 4");
            assertThat(summary).contains("new draft");
        }

        @Test
        @DisplayName("the revised notice names the number the server actually made")
        void revisedNotice() {
            assertThat(ExamListCopy.revisedNotice(9)).isEqualTo("Version 9 is ready as a draft.");
        }

        @Test
        @DisplayName("nulls are empty strings, never the word null on a screen")
        void nullsAreEmpty() {
            assertThat(ExamListCopy.courseLabel(null)).isEmpty();
            assertThat(ExamListCopy.examSummary(null)).isEmpty();
            assertThat(ExamListCopy.versionSummary(null)).isEmpty();
            assertThat(ExamListCopy.submitSummary(null, null)).isEmpty();
            assertThat(ExamListCopy.reviseSummary(null, null)).isEmpty();
            assertThat(ExamListCopy.createdAt(null)).isEmpty();
        }
    }
}
