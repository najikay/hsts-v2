package common.protocol;

/**
 * The protocol v2 operation vocabulary (Common tier) — one constant per
 * operation, grouped per feature (ARCHITECTURE §3).
 *
 * <p>A {@link Message} always carries exactly one verb. Request verbs travel
 * client → server and are answered with the same verb and {@code requestId};
 * {@code PUSH_*} verbs travel server → client unsolicited over the push channel
 * ({@code server.realtime.PushGateway}).
 *
 * <p>Verbs are only ever added, never renamed or removed — both tiers ship in
 * separate JARs and an unknown verb must stay a recoverable "unsupported verb"
 * error rather than a deserialization failure. Names are the whole wire
 * contract (Java serializes enums by name, never by ordinal), so new verbs are
 * inserted into their feature's group for readability rather than appended.
 */
public enum Verb {

    // ===================== Connection & session (E1/E5) =====================

    /** Authenticate a connection. Open (no session required) by definition. */
    LOGIN,

    /**
     * End the authenticated session for the calling connection.
     * Reserved: the handler lands with E5 (auth &amp; login).
     */
    LOGOUT,

    // ===================== Question bank (legacy prototype flow) ===========
    // Kept working verbatim through protocol v2 so the phase-3 demo never
    // regresses; E6 replaces them with the versioned bank verbs.

    /** List every question in the bank. Response payload: {@code List<Question>}. */
    GET_ALL_QUESTIONS,

    /** Persist an edited question. Request payload: {@code Question}. */
    UPDATE_QUESTION,

    // ===================== Question bank (E6) ==============================
    // The draft wire contract: docs/contracts/BANK_WIRE_CONTRACT.md, with the
    // lead's rulings of 2026-08-21 applied. Payload types live in
    // {@code common.dto.bank}; the handlers are
    // {@code server.features.bank.QuestionService}.
    //
    // EVERY verb here is staff-only, and the split is three mutating against four
    // read. The three that write are requireRole(TEACHER, COORDINATOR); the four
    // that read add PRINCIPAL, who gets a read-only bank browse (F9.3) and
    // literally zero mutating verbs.
    //
    // SCOPE IS PER ROLE AND IS RESOLVED SERVER-SIDE, NEVER FROM THE PAYLOAD:
    //   TEACHER     - questions in the courses she teaches (course_teachers), S-5
    //   COORDINATOR - questions in EVERY course of the subject she coordinates.
    //                 Not "courses I teach": rina.barak holds a coordinators row
    //                 for subject 10 and zero course_teachers rows, deliberately,
    //                 so an implementation deriving coordinator-ness from the
    //                 wrong table shows a starred demo account an empty bank
    //                 (roster decision 2026-08-20, lead's ruling 2026-08-21)
    //   PRINCIPAL   - every course, read-only (F9.3)
    // BANK_LIST intersects any course filter with that set rather than trusting
    // it, and a question outside it answers NOT_FOUND on every read verb.
    //
    // NOT_FOUND is the only answer for anything the caller cannot reach: unknown,
    // soft-deleted and out-of-scope are one answer, indistinguishable on purpose.
    // FORBIDDEN is for the ROLE check alone and never for scope, so a caller who
    // may not use a verb at all learns nothing about which questions exist (P-5).
    //
    // No payload carries a caller id - authorship is the session - and no payload
    // carries a lock field: the "being edited by" badges on a bank list ride
    // E18.8's LOCK_WATCH / LOCKS_SNAPSHOT / PUSH_LOCK_CHANGED and are merged onto
    // rows client-side (F10.0). There are no pushes in this group.

    /**
     * One page of the question bank, filtered (E6.5 — F2.6).
     * Caller: teacher, coordinator, principal. Request payload:
     * {@code BankListRequest}; response: {@code BankPage}.
     *
     * <p>Every filter is optional. {@code courseCode} narrows the caller's own
     * reachable set and never widens it: it is <b>intersected</b> server-side with
     * the courses her role reaches, so naming somebody else's course answers an
     * empty page rather than its contents. The client's filter dropdown is fed by
     * {@code COURSES_FOR_USER}, which is E4's verb and not this contract's.
     *
     * <p>{@code BankQuestionRow} carries no answers at all and no image bytes, and
     * its stem is truncated server-side (lead's ruling): the list is the payload a
     * bank browse feels, and the one that ends up in a shared screenshot. The key
     * is fetched a question at a time by {@link #QUESTION_GET}. {@code size} is
     * clamped to 1..100 rather than refused, because an out-of-range page size is
     * a client bug and not something a teacher can act on.
     */
    BANK_LIST,

    /**
     * One question opened, answer key included (E6.1/E6.3 — F2.1).
     * Caller: teacher, coordinator, principal. Request payload:
     * {@code QuestionRequest}; response: {@code QuestionDetail}.
     *
     * <p><b>The verb that hands out the answer key, and the reason the bank has a
     * leak guard of its own.</b> A teacher authoring a question is looking at
     * which answer is right because that is what authoring is, so this wire
     * carries correctness where {@link #EXAM_JOIN}'s deliberately cannot. The
     * safety property is "no key on a path a student can reach", enforced by the
     * staff-only gate here and by
     * {@code server.db.repos.BankWireLeakGuardTest} scanning
     * {@code common.dto.bank} for anything key-bearing that is not licensed.
     *
     * <p>The <b>principal receives the same type</b> (lead's ruling of
     * 2026-08-21): one detail record for every staff reader, rather than a second
     * keyless projection for a distinction whose threat model is students and not
     * staff. Her limit is F9.3, which is expressed by the three mutating verbs not
     * accepting her role at all.
     *
     * <p>Out of scope, soft-deleted and never-existed all answer {@code NOT_FOUND}.
     */
    QUESTION_GET,

