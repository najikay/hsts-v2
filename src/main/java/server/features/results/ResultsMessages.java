package server.features.results;

/**
 * Every sentence the results verbs are allowed to say (Logic tier, E14.1 — PRD §4.1).
 *
 * <p>Written once, here, on the {@code ExamMessages} pattern: the copy rules are then
 * enforceable by one test (no em dashes, sentence case, and <b>every refusal says what to do
 * next</b>), and the wording is reviewed once rather than at three call sites.
 *
 * <p>There are only two of them, and that is itself a decision. E14 has one refusal —
 * {@code NOT_FOUND} — and it is deliberately the <b>same sentence</b> for an execution that
 * does not exist and for one belonging to an exam somebody else wrote. Two different sentences
 * would let a teacher, or anybody holding her session, discover which execution ids are real
 * by reading the wording. The role gate's sentence comes from {@code Authorization} and is not
 * repeated here.
 */
public final class ResultsMessages {

    private ResultsMessages() {
    }

    /**
     * The one refusal: unknown id, or an exam the caller did not write.
     *
     * <p>Says "you can only see results for exams you wrote" rather than "this is not yours",
     * because the first explains the rule and the second implies the execution exists.
     */
    public static final String NO_SUCH_EXECUTION =
            "That exam sitting is not available. You can see results for exams you wrote, "
                    + "including sittings run by other teachers.";

    /** A payload that is not an {@code ExecutionResultsRequest}: a client bug, not a user's. */
    public static final String MALFORMED_REQUEST =
            "That request could not be read. Reopen the results screen and try again.";
}
