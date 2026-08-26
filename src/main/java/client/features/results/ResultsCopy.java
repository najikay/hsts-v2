package client.features.results;

import client.ui.components.logic.StatChartLogic;
import common.dto.exam.AttemptState;
import common.dto.grading.GradeState;
import common.dto.grading.StudentGradeRow;
import common.dto.results.ExecutionResultRow;
import common.dto.results.ExecutionResults;
import common.dto.results.ExecutionState;
import common.dto.results.ResultStatistics;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Every sentence and every number the teacher's results screen prints (Presentation tier,
 * E14.2 — F9.2).
 *
 * <p>FX-free and static, so the six stat cards, the pass-rate phrasing and the
 * grading-unfinished copy are unit tested rather than eyeballed, and so the FX view beside it
 * stays a renderer with no decisions in it. The {@code ExamMessages} discipline applied to a
 * screen: the copy rules (no em dashes, sentence case, an explanation rather than a dead end)
 * are checkable in one file.
 *
 * <h2>One formatter, shared with the chart</h2>
 *
 * <p>Numbers go through {@link StatChartLogic#number(double)} — the same function the
 * histogram's markers use. A stat card reading "Mean 73" above a marker reading "Mean 72.5"
 * would be two roundings of one stored number, and the reader would have no way to tell which
 * one the exam actually had.
 *
 * <h2>Nothing here computes a statistic</h2>
 *
 * <p>Every figure is read off {@link ResultStatistics}, which was read off the frozen column
 * (F8.5). This class formats; it does not average, it does not re-derive a pass count from
 * scores, and it does not divide anything the server has already divided.
 */
public final class ResultsCopy {

    /** Shown when the exams list cannot be loaded; says nothing about why (F1.1's discipline). */
    public static final String LOAD_FAILED =
            "Your results could not be loaded. Check your connection and try again.";

    /** Shown when a sitting cannot be opened. */
    public static final String EXECUTION_FAILED =
            "That sitting could not be opened. Choose it again, or pick another one.";

    /** The teacher has written no exams at all. */
    public static final String NO_EXAMS_TITLE = "No exams yet";

    /** The teacher has written no exams at all, explained. */
    public static final String NO_EXAMS_HINT =
            "Results appear here for every exam you write, including sittings run by other "
                    + "teachers.";

    /** She has exams, but this one has never been taken out of the drawer (S-2). */
    public static final String NEVER_RELEASED_TITLE = "Not run yet";

    /** She has exams, but this one has never been released, explained. */
    public static final String NEVER_RELEASED_HINT =
            "This exam has no sittings. Release it from the Releases screen and its results "
                    + "will appear here.";

    /** Nobody sat the chosen sitting. */
    public static final String NOBODY_SAT_TITLE = "Nobody sat this one";

    /** Nobody sat the chosen sitting, explained. */
    public static final String NOBODY_SAT_HINT =
            "No student entered the code for this sitting, so there is nothing to show.";

    /** Students sat it, but no paper has been marked yet. A different fact from the above. */
    public static final String NOTHING_MARKED_TITLE = "Nothing marked yet";

    /** Students sat it, but no paper has been marked yet, explained. */
    public static final String NOTHING_MARKED_HINT =
            "Papers appear here as they are marked. Start from the Grading screen.";

    /** Grading has not finished, so there are no frozen statistics yet (F8.5). */
    public static final String GRADING_UNFINISHED_TITLE = "Grading is not finished";

    /**
     * The calm version of "no statistics".
     *
     * <p>It says what is missing, why, and what will fix it, because a teacher meeting this
     * mid-marking has done nothing wrong and the screen should not read as an error.
     */
    public static final String GRADING_UNFINISHED_HINT =
            "Statistics are worked out once every paper in this sitting has been approved. "
                    + "The scores below are what has been marked so far.";

    /** The style class the print-friendly pass adds to the screen's root (E14.4). */
    public static final String PRINT_STYLE_CLASS = "results-print";

    /** Date and time as a teacher reads it off a schedule: "20 Aug 09:00". */
    private static final DateTimeFormatter WINDOW_START =
            DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH);

    /** The closing half of a window, which is the same day almost always: "11:00". */
    private static final DateTimeFormatter WINDOW_END =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    private ResultsCopy() {
        // static helper - no instances
    }

    /**
     * The panel shown where a table would be, when there is no table to show.
     *
     * <p>A record rather than two loose strings because the four situations that produce one
     * are genuinely different facts and the screen has to say which: no exams at all, an exam
     * never released, a sitting nobody sat, and a sitting nobody has marked. Collapsing them
     * into one "nothing here" is the failure PRD §4.1 calls a dead end.
     *
     * @param title the heading
     * @param hint  the explanation, which always says what would make the panel go away
     */
    public record EmptyPanel(String title, String hint) {

        public EmptyPanel {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(hint, "hint");
        }
    }

    /** She has written no exams at all. */
    public static final EmptyPanel NO_EXAMS = new EmptyPanel(NO_EXAMS_TITLE, NO_EXAMS_HINT);

    /** The exam exists and has never been taken out of the drawer (S-2). */
    public static final EmptyPanel NEVER_RELEASED =
            new EmptyPanel(NEVER_RELEASED_TITLE, NEVER_RELEASED_HINT);

    /** The sitting ran and nobody entered its code. */
    public static final EmptyPanel NOBODY_SAT = new EmptyPanel(NOBODY_SAT_TITLE, NOBODY_SAT_HINT);

    /** Students sat it; no paper has been marked. */
    public static final EmptyPanel NOTHING_MARKED =
            new EmptyPanel(NOTHING_MARKED_TITLE, NOTHING_MARKED_HINT);

    // ===================== The stat cards =================================

    /**
     * One card above the table: a label, the figure, and a line saying what the figure is.
     *
     * @param label the caption under the value
     * @param value the figure itself
     * @param hint  one short line of context, never empty
     */
    public record StatCard(String label, String value, String hint) {

        public StatCard {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(hint, "hint");
        }
    }

    /**
     * The six cards F9.2 asks for, in the order it asks for them.
     *
     * <p>Order is part of the requirement and part of the reading: average and median sit
     * together because comparing them is how a teacher spots a skewed class, σ follows because
     * it qualifies both, and the pass rate is last but one because it is the figure that gets
     * quoted. Participants closes the row as the denominator everything else was computed
     * over.
     *
     * @param stats the execution's frozen statistics
     * @return six cards, always six, so the row's layout never shifts between executions
     */
    public static List<StatCard> statCards(ResultStatistics stats) {
        Objects.requireNonNull(stats, "stats");
        List<StatCard> cards = new ArrayList<>(6);
        cards.add(new StatCard("Average", number(stats.mean()), "out of 100"));
        cards.add(new StatCard("Median", number(stats.median()), "middle score"));
        cards.add(new StatCard("Std deviation", number(stats.standardDeviation()),
                "population sigma"));
        cards.add(new StatCard("Min / max", stats.min() + " to " + stats.max(),
                "lowest to highest"));
        cards.add(new StatCard("Pass rate", passRateLabel(stats),
                "pass mark " + ResultStatistics.PASS_MARK));
        cards.add(new StatCard("Participants", Integer.toString(stats.count()),
                "results counted"));
        return List.copyOf(cards);
    }

    /**
     * The pass rate as F9.2 wants it read: "7 of 8 (87.5%)".
     *
     * <p>Both halves, because neither is enough on its own. "87.5%" hides that the class had
     * eight students, and "7 of 8" makes a reader do the division the server already did. The
     * numerator is the stored {@code passCount}, not a threshold re-applied to the rows.
     *
     * @param stats the frozen statistics
     * @return the label
     */
    public static String passRateLabel(ResultStatistics stats) {
        Objects.requireNonNull(stats, "stats");
        return stats.passCount() + " of " + stats.count()
                + " (" + number(stats.passPercent()) + "%)";
    }

    // ===================== Rows and headers ===============================

    /**
     * @param results one sitting's answer
     * @return "6 of 8 papers marked", or "All 8 papers marked" when the marking is complete.
     *         The gap between the rows in the table and the students who sat it is stated
     *         rather than left for the teacher to notice by counting
     */
    public static String markedLabel(ExecutionResults results) {
        Objects.requireNonNull(results, "results");
        int marked = results.rows().size();
        int participants = results.execution().participants();
        if (participants == 0) {
            return "Nobody sat this sitting";
        }
        if (participants == 1) {
            return marked >= 1 ? "1 paper marked" : "0 of 1 papers marked";
        }
        if (marked >= participants) {
            return "All " + participants + " papers marked";
        }
        return marked + " of " + participants + " papers marked";
    }

    /**
     * The execution picker's line: which code, when, and how it went.
     *
     * @param execution one sitting
     * @param zone      the reader's zone; wire instants are UTC (ADR-010)
     * @return "Code 4821 · 20 Aug 09:00 to 11:00 · closed · 8 sat"
     */
    public static String executionLabel(ExecutionResultRow execution, ZoneId zone) {
        Objects.requireNonNull(execution, "execution");
        return "Code " + execution.code4()
                + " · " + windowLabel(execution, zone)
                + " · " + stateLabel(execution.state())
                + " · " + participantsLabel(execution.participants());
    }

    /**
     * @param execution one sitting
     * @param zone      the reader's zone
     * @return "20 Aug 09:00 to 11:00"
     */
    public static String windowLabel(ExecutionResultRow execution, ZoneId zone) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(zone, "zone");
        return format(execution.openAt(), WINDOW_START, zone)
                + " to " + format(execution.closeAt(), WINDOW_END, zone);
    }

    /**
     * @param state the wire state
     * @return the word a teacher uses for it. Cancelled is included for completeness and never
     *         reaches this screen, because the server excludes those sittings (H15.2)
     */
    public static String stateLabel(ExecutionState state) {
        Objects.requireNonNull(state, "state");
        return switch (state) {
            case SCHEDULED -> "scheduled";
            case LIVE -> "live now";
            case CLOSED -> "closed";
            case CANCELLED -> "cancelled";
        };
    }

    /**
     * @param count how many students sat the execution
     * @return "8 sat", or "nobody sat it" when the count is zero
     */
    public static String participantsLabel(int count) {
        if (count <= 0) {
            return "nobody sat it";
        }
        return count + " sat";
    }

    /**
     * @param state a row's grade state
     * @return "Approved" or "Awaiting approval". A student sees nothing until the second
     *         becomes the first (C-3, S-24), so the teacher's table has to say which it is
     */
    public static String gradeStateLabel(GradeState state) {
        Objects.requireNonNull(state, "state");
        return state == GradeState.APPROVED ? "Approved" : "Awaiting approval";
    }

    /**
     * The adjusted marker (S-23).
     *
     * @param row a result row
     * @return "Adjusted" when a teacher changed the machine's score, otherwise an empty string
     *         so the column stays blank rather than showing a dash for the ordinary case
     */
    public static String adjustedMarker(StudentGradeRow row) {
        Objects.requireNonNull(row, "row");
        return wasAdjusted(row) ? "Adjusted" : "";
    }

    /**
     * @param row a result row
     * @return whether a teacher's score replaced the machine's
     */
    public static boolean wasAdjusted(StudentGradeRow row) {
        Objects.requireNonNull(row, "row");
        return row.finalScore() != null && row.finalScore() != row.autoScore();
    }

    /**
     * How the attempt ended, as a word ⚑ (B-16 — T-10.2, F9.2).
     *
     * <p>A word and never only a colour, per the wave rules: "timed out" is the fact a teacher
     * acts on, and a row tinted amber tells a colour-blind reader, a printed page and a
     * screenshot nothing at all. The ordinary case is "Submitted" rather than blank, because a
     * column whose only content is the exception reads as data that failed to load.
     *
     * <p>Blank only when the path did not carry a status, which no teacher results row does.
     *
     * @param status how the attempt ended, or {@code null}
     * @return "Submitted", "Timed out", "In progress", or an empty string
     */
    public static String attemptStatusLabel(AttemptState status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case SUBMITTED -> "Submitted";
            case TIMED_OUT -> "Timed out";
            case IN_PROGRESS -> "In progress";
            case NOT_STARTED -> "";
        };
    }

    /**
     * Recorded solving time (S-19, B-16).
     *
     * @param minutes what the server recorded, or {@code null} when it recorded nothing
     * @return "43 min", or "Not recorded" — which is a different fact from zero and is said as
     *         one rather than shown as an empty cell a reader would read as a bug
     */
    public static String solvingTimeLabel(Integer minutes) {
        return minutes == null ? "Not recorded" : minutes + " min";
    }

    /**
     * @param row a result row
     * @return whether the server handed this paper in at the bell rather than the student
     *         (F6.4). What the table tints behind the word, never instead of it
     */
    public static boolean wasTimedOut(StudentGradeRow row) {
        Objects.requireNonNull(row, "row");
        return row.attemptStatus() == AttemptState.TIMED_OUT;
    }

    // ===================== Formatting =====================================

    /**
     * @param value a statistic
     * @return its display form, through the chart's own formatter so the cards and the markers
     *         can never round the same stored number two different ways
     */
    public static String number(double value) {
        return StatChartLogic.number(value);
    }

    private static String format(Instant instant, DateTimeFormatter formatter, ZoneId zone) {
        return instant == null ? "" : formatter.format(instant.atZone(zone));
    }
}