    /**
     * Every version a question has ever had, newest first (E6.3 — F2.3, C-2).
     * Caller: teacher, coordinator, principal. Request payload:
     * {@code QuestionRequest}; response: {@code VersionHistory}.
     *
     * <p>Editing writes a new version rather than mutating one, so this is the
     * whole life of the question and includes the current version rather than only
     * the superseded ones. That is what keeps an exam pinned to v1 explicable a
     * year later. The rows are {@code QuestionVersionDetail} and they carry the
     * key on the same licence as {@link #QUESTION_GET}: a history that hid which
     * answer used to be right would be a diff a teacher cannot read.
     */
    QUESTION_VERSIONS,

    /**
     * One version's illustration, fetched lazily (E6.6 — F2.4).
     * Caller: teacher, coordinator, principal. Request payload:
     * {@code QuestionImageRequest}; response: {@code QuestionImage}.
     *
     * <p>Named noun-first to match {@link #NOTIFICATIONS_GET},
     * {@link #LOCKS_SNAPSHOT} and {@link #BOT_MANAGER_GET} rather than the two
     * legacy verbs above (lead's ruling of 2026-08-21; TODO E6.6 was reworded to
     * match).
     *
     * <p><b>Addressed by version, not by question</b>, because versions are
     * immutable and a picture belongs to the wording it was uploaded with. Neither
     * the list nor the detail ever carries bytes, so forty rows cost no image
     * traffic and one picture crosses the wire when somebody opens it (NFR-18).
     * {@code contentType} is re-sniffed from the leading bytes on read: there is no
     * content-type column, and a nullable one that can disagree with its own blob
     * is worse than a derivation that cannot.
     */
    QUESTION_IMAGE_GET,

    /**
     * Add a question to the bank (E6.1 — F2.1/F2.2).
     * Caller: teacher or coordinator. Request payload: {@code QuestionDraft};
     * response: {@code QuestionDetail}, the version just written.
     *
     * <p>The draft carries no display id, because the server allocates the 5-digit
     * serial (S-8), and no author id, because authorship is the caller's session
     * and a question must not be creatable in somebody else's name (P-5).
     *
     * <p>Validation is {@code QuestionValidator}, shared with
     * {@link #QUESTION_UPDATE} so the two cannot diverge, and each failure answers
     * {@code VALIDATION} with a message naming the offending field: a teacher
     * fixing three bad saves in a row needs three different sentences. The rules
     * are at least as strict as the database CHECK in every dimension, accent
     * folding included, because {@code utf8mb4_unicode_ci} is accent-insensitive
     * and a validator that folds only case would hand its own backstop a case to
     * reject rudely.
     */
    QUESTION_CREATE,

    /**
     * Edit a question, which writes the next version (E6.3 — F2.1/F2.3, ADR-011).
     * Caller: teacher or coordinator. Request payload: {@code QuestionEdit};
     * response: {@code QuestionDetail} for the <b>new</b> version.
     *
     * <p>Version n is never mutated: exams pinned to it keep it, wording and key
     * and picture. The stale-editor race is caught by {@code baseVersionNo}
     * disagreeing with the current latest, which answers {@code CONFLICT},
     * backed underneath by {@code uq_question_versions_no}. There is deliberately
     * no {@code lockVersion} beside it: {@code questions} is the identity row and
     * inserting a version does not dirty it, so an echoed token would match
     * forever, and shipping an inert token next to a working one is how the
     * working one stops being trusted.
     *
     * <p>{@code imageAction} is KEEP, REPLACE or REMOVE rather than a nullable
     * blob, because a null image is ambiguous between "unchanged" and "cleared"
     * and F2.1's editor has an explicit remove button. KEEP copies the blob onto
     * the new version, which is the honest cost of immutable versions and the
     * reason {@link #QUESTION_IMAGE_GET} is addressed by version.
     */
    QUESTION_UPDATE,

    /**
     * Remove a question from the bank, or explain why not (E6.4 — F2.5, T-2.7).
     * Caller: teacher or coordinator. Request payload:
     * {@code QuestionDeleteRequest}; response: {@code DeleteOutcome}.
     *
     * <p>Blocked while any exam version references any version of the question,
     * and the refusal is an {@code OK} carrying {@code deleted = false} and the
     * blocking exams by display id and name, not an error code: a question in use
     * is information the teacher can act on, not a fault she committed. The list
     * is de-duplicated <b>by exam</b> rather than by exam version, so an exam
     * pinning the question in two of its own versions is named once.
     *
     * <p>Otherwise soft: {@code deleted_at} is stamped, the question leaves every
     * listing, the version history survives so marked papers stay explicable, and
     * the serial is never reused (T-2.8). The verb is named DELETE because that is
     * what the teacher is doing, not what the row does. It carries the same
     * {@code baseVersionNo} an edit does, so a delete racing an edit is a
     * {@code CONFLICT} rather than a coin toss.
     */
    QUESTION_DELETE,

    // ===================== Notifications (E17) =============================

    /**
     * Fetch the caller's most recent notifications and unread count.
     * Request payload: {@code NotificationsGetRequest}; response:
     * {@code NotificationsPage}. The caller is always the recipient — the
     * request carries no user id, because it could only ever be someone else's.
     */
    NOTIFICATIONS_GET,

