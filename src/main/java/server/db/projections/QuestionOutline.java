package server.db.projections;

/**
 * A question reduced to what a summary grid needs (E10.13/E10.14 — F6.9, F6.10, F6.4).
 *
 * <p>{@link TakeExamQuestion} without the stem, the options or the illustration. The three
 * screens that show "answered 7 of 20" as a grid of numbered chips need a position, a
 * label and nothing else, and building that from the full projection would ship every
 * question's image bytes to render a row of numbers. The live monitor pays the same cost on
 * every push, which is where it would actually hurt.
 *
 * <p>Same guarantee as its bigger sibling and for the same reason: there is nowhere here to
 * put which answer is right, and the query behind it does not select {@code correct_answer}.
 *
 * @param questionVersionId the pinned version, which is what an answer row keys on
 * @param displayId         the 5-digit id students quote (S-8), shown on a chip's tooltip
 * @param ordinal           position in the paper, 1-based, which is the chip's label
 * @param points            what it is worth, for the "unanswered score 0" note (F6.9)
 */
public record QuestionOutline(long questionVersionId, String displayId, int ordinal, int points) {
}
