package client.features.exam;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Turning server instants into the two phrases the exam screens use (Presentation tier,
 * E10.12/E10.13).
 *
 * <p>Every timestamp on the wire is UTC (§5) and every one a student reads is local, so
 * the conversion has to happen somewhere; putting it in one class means the "new end
 * 11:45" in the extension toast and the "handed in at 11:45" on the Submitted screen are
 * formatted by the same code and cannot disagree by an hour.
 *
 * <p>Separate from {@code RelativeTime}, which answers "how long ago" for notification
 * rows. An exam needs the opposite: an exact wall-clock time a student can compare against
 * the room's clock, and a spoken duration for a dialog. "3 minutes ago" is useless when the
 * question is when your exam ends.
 *
 * <p>The zone is injectable so the formatting is testable without depending on wherever
 * the build machine thinks it is.
 */
public final class ExamClock {

    /** Wall-clock time as a student reads it off a room clock: 24-hour, no seconds. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private ExamClock() {
    }

    /**
     * @param instant a server instant, or {@code null}
     * @return the local time of day, "11:45"; empty string for {@code null}, because a
     *         screen showing a blank is better than one showing the word "null"
     */
    public static String localTime(Instant instant) {
        return localTime(instant, ZoneId.systemDefault());
    }

    /**
     * @param instant a server instant, or {@code null}
     * @param zone    the reader's zone
     * @return the local time of day in that zone
     */
    public static String localTime(Instant instant, ZoneId zone) {
        Objects.requireNonNull(zone, "zone");
        return instant == null ? "" : TIME.format(instant.atZone(zone));
    }

    /**
     * A duration in words, for a sentence rather than a chip.
     *
     * <p>The countdown chip shows {@code 12:30} because it is being watched; a dialog says
     * "12 minutes" because it is being read. Rounded down: telling a student she has 13
     * minutes when she has 12 minutes 40 seconds is the wrong direction to be wrong in.
     *
     * @param duration how long
     * @return "12 minutes", "1 minute", "1 hour 5 minutes", or "less than a minute"
     */
    public static String words(Duration duration) {
        if (duration == null || duration.isNegative() || duration.toMinutes() < 1) {
            return "less than a minute";
        }
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        if (hours == 0) {
            return plural(minutes, "minute");
        }
        if (minutes == 0) {
            return plural(hours, "hour");
        }
        return plural(hours, "hour") + " " + plural(minutes, "minute");
    }

    private static String plural(long count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }
}
