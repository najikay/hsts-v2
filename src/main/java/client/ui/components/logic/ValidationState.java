package client.ui.components.logic;

import java.util.Objects;
import java.util.Optional;

/**
 * The validation outcome a {@code FormField} renders (Presentation tier, E4.12).
 *
 * <p>Three states, not two — {@link Level#PRISTINE} is the reason this is a type
 * rather than a boolean. A field the user has not touched yet must not shout
 * "required" in red the moment a form opens; it goes {@code PRISTINE} →
 * {@code INVALID}/{@code VALID} only once it has been edited or the form was
 * submitted. That distinction is exactly the sort of rule that rots when it
 * lives inside a view, so it lives here and is tested.
 *
 * @param level   which of the three states this is
 * @param message the message to show; empty for {@code PRISTINE}
 */
public record ValidationState(Level level, String message) {

    /** Whether a field should currently be showing feedback, and of what kind. */
    public enum Level {
        /** Untouched: no border tint, no message. */
        PRISTINE,
        /** Passed: optional confirmation message. */
        VALID,
        /** Failed: danger border and message with icon. */
        INVALID
    }

    private static final ValidationState PRISTINE = new ValidationState(Level.PRISTINE, "");
    private static final ValidationState VALID = new ValidationState(Level.VALID, "");

    public ValidationState {
        Objects.requireNonNull(level, "level");
        message = message == null ? "" : message;
        if (level == Level.INVALID && message.isBlank()) {
            throw new IllegalArgumentException("An invalid field must explain why");
        }
    }

    /** @return the untouched state. */
    public static ValidationState pristine() {
        return PRISTINE;
    }

    /** @return a passing state with no message. */
    public static ValidationState valid() {
        return VALID;
    }

    /** @return a passing state with a confirmation message. */
    public static ValidationState valid(String message) {
        return new ValidationState(Level.VALID, message);
    }

    /**
     * @param message why the value is not acceptable — required, and phrased for
     *                the user ("Port must be between 1 and 65535"), never for the
     *                developer ("NumberFormatException")
     */
    public static ValidationState invalid(String message) {
        return new ValidationState(Level.INVALID, message);
    }

    /**
     * Bridges a validator that returns an optional error message.
     *
     * @return {@link #invalid} carrying the message when present, else {@link #valid}
     */
    public static ValidationState from(Optional<String> error) {
        Objects.requireNonNull(error, "error");
        return error.map(ValidationState::invalid).orElseGet(ValidationState::valid);
    }

    public boolean isPristine() {
        return level == Level.PRISTINE;
    }

    public boolean isValid() {
        return level == Level.VALID;
    }

    public boolean isInvalid() {
        return level == Level.INVALID;
    }

    /** @return {@code true} when the field should render a message row at all. */
    public boolean hasMessage() {
        return !message.isEmpty();
    }

    /**
     * @return the {@code hsts.css} modifier to put on the field wrapper, or empty
     *         for {@code PRISTINE} (which is the unmodified base style)
     */
    public String styleClass() {
        return switch (level) {
            case PRISTINE -> "";
            case VALID -> "valid";
            case INVALID -> "invalid";
        };
    }

    /**
     * Blocking rule for a form's submit button: a form may be submitted only when
     * no field is invalid. {@code PRISTINE} does <b>not</b> block — required-ness
     * is enforced by the field's own validator producing {@code INVALID} on
     * submit, not by the absence of interaction.
     */
    public boolean blocksSubmit() {
        return level == Level.INVALID;
    }
}
