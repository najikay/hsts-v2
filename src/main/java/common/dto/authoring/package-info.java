/**
 * The E7 exam builder wire contract (Common tier — F3).
 *
 * <h2>Specified elsewhere, implemented here</h2>
 *
 * <p>Every type in this package is specified by
 * {@code docs/contracts/EXAM_BUILDER_WIRE_CONTRACT.md}, with the lead's rulings of 2026-08-23
 * applied. That file is authoritative: record names, component names, their order and their
 * types <em>are</em> the wire, because Java serialization reads a record back through its
 * canonical constructor — so a rename or a retype here is a protocol break between two
 * separately-built JARs rather than a refactor. Changes are additive only once the contract
 * header says FROZEN, and anything else needs a lead decision recorded in that file first.
 *
 * <p>The seven verbs these types travel on live in {@link common.protocol.Verb}, grouped under
 * its "Exam builder (E7)" section, each javadoc'd with its caller roles, its payload, its
 * response and its error codes. The handlers are
 * {@code server.features.exambuild.ExamHandlers} over {@code ExamService},
 * {@code ExamValidator} and {@code AutoComposer}, and they are Member A's E7 epic; this package
 * and the verb section are the contract only, so both members build against one shape that is
 * already compiled and tested.
 *
 * <p><b>Named {@code authoring}, not {@code exam}</b> (lead's ruling 1). {@code common.dto.exam}
 * is E10/E11's take-exam surface, and reusing it would put a student's paper and a teacher's
 * composition in one package — two audiences whose whole relationship is that one of them must
 * never see what the other is holding.
 *
 * <h2>The rule this package exists to enforce</h2>
 *
 * <p><b>An exam version that exists is a releasable object, or it is a DRAFT nobody has
 * submitted.</b> There is no third state.
 *
 * <p>The take-exam tier (E10), the grading tier (E12) and the release manager (E9) all read
 * {@code exam_version_questions} and all assume its points sum to 100, because a paper that
 * cannot total 100 cannot be scored out of 100. That assumption <b>cannot be a DDL
 * constraint</b> — a table-level {@code CHECK} cannot span rows, and {@code V3__exams.sql} says
 * so in a comment — so it is enforced on the write path with no exceptions, and
 * {@link common.dto.authoring.ExamCreateRequest#POINTS_TOTAL} is the one number both tiers count
 * to.
 *
 * <p>Two shapes follow from it rather than being chosen:
 *
 * <ul>
 *   <li>{@link common.dto.authoring.ExamCreateRequest} <b>carries the whole composition</b>.
 *       Creating an empty exam and filling it in later would put an unsatisfiable row in
 *       {@code exam_versions} for as long as the teacher is thinking, and T-3.5 says in plain
 *       words that a refused auto-composition creates <b>no exam</b>. <b>There is no
 *       work-in-progress row</b>: a half-composed exam lives in the client and nowhere else,
 *       which is what F3.1's "save blocked (not warned)" already required.</li>
 *   <li>{@code EXAM_AUTO_COMPOSE} <b>writes nothing at all</b>. It is a pure read answering with
 *       {@link common.dto.authoring.AutoComposeResult}, which is what makes "no exam is created"
 *       true by construction rather than by a rollback that has to work.</li>
 * </ul>
 *
 * <h2>The answer key is not here, and the guard says so</h2>
 *
 * <p>This is the mirror of {@code common.dto.bank}, which must carry the key because an editor
 * that cannot show which answer is right cannot be used to author.
 * {@link common.dto.authoring.ComposedQuestion} carries a <b>stem and nothing else a student
 * could not see</b>: no answers, no key, no correctness of any kind. The builder shows which
 * questions are on the paper and what they are worth; it is not a preview of the paper, and a
 * teacher who wants to read a question opens it in the bank under
 * {@code QUESTION_GET}'s existing staff-only licence.
 *
 * <p>The consequence is the claim the contract's section 9 makes and this package keeps true:
 * <b>E7 adds no type to the correctness boundary and the leak guard's licensed list does not
 * grow.</b> {@code common.dto.WireDtoLeakGuardTest} scans every wire package including this one,
 * and no component here appears on its licence list — not because a licence was written, but
 * because there is nothing to license.
 *
 * <h2>Conventions, and the one that has burned reviews</h2>
 *
 * <ul>
 *   <li><b>Null checks follow the direction of travel</b>, exactly as
 *       {@code common.dto.bank} states it. Outbound records the server builds
 *       ({@link common.dto.authoring.ExamList}, {@link common.dto.authoring.ExamListRow},
 *       {@link common.dto.authoring.ExamVersionRow},
 *       {@link common.dto.authoring.ExamComposition},
 *       {@link common.dto.authoring.ComposedQuestion},
 *       {@link common.dto.authoring.AutoComposeResult}) null-check aggressively, because a null
 *       in one of them is a server bug and should surface as one at build time. Inbound payloads
 *       the client sends ({@link common.dto.authoring.ExamVersionRequest},
 *       {@link common.dto.authoring.ExamVersionAction},
 *       {@link common.dto.authoring.ExamCreateRequest},
 *       {@link common.dto.authoring.ExamVersionSave},
 *       {@link common.dto.authoring.AutoComposeRequest},
 *       {@link common.dto.authoring.TopicQuota}, {@link common.dto.authoring.QuestionPin})
 *       <b>normalise and never throw</b>.
 *       {@link common.dto.authoring.Shortfall} is outbound with no null check at all, and that is
 *       not an oversight: both of its nullable fields carry meaning, mirroring
 *       {@code TopicQuota}'s.</li>
 *   <li><b>An inbound list copy is tolerant of a null element</b> and is deliberately not
 *       {@link java.util.List#copyOf}. That constructor runs on the server's socket read thread
 *       during deserialization, where any throw kills the connection (E1.11, found by Member A
 *       on 2026-08-21): a null element must survive construction so {@code ExamValidator} can
 *       refuse it with a named {@code VALIDATION} sentence instead of the teacher losing her
 *       composition to a silent disconnect. Outbound lists use the strict copy, because there a
 *       null element is a defect in the assembler rather than a payload to refuse politely.</li>
 *   <li><b>No business rule is in a compact constructor.</b> Points summing to 100, no duplicate
 *       question, distinct topics per request, name and duration and text lengths: all of them
 *       are {@code ExamValidator}'s, shared by create and save so the two cannot diverge (E7.8),
 *       and each answers {@code VALIDATION} naming the field. The shape constants the validator
 *       cites live here so both tiers count to the same numbers —
 *       {@code MAX_NAME_LENGTH}, {@code MIN_DURATION_MINUTES}, {@code MAX_DURATION_MINUTES},
 *       {@code MAX_TEXT_LENGTH} and {@code POINTS_TOTAL} on the two write requests,
 *       {@code MIN_POINTS} and {@code MAX_POINTS} on {@link common.dto.authoring.QuestionPin}.
 *       The <b>one</b> exception is
 *       {@link common.dto.authoring.AutoComposeResult}'s invariant, which is a rule about the
 *       server's own answer and not about anything a client sent.</li>
 *   <li><b>{@code strip()}, never {@code trim()}</b>, imported verbatim from the bank contract
 *       including its reason and its measured limit: {@code trim} cuts only characters at or
 *       below U+0020, so a course code carrying a Unicode space above it matches the row in SQL
 *       (a PAD SPACE {@code CHAR(2)} column) while failing Java equality against the reachable
 *       set. {@code strip} closes most of that gap and not all of it — the non-breaking spaces
 *       U+00A0, U+2007 and U+202F are exactly the ones
 *       {@link java.lang.Character#isWhitespace(char)} rejects, and a code padded with one of
 *       them is refused downstream rather than matched, which is the safe direction. Optional
 *       texts are stripped and then folded blank-to-{@code null}, so "she wrote nothing" has one
 *       representation.</li>
 *   <li><b>Two enums are reused rather than redeclared.</b>
 *       {@link common.dto.bank.Difficulty}, because the criteria grid is a statement about bank
 *       rows and must speak the bank's vocabulary; and
 *       {@link common.dto.approval.ApprovalState}, because {@code exam_versions.status} is one
 *       column with one meaning and E8 already bridges it with an exhaustive switch that makes a
 *       new member a compile error. An {@code ExamState} beside it would be a second bridge with
 *       no such property.</li>
 *   <li><b>Instants are UTC</b> (ADR-010).</li>
 *   <li><b>Truncation is the server's</b>: {@link common.dto.authoring.ComposedQuestion#text()}
 *       is a cut stem and says so, exactly as {@code BankQuestionRow.text()} is.</li>
 * </ul>
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p><b>No caller id in any payload.</b> Authorship is {@code CallerContext.userId()} (S-12), so
 * an exam cannot be created in somebody else's name and an edit cannot be attributed to somebody
 * else (P-5). The scope is <b>author-only</b> and narrower than "teaches the course" (lead's
 * ruling 2, following E14's frozen ruling): a coordinator's read of somebody else's exam is
 * E8's {@code EXAM_PREVIEW_GET} and nothing here widens it. The principal is absent entirely —
 * E15.2's {@code DATA_EXAMS_GET} already serves her the school's exams, and an authoring surface
 * is not a read of entered data.
 *
 * <p><b>No lock-holder field.</b> The builder's live "being edited by" state rides E18.8's
 * {@code LOCK_WATCH} / {@code LOCKS_SNAPSHOT} under the existing {@code EntityRef.EXAM_VERSION}
 * constant (F10.0). Two expressions of one fact drift, and viewing a list should never contend
 * for a lock. {@code lockVersion} is the optimistic token and a different thing entirely.
 *
 * <p><b>No formatted sentence on the infeasibility report</b> (lead's ruling 4).
 * {@link common.dto.authoring.Shortfall} is structural and {@code ExamCopy} composes the
 * sentence once, with the PRD's example pinned by a copy test. Carrying both would be two
 * expressions of one fact, and they would disagree.
 *
 * <p><b>No total field on {@link common.dto.authoring.TopicQuota}.</b> The total is derived from
 * the buckets in one place, so a request cannot carry a total that disagrees with its own
 * breakdown.
 *
 * <p><b>No exam delete, no bank browse verb, no release verb, no draft autosave.</b> Contract
 * sections 3 and 9. The picker in E7.12 is {@code BANK_LIST} from the frozen bank contract; an
 * APPROVED version is handed to E9, not released from here.
 *
 * @see common.protocol.Verb
 * @see common.dto.bank
 * @see common.dto.approval
 */
package common.dto.authoring;
