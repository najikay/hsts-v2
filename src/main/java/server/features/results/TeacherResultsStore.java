package server.features.results;

import java.util.function.Function;

/**
 * The seam between the teacher's results screen and the database (Logic tier, E14.1).
 *
 * <p>One method, handing out a {@link TeacherResultsData} for the length of one transaction —
 * the {@code ExamStore} shape (ADR-002), reused rather than re-invented. Both verbs of E14
 * read several facts that have to agree with each other (which exams are hers, which sittings
 * those have, how many sat each one), and reading them across separate transactions is how a
 * screen ends up showing a participant count that belongs to a different moment than the row
 * it is printed on.
 *
 * <p>Being an interface is also what lets every scoping rule in
 * {@link TeacherResultsService} — including S-35, which is the defence-critical one — be
 * proven against an in-memory fake with no database at all.
 */
@FunctionalInterface
public interface TeacherResultsStore {

    /**
     * Runs one unit of work in one transaction and returns its result.
     *
     * @param work what to do; the {@link TeacherResultsData} it receives is valid only for
     *             this call
     * @param <T>  the result type
     * @return whatever the work returned
     */
    <T> T inTx(Function<TeacherResultsData, T> work);
}
