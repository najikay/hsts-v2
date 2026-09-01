package client.features.reports;

import client.ui.components.logic.StatChartLogic;
import common.dto.report.ReportDimension;
import common.dto.report.ReportRow;
import common.dto.report.ReportSubject;
import common.dto.report.ReportSummary;
import common.dto.results.ResultStatistics;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Every sentence and every number the principal's Reports screen prints (Presentation tier,
 * E15.4 — F9.4).
 *
 * <p>FX-free and static, on the {@code ResultsCopy} pattern, so the summary cards, the row
 * labels and the four empty states are unit tested rather than eyeballed, and so the FX view
 * beside it stays a renderer with no decisions in it.
 *
 * <h2>One formatter, shared with the chart and with E14</h2>
 *
 * <p>Numbers go through {@link StatChartLogic#number(double)} — the same function the histogram's
 * markers and the teacher's stat cards use. A report card reading "Average 73" above a chart
 * marker reading "72.5" would be two roundings of one stored number, and a principal comparing
 * the report against the teacher's screen would find them disagreeing for no reason she could
 * see.
 *
 * <h2>Nothing here computes a statistic</h2>
 *
 * <p>Every figure is read off {@link ResultStatistics} or {@link ReportSummary}, both of which
 * carry numbers that were frozen when a sitting's last grade was approved (F8.5) or aggregated
 * from those on the server. This class formats. It does not average, it does not re-apply the
 * pass mark, and it does not divide anything that was already divided.
 */
public final class ReportsCopy {

    /** The screen's title. */
    public static final String TITLE = "Reports";

    /** What the screen is for, in one line under the title. */
    public static final String SUBTITLE =
            "Compare the statistics of closed exam sittings across teachers, courses and "
                    + "students.";

    /** Shown when the subject list cannot be loaded; says nothing about why (F1.1's discipline). */
    public static final String SUBJECTS_FAILED =
            "The list of subjects could not be loaded. Check your connection and try again.";

    /** Shown when a report cannot be run. */
    public static final String REPORT_FAILED =
            "That report could not be run. Choose the subject again, or pick another one.";

    /** The style class the print-friendly layout adds to the screen root (E15.4). */
    public static final String PRINT_STYLE_CLASS = "reports-print";

    /** REPORTS A2: the by-student heading's explanation, so the screen says what it shows. */
    public static final String BY_STUDENT_NOTE =
            "Her score per sitting, beside the class distribution it landed in. "
                    + "Her marked papers stay on her own screen.";

    /** REPORTS A2: the by-student column heading. */
    public static final String HER_SCORE_COLUMN = "Her score";

    /**
     * @param score her approved score on this sitting, or {@code null}
     * @return "60", or "-" for a sitting whose grade is not approved yet: unpublished stays
     *         unpublished, on the principal's screen too
     */
    public static String herScore(Integer score) {
        return score == null ? "-" : String.valueOf(score);
    }

    /** The one line that says what the whole screen is showing, above the table. */
    public static final String ROWS_TABLE_TITLE = "Closed sittings";

    /** Why a live or cancelled sitting is not in the table, said once rather than per row. */
    public static final String SCOPE_HINT =
            "Only sittings that have closed with their statistics frozen are compared. "
                    + "Live, scheduled and cancelled sittings are not.";

    /** How a report reads the middle of a pooled distribution, said where it is printed. */
    public static final String MEDIAN_BAND_HINT = "band the middle score falls in";

    // ===================== Empty states ===================================

    /**
     * The panel shown where the table would be, when there is no table to show.
     *
     * <p>A record rather than two loose strings, for the reason {@code ResultsCopy.EmptyPanel}
     * is one: the situations that produce it are different facts and the screen has to say which
     * of them it is. One generic "nothing here" for all of them is the dead end section 4.1
     * forbids.
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

    /** Nothing has been picked yet: the screen has just opened. */
    public static final EmptyPanel NOTHING_PICKED = new EmptyPanel(
            "Pick a subject",
            "Choose a report type above, then a teacher, course or student to compare.");

    /** This dimension has no subjects at all, which means the school has no such rows. */
    public static final EmptyPanel NO_SUBJECTS = new EmptyPanel(
            "Nothing to report on yet",
            "There is nobody and nothing of this kind on record. Reports appear here once "
                    + "there is.");

    /** The subject exists and no sitting of it has closed with statistics (E15.5). */
    public static final EmptyPanel NO_EXECUTIONS = new EmptyPanel(
            "No closed sittings yet",
            "Nothing here has been sat and fully marked, so there is nothing to compare. "
                    + "A sitting joins this report once its last grade is approved.");

    /**
     * Said under the cards when the report found <b>one</b> row.
     *
     * <p>The fourth state of this screen, and the one it had no words for. The three panels above
     * cover having nothing at all; a report with a single sitting shows real figures and a table
     * with one line in it, and every card, the chart and the word "compare" in the subtitle then
     * describe something the reader cannot do. That reads as a broken screen rather than as a
     * true answer, which is what {@code SCOPE_HINT} already stops the table doing for the
     * sittings it leaves out.
     *
     * <p><b>It says what would make it go away, like every other empty state here</b>, and it
     * says the same thing {@code NO_EXECUTIONS} does about what qualifies: approval is what puts
     * a sitting in a report. The "Sittings" card already reads "one sitting, no trend" under its
     * figure; that is a caption on a number, and this is the sentence the reader needs when the
     * whole screen is the number.
     */
    public static final String SINGLE_ROW_HINT =
            "One closed and graded sitting so far; approve more sittings to compare.";

    // ===================== Formatting ======================================

    /** Date and time as a report reads it off a row: "20 Aug 2026". */
    private static final DateTimeFormatter ROW_DATE =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private ReportsCopy() {
        // static helper - no instances
    }

    /**
     * @param dimension the selected dimension
     * @return the subject picker's prompt: "Teacher", "Course", "Student". Taken from the
     *         dimension rather than switched on here, so a fourth dimension needs no line in
     *         this file
     */
    public static String subjectPrompt(ReportDimension dimension) {
        Objects.requireNonNull(dimension, "dimension");
        return dimension.subjectNoun();
    }

    /**
     * @param dimension the selected dimension
     * @param subject   the selected subject
     * @return the heading over the table: "Algebra · by course"
     */
    public static String heading(ReportDimension dimension, ReportSubject subject) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(subject, "subject");
        return subject.label() + " · " + dimension.segment().toLowerCase(Locale.ENGLISH);
    }

    /**
     * @param subject the subject as the picker shows it
     * @return "Dana Cohen · 3 sittings", or "· nothing to report" when there are none. The count
     *         is in the picker so a principal learns a subject is empty before she opens it
     *         (E15.5)
     */
    public static String subjectLabel(ReportSubject subject) {
        Objects.requireNonNull(subject, "subject");
        if (subject.hasNothingToReport()) {
            return subject.label() + " · nothing to report";
        }
        return subject.label() + " · " + countOf(subject.executions(), "sitting", "sittings");
    }

    /**
     * @param row one sitting
     * @return the row's name in the table and on the chart: "Algebra midterm · 4821". The exam
     *         name is the released version's, so a sitting is labelled with what the students
     *         actually saw, and the code is what tells two sittings of one exam apart
     */
    public static String rowLabel(ReportRow row) {
        Objects.requireNonNull(row, "row");
        return row.examName() + " · " + row.code4();
    }

    /**
     * @param when the sitting's opening instant
     * @param zone the local zone
     * @return "20 Aug 2026". A date and not a time: two sittings a report compares are usually
     *         months apart, and the hour is noise at that distance
     */
    public static String rowDate(Instant when, ZoneId zone) {
        Objects.requireNonNull(when, "when");
        Objects.requireNonNull(zone, "zone");
        return ROW_DATE.format(when.atZone(zone));
    }

    /**
     * @param stats one sitting's frozen statistics
     * @return the pass-rate cell: "7 of 8 (87.5%)", the same phrasing E14's card uses so the two
     *         screens can be read side by side
     */
    public static String passRate(ResultStatistics stats) {
        Objects.requireNonNull(stats, "stats");
        return stats.passCount() + " of " + stats.count()
                + " (" + StatChartLogic.number(stats.passPercent()) + "%)";
    }

    /**
     * @param value any stored figure
     * @return it, rounded the one way this application rounds
     */
    public static String number(double value) {
        return StatChartLogic.number(value);
    }

    // ===================== The summary cards ===============================

    /**
     * One card above the table: a label, the figure, and a line saying what the figure is.
     *
     * @param label the caption under the value
     * @param value the figure itself
     * @param hint  one short line of context, never empty
     */
    public record SummaryCard(String label, String value, String hint) {

        public SummaryCard {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(hint, "hint");
        }
    }

    /**
     * The cross-row cards, in the order they are read.
     *
     * <p><b>Every hint here names the arithmetic</b>, and that is the point of the row rather
     * than decoration. "Weighted by participants" under the average is what stops a reader
     * checking it against the mean of the column above and concluding the report is wrong; the
     * two genuinely differ whenever the sittings differ in size, and the weighted one is the
     * true one. "Band" under the median says why it is a range: a median cannot be recovered
     * from medians, and the pooled distribution pins it to a decile and no further.
     *
     * @param summary the cross-row aggregate
     * @return six cards, always six, so the row's layout does not shift between subjects
     */
    public static List<SummaryCard> summaryCards(ReportSummary summary) {
        Objects.requireNonNull(summary, "summary");
        List<SummaryCard> cards = new ArrayList<>(6);
        cards.add(new SummaryCard("Sittings", Integer.toString(summary.executions()),
                summary.isSingleExecution() ? "one sitting, no trend" : "closed and marked"));
        cards.add(new SummaryCard("Average", number(summary.mean()),
                "weighted by participants"));
        cards.add(new SummaryCard("Median", medianBand(summary), MEDIAN_BAND_HINT));
        cards.add(new SummaryCard("Std deviation", number(summary.standardDeviation()),
                "pooled population sigma"));
        cards.add(new SummaryCard("Pass rate", summaryPassRate(summary),
                "pass mark " + ResultStatistics.PASS_MARK));
        cards.add(new SummaryCard("Results", Integer.toString(summary.scored()),
                summary.unmarked() == 0
                        ? "every paper marked"
                        : summary.unmarked() + " unmarked"));
        return List.copyOf(cards);
    }

    /**
     * @param summary the cross-row aggregate
     * @return the decile the middle score falls in, as "70-79", or a dash when nothing was
     *         scored. Never a made-up point value: a mean of medians would be a number with no
     *         referent
     */
    public static String medianBand(ReportSummary summary) {
        Objects.requireNonNull(summary, "summary");
        if (summary.medianBucket() == ReportSummary.NO_MEDIAN_BUCKET) {
            return "-";
        }
        return StatChartLogic.bucketLabel(summary.medianBucket());
    }

    /**
     * @param summary the cross-row aggregate
     * @return "10 of 12 (83.3%)", built from the summed stored pass counts rather than from the
     *         threshold applied a second time
     */
    public static String summaryPassRate(ReportSummary summary) {
        Objects.requireNonNull(summary, "summary");
        if (summary.scored() == 0) {
            return "-";
        }
        return summary.passCount() + " of " + summary.scored()
                + " (" + number(summary.passPercent()) + "%)";
    }

    /**
     * @param summary the cross-row aggregate
     * @return the line under the cards: "12 students across 2 sittings", or the singular forms.
     *         Participants rather than results, because it is the count of people who sat and
     *         the cards already carry the count of papers marked
     */
    public static String participantsLine(ReportSummary summary) {
        Objects.requireNonNull(summary, "summary");
        if (summary.isEmpty()) {
            return "";
        }
        return countOf(summary.participants(), "student", "students") + " across "
                + countOf(summary.executions(), "sitting", "sittings");
    }

    /**
     * @param summary the cross-row aggregate
     * @return {@link #SINGLE_ROW_HINT} when the report found exactly one sitting, and
     *         {@code ""} otherwise. Empty rather than null, on {@link #participantsLine}'s
     *         precedent, so the view hides a label instead of branching on a null
     */
    public static String comparisonHint(ReportSummary summary) {
        Objects.requireNonNull(summary, "summary");
        return summary.isSingleExecution() ? SINGLE_ROW_HINT : "";
    }

    private static String countOf(int count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural);
    }
}