    /**
     * Mark one of the caller's notifications read, or all of them.
     * Request payload: {@code MarkReadRequest}; response: {@code NotificationsPage}
     * so the badge and the list stay in step with one round trip.
     */
    NOTIFICATIONS_MARK_READ,

    // ===================== Edit locks (E18) ================================
    // The first four carry a {@code common.dto.lock.LockRequest} and answer with a
    // {@code LockResponse}. Acquiring also registers the caller as a watcher of
    // that entity, which is how {@link #PUSH_LOCK_CHANGED} finds its recipients.
    //
    // Every verb in this group is TEACHER or COORDINATOR only. Students never edit
    // and never list what is being edited, and a student who could call
    // {@link #LOCK_ACQUIRE} could pin an entity read-only for its TTL over and
    // over (P-5 follow-up). The gate is the same one for all five so a reader does
    // not have to check which ones are softer: none are.
    //
    // Identity is never on the wire. No payload in this group carries a user id,
    // because a user id in one of them could only ever be somebody else's
    // (ARCHITECTURE §3, security).

    /** Take (or take over) the advisory edit lock on one entity. */
    LOCK_ACQUIRE,

    /** Heartbeat: extend the caller's own lock before its TTL runs out. */
    LOCK_RENEW,

    /** Give the lock back and stop watching the entity. */
    LOCK_RELEASE,

    /**
     * Watch one entity's lock <b>without contending for it</b> (E18.8).
     * Request payload: {@code LockRequest}; response: {@code LockResponse}
     * describing the entity's current state.
     *
     * <p>The difference from {@link #LOCK_ACQUIRE} is the whole reason this verb
     * exists: acquiring registers interest <em>and takes the lock</em>, which is
     * exactly wrong for a list screen. A bank list showing forty rows would take
     * forty locks and block forty colleagues by the act of being looked at. This
     * registers the same interest and takes nothing, so the caller receives
     * {@link #PUSH_LOCK_CHANGED} for that entity and the entity stays as free as
     * it was.
     *
     * <p>The answer is a {@code LockResponse} with {@code granted = false} in
     * every case, including when nobody holds the entity: a watcher is never a
     * holder. {@code holder} is populated when somebody is editing and
     * {@code null} when nobody is, which is the same shape a refusal and a
     * release already use.
     *
     * <p>To stop watching, send {@link #LOCK_RELEASE} for the same entity: it
     * drops the registration and, since the watcher holds nothing, changes no
     * lock. Logging out or dropping the socket drops every registration anyway.
     */
    LOCK_WATCH,

    /**
     * Bulk query: who is currently editing each of these entities (E18.8).
     * Request payload: {@code LocksSnapshotRequest} (one entity type plus the ids
     * on screen); response: {@code LocksSnapshot} (id → {@code LockHolder}).
     *
     * <p>What a list screen needs on its first paint. Without it the "Editing ·
     * &lt;name&gt;" chip would only appear on rows whose lock changed <em>after</em>
     * the screen opened, because pushes carry news and not state: a question
     * locked ten minutes ago raises nothing, so a freshly opened list would show
     * it as free. One snapshot at load plus the pushes afterwards is the complete
     * picture.
     *
     * <p>Only <b>live</b> holds are in the answer. Ids nobody is editing are
     * absent from the map rather than mapped to null, and an id that does not
     * exist at all is treated identically: this verb reports locks, and it is not
     * an existence oracle for rows the caller may not be allowed to see.
     *
     * <p>Asking does <b>not</b> subscribe. A screen that wants live updates too
     * sends {@link #LOCK_WATCH} per row it is showing; keeping the two separate is
     * what lets a one-off refresh stay a one-off.
     *
     * <p>Client side: the bank-list chip that consumes both of these ships with
     * E6's rebuilt bank list. The server half is deliberately ahead of it.
     */
    LOCKS_SNAPSHOT,

    // ===================== Take exam (E10) =================================
    // The draft wire contract: docs/contracts/EXAM_WIRE_CONTRACT.md. Payload
    // types live in {@code common.dto.exam}; the handlers are
    // {@code server.features.exam.AttemptService}.
    //
    // Every verb below is a STUDENT verb: any authenticated caller, scoped to
    // themselves in the query itself. None of them takes a student id, because a
    // student id in one of these payloads could only ever be somebody else's
    // (P-5: the CallerContext is what identifies the caller). An attempt id that
    // is not the caller's answers NOT_FOUND, indistinguishably from one that does
    // not exist.
    //
    // Two rules bind all five, and they are what the first defence failed on:
    // the SERVER owns the clock (every answer carries an {@code AttemptTiming}
    // the client re-anchors to, and every write re-checks the deadline against
    // the server's own Clock inside the transaction), and NO CORRECTNESS ever
    // travels on these verbs — {@code ExamQuestion} has no field for it.

    /**
     * Look an execution up by its 4-character code (C-1) and answer the exam
     * header, <b>without</b> questions.
     * Request payload: {@code ExamJoinRequest}; response: {@code ExamHeader}.
     * The questions do not exist on the client until an identity has been
     * confirmed, because that is what starts the clock (S-18).
     */
    EXAM_JOIN,

    /**
     * Confirm identity and begin: creates the attempt, derives the deadline and
     * arms the server-side timer (S-18).
     * Request payload: {@code AttemptStartRequest}; response: {@code AttemptForm}.
     * The national id must match the <em>caller's own</em> user record. Starting
     * twice is not an error: the second call answers the resumable state of the
     * first (F6.7).
     */
    ATTEMPT_START,

