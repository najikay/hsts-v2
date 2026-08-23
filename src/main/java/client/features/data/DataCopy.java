package client.features.data;

import client.ui.components.logic.ChipCatalog;
import client.ui.components.logic.StatChartLogic;
import common.dto.bank.BankQuestionRow;
import common.dto.bank.Difficulty;
import common.dto.report.DataExamRow;
import common.dto.report.ReportRow;
import common.dto.results.ResultStatistics;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * Every sentence and every figure the principal's Data screen prints (Presentation tier,
 * E15.2 — F9.3, T-11).
 *
 * <p>FX-free and static, on the {@code ReportsCopy} pattern, so the empty states, the count
 * lines and the row labels are unit tested rather than eyeballed, and so the FX view beside it
 * stays a renderer with no decisions in it.
 *
 * <h2>Three tabs, one voice</h2>
 *
 * <p>The failure sentence, the count line and the "nothing here" panel are all built from
 * {@link DataTab}'s own nouns rather than written three times. That is not only tidiness: three
 * hand-written copies of "could not be loaded" is how one of them ends up saying something
 * subtly different, and a fourth tab would add a fourth.
 *
 * <h2>Nothing here computes a statistic</h2>
 *
 * <p>The Results tab prints figures that were frozen when a sitting's last grade was approved
 * (F8.5) and travelled unchanged through {@link ReportRow}. They go through
 * {@link StatChartLogic#number(double)} — the same rounding the teacher's histogram and the
 * principal's own reports use — so one sitting reads the same on all three screens.
 */
public final class DataCopy {

    /** The screen's title. Matches the rail item's label. */
    public static final String TITLE = "Data";

    /** What the screen is for, in one line under the title. */
    public static final String SUBTITLE =
            "Browse the school's question bank, exams and closed results.";

    /**
     * S-7 said out loud rather than merely enforced.
     *
     * <p>It is on screen because T-11.3 has a reviewer looking for a create, edit or delete
     * control and finding none, and a screen that simply has no buttons is indistinguishable
     * from one whose buttons have not been built yet.
     */
    public static final String READ_ONLY_NOTE =
            "This screen is read only. Nothing on it can change a question, an exam or a result.";

    /** The prompt in the filter box, the same on all three tabs. */
    public static final String FILTER_PROMPT = "Filter by name, code or course";

    /** The course picker's "do not filter" entry. */
    public static final String ALL_COURSES = "All courses";

    /** Why a live, scheduled or cancelled sitting is not on the Results tab, said once. */
    public static final String SCOPE_HINT =
            "Only sittings that have closed with their statistics frozen are listed. "
                    + "Live, scheduled and cancelled sittings are not.";

    /**
     * The safety cap, in the one case it can fire.
     *
     * <p>The bank arrives a page at a time and this screen asks for every page, so the sentence
     * is unreachable in any school of a realistic size. It exists because a loop that asks a
     * server for pages until it runs out is a loop, and a loop with no bound is a client that
     * hangs on a server answering nonsense.
     */
    public static final String TOO_MANY_QUESTIONS =
            "This bank is larger than this screen loads at once. Not every question is listed.";

    // ===================== Empty states ===================================

    /**
     * The panel shown where a table would be, when there is no table to show.
     *
     * <p>A record rather than two loose strings, for the reason {@code ReportsCopy.EmptyPanel}
     * is one: "this tab has nothing in it" and "your filter matched nothing" are different facts
     * and the screen has to say which. One generic "nothing here" for both is the dead end
     * PRD section 4.1 forbids.
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

    /** The tab holds rows and the filter hid all of them. */
    public static final EmptyPanel NO_MATCHES = new EmptyPanel(
            "Nothing matches that filter",
            "Clear the text box, or choose another course, to see the whole list again.");

    /** The school's bank is empty (T-11.1). */
    public static final EmptyPanel NO_QUESTIONS = new EmptyPanel(
            "The question bank is empty",
            "Questions appear here as soon as a teacher writes one, in any course.");

    /** No exam has been written yet (T-11.2). */
    public static final EmptyPanel NO_EXAMS = new EmptyPanel(
            "No exams have been written yet",
            "An exam appears here as soon as a teacher creates one, whatever became of it "
                    + "afterwards.");

    /** Nothing has been sat and fully marked yet (T-11.2, E15.5's degenerate case). */
    public static final EmptyPanel NO_RESULTS = new EmptyPanel(
            "No sittings have finished yet",
            "A sitting appears here once it has closed and its last grade is approved.");

    // ===================== Formatting ======================================

    /** Date as this screen reads it off a row: "20 Aug 2026". */
    private static final DateTimeFormatter ROW_DATE =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private DataCopy() {
        // static helper - no instances
    }

    /**
     * @param tab which list failed to arrive
     * @return the sentence over the table: says what failed and what to do, and never why
     *         (F1.1's discipline)
     */
    public static String loadFailed(DataTab tab) {
        Objects.requireNonNull(tab, "tab");
        return "The list of " + tab.listNoun()
                + " could not be loaded. Check your connection and try again.";
    }

    /**
     * @param tab the tab with nothing to show
     * @return that tab's own empty panel. Three different facts about three different tables,
     *         which is why there is not one shared "nothing here"
     */
    public static EmptyPanel nothingHere(DataTab tab) {
        Objects.requireNonNull(tab, "tab");
        return switch (tab) {
            case QUESTIONS -> NO_QUESTIONS;
            case EXAMS -> NO_EXAMS;
            case RESULTS -> NO_RESULTS;
        };
    }

    /**
     * @param tab    the tab being counted
     * @param shown  rows passing the filter
     * @param loaded rows the tab holds altogether
     * @return "40 questions", or "12 of 40 questions" while a filter is narrowing them. The
     *         second form exists so a filtered list can never be mistaken for a short one.
     *         Counted in rows rather than in the tab's own name: the Results tab lists
     *         <b>sittings</b>, and "1 of 2 results" would be counting the wrong thing
     */
    public static String countLine(DataTab tab, int shown, int loaded) {
        Objects.requireNonNull(tab, "tab");
        if (loaded == 0) {
            return "";
        }
        // The noun agrees with the LOADED count, not the shown one: "1 of 2 sittings" is the
        // English, and agreeing with the shown count would print "1 of 2 sitting".
        String noun = loaded == 1 ? tab.rowNoun() : tab.rowNounPlural();
        if (shown == loaded) {
            return loaded + " " + noun;
        }
        return shown + " of " + loaded + " " + noun;
    }

    /**
     * @param row one question
     * @return the id column: "Q11005". The letter is how staff say a question id out loud, and
     *         it keeps a five-digit id from being read as a score
     */
    public static String questionId(BankQuestionRow row) {
        Objects.requireNonNull(row, "row");
        return "Q" + row.displayId5();
    }

    /**
     * @param difficulty how hard a question is
     * @return "Easy", "Medium" or "Hard", through the one catalogue that already names them, so
     *         this screen cannot disagree with the chips on the teacher's bank
     */
    public static String difficulty(Difficulty difficulty) {
        Objects.requireNonNull(difficulty, "difficulty");
        return ChipCatalog.forDifficulty(difficulty.name()).label();
    }

    /**
     * @param row one question
     * @return the version column: "v2", or "v1" for one never rewritten. A number alone would
     *         read as a count of something
     */
    public static String questionVersion(BankQuestionRow row) {
        Objects.requireNonNull(row, "row");
        return "v" + row.latestVersionNo();
    }

    /**
     * @param row one exam
     * @return the version column: "v1" for an exam written once, "v3 of 3" for one rewritten
     *         twice. The long form is deliberate on this screen: F2.3's version history is a
     *         fact about the exam, and the principal has no other surface that shows it
     */
    public static String examVersions(DataExamRow row) {
        Objects.requireNonNull(row, "row");
        if (!row.hasBeenRevised()) {
            return "v1";
        }
        return "v" + row.versions() + " of " + row.versions();
    }

    /**
     * @param row one sitting
     * @return the sitting's name: "Algebra midterm · 4821". The same label the reports screen
     *         prints, so a principal moving between the two screens is looking at one thing
     */
    public static String sittingLabel(ReportRow row) {
        Objects.requireNonNull(row, "row");
        return row.examName() + " · " + row.code4();
    }

    /**
     * @param row one exam or one course row
     * @return "Algebra (11)", so two similarly named courses are distinguishable and the code a
     *         display id is built from is visible beside it
     */
    public static String course(String courseCode, String courseName) {
        Objects.requireNonNull(courseCode, "courseCode");
        if (courseName == null || courseName.isBlank()) {
            return courseCode;
        }
        return courseName + " (" + courseCode + ")";
    }

    /**
     * @param when a wire instant
     * @param zone the local zone
     * @return "20 Aug 2026". A date and not a time: nothing on this screen is browsed by the
     *         hour
     */
    public static String rowDate(Instant when, ZoneId zone) {
        Objects.requireNonNull(when, "when");
        Objects.requireNonNull(zone, "zone");
        return ROW_DATE.format(when.atZone(zone));
    }

    /**
     * @param stats one sitting's frozen statistics
     * @return "7 of 8 (87.5%)", the same phrasing E14's card and the reports table use
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
}
