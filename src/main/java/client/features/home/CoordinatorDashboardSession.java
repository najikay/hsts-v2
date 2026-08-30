package client.features.home;

import client.core.Routes;
import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import client.ui.components.logic.ChipSpec;
import client.ui.components.logic.ChipTone;
import common.dto.approval.ApprovalQueue;
import common.dto.approval.ApprovalRow;
import common.protocol.Message;
import common.protocol.Verb;
import client.events.ClientEventBus;
import client.events.ServerPushEvent;
import common.dto.notify.NotificationDto;
import common.dto.notify.NotificationType;
import org.greenrobot.eventbus.Subscribe;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;

/**
 * The coordinator's dashboard (Presentation tier, UI wave 1 — F-10).
 *
 * <h2>Two cards, one verb</h2>
 *
 * <p>{@code APPROVALS_QUEUE_GET} answers both questions the coordinator's home
 * asks: how many exams are waiting, and who is submitting them. The second is
 * derived here rather than fetched, because {@link ApprovalRow} already carries
 * its author's name and a "list the teachers I coordinate" verb does not exist.
 * Deriving it is honest about what it means — <i>teachers with something in the
 * queue right now</i>, which is what a coordinator's home should surface — and it
 * keeps the wave's promise that no new verb is invented for a dashboard.
 *
 * <p>The distinct-author count is case-insensitive and ordered, so it is stable
 * across reads and two spellings of one name cannot inflate it.
 */
public final class CoordinatorDashboardSession {

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;

    private Runnable onChange = () -> { };

    private DashboardCard approvals = loadingApprovals();
    private DashboardCard teachers = loadingTeachers();

    private int waitingCount;
    private int teacherCount;

    /** Whether {@link #load}'s read is still outstanding; the {@link #refresh} guard (U-63). */
    private int pending;

