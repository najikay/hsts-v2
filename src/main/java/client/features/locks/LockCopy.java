package client.features.locks;

import java.util.Objects;

/**
 * Every sentence the edit-lock UI says (Presentation tier, E18.3/E18.4 — PRD §4.1).
 *
 * <p>Concurrency is the part of an app where wording does the most work: a user
 * who is suddenly read-only, or who has just lost twenty minutes of typing to
 * someone else's save, needs to be told what happened and what they can do about
 * it, in that order and in one line. Collecting the strings here means the
 * banner, the takeover prompt and the conflict dialog stay consistent, and that
 * one test can check the whole set against the copy rules: no em dashes,
 * sentence case, and every message ending in something the user can do.
 *
 * <p>The entity noun is a parameter rather than baked in, because the same
 * helper wraps a question, an exam version and a bot source in later epics and
 * "someone is editing this entity" is not English.
 */
public final class LockCopy {

    /** The takeover prompt's title, shared by both reasons a takeover is offered. */
    public static final String TAKEOVER_TITLE = "Take over editing?";

    /** Label of the takeover dialog's confirming button. */
    public static final String TAKEOVER_CONFIRM = "Take over";

    /** Label of the takeover dialog's dismissing button. */
    public static final String TAKEOVER_CANCEL = "Stay read only";

    /** The stale-write dialog's title (E18.4). */
    public static final String CONFLICT_TITLE =
            "This was changed by someone else while you were editing. Reload the latest version?";

    /** What reloading costs, said plainly, because it is not free. */
    public static final String CONFLICT_EXPLANATION =
            "Reloading replaces what is on your screen with the saved version. "
                    + "Your unsaved text is lost.";

    /** Label of the conflict dialog's confirming button. */
    public static final String CONFLICT_CONFIRM = "Reload";

    /** Label of the conflict dialog's dismissing button. */
    public static final String CONFLICT_CANCEL = "Keep my text";

    /** Shown while the editor is waiting for the server to answer its acquire. */
    public static final String CHECKING = "Checking who is editing this.";

    private LockCopy() {
    }

    /**
     * The read-only banner (E18.3, state b).
     *
     * @param holderName the person holding the lock
     * @param entityNoun what is being edited, lower case singular ("question")
     * @return e.g. {@code "Rina Barak is editing this question. It is read-only for you."}
     */
    public static String readOnlyBanner(String holderName, String entityNoun) {
        Objects.requireNonNull(holderName, "holderName");
        Objects.requireNonNull(entityNoun, "entityNoun");
        return holderName + " is editing this " + entityNoun + ". It is read-only for you.";
    }

    /**
     * The takeover prompt's explanation.
     *
     * <p>Two reasons, two sentences, because they are genuinely different
     * situations: the lock became free while you watched, or the lock you held
     * lapsed while you were away. Telling the second user the first story would
     * hide the fact that their own editing session ended.
     *
     * @param reason     why a takeover is on offer
     * @param entityNoun what is being edited, lower case singular
     */
    public static String takeoverExplanation(TakeoverReason reason, String entityNoun) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(entityNoun, "entityNoun");
        return switch (reason) {
            case AVAILABLE -> "Nobody is editing this " + entityNoun
                    + " now. Take over to start editing it yourself.";
            case LOST -> "Your editing lock expired, so this " + entityNoun
                    + " is open to others. Take over to continue editing.";
        };
    }

    /**
     * The banner shown while a takeover is on offer, for screens that prefer an
     * inline affordance to a modal.
     */
    public static String takeoverBanner(TakeoverReason reason, String entityNoun) {
        return takeoverExplanation(reason, entityNoun);
    }
}
