package client.features.home;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The dashboards' logic: {@link HomeGreeting} and {@link StudentHomeSession}
 * (E5.6).
 *
 * <p>Both are small, and both are the kind of small that goes wrong silently —
 * a greeting that says "Good morning" at 6pm, or an exam code that the client
 * accepts and the server then rejects.
 */
class HomeLogicTest {

    @Nested
    @DisplayName("HomeGreeting")
    class Greeting {

        @ParameterizedTest
        @CsvSource({
                "00:00, Good morning",
                "11:59, Good morning",
                "12:00, Good afternoon",
                "17:59, Good afternoon",
                "18:00, Good evening",
                "23:59, Good evening"})
        @DisplayName("the boundaries are where a greeting goes wrong")
        void timeOfDayBoundaries(String time, String expected) {
            assertThat(HomeGreeting.timeOfDay(LocalTime.parse(time))).isEqualTo(expected);
        }

        @Test
        @DisplayName("greets by first name")
        void greetsByFirstName() {
            LocalDateTime morning = LocalDateTime.parse("2026-08-19T09:15:00");

            assertThat(HomeGreeting.greeting("Dana Cohen", morning)).isEqualTo("Good morning, Dana");
        }

        @Test
        @DisplayName("a missing name never becomes 'Good morning, null'")
        void missingNameFallsBack() {
            assertThat(HomeGreeting.firstName(null)).isEqualTo(HomeGreeting.FALLBACK_NAME);
            assertThat(HomeGreeting.firstName("   ")).isEqualTo(HomeGreeting.FALLBACK_NAME);
        }

        @Test
        @DisplayName("names with extra whitespace, one word, or Hebrew all work")
        void firstNameEdgeCases() {
            assertThat(HomeGreeting.firstName("  Rina   Barak ")).isEqualTo("Rina");
            assertThat(HomeGreeting.firstName("Maya")).isEqualTo("Maya");
            assertThat(HomeGreeting.firstName("דנה כהן")).isEqualTo("דנה");
        }

        @Test
        @DisplayName("the date line is English, whatever the machine's locale (X-I18N)")
        void dateLineIsEnglish() {
            assertThat(HomeGreeting.dateLine(LocalDate.parse("2026-08-19")))
                    .isEqualTo("Wednesday, 19 August 2026");
        }
    }

    @Nested
    @DisplayName("StudentHomeSession — the exam-code card")
    class ExamCode {

        private final StudentHomeSession session = new StudentHomeSession();

        @Test
        @DisplayName("starts empty, with nothing to submit and nothing to complain about")
        void startsPristine() {
            assertThat(session.code()).isEmpty();
            assertThat(session.canSubmit()).isFalse();
            assertThat(session.validationError()).isEmpty();
        }

        @ParameterizedTest
        @CsvSource({"4B7Q, true", "1234, true", "abcd, true", "12, false",
                "12345, false", "4B7-, false", "'    ', false"})
        @DisplayName("accepts exactly 4 alphanumeric characters (C-1)")
        void validation(String code, boolean valid) {
            session.setCode(code);

            assertThat(session.isValid()).isEqualTo(valid);
            assertThat(session.canSubmit()).isEqualTo(valid);
        }

        @Test
        @DisplayName("entry is case-insensitive but the code goes up as upper case")
        void normalisation() {
            session.setCode(" a1b2 ");

            assertThat(session.code()).isEqualTo("a1b2");
            assertThat(session.normalizedCode()).isEqualTo("A1B2");
        }

        @Test
        @DisplayName("an untouched or emptied field is not an error yet")
        void pristineIsNotAnError() {
            session.setCode("");

            assertThat(session.validationError()).isEmpty();
        }

        @Test
        @DisplayName("a wrong code explains itself inline")
        void wrongCodeExplains() {
            session.setCode("12");

            assertThat(session.validationError()).contains(StudentHomeSession.INVALID_CODE);
        }

        @Test
        @DisplayName("submitting a valid code says honestly that E10 will do the rest")
        void submitIsHonest() {
            session.setCode("4B7Q");

            assertThat(session.submit()).isEqualTo(StudentHomeSession.NOT_BUILT_YET);
        }

        @Test
        @DisplayName("submitting an invalid code answers the validation message, never silence")
        void submitInvalid() {
            session.setCode("nope!");

            assertThat(session.submit()).isEqualTo(StudentHomeSession.INVALID_CODE);
        }

        @Test
        @DisplayName("clear returns the card to pristine")
        void clearResets() {
            session.setCode("4B7Q");

            session.clear();

            assertThat(session.code()).isEmpty();
            assertThat(session.canSubmit()).isFalse();
            assertThat(session.validationError()).isEmpty();
        }

        @Test
        @DisplayName("every change notifies the view exactly once")
        void notifiesTheView() {
            AtomicInteger changes = new AtomicInteger();
            session.onChange(changes::incrementAndGet);

            session.setCode("4");
            session.setCode("4B");
            session.clear();

            assertThat(changes.get()).isEqualTo(3);
            assertThatNullPointerException().isThrownBy(() -> session.onChange(null));
        }

        @Test
        @DisplayName("a null value is an empty field, not a crash")
        void nullCodeIsSafe() {
            session.setCode(null);

            assertThat(session.code()).isEmpty();
            assertThat(session.normalizedCode()).isEmpty();
            assertThat(session.isValid()).isFalse();
        }
    }
}
