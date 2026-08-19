package client.ui.components.logic;

import client.core.ConnectPrefs;
import client.ui.components.logic.ValidationState.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link ValidationState} and {@link FormValidator} — inline form validation (E4.12). */
class ValidationTest {

    @Nested
    @DisplayName("ValidationState")
    class State {

        @Test
        void pristineShowsNothing() {
            ValidationState pristine = ValidationState.pristine();

            assertThat(pristine.isPristine()).isTrue();
            assertThat(pristine.hasMessage()).isFalse();
            assertThat(pristine.styleClass()).isEmpty();
            assertThat(pristine.blocksSubmit()).isFalse();
        }

        @Test
        void validMayOrMayNotCarryAMessage() {
            assertThat(ValidationState.valid().hasMessage()).isFalse();
            assertThat(ValidationState.valid("Looks good").message()).isEqualTo("Looks good");
            assertThat(ValidationState.valid().styleClass()).isEqualTo("valid");
            assertThat(ValidationState.valid().isValid()).isTrue();
            assertThat(ValidationState.valid().blocksSubmit()).isFalse();
        }

        @Test
        void invalidAlwaysCarriesAReason() {
            ValidationState invalid = ValidationState.invalid("Port must be a number");

            assertThat(invalid.isInvalid()).isTrue();
            assertThat(invalid.hasMessage()).isTrue();
            assertThat(invalid.styleClass()).isEqualTo("invalid");
            assertThat(invalid.blocksSubmit()).isTrue();
        }

        @Test
        void anInvalidStateWithoutAReasonIsRejectedAtConstruction() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ValidationState.invalid("   "))
                    .withMessageContaining("explain");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ValidationState(Level.INVALID, null));
        }

        @Test
        void aNullMessageBecomesEmptyForTheNonBlockingLevels() {
            assertThat(new ValidationState(Level.PRISTINE, null).message()).isEmpty();
            assertThat(new ValidationState(Level.VALID, null).message()).isEmpty();
        }

        @Test
        void bridgesAnOptionalErrorFromAValidator() {
            assertThat(ValidationState.from(Optional.empty()).isValid()).isTrue();
            assertThat(ValidationState.from(Optional.of("nope")).isInvalid()).isTrue();
            assertThat(ValidationState.from(ConnectPrefs.validatePort("0")).message())
                    .isEqualTo("Port must be between 1 and 65535");
        }

        @Test
        void rejectsNulls() {
            assertThatThrownBy(() -> new ValidationState(null, ""))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> ValidationState.from(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("FormValidator")
    class Form {

        private FormValidator form;

        @BeforeEach
        void setUp() {
            form = new FormValidator()
                    .field("host", ConnectPrefs::validateHost)
                    .field("port", ConnectPrefs::validatePort);
        }

        @Test
        void everyFieldStartsPristine() {
            assertThat(form.state("host").isPristine()).isTrue();
            assertThat(form.state("port").isPristine()).isTrue();
            assertThat(form.value("host")).isEmpty();
            assertThat(form.fieldNames()).containsExactly("host", "port");
            assertThat(form.isSubmitted()).isFalse();
        }

        @Test
        void anEmptyUntouchedFormIsValidButNotSubmittable() {
            // Nothing is wrong yet — but nothing has been filled in either.
            assertThat(form.isValid()).isTrue();
            assertThat(form.canSubmit()).isFalse();
        }

        @Test
        void typingAGoodValueMakesTheFieldValid() {
            form.set("host", "localhost");

            assertThat(form.state("host").isValid()).isTrue();
        }

        @Test
        void typingABadValueShowsTheValidatorsMessage() {
            form.set("port", "abcd");

            assertThat(form.state("port").isInvalid()).isTrue();
            assertThat(form.state("port").message()).isEqualTo("Port must be a number");
        }

        @Test
        void clearingAFieldTheUserAlreadyBrokeKeepsTheErrorVisible() {
            form.set("port", "abcd");
            form.set("port", "");

            assertThat(form.state("port").isInvalid()).isTrue();
        }

        @Test
        void clearingANeverTouchedFieldStaysQuiet() {
            form.set("host", "");

            assertThat(form.state("host").isPristine()).isTrue();
        }

        @Test
        void bothFieldsFilledAndValidMakesTheFormSubmittable() {
            form.set("host", "localhost");
            form.set("port", "5555");

            assertThat(form.canSubmit()).isTrue();
            assertThat(form.isValid()).isTrue();
        }

        @Test
        void oneBadFieldBlocksSubmission() {
            form.set("host", "localhost");
            form.set("port", "0");

            assertThat(form.isValid()).isFalse();
            assertThat(form.canSubmit()).isFalse();
            assertThat(form.invalidFields()).containsExactly("port");
            assertThat(form.firstError()).contains("Port must be between 1 and 65535");
        }

        @Test
        void submitValidatesEvenUntouchedFieldsAndReportsFailure() {
            assertThat(form.submit()).isFalse();

            assertThat(form.isSubmitted()).isTrue();
            assertThat(form.state("host").isInvalid()).isTrue();
            assertThat(form.state("port").isInvalid()).isTrue();
            assertThat(form.invalidFields()).containsExactly("host", "port");
        }

        @Test
        void submitSucceedsOnACompleteForm() {
            form.set("host", "localhost");
            form.set("port", "5555");

            assertThat(form.submit()).isTrue();
            assertThat(form.invalidFields()).isEmpty();
            assertThat(form.firstError()).isEmpty();
        }

        @Test
        void afterSubmitAFieldClearedByTheUserErrorsImmediately() {
            form.set("host", "localhost");
            form.set("port", "5555");
            form.submit();

            form.set("host", "");

            assertThat(form.state("host").isInvalid()).isTrue();
        }

        @Test
        void resetReturnsEverythingToPristine() {
            form.set("host", "localhost");
            form.submit();

            form.reset();

            assertThat(form.state("host").isPristine()).isTrue();
            assertThat(form.value("host")).isEmpty();
            assertThat(form.isSubmitted()).isFalse();
        }

        @Test
        void aNullValueIsStoredAsEmpty() {
            form.set("host", null);

            assertThat(form.value("host")).isEmpty();
        }

        @Test
        void notifiesOnEveryStateChange() {
            AtomicInteger changes = new AtomicInteger();
            form.onChange(changes::incrementAndGet);

            form.set("host", "localhost");
            form.submit();
            form.reset();

            assertThat(changes).hasValue(3);
        }

        @Test
        void rejectsUnknownFields() {
            assertThatThrownBy(() -> form.set("ghost", "x"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ghost")
                    .hasMessageContaining("host");
            assertThatThrownBy(() -> form.state("ghost"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> form.value("ghost"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsDuplicateAndNullFieldDeclarations() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> form.field("host", ConnectPrefs::validateHost));
            assertThatThrownBy(() -> form.field(null, ConnectPrefs::validateHost))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> form.field("x", null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> form.onChange(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
