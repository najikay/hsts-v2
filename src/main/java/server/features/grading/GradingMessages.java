package server.features.grading;

/**
 * Every sentence the grading verbs say to a teacher (Logic tier, E12).
 *
 * <p>Gathered here for the same reason the other features gather theirs: a message a teacher
 * reads mid-demo is product copy, and copy that lives inline in handlers gets edited by
 * whoever is closest to the bug rather than by whoever is thinking about the wording.
 *
 * <p>Two of these deserve a second look, because they are deliberately vaguer than the code
 * that returns them. {@link #NO_SUCH_GRADE} is the answer both to a grade that does not exist
 * and to one belonging to another teacher's execution, and it must stay ambiguous: a sentence
 * that distinguished them would turn the verb into a way of discovering which grades exist.
 * {@link #ALREADY_APPROVED} names a state rather than a permission, because the teacher has
 * not done anything wrong — the workflow simply does not run backwards in v1.
 */
public final class GradingMessages {

    private GradingMessages() {
        // sentences only
    }

    /** The payload was missing, or was not the type the verb takes. */
    public static final String MALFORMED_REQUEST =
            "That request could not be read. Please try again.";

    /** Unknown grade, or one that is not this teacher's — one sentence for both, on purpose. */
    public static final String NO_SUCH_GRADE =
            "That grade is not available.";

    /** The justification is required, and blank space is not a justification. */
    public static final String JUSTIFICATION_REQUIRED =
            "Please say why you are changing this score. The reason is stored with the grade.";

    /** The score is outside the range an exam can produce. */
    public static final String SCORE_OUT_OF_RANGE =
            "A score must be between 0 and 100.";

    /** Unknown execution, or one that is not this teacher's — one sentence for both. */
    public static final String NO_SUCH_EXECUTION =
            "That exam sitting is not available.";

    /** The grade has already been published to the student, so it may not be changed (C-3). */
    public static final String ALREADY_APPROVED =
            "This grade has already been approved and sent to the student, so it can no longer "
                    + "be changed.";
}
