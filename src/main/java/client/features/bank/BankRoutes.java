package client.features.bank;

/**
 * The two route ids this feature's screens are registered under (E6.9, E6.10).
 *
 * <p>Written once each, because both are about to move. {@code client.core.Routes} is where they
 * are actually declared and that file is not this epic's to edit, so these constants are the
 * feature's own copy of the spelling, and the one place a rename has to be applied when the
 * retirement PR swaps the list behind {@code questions}.
 *
 * <p>{@code BankScreenWiringGuardTest} pins both against {@code SessionRoutes}, so a copy that
 * drifts from the declaration fails the build rather than producing a button that navigates
 * nowhere.
 */
public final class BankRoutes {

    /**
     * The question bank list.
     *
     * <p><b>Temporary.</b> The lead ruled on 2026-08-23 that rail id {@code questions} keeps
     * serving the legacy screen until E6's retirement PR, so the replacement list is registered
     * under its own non-rail id and reached from a banner on the legacy screen. At retirement
     * this becomes {@code "questions"} and the legacy screen is deleted.
     */
    public static final String LIST = "bank";

    /**
     * The question editor.
     *
     * <p>Spelled for the end state rather than the interim one: it is a view of one question
     * reached from the list, the same shape as {@code approvals.preview} and
     * {@code grades.checked}, and naming it after the rail id it will finally sit under means the
     * retirement PR does not rename it.
     */
    public static final String EDITOR = "questions.edit";

    private BankRoutes() {
    }
}
