package client.features.bank;

/**
 * The two route ids this feature's screens are registered under (E6.9, E6.10).
 *
 * <p>{@code client.core.Routes} is where they are actually declared; these constants are the
 * feature's own copy of the spelling, used by every button and every navigation in the package,
 * so a screen in here never hard-codes a string that only the route table knows.
 *
 * <p>{@code BankScreenWiringGuardTest} pins both against {@code client.core.Routes} through
 * {@code SessionRoutes}, so a copy that drifts from the declaration fails the build rather than
 * producing a button that navigates nowhere. Both files move together or neither does.
 */
public final class BankRoutes {

    /**
     * The question bank list.
     *
     * <p>The rail id, and the end state. It read {@code "bank"} while the E0 prototype list still
     * answered to {@code questions} — the lead's ruling of 2026-08-23, which kept exactly one bank
     * on the rail at every moment. The retirement PR deleted that screen and moved this constant
     * onto the rail id, which is the only spelling either half needs now.
     */
    public static final String LIST = "questions";

    /**
     * The question editor.
     *
     * <p>Spelled for the end state from the start: it is a view of one question reached from the
     * list, the same shape as {@code approvals.preview} and {@code grades.checked}, and naming it
     * after the rail id it would finally sit under meant the retirement PR renamed nothing here.
     */
    public static final String EDITOR = "questions.edit";

    private BankRoutes() {
    }
}
