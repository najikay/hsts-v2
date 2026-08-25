package common.dto.approval;

import common.dto.exam.ExamQuestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The approval wire model: round trips, normalisation, and the one rule the package exists
 * for (E8, over the draft contract).
 *
 * <p>These are records, so they deserialize through their canonical constructor and every
 * compact-constructor normalisation runs again on the receiving side. That is what the round
 * trips pin down, together with Hebrew survival, which every screen in this feature depends
 * on: the seeded rejection reason for exam 1 is a Hebrew sentence.
 *
 * <p>The last two tests are the structural half of F4.1. One says correctness lives in
 * exactly one type here and is fenced inside the teacher-only block; the other says the
 * preview's paper is the student's own {@code ExamQuestion} rather than a shape of this
 * package's own, because a preview built from a private copy is a preview that can drift from
 * what the student is served.
 */
class ApprovalDtoTest {

    private static final Instant SUBMITTED = Instant.parse("2026-08-20T09:00:00Z");

    private static ApprovalRow row(ApprovalState state, String reason) {
        return new ApprovalRow(31L, "101201", "מבחן אמצע — חדו\"א", "12", "חדו\"א", 2,
                "דנה כהן", SUBMITTED, 12, 60, state, reason, false, 3);
    }

    // ===================== ApprovalRow ===================================

    @Test
    @DisplayName("a row round-trips, Hebrew included")
    void rowRoundTrips() throws Exception {
        ApprovalRow restored = roundTrip(row(ApprovalState.REJECTED,
                "חמש שאלות בלבד ל-60 דקות, והציון לכל שאלה גבוה מדי."));

        assertThat(restored.examName()).isEqualTo("מבחן אמצע — חדו\"א");
        assertThat(restored.rejectedReason()).startsWith("חמש שאלות בלבד");
        assertThat(restored.lockVersion()).isEqualTo(3);
        assertThat(restored).isEqualTo(row(ApprovalState.REJECTED,
                "חמש שאלות בלבד ל-60 דקות, והציון לכל שאלה גבוה מדי."));
    }

    @Test
    @DisplayName("null text normalises to empty on both sides, so no screen renders 'null'")
    void rowNormalisesNulls() throws Exception {
        ApprovalRow sparse = new ApprovalRow(1L, null, null, null, null, 1, null,
                SUBMITTED, 0, 0, null, null, false, 0);

        assertThat(sparse.rejectedReason()).isEmpty();
        assertThat(sparse.hasRejectedReason()).isFalse();
        assertThat(sparse.state()).isEqualTo(ApprovalState.DRAFT);
        assertThat(roundTrip(sparse).examName()).isEmpty();
    }

    @Test
    @DisplayName("the two labels a screen reads are built from the parts it has")
    void rowLabels() {
        ApprovalRow full = row(ApprovalState.PENDING, "");
        assertThat(full.courseLabel()).isEqualTo("12 · חדו\"א");
        assertThat(full.examLabel()).isEqualTo("101201 · מבחן אמצע — חדו\"א (v2)");

        ApprovalRow noCourseName = new ApprovalRow(1L, "101201", "X", "12", "", 1,
                "דנה", SUBMITTED, 1, 60, ApprovalState.PENDING, "", false, 0);
        assertThat(noCourseName.courseLabel())
                .as("a missing course name falls back to the code, never to a dangling separator")
                .isEqualTo("12");
    }

    // ===================== Queue and the two empty states ================

    @Test
    @DisplayName("the two empty queues are distinguishable, which is the whole point of the flag")
    void queueEmptyStates() throws Exception {
        assertThat(ApprovalQueue.empty().isEmpty()).isTrue();
        assertThat(ApprovalQueue.empty().coordinatesAnything()).isTrue();
        assertThat(ApprovalQueue.notACoordinator().coordinatesAnything()).isFalse();

        ApprovalQueue loaded = roundTrip(new ApprovalQueue(List.of(row(ApprovalState.PENDING, "")), true));
        assertThat(loaded.size()).isEqualTo(1);
        assertThat(loaded.rows().get(0).examDisplayId()).isEqualTo("101201");

        assertThat(new ApprovalQueue(null, true).rows()).isEmpty();
    }

    // The author's own list was measured here. MyApprovals retired into common.dto.authoring's
    // ExamList on 2026-08-25 (APPROVAL ruling 1); ExamList/ExamListRow/ExamVersionRow carry the
    // separation now, and ExamListSessionTest is where the rejected-versus-not split is
    // measured against the screen that renders it.

