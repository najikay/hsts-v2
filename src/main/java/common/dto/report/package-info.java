/**
 * The E15 principal report wire contract (Common tier, F9.4 / S-37).
 *
 * <h2>Draft</h2>
 *
 * <p>Every type in this package is specified by
 * {@code docs/contracts/REPORTS_WIRE_CONTRACT.md}, marked <b>DRAFT</b> until the lead freezes
 * it. Once it is frozen the same rule applies as to {@code common.dto.results}: record names,
 * component names, their order and their types <em>are</em> the wire, Java serialization reads
 * records back through their canonical constructor, and a rename here is a protocol break
 * between two separately-built JARs rather than a refactor.
 *
 * <p>The two verbs these types travel on live in {@link common.protocol.Verb}, grouped under its
 * "Principal reports (E15)" section, and both are gated on the {@code PRINCIPAL} role alone.
 *
 * <h2>One mechanism, parameterised</h2>
 *
 * <p>F9.4's requirement is not three reports; it is one report with a parameter, so that "a new
 * report type is a new strategy class and a menu entry, nothing else". This package is that
 * parameterisation on the wire: {@link common.dto.report.ReportDimension} names the comparison,
 * {@link common.dto.report.ReportSubject} is whatever it is about, and
 * {@link common.dto.report.ReportResult} is the answer whichever it was. Not one record here has
 * a teacher field, a course field or a student field; a fourth dimension adds a constant and
 * nothing else to this package.
 *
 * <h2>What is reused rather than redefined</h2>
 *
 * <p>Per-execution figures are {@link common.dto.results.ResultStatistics}, unchanged. E14's
 * contract said this would be so, and the reason is the rule below: a second statistics record
 * would be a second place for a divisor or a threshold to be chosen.
 *
 * <h2>The one rule this package inherits</h2>
 *
 * <p>Statistics travel exactly as they were frozen (F8.5): population σ with divisor {@code n},
 * pass mark 55, ten stored buckets. Nothing here recomputes them from grades.
 * {@link common.dto.report.ReportSummary} <em>aggregates</em> them — a weighted mean, a pooled
 * sum of squares, summed buckets and summed pass counts — which is arithmetic on stored numbers
 * and is the same move E14's {@code FrozenStatistics} makes when it reconstitutes a pass count
 * from a stored rate. The forbidden move is going back to the score rows, and no type here can
 * reach one.
 *
 * @see common.protocol.Verb
 * @see common.dto.results.ResultStatistics
 */
package common.dto.report;
