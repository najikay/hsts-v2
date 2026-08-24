package client.features.home;

/**
 * Every word on the four dashboards (Presentation tier, UI wave 1 — F-10;
 * extended in UI wave 2).
 *
 * <p>One catalogue rather than strings inline in four views, for the reason every
 * other {@code *Copy} class in this package exists: the copy tests scan a class,
 * and a sentence written straight into a view is a sentence no rule ever reads.
 *
 * <h2>What an empty card says</h2>
 *
 * <p>Each card carries two hints, and the difference between them is the whole
 * point. The <b>hint</b> explains the number when there is one. The <b>empty</b>
 * line replaces it when the number is zero, and it names what happens next rather
 * than restating the absence: "nothing to grade" tells a teacher what she already
 * sees, "sittings appear here once students have handed in" tells her why. PRD
 * §4.1 calls a bare "no data" the mystery state, and a dashboard of four of them
 * is how a first-run account looks broken instead of new.
 *
 * <h2>Kickers are stored in sentence case</h2>
 *
 * <p>Every {@code *_KICKER} below reads "Live now", not "LIVE NOW". The uppercase
 * is a rendering decision made once by
 * {@link client.ui.components.logic.KickerText}, which is also where the tracking
 * happens, because JavaFX CSS has neither property. Storing them shouting would
 * put a string in this file that the house copy scan is right to reject, and
 * would make the same words un-reusable anywhere the caps are wrong.
 */
public final class DashboardCopy {

    // ---------------------------------------------------------------- teacher

    /** The teacher's first card: a sitting running right now. */
    public static final String LIVE_KICKER = "Live now";
    public static final String LIVE_TITLE = "Sittings in progress";
    public static final String LIVE_HINT = "Students are sitting an exam of yours right now.";
    public static final String LIVE_EMPTY = "Release an exam and its sitting appears here.";
    public static final String LIVE_LINK = "Open releases";

    /**
     * How the live card labels its progress bar.
     *
     * <p>Stored capitalised and lowercased by {@link #submittedLine(int, int)}
     * where the sentence needs it, for the same reason the kickers are stored in
     * sentence case: this file holds words, and where they sit in a line is the
     * caller's decision, not a second spelling of the same word.
     */
    public static final String LIVE_SUBMITTED = "Submitted";

    /** Prefix on the live card's code line. */
    public static final String LIVE_CODE = "Code";

    /** How the live card names the moment it shuts. */
    public static final String LIVE_CLOSES = "Closes";

    /** What the live card says when the closing time has passed but the state has not caught up. */
    public static final String LIVE_CLOSING = "Closing now";

    /** Singular of the live card's time-left line. */
    public static final String LIVE_MINUTE_LEFT = "Minute left";

    /** Plural of the live card's time-left line. */
    public static final String LIVE_MINUTES_LEFT = "Minutes left";

    /** Shown on the live card when more students are sitting than the card lists. */
    public static final String LIVE_MORE = "More students are in the monitor.";

    public static final String SITTINGS_TITLE = "Today and next";
    public static final String SITTINGS_HINT = "Sittings open now or scheduled ahead.";
    public static final String SITTINGS_EMPTY = "Release an exam and its sitting appears here.";

    /** The teacher's third card: what is scheduled but has not opened. */
    public static final String NEXT_RELEASE_KICKER = "Next release";
    public static final String NEXT_RELEASE_TITLE = "Scheduled ahead";
    public static final String NEXT_RELEASE_HINT = "Sittings you have scheduled and not yet opened.";
    public static final String NEXT_RELEASE_EMPTY = "Schedule a release and it waits here until it opens.";
    public static final String NEXT_RELEASE_LINK = "Open releases";

    public static final String GRADING_KICKER = "Awaiting grading";
    public static final String GRADING_TITLE = "Awaiting grading";
    public static final String GRADING_HINT = "Sittings with papers still to mark.";
    public static final String GRADING_EMPTY = "Papers arrive here once a sitting closes.";
    public static final String GRADING_LINK = "Open grading";

