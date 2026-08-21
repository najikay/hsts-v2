/**
 * The E14 teacher results and statistics wire contract (Common tier).
 *
 * <h2>Draft</h2>
 *
 * <p>Every type in this package is specified by
 * {@code docs/contracts/RESULTS_WIRE_CONTRACT.md}, marked <b>DRAFT</b> until the lead freezes
 * it. Once it is frozen the same rule applies as to {@code common.dto.grading}: record names,
 * component names, their order and their types <em>are</em> the wire, Java serialization reads
 * records back through their canonical constructor, and a rename here is a protocol break
 * between two separately-built JARs rather than a refactor.
 *
 * <p>The two verbs these types travel on live in {@link common.protocol.Verb}, grouped under
 * its "Teacher results &amp; statistics (E14)" section.
 *
 * <h2>What is reused rather than redefined</h2>
 *
 * <p>Per-student rows are {@link common.dto.grading.StudentGradeRow}, unchanged. E14 is the
 * teacher path, so {@code overrideReason} is populated here — the structural stripping in
 * {@link common.dto.grading.MyGrades} guards the <em>student</em> containers, and this package
 * deliberately adds no third row shape for the same eight facts.
 *
 * <h2>The one rule this package exists to protect</h2>
 *
 * <p>{@link common.dto.results.ResultStatistics} is a <b>carrier of stored numbers</b>. F8.5
 * freezes an execution's statistics when its last grade is approved, with a
 * <b>population</b> standard deviation (divisor {@code n}) and a pass mark of 55; every field
 * of this record is read out of {@code exam_executions.stats} and none of them is derived from
 * the rows travelling beside it. A second computation with a different divisor or a different
 * threshold produces numbers that look plausible and disagree with the seed, which is the exact
 * failure the frozen column exists to prevent.
 *
 * @see common.protocol.Verb
 * @see common.dto.grading.StudentGradeRow
 */
package common.dto.results;
