/**
 * The E12/E13 grading and results wire contract (Common tier).
 *
 * <h2>Frozen</h2>
 *
 * <p>Every type in this package is specified by
 * {@code docs/contracts/GRADING_WIRE_CONTRACT.md}, <b>frozen 2026-08-20</b>. That file is
 * authoritative: the record names, their component names, their order and their types are the
 * wire, and Java serialization reads records back through their canonical constructor, so a
 * rename or a retype here is a protocol break between two separately-built JARs rather than a
 * refactor. Changes are additive only (a new optional field, a new verb), and anything else
 * needs a lead decision recorded in the contract file first.
 *
 * <p>The seven verbs these types travel on live in
 * {@link common.protocol.Verb}, grouped under its "Grading &amp; results (E12/E13)" section,
 * each one javadoc'd with the caller role and the payload types the contract table gives it.
 *
 * <h2>What these records do, and what they deliberately do not</h2>
 *
 * <p>They are contract artifacts, nothing more. Each one null-checks the references it cannot
 * be meaningful without and defensively copies every list, exactly as
 * {@code common.dto.notify} and {@code common.dto.lock} do, because those two rules also have
 * to hold on the receiving side of the wire and a compact constructor is the only code that
 * runs there.
 *
 * <p><b>Range and blank validation is not here.</b> A score outside 0..100 and a blank
 * override justification are {@code VALIDATION} answers with a sentence for the teacher, not
 * {@link IllegalArgumentException}s thrown inside a deserialization on a socket read thread,
 * and the handler is the only layer that can tell the difference between "this client sent
 * nonsense" and "this user typed nothing". Those checks belong to Member B's E12 handlers and
 * are specified in the contract's error-code section.
 *
 * <h2>The one rule this package does enforce</h2>
 *
 * <p>{@link common.dto.grading.MyGrades} strips {@code overrideReason} from every row it
 * carries. The contract states that the justification is teacher and audit material and never
 * reaches the student wire; making that structural here means the student list cannot leak it
 * even if a future handler builds its rows from a teacher-side query.
 *
 * <h2>Not implemented here</h2>
 *
 * <p>Services, handlers and client screens are Member B's E12/E13 epics. This package and the
 * verb section are the contract only, so both members can build against one shape that is
 * already compiled and tested.
 *
 * @see common.protocol.Verb
 */
package common.dto.grading;
