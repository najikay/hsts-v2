package client.ui.components.logic;

import java.time.Duration;
import java.util.Objects;

/**
 * One transient message destined for the toast stack (Presentation tier, E4.14,
 * F11.3).
 *
 * <p>Toasts are explicitly <b>not</b> notifications: notifications persist in the
 * DB and live in the bell panel (F11.1/F11.2), toasts are feedback for something
 * the user just did and vanish. Keeping them a plain record means a screen can
 * hand one to {@code Toasts.show(...)} without importing a single FX type.
 *
 * @param variant colour treatment
 * @param title   short bold line ("Saved", "Could not save")
 * @param message optional detail line; may be empty
 * @param dwell   how long it stays before auto-dismissing
 */
public record ToastSpec(Variant variant, String title, String message, Duration dwell) {

    /** Toast flavours; each maps to a rail colour and icon in {@code hsts.css}. */
    public enum Variant {
        SUCCESS("success"),
        ERROR("error"),
        INFO("info"),
        WARN("warn");

        private final String styleClass;

        Variant(String styleClass) {
            this.styleClass = styleClass;
        }

        /** @return the {@code hsts.css} modifier class. */
        public String styleClass() {
            return styleClass;
        }
    }

    /** Default dwell for success/info: long enough to read, short enough to ignore. */
    public static final Duration DEFAULT_DWELL = Duration.ofSeconds(4);

    /**
     * Errors linger. A failure the user missed is a support call, and unlike a
     * success it usually carries an instruction ("check the server is running").
     */
    public static final Duration ERROR_DWELL = Duration.ofSeconds(7);

    public ToastSpec {
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(dwell, "dwell");
        message = message == null ? "" : message;
        if (title.isBlank()) {
            throw new IllegalArgumentException("A toast needs a title");
        }
        if (dwell.isNegative() || dwell.isZero()) {
            throw new IllegalArgumentException("A toast must stay on screen: " + dwell);
        }
    }

    public static ToastSpec success(String title) {
        return success(title, "");
    }

    public static ToastSpec success(String title, String message) {
        return new ToastSpec(Variant.SUCCESS, title, message, DEFAULT_DWELL);
    }

    public static ToastSpec error(String title) {
        return error(title, "");
    }

    public static ToastSpec error(String title, String message) {
        return new ToastSpec(Variant.ERROR, title, message, ERROR_DWELL);
    }

    public static ToastSpec info(String title) {
        return info(title, "");
    }

    public static ToastSpec info(String title, String message) {
        return new ToastSpec(Variant.INFO, title, message, DEFAULT_DWELL);
    }

    public static ToastSpec warn(String title, String message) {
        return new ToastSpec(Variant.WARN, title, message, ERROR_DWELL);
    }

    /** @return this toast with a different dwell time. */
    public ToastSpec withDwell(Duration newDwell) {
        return new ToastSpec(variant, title, message, newDwell);
    }

    /** @return {@code true} when the detail line should be rendered. */
    public boolean hasMessage() {
        return !message.isEmpty();
    }
}