    /**
     * Come back to an attempt after a reconnect, a crash or a reopened screen
     * (E10.6, F6.3).
     * Request payload: {@code AttemptResumeRequest}; response: {@code AttemptForm}
     * carrying the saved answers and the authoritative remaining time. If the
     * attempt timed out while the client was away, the form says so and the
     * client shows the Time Up takeover (F6.4).
     */
    ATTEMPT_RESUME,

    /**
     * Autosave one choice (F6.3).
     * Request payload: {@code SaveAnswerRequest}; response:
     * {@code SaveAnswerResult}. Rejected with {@code CONFLICT} when the attempt
     * is no longer {@code IN_PROGRESS} or the deadline has passed — checked
     * against the server Clock inside the transaction, so an answer in flight
     * when time ran out does not land (§6, E10.8 ⚑).
     */
    ANSWER_SAVE,

    /**
     * Hand the paper in (F6.9).
     * Request payload: {@code SubmitAttemptRequest}; response:
     * {@code AttemptOutcome}. Finalisation is a status-guarded atomic UPDATE
     * (ARCHITECTURE §5), so a submit racing the expiry timer has exactly one
     * winner; the loser reads the final state and answers with it rather than
     * with an error, because a student pressing submit as her time runs out has
     * done nothing wrong.
     */
    ATTEMPT_SUBMIT,

    /**
     * Report that the exam window lost and regained focus during a live attempt
     * (E11.7 — F7.1b). Request payload: {@code AttentionReport}; response: OK
     * with no payload.
     * <p>Additive to the frozen E10/E11 contract. Carries no attempt id and no
     * student id: the server resolves the caller's own {@code IN_PROGRESS}
     * attempt through {@code AttemptRegistry}, on the same P-5 rule as every
     * other student verb. A report from a student with no live attempt answers
     * OK and does nothing — the attempt may have expired mid-absence, which is
     * normal rather than an error.
     * <p><b>Signal, not verdict.</b> There is no auto-penalty, no student-facing
     * UI and no correctness anywhere near this verb; it adds one calm line to the
     * teacher's monitor row and stops there.
     */
    ATTEMPT_ATTENTION,

    // ===================== Extension & monitoring (E11) ====================
    // Teacher verbs: {@code requireRole(TEACHER, COORDINATOR)} PLUS ownership
    // resolved from the repositories — the caller must be the execution's
    // executing teacher or the exam's author, never whoever the payload says.

    /**
     * Add minutes to a live execution (F7.1, S-20).
     * Request payload: {@code ExtendTimeRequest}; response:
     * {@code ExecutionMonitor}, refreshed. Applies to the execution only, never
     * to the stored exam; reschedules every live attempt and pushes
     * {@link #PUSH_TIMER_EXTENDED} to the students sitting it.
     */
    EXECUTION_EXTEND,

    /**
     * The live state of one execution, and a subscription to it (F7.2).
     * Request payload: {@code MonitorRequest}; response:
     * {@code ExecutionMonitor}. Asking also registers the caller as a watcher,
     * the same "whoever asked is watching" mechanism the edit locks use, so no
     * second verb is needed and no screen ever refreshes by hand (NFR-18).
     */
    EXECUTION_MONITOR_GET,

    // ===================== Release manager (E9) ============================
    // Additive to the frozen E10/E11 contract, documented as amendments A3-A7
    // of docs/contracts/EXAM_WIRE_CONTRACT.md. Payload types live in
    // {@code common.dto.release}; the handlers are
    // {@code server.features.release.ReleaseService}.
    //
    // Every verb below is a TEACHER verb, gated exactly as E11's pair is:
    // {@code requireRole(TEACHER, COORDINATOR)} PLUS ownership resolved from the
    // execution itself — the caller must be the teacher who released it or the
    // author of the exam being sat, never whoever the payload says (P-5). None
    // of these payloads carries a teacher id, because one could only ever be
    // somebody else's.
    //
    // Two things are the server's and are not on any request: the 4-character
    // code (generated here, because only the server can check it is free among
    // releases students might still be sitting) and the state (derived from the
    // window and the stored status against the server's clock).

    /**
     * The approved exam versions this teacher may take out of the drawer
     * (F5.1, S-14).
     * Request payload: {@code null} — which versions those are is resolved from
     * the session, not from a field. Response: {@code ReleaseOptions}.
     * <p>The query filters on {@code APPROVED}, so PRD §6's "release unapproved
     * version → impossible (not listed)" is a {@code where} clause rather than a
     * client-side omission. {@link #RELEASE_CREATE} checks it again: a list is a
     * courtesy, never a gate.
     */
    RELEASE_OPTIONS_GET,

    /**
     * This teacher's releases, with their live state and participation (F5.4).
     * Request payload: {@code null}; response: {@code ReleaseList}. Scoped in the
     * query to the releases she created or whose exam she wrote, which is exactly
     * the set she may act on, so no row in the list is un-actionable and no
     * actionable release is invisible.
     */
    RELEASE_LIST_GET,

    /**
     * Schedule a release of an approved exam version (F5.1, F5.2, S-2).
     * Request payload: {@code ReleaseCreateRequest}; response:
     * {@code ReleaseRow}, carrying the generated code the teacher reads out.
     * <p>Refuses an unapproved version with {@code VALIDATION}, a window whose
     * close is not after its open or whose open is well in the past with
     * {@code VALIDATION}, and a version outside her courses with
     * {@code FORBIDDEN}. The same exam may be released many times (S-2).
     */
    RELEASE_CREATE,

