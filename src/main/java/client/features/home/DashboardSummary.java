package client.features.home;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one live sentence under a dashboard greeting (Presentation tier, UI wave 2).
 *
 * <p>The remodel puts a sentence beneath "Good morning, Dana" that says what her
 * morning actually contains: <i>"One sitting is live right now, and 8 papers are
 * waiting for your sign-off."</i> It is composed from the numbers the cards
 * themselves loaded, never from a read of its own, which is what keeps the
 * promise that wave 2 adds no verb and no round trip.
 *
 * <h2>Why it is a class and not a format string</h2>
 *
 * <p>Because it has to be right in the cases nobody demonstrates. A sentence
 * built by concatenation says "1 sittings are live", says "and 0 papers are
 * waiting", and says both while the reads are still in flight. Each of those is
 * a sentence a visitor would read in the first four seconds of the demo, and
 * each of them is a rule with an edge:
 *
 * <ul>
 *   <li><b>Nothing loaded yet</b> gives {@link DashboardCopy#SUMMARY_LOADING},
 *       not a sentence full of zeros that is about to be replaced.</li>
 *   <li><b>Every read failed</b> gives {@link DashboardCopy#SUMMARY_UNAVAILABLE}.
 *       Zeros here would be the same lie {@link DashboardCard.State#FAILED}
 *       exists to prevent, told in prose instead of in a number.</li>
 *   <li><b>Nothing is happening</b> gives the role's calm sentence rather than a
 *       list of noughts. A quiet Tuesday is a real answer.</li>
 *   <li><b>A clause with nothing in it is dropped</b>, so a teacher with a live
 *       sitting and an empty grading queue reads one clause, not two with a
 *       zero in the second.</li>
 * </ul>
 *
 * <p>Free of JavaFX and free of a clock: the time of day belongs to
 * {@link HomeGreeting}, which the header pairs this with. Both are pure
 * functions, both are tested, and neither can be got wrong by a screen.
 */
public final class DashboardSummary {

    private DashboardSummary() {
    }

    // ===================== The four roles ================================

    /**
     * The teacher's sentence.
     *
     * @param live      sittings running right now
     * @param toGrade   sittings with papers still to mark
     * @param unloaded  {@code true} while at least one of her reads has not
     *                  settled, which suppresses the sentence rather than
     *                  publishing a half-formed one
     * @param allFailed {@code true} when every read failed
     */
    public static String teacher(int live, int toGrade, boolean unloaded, boolean allFailed) {
        String pending = pending(unloaded, allFailed);
        if (pending != null) {
            return pending;
        }
        List<String> clauses = new ArrayList<>();
        if (live > 0) {
            clauses.add(count(live, "sitting", "sittings")
                    + (live == 1 ? " is live right now" : " are live right now"));
        }
        if (toGrade > 0) {
            clauses.add(count(toGrade, "sitting", "sittings")
                    + (toGrade == 1 ? " is waiting for your marking" : " are waiting for your marking"));
        }
        return clauses.isEmpty() ? DashboardCopy.SUMMARY_TEACHER_QUIET : sentence(clauses);
    }

    /**
     * The coordinator's sentence.
     *
     * @param waiting  exams submitted for her decision
     * @param teachers how many different authors they came from
     */
    public static String coordinator(int waiting, int teachers, boolean unloaded,
                                     boolean allFailed) {
        String pending = pending(unloaded, allFailed);
        if (pending != null) {
            return pending;
        }
        if (waiting <= 0) {
            return DashboardCopy.SUMMARY_COORDINATOR_QUIET;
        }
        List<String> clauses = new ArrayList<>();
        clauses.add(count(waiting, "exam", "exams")
                + (waiting == 1 ? " is waiting for your approval" : " are waiting for your approval"));
        if (teachers > 0) {
            clauses.add("they came from " + count(teachers, "teacher", "teachers").toLowerCase(Locale.ENGLISH));
        }
        return sentence(clauses);
    }

    /**
     * The student's sentence.
     *
     * @param grades   how many published grades she has
     * @param latest   the name of the most recent exam, or {@code null}
     */
    public static String student(int grades, String latest, boolean unloaded, boolean allFailed) {
        String pending = pending(unloaded, allFailed);
        if (pending != null) {
            return pending;
        }
        if (grades <= 0) {
            return DashboardCopy.SUMMARY_STUDENT_QUIET;
        }
        List<String> clauses = new ArrayList<>();
        clauses.add(count(grades, "grade", "grades")
                + (grades == 1 ? " is published" : " are published"));
        if (latest != null && !latest.isBlank()) {
            clauses.add("the newest is " + latest.trim());
        }
        return sentence(clauses);
    }

    /**
     * The principal's sentence.
     *
     * @param exams    exams on file across the school
     * @param sittings closed sittings with final statistics
     */
    public static String principal(int exams, int sittings, boolean unloaded, boolean allFailed) {
        String pending = pending(unloaded, allFailed);
        if (pending != null) {
            return pending;
        }
        if (exams <= 0 && sittings <= 0) {
            return DashboardCopy.SUMMARY_PRINCIPAL_QUIET;
        }
        List<String> clauses = new ArrayList<>();
        clauses.add("the school has " + count(exams, "exam", "exams").toLowerCase(Locale.ENGLISH)
                + " on file");
        if (sittings > 0) {
            clauses.add(count(sittings, "sitting", "sittings").toLowerCase(Locale.ENGLISH)
                    + (sittings == 1 ? " has been marked" : " have been marked"));
        }
        return sentence(clauses);
    }

    // ===================== The pieces ====================================

    /**
     * A counted noun, with the article the canvas uses.
     *
     * <p>"One sitting" rather than "1 sitting": a sentence is prose, and a
     * numeral at the start of one reads as a table cell that escaped. Everything
     * above one stays a numeral, which is also what the canvas shows.
     *
     * @return for example {@code "One sitting"} or {@code "8 papers"}
     */
    public static String count(int howMany, String singular, String plural) {
        if (howMany == 1) {
            return "One " + singular;
        }
        return howMany + " " + plural;
    }

    /**
     * Joins clauses into one sentence.
     *
     * <p>Two clauses are joined with ", and", which is the canvas's own
     * punctuation and reads as one thought rather than two facts. Three or more
     * would be a list, and a dashboard summary that needs a list has stopped
     * being a summary — so this caps at two and drops the rest, deliberately.
     *
     * @param clauses lowercase fragments with no terminal punctuation
     * @return the finished sentence, capitalised and stopped
     */
    public static String sentence(List<String> clauses) {
        List<String> kept = clauses.stream().filter(c -> c != null && !c.isBlank()).limit(2).toList();
        if (kept.isEmpty()) {
            return "";
        }
        String joined = kept.size() == 1 ? kept.get(0) : kept.get(0) + ", and " + kept.get(1);
        return capitalise(joined) + ".";
    }

    /** @return the sentence to show instead, or {@code null} when the real one can be built. */
    private static String pending(boolean unloaded, boolean allFailed) {
        if (allFailed) {
            return DashboardCopy.SUMMARY_UNAVAILABLE;
        }
        return unloaded ? DashboardCopy.SUMMARY_LOADING : null;
    }

    private static String capitalise(String text) {
        if (text.isEmpty()) {
            return text;
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
