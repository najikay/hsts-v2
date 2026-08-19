package client.ui.components.logic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Tracks the validation state of a whole form (Presentation tier, E4.12).
 *
 * <p>Every HSTS form has the same two behaviours and both are easy to get subtly
 * wrong, so they are implemented once here:
 * <ul>
 *   <li><b>touch semantics</b> — a field validates on edit only after it has been
 *       touched; {@link #submit()} touches everything at once, which is how
 *       "press Save on an empty form" lights up all the required fields;</li>
 *   <li><b>a single submittable flag</b> — the exam builder's "points must total
 *       100" (F3.1) and the connect screen's host/port both need the submit
 *       button bound to "no field is invalid", live.</li>
 * </ul>
 *
 * <p>FX-free: the view binds a listener to {@link #onChange} and pushes the
 * resulting {@link ValidationState}s into the fields.
 */
public final class FormValidator {

    private final Map<String, Function<String, Optional<String>>> rules = new LinkedHashMap<>();
    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, ValidationState> states = new LinkedHashMap<>();
    private final List<Runnable> changeListeners = new ArrayList<>();

    private boolean submitted;

    /**
     * Registers a field.
     *
     * @param name the field key
     * @param rule returns an error message when the value is unacceptable, empty
     *             when it is fine (matching {@code ConnectPrefs.validateHost}'s shape)
     */
    public FormValidator field(String name, Function<String, Optional<String>> rule) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(rule, "rule");
        if (rules.putIfAbsent(name, rule) != null) {
            throw new IllegalStateException("Field already registered: " + name);
        }
        values.put(name, "");
        states.put(name, ValidationState.pristine());
        return this;
    }

    /**
     * Records a new value for a field and re-evaluates it.
     *
     * <p>Before {@link #submit()}, a field only reports {@code INVALID} once it
     * has a non-empty value or has previously been invalid — typing the first
     * character of a username must not flash an error underneath it.
     */
    public void set(String name, String value) {
        requireKnown(name);
        values.put(name, value == null ? "" : value);
        states.put(name, evaluate(name));
        notifyChanged();
    }

    /** @return the current value of a field. */
    public String value(String name) {
        requireKnown(name);
        return values.get(name);
    }

    /** @return the current validation state of a field. */
    public ValidationState state(String name) {
        requireKnown(name);
        return states.get(name);
    }

    /**
     * Marks the form as submitted and validates every field, including untouched
     * ones.
     *
     * @return {@code true} when the form is valid and the caller may proceed
     */
    public boolean submit() {
        submitted = true;
        for (String name : rules.keySet()) {
            states.put(name, evaluate(name));
        }
        notifyChanged();
        return isValid();
    }

    /** @return {@code true} when no field is currently invalid. */
    public boolean isValid() {
        return states.values().stream().noneMatch(ValidationState::blocksSubmit);
    }

    /**
     * @return {@code true} when the submit button should be enabled — valid, and
     *         (before submit) every field carrying a value
     */
    public boolean canSubmit() {
        return isValid() && rules.keySet().stream().noneMatch(name -> values.get(name).isBlank());
    }

    /** @return the names of every field currently invalid, in registration order. */
    public List<String> invalidFields() {
        return states.entrySet().stream()
                .filter(e -> e.getValue().blocksSubmit())
                .map(Map.Entry::getKey)
                .toList();
    }

    /** @return the first error message, for a summary banner. */
    public Optional<String> firstError() {
        return states.values().stream()
                .filter(ValidationState::blocksSubmit)
                .map(ValidationState::message)
                .findFirst();
    }

    /** @return {@code true} once {@link #submit()} has been called. */
    public boolean isSubmitted() {
        return submitted;
    }

    /** Returns every field to pristine and forgets that submit happened. */
    public void reset() {
        submitted = false;
        for (String name : rules.keySet()) {
            values.put(name, "");
            states.put(name, ValidationState.pristine());
        }
        notifyChanged();
    }

    /** Subscribes to "some state changed"; the view re-reads what it needs. */
    public void onChange(Runnable listener) {
        changeListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** @return the registered field names, in registration order. */
    public List<String> fieldNames() {
        return List.copyOf(rules.keySet());
    }

    private ValidationState evaluate(String name) {
        String value = values.get(name);
        Optional<String> error = rules.get(name).apply(value);
        if (error.isEmpty()) {
            return ValidationState.valid();
        }
        boolean touched = !value.isBlank() || states.get(name).isInvalid();
        if (!submitted && !touched) {
            return ValidationState.pristine();
        }
        return ValidationState.invalid(error.get());
    }

    private void requireKnown(String name) {
        if (!rules.containsKey(name)) {
            throw new IllegalArgumentException("Unknown field '" + name + "'. Known: " + rules.keySet());
        }
    }

    private void notifyChanged() {
        for (Runnable listener : List.copyOf(changeListeners)) {
            listener.run();
        }
    }
}
