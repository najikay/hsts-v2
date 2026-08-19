package client.features.home;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The exam-code entry on the student dashboard (Presentation tier, E5.6 → E10).
 *
 * <p>The card is real UI with a real rule behind it — the 4-character
 * alphanumeric execution code of C-1/F5.3 — but the flow it opens does not exist
 * until E10. So it validates honestly and then says so: {@link #submit()} answers
 * {@link #NOT_BUILT_YET}, which the screen shows as an info toast. That is the
 * deliberate alternative to a fake "loading…" or a dead button; PRD §4.1 asks for
 * no mystery states, and "this part is not built yet" is not a mystery.
 *
 * <p>Validation matches the server's future rule exactly (case-insensitive on
 * entry, upper-cased for display), so when E10 replaces {@link #submit()} with a
 * real {@code START_ATTEMPT} request, nothing about this class's contract or its
 * tests changes.
 */
public final class StudentHomeSession {

    /** Execution codes are exactly this many characters (C-1). */
    public static final int CODE_LENGTH = 4;

    /** Codes are alphanumeric; entry is case-insensitive (C-1). */
    public static final Pattern CODE_PATTERN = Pattern.compile("[A-Za-z0-9]{" + CODE_LENGTH + "}");

    /** Inline message for anything that is not a well-formed code. */
    public static final String INVALID_CODE = "Codes are 4 letters or digits.";

    /** The honest placeholder shown on submit until E10 lands. */
    public static final String NOT_BUILT_YET = "Exam taking arrives in E10";

    private Runnable onChange = () -> { };
    private String code = "";
    private boolean touched;

    /** Registers the "re-read me and re-render" callback. */
    public StudentHomeSession onChange(Runnable listener) {
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /**
     * Records what the student typed.
     *
     * <p>Anything beyond {@link #CODE_LENGTH} characters is <b>kept</b>, not
     * silently truncated: a student who pasted five characters should see that
     * their code is wrong, not watch a character vanish.
     */
    public void setCode(String raw) {
        this.code = raw == null ? "" : raw.trim();
        this.touched = true;
        onChange.run();
    }

    /** @return what was typed, trimmed. */
    public String code() {
        return code;
    }

    /** @return the code as the server will see it — upper case (C-1). */
    public String normalizedCode() {
        return code.toUpperCase(Locale.ROOT);
    }

    /** @return {@code true} when the code is well-formed. */
    public boolean isValid() {
        return CODE_PATTERN.matcher(code).matches();
    }

    /**
     * @return the inline error, or empty while the field is still pristine or the
     *         code is valid. An empty field the student has not touched is not an
     *         error yet — it is just empty
     */
    public Optional<String> validationError() {
        if (!touched || code.isEmpty() || isValid()) {
            return Optional.empty();
        }
        return Optional.of(INVALID_CODE);
    }

    /** @return {@code true} when the Enter button should be enabled. */
    public boolean canSubmit() {
        return isValid();
    }

    /**
     * "Starts" the attempt.
     *
     * @return the message for the screen to show — {@link #NOT_BUILT_YET} on a
     *         valid code, {@link #INVALID_CODE} otherwise. Never empty: pressing a
     *         button must always produce feedback (NFR-21)
     */
    public String submit() {
        return canSubmit() ? NOT_BUILT_YET : INVALID_CODE;
    }

    /** Clears the field (after a submit, or on revisiting the dashboard). */
    public void clear() {
        code = "";
        touched = false;
        onChange.run();
    }
}
