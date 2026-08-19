package client.ui.components;

import client.ui.components.logic.ValidationState;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.function.Function;
import java.util.Optional;

/**
 * Label + control + inline validation message (Presentation tier, E4.12).
 *
 * <p>The wrapper every form field in HSTS uses. It renders the mockup's error
 * treatment — 1.5px danger border on the control, a danger-tinted label, and a
 * message row with a leading error icon — by putting an {@code invalid} class on
 * itself; the actual colours and border widths are in {@code hsts.css}, so a
 * theme or palette switch needs nothing from this class.
 *
 * <p>All decisions live in {@link ValidationState} and
 * {@link client.ui.components.logic.FormValidator}: this class is a renderer.
 * Its one behaviour is layout stability — the message row is
 * {@code managed==visible}, so a field growing an error does not shove the rest
 * of the form down by a hidden row's height.
 */
public final class FormField extends VBox {

    private final Label labelNode = new Label();
    private final Control control;
    private final HBox messageRow = new HBox(6);
    private final Label messageLabel = new Label();
    private Label hintLabel;

    /**
     * @param labelText field label
     * @param control   the input control (TextField, ComboBox, …)
     */
    public FormField(String labelText, Control control) {
        this.control = Objects.requireNonNull(control, "control");
        getStyleClass().add("hsts-field");
        setSpacing(6);

        labelNode.setText(labelText);
        labelNode.getStyleClass().add("field-label");
        labelNode.setLabelFor(control);

        messageLabel.getStyleClass().add("field-message");
        messageLabel.setWrapText(true);
        messageRow.getStyleClass().add("field-message-row");
        messageRow.setAlignment(Pos.CENTER_LEFT);
        messageRow.getChildren().addAll(
                Icons.of(Icons.ERROR, 13, "field-message-icon"), messageLabel);
        setShown(messageRow, false);

        getChildren().addAll(labelNode, control, messageRow);
        apply(ValidationState.pristine());
    }

    /** @return a field wrapping a new {@link TextField} with the given prompt. */
    public static FormField text(String labelText, String prompt) {
        TextField input = new TextField();
        input.setPromptText(prompt);
        return new FormField(labelText, input);
    }

    /** @return the wrapped control, for binding and focus. */
    public Control control() {
        return control;
    }

    /** @return the wrapped control cast to {@link TextField}. */
    public TextField textField() {
        if (control instanceof TextField field) {
            return field;
        }
        throw new IllegalStateException("This field wraps a " + control.getClass().getSimpleName()
                + ", not a TextField");
    }

    /** @return the current text, for a text-based field. */
    public String text() {
        return textField().getText();
    }

    /** Adds a persistent helper line below the control ("Usually 5555"). */
    public FormField hint(String hintText) {
        if (hintLabel == null) {
            hintLabel = new Label();
            hintLabel.getStyleClass().add("field-hint");
            hintLabel.setWrapText(true);
            getChildren().add(indexOf(messageRow), hintLabel);
        }
        hintLabel.setText(hintText);
        setShown(hintLabel, hintText != null && !hintText.isBlank());
        return this;
    }

    /** Marks the field as required, adding the danger asterisk to its label. */
    public FormField required() {
        if (!getStyleClass().contains("required")) {
            getStyleClass().add("required");
            labelNode.setText(labelNode.getText() + " *");
        }
        return this;
    }

    /**
     * Renders a validation outcome: style class on the wrapper and on the
     * control, message row shown or hidden.
     */
    public void apply(ValidationState state) {
        Objects.requireNonNull(state, "state");
        getStyleClass().removeAll("valid", "invalid");
        control.getStyleClass().remove("invalid");

        String modifier = state.styleClass();
        if (!modifier.isEmpty()) {
            getStyleClass().add(modifier);
        }
        if (state.isInvalid()) {
            control.getStyleClass().add("invalid");
        }
        messageLabel.setText(state.message());
        setShown(messageRow, state.hasMessage());
        control.setAccessibleHelp(state.hasMessage() ? state.message() : null);
    }

    /**
     * Wires live validation: every keystroke re-runs {@code rule} and re-renders.
     *
     * @param rule returns an error message when the value is unacceptable
     */
    public FormField validateWith(Function<String, Optional<String>> rule) {
        Objects.requireNonNull(rule, "rule");
        TextField field = textField();
        field.textProperty().addListener((obs, old, value) ->
                apply(value == null || value.isBlank()
                        ? ValidationState.pristine()
                        : ValidationState.from(rule.apply(value))));
        return this;
    }

    /** Shows an error directly (a server-side rejection, not a local rule). */
    public void showError(String message) {
        apply(ValidationState.invalid(message));
    }

    /** Returns the field to its untouched appearance. */
    public void clearValidation() {
        apply(ValidationState.pristine());
    }

    private int indexOf(Node node) {
        return getChildren().indexOf(node);
    }

    /** Keeps {@code managed} in step with {@code visible} so hidden rows take no space. */
    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }
}
