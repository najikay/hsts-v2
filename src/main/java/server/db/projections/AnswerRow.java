package server.db.projections;

/**
 * One stored choice, on its way back to a resuming student (E10.6 — F6.3).
 *
 * <p>Read from {@code attempt_answers} on resume so a client that was killed mid-exam
 * finds the paper exactly as it left it. It says which question and what was picked, and
 * has no idea whether that was the right answer — the answer key lives in
 * {@code question_versions} and no read on this path goes near it (E2.12).
 *
 * @param questionVersionId the question, by the pinned version the paper asks
 * @param selected          the chosen option 1..4, or {@code null} for a row that was
 *                          written and then cleared
 */
public record AnswerRow(long questionVersionId, Integer selected) {

    /** @return {@code true} when this row actually carries a choice. */
    public boolean isAnswered() {
        return selected != null;
    }
}
