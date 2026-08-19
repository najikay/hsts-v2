package client.features.home;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The dashboard header's two lines (Presentation tier, E5.6).
 *
 * <p>Small, but not nothing: "Good morning, Dana" is the first thing every role
 * sees after signing in, and it has three ways to be wrong that a screen test
 * would never catch — the wrong greeting at a boundary hour, a first name
 * extracted badly from a full name, and a date rendered in the machine's locale
 * rather than the app's (the UI is English, X-I18N, while the data may be
 * Hebrew). All three live here, as pure functions over an injected time.
 */
public final class HomeGreeting {

    /** Before this hour it is "morning". */
    public static final int MORNING_ENDS = 12;

    /** Before this hour it is "afternoon"; after it, "evening". */
    public static final int AFTERNOON_ENDS = 18;

    /** Stand-in when a display name is missing — never "Good morning, null". */
    public static final String FALLBACK_NAME = "there";

    private static final DateTimeFormatter DATE_LINE =
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);

    private HomeGreeting() {
    }

    /**
     * @param displayName the user's full name from {@code LoginResult}
     * @param now         the moment to greet at (injected, so boundaries are testable)
     * @return e.g. {@code "Good afternoon, Dana"}
     */
    public static String greeting(String displayName, LocalDateTime now) {
        return timeOfDay(now.toLocalTime()) + ", " + firstName(displayName);
    }

    /** @return {@code "Good morning"} / {@code "Good afternoon"} / {@code "Good evening"}. */
    public static String timeOfDay(LocalTime time) {
        int hour = time.getHour();
        if (hour < MORNING_ENDS) {
            return "Good morning";
        }
        return hour < AFTERNOON_ENDS ? "Good afternoon" : "Good evening";
    }

    /**
     * @return the first whitespace-separated token of the name, or
     *         {@link #FALLBACK_NAME} when there is nothing usable. Works for the
     *         Hebrew names in the seed data as well: it splits on whitespace, not
     *         on script
     */
    public static String firstName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return FALLBACK_NAME;
        }
        String[] parts = displayName.trim().split("\\s+");
        return parts[0];
    }

    /** @return the sub-header date, e.g. {@code "Wednesday, 19 August 2026"}. */
    public static String dateLine(LocalDate date) {
        return DATE_LINE.format(date);
    }
}
