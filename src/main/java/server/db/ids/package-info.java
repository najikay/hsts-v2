/**
 * Display-id allocation for questions and exams (E2.14).
 *
 * <h2>MAX(serial) + 1, never COUNT + 1</h2>
 *
 * <p>ARCHITECTURE §5 states the rule and the reason: questions are soft-deleted (F2.5) and
 * keep their serial forever, so counting rows returns a number that is already taken the
 * moment anything has been deleted. {@code COUNT + 1} passes every single-threaded test on
 * an untouched database and then issues a duplicate on the first real one.
 *
 * <h2>How concurrency is handled</h2>
 *
 * <p>{@code MAX + 1} is a read followed by a write, which two transactions can interleave.
 * Allocation therefore takes a row lock on the parent {@code courses} row first
 * ({@code SELECT ... FOR UPDATE}), which serialises allocation <em>per course</em> — two
 * teachers authoring in different courses never wait for each other — and the unique
 * constraint on {@code display_id5}/{@code display_id6} remains as the backstop that turns
 * any remaining mistake into a loud failure instead of a duplicate id.
 *
 * <p>The alternative is a dedicated sequence table. It is more machinery for the same
 * guarantee, and it adds a row that can disagree with the data it describes. This choice is
 * flagged for the lead in the PR 2b report; reversing it is one method body.
 *
 * <h2>Width is a real constraint</h2>
 *
 * <p>{@code display_id5} is {@code CHAR(5)} = course(2) + serial(3) and {@code display_id6}
 * is {@code CHAR(6)} = subject(2) + course(2) + serial(2). The entity fields are {@code short}
 * and {@code byte}, which both accept values far past what the display id can hold, so
 * overflow is rejected explicitly here rather than silently producing a too-long id.
 */
package server.db.ids;