    /**
     * Call off a scheduled release before it ever opens (F5.5).
     * Request payload: {@code ReleaseActionRequest}; response:
     * {@code ReleaseRow}, refreshed. Legal only from {@code SCHEDULED}: a live
     * release is ended with {@link #RELEASE_CLOSE_EARLY}, which is a different
     * action because it hands people in. Cancelled releases are excluded from the
     * report corpus (PRD §6).
     */
    RELEASE_CANCEL,

    /**
     * End a live release now (F5.5).
     * Request payload: {@code ReleaseActionRequest}; response:
     * {@code ReleaseRow}, refreshed. <b>Behaves exactly like time expiry for
     * active students</b>: every attempt still in progress goes through the same
     * force-submit path an expiry takes, so a student mid-question ends
     * {@code TIMED_OUT} with the answers she had saved and gets the same
     * {@link #PUSH_FORCE_SUBMITTED} her own timer would have sent. Idempotent.
     */
    RELEASE_CLOSE_EARLY,

    // ===================== Grading & results (E12/E13) =====================
    // The frozen wire contract: docs/contracts/GRADING_WIRE_CONTRACT.md. Payload
    // types live in {@code common.dto.grading}; the handlers are E12/E13.
    //
    // Two role families, and the difference is the whole security story here.
    // Every teacher verb is {@code requireRole(TEACHER, COORDINATOR)} PLUS an
    // ownership check resolved from the repositories — the caller must be the
    // execution's executing teacher or the exam's author, never whoever the
    // payload says (P-5: a CallerContext is always read). Every student verb is
    // open to any authenticated caller and scoped to their own grades in the
    // query itself ({@code WHERE student_id = :caller}), so someone else's grade
    // id answers NOT_FOUND and reveals nothing.

    /**
     * The teacher's queue of closed executions waiting to be marked.
     * Caller: teacher (or coordinator). Request payload: {@code null} — which
     * executions those are is resolved from the session, not from a field.
     * Response: {@code GradingQueue}.
     */
    GRADING_QUEUE_GET,

    /**
     * Every student's grade in one execution, with its header.
     * Caller: teacher. Request payload: {@code ExecutionGradesRequest};
     * response: {@code ExecutionGrades}.
     */
    GRADING_EXECUTION_GET,

    /**
     * One grade opened for review: the header plus the marked paper.
     * Caller: teacher. Request payload: {@code GradeReviewRequest}; response:
     * {@code GradeReview}, which carries the answer key and therefore never
     * reaches a student (see {@link #CHECKED_FORM_GET}).
     */
    GRADE_REVIEW_GET,

    /**
     * Change a score, with a required justification (S-23).
     * Caller: teacher. Request payload: {@code GradeOverrideRequest}; response:
     * {@code GradeReview}, refreshed from the server's own read rather than an
     * acknowledgement the client would have to patch a row with. Allowed only
     * while the grade is {@code AUTO}: overriding an approved grade answers
     * {@code CONFLICT}.
     */
    GRADE_OVERRIDE,

    /**
     * Approve one grade or a whole execution — one verb for both (E12.2/E12.7).
     * Caller: teacher. Request payload: {@code ApproveRequest}; response:
     * {@code ApproveResult}. Idempotent: re-approving counts in
     * {@code alreadyApproved} and never errors. Completing an execution freezes
     * its {@code ScoreStatistics} in the same transaction (E12.4), and each
     * approval publishes to the student through {@link #PUSH_GRADE_PUBLISHED}
     * and a durable {@code GRADE_PUBLISHED} notification (C-3, E13.6).
     */
    GRADES_APPROVE,

    /**
     * The calling student's own published results.
     * Caller: any authenticated user, scoped to themselves. Request payload:
     * {@code null}; response: {@code MyGrades} — approved rows only, and never
     * the override justification.
     */
    MY_GRADES_GET,

    /**
     * The calling student's own marked paper, chosen answers against correct
     * ones (E13.2).
     * Caller: any authenticated user, scoped to themselves. Request payload:
     * {@code CheckedFormRequest}; response: {@code CheckedForm}. The only verb
     * that hands correctness to a student, and only when the grade is theirs,
     * it is {@code APPROVED}, and the execution is closed; anything else is
     * {@code NOT_FOUND}, indistinguishably.
     */
    CHECKED_FORM_GET,

    // ===================== Exam approval (E8) ==============================
    // The draft wire contract: docs/contracts/APPROVAL_WIRE_CONTRACT.md. Payload
    // types live in {@code common.dto.approval}; the handlers are
    // {@code server.features.approval.ApprovalService}.
    //
    // Every verb here is COORDINATOR-gated PLUS subject ownership resolved from
    // the {@code coordinators} table — {@code requireCoordinatorOf}, never
    // whoever the payload says (P-5). "One coordinator per subject" is the
    // primary key of that table, so the scoping question has exactly one answer.
    // Two exceptions, both deliberate: {@link #MY_APPROVALS_GET} is any teacher's
    // read of her own submissions, scoped to the caller in the query itself; and
    // {@link #EXAM_PREVIEW_GET} also admits the version's own AUTHOR as a plain
    // teacher, because a rejection reason is only actionable if she can re-read
    // the exam it names (F4.2). Corrected 2026-08-21; the contract was always right.
    //
    // Two rules bind the group. The two decisions are optimistic-locked
    // compare-and-sets on {@code exam_versions.lock_version} AND guarded on
    // {@code status}, so a decision taken against a row that has moved is
    // refused rather than applied. And {@link #EXAM_PREVIEW_GET} is the one
    // verb in the product that hands an answer key to somebody who is not
    // grading and did not write the question — because approving an exam you
    // cannot check is approving a document, not an exam.

