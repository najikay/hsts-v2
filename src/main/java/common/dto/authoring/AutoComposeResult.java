package common.dto.authoring;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * The answer to {@code EXAM_AUTO_COMPOSE}: a proposal, or a report saying exactly what is
 * missing (Common tier, E7.4 — F3.2, F3.3 ⚑).
 *
 * <h2>The invariant, and why it is enforced rather than documented</h2>
 *
 * <p><b>Exactly one of the two lists is non-empty, and {@link #feasible()} says which.</b>
 * Anything else throws in the compact constructor, on both sides of the wire, because a record
 * is deserialized through its canonical constructor.
 *
 * <p>Both-empty is the case that matters: an auto-composition that selected nothing and
 * explained nothing is precisely the failure F3.3 exists to prevent, and it must not be
 * representable. A teacher facing a blank dialog with no sentence in it is the outcome the whole
 * of contract section 7 is arranged against, and leaving it merely undocumented would mean the
 * first time it happened would be on the demo stage. Both-populated is refused for the mirror
 * reason: a proposal that also reports a shortfall is two answers, and the client would have to
 * pick one.
 *
 * <p>This is the one place in this package where an outbound record enforces a business rule
 * rather than a null check, and it is deliberate. The rule is about the server's own answer, not
 * about anything a client sent, so throwing surfaces a server bug at the moment it is built —
 * there is no socket read thread to kill and no teacher to refuse, which is exactly why the
 * inbound records in this package do the opposite (E1.11).
 *
 * <h2>Nothing is written either way</h2>
 *
 * <p>No exam, no version, no allocated serial. The verb is a pure read, which is what makes
 * T-3.5's "<b>No exam is created</b>" true by construction rather than by a rollback that has to
 * work. A feasible proposal is handed to {@code EXAM_CREATE} by the client if she likes it.
 *
 * <h2>When it is feasible</h2>
 *
 * <p>{@link #questions()} arrive already totalling {@link ExamCreateRequest#POINTS_TOTAL},
 * distributed as evenly as the count allows with the remainder on the earliest questions — three
 * questions become 34, 33, 33 — so the auto path is savable in one click (T-3.4) and every
 * proposal already satisfies contract section 1. {@code ord} is the selection order, 1-based,
 * and no question appears twice across quotas or within one.
 *
 * <h2>When it is not</h2>
 *
 * <p>{@link #shortfalls()} holds <b>every</b> shortfall, not the first one. A teacher short on
 * both Algebra Hard and Recursion Hard gets two lines: first-failure reporting turns a report
 * into an error and makes her discover the second problem by fixing the first, which on a demo
 * stage is a very long silence.
 *
 * @param feasible   whether a paper could be composed; redundant with the two lists on purpose,
 *                   so a client can branch on a boolean and the constructor can check that the
 *                   boolean and the lists agree
 * @param questions  the proposal, or empty; never {@code null}
 * @param shortfalls the report, or empty; never {@code null}
 */
public record AutoComposeResult(boolean feasible,
                                List<ComposedQuestion> questions,
                                List<Shortfall> shortfalls) implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Enforces the contract's invariant on every construction, deserialization included.
     *
     * @throws NullPointerException     if either list is {@code null}, which is a server bug
     * @throws IllegalArgumentException if both lists are empty, both are populated, or
     *                                  {@code feasible} disagrees with them
     */
    public AutoComposeResult {
        questions = List.copyOf(Objects.requireNonNull(questions, "questions"));
        shortfalls = List.copyOf(Objects.requireNonNull(shortfalls, "shortfalls"));

        boolean proposed = !questions.isEmpty();
        boolean reported = !shortfalls.isEmpty();
        if (proposed == reported) {
            throw new IllegalArgumentException(proposed
                    ? "an auto-compose result carries a proposal or a shortfall report, never both"
                    : "an auto-compose result that selected nothing and explained nothing is the "
                            + "failure F3.3 exists to prevent");
        }
        if (feasible != proposed) {
            throw new IllegalArgumentException(
                    "feasible=" + feasible + " disagrees with the lists: "
                            + questions.size() + " questions, " + shortfalls.size()
                            + " shortfalls");
        }
    }

    /**
     * @param questions the proposal, already totalling {@link ExamCreateRequest#POINTS_TOTAL}
     * @return a feasible result
     */
    public static AutoComposeResult composed(List<ComposedQuestion> questions) {
        return new AutoComposeResult(true, questions, List.of());
    }

    /**
     * @param shortfalls every shortfall, not the first one (section 7.2)
     * @return an infeasible result, which is a report and not an error
     */
    public static AutoComposeResult infeasible(List<Shortfall> shortfalls) {
        return new AutoComposeResult(false, List.of(), shortfalls);
    }

    /** @return how many questions the composer proposed; zero when it could not. */
    public int questionCount() {
        return questions.size();
    }

    /** @return how many distinct things are missing; zero when the request was feasible. */
    public int shortfallCount() {
        return shortfalls.size();
    }

    /**
     * @return the points on the proposal, which section 7.4 fixes at
     *         {@link ExamCreateRequest#POINTS_TOTAL} for a feasible result and 0 otherwise
     */
    public int totalPoints() {
        int total = 0;
        for (ComposedQuestion question : questions) {
            total += question.points();
        }
        return total;
    }
}
