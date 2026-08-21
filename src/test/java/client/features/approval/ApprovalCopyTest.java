package client.features.approval;

import common.dto.approval.ApprovalRow;
import common.dto.approval.ApprovalState;
import common.dto.approval.ExamRejectRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.features.approval.ApprovalMessages;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The copy rules, over both halves of the feature's vocabulary (E8 — PRD §4.1).
 *
 * <p>The catalogue classes exist so that "no em dashes" and "every error says what to do
 * next" can be checked by one test instead of by reading nine view classes, and this is that
 * test. It scans {@link ApprovalCopy} and {@link ApprovalMessages} by reflection rather than
 * listing their constants, so a sentence added next month is checked without anybody
 * remembering to add it here.
 *
 * <p>{@link ApprovalMessages#SUPERSEDED_REASON} gets a test of its own because it is not
 * really copy: it is stored in {@code exam_versions.rejected_reason} and read back by a
 * teacher who has to be able to tell the system apart from her coordinator's opinion.
 */
class ApprovalCopyTest {

    @Test
    @DisplayName("no user-visible sentence contains an em dash (PRD §4.1)")
    void noEmDashes() {
        for (String sentence : allSentences()) {
            assertThat(sentence)
                    .as("copy: %s", sentence)
                    .doesNotContain("—")
                    .doesNotContain("–");
        }
    }

    @Test
    @DisplayName("every refusal the server can send says what to do next")
    void everyRefusalSaysWhatToDoNext() {
        // Not a style preference: a coordinator whose decision was refused is looking at a
        // screen that will refuse it again, and a message that only names the problem leaves
        // her pressing the same button.
        List<String> refusals = List.of(
                ApprovalMessages.MALFORMED_REQUEST,
                ApprovalMessages.VERSION_UNKNOWN,
                ApprovalMessages.NOT_PENDING,
                ApprovalMessages.DECISION_RACED,
                ExamRejectRequest.REASON_REQUIRED,
                ExamRejectRequest.REASON_TOO_SHORT);

        for (String refusal : refusals) {
            assertThat(refusal).as("refusal: %s", refusal).isNotBlank();
            assertThat(hasAnInstruction(refusal))
                    .as("this refusal names no next step: %s", refusal)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("every sentence is a finished sentence")
    void sentencesAreFinished() {
        for (String sentence : allSentences()) {
            assertThat(sentence).as("copy: %s", sentence).isNotBlank();
        }
    }

    @Test
    @DisplayName("the supersede reason is recognisable, and says what to do about it")
    void supersedeReasonIsRecognisable() {
        assertThat(ApprovalMessages.SUPERSEDED_REASON)
                .as("a teacher must be able to tell the system apart from her coordinator")
                .startsWith("Superseded by a newer version.")
                .contains("withdrawn from the approval queue")
                .contains("Open the newest version");
    }

    @Test
    @DisplayName("the self-approval marker is a token a grep can find (acceptance case 4.6)")
    void selfApprovalMarkerIsGreppable() {
        assertThat(ApprovalMessages.SELF_APPROVAL_MARKER)
                .isEqualTo("SELF-APPROVAL")
                .doesNotContain(" ");
    }

    @Test
    @DisplayName("the two empty states of the queue say opposite things")
    void twoEmptyStatesReadDifferently() {
        assertThat(ApprovalCopy.QUEUE_EMPTY_TITLE)
                .isNotEqualTo(ApprovalCopy.QUEUE_NOT_COORDINATOR_TITLE);
        assertThat(ApprovalCopy.QUEUE_EMPTY_HINT)
                .as("a finished inbox is good news and should read like it")
                .contains("No exams need your approval");
        assertThat(ApprovalCopy.QUEUE_NOT_COORDINATOR_HINT)
                .as("and the other one names who to ask")
                .contains("ask the principal");
    }

    @Test
    @DisplayName("the preview banner says what the two panes are")
    void previewBannerExplainsTheSplit() {
        assertThat(ApprovalCopy.PREVIEW_BANNER)
                .contains("exactly as a student will see it")
                .contains("no student ever sees them");
    }

    @Test
    @DisplayName("the F4.3 note informs rather than warns")
    void selfApprovalNoteIsNotAWarning() {
        assertThat(ApprovalCopy.SELF_APPROVAL_NOTE)
                .contains("is allowed")
                .contains("recorded")
                .doesNotContainIgnoringCase("not allowed")
                .doesNotContainIgnoringCase("warning");
    }

    @Test
    @DisplayName("the reason hint counts down and then says who reads it")
    void reasonHint() {
        assertThat(ApprovalCopy.reasonHint(""))
                .isEqualTo(ExamRejectRequest.MIN_REASON_LENGTH + " more characters needed.");
        assertThat(ApprovalCopy.reasonHint("123456789"))
                .as("one to go reads as 'character', not 'characters'")
                .isEqualTo("1 more character needed.");
        assertThat(ApprovalCopy.reasonHint("Question 4 has two correct answers."))
                .isEqualTo("The teacher will see this reason.");
    }

    @Test
    @DisplayName("counts and durations are never printed as '1 questions'")
    void pluralsAreRight() {
        assertThat(ApprovalCopy.questions(1)).isEqualTo("1 question");
        assertThat(ApprovalCopy.questions(12)).isEqualTo("12 questions");
        assertThat(ApprovalCopy.questions(0)).isEqualTo("0 questions");
        assertThat(ApprovalCopy.minutes(1)).isEqualTo("1 minute");
        assertThat(ApprovalCopy.minutes(60)).isEqualTo("60 minutes");
    }

    @Test
    @DisplayName("a submitted-at instant renders, and a missing one renders as nothing")
    void submittedAtIsRendered() {
        assertThat(ApprovalCopy.submittedAt(Instant.parse("2026-08-20T09:00:00Z"))).isNotBlank();
        assertThat(ApprovalCopy.submittedAt(null))
                .as("an absent instant is an absent cell, never the word 'null'")
                .isEmpty();
    }

    @Test
    @DisplayName("a queue row's summary line carries the four facts a coordinator triages by")
    void queueSummary() {
        ApprovalRow row = new ApprovalRow(31L, "101201", "מבחן אמצע", "12", "חדו\"א", 1,
                "דנה כהן", Instant.parse("2026-08-20T09:00:00Z"), 12, 60,
                ApprovalState.PENDING, "", false, 0);

        assertThat(ApprovalCopy.queueSummary(row))
                .contains("12 · חדו\"א")
                .contains("דנה כהן")
                .contains("12 questions")
                .contains("60 minutes");
    }

    @Test
    @DisplayName("the chip label comes from the wire enum, so the two can never disagree")
    void stateLabelDelegates() {
        for (ApprovalState state : ApprovalState.values()) {
            assertThat(ApprovalCopy.stateLabel(state)).isEqualTo(state.label());
        }
        assertThat(ApprovalCopy.stateLabel(null)).isEmpty();
    }

    // ===================== Helpers =======================================

    /** @return every public String constant of both catalogues. */
    private static List<String> allSentences() {
        List<String> sentences = new ArrayList<>();
        collect(ApprovalCopy.class, sentences);
        collect(ApprovalMessages.class, sentences);
        collect(ExamRejectRequest.class, sentences);
        assertThat(sentences)
                .as("the scan must actually find the copy")
                .hasSizeGreaterThan(20);
        return sentences;
    }

    private static void collect(Class<?> type, List<String> into) {
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && Modifier.isPublic(field.getModifiers())
                    && field.getType() == String.class) {
                try {
                    into.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("could not read " + field, e);
                }
            }
        }
    }

    /**
     * @return whether the sentence tells the reader to do something. Deliberately a search
     *         for the verbs this feature's refusals actually use rather than for a full stop:
     *         a sentence can be well punctuated and still be a dead end
     */
    private static boolean hasAnInstruction(String sentence) {
        String lower = sentence.toLowerCase(java.util.Locale.ROOT);
        return List.of("open ", "ask ", "type ", "give ", "try ", "check ", "speak ", "wait ")
                .stream().anyMatch(lower::contains);
    }
}