    /**
     * The versions waiting on this coordinator's decision (F4.1).
     * Caller: coordinator. Request payload: {@code null} — which subjects those
     * are is resolved from the session, not from a field. Response:
     * {@code ApprovalQueue}, scoped to her coordinated subjects <em>in the SQL</em>.
     * A coordinator who coordinates nothing gets an empty queue that says so,
     * which is a different empty state from "nothing is waiting".
     */
    APPROVALS_QUEUE_GET,

    /**
     * One exam version opened for review (F4.1 ⚑ — the v1 fix).
     * Caller: the subject's coordinator, or the version's own author. Request
     * payload: {@code ExamPreviewRequest}; response: {@code ExamPreview}.
     *
     * <p>The response carries the paper as {@code List<ExamQuestion>} — the
     * <b>student's</b> wire type, from the same no-correctness projection a real
     * attempt is built from — plus a fenced {@code TeacherOnlyBlock} holding the
     * teacher notes, the author's name and the answer key. Two audiences in one
     * message, with the wall between them visible in the types.
     */
    EXAM_PREVIEW_GET,

    /**
     * Approve one version (F4.2).
     * Caller: the subject's coordinator. Request payload:
     * {@code ExamApproveRequest}; response: {@code ApprovalDecision}.
     * {@code PENDING → APPROVED}, guarded on both the status and the
     * {@code lock_version} the caller was looking at: a stale decision answers
     * {@code CONFLICT} with a sentence telling her to open it again. The author
     * is notified. A coordinator approving her <em>own</em> exam succeeds by
     * design (F4.3) and is recorded in the server log.
     */
    EXAM_APPROVE,

    /**
     * Send one version back, with a reason (F4.2).
     * Caller: the subject's coordinator. Request payload:
     * {@code ExamRejectRequest}; response: {@code ApprovalDecision}.
     * The reason is required and is refused with {@code VALIDATION} when it is
     * missing or shorter than {@code ExamRejectRequest.MIN_REASON_LENGTH}
     * characters after trimming: a rejection the author cannot act on is the one
     * message this feature must never send. Same status-and-lock guard as
     * {@link #EXAM_APPROVE}; the reason is stored on the version and delivered to
     * the author as a notification that deep-links to it.
     */
    EXAM_REJECT,

    /**
     * The calling teacher's own submitted versions, with their outcomes (F4.2).
     * Caller: any teacher or coordinator, scoped to herself. Request payload:
     * {@code null}; response: {@code MyApprovals} — the "visible on the exam"
     * half of F4.2, which a dismissed notification cannot provide.
     *
     * <p>Deliberately the narrow approval-status read and not an exam list: E7
     * owns the exam list and its verb, and this one retires into it.
     */
    MY_APPROVALS_GET,

    // ============= Teacher results & statistics (E14) ======================
    // The draft wire contract: docs/contracts/RESULTS_WIRE_CONTRACT.md. Payload
    // types live in {@code common.dto.results}; the handlers are
    // {@code server.features.results.TeacherResultsService}.
    //
    // Both verbs are {@code requireRole(TEACHER, COORDINATOR)} PLUS authorship:
    // the scope is every exam the caller WROTE, resolved from the exam's recorded
    // author, even when the sitting was released by another teacher (S-35). The
    // scoping is in the query rather than in a check afterwards, and a caller who
    // did not write the exam gets {@code NOT_FOUND} — the same answer an id that
    // never existed gets, so neither verb can be used to discover that an
    // execution exists.
    //
    // Statistics travel as they were frozen (F8.5): population sigma, pass mark
    // 55. Nothing on this path recomputes them.

    /**
     * Every exam the calling teacher wrote, each with its sittings.
     * Caller: teacher (or coordinator). Request payload: {@code null} — whose
     * exams these are is resolved from the session, not from a field.
     * Response: {@code TeacherResults}. Cancelled executions are excluded
     * (H15.2); an exam that was never released comes back with an empty list
     * rather than being dropped.
     */
    RESULTS_EXAMS_GET,

    /**
     * One execution's results: the header, a row per marked student, and the
     * frozen statistics.
     * Caller: teacher. Request payload: {@code ExecutionResultsRequest};
     * response: {@code ExecutionResults}. Rows are
     * {@code StudentGradeRow} on the teacher path, so the override justification
     * is present. An execution whose grading is unfinished answers OK with its
     * rows and no statistics — that is a state the screen renders calmly, not an
     * error.
     */
    RESULTS_EXECUTION_GET,

