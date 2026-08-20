package client.features.notify;

import client.ui.components.Icons;
import client.ui.components.logic.RelativeTime;
import client.ui.components.logic.ToastSpec;
import common.dto.notify.NotificationDto;
import common.dto.notify.NotificationType;

import java.time.Instant;
import java.util.Objects;

/**
 * How a notification looks and sounds (Presentation tier, E17.4/E17.5).
 *
 * <p>Three presentation decisions that would otherwise be buried in a view
 * class: which icon a type gets, how urgent its toast is, and how its age reads.
 * All three are pure functions of the DTO, so they are unit-tested here rather
 * than eyeballed in a running app.
 *
 * <p>The toast mapping is the one worth arguing about. A toast is an
 * interruption, so its colour is a claim about how much the interruption is
 * worth:
 * <ul>
 *   <li><b>success</b> for the two pieces of good news a user is waiting for
 *       (an approval, a published grade);</li>
 *   <li><b>warn</b> for the two that need a decision (a rejection to fix, a
 *       possible integrity issue to look at);</li>
 *   <li><b>info</b> for the rest, which are updates rather than requests.</li>
 * </ul>
 * Nothing maps to <b>error</b>: an error toast means <i>your</i> action failed,
 * and none of these is the user's action failing (F11.3 keeps toasts and
 * notifications distinct on purpose).
 */
public final class NotificationPresenter {

    private NotificationPresenter() {
    }

    /**
     * @param type what happened
     * @return the Ikonli literal for the panel row's icon; never {@code null}
     */
    public static String iconFor(NotificationType type) {
        Objects.requireNonNull(type, "type");
        return switch (type) {
            case APPROVAL_REQUESTED -> Icons.APPROVALS;
            case APPROVAL_APPROVED -> Icons.CHECK;
            case APPROVAL_REJECTED -> Icons.ERROR;
            case GRADE_PUBLISHED -> Icons.GRADING;
            case TIME_EXTENDED -> Icons.CLOCK;
            case BOT_SOURCE_CHANGED -> Icons.BOT;
            case RELEASE_OPENING_SOON -> Icons.RELEASE;
            case INTEGRITY_ALERT -> Icons.WARNING;
        };
    }

    /**
     * Builds the toast a foreground push raises (E17.5).
     *
     * <p>The notification's own title and body are reused verbatim: a toast that
     * paraphrased the row the user is about to see in the panel would be one more
     * sentence to write, review and keep in step for no gain.
     *
     * @param notification the pushed notification
     * @return the toast to hand to the shell's {@code ToastStack}
     */
    public static ToastSpec toastFor(NotificationDto notification) {
        Objects.requireNonNull(notification, "notification");
        return switch (notification.type()) {
            case APPROVAL_APPROVED, GRADE_PUBLISHED ->
                    ToastSpec.success(notification.title(), notification.body());
            case APPROVAL_REJECTED, INTEGRITY_ALERT ->
                    ToastSpec.warn(notification.title(), notification.body());
            default -> ToastSpec.info(notification.title(), notification.body());
        };
    }

    /**
     * @param notification the row being rendered
     * @param now          the reference instant, injected so tests need no clock
     * @return the row's age, e.g. {@code "3 min ago"}
     */
    public static String ageOf(NotificationDto notification, Instant now) {
        Objects.requireNonNull(notification, "notification");
        return RelativeTime.of(notification.createdAt(), now);
    }

    /**
     * @return the row's accessible description: everything a sighted user reads
     *         from icon, weight and position, said out loud
     */
    public static String accessibleTextOf(NotificationDto notification, Instant now) {
        Objects.requireNonNull(notification, "notification");
        String unread = notification.isUnread() ? "Unread. " : "";
        String body = notification.body().isEmpty() ? "" : notification.body() + ". ";
        return unread + notification.title() + ". " + body + ageOf(notification, now);
    }
}
