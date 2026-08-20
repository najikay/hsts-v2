/**
 * The take-exam and live-monitoring wire model (Common tier, E10/E11 — F6/F7).
 *
 * <h2>The one rule this package exists to keep</h2>
 *
 * <p>v1 shipped correct answers to the student's client and the team's first defence
 * failed on it. The fix here is structural rather than careful: every type a student
 * receives while sitting an exam is a record in this package, and none of them has
 * anywhere to put a correct answer. {@link common.dto.exam.ExamQuestion} carries a stem
 * and four options, full stop; it is mapped from
 * {@code server.db.projections.TakeExamQuestion}, which is itself built by a JPQL
 * constructor expression that never selects {@code correct_answer}. So the answer key
 * does not leave the database, does not reach the server's memory on this path, and has
 * no field to land in if it did.
 *
 * <p>That is enforced, not asserted: {@code server.db.repos.ExamWireLeakGuardTest} scans
 * every record in this package for a component whose <em>name</em> reads like an answer
 * key and fails the build on a match. Adding correctness here is therefore a deliberate
 * act that breaks a red test, not an oversight in a mapper.
 *
 * <h2>The other rule: the server owns time</h2>
 *
 * <p>Nothing in this package lets a client decide anything about the clock.
 * {@link common.dto.exam.AttemptTiming} travels on every response and every push that
 * could move a deadline, and it always carries the server's own {@code now} alongside the
 * deadline, so the client renders remaining time rather than counting it. A client whose
 * laptop slept, whose clock is skewed, or which was offline for the whole extension is
 * corrected by the next message it receives.
 *
 * <p>The full verb list, payload shapes and error codes are documented in
 * {@code docs/contracts/EXAM_WIRE_CONTRACT.md}.
 */
package common.dto.exam;