    // ============= Principal reports (E15) =================================
    // The draft wire contract: docs/contracts/REPORTS_WIRE_CONTRACT.md. Payload
    // types live in {@code common.dto.report}; the handler is
    // {@code server.features.reports.ReportService} over
    // {@code server.features.reports.ReportEngine}.
    //
    // Both verbs are requireRole(PRINCIPAL) and nothing else. That is the whole
    // authorization story and it is deliberate: F9.3 gives the principal a
    // school-wide READ and literally zero mutating verbs, so there is no scope to
    // narrow and no ownership to resolve. A teacher, a coordinator and a student
    // all get FORBIDDEN from the role gate rather than an empty answer, because an
    // empty answer would read as "you have no reports" rather than "this is not
    // your screen".
    //
    // Neither verb writes. Neither verb pushes. A report is a comparison of
    // sittings that have already closed, and nothing about it can move while it is
    // on screen.
    //
    // Statistics travel as they were frozen (F8.5): population sigma, pass mark
    // 55, ten stored buckets. The engine aggregates those numbers across rows and
    // recomputes none of them. Cancelled executions are excluded (H15.2).

    /**
     * The subjects a given dimension can be reported about (F9.4).
     * Caller: principal. Request payload: {@code ReportSubjectsRequest};
     * response: {@code ReportSubjects}. School-wide and unpaginated (spec 7.3.1,
     * PRD section 6). Subjects with nothing to report are included and carry a
     * count of zero, so an empty comparison is visible before it is asked for.
     */
    REPORT_SUBJECTS_GET,

    /**
     * One report: a dimension, a subject, its sittings and their cross-row
     * summary (F9.4, S-37).
     * Caller: principal. Request payload: {@code ReportRequest}; response:
     * {@code ReportResult}. Rows are closed executions with frozen statistics,
     * oldest first. A subject that exists with nothing to compare answers OK with
     * no rows - a real answer, not an error.
     */
    REPORT_GET,

    // ============= Principal data browser (E15.2) ==========================
    // Amendment A1 of docs/contracts/REPORTS_WIRE_CONTRACT.md (2026-08-23), which
    // owns the principal's role. Payload types live in {@code common.dto.report};
    // the handler is {@code server.features.reports.DataBrowseService} over the
    // same read-only {@code ReportData} seam the report engine uses.
    //
    // These two are what T-11's "read-only browse of question bank, exams,
    // results" was missing. The bank half needed NO new verb: the principal has
    // been on BANK_LIST / QUESTION_GET / QUESTION_VERSIONS / QUESTION_IMAGE_GET
    // since E6 and reaches every course through them (F9.3, BANK contract section
    // 3). What did not exist was a school-wide EXAM listing and a school-wide
    // RESULTS listing - RESULTS_EXAMS_GET and RESULTS_EXECUTION_GET are scoped to
    // the exams the caller WROTE (S-35), which is a scope she does not have and
    // must not be given by widening theirs.
    //
    // Both are requireRole(PRINCIPAL) and nothing else, for the reason the E15
    // block above gives. Both take NO payload: there is no field a client could
    // set, so there is nothing to widen and no VALIDATION path.
    //
    // Neither verb writes. Neither verb pushes. Statistics travel exactly as they
    // were frozen (F8.5) through the same mapping the reports use, and cancelled
    // sittings are absent from both (H15.2).

    /**
     * Every exam in the school, as a catalogue (F9.3, T-11.2).
     * Caller: principal. Request payload: {@code null}; response:
     * {@code DataExams}. Ordered by display id, unpaginated (PRD section 6). Each
     * row carries the exam's identity, its course, its author's name and its
     * latest version; it carries no questions, no answer key, no instructions and
     * no approval status.
     */
    DATA_EXAMS_GET,

    /**
     * Every closed sitting in the school with its frozen statistics (F9.3,
     * T-11.2).
     * Caller: principal. Request payload: {@code null}; response:
     * {@code DataResults}, whose rows are {@code ReportRow} reused unchanged.
     * Newest first, which is the opposite of a report's ordering and deliberate:
     * a browse is a filing cabinet, a report is a trend. Scheduled, live,
     * cancelled and unmarked sittings are all absent - a sitting appears once its
     * last grade is approved.
     */
    DATA_RESULTS_GET,

    // ===================== Study bot (E16) =================================
    // The draft wire contract: docs/contracts/BOT_WIRE_CONTRACT.md. Payload types
    // live in {@code common.dto.bot}; the handlers are
    // {@code server.features.bot.BotService} (student) and
    // {@code server.features.bot.BotAdminService} (teacher).
    //
    // The security boundary of this group is F12.8 ⚑ and it is structural rather
    // than procedural: NO verb here can reach an exam definition, an execution
    // code, an attempt or a grade, because the feature package that serves them
    // has no compile-time dependency on any of that (proved by
    // BotIsolationGuardTest). What the model sees is course source material plus
    // course bank questions (S-28) — and the bank read carries the four answers
    // WITHOUT marking which is correct, because a study bot that hands out answer
    // keys to live-exam-adjacent material defeats its own point.
    //
    // Student verbs carry no user id, ever. Teacher verbs are
    // requireRole(TEACHER, COORDINATOR) PLUS taught-course ownership resolved
    // from the repositories, never from the payload.

    /**
     * Ask a course's bot a question (F12.5, C-4 ⚑).
     * Request payload: {@code BotAskRequest}; response: {@code BotAnswer}.
     *
     * <p>Refused with {@code FORBIDDEN} when the caller is not enrolled (S-31),
     * with {@code CONFLICT} when the bot is switched off (F12.4) or when she is
     * sitting an exam of <em>that</em> course (C-4: the message names the exam and
     * when the lock lifts), and with {@code VALIDATION} when she has exceeded the
     * per-minute rate limit. A live attempt in <em>another</em> course does not
     * refuse: the first ask answers {@code OK} carrying a
     * {@code BotIntegrityNotice} instead of an answer, and the re-sent request with
     * {@code integrityAcknowledged} proceeds and reports her to that exam's teacher
     * (ADR-018).
     */
    BOT_ASK,

