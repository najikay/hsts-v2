/**
 * The E6 question bank wire contract (Common tier — F2).
 *
 * <h2>Specified elsewhere, implemented here</h2>
 *
 * <p>Every new type in this package is specified by
 * {@code docs/contracts/BANK_WIRE_CONTRACT.md}, with the lead's rulings of 2026-08-21 applied.
 * That file is authoritative: record names, component names, their order and their types
 * <em>are</em> the wire, because Java serialization reads a record back through its canonical
 * constructor — so a rename or a retype here is a protocol break between two separately-built
 * JARs rather than a refactor. Changes are additive only, and anything else needs a lead
 * decision recorded in the contract file first.
 *
 * <p>The seven verbs these types travel on live in {@link common.protocol.Verb}, grouped under
 * its "Question bank (E6)" section, each javadoc'd with its caller roles, its payload and its
 * response. The handlers are {@code server.features.bank.QuestionService} and are Member A's
 * E6 epic; this package and the verb section are the contract only, so both members build
 * against one shape that is already compiled and tested.
 *
 * <h2>The rule this package exists to enforce</h2>
 *
 * <p><b>The answer key travels to staff who author, and to nobody else, and the build says so.</b>
 *
 * <p>This is the mirror image of {@code common.dto.exam}. There,
 * {@link common.dto.exam.ExamQuestion} has nowhere to put a correct answer because its recipient
 * is a student sitting the paper, and the guarantee is "the key is not on this wire". Here the
 * recipient is a teacher editing the question, so the key <b>must</b> travel: an editor that
 * cannot show which answer is right cannot be used to author. The safety property is therefore
 * the weaker "no key on a path a student can reach", and a weaker claim needs a stronger guard
 * to be worth anything.
 *
 * <p>{@code server.db.repos.BankWireLeakGuardTest} scans the compiled package and fails the
 * build on any key-bearing type that is not explicitly licensed, split by direction:
 *
 * <ul>
 *   <li><b>inbound, uninteresting</b> — {@link common.dto.bank.QuestionDraft} and
 *       {@link common.dto.bank.QuestionEdit}. The teacher is submitting the key; there is
 *       nothing to leak, because the server already holds every key in the bank.</li>
 *   <li><b>outbound, licensed</b> — {@link common.dto.bank.QuestionDetail} and
 *       {@link common.dto.bank.QuestionVersionDetail}. Both go out on staff-only, scoped verbs,
 *       to a reader who is authoring or reviewing what she authored. The principal is included
 *       by the lead's ruling: one detail type for every staff reader, rather than a second
 *       keyless projection for a distinction whose threat model is students and not staff.</li>
 * </ul>
 *
 * <p>The split is asserted, not merely written down, so a future outbound DTO cannot be waved
 * through by pointing at the inbound pair. Everything else in the package must be keyless, and
 * {@link common.dto.bank.BankQuestionRow} above all: the list is the high-volume payload and the
 * one that ends up in screenshots, so the key is fetched a question at a time by a verb that
 * names a single question.
 *
 * <h2>The qualification that used to live here, and why it is gone</h2>
 *
 * <p>{@code Question} and {@code QuestionUpdate} were the legacy prototype pair, serving
 * {@code GET_ALL_QUESTIONS} and {@code UPDATE_QUESTION}. {@code Question.answer} was a real answer
 * key invisible to the scan twice over: the name {@code answer} matches nothing in
 * {@code CorrectnessNames} (deliberately, since {@code answer1..answer4} are options a student is
 * meant to see), and {@code Question} was a mutable class rather than a record. They sat on a
 * named, dated allow-list entry in the guard so that "the build says so" was qualified in the one
 * place a reader would look rather than being quietly false.
 *
 * <p><b>The retirement PR deleted both types, both verbs and the screen that sent them</b>, and
 * took the allow-list entry with them. Every type left in this package is a record and every one
 * of them is inside the scan, so the claim is now unqualified: the four licensed types are the
 * only key carriers here and the build checks it.
 *
 * <h2>Conventions, and where the contract left a shape open</h2>
 *
 * <p>Where the draft did not fix a detail, this package follows the analogous choice in
 * {@code common.dto.grading} rather than inventing a second convention:
 *
 * <ul>
 *   <li><b>Range and blank validation is not here.</b> A {@code correctAnswer} of 7, three
 *       answers instead of four, a 5000-character stem: all of them are {@code VALIDATION}
 *       answers naming the offending field, from {@code QuestionValidator} in the handler, not
 *       {@link java.lang.IllegalArgumentException}s thrown inside a deserialization on a socket
 *       read thread. Only the handler can tell "this client sent nonsense" from "this teacher
 *       typed nothing", and only the handler can answer in a sentence.</li>
 *   <li><b>Null checks follow the direction of travel.</b> Outbound records the server builds
 *       ({@code BankPage}, {@code BankQuestionRow}, {@code QuestionDetail},
 *       {@code QuestionVersionDetail}, {@code VersionHistory}, {@code BlockingExam},
 *       {@code QuestionImage}) null-check the references they cannot be meaningful without,
 *       because a null in one of them is a server bug and should surface as one. Inbound
 *       payloads the client sends ({@code QuestionRequest}, {@code QuestionDraft},
 *       {@code QuestionEdit}, {@code QuestionDeleteRequest}, {@code QuestionImageRequest},
 *       {@code BankListRequest}) normalise instead of throwing, for the reason above.</li>
 *   <li><b>Every list is defensively copied</b> with {@code List.copyOf} after folding
 *       {@code null} to empty, and every {@code byte[]} is cloned in and out, exactly as
 *       {@code common.dto.exam} and {@code common.dto.grading} do. These rules have to hold on
 *       the <em>receiving</em> side too, and a compact constructor is the only code that runs
 *       there.</li>
 *   <li><b>Records carrying a {@code byte[]} override {@code equals}, {@code hashCode} and
 *       {@code toString}</b>: a generated {@code equals} compares arrays by reference, so two
 *       values built from identical inputs would never be equal, and a generated
 *       {@code toString} would print a megabyte of picture into a log line.</li>
 *   <li><b>Instants are UTC</b> (ADR-010), and {@code lastVersionAt} is named for what the schema
 *       actually has rather than for the {@code updated_at} column it does not.</li>
 *   <li><b>Truncation is the server's</b> (lead's ruling): {@code BankQuestionRow.text()} is a
 *       cut stem and says so; {@code QuestionDetail.text()} is the whole thing.</li>
 *   <li><b>Scope is the server's</b> (lead's ruling): a coordinator reaches every course of the
 *       subject she coordinates, not the courses she teaches, and
 *       {@code BankListRequest.courseCode} is intersected with the caller's reachable set rather
 *       than trusted. Nothing about that is expressible in a DTO, which is why it lives in the
 *       verb javadoc and in the handler.</li>
 * </ul>
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p><b>No lock field, anywhere.</b> The live "Editing · &lt;name&gt;" badge on a bank row is
 * E18.8's and rides {@code LOCK_WATCH} / {@code LOCKS_SNAPSHOT} / {@code PUSH_LOCK_CHANGED}
 * (F10.0). Duplicating it here would let a stale badge disagree with a real lock and would make
 * looking at a list of forty rows contend for forty locks.
 *
 * <p><b>No {@code lockVersion}.</b> {@code questions} is the identity row and never changes when
 * a version is inserted, so its {@code @Version} column never increments;
 * {@link common.dto.bank.QuestionEdit#baseVersionNo()} is the token that actually catches a stale
 * editor.
 *
 * <p><b>No caller id in any payload</b>, because a user id in one of these could only ever be
 * somebody else's (P-5). Authorship and scope are both resolved from the session.
 *
 * <p><b>No student type.</b> The one student-reachable read of the bank is the study bot's, which
 * is not here: it goes through {@code QuestionRepository.findBankForBot} and
 * {@code server.db.projections.BotBankQuestion} (S-28, F12.8), and is keyless.
 *
 * @see common.protocol.Verb
 * @see common.dto.exam
 */
package common.dto.bank;
