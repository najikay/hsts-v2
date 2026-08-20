package server.features.notify;

import common.dto.notify.NavRef;
import common.dto.notify.NotificationType;

import java.util.Collection;
import java.util.List;

/**
 * <b>The integration point for every feature that notifies somebody</b> (Logic
 * tier, E17.1 — F11.1).
 *
 * <p>This one interface is the entire contract between notifications and the
 * rest of the server. A feature that needs to tell a user something takes a
 * {@code Notifier} in its constructor and calls it; it never touches the store,
 * the push gateway or the session map, and it never learns whether the recipient
 * happened to be online.
 *
 * <p><b>How E7, E8, E12 and E16 use it</b> — the recipe, so nobody has to read
 * {@link NotificationService} to wire theirs up:
 *
 * <pre>{@code
 * // 1. take the seam, not the implementation
 * public ApprovalService(ExamRepository exams, Notifier notifier) { ... }
 *
 * // 2. at the moment the rule fires, send the ready-made sentence
 * notifier.notify(List.of(coordinatorId),
 *                 NotificationCatalog.approvalRequested(examName, teacherName, examVersionId));
 *
 * // 3. wire the real one once, where the server is assembled (HSTSServer)
 * new ApprovalService(exams, notificationService);
 * }</pre>
 *
 * <p>Two rules that are the reason this is an interface rather than a static
 * call:
 * <ul>
 *   <li><b>the text is not yours to invent.</b> Titles and bodies live in
 *       {@link NotificationCatalog} so the wording is written once, reviewed
 *       once, and checked by one test against the PRD §4.1 copy rules. Use the
 *       catalog factory for your emit point; add one there if it is missing.</li>
 *   <li><b>recipients are yours to decide.</b> Notifications routes to the ids it
 *       is given and to nobody else. Working out <i>who</i> the coordinator of a
 *       subject is, or which students are mid-attempt, is the calling feature's
 *       business — and the negative tests in E17.6 exist to prove a wrong answer
 *       there cannot leak into another user's bell.</li>
 * </ul>
 *
 * <p>Being an interface also means a feature's unit tests take a Mockito double
 * and assert "these users were notified, with this type" without a socket, a
 * session or a store.
 */
@FunctionalInterface
public interface Notifier {

    /**
     * Persists one notification per recipient, then pushes to whichever of them
     * are online.
     *
     * <p>Persist-first is the guarantee: a recipient who is signed out is not
     * skipped, they see it in their bell at their next login (E17.6). Delivery
     * failures never propagate — one dead socket must not roll back the
     * transaction that raised the notification.
     *
     * @param userIds recipients; duplicates collapse, {@code null} and empty are
     *                a no-op (a rule that fired for nobody is not an error)
     * @param type    what happened
     * @param title   one short line, already composed for these recipients
     * @param body    optional detail line
     * @param ref     where clicking it navigates; {@link NavRef#none()} for none
     * @return how many rows were written and how many reached a live client
     */
    Outcome notify(Collection<Long> userIds, NotificationType type, String title, String body, NavRef ref);

    /**
     * Sends a ready-made {@link NotificationCatalog.Draft} — the form every
     * caller should use.
     *
     * @param userIds recipients
     * @param draft   the composed notification from {@link NotificationCatalog}
     * @return how many rows were written and how many reached a live client
     */
    default Outcome notify(Collection<Long> userIds, NotificationCatalog.Draft draft) {
        java.util.Objects.requireNonNull(draft, "draft");
        return notify(userIds, draft.type(), draft.title(), draft.body(), draft.ref());
    }

    /** Convenience for the common single-recipient case. */
    default Outcome notifyUser(long userId, NotificationCatalog.Draft draft) {
        return notify(List.of(userId), draft);
    }

    /**
     * What one {@code notify} call did.
     *
     * @param persisted rows written, i.e. how many people will see it eventually
     * @param pushed    how many of those were online and got it immediately
     */
    record Outcome(int persisted, int pushed) {

        /** The result of notifying nobody. */
        public static final Outcome NONE = new Outcome(0, 0);

        /** @return {@code true} when at least one recipient saw it live. */
        public boolean reachedAnyoneLive() {
            return pushed > 0;
        }

        /** @return recipients who will only see it at their next login. */
        public int offline() {
            return persisted - pushed;
        }
    }
}
