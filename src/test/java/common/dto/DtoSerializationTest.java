package common.dto;

import common.dto.exam.AttemptState;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginRequest;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.dto.grading.AnswerReviewRow;
import common.dto.grading.ApproveRequest;
import common.dto.grading.ApproveResult;
import common.dto.grading.CheckedForm;
import common.dto.grading.CheckedFormRequest;
import common.dto.grading.ExecutionGrades;
import common.dto.grading.ExecutionGradesRequest;
import common.dto.grading.ExecutionGradingSummary;
import common.dto.grading.GradeOverrideRequest;
import common.dto.grading.GradeReview;
import common.dto.grading.GradeReviewRequest;
import common.dto.grading.GradeState;
import common.dto.grading.GradingQueue;
import common.dto.grading.MyGrades;
import common.dto.grading.StudentGradeRow;
import common.protocol.Message;
import common.protocol.Verb;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trip tests for every wire DTO (E1.10).
 *
 * <p>These DTOs are records, and records deserialize through their canonical
 * constructor rather than field-by-field reflection — which means a compact
 * constructor (normalisation, defensive copy) runs again on the receiving side.
 * That is exactly what these tests pin down, together with Hebrew text survival
 * and the "no password in a log line" rule.
 */
class DtoSerializationTest {

    /** When the grading fixtures were approved; UTC, as every instant on the wire is. */
    private static final Instant APPROVED_AT = Instant.parse("2026-08-20T11:30:00Z");

    @Test
    @DisplayName("ErrorPayload round-trips, including Hebrew")
    void errorPayloadRoundTrips() throws Exception {
        ErrorPayload restored = roundTrip(new ErrorPayload("החשבון כבר מחובר במקום אחר"));

        assertThat(restored.message()).isEqualTo("החשבון כבר מחובר במקום אחר");
        assertThat(restored).isEqualTo(new ErrorPayload("החשבון כבר מחובר במקום אחר"));
    }

    @Test
    @DisplayName("ErrorPayload normalises a null message to empty — on both sides of the wire")
    void errorPayloadNormalisesNull() throws Exception {
        ErrorPayload payload = new ErrorPayload(null);

        assertThat(payload.message()).isEmpty();
        assertThat(roundTrip(payload).message()).isEmpty();
    }

    @Test
    @DisplayName("LoginRequest round-trips both fields")
    void loginRequestRoundTrips() throws Exception {
        LoginRequest restored = roundTrip(new LoginRequest("naji", "s3cr3t"));

        assertThat(restored.username()).isEqualTo("naji");
        assertThat(restored.password()).isEqualTo("s3cr3t");
    }

    @Test
    @DisplayName("LoginRequest never prints the password")
    void loginRequestHidesThePassword() {
        String text = new LoginRequest("naji", "s3cr3t").toString();

        assertThat(text).contains("naji").contains("***").doesNotContain("s3cr3t");
    }

    @Test
    @DisplayName("CourseRef round-trips and compares by value")
    void courseRefRoundTrips() throws Exception {
        CourseRef restored = roundTrip(new CourseRef("11", "מתמטיקה"));

        assertThat(restored).isEqualTo(new CourseRef("11", "מתמטיקה"));
        assertThat(restored.name()).isEqualTo("מתמטיקה");
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    @DisplayName("every role survives a round-trip inside a LoginResult")
    void everyRoleRoundTrips(Role role) throws Exception {
        LoginResult restored = roundTrip(
                new LoginResult(42L, "user", "Full Name", role, List.of(new CourseRef("11", "Math"))));

        assertThat(restored.role()).isEqualTo(role);
        assertThat(restored.userId()).isEqualTo(42L);
        assertThat(restored.courses()).containsExactly(new CourseRef("11", "Math"));
    }

    @Test
    @DisplayName("LoginResult round-trips a multi-course, Hebrew-named user")
    void loginResultRoundTrips() throws Exception {
        LoginResult original = new LoginResult(7L, "dana", "דנה כהן", Role.TEACHER,
                List.of(new CourseRef("11", "אלגברה"), new CourseRef("12", "גאומטריה")));

        LoginResult restored = roundTrip(original);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.displayName()).isEqualTo("דנה כהן");
        assertThat(restored.courses()).hasSize(2);
    }

