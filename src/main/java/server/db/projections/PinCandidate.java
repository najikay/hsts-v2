package server.db.projections;

/**
 * What the exam-builder validator needs to know about one question a teacher wants to pin
 * (E7.2, E7.8 — T-3.9).
 *
 * <p>Resolved from the {@code question_versions} id the client sent, because every rule in the
 * contract's §5.2 is about a fact the client does not supply and must not be trusted for: which
 * question the version belongs to, which course that question is in, and whether it has been soft
 * deleted since she picked it.
 *
 * <p><b>{@code questionId} is the whole of the duplicate rule.</b> The client sends version ids,
 * so two entries pointing at two different versions of one question look distinct until they are
 * resolved. Resolving them here is what lets the refusal name the question rather than let the
 * composite unique constraint fire with a message no teacher can act on.
 *
 * <p><b>{@code deleted} has no database backstop and that is why it is on this record.</b> A soft
 * delete is an {@code UPDATE}, and no foreign key fires on an update, so nothing stops
 * {@code exam_version_questions} referencing a question the bank considers gone. ARCHITECTURE §5
 * assigns the rule to this validator by name, and the two-engine repository test on this read
 * stands in for the constraint that cannot exist.
 *
 * <p>Carries no answer key: the constructor expression never names {@code correct_answer}.
 *
 * @param questionVersionId  the version id the client sent, echoed so a result can be matched
 *                           back to its position in her list
 * @param questionId         the owning question, which is what "duplicate" means (T-3.9)
 * @param questionDisplayId5 the five-digit display id, so a refusal can name the question
 * @param courseCode         the question's course, for the same-course rule
 * @param deleted            whether the question is soft deleted
 */
public record PinCandidate(long questionVersionId,
                           long questionId,
                           String questionDisplayId5,
                           String courseCode,
                           boolean deleted) {
}
