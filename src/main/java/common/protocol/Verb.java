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
