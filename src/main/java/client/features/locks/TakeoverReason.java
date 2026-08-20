package client.features.locks;

/**
 * Why the editor is offering to take the lock (Presentation tier, E18.3).
 *
 * <p>Both reasons end at the same prompt, and they must still be told apart: one
 * of them means the user's own editing session quietly ended, and hiding that
 * behind "this is available now" would let them keep typing into a screen whose
 * changes nobody is holding for them.
 */
public enum TakeoverReason {

    /**
     * The lock was somebody else's and they gave it back (or it lapsed). The user
     * was read-only and can now start editing.
     */
    AVAILABLE,

    /**
     * The lock was <b>this</b> user's and it expired: their client stopped
     * renewing long enough for the TTL to run out (a suspended laptop, a network
     * drop). Whatever is on their screen is no longer protected.
     */
    LOST
}
