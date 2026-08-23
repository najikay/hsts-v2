package server.db.projections;

/**
 * A question-bank question as the <b>study bot</b> may see it (E2.11 / E16.6 —
 * S-28, F12.8 ⚑).
 *
 * <h2>Why there is a third question projection</h2>
 *
 * <p>{@link TakeExamQuestion} exists so a student sitting an exam cannot be sent
 * an answer key. This one exists for a different reader with a different rule, and
 * conflating the two would have been the easy mistake: the take-exam projection
 * carries images and exam ordering that a prompt has no use for, and it is
 * addressed by exam version, which is precisely the thing the bot must not be able
 * to name.
 *
 * <p>S-28 lets the bot answer from the course's question bank, and F12.8 lets it
 * see course material. So the bot gets the question and its four options — that is
 * genuinely useful study material, and it is what the specification describes as
 * "the questions from the question bank".
 *
 * <h2>The correctness ruling, and why this type has no suffix</h2>
 *
 * <p><b>Which answer is right is not here.</b> That was the lead's ruling when this
 * epic was designed, and the reasoning is worth keeping next to the type: a study
 * bot that hands out answer keys for live-exam-adjacent material defeats its own
 * purpose, and the specification asks for the questions, not for the key. So this
 * projection has nowhere to put one, and the query behind it does not select
 * {@code correct_answer} at all — the column never leaves the database on this
 * path.
 *
 * <p>That is also why the read that returns it needs none of the sanctioned
 * {@code ForAuthoring} / {@code ForGrading} suffixes that
 * {@code CorrectnessLeakGuardTest} requires. Those suffixes exist to mark reads
 * that <em>do</em> carry a key, so a caller serving the wrong audience cannot pick
 * one up by accident. This read carries no key, so there is no audience to
 * declare — and the guard test's scan confirms that rather than taking it on
 * trust.
 *
 * <p>Deliberately not {@code Serializable}: bank questions reach the model, not
 * the student's screen.
 *
 * @param displayId the 5-digit id people quote (S-8)
 * @param text      the question stem
 * @param answer1   first option
 * @param answer2   second option
 * @param answer3   third option
 * @param answer4   fourth option
 */
public record BotBankQuestion(String displayId,
                              String text,
                              String answer1,
                              String answer2,
                              String answer3,
                              String answer4) {

    public BotBankQuestion {
        displayId = displayId == null ? "" : displayId;
        text = text == null ? "" : text;
        answer1 = answer1 == null ? "" : answer1;
        answer2 = answer2 == null ? "" : answer2;
        answer3 = answer3 == null ? "" : answer3;
        answer4 = answer4 == null ? "" : answer4;
    }

    /**
     * @return the question rendered for a prompt: the stem, then the four options,
     *         unmarked. The options are lettered rather than numbered so nothing in
     *         the rendering can be mistaken for a position in an answer key
     */
    public String asStudyMaterial() {
        return "Question " + displayId + ": " + text + "\n"
                + "A) " + answer1 + "\n"
                + "B) " + answer2 + "\n"
                + "C) " + answer3 + "\n"
                + "D) " + answer4;
    }

    /** @return the text this question is matched against when selecting context. */
    public String searchableText() {
        return text + ' ' + answer1 + ' ' + answer2 + ' ' + answer3 + ' ' + answer4;
    }
}
