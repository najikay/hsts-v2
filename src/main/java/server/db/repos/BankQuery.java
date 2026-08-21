package server.db.repos;

import server.db.entities.Difficulty;

import java.util.List;
import java.util.Objects;

/**
 * What a bank browse is asking for: who may see what, and which filters are on (E6.5).
 *
 * <p>A record rather than six parameters, because four of them are adjacent nullable strings
 * and a call site with {@code (null, null, "root", null)} in it is a defect waiting for
 * somebody to reorder the signature.
 *
 * <h2>Scope and filter are separate on purpose</h2>
 *
 * <p>{@link #allCourses} and {@link #reachableCourses} are <b>authorization</b>: what the
 * caller is permitted to see, decided by her role and never by anything she sent.
 * {@link #courseCode} is a <b>filter</b>: what she asked to narrow to. They are kept apart
 * because they fail differently. A filter naming a course she cannot reach is not an error and
 * not a refusal, it simply matches nothing once intersected with her scope, so the client's
 * filter list stays a convenience rather than a boundary (BANK_WIRE_CONTRACT §8).
 *
 * <p><b>An empty {@link #reachableCourses} is not the same as {@link #allCourses}.</b> Empty
 * means the caller reaches nothing and the query must return nothing; unrestricted means she
 * reaches everything. Collapsing the two is how a scoping bug becomes a data leak rather than
 * an empty screen, so the two are different fields and the factories below are the only way in.
 *
 * @param allCourses       whether scope is unrestricted (PRINCIPAL only, F9.3)
 * @param reachableCourses the course codes in scope, ignored when {@code allCourses}
 * @param courseCode       narrow to one course, or null for all reachable ones
 * @param topic            exact topic match, or null
 * @param difficulty       exact difficulty match, or null
 * @param search           case-insensitive substring of the question stem, or null
 */
public record BankQuery(boolean allCourses, List<String> reachableCourses,
                        String courseCode, String topic, Difficulty difficulty, String search) {

    public BankQuery {
        reachableCourses = reachableCourses == null ? List.of() : List.copyOf(reachableCourses);
    }

    /**
     * A browse scoped to the courses a teacher or coordinator reaches.
     *
     * @param reachable her reachable course codes; empty means she sees nothing
     */
    public static BankQuery scopedTo(List<String> reachable, String courseCode, String topic,
                                     Difficulty difficulty, String search) {
        Objects.requireNonNull(reachable, "reachable");
        return new BankQuery(false, reachable, courseCode, topic, difficulty, search);
    }

    /** A browse over every course, which only the principal gets (F9.3). */
    public static BankQuery everyCourse(String courseCode, String topic,
                                        Difficulty difficulty, String search) {
        return new BankQuery(true, List.of(), courseCode, topic, difficulty, search);
    }

    /**
     * Whether this query can match anything at all.
     *
     * <p>A caller with no reachable courses is answered without touching the database. This is
     * <b>only</b> an optimisation, measured rather than assumed: Hibernate 6 expands an empty
     * {@code in ()} into a false predicate on both H2 and MySQL, so removing this short-circuit
     * still returns nothing and still does not throw. An earlier version of this javadoc claimed
     * it prevented a crash; planting that exact change proved otherwise.
     *
     * <p>The property that <em>is</em> load-bearing lives in the caller's WHERE clause, not
     * here: the scope predicate must be applied even when the list is empty. A version that
     * skipped the clause for an empty list, which is the natural-looking way to "handle" this
     * case, hands every question in the school to a caller entitled to none.
     * {@code BankBrowseContract.emptyScopeMatchesNothing} fails on that change and was watched
     * doing so.
     *
     * @return true when the scope is empty and not unrestricted
     */
    public boolean matchesNothing() {
        return !allCourses && reachableCourses.isEmpty();
    }
}
