package common.dto;

import common.dto.auth.CourseRef;
import common.dto.auth.LoginRequest;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.dto.bank.Question;
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

    @Test
    @DisplayName("the legacy bank DTO still travels unchanged")
    void questionStillRoundTrips() throws Exception {
        Question restored = roundTrip(new Question(5, "מהי בירת צרפת?", "פריז"));

        assertThat(restored.getId()).isEqualTo(5);
        assertThat(restored.getQuestionText()).isEqualTo("מהי בירת צרפת?");
        assertThat(restored.getAnswer()).isEqualTo("פריז");
    }

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
