/**
 * The exam-approval wire model (Common tier, E8 — F4).
 *
 * <h2>The one rule this package exists to keep</h2>
 *
 * <p>v1's coordinator approved exams she could not see, and the team's first defence failed
 * on it. The fix here is structural rather than a new screen:
 * {@link common.dto.approval.ExamPreview} carries the paper as
 * {@code List<common.dto.exam.ExamQuestion>} — <b>the student's own wire type</b>, built by
 * the same no-correctness projection a real attempt is built from. A coordinator's preview
 * and a student's paper are therefore the same data rendered by the same component, and
 * "she sees exactly what the student sees" is a property of the types rather than a promise
 * about a layout.
 *
 * <h2>The other rule: correctness has exactly one door, and it is labelled</h2>
 *
 * <p>A coordinator is staff and must see the answer key to do her job, so this package does
 * carry one. It carries it in exactly one place — {@link common.dto.approval.PreviewAnswerRow},
 * fenced inside {@link common.dto.approval.TeacherOnlyBlock} — reached by exactly one verb,
 * {@code EXAM_PREVIEW_GET}, behind {@code requireRole(COORDINATOR)} plus
 * {@code requireCoordinatorOf} on the exam's subject (or the version's own author). The
 * repository read behind it is named {@code findAnswerKeyForAuthoring} on E2.12's convention,
 * so {@code CorrectnessLeakGuardTest} stays truthful about which audience each key-bearing
 * read serves.
 *
 * <p>Note what this package does <b>not</b> do: it does not add a field to
 * {@code common.dto.exam}. That package is scanned by {@code ExamWireLeakGuardTest} and a
 * correctness-shaped component there fails the build. Keeping the staff-only block in a
 * separate package beside the student types, rather than as optional fields inside them, is
 * what keeps that guard meaningful instead of suppressed.
 *
 * <h2>Optimistic locking is on the wire on purpose</h2>
 *
 * <p>{@code status} is the one mutable field of an otherwise immutable version row, which is
 * why {@code exam_versions} carries {@code lock_version} (ARCHITECTURE §5). Every decision
 * request echoes the {@code lockVersion} its screen was rendered from, so a decision taken
 * against a row that has since moved — superseded by a newer submission, or already decided
 * in another window — is refused with {@code CONFLICT} and a sentence saying to open it
 * again, rather than silently overwriting.
 *
 * <p>The full verb list, payload shapes and error codes are documented in
 * {@code docs/contracts/APPROVAL_WIRE_CONTRACT.md}.
 */
package common.dto.approval;