    /** The teacher's fourth card: the sitting that closed most recently. */
    public static final String LAST_CLOSED_KICKER = "Last closed sitting";
    public static final String LAST_CLOSED_TITLE = "Class average";
    public static final String LAST_CLOSED_HINT = "The mean of the last sitting you closed.";
    public static final String LAST_CLOSED_EMPTY = "Averages appear once a sitting has been marked.";
    public static final String LAST_CLOSED_LINK = "Open results";

    /** How the last-closed card labels its pass count. */
    public static final String LAST_CLOSED_PASSED = "Passed";

    /** What the last-closed card says while its marking is unfinished. */
    public static final String LAST_CLOSED_UNMARKED = "Marking is not finished yet.";

    public static final String RECENT_RESULTS_TITLE = "Your exams";
    public static final String RECENT_RESULTS_HINT = "Exams you wrote that have been sat.";
    public static final String RECENT_RESULTS_EMPTY = "Results appear once an exam has been sat.";

    // ------------------------------------------------------------ coordinator

    public static final String APPROVALS_KICKER = "Waiting for you";
    public static final String APPROVALS_TITLE = "Waiting for you";
    public static final String APPROVALS_HINT = "Exams submitted for your approval.";
    public static final String APPROVALS_EMPTY = "Nothing is waiting for a decision.";
    public static final String APPROVALS_LINK = "Open approvals";

    public static final String TEACHERS_KICKER = "Teachers submitting";
    public static final String TEACHERS_TITLE = "Teachers submitting";
    public static final String TEACHERS_HINT = "Authors with an exam in your queue.";
    public static final String TEACHERS_EMPTY = "Names appear here when an exam is submitted.";
    public static final String TEACHERS_LINK = "Open approvals";

    // ---------------------------------------------------------------- student

    public static final String LATEST_GRADE_KICKER = "Latest grade";
    public static final String LATEST_GRADE_TITLE = "Latest grade";
    public static final String LATEST_GRADE_HINT = "Your most recently published mark.";
    public static final String LATEST_GRADE_EMPTY = "Grades appear here once a teacher publishes them.";
    public static final String LATEST_GRADE_LINK = "Open my grades";

    public static final String BOT_KICKER = "Study bot";
    public static final String BOT_TITLE = "Study bot";
    public static final String BOT_HINT = "Ask about a topic and get an answer from your course material.";
    public static final String BOT_LINK = "Open the study bot";

    /** The bot card's big line. It is a door, so it says what opening it does. */
    public static final String BOT_VALUE = "Ask";

    // -------------------------------------------------------------- principal

    public static final String SCHOOL_EXAMS_KICKER = "Exams in the school";
    public static final String SCHOOL_EXAMS_TITLE = "Exams in the school";
    public static final String SCHOOL_EXAMS_HINT = "Every exam on file, across all subjects.";
    public static final String SCHOOL_EXAMS_EMPTY = "The catalogue is empty.";
    public static final String SCHOOL_EXAMS_LINK = "Open data";

    public static final String SCHOOL_SITTINGS_KICKER = "Sittings marked";
    public static final String SCHOOL_SITTINGS_TITLE = "Sittings marked";
    public static final String SCHOOL_SITTINGS_HINT = "Closed sittings with final statistics.";
    public static final String SCHOOL_SITTINGS_EMPTY = "Sittings appear once their marking is approved.";
    public static final String SCHOOL_SITTINGS_LINK = "Open reports";

    // ------------------------------------------------------- summary sentence

    /**
     * The summary while the reads are still in flight.
     *
     * <p>Deliberately not a sentence full of zeros that is about to be replaced:
     * a visitor reads the header first, and the first thing they read should not
     * be wrong for a second and a half.
     */
    public static final String SUMMARY_LOADING = "Checking what is happening today.";

    /** The summary when every read failed. The prose equivalent of "not available". */
    public static final String SUMMARY_UNAVAILABLE =
            "Today's summary could not be loaded. The cards below say which part is missing.";

    /** The teacher's quiet day. A calm answer, not a list of noughts. */
    public static final String SUMMARY_TEACHER_QUIET =
            "Nothing is live and nothing is waiting to be marked.";

