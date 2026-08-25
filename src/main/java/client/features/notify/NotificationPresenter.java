package client.features.notify;

import client.core.NavParams;
import client.core.Routes;
import client.ui.components.Icons;
import client.ui.components.logic.RelativeTime;
import client.ui.components.logic.ToastSpec;
import common.dto.notify.NavRef;
import common.dto.notify.NotificationDto;
import common.dto.notify.NotificationType;

import java.time.Instant;
import java.util.Map;
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
            // A newer version replaced one in the queue (E8.2). The exams icon rather than a
            // warning: nothing is wrong, the coordinator's list simply changed underneath her.
            case APPROVAL_SUPERSEDED -> Icons.EXAMS;
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
    /**
     * The tone of a row's icon badge in the popover (UI wave 2).
     *
     * <p>The remodel gives every row a 34px rounded badge whose soft background
     * says at a glance what kind of news it is, and the three tones are the same
     * three {@link #toastFor} already decides between — deliberately, because a
     * push that arrived as a green toast and is then listed with a red badge has
     * told the reader two different things about one event.
     *
     * @return the style-class suffix, one of {@code ok}, {@code danger} or
     *         {@code accent}
     */
    public static String badgeToneFor(NotificationType type) {
        Objects.requireNonNull(type, "type");
        return switch (type) {
            case APPROVAL_APPROVED, GRADE_PUBLISHED -> "ok";
            case APPROVAL_REJECTED, INTEGRITY_ALERT -> "danger";
            default -> "accent";
        };
    }

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

    // ===================== The deep link ==================================

    /**
     * Turns a notification's reference into the parameters its destination reads ⚑.
     *
     * <p><b>The defect this fixes affected every notification in the app.</b>
     * {@code NotificationsPanel.activate} called the one-argument
     * {@code navigate(routeId)}, which passes {@link NavParams#empty()}, so
     * {@link NavRef#entityId()} — set by {@code NotificationCatalog} on every
     * draft it writes — was dropped at the last hop. The panel opened the right
     * <em>screen</em> and never told it <em>which row</em>, which for F4.2's
     * "the reason is visible on the exam" means a teacher with exams in two
     * courses lands on whichever one her list happens to open on.
     *
     * <h2>Why a table and not one canonical key</h2>
     *
     * <p>There is no single parameter name to pass it under, and inventing one
     * would mean editing every destination screen to read it. The screens already
     * name what they want and have since E4.2: {@code ExamListView} and
     * {@code ExamPreviewView} read {@code examVersionId},
     * {@code ExecutionMonitorView} reads {@code executionId}. So the mapping is
     * from the route the server named to the key that route's screen asks for,
     * written once, here, where it is a pure function and can be tested. The
     * routes are keyed off {@link Routes}' own ids rather than re-typed literals,
     * because those are the ids the server's catalog was written against and a
     * rename that missed one should break the build, not the deep link.
     *
     * <p><b>A route not in the table gets no parameter, and that is deliberate.</b>
     * {@link Routes#BOT_MANAGER} is the case that proves it: the catalog's
     * {@code botSourceChanged} carries a <em>bot</em> id, while
     * {@code BotManagerView.PARAM_COURSE} wants a course <em>code</em>, a
     * {@code String}. Handing a {@code Long} to that key would not deep-link, it
     * would throw {@link IllegalArgumentException} out of
     * {@link NavParams#get(String, Class)} on a bell click. Navigating to the
     * screen with nothing is the behaviour every notification had before this
     * fix, which is the honest fallback; making that one deep-link needs the
     * catalog to carry a course code, which is a wire change and not this one.
     *
     * @param ref the reference the notification travelled with
     * @return the parameters to navigate with; {@link NavParams#empty()} when the
     *         reference has no id, or when its route takes none
     */
    public static NavParams paramsFor(NavRef ref) {
        Objects.requireNonNull(ref, "ref");
        String key = ref.entityId() == null ? null : PARAM_BY_ROUTE.get(ref.route());
        return key == null ? NavParams.empty() : NavParams.of(key, ref.entityId());
    }

    /**
     * Route id -> the navigation parameter that route's screen reads.
     *
     * <p>Every entry is the key a real {@code onShow(NavParams)} looks up, named
     * beside it. A route absent from here navigates with no parameters.
     */
    private static final Map<String, String> PARAM_BY_ROUTE = Map.of(
            // ExamListView.onShow -> params.getLong("examVersionId", 0) (E7.10).
            Routes.EXAMS.id(), "examVersionId",
            // The approval queue's rows are versions too, so the id means the same
            // thing here; ExamPreviewView reads the identical key (E8.4).
            Routes.APPROVALS.id(), "examVersionId",
            // ExecutionMonitorView.onShow -> params.getLong("executionId", 0) (E11).
            Routes.MONITOR.id(), "executionId",
            // The catalog hands these two an execution id as well.
            Routes.TAKE_EXAM.id(), "executionId",
            Routes.RELEASES.id(), "executionId",
            // The catalog's gradePublished carries the attempt the grade is for.
            Routes.MY_GRADES.id(), "attemptId");
}
