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
     * <p><b>Declared and reachable.</b> {@code Routes.EXAM_BUILD} carries this id and
     * {@code SessionRoutes} registers the screen for both teaching roles. Two navigations reach
     * it: the list's Open/Edit action, carrying an {@code examVersionId}, and its New exam
     * control, carrying an {@link ExamBuilderView#PARAM_COURSE} and no version.
     *
     * <p><b>This paragraph used to say "not a declared route yet" and "nothing in this PR
     * navigates to it".</b> Both were true when written, at #51, when the id was named here
     * ahead of the screen so the pending decision bound at one line (method rule 3). The lead's
     * assembly declared the route, #52 built the screen behind it, and the New exam control made
     * the second sentence twice wrong. Corrected with its history, because a correction nobody
     * can date reads exactly like a fresh mistake.
     *
     * <p>Spelled on the convention the rest of the app already uses for a view of one thing
     * reached from a list: {@code questions.edit}, {@code approvals.preview},
     * {@code grades.checked}.
     */
    public static final String BUILDER = "exams.build";

    private ExamBuildRoutes() {
    }
}