    // ===================== The reject rule ===============================

    @Test
    @DisplayName("the reject rule is one method, and it is the rule both tiers run")
    void rejectValidation() {
        assertThat(ExamRejectRequest.validate(null)).contains(ExamRejectRequest.REASON_REQUIRED);
        assertThat(ExamRejectRequest.validate("   ")).contains(ExamRejectRequest.REASON_REQUIRED);
        assertThat(ExamRejectRequest.validate("no")).contains(ExamRejectRequest.REASON_TOO_SHORT);
        assertThat(ExamRejectRequest.validate("         "))
                .as("nine spaces are not nine characters of reason")
                .contains(ExamRejectRequest.REASON_REQUIRED);
        assertThat(ExamRejectRequest.validate("1234567890"))
                .as("exactly the minimum is enough")
                .isEmpty();
        assertThat(ExamRejectRequest.validate("Question 4 has two correct answers.")).isEmpty();
    }

    @Test
    @DisplayName("the message names the minimum, so the bar is never a guess")
    void rejectMessageNamesTheMinimum() {
        assertThat(ExamRejectRequest.REASON_TOO_SHORT)
                .contains(String.valueOf(ExamRejectRequest.MIN_REASON_LENGTH));
        assertThat(ExamRejectRequest.REASON_REQUIRED)
                .as("PRD §4.1: every error says what to do next")
                .contains("Type why");
    }

    @Test
    @DisplayName("the live counter counts down and then stops counting")
    void charactersStillNeeded() {
        assertThat(ExamRejectRequest.charactersStillNeeded(null))
                .isEqualTo(ExamRejectRequest.MIN_REASON_LENGTH);
        assertThat(ExamRejectRequest.charactersStillNeeded("abc"))
                .isEqualTo(ExamRejectRequest.MIN_REASON_LENGTH - 3);
        assertThat(ExamRejectRequest.charactersStillNeeded("  abc  "))
                .as("trimmed, like everything else about this field")
                .isEqualTo(ExamRejectRequest.MIN_REASON_LENGTH - 3);
        assertThat(ExamRejectRequest.charactersStillNeeded("a whole real sentence")).isZero();
    }

    @Test
    @DisplayName("the request trims on the wire, so what is validated is what is stored")
    void rejectRequestTrims() throws Exception {
        ExamRejectRequest request = new ExamRejectRequest(31L, "   spaced out reason   ", 2);

        assertThat(request.reason()).isEqualTo("spaced out reason");
        assertThat(request.hasUsableReason()).isTrue();
        assertThat(roundTrip(request).reason()).isEqualTo("spaced out reason");
        assertThat(new ExamRejectRequest(31L, null, 0).reason()).isEmpty();
    }

    // ===================== The preview, and its one answer key ===========

    @Test
    @DisplayName("the preview carries the student's own question type ⚑")
    void previewCarriesTheStudentType() throws Exception {
        ExamQuestion question = new ExamQuestion(901, "12001", 1, 100, "שאלה",
                "1, 6", "2, 3", "-2, -3", "0, 5", null);
        ExamPreview preview = new ExamPreview(row(ApprovalState.PENDING, ""), "ענו על הכל.",
                List.of(question),
                new TeacherOnlyBlock("notes", "דנה כהן",
                        List.of(new PreviewAnswerRow(901, 1, (byte) 2))));

        ExamPreview restored = roundTrip(preview);

        assertThat(restored.questions())
                .as("the student's type, not a copy of it")
                .containsExactly(question);
        assertThat(restored.questionCount()).isEqualTo(1);
        assertThat(restored.totalPoints()).isEqualTo(100);
        assertThat(restored.hasStudentText()).isTrue();
        assertThat(restored.teacherOnly().correctOptionOf(901)).isEqualTo(2);
        assertThat(restored.teacherOnly().correctOptionOf(999))
                .as("a question with no key entry reads as absent, never as option 0")
                .isZero();
    }

    @Test
    @DisplayName("a preview normalises its blocks so no screen has to null-check")
    void previewNormalises() {
        ExamPreview sparse = new ExamPreview(row(ApprovalState.PENDING, ""), null, null, null);

        assertThat(sparse.studentText()).isEmpty();
        assertThat(sparse.hasStudentText()).isFalse();
        assertThat(sparse.questions()).isEmpty();
        assertThat(sparse.teacherOnly().answerKey()).isEmpty();
        assertThat(sparse.teacherOnly().hasTeacherText()).isFalse();
    }

