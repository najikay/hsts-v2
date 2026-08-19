package client.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable, typed parameter bag handed to a screen on navigation
 * (Presentation tier, E4.2).
 *
 * <p>The alternative — screens reaching into a shared mutable "current
 * selection" singleton — is exactly the v1 coupling this framework removes.
 * Here a caller states what the next screen needs ({@code NavParams.of("examId",
 * 4711)}) and the screen asks for it by name <i>and type</i>; a mismatch fails
 * loudly at the boundary instead of as a {@code ClassCastException} three
 * methods deep.
 *
 * <p>Instances are immutable: {@link #with(String, Object)} returns a new bag, so
 * a params object stored on the back-stack can never be mutated underneath the
 * entry that remembers it.
 */
public final class NavParams {

    private static final NavParams EMPTY = new NavParams(Map.of());

    private final Map<String, Object> values;

    private NavParams(Map<String, Object> values) {
        this.values = values;
    }

    /** @return the shared empty parameter bag. */
    public static NavParams empty() {
        return EMPTY;
    }

    /** @return a bag holding a single entry. */
    public static NavParams of(String key, Object value) {
        return empty().with(key, value);
    }

    /** @return a bag holding two entries. */
    public static NavParams of(String key1, Object value1, String key2, Object value2) {
        return of(key1, value1).with(key2, value2);
    }

    /** @return a bag copied from {@code source} (a {@code null} source yields the empty bag). */
    public static NavParams copyOf(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return empty();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((k, v) -> copy.put(Objects.requireNonNull(k, "key"), v));
        return new NavParams(Collections.unmodifiableMap(copy));
    }

    /** @return a new bag with {@code key} added or replaced. */
    public NavParams with(String key, Object value) {
        Objects.requireNonNull(key, "key");
        Map<String, Object> copy = new LinkedHashMap<>(values);
        copy.put(key, value);
        return new NavParams(Collections.unmodifiableMap(copy));
    }

    /** @return a new bag without {@code key}; returns {@code this} when absent. */
    public NavParams without(String key) {
        if (!values.containsKey(key)) {
            return this;
        }
        Map<String, Object> copy = new LinkedHashMap<>(values);
        copy.remove(key);
        return new NavParams(Collections.unmodifiableMap(copy));
    }

    /**
     * Type-checked lookup.
     *
     * @return the value when present and assignable to {@code type}, else empty
     * @throws IllegalArgumentException when present but of an incompatible type —
     *         a wiring bug, surfaced at the navigation boundary
     */
    public <T> Optional<T> get(String key, Class<T> type) {
        Objects.requireNonNull(type, "type");
        Object raw = values.get(key);
        if (raw == null) {
            return Optional.empty();
        }
        if (!type.isInstance(raw)) {
            throw new IllegalArgumentException("Param '" + key + "' is a " + raw.getClass().getSimpleName()
                    + ", not a " + type.getSimpleName());
        }
        return Optional.of(type.cast(raw));
    }

    /**
     * Mandatory lookup for a parameter the target screen cannot render without.
     *
     * @throws NoSuchElementException when the key is absent
     */
    public <T> T require(String key, Class<T> type) {
        return get(key, type).orElseThrow(() ->
                new NoSuchElementException("Missing required navigation param '" + key + "'"));
    }

    /** @return the string value, or {@code fallback} when absent. */
    public String getString(String key, String fallback) {
        return get(key, String.class).orElse(fallback);
    }

    /** @return the int value, or {@code fallback} when absent. */
    public int getInt(String key, int fallback) {
        return get(key, Integer.class).orElse(fallback);
    }

    /** @return the long value, or {@code fallback} when absent. */
    public long getLong(String key, long fallback) {
        return get(key, Long.class).orElse(fallback);
    }

    /** @return the boolean value, or {@code fallback} when absent. */
    public boolean getBoolean(String key, boolean fallback) {
        return get(key, Boolean.class).orElse(fallback);
    }

    public boolean containsKey(String key) {
        return values.containsKey(key);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public int size() {
        return values.size();
    }

    public Set<String> keys() {
        return values.keySet();
    }

    /** @return an unmodifiable view of the raw values (for logging / diagnostics). */
    public Map<String, Object> asMap() {
        return values;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NavParams other && values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "NavParams" + values;
    }
}
