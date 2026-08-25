package server.features.bank;

import common.dto.lock.EntityRef;

/**
 * The one place a question becomes a lock key (E6.14 — E18.5, F10.3).
 *
 * <p>Edit locks are generic: {@link EntityRef} is a {@code (type, long id)} pair and the lock
 * service holds no domain knowledge at all. So somebody has to decide which number identifies a
 * question. There were <b>two candidates</b>: the {@code questions} primary key, which the E0
 * prototype screen used, and the five-digit display id, which is the only identifier the
 * versioned bank's wire carries — {@code BankQuestionRow} and {@code QuestionDetail} have no PK
 * on them. The retirement PR deleted the screen that held the first, so there is one candidate
 * left and this file is where it is applied.
 *
 * <h2>The lead's ruling, and why it is a ruling rather than a preference</h2>
 *
 * <p>Two numbering schemes under one {@code entityType} means two teachers editing one question
 * can hold two different locks and never see each other. Asked on 2026-08-23 whether to add a
 * second {@code entityType} for the versioned bank, the lead ruled <b>no</b>: a second type would
 * keep that hazard alive for exactly as long as both screens exist, which is the window we would
 * be demoing in. The legacy retirement therefore folded into E6's endgame, and since that PR
 * {@code EntityRef.QUESTION} numbers by {@code displayId5} with no second scheme anywhere — the
 * screen that held the other one no longer exists.
 *
 * <h2>Why this lives in the server tier</h2>
 *
 * <p>It began as {@code client.features.bank.BankLocks}, package-private, when the editor was the
 * only thing that took a lock. The write-path consult ruled in on 2026-08-24 gave it a second
 * caller on the other side of the wire: {@link QuestionService} has to reach the <em>same</em> key
 * the editor locked under, or the consult reads an empty map and refuses nothing.
 *
 * <p>Two implementations of one numbering rule is the precise hazard the ruling above exists to
 * prevent, so the rule moved to one home rather than being copied into a second. The direction is
 * the one the lead accepted twice for {@code QuestionValidator}: the rule lives in the tier that
 * owns it and the client calls it. The closure is this file plus {@link EntityRef}, which is an
 * annotation-free record in the common tier, so nothing on a client classpath is pulled in behind
 * it.
 *
 * @see QuestionService#update the write-path consult
 */
public final class QuestionLockKey {

    /** Course(2) + serial(3), per S-8. The width is what keeps the key space disjoint. */
    private static final int DISPLAY_ID_LENGTH = 5;

    private QuestionLockKey() {
    }

    /**
     * The lock key for a question.
     *
     * <p>{@code displayId5} is course(2) + serial(3), always five digits and always unique
     * ({@code uq_questions_display_id}), so it parses to a stable {@code long} and no two
     * questions can collide. A soft-deleted question keeps its id, which is what makes the key
     * safe across a delete.
     *
     * <p><b>On the server this must be called only with an id that matched a stored row.</b> The
     * throws below are correct for the client, where the id came from a payload the server sent.
     * On the write path nothing validates {@code displayId5}'s shape before {@code QuestionService}
     * runs, so calling this on raw request data would turn a hostile payload into a thrown
     * exception on the socket read thread (E1.11) instead of a named refusal. {@code update} and
     * {@code delete} therefore resolve the question first and key the lock afterwards, at which
     * point a throw can only mean the database holds a display id that is not five digits, which
     * is a server defect and should be loud.
     *
     * @param displayId5 the five-digit id from the wire
     * @return the lock reference
     * @throws IllegalArgumentException if the id is not the five digits the wire promises, which
     *         would be a protocol defect rather than a user's doing
     */
    public static EntityRef of(String displayId5) {
        if (displayId5 == null || displayId5.isBlank()) {
            throw new IllegalArgumentException("a question needs a display id to be locked");
        }
        String trimmed = displayId5.strip();
        // The length check the javadoc above promises, and which was missing: without it
        // of("7") returned question#7 and the @throws clause was false.
        //
        // It does NOT carve out a disjoint key space, and an earlier version of this comment
        // claimed it did. A five-digit display id does not start at 10000: course codes may lead
        // with a zero, so 01003 keys question#1003, squarely inside primary-key auto-increment
        // range - QuestionLockKeyTest.leadingZeroIsNotLost measures it. That overlap was harmless
        // only because the prototype screen took no lock, and is moot now that it is deleted and
        // displayId5 is the sole scheme. Do not reach for this check as a licence to key
        // something else through here.
        if (trimmed.length() != DISPLAY_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "question display id '" + trimmed + "' is not " + DISPLAY_ID_LENGTH
                            + " digits, so it cannot key a lock (S-8)");
        }
        try {
            return EntityRef.question(Long.parseLong(trimmed));
        } catch (NumberFormatException notANumber) {
            // Deliberately loud. The ids come from the server, so a non-numeric one means the
            // wire disagrees with S-8 and every lock on this screen is keyed on nothing.
            throw new IllegalArgumentException(
                    "question display id '" + trimmed + "' is not numeric, so it cannot key a "
                            + "lock (S-8 says five digits)", notANumber);
        }
    }

    /**
     * The inverse of {@link #of(String)}: the display id a lock key was made from (E18.9).
     *
     * <p><b>Zero-padded to five, and that is the entire content of this method.</b>
     * {@code of("01003")} keys {@code question#1003}, because a course code may lead with a
     * zero — {@code QuestionLockKeyTest.leadingZeroIsNotLost} measures exactly that. Going
     * back with {@code Long.toString} would hand the bank {@code "1003"}, which matches no
     * row in {@code uq_questions_display_id}, and the question would resolve to nothing.
     *
     * <p>The consequence of getting it wrong is not a visible error. The caller of this is
     * the {@code question} entity scope, which turns "no such question" into "you do not
     * reach it" and drops the id from a snapshot — so a truncating inverse would silently
     * hide every lock on every question whose course code starts with a zero, and the suite
     * would stay green because such a question is absent either way when nobody holds it.
     * That is why the round trip is pinned by a test rather than left to reading.
     *
     * <p>Lives here rather than at the call site because this file is the one home of the
     * numbering rule, and an inverse kept somewhere else is a second implementation of it —
     * the precise hazard the lead's ruling above exists to prevent.
     *
     * @param entityId the {@link EntityRef} id, as {@link #of(String)} produced it
     * @return the five-digit display id
     * @throws IllegalArgumentException when the id cannot be a five-digit display id at all,
     *         which would mean something other than {@link #of(String)} made the key
     */
    public static String displayIdOf(long entityId) {
        if (entityId < 0 || entityId > MAX_DISPLAY_ID) {
            throw new IllegalArgumentException(
                    "entity id " + entityId + " cannot be a " + DISPLAY_ID_LENGTH
                            + "-digit question display id (S-8)");
        }
        return String.format("%0" + DISPLAY_ID_LENGTH + "d", entityId);
    }

    /** 99999: the largest value five digits can hold, and so the largest legal display id. */
    private static final long MAX_DISPLAY_ID = 99_999L;
}
