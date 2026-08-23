package server.features.reports;

import common.dto.report.ReportDimension;
import common.dto.report.ReportSubject;
import server.db.projections.ExecutionReport;

import java.util.List;
import java.util.Optional;

/**
 * One way of choosing which sittings a report compares (Logic tier, E15.3 ⚑ — F9.4, S-37).
 *
 * <p><b>This interface is the answer to "minimal development for new reports".</b> F9.4 does not
 * ask for three reports; it asks for one parameterised mechanism whose parameter is the
 * dimension, so that a fourth comparison is a new class implementing this and a line registering
 * it. Everything a report does that is <em>not</em> "which rows" — mapping frozen statistics onto
 * the wire, counting participants, pooling the deciles, weighting the mean, rendering the table
 * and the chart — is written once in {@link ReportEngine} and on one screen, and none of it knows
 * these three implementations exist.
 *
 * <h2>What an implementation answers, and what it must not</h2>
 *
 * <p>Three questions, all of them about population and labelling:
 *
 * <ol>
 *   <li>{@link #subjects} — what can be reported about, for the picker;</li>
 *   <li>{@link #subject} — one subject by its id, for the heading, and {@code empty()} when the
 *       id does not name one;</li>
 *   <li>{@link #executionsOf} — that subject's reportable sittings.</li>
 * </ol>
 *
 * <p>An implementation computes <b>no statistic</b>. It selects rows; the figures on them were
 * frozen when each sitting's last grade was approved (F8.5) and travel untouched. A strategy that
 * averaged anything would be a second place for a divisor to be chosen, which is the H14.4 ⚑ bug
 * class the frozen column exists to prevent.
 *
 * <p>An implementation also performs <b>no authorization</b>. The role gate lives on the verb
 * ({@code requireRole(PRINCIPAL)}), and the principal's scope is the whole school (spec 7.3.1,
 * F9.3), so there is no per-subject check for a strategy to forget. This is stated because the
 * absence of a check in a data-selecting class is normally worth a second look, and here it is
 * correct.
 *
 * <h2>Subject ids are the strategy's own</h2>
 *
 * <p>{@link ReportSubject#id()} is an opaque string as far as the engine, the wire and the screen
 * are concerned; the only code that interprets it is the strategy that issued it. That is why a
 * teacher's id and a course's code can travel through the same field, and why a fourth dimension
 * keyed on something else again — a subject, an exam, a year group — needs no wire change at all.
 * An id that does not parse is answered {@code empty()} rather than thrown: a malformed id is a
 * client that has been away for a schema change, not a server fault.
 */
public interface DimensionStrategy {

    /**
     * @return which dimension this strategy serves; the key it is registered under, so two
     *         strategies claiming the same dimension is a startup failure rather than a coin toss
     */
    ReportDimension dimension();

    /**
     * Everything this dimension can be reported about.
     *
     * <p>Includes subjects with nothing to compare, each carrying a count of zero. A picker that
     * hid them would answer "which teachers exist" with "which teachers have had an exam close",
     * and a principal looking for the first list would conclude the second was it.
     *
     * @param data this transaction's reads
     * @return the subjects, ordered for display; never null
     */
    List<ReportSubject> subjects(ReportData data);

    /**
     * One subject by the id the picker issued.
     *
     * @param data      this transaction's reads
     * @param subjectId the id, exactly as it travelled
     * @return the subject, or empty when the id names none — including when it is not even the
     *         right shape for this dimension
     */
    Optional<ReportSubject> subject(ReportData data, String subjectId);

    /**
     * The rows of a report about this subject.
     *
     * <p>Closed sittings with frozen statistics, oldest first. Cancelled sittings are excluded,
     * which is H15.2 ⚑ landing where it was written for; the exclusion is in the query rather
     * than here, so it cannot be forgotten by a fourth strategy that reuses the same read.
     *
     * @param data      this transaction's reads
     * @param subjectId the id, exactly as it travelled
     * @return its sittings; empty when there are none, or when the id names no subject
     */
    List<ExecutionReport> executionsOf(ReportData data, String subjectId);

    /**
     * Reads a user id out of a subject id.
     *
     * <p>Shared by the two person-keyed strategies, and the reason it is here rather than
     * copied: "an id that does not parse is an empty answer, not an exception on a socket
     * thread" is a decision, and one decision belongs in one place.
     *
     * @param subjectId the id, exactly as it travelled
     * @return the id, or empty when it is not a number
     */
    static Optional<Long> asUserId(String subjectId) {
        if (subjectId == null || subjectId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(subjectId.strip()));
        } catch (NumberFormatException notAnId) {
            return Optional.empty();
        }
    }
}
