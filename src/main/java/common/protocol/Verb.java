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

    /** A live execution/attempt deadline moved (E9 time extension). */
    PUSH_TIMER_EXTENDED,

    /** The server force-submitted an attempt on expiry (E10). */
    PUSH_FORCE_SUBMITTED,

    /** An execution changed state (SCHEDULED → LIVE → CLOSED) (E9). */
    PUSH_EXECUTION_STATUS,

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
