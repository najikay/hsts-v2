package client.ui.components.logic;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * "3 min ago" (Presentation tier, E17.4 — PRD §4.1).
 *
 * <p>Lists in this app carry relative times because that is the question a user
 * actually has: not "when was this, in clock terms" but "how fresh is it". An
 * absolute timestamp forces them to do the subtraction themselves, every row.
 *
 * <p>The server sends UTC (ADR-010); this class converts once, at the very edge,
 * using the machine's own zone. Two rules make it worth a class of its own
 * rather than a formatter inline in the panel:
 *
 * <ul>
 *   <li><b>a future timestamp reads as "just now".</b> Two machines on a LAN are
 *       rarely synchronised to the second, so a notification created "three
 *       seconds from now" is routine. "In -3 minutes" is not a thing a user
 *       should ever see.</li>
 *   <li><b>relative stops being useful at about a week.</b> "9 d ago" is worse
 *       than a date: nobody counts back nine days. Past the threshold it
 *       switches to a short date, and past a year it adds the year.</li>
 * </ul>
 *
 * <p>No em dashes and no abbreviation a reader has to decode (PRD §4.1).
 */
public final class RelativeTime {

    /** Below this, everything is simply "just now". */
    public static final Duration JUST_NOW = Duration.ofMinutes(1);

    /** Past this, a relative age stops helping and a date is shown instead. */
    public static final Duration RELATIVE_HORIZON = Duration.ofDays(7);

    /** What anything under a minute old says. */
    public static final String JUST_NOW_TEXT = "just now";

    private static final DateTimeFormatter SHORT_DATE =
            DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private static final DateTimeFormatter DATE_WITH_YEAR =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private RelativeTime() {
    }

    /**
     * Formats {@code when} relative to {@code now}, in the system time zone.
     *
     * @param when the instant to describe (UTC from the server)
     * @param now  the reference instant, injected so tests need no clock
     * @return a short phrase, never {@code null}
     */
    public static String of(Instant when, Instant now) {
        return of(when, now, ZoneId.systemDefault());
    }

    /**
     * @param zone the zone to render an absolute date in, once the age passes
     *             {@link #RELATIVE_HORIZON}
     * @see #of(Instant, Instant)
     */
    public static String of(Instant when, Instant now, ZoneId zone) {
        Objects.requireNonNull(when, "when");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(zone, "zone");

        Duration age = Duration.between(when, now);
        if (age.isNegative() || age.compareTo(JUST_NOW) < 0) {
            // Negative age = the other machine's clock is slightly ahead. Normal on a
            // LAN, and not something to expose to a user.
            return JUST_NOW_TEXT;
        }
        if (age.toHours() < 1) {
            return age.toMinutes() + " min ago";
        }
        if (age.toDays() < 1) {
            return age.toHours() + " h ago";
        }
        if (age.compareTo(RELATIVE_HORIZON) < 0) {
            return age.toDays() + (age.toDays() == 1 ? " day ago" : " days ago");
        }
        return absolute(when, now, zone);
    }

    /** @return the short date, gaining a year only when the year differs from today's. */
    private static String absolute(Instant when, Instant now, ZoneId zone) {
        var date = when.atZone(zone);
        var today = now.atZone(zone);
        DateTimeFormatter formatter = date.getYear() == today.getYear() ? SHORT_DATE : DATE_WITH_YEAR;
        return formatter.format(date);
    }
}
