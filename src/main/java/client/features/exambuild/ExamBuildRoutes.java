package client.features.exambuild;

/**
 * The route ids this feature's screens are registered under (E7.10, E7.11).
 *
 * <p>{@code client.core.Routes} is where they are actually declared; these constants are the
 * feature's own copy of the spelling, used by every button and every navigation in the package,
 * so a screen in here never hard-codes a string that only the route table knows. The same shape
 * as {@code BankRoutes}, and for the same reason.
 *
 * <p>{@code ExamListWiringGuardTest} pins {@link #LIST} against {@code client.core.Routes}
 * through {@code SessionRoutes}, so a copy that drifts from the declaration fails the build
 * rather than producing a button that navigates nowhere.
 */
public final class ExamBuildRoutes {

    /**
     * The teacher's exam list, and the rail id.
     *
     * <p>Spelled for the end state from the start, which costs nothing here because the end
     * state is already the spelling: {@code Routes.EXAMS} has read {@code "exams"} since E8
     * shipped the approval-status half behind it, and its own javadoc says E7 "replaces the
     * screen behind this id when it lands". So this feature renames nothing and the
     * notification route table keeps working across the swap ({@code APPROVAL_APPROVED} and
     * {@code APPROVAL_REJECTED} both point here).
     */
    public static final String LIST = "exams";

    /**
     * The exam builder, reached from the list.
     *
     * <p><b>Not a declared route yet</b>, and deliberately named here anyway. E7.11 to E7.13 are
     * the screen behind it and land in a later PR, at which point the lead declares the id in
     * {@code client.core.Routes} and registers the view. Naming it in one place now is method
     * rule 3: the pending decision binds at exactly one line, so adopting the real route costs
     * one edit instead of a sweep through every button that wanted to open a builder.
     *
     * <p>Spelled on the convention the rest of the app already uses for a view of one thing
     * reached from a list: {@code questions.edit}, {@code approvals.preview},
     * {@code grades.checked}.
     *
     * <p>Nothing in this PR navigates to it. The list's Edit action lands with the builder it
     * opens, in the same change, so there is never a button on a rail that goes nowhere.
     */
    public static final String BUILDER = "exams.build";

    private ExamBuildRoutes() {
    }
}
