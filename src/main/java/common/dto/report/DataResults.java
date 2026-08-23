package common.dto.report;

import java.io.Serializable;
import java.util.List;

/**
 * The answer to {@code DATA_RESULTS_GET}: every closed sitting in the school (Common tier,
 * E15.2 — F9.3, F8.5, H15.2 ⚑).
 *
 * <p><b>{@link ReportRow} is reused unchanged</b>, and that is the whole design of this record.
 * A sitting the principal browses and a sitting a report compares are the same thing seen twice:
 * the same four-character code, the same released version's name, the same participant count and
 * the same frozen statistics. A second row record would be a second place for a divisor, a pass
 * mark or a decile width to be chosen, which is precisely what the reports contract's section 4
 * refused when it reused {@code ResultStatistics} rather than restating it.
 *
 * <p>The consequence, stated so nobody has to rediscover it: a sitting appears here only when it
 * is <b>CLOSED with its statistics frozen</b>. Scheduled and live sittings have no results yet, a
 * closed one whose grading never finished has none either, and a cancelled one was never sat
 * (H15.2 ⚑). The screen says so once, above the table, rather than showing four kinds of blank
 * row for a reader to interpret.
 *
 * <p>Newest first, which is the opposite of {@link ReportResult}'s ordering and is deliberate: a
 * report is a trend and reads left to right from the oldest, while a browse is a filing cabinet
 * and the thing being looked for is usually the most recent.
 *
 * @param sittings the closed sittings, newest first; never {@code null}, defensively copied
 */
public record DataResults(List<ReportRow> sittings) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** A school where nothing has been sat and fully marked yet. */
    public static final DataResults EMPTY = new DataResults(List.of());

    public DataResults {
        // List.copyOf yields an immutable, Serializable list - safe on the wire.
        sittings = sittings == null ? List.of() : List.copyOf(sittings);
    }

    /** @return {@code true} when nothing has closed with statistics yet. */
    public boolean isEmpty() {
        return sittings.isEmpty();
    }
}
