/**
 * The release manager: taking an exam out of the drawer and putting it back (Logic tier,
 * E9 — F5).
 *
 * <p>Five verbs, one scheduled check, and one entity it shares with the take-exam feature.
 * The split between the two is worth stating, because they act on the same
 * {@code exam_executions} row from opposite ends:
 *
 * <ul>
 *   <li><b>E9, here.</b> A release exists, has a window, has a code, and moves through
 *       {@code SCHEDULED → LIVE → CLOSED} (or is called off). Teacher-facing, every verb
 *       role-gated and ownership-gated.</li>
 *   <li><b>E10/E11, {@code server.features.exam}.</b> What happens inside a live one:
 *       attempts, answers, timers, extensions, monitoring. Student-facing, plus the
 *       teacher's live monitor.</li>
 * </ul>
 *
 * <p>They meet at exactly one seam and it points one way.
 * {@code ExecutionCloseService.close} was built by E11 with no verb registered against it,
 * because closing was E9's to own; {@link server.features.release.ReleaseService} registers
 * that verb and {@link server.features.release.ReleaseScheduler} calls the same method when
 * a window runs out. So the two ways a release can end — a teacher pressing the button and
 * the clock arriving — are one code path, force-submitting stragglers through the same
 * expiry the attempt timers use. F5.5's "behaves exactly like time expiry for active
 * students" is that reuse, not a second implementation written to the same specification.
 *
 * <p>The wire model is {@code common.dto.release}, documented as amendments A3 to A7 of
 * {@code docs/contracts/EXAM_WIRE_CONTRACT.md}.
 */
package server.features.release;
