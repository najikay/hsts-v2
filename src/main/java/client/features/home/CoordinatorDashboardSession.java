package client.features.home;

import client.core.Routes;
import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import common.dto.approval.ApprovalQueue;
import common.dto.approval.ApprovalRow;
import common.protocol.Message;
import common.protocol.Verb;

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

    /** Sends the one read both cards are built from. */
    public void load() {
        approvals = loadingApprovals();
        teachers = loadingTeachers();
        onChange.run();

        dispatcher.send(Verb.APPROVALS_QUEUE_GET, null)
                .whenComplete((response, failure) -> poster.run(() -> settle(response, failure)));
    }

    private void settle(Message response, Throwable failure) {
        if (!(TeacherDashboardSession.payloadOf(response, failure) instanceof ApprovalQueue queue)) {
            approvals = DashboardCard.failed(DashboardCopy.APPROVALS_TITLE, Routes.APPROVALS.id());
            teachers = DashboardCard.failed(DashboardCopy.TEACHERS_TITLE, Routes.APPROVALS.id());
            onChange.run();
            return;
        }
        approvals = DashboardCard.counted(DashboardCopy.APPROVALS_TITLE, queue.rows().size(),
                DashboardCopy.APPROVALS_HINT, DashboardCopy.APPROVALS_EMPTY,
                Routes.APPROVALS.id());
        teachers = DashboardCard.counted(DashboardCopy.TEACHERS_TITLE,
                distinctAuthors(queue.rows()), DashboardCopy.TEACHERS_HINT,
                DashboardCopy.TEACHERS_EMPTY, Routes.APPROVALS.id());
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
        return DashboardCard.loading(DashboardCopy.APPROVALS_TITLE, DashboardCopy.APPROVALS_HINT,
                Routes.APPROVALS.id());
    }

    private static DashboardCard loadingTeachers() {
        return DashboardCard.loading(DashboardCopy.TEACHERS_TITLE, DashboardCopy.TEACHERS_HINT,
                Routes.APPROVALS.id());
    }
}