    @Test
    @DisplayName("an answer key outside 1..4 is a corrupt row, and is refused rather than rendered")
    void answerKeyIsBounded() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PreviewAnswerRow(901, 1, (byte) 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PreviewAnswerRow(901, 1, (byte) 5));
    }

    @Test
    @DisplayName("the key never prints itself into a log line")
    void answerKeyStaysOutOfLogs() {
        PreviewAnswerRow key = new PreviewAnswerRow(901, 3, (byte) 4);

        assertThat(key.toString()).contains("ordinal=3").doesNotContain("correctOption=4");
        assertThat(key.label()).isEqualTo("Q3 · option 4");
        assertThat(key.marks(4)).isTrue();
        assertThat(key.marks(1)).isFalse();
        assertThat(key).isEqualTo(new PreviewAnswerRow(901, 3, (byte) 4))
                .hasSameHashCodeAs(new PreviewAnswerRow(901, 3, (byte) 4));
    }

    @Test
    @DisplayName("correctness lives in exactly one record in this package, and it is the fenced one ⚑")
    void oneDoorForCorrectness() {
        // The structural half of F4.1. A future field named `correctAnswer` on ApprovalRow or
        // ExamPreview would put an answer key on a list a coordinator scans in public, and on
        // a payload whose other half is deliberately the student's. This is the check that
        // makes adding one a red test rather than a review someone might not do.
        // MyApprovals came off this list with its retirement (ruling 1, 2026-08-25). Its
        // successor ExamList is not unfenced-by-omission: WireDtoLeakGuardTest scans EVERY
        // record under common/dto/** for a correctness-suggesting component and licenses by
        // name, so the authoring package is covered by that scan rather than by this list.
        List<Class<? extends Record>> unfenced = List.of(ApprovalRow.class, ApprovalQueue.class,
                ExamPreview.class, ExamPreviewRequest.class,
                ExamApproveRequest.class, ExamRejectRequest.class, ApprovalDecision.class);

        for (Class<? extends Record> type : unfenced) {
            assertThat(componentNames(type))
                    .as("%s must not carry which answer is right", type.getSimpleName())
                    .noneMatch(ApprovalDtoTest::looksLikeAnAnswerKey);
        }
        assertThat(componentNames(PreviewAnswerRow.class))
                .as("and the one that does still does, so this check has teeth")
                .anyMatch(ApprovalDtoTest::looksLikeAnAnswerKey);
        assertThat(componentNames(TeacherOnlyBlock.class))
                .as("the fence itself: the key is reachable only through the staff-only block")
                .contains("answerKey");
    }

    @Test
    @DisplayName("the decision says what happened in a sentence a toast can show")
    void decisionConfirms() throws Exception {
        ApprovalDecision approved =
                new ApprovalDecision(row(ApprovalState.APPROVED, ""), false);
        ApprovalDecision rejected =
                new ApprovalDecision(row(ApprovalState.REJECTED, "Add a fourth question."), false);

        assertThat(approved.confirmation()).contains("מבחן אמצע").contains("released");
        assertThat(rejected.confirmation()).contains("sent back to").contains("דנה כהן");
        assertThat(approved.examVersionId()).isEqualTo(31L);
        assertThat(roundTrip(approved).state()).isEqualTo(ApprovalState.APPROVED);
    }

    @Test
    @DisplayName("every state has a label, and the two predicates a screen branches on")
    void stateLabels() {
        for (ApprovalState state : ApprovalState.values()) {
            assertThat(state.label()).isNotBlank();
        }
        assertThat(ApprovalState.PENDING.isPending()).isTrue();
        assertThat(ApprovalState.REJECTED.isRejected()).isTrue();
        assertThat(ApprovalState.APPROVED.isPending()).isFalse();
        assertThat(ApprovalState.APPROVED.isRejected()).isFalse();
    }

    // ===================== Helpers =======================================

    private static List<String> componentNames(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    /** The same vocabulary {@code CorrectnessNames} uses, restated where this package can see it. */
    private static boolean looksLikeAnAnswerKey(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("key") || lower.contains("correct") || lower.contains("answerkey")
                || lower.contains("solution") || lower.contains("rightanswer");
    }

    @SuppressWarnings("unchecked")
    private static <T extends Serializable> T roundTrip(T original) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) in.readObject();
        }
    }
}
