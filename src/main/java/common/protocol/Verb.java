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
    // All three carry a {@code common.dto.lock.LockRequest} and answer with a
    // {@code LockResponse}. Acquiring also registers the caller as a watcher of
    // that entity, which is how {@link #PUSH_LOCK_CHANGED} finds its recipients.

    /** Take (or take over) the advisory edit lock on one entity. */
    LOCK_ACQUIRE,

    /** Heartbeat: extend the caller's own lock before its TTL runs out. */
    LOCK_RENEW,

    /** Give the lock back and stop watching the entity. */
    LOCK_RELEASE,

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

    /** An execution changed state (SCHEDULED → LIVE → CLOSED) (E9). */
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