    /** The coordinator's quiet day. */
    public static final String SUMMARY_COORDINATOR_QUIET =
            "No exams are waiting for your approval.";

    /** The student's quiet day. */
    public static final String SUMMARY_STUDENT_QUIET =
            "No grades have been published to you yet.";

    /** The principal's empty school. */
    public static final String SUMMARY_PRINCIPAL_QUIET =
            "There is nothing on file to summarise yet.";

    // ---------------------------------------------------------------- shared

    /** Shown in place of a number while the answer is still in flight. */
    public static final String LOADING = "Loading";

    /** Shown in place of a number when the read failed. Never a zero. */
    public static final String UNAVAILABLE = "Not available";

    /** The chip on a card whose read failed. */
    public static final String CHIP_OFFLINE = "Offline";

    /** The chip on the live card, next to a pulsing dot. */
    public static final String CHIP_LIVE = "Live";

    /** The chip on a card that is holding something for the reader to act on. */
    public static final String CHIP_TO_DO = "To do";

    /** The chip on a card whose news is good and needs no action. */
    public static final String CHIP_DONE = "All clear";

    /**
     * What a failed card says under its value.
     *
     * <p>A card that cannot reach the server shows this rather than a zero.
     * Zero is a fact about the school; this is a fact about the connection, and
     * a dashboard that renders the second as the first tells a coordinator her
     * queue is empty when it is merely unreachable.
     */
    public static final String LOAD_FAILED = "Could not reach the server. It will fill in on the "
            + "next visit.";

    // ------------------------------------------------- composed lines

    /**
     * The live card's identity line.
     *
     * @param code     the four-character execution code a teacher reads out
     * @param closesAt the local closing time, already formatted
     * @return for example {@code "Code 4B7Q · closes 10:30"}
     */
    public static String codeLine(String code, String closesAt) {
        return LIVE_CODE + " " + code + " · " + lower(LIVE_CLOSES) + " " + closesAt;
    }

    /**
     * The live card's progress caption.
     *
     * @param submitted how many have handed in
     * @param sitting   how many started the paper
     * @return for example {@code "3 of 8 submitted"}
     */
    public static String submittedLine(int submitted, int sitting) {
        return Math.max(submitted, 0) + " " + submittedSuffix(sitting);
    }

    /**
     * The part of the progress caption after the count.
     *
     * <p>Split out because the count itself is a {@code NumberRoll} — it is one
     * of the two numbers the motion spec has changing while a reader is looking
     * at it — and a rolling label cannot also hold the words around it. The full
     * sentence is still assembled by {@link #submittedLine(int, int)} and is
     * what the caption reports as its accessible text, so a screen reader hears
     * one phrase rather than a number and then a fragment.
     *
     * @param sitting how many started the paper
     * @return for example {@code "of 8 submitted"}
     */
    public static String submittedSuffix(int sitting) {
        return "of " + Math.max(sitting, 0) + " " + lower(LIVE_SUBMITTED);
    }

    /**
     * The live card's time-left line.
     *
     * @param minutes whole minutes remaining; anything at or below zero reads as
     *                {@link #LIVE_CLOSING}, because "0 minutes left" is a number
     *                a teacher would act on and a sitting that is closing is not
     *                a sitting with no time left
     * @return for example {@code "18 minutes left"}
     */
    public static String timeLeftLine(long minutes) {
        if (minutes <= 0) {
            return LIVE_CLOSING;
        }
        return minutes + " " + lower(minutes == 1 ? LIVE_MINUTE_LEFT : LIVE_MINUTES_LEFT);
    }

    /**
     * The last-closed card's pass line.
     *
     * @param passed how many reached the pass mark
     * @param sat    how many were marked
     * @return for example {@code "12 of 18 passed"}
     */
    public static String passedLine(int passed, int sat) {
        return Math.max(passed, 0) + " of " + Math.max(sat, 0) + " " + lower(LAST_CLOSED_PASSED);
    }

    private static String lower(String word) {
        return word.toLowerCase(java.util.Locale.ENGLISH);
    }

    private DashboardCopy() {
    }
}
