/**
 * The release-manager wire model (Common tier, E9 — F5).
 *
 * <h2>Why this is not {@code common.dto.exam}</h2>
 *
 * <p>That package is the <b>student's</b> wire and one package-wide guarantee holds over
 * every record in it: nothing there can carry a correct answer, enforced by a scan
 * ({@code ExamWireLeakGuardTest}). This package is the opposite audience. Every type here
 * travels only to a teaching role, behind a verb that is role-gated and ownership-gated, and
 * one of them ({@link common.dto.release.ReleaseRow}) carries the 4-character entry code,
 * which S-17 says students learn by ear and never in the app. Putting a teacher-only record
 * that carries an exam code into the package whose defining property is "safe to send to a
 * student sitting an exam" would weaken the sentence that package is documented by.
 *
 * <p>The two packages still meet in one place, deliberately:
 * {@link common.dto.exam.MonitorCounts} is reused rather than re-declared, because the
 * participation numbers on a release row and the ones at the top of the live monitor are the
 * same three derived counts (S-21, §5). A parallel {@code ReleaseCounts} would be a second
 * definition of the same thing, and the day the two disagreed nobody would know which was
 * right.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <ul>
 *   <li><b>No teacher id in any request.</b> Who is releasing, cancelling or closing is the
 *       session's answer (P-5), so no payload has a field a client could put a colleague's
 *       id into.</li>
 *   <li><b>No client-supplied code.</b> The server generates it, because only the server can
 *       check it is not already in use by a release students might still be sitting
 *       (C-1, §5's service rule).</li>
 *   <li><b>No scores and no statistics.</b> A release row stops at "how many sat it"; the
 *       figures are E12's, frozen when the last grade is approved, and read by E14.</li>
 * </ul>
 *
 * <p>The verbs, payload shapes and error codes are documented as amendments A3 to A7 of
 * {@code docs/contracts/EXAM_WIRE_CONTRACT.md}.
 */
package common.dto.release;
