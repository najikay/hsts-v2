package client.ui.components;

import client.ui.components.logic.RadioGroupLogic;
import client.ui.components.logic.ValidationState;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * "Exactly one of these" as one control (Presentation tier, for E6.10 and E6.11 — F2.1, C-8).
 *
 * <p>C-8 is a constraint about a rule, not about a widget: a question has one correct answer,
 * enforced. The widget half of that is a radio group, and the reason it is a component rather
 * than four {@link RadioButton}s in a {@link VBox} at each call site is that the four
 * interesting behaviours are exactly the ones a call site forgets:
 *
 * <ul>
 *   <li><b>Keyboard.</b> Arrow keys move within the group, wrap at both ends and skip disabled
 *       options; Space confirms. JavaFX gives the Space half free and the arrow half wrong: its
 *       default directional traversal walks straight out of the group and into whatever sits
 *       below, which in the question editor is the topic field. The arithmetic is
 *       {@link RadioGroupLogic}'s and is unit tested; this class only maps keys onto it.</li>
 *   <li><b>Selection follows focus</b>, which is the platform convention for radio groups
 *       specifically: the option the keyboard is on <i>is</i> the chosen one, so a teacher
 *       arrowing to answer 3 has chosen answer 3 without a second keystroke. Space stays
 *       wired, so the muscle memory that expects to press it is not wrong either.</li>
 *   <li><b>Validation</b> renders through {@link ValidationState} and the {@code hsts-field}
 *       classes, so "you have not said which answer is correct" looks identical to every other
 *       inline error in the app. That is why the group carries {@code hsts-field} itself: the
 *       label, message row and {@code invalid} treatment are {@code FormField}'s, reused rather
 *       than reimplemented. {@code FormField} cannot simply wrap this one because it takes a
 *       {@link javafx.scene.control.Control} and a radio group is a container.</li>
 *   <li><b>RTL.</b> Hebrew answers render right-to-left, and Left then means "towards the next
 *       option". Everything else mirrors for free because the layout is boxes and alignments
 *       rather than coordinates.</li>
 * </ul>
 *
 * <p>Generic in the option id so the component is not about answers: E6.10 uses
 * {@link #indexed} for the 1..4 the wire wants, and anything else that has to pick exactly one
 * of a short list can use its own id type.
 *
 * @param <T> the option id type, echoed back on selection
 */
public final class RadioGroup<T> extends VBox {

    /**
     * One choice.
     *
     * @param id    what the caller gets back when this option is selected
     * @param label what the teacher reads
     * @param <V>   the option id type
     */
    public record Option<V>(V id, String label) {

        public Option {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
        }

        /** @return an option whose id and label are the same string. */
        public static Option<String> of(String label) {
            return new Option<>(label, label);
        }
    }

    private final List<Option<T>> options;
    private final List<RadioButton> buttons = new ArrayList<>();
    private final ToggleGroup toggles = new ToggleGroup();
    private final ReadOnlyObjectWrapper<T> selected = new ReadOnlyObjectWrapper<>(this, "selected");

    private final Label labelNode = new Label();
    private final HBox messageRow = new HBox(6);
    private final Label messageLabel = new Label();

    private Consumer<T> onSelect;
    private boolean applying;

    /**
     * @param labelText the group's label, or {@code null} for a group inside a card that
     *                  already carries one
     * @param options   the choices, in the order they should read; at least one
     */
    public RadioGroup(String labelText, List<Option<T>> options) {
        Objects.requireNonNull(options, "options");
        if (options.isEmpty()) {
            throw new IllegalArgumentException("A radio group needs at least one option");
        }
        this.options = List.copyOf(options);

        // hsts-field first: every label, hint, message and invalid rule in section 6 of
        // hsts.css is written against it, and inheriting them is the point.
        getStyleClass().addAll("hsts-field", "hsts-radio-group");
        setSpacing(6);
        setFillWidth(true);

        labelNode.setText(labelText == null ? "" : labelText);
        labelNode.getStyleClass().add("field-label");
        setShown(labelNode, labelText != null && !labelText.isBlank());

        VBox optionBox = new VBox(6);
        optionBox.getStyleClass().add("radio-options");
        optionBox.setFillWidth(true);
        for (Option<T> option : this.options) {
            RadioButton button = new RadioButton(option.label());
            button.getStyleClass().add("radio-option");
            button.setWrapText(true);
            button.setToggleGroup(toggles);
            button.setUserData(option.id());
            buttons.add(button);
            optionBox.getChildren().add(button);
        }

        messageLabel.getStyleClass().add("field-message");
        messageLabel.setWrapText(true);
        messageRow.getStyleClass().add("field-message-row");
        messageRow.setAlignment(Pos.CENTER_LEFT);
        messageRow.getChildren().addAll(
                Icons.of(Icons.ERROR, 13, "field-message-icon"), messageLabel);
        setShown(messageRow, false);

        getChildren().addAll(labelNode, optionBox, messageRow);

        toggles.selectedToggleProperty().addListener((obs, old, picked) -> {
            @SuppressWarnings("unchecked")
            T id = picked == null ? null : (T) picked.getUserData();
            selected.set(id);
            if (id != null && !applying && onSelect != null) {
                onSelect.accept(id);
            }
        });

        // A FILTER, not a handler: JavaFX's directional traversal is installed on the focused
        // RadioButton itself, so a handler on this container would run after focus had already
        // left the group. The filter sees the key on the way down and consumes it.
        addEventFilter(KeyEvent.KEY_PRESSED, this::onArrowKey);
        setAccessibleText(labelText);
    }

    /**
     * The shape F2.1 wants: four answers, ids 1..4.
     *
     * <p>Ids are one-based rather than zero-based because that is what
     * {@link common.dto.bank.QuestionDraft#correctAnswer()} and
     * {@link common.dto.bank.QuestionEdit#correctAnswer()} carry, and a component that handed
     * back a zero-based index would put the off-by-one somewhere less visible than here.
     *
     * @param labelText the group's label
     * @param labels    the option labels, in order
     * @return a group whose selection is the 1-based position of the chosen option
     */
    public static RadioGroup<Integer> indexed(String labelText, List<String> labels) {
        Objects.requireNonNull(labels, "labels");
        List<Option<Integer>> options = new ArrayList<>(labels.size());
        for (int i = 0; i < labels.size(); i++) {
            options.add(new Option<>(i + 1, labels.get(i)));
        }
        return new RadioGroup<>(labelText, options);
    }

    // ===================== Selection ======================================

    /** @return the selected option id, or {@code null} when nothing is selected. */
    public T selected() {
        return selected.get();
    }

    /** @return the selection, for binding a form's submit-enabled state to it. */
    public ReadOnlyObjectProperty<T> selectedProperty() {
        return selected.getReadOnlyProperty();
    }

    /**
     * Selects an option without firing {@link #setOnSelect}.
     *
     * <p>Silent because this is the "load the form" path: an editor filling itself in from a
     * {@code QuestionDetail} is not the teacher changing her mind, and a callback there would
     * mark a freshly opened form dirty.
     *
     * @param id the option to select; an id no option carries clears the selection
     */
    public void select(T id) {
        applying = true;
        try {
            toggles.selectToggle(null);
            for (RadioButton button : buttons) {
                if (Objects.equals(button.getUserData(), id)) {
                    button.setSelected(true);
                    break;
                }
            }
        } finally {
            applying = false;
        }
    }

    /** Returns the group to "nothing chosen". */
    public void clearSelection() {
        select(null);
    }

    /** Runs when the <i>user</i> picks an option; never fired by {@link #select}. */
    public void setOnSelect(Consumer<T> handler) {
        this.onSelect = handler;
    }

    // ===================== States =========================================

    /**
     * Enables or disables one option, leaving the rest alone.
     *
     * <p>Arrow keys skip whatever is disabled here, so a group with holes in it stays fully
     * reachable from the keyboard.
     *
     * @param id       the option to change
     * @param disabled whether it should be unusable
     */
    public void setOptionDisabled(T id, boolean disabled) {
        for (RadioButton button : buttons) {
            if (Objects.equals(button.getUserData(), id)) {
                button.setDisable(disabled);
                return;
            }
        }
    }

    /** Marks the group as required, adding the danger asterisk to its label. */
    public RadioGroup<T> required() {
        if (!getStyleClass().contains("required")) {
            getStyleClass().add("required");
            labelNode.setText(labelNode.getText() + " *");
        }
        return this;
    }

    /** Renders a validation outcome, exactly as {@code FormField} does. */
    public void apply(ValidationState state) {
        Objects.requireNonNull(state, "state");
        getStyleClass().removeAll("valid", "invalid");
        String modifier = state.styleClass();
        if (!modifier.isEmpty()) {
            getStyleClass().add(modifier);
        }
        messageLabel.setText(state.message());
        setShown(messageRow, state.hasMessage());
        setAccessibleHelp(state.hasMessage() ? state.message() : null);
    }

    /** Shows an error directly (a server-side rejection, not a local rule). */
    public void showError(String message) {
        apply(ValidationState.invalid(message));
    }

    /** Returns the group to its untouched appearance. */
    public void clearValidation() {
        apply(ValidationState.pristine());
    }

    /** @return the radio buttons, in order; unmodifiable, for tests and for extra decoration. */
    public List<RadioButton> buttons() {
        return Collections.unmodifiableList(buttons);
    }

    /** @return the options this group was built from, in order. */
    public List<Option<T>> options() {
        return options;
    }

    /**
     * Puts the keyboard on the group's own idea of where focus belongs (the selected option,
     * or the first usable one). Screens call this when a validation error sends the teacher
     * back to the group.
     */
    public void focusSelected() {
        int target = RadioGroupLogic.focusIndex(indexOfSelected(), buttons.size(), this::isUsable);
        if (target != RadioGroupLogic.NONE) {
            buttons.get(target).requestFocus();
        }
    }

    // ===================== Keyboard =======================================

    private void onArrowKey(KeyEvent event) {
        RadioGroupLogic.Arrow arrow = arrowOf(event.getCode());
        if (arrow == null) {
            return;
        }
        int from = indexOfFocused();
        if (from == RadioGroupLogic.NONE) {
            from = indexOfSelected();
        }
        boolean rightToLeft = getEffectiveNodeOrientation() == NodeOrientation.RIGHT_TO_LEFT;
        int target = RadioGroupLogic.nextIndex(from, buttons.size(),
                RadioGroupLogic.step(arrow, rightToLeft), this::isUsable);
        if (target == RadioGroupLogic.NONE) {
            return;
        }
        RadioButton button = buttons.get(target);
        button.requestFocus();
        button.setSelected(true);
        // Consumed even when the selection did not move, so a wrap at the end of the group
        // never falls through to the traversal that would leave it.
        event.consume();
    }

    private static RadioGroupLogic.Arrow arrowOf(KeyCode code) {
        return switch (code) {
            case UP -> RadioGroupLogic.Arrow.UP;
            case DOWN -> RadioGroupLogic.Arrow.DOWN;
            case LEFT -> RadioGroupLogic.Arrow.LEFT;
            case RIGHT -> RadioGroupLogic.Arrow.RIGHT;
            default -> null;
        };
    }

    private boolean isUsable(int index) {
        return !buttons.get(index).isDisabled();
    }

    private int indexOfSelected() {
        for (int i = 0; i < buttons.size(); i++) {
            if (buttons.get(i).isSelected()) {
                return i;
            }
        }
        return RadioGroupLogic.NONE;
    }

    private int indexOfFocused() {
        for (int i = 0; i < buttons.size(); i++) {
            if (buttons.get(i).isFocused()) {
                return i;
            }
        }
        return RadioGroupLogic.NONE;
    }

    /** Keeps {@code managed} in step with {@code visible} so hidden rows take no space. */
    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }
}