    @Test
    @DisplayName("LoginResult defaults a null course list to empty")
    void loginResultDefaultsCourses() throws Exception {
        LoginResult result = new LoginResult(1L, "u", "U", Role.PRINCIPAL, null);

        assertThat(result.courses()).isEmpty();
        assertThat(roundTrip(result).courses()).isEmpty();
    }

    @Test
    @DisplayName("LoginResult copies the caller's list — later mutation cannot reach it")
    void loginResultCopiesCourses() {
        List<CourseRef> mutable = new ArrayList<>(List.of(new CourseRef("11", "Math")));

        LoginResult result = new LoginResult(1L, "u", "U", Role.STUDENT, mutable);
        mutable.add(new CourseRef("12", "Physics"));

        assertThat(result.courses()).hasSize(1);
        assertThatThrownBy(() -> result.courses().add(new CourseRef("13", "Chemistry")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("LoginResult carries the unread notification count across the wire (E17.5)")
    void loginResultCarriesTheUnreadCount() throws Exception {
        LoginResult original = new LoginResult(7L, "dana", "Dana Cohen", Role.TEACHER,
                List.of(new CourseRef("11", "Algebra 11")), 12);

        LoginResult restored = roundTrip(original);

        assertThat(restored.unreadNotifications()).isEqualTo(12);
        assertThat(restored).isEqualTo(original);
    }

    @Test
    @DisplayName("the pre-E17 five-argument shape still compiles and reports no notifications")
    void loginResultStaysBackwardCompatible() throws Exception {
        LoginResult result = new LoginResult(7L, "dana", "Dana Cohen", Role.TEACHER, List.of());

        assertThat(result.unreadNotifications())
                .as("a caller with no count to report says zero rather than guessing")
                .isZero();
        assertThat(roundTrip(result).unreadNotifications()).isZero();
    }

    @Test
    @DisplayName("a negative unread count is clamped, on both sides of the wire")
    void loginResultClampsNegativeCounts() throws Exception {
        LoginResult result = new LoginResult(7L, "dana", "Dana", Role.STUDENT, List.of(), -3);

        assertThat(result.unreadNotifications()).isZero();
        assertThat(roundTrip(result).unreadNotifications()).isZero();
    }

    @Test
    @DisplayName("withUnreadNotifications copies the result and changes only the count")
    void loginResultWithUnreadCount() {
        LoginResult original = new LoginResult(7L, "dana", "Dana Cohen", Role.TEACHER,
                List.of(new CourseRef("11", "Algebra 11")));

        LoginResult stamped = original.withUnreadNotifications(4);

        assertThat(stamped.unreadNotifications()).isEqualTo(4);
        assertThat(stamped.userId()).isEqualTo(original.userId());
        assertThat(stamped.displayName()).isEqualTo(original.displayName());
        assertThat(stamped.role()).isEqualTo(original.role());
        assertThat(stamped.courses()).isEqualTo(original.courses());
        assertThat(original.unreadNotifications())
                .as("the original is untouched; records are values")
                .isZero();
    }

    @Test
    @DisplayName("a DTO inside a Message payload survives the same round-trip")
    void dtoInsideAnEnvelope() throws Exception {
        LoginResult result = new LoginResult(3L, "sam", "Sam", Role.COORDINATOR, List.of());

        Message restored = roundTrip(Message.ok(Message.request(Verb.LOGIN, null), result));

        assertThat(restored.getPayload()).isEqualTo(result);
    }

    // ===================== Grading & results (E12/E13) ====================
    // The frozen contract (docs/contracts/GRADING_WIRE_CONTRACT.md) is a wire
    // contract, so every one of its types has to survive the wire. These pin
    // the round trip; GradingDtoTest pins the compact-constructor rules.

    @Test
    @DisplayName("GradeState survives a round-trip by name, both constants")
    void gradeStateRoundTrips() throws Exception {
        assertThat(roundTrip(GradeState.AUTO)).isEqualTo(GradeState.AUTO);
        assertThat(roundTrip(GradeState.APPROVED)).isEqualTo(GradeState.APPROVED);
    }

    @Test
    @DisplayName("StudentGradeRow round-trips every field, Hebrew and nulls included")
    void studentGradeRowRoundTrips() throws Exception {
        StudentGradeRow original = new StudentGradeRow(9L, 2001L, "מאיה לוי", 72, 80, 80,
                GradeState.APPROVED, "Question 4 was ambiguous", "Well done", APPROVED_AT);

        StudentGradeRow restored = roundTrip(original);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.studentName()).isEqualTo("מאיה לוי");
        assertThat(restored.finalScore()).isEqualTo(80);
        assertThat(restored.effectiveScore()).isEqualTo(80);
        assertThat(restored.approvedAt()).isEqualTo(APPROVED_AT);
    }

    @Test
    @DisplayName("an un-overridden, unapproved row keeps all four of its nulls")
    void studentGradeRowKeepsItsNulls() throws Exception {
        StudentGradeRow restored = roundTrip(autoRow());

        assertThat(restored.finalScore()).isNull();
        assertThat(restored.overrideReason()).isNull();
        assertThat(restored.teacherComment()).isNull();
        assertThat(restored.approvedAt()).isNull();
        assertThat(restored.state()).isEqualTo(GradeState.AUTO);
    }

    @Test
    @DisplayName("the student wire carries no override justification, before and after the wire")
    void myGradesNeverSerialisesAnOverrideReason() throws Exception {
        // The contract's rule: overrideReason is teacher and audit material. MyGrades
        // strips it, so a handler that assembled its rows from a teacher-side query
        // still cannot leak the justification onto a student's socket.
        StudentGradeRow teacherSide = new StudentGradeRow(9L, 2001L, "Maya Levi", 72, 80, 80,
                GradeState.APPROVED, "Question 4 was ambiguous", "Well done", APPROVED_AT);

        MyGrades restored = roundTrip(new MyGrades(List.of(teacherSide)));

        assertThat(restored.grades()).hasSize(1);
        StudentGradeRow row = restored.grades().get(0);
        assertThat(row.overrideReason())
                .as("MY_GRADES_GET never carries the justification")
                .isNull();
        assertThat(row.teacherComment())
                .as("the comment is what the student is meant to read")
                .isEqualTo("Well done");
        assertThat(row.effectiveScore()).isEqualTo(80);
        assertThat(row.gradeId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("AnswerReviewRow round-trips, unanswered questions included")
    void answerReviewRowRoundTrips() throws Exception {
        AnswerReviewRow answered = new AnswerReviewRow(1, "112001", "מהי בירת צרפת?",
                "פריז", "לונדון", "רומא", "מדריד", 25, (byte) 1, (byte) 1, true, 25);
        AnswerReviewRow blank = new AnswerReviewRow(2, "112002", "2 + 2 = ?",
                "3", "4", "5", "6", 25, null, (byte) 2, false, 0);

        assertThat(roundTrip(answered)).isEqualTo(answered);
        AnswerReviewRow restoredBlank = roundTrip(blank);
        assertThat(restoredBlank.chosen()).isNull();
        assertThat(restoredBlank.isUnanswered()).isTrue();
        assertThat(restoredBlank.pointsAwarded()).isZero();
    }

    @Test
    @DisplayName("the queue DTOs round-trip with their summaries intact")
    void queueDtosRoundTrip() throws Exception {
        ExecutionGradingSummary summary = new ExecutionGradingSummary(4821L, "Midterm", "11",
                "7391", APPROVED_AT, 28, 28, 3);

        GradingQueue queue = roundTrip(new GradingQueue(List.of(summary)));
        ExecutionGradesRequest request = roundTrip(new ExecutionGradesRequest(4821L));
        ExecutionGrades grades = roundTrip(new ExecutionGrades(summary, List.of(autoRow())));

        assertThat(queue.executions()).containsExactly(summary);
        assertThat(request.executionId()).isEqualTo(4821L);
        assertThat(grades.summary()).isEqualTo(summary);
        assertThat(grades.rows()).hasSize(1);
    }

    @Test
    @DisplayName("the review DTOs round-trip, answer key and all")
    void reviewDtosRoundTrip() throws Exception {
        AnswerReviewRow answer = new AnswerReviewRow(1, "112001", "q", "a", "b", "c", "d",
                25, (byte) 0, (byte) 3, false, 0);

        GradeReviewRequest request = roundTrip(new GradeReviewRequest(9L));
        GradeReview review = roundTrip(new GradeReview(autoRow(), List.of(answer)));
        GradeOverrideRequest override =
                roundTrip(new GradeOverrideRequest(9L, 80, "Question 4 was ambiguous"));

        assertThat(request.gradeId()).isEqualTo(9L);
        assertThat(review.grade()).isEqualTo(autoRow());
        assertThat(review.answers()).containsExactly(answer);
        assertThat(override.newScore()).isEqualTo(80);
        assertThat(override.justification()).isEqualTo("Question 4 was ambiguous");
        assertThat(override.teacherComment())
                .as("the v1 shape still travels, and still carries no comment")
                .isNull();
    }

    @Test
    @DisplayName("an override carries its comment across the wire, Hebrew and all (A3, S-22)")
    void overrideCommentRoundTrips() throws Exception {
        GradeOverrideRequest sent = new GradeOverrideRequest(9L, 80,
                "Question 4 was ambiguous", "  שיפור ניכר מאז המבחן הקודם.  ");

        GradeOverrideRequest received = roundTrip(sent);

        // A record deserializes through its canonical constructor, so the strip-and-collapse
        // runs again on the receiving side. That is the only code standing between a padded
        // comment and the database.
        assertThat(received.teacherComment()).isEqualTo("שיפור ניכר מאז המבחן הקודם.");
        assertThat(received.justification()).isEqualTo("Question 4 was ambiguous");
        assertThat(received).isEqualTo(sent);

        assertThat(roundTrip(new GradeOverrideRequest(9L, 80, "why", "   ")).teacherComment())
                .as("a blank comment arrives as null on the far side too")
                .isNull();
    }

    @Test
    @DisplayName("the approval DTOs round-trip, refusals included")
    void approvalDtosRoundTrip() throws Exception {
        ApproveRequest request = roundTrip(new ApproveRequest(List.of(9L, 10L, 11L)));
        ApproveResult result = roundTrip(new ApproveResult(2, 1, List.of(11L)));

        assertThat(request.gradeIds()).containsExactly(9L, 10L, 11L);
        assertThat(result.approved()).isEqualTo(2);
        assertThat(result.alreadyApproved()).isEqualTo(1);
        assertThat(result.refused()).containsExactly(11L);
        assertThat(result.isComplete()).isFalse();
    }

    @Test
    @DisplayName("the student DTOs round-trip, checked form included")
    void studentGradingDtosRoundTrip() throws Exception {
        AnswerReviewRow answer = new AnswerReviewRow(1, "112001", "q", "a", "b", "c", "d",
                25, (byte) 2, (byte) 2, true, 25);
        StudentGradeRow approved = new StudentGradeRow(9L, 2001L, "Maya Levi", 72, null, 72,
                GradeState.APPROVED, null, null, APPROVED_AT);

        CheckedFormRequest request = roundTrip(new CheckedFormRequest(9L));
        CheckedForm form = roundTrip(new CheckedForm(approved, "Midterm", "11", "Dana Cohen", AttemptState.TIMED_OUT, 75,
                List.of(answer)));
        MyGrades mine = roundTrip(new MyGrades(List.of(approved)));

        assertThat(request.gradeId()).isEqualTo(9L);
        assertThat(form.examName()).isEqualTo("Midterm");
        assertThat(form.courseCode()).isEqualTo("11");
        assertThat(form.answers()).containsExactly(answer);
        assertThat(mine.grades()).containsExactly(approved);
    }

    @Test
    @DisplayName("a grading DTO inside a Message payload survives the same round-trip")
    void gradingDtoInsideAnEnvelope() throws Exception {
        MyGrades payload = new MyGrades(List.of(autoRow()));

        Message restored = roundTrip(Message.ok(Message.request(Verb.MY_GRADES_GET, null), payload));

        assertThat(restored.getPayload()).isEqualTo(payload);
        assertThat(restored.getVerb()).isEqualTo(Verb.MY_GRADES_GET);
    }

    /** An unapproved, un-overridden grade: every optional field null. */
    private static StudentGradeRow autoRow() {
        return new StudentGradeRow(9L, 2001L, "Maya Levi", 72, null, 72,
                GradeState.AUTO, null, null, null);
    }

    // "the legacy bank DTO still travels unchanged" stood here, round-tripping the prototype's
    // Question with Hebrew text. The type retired with GET_ALL_QUESTIONS and UPDATE_QUESTION, and
    // no coverage went with it: the bank package has BankDtoTest, and Hebrew survival across the
    // wire is pinned by six other cases in this file.

    private static <T extends Serializable> T roundTrip(T original) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            @SuppressWarnings("unchecked")
            T restored = (T) in.readObject();
            return restored;
        }
    }
}
