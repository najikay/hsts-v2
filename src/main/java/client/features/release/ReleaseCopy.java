package client.features.release;

import common.dto.release.ReleaseRow;
import common.protocol.ErrorCode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Every sentence the Release Manager says (Presentation tier, E9.5/E9.6 — PRD §4.1).
 *
 * <p>In one class for the reason {@code ExamCopy} and {@code NotificationCatalog} are: one
 * test then checks the whole screen against the copy rules — <b>no em dashes anywhere</b>,
 * sentence case, and every message says what to do next.
 *
 * <h2>Server sentences are used, not restated</h2>
 *
 * <p>The refusals this screen can provoke are decided by the server and arrive with their
 * own text; {@link #serverMessage} prefers what the server said and falls back only when
 * there is nothing usable, which happens when the round trip failed rather than the request.
 * The window rules are the one exception and they are not restated either: they live on
 * {@code common.dto.release.ReleaseWindow}, on the wire, so the inline hint the teacher sees
 * as she types and the error that would come back are the same string.
 *
 * <p>What lives here is what only the client knows: the labels, the two confirmations, and
 * the arithmetic that turns instants into the words a teacher reads.
 */
public final class ReleaseCopy {

    private ReleaseCopy() {
    }

    // ===================== The screen ====================================

    /** Page heading. */
    public static final String TITLE = "Releases";

    /** Explanation under it. */
    public static final String SUBTITLE =
            "Take an approved exam out of the drawer, then read its code out to the room.";

    /** The button that opens the create dialog. */
    public static final String CREATE_BUTTON = "Release an exam";

    /** Empty state, for a teacher who has released nothing yet. */
    public static final String EMPTY_TITLE = "You have not released anything yet";

    /** And its explanation. */
    public static final String EMPTY_BODY =
            "Release an approved exam to give it a code, a window and a place on this list.";

    /** Shown when the round trip itself failed rather than the request being refused. */
    public static final String OFFLINE =
            "Could not reach the server. Check your connection and try again.";

    // ===================== The create dialog =============================

    /** Dialog heading. */
    public static final String CREATE_TITLE = "Release an exam";

    /** Label of the version picker. */
    public static final String VERSION_LABEL = "Approved exam";

    /** Hint under it, which is also the F5.1 rule stated before it can be broken. */
    public static final String VERSION_HINT =
            "Only approved exams appear here. Ask your coordinator to approve one if it is missing.";

    /** Nothing picked. */
    public static final String VERSION_REQUIRED = "Pick the exam you want to release.";

    /** Label of the code field (F5.3, §4: the teacher defines the code). */
    public static final String CODE_LABEL = "Exam code";

    /** Prompt inside the empty code field, which is also what leaving it blank means. */
    public static final String CODE_PROMPT = "Leave blank to generate one";

    /** Hint under the code field. */
    public static final String CODE_HINT =
            "Four letters or digits. You read it out at the start, so students never see it here.";

    /** The dice: hands the choice back to the server. */
    public static final String CODE_GENERATE = "Generate for me";

    /** Shown under the field once the dice has been pressed. */
    public static final String CODE_GENERATED =
            "The server will pick a code that is easy to read out, and show it once you release.";

    /** Label of the opening picker. */
    public static final String OPENS_LABEL = "Opens";

    /** Label of the closing picker. */
    public static final String CLOSES_LABEL = "Closes";

    /** The confirm button. */
    public static final String CREATE_CONFIRM = "Release it";

    /** The cancel button of the dialog. */
    public static final String CREATE_DISMISS = "Cancel";

    /** Empty picker, when she has exams but none approved. */
    public static final String NONE_APPROVED =
            "None of your exams is approved yet. Ask your subject coordinator to approve one, "
                    + "then release it here.";

    /** Empty picker, when she has written nothing. */
    public static final String NONE_WRITTEN =
            "You have no exams to release yet. Build one first, then send it for approval.";

    // ===================== The code reveal ===============================

    /** Heading of the panel that shows the new code. */
    public static final String CODE_TITLE = "Read this code out";

    /** Explanation under it (S-17). */
    public static final String CODE_BODY =
            "Students never see this code in the app, so say it out loud when the exam opens.";

    /** The button that puts the code on the clipboard. */
    public static final String CODE_COPY = "Copy code";

    /** Confirmation that the copy worked. */
    public static final String CODE_COPIED = "Code copied";

    /** Dismisses the reveal. */
    public static final String CODE_DONE = "Done";

    // ===================== The two dangerous actions =====================

    /** Label of the cancel action. */
    public static final String CANCEL_ACTION = "Cancel release";

    /** Title of its confirmation. */
    public static final String CANCEL_TITLE = "Cancel this release?";

    /** Confirm button of the cancel dialog. */
    public static final String CANCEL_CONFIRM = "Cancel it";

    /** The button that keeps the release. */
    public static final String KEEP = "Keep it";

    /** Label of the close-early action. */
    public static final String CLOSE_ACTION = "Close early";

    /** Confirm button of the close-early dialog. */
    public static final String CLOSE_CONFIRM = "Close it now";

    /** The button that leaves a live exam running. */
    public static final String KEEP_RUNNING = "Keep it running";

    /** Opens the live monitor for a release people are sitting. */
    public static final String MONITOR_ACTION = "Monitor";

    // ===================== Sentences with numbers in ======================

    /**
     * What cancelling this release will do (F5.5).
     *
     * @param row the release about to be cancelled
     * @return the explanation for the confirmation dialog
     */
    public static String cancelExplanation(ReleaseRow row) {
        return "This exam will not open and its code will stop working. Nobody has sat it, so "
                + "nothing is lost. It stays on this list as cancelled, and it is left out of "
                + "reports. Release " + row.examName() + " again whenever you are ready.";
    }

    /**
     * What closing this release early will do (F5.5).
     *
     * <p>Says the consequence in the students' terms, because that is what the teacher is
     * deciding about: the sentence is F5.5's own behaviour, that closing early is time
     * expiry. Anyone still working is handed in with what she has saved, exactly as if her
     * own timer had run out.
     *
     * @param row the live release
     * @return the explanation for the confirmation dialog
     */
    public static String closeExplanation(ReleaseRow row) {
        long working = row.counts().inProgress();
        String who = working == 0
                ? "Nobody is working on it right now."
                : working + (working == 1 ? " student is" : " students are")
                        + " working on it right now.";
        return who + " Anyone still working is handed in immediately with the answers she has "
                + "saved, exactly as if her time had run out. This cannot be undone.";
    }

    /** @return the title of the close-early confirmation. */
    public static String closeTitle(ReleaseRow row) {
        return "Close " + row.examName() + " now?";
    }

    /**
     * The window, as one readable line.
     *
     * @param row  the release
     * @param zone the viewer's time zone
     * @return e.g. "20 Aug, 09:00 to 10:30"
     */
    public static String window(ReleaseRow row, ZoneId zone) {
        return localMoment(row.openAt(), zone) + " to " + localTime(row.closeAt(), zone);
    }

    /**
     * The one line under a row that says what happens next.
     *
     * <p>Different per state, because the useful fact is different: a scheduled release is
     * about how long until it opens, a live one about how long is left, a finished one about
     * how many sat it.
     *
     * @param row  the release
     * @param now  the server's clock reading, aged locally
     * @param zone the viewer's time zone
     * @return the sentence
     */
    public static String status(ReleaseRow row, Instant now, ZoneId zone) {
        return switch (row.state()) {
            case SCHEDULED -> "Opens in " + humanDuration(Duration.between(now, row.openAt()))
                    + ", at " + localTime(row.openAt(), zone);
            case LIVE -> humanDuration(Duration.between(now, row.effectiveCloseAt()))
                    + " left, " + row.counts().inProgress() + " still working";
            case CLOSED -> row.counts().started() == 0
                    ? "Nobody sat this one"
                    : row.counts().started() + " sat it, " + row.counts().finished()
                            + " handed in, " + row.counts().timedOut() + " ran out of time";
            case CANCELLED -> "Cancelled before it opened, and left out of reports";
        };
    }

    /**
     * A duration in the words a person would use.
     *
     * @param duration how long; negative reads as "moments"
     * @return e.g. "2 days", "3 hours", "25 minutes", "moments"
     */
    public static String humanDuration(Duration duration) {
        if (duration == null || duration.isNegative() || duration.toMinutes() < 1) {
            return "moments";
        }
        long days = duration.toDays();
        if (days >= 1) {
            return days + (days == 1 ? " day" : " days");
        }
        long hours = duration.toHours();
        if (hours >= 1) {
            return hours + (hours == 1 ? " hour" : " hours");
        }
        long minutes = duration.toMinutes();
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }

    /** @return "20 Aug, 09:00" in the viewer's zone. */
    public static String localMoment(Instant instant, ZoneId zone) {
        if (instant == null) {
            return "";
        }
        return DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)
                .format(LocalDateTime.ofInstant(instant, zone));
    }

    /** @return "09:00" in the viewer's zone. */
    public static String localTime(Instant instant, ZoneId zone) {
        if (instant == null) {
            return "";
        }
        return DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
                .format(LocalDateTime.ofInstant(instant, zone));
    }

    /**
     * Prefers the server's own sentence and falls back only when there is none.
     *
     * <p>Two copies of the same refusal in two tiers is exactly how one of them ends up
     * wrong, so this class keeps none of them.
     *
     * @param code     the error code, if any
     * @param message  what the server said
     * @param fallback what to say when it said nothing usable
     * @return the sentence to show
     */
    public static String serverMessage(ErrorCode code, String message, String fallback) {
        if (message != null && !message.isBlank()) {
            return message;
        }
        // An error with a code but no text is a server that refused without explaining, which
        // is a bug there rather than something to invent a sentence for here. The fallback
        // says what the reader can do, which is all this tier honestly knows.
        return fallback;
    }
}