    /**
     * The calling student's own conversations with one course's bot (F12.10).
     * Request payload: {@code BotCourseRequest}; response: {@code BotSessionsPage}.
     * Scoped to the caller in the query itself, like {@link #NOTIFICATIONS_GET}.
     */
    BOT_SESSIONS_GET,

    /**
     * Reopen one of the caller's own conversations (F12.10, S-33).
     * Request payload: {@code BotSessionRequest}; response: {@code BotConversation}.
     * Somebody else's session id answers {@code NOT_FOUND}, indistinguishably from
     * one that does not exist.
     */
    BOT_SESSION_GET,

    /**
     * The teacher's Bot Manager view of one taught course (F12.1/F12.3).
     * Request payload: {@code BotCourseRequest}; response: {@code BotManagerPage},
     * whose {@code bot} is {@code null} when the course has no bot yet — an empty
     * state to draw, not an error.
     */
    BOT_MANAGER_GET,

    /**
     * Create the bot for a taught course (F12.1, S-30).
     * Request payload: {@code BotCreateRequest}; response: {@code BotManagerPage}.
     * Idempotent by design: one bot per course, so a second teacher sending this
     * receives the existing bot and becomes a contributor to it rather than
     * getting a conflict.
     */
    BOT_CREATE,

    /**
     * Switch a taught course's bot on or off (F12.4, S-31).
     * Request payload: {@code BotActiveRequest}; response: {@code BotManagerPage}.
     */
    BOT_ACTIVE_SET,

    /**
     * Add one piece of material to a taught course's bot (F12.2/F12.3).
     * Request payload: {@code SourceAddRequest}; response: {@code BotManagerPage}.
     * The file is parsed server-side <b>before</b> the row is written, so a PDF
     * that cannot be read answers {@code VALIDATION} with a sentence the uploader
     * can act on rather than creating a source that contributes nothing.
     * Co-teachers of the course get a {@code BOT_SOURCE_CHANGED} notification.
     */
    BOT_SOURCE_ADD,

    /**
     * Remove one source from a taught course's bot (F12.3).
     * Request payload: {@code SourceRemoveRequest}; response: {@code BotManagerPage}.
     * Requires the advisory edit lock on that source (E18.5), so two teachers
     * cannot delete each other's work mid-edit.
     */
    BOT_SOURCE_REMOVE,

    /**
     * The anonymised usage aggregate for a taught course's bot (F12.11, S-34 ⚑).
     * Request payload: {@code BotCourseRequest}; response: {@code BotAnalytics},
     * which has no field capable of holding a student identity.
     */
    BOT_ANALYTICS_GET,

    // ===================== Server push channel =============================
    // Constants only for now — the producing services arrive with their epics.

    /** A new notification row for the recipient (E12). */
    PUSH_NOTIFICATION,

    /** An advisory edit lock was acquired, renewed or released (E13). */
    PUSH_LOCK_CHANGED,

    /**
     * A teacher added minutes to a live execution (E11.1, F7.1).
     * Payload: {@code common.dto.exam.TimerExtended}. Delivered to every student
     * currently sitting that execution; a durable {@code TIME_EXTENDED}
     * notification goes out alongside it so a student who was offline still
     * learns what happened (E11.4). The client plays the <i>Time Extended</i>
     * designed moment on it — time added is never silent.
     */
    PUSH_TIMER_EXTENDED,

    /**
     * The server force-submitted an attempt on expiry (E10.5 ⚑, F6.4).
     * Payload: {@code common.dto.exam.AttemptOutcome}. Best-effort by nature: the
     * expiry happens in the database whether or not anyone is listening, and a
     * student who was offline for it finds the same outcome inside her next
     * {@link #ATTEMPT_RESUME}. The client turns it into the Time Up takeover,
     * with no confirmation, because it has already happened.
     */
    PUSH_FORCE_SUBMITTED,

    /**
     * A release changed state, or its participation moved (E9 — F5.4).
     * Payload: {@code common.dto.release.ReleaseRow} — one <b>whole</b> row, not a
     * delta, for the reason {@link #PUSH_MONITOR_UPDATED} carries a whole
     * snapshot: a list that patched fields from events drifts the first time one
     * is missed. Recipients are the teachers who own that release, i.e. the one
     * who created it and the author of the exam being sat.
     * <p>Emitted on create, on cancel, on close (early or by the clock) and on the
     * scheduled opening transition. A row for a release the client has not seen is
     * an insert, not a mistake: a release created on her other machine has to
     * appear without anybody pressing refresh (NFR-18).
     */
    PUSH_EXECUTION_STATUS,

    /**
     * A watched execution's live state changed (E11.2, F7.2).
     * Payload: {@code common.dto.exam.ExecutionMonitor} — a whole snapshot, not a
     * delta, so a monitor screen rebuilds rather than patches and cannot drift.
     * Recipients are the teachers who asked for that execution with
     * {@link #EXECUTION_MONITOR_GET}.
     */
    PUSH_MONITOR_UPDATED,

    /** A grade was approved and published to the student (E11). */
    PUSH_GRADE_PUBLISHED;

    /**
     * @return {@code true} for the server-initiated push verbs, i.e. the ones
     *         that legitimately appear on a {@link Status#PUSH} message.
     */
    public boolean isPush() {
        return name().startsWith("PUSH_");
    }
}