    public CoordinatorDashboardSession(RequestDispatcher dispatcher, FxThreadPoster poster) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
    }

    /** Registers the "re-read me and re-render" callback. */
    public CoordinatorDashboardSession onChange(Runnable listener) {
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /** @return the cards, in the order they are laid out. */
    public List<DashboardCard> cards() {
        return List.of(approvals, teachers);
    }

    /**
     * @return the one sentence under the greeting, composed from the numbers
     *         these two cards already loaded
     */
    public String summary() {
        return DashboardSummary.coordinator(waitingCount, teacherCount,
                approvals.state() == DashboardCard.State.LOADING,
                approvals.state() == DashboardCard.State.FAILED);
    }

    // ===================== Live (U-63) ===================================

    /**
     * Subscribes the dashboard to the app bus (NFR-18, U-63).
     *
     * <p><b>{@link #onServerPush(ServerPushEvent)} must stay public on a public class</b>: the
     * bus invokes reflectively, and a subscriber it cannot reach registers happily and then
     * fails silently on every delivery. {@code ApprovalQueueSession} spells the trap out.
     *
     * @param eventBus the app bus; pushes arrive on it already on the FX thread
     * @return this, for chaining beside {@link #onChange(Runnable)}
     */
    public CoordinatorDashboardSession subscribeTo(ClientEventBus eventBus) {
        Objects.requireNonNull(eventBus, "eventBus").register(this);
        return this;
    }

    /**
     * A server push landed; re-read if it changes what these two cards say (U-63).
     *
     * <p>The same two types as {@code ApprovalQueueSession}, and for the same reason, because
     * both cards here are built from the same {@code APPROVALS_QUEUE_GET} that fills her queue:
     * {@code APPROVAL_REQUESTED} puts an exam in it and {@code APPROVAL_SUPERSEDED} takes one
     * out. Her home screen and her queue therefore cannot disagree about how many are waiting,
     * which they could until U-63, since the queue subscribed under B-30 and this did not.
     *
     * @param event the push, straight off the bus
     */
    @Subscribe
    public void onServerPush(ServerPushEvent event) {
        if (event == null || event.verb() != Verb.PUSH_NOTIFICATION) {
            return;
        }
        if (event.payload() instanceof NotificationDto item
                && (item.type() == NotificationType.APPROVAL_REQUESTED
                    || item.type() == NotificationType.APPROVAL_SUPERSEDED)) {
            refresh();
        }
    }

    /**
     * Re-reads both cards (U-63).
     *
     * <p>Ignored while the read is in flight: that answer is at least as new as this push, and
     * a second request behind it could settle out of order and put the older count back.
     */
    public void refresh() {
        if (pending > 0) {
            return;
        }
        load();
    }

    /** Sends the one read both cards are built from. */
    public void load() {
        approvals = loadingApprovals();
        teachers = loadingTeachers();
        waitingCount = 0;
        teacherCount = 0;
        onChange.run();

        pending = 1;
        dispatcher.send(Verb.APPROVALS_QUEUE_GET, null)
                .whenComplete((response, failure) -> poster.run(() -> {
                    // Counted off here rather than inside settle, which returns early on the
                    // failure path: a counter that leaks on an error is a dashboard that stops
                    // refreshing after the first dropped connection.
                    pending = 0;
                    settle(response, failure);
                }));
    }

    private void settle(Message response, Throwable failure) {
        if (!(TeacherDashboardSession.payloadOf(response, failure) instanceof ApprovalQueue queue)) {
            approvals = DashboardCard.failed(DashboardCopy.APPROVALS_KICKER,
                    DashboardCopy.APPROVALS_TITLE, DashboardCopy.APPROVALS_LINK,
                    Routes.APPROVALS.id());
            teachers = DashboardCard.failed(DashboardCopy.TEACHERS_KICKER,
                    DashboardCopy.TEACHERS_TITLE, DashboardCopy.TEACHERS_LINK,
                    Routes.APPROVALS.id());
            onChange.run();
            return;
        }
        waitingCount = queue.rows().size();
        teacherCount = distinctAuthors(queue.rows());

        approvals = DashboardCard.counted(DashboardCopy.APPROVALS_KICKER,
                DashboardCopy.APPROVALS_TITLE, waitingCount, DashboardCopy.APPROVALS_HINT,
                DashboardCopy.APPROVALS_EMPTY, DashboardCopy.APPROVALS_LINK,
                Routes.APPROVALS.id());
        approvals = approvals.withChip(waitingCount > 0
                ? ChipSpec.of(DashboardCopy.CHIP_TO_DO, ChipTone.WARN)
                : ChipSpec.of(DashboardCopy.CHIP_DONE, ChipTone.OK));
        teachers = DashboardCard.counted(DashboardCopy.TEACHERS_KICKER,
                DashboardCopy.TEACHERS_TITLE, teacherCount, DashboardCopy.TEACHERS_HINT,
                DashboardCopy.TEACHERS_EMPTY, DashboardCopy.TEACHERS_LINK,
                Routes.APPROVALS.id());
        onChange.run();
    }

    /**
     * @return how many different teachers have an exam in the queue. Blank author
     *         names are not counted: an unnamed author is a data problem, not a
     *         teacher, and counting it would put a person on the card who is not
     *         in the list behind it
     */
    static int distinctAuthors(List<ApprovalRow> rows) {
        TreeSet<String> names = new TreeSet<>();
        for (ApprovalRow row : rows) {
            String author = row.authorName();
            if (author != null && !author.isBlank()) {
                names.add(author.trim().toLowerCase(Locale.ROOT));
            }
        }
        return names.size();
    }

    private static DashboardCard loadingApprovals() {
        return DashboardCard.loading(DashboardCopy.APPROVALS_KICKER,
                DashboardCopy.APPROVALS_TITLE, DashboardCopy.APPROVALS_HINT,
                DashboardCopy.APPROVALS_LINK, Routes.APPROVALS.id());
    }

    private static DashboardCard loadingTeachers() {
        return DashboardCard.loading(DashboardCopy.TEACHERS_KICKER, DashboardCopy.TEACHERS_TITLE,
                DashboardCopy.TEACHERS_HINT, DashboardCopy.TEACHERS_LINK, Routes.APPROVALS.id());
    }
}
