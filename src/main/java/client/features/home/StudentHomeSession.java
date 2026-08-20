package client.features.home;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The exam-code entry on the student dashboard (Presentation tier, E5.6 → E10.9).
 *
 * <p>The 4-character alphanumeric execution code of C-1/F5.3, validated locally so the
 * button is honest before anything is sent. It is a shortcut, not the flow: submitting a
 * well-formed code hands it to the take-exam screen, which owns the two-step entry, the
 * four distinct refusals and the identity check that starts the clock (S-18).
 *
 * <p>The rule here matches the server's exactly (case-insensitive on entry, upper-cased on
 * the way out), so the only thing this card can be wrong about is a code the server would
 * also reject.
 *
 * <p><b>It answers a decision, not a message.</b> Before E10 landed, {@link #submit()}
 * returned the sentence "not built yet"; now it says whether the code is good enough to
 * travel with, and the screen navigates. The distinction matters because a dashboard that
 * knew what the take-exam screen says next would be a second place for that copy to live.
 */
public final class StudentHomeSession {

    /** Execution codes are exactly this many characters (C-1). */
    public static final int CODE_LENGTH = 4;

    /** Codes are alphanumeric; entry is case-insensitive (C-1). */
    public static final Pattern CODE_PATTERN = Pattern.compile("[A-Za-z0-9]{" + CODE_LENGTH + "}");

    /** Inline message for anything that is not a well-formed code. */
    public static final String INVALID_CODE = "Codes are 4 letters or digits.";

    /**
     * The navigation parameter the take-exam screen reads a pre-filled code from.
     *
     * <p>A hint, never a shortcut past anything: the screen still calls {@code EXAM_JOIN}
     * and still asks for an ID, so arriving with a code saves four keystrokes and skips no
     * rule (S-18).
     */
    public static final String CODE_PARAM = "code";

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
     * Decides what pressing Enter does.
     *
     * <p>Never silent: a valid code navigates and an invalid one shows the inline rule, so
     * the button always produces feedback (NFR-21). What it never does is guess why the
     * server might refuse the code, because the server is the tier that knows.
     *
     * @return {@code true} when the take-exam screen should be opened with this code
     */
    public boolean submit() {
        return canSubmit();
    }

    /** Clears the field (after a submit, or on revisiting the dashboard). */
    public void clear() {
        code = "";
        touched = false;
        onChange.run();
    }
}
