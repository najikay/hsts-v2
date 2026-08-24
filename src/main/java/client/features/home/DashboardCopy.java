package client.features.home;

/**
 * Every word on the four dashboards (Presentation tier, UI wave 1 — F-10).
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
 */
public final class DashboardCopy {

    // ---------------------------------------------------------------- teacher

    public static final String SITTINGS_TITLE = "Today and next";
    public static final String SITTINGS_HINT = "Sittings open now or scheduled ahead.";
    public static final String SITTINGS_EMPTY = "Release an exam and its sitting appears here.";

    public static final String GRADING_TITLE = "Awaiting grading";
    public static final String GRADING_HINT = "Sittings with papers still to mark.";
    public static final String GRADING_EMPTY = "Papers arrive here once a sitting closes.";

    public static final String RECENT_RESULTS_TITLE = "Your exams";
    public static final String RECENT_RESULTS_HINT = "Exams you wrote that have been sat.";
    public static final String RECENT_RESULTS_EMPTY = "Results appear once an exam has been sat.";

    // ------------------------------------------------------------ coordinator

    public static final String APPROVALS_TITLE = "Waiting for you";
    public static final String APPROVALS_HINT = "Exams submitted for your approval.";
    public static final String APPROVALS_EMPTY = "Nothing is waiting for a decision.";

    public static final String TEACHERS_TITLE = "Teachers submitting";
    public static final String TEACHERS_HINT = "Authors with an exam in your queue.";
    public static final String TEACHERS_EMPTY = "Names appear here when an exam is submitted.";

    // ---------------------------------------------------------------- student

    public static final String LATEST_GRADE_TITLE = "Latest grade";
    public static final String LATEST_GRADE_HINT = "Your most recently published mark.";
    public static final String LATEST_GRADE_EMPTY = "Grades appear here once a teacher publishes them.";

    public static final String BOT_TITLE = "Study bot";
    public static final String BOT_HINT = "Ask about a topic and get an answer from your course material.";

    // -------------------------------------------------------------- principal

    public static final String SCHOOL_EXAMS_TITLE = "Exams in the school";
    public static final String SCHOOL_EXAMS_HINT = "Every exam on file, across all subjects.";
    public static final String SCHOOL_EXAMS_EMPTY = "The catalogue is empty.";

    public static final String SCHOOL_SITTINGS_TITLE = "Sittings marked";
    public static final String SCHOOL_SITTINGS_HINT = "Closed sittings with final statistics.";
    public static final String SCHOOL_SITTINGS_EMPTY = "Sittings appear once their marking is approved.";

    // ---------------------------------------------------------------- shared

    /** Shown in place of a number while the answer is still in flight. */
    public static final String LOADING = "Loading";

    /** Shown in place of a number when the read failed. Never a zero. */
    public static final String UNAVAILABLE = "Not available";

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

    private DashboardCopy() {
    }
}
