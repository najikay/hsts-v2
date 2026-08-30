package client.features.data;

import common.dto.report.ReportRow;
import common.dto.results.ResultStatistics;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Every sentence the principal's three detail screens print (Presentation tier, E15.2 — F9.3,
 * S-7, U-44, the lead's ruling of 2026-08-30).
 *
 * <p>FX-free and static, on the {@link DataCopy} pattern beside it, so the headings, the
 * refusal sentences and the decile labels are unit tested rather than eyeballed, and so the
 * three views stay renderers with no decisions in them.
 *
 * <h2>Separate from {@link DataCopy}, deliberately</h2>
 *
 * <p>That class is the <b>list</b>: three tabs, three count lines, four empty panels, and every
 * string in it is shaped by {@link DataTab}. These are the three screens a row opens, and none
 * of them has a tab, a filter or a count. Folding them in would have put ten strings in a class
 * whose organising idea does not describe them, which is how a copy class becomes a bag.
 *
 * <h2>Read-only is said out loud here too</h2>
 *
 * <p>{@link #READ_ONLY_NOTE} repeats {@code DataCopy.READ_ONLY_NOTE}'s job one level down: T-11.3
 * has a reviewer looking for a create, edit or delete control <em>anywhere in her shell</em>, and
 * a detail screen that simply has no buttons is indistinguishable from one whose buttons have not
 * been built yet. It is worded per screen, because "nothing here can change this question" is a
 * more useful sentence on a question than a paraphrase of the list's.
 */
public final class DataDetailCopy {

    // ===================== Shared =========================================

    /** S-7, said on each of the three screens rather than only on the list. */
    public static final String READ_ONLY_NOTE =
            "This screen is read only. Nothing on it can change what it shows.";

    // ===================== The question (T-11.1) ==========================

    /** The question screen's title, before the id is known. */
    public static final String QUESTION_TITLE = "Question";

    /** What the question screen is, in one line. */
    public static final String QUESTION_SUBTITLE =
            "One question of the school's bank, with its answer key and its version history.";

    /** The heading over the version timeline. */
    public static final String HISTORY_TITLE = "Version history";

    /** Shown while the version timeline is on its way. */
    public static final String HISTORY_LOADING = "Reading the version history.";

    /**
     * Shown when the timeline could not be read.
     *
     * <p>The question itself is already on screen when this appears, so the sentence is about
     * the panel and not about the screen: a reader who can see the question must not be told
     * the question failed.
     */
    public static final String HISTORY_FAILED =
            "The version history could not be read. The question above is unaffected.";

    /** Shown when the question could not be opened at all. */
    public static final String QUESTION_FAILED_TITLE = "This question could not be opened";

    /** What to do about it. Says nothing about why (F1.1's discipline). */
    public static final String QUESTION_FAILED_HINT =
            "It may have been deleted since the list was loaded. Go back and try another one.";

    // ===================== The exam (T-11.2) ==============================

    /** The exam screen's title, before the exam is known. */
    public static final String EXAM_TITLE = "Exam";

    /**
     * The banner over the paper.
     *
     * <p>Says both halves of what this screen is: the paper on the left is the students' own,
     * and the block on the right is not. A principal reading an exam should never be in doubt
     * about which of the two she is looking at.
     */
    public static final String EXAM_BANNER =
            "The paper below is exactly what the students saw. The answer key and the teacher's "
                    + "notes beside it are staff only.";

    /** Shown when the exam could not be opened. */
    public static final String EXAM_FAILED_TITLE = "This exam could not be opened";

    /** What to do about it. */
    public static final String EXAM_FAILED_HINT =
            "Go back to the list and try it again, or pick another exam.";

    /** Shown on an exam row that carries no version to open (an older server). */
    public static final String EXAM_NOT_OPENABLE =
            "This exam has no version this screen can open.";

    // ===================== The sitting (T-11.2) ===========================

    /** The results screen's title, before the sitting is known. */
    public static final String SITTING_TITLE = "Sitting results";

    /** The heading over the distribution table. */
    public static final String DISTRIBUTION_TITLE = "Score distribution";

    /**
     * Why this screen shows a distribution and not a list of students.
     *
     * <p>The honest reason, said rather than left to be noticed. {@code DATA_RESULTS_GET} carries
     * the frozen figures for a sitting and no per-student row, and F9.3 gives the principal the
     * school's data rather than the school's pupils by name; the teacher who wrote the exam reads
     * the named list on her own Results screen (S-35).
     */
    public static final String DISTRIBUTION_HINT =
            "Figures are frozen as they were when the last grade was approved. Individual "
                    + "students are named on the teacher's own results screen, not here.";

    /** Shown when the sitting could not be found. */
    public static final String SITTING_FAILED_TITLE = "This sitting could not be opened";

    /** What to do about it. */
    public static final String SITTING_FAILED_HINT =
            "It may have been reopened for grading since the list was loaded. Go back and try "
                    + "again.";

    // ===================== Formatting ======================================

    /** Date and time as the sitting header reads it: "20 Aug 2026, 09:00". */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    private DataDetailCopy() {
        // static helper - no instances
    }

    /**
     * @param displayId5 the question's five-digit id
     * @return the screen's heading: "Question Q11001". The letter is how staff say a question id
     *         out loud, exactly as the list column spells it
     */
    public static String questionHeading(String displayId5) {
        Objects.requireNonNull(displayId5, "displayId5");
        return QUESTION_TITLE + " Q" + displayId5;
    }

    /**
     * @param row the sitting
     * @return the header line under the sitting's name: course, then the window it ran in
     */
    public static String sittingMeta(ReportRow row, ZoneId zone) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(zone, "zone");
        return DataCopy.course(row.courseCode(), row.courseName())
                + " · opened " + stamp(row.openAt(), zone)
                + " · closed " + stamp(row.closeAt(), zone);
    }

    /**
     * The participants line, which is the one place the two counts are allowed to disagree.
     *
     * @param row the sitting
     * @return "8 sat it, 8 marked", or "8 sat it, 7 marked (1 paper has no grade behind these
     *         figures)". The gap is a fact a principal should be able to see: it is the
     *         difference between the {@code COUNT} over attempts and the count the statistics
     *         were frozen over
     */
    public static String participantsLine(ReportRow row) {
        Objects.requireNonNull(row, "row");
        String base = row.participants() + " sat it, " + row.statistics().count() + " marked";
        int unmarked = row.unmarked();
        if (unmarked == 0) {
            return base;
        }
        return base + " (" + unmarked + (unmarked == 1 ? " paper has" : " papers have")
                + " no grade behind these figures)";
    }

    /**
     * One row of the distribution table.
     *
     * @param bucket the decile index, 0..9
     * @return "0 to 9", up to "90 to 100". The top bucket is deliberately eleven wide: a perfect
     *         score lands in it rather than in an eleventh bucket, which is how
     *         {@link ResultStatistics} freezes it and therefore how it has to be read
     */
    public static String decileLabel(int bucket) {
        if (bucket < 0 || bucket >= ResultStatistics.BUCKET_COUNT) {
            throw new IllegalArgumentException(
                    "A decile index is 0.." + (ResultStatistics.BUCKET_COUNT - 1)
                            + ", got " + bucket);
        }
        int low = bucket * 10;
        int high = bucket == ResultStatistics.BUCKET_COUNT - 1 ? 100 : low + 9;
        return low + " to " + high;
    }

    /**
     * @param count how many scores fell in one bucket
     * @param total how many scores there are altogether
     * @return "2 (25%)", or "0" when the bucket is empty, so a row of zeroes does not print a
     *         column of "0%" for a reader to scan past
     */
    public static String decileShare(int count, int total) {
        if (count == 0) {
            return "0";
        }
        if (total <= 0) {
            return Integer.toString(count);
        }
        return count + " (" + DataCopy.number(count * 100.0 / total) + "%)";
    }

    /**
     * The ten buckets as rows, built once so the view has nothing to decide.
     *
     * @param stats the frozen statistics
     * @return one row per decile, lowest first, which is how a distribution reads
     */
    public static List<DecileRow> distribution(ResultStatistics stats) {
        Objects.requireNonNull(stats, "stats");
        List<DecileRow> rows = new ArrayList<>(ResultStatistics.BUCKET_COUNT);
        for (int i = 0; i < stats.deciles().size(); i++) {
            int count = stats.deciles().get(i);
            rows.add(new DecileRow(decileLabel(i), count,
                    decileShare(count, stats.count())));
        }
        return List.copyOf(rows);
    }

    /**
     * One row of the score distribution.
     *
     * @param range the score band, "0 to 9"
     * @param count how many scores fell in it, for a numeric column that sorts as a number
     * @param share what the table prints: the count and its percentage
     */
    public record DecileRow(String range, int count, String share) {

        public DecileRow {
            Objects.requireNonNull(range, "range");
            Objects.requireNonNull(share, "share");
        }
    }

    private static String stamp(Instant when, ZoneId zone) {
        return STAMP.format(when.atZone(zone));
    }
}
