package client.features.home;

import client.core.Routes;
import client.events.FxThreadPoster;
import client.features.results.MyGradesCopy;
import client.net.RequestDispatcher;
import client.ui.components.logic.ChipSpec;
import client.ui.components.logic.ChipTone;
import common.dto.grading.MyGrades;
import common.dto.grading.StudentGradeRow;
import common.dto.results.ResultStatistics;
import common.protocol.Message;
import common.protocol.Verb;
import client.events.ClientEventBus;
import client.events.ServerPushEvent;
import common.dto.notify.NotificationDto;
import common.dto.notify.NotificationType;
import org.greenrobot.eventbus.Subscribe;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The student's dashboard cards (Presentation tier, UI wave 1 — F-10).
 *
 * <h2>One card, and one that was dropped</h2>
 *
 * <p>The wave asked for three: next or live exam, latest grade, and the study bot.
 * Only two were buildable and this class is why.
 *
 * <p><b>Latest grade</b> comes from {@code MY_GRADES_GET}, the same read the My
 * Grades screen makes. Only approved rows ever arrive on that verb (C-3, S-24),
 * so there is no filtering here — a client-side filter would imply the server
 * might send something it should not.
 *
 * <p><b>The study bot</b> needs no read at all: it is an entry point, and the card
 * is a labelled door.
 *
 * <p><b>"Next or live exam" was dropped</b>, and deliberately rather than by
 * omission. No verb answers it. A student reaches a sitting by typing the
 * four-character code a teacher reads out ({@code EXAM_JOIN}, S-18); there is no
 * "list the sittings I could join" read on the wire, and inventing one would mean
 * a protocol change, a handler and a service — none of which this wave is allowed
 * to touch, and none of which should be decided by a dashboard card. The code
 * entry that already sits on this screen remains the real answer to "how do I get
 * into my exam", and it is not a summary, so it stays where it is rather than
 * becoming a card.
 *
 * <h2>Which grade is "latest"</h2>
 *
 * <p>The one approved most recently, not the highest and not the last in the
 * list. A row with no approval time sorts oldest rather than throwing: the server
 * should not send one, and a dashboard is not the place to discover that it did.
 */
public final class StudentDashboardSession {

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;

    private Runnable onChange = () -> { };

    private DashboardCard latestGrade = loadingGrade();

    private int gradeCount;
    private String newestExam;

    /** Whether {@link #load}'s read is still outstanding; the {@link #refresh} guard (U-63). */
    private int pending;

    public StudentDashboardSession(RequestDispatcher dispatcher, FxThreadPoster poster) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
    }

    /** Registers the "re-read me and re-render" callback. */
    public StudentDashboardSession onChange(Runnable listener) {
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /**
     * @return the cards, in the order they are laid out. The bot card is a
     *         constant because it asks the server nothing
     */
    public List<DashboardCard> cards() {
        return List.of(latestGrade, botCard());
    }

    /** The study bot entry. Always ready: it is a door, not a number. */
    static DashboardCard botCard() {
        return new DashboardCard(DashboardCopy.BOT_KICKER, DashboardCopy.BOT_TITLE,
                DashboardCopy.BOT_VALUE, DashboardCopy.BOT_HINT, DashboardCopy.BOT_LINK, null,
                Routes.BOT_CHAT.id(), DashboardCard.State.READY);
    }

    /**
     * @return the one sentence under the greeting, composed from the grades this
     *         session already read. The bot card contributes nothing: it asks the
     *         server nothing, so it has no news
     */
    public String summary() {
        return DashboardSummary.student(gradeCount, newestExam,
                latestGrade.state() == DashboardCard.State.LOADING,
                latestGrade.state() == DashboardCard.State.FAILED);
    }

    // ===================== Live (U-63) ===================================

    /**
     * Subscribes the dashboard to the app bus (NFR-18, U-63).
     *
     * <p><b>{@link #onServerPush(ServerPushEvent)} must stay public on a public class</b>: the
     * bus invokes reflectively, and a subscriber it cannot reach registers happily and then
     * fails silently on every delivery.
     *
     * @param eventBus the app bus; pushes arrive on it already on the FX thread
     * @return this, for chaining beside {@link #onChange(Runnable)}
     */
    public StudentDashboardSession subscribeTo(ClientEventBus eventBus) {
        Objects.requireNonNull(eventBus, "eventBus").register(this);
        return this;
    }

    /**
     * A server push landed; re-read the card if a grade of hers was published (U-63).
     *
     * <p>Two ways of learning the same thing, and both are honoured because the server sends
     * both: {@code PUSH_GRADE_PUBLISHED} carries the row and goes only to the student it
     * belongs to ({@code GradingHandlers.publish}), and a durable {@code GRADE_PUBLISHED}
     * notification goes out beside it. Reacting to either and not to the other would make this
     * card's freshness depend on which of two messages happened to arrive first.
     *
     * <p>The pushed row is not adopted, deliberately. This card is "your latest grade" out of a
     * whole {@code MyGrades} answer, and deciding whether one pushed row is the latest is the
     * ordering question the server has already answered. {@code MyGradesSession} takes the same
     * line.
     *
     * @param event the push, straight off the bus
     */
    @Subscribe
    public void onServerPush(ServerPushEvent event) {
        if (event == null) {
            return;
        }
        if (event.verb() == Verb.PUSH_GRADE_PUBLISHED) {
            refresh();
            return;
        }
        if (event.verb() == Verb.PUSH_NOTIFICATION
                && event.payload() instanceof NotificationDto item
                && item.type() == NotificationType.GRADE_PUBLISHED) {
            refresh();
        }
    }

    /**
     * Re-reads the card (U-63).
     *
     * <p>Ignored while the read is in flight, which here is doing real work rather than being a
     * formality: the two messages above arrive within milliseconds of each other for the same
     * event, so without this guard every published grade would cost two identical reads that
     * could settle in either order.
     */
    public void refresh() {
        if (pending > 0) {
            return;
        }
        // Without the blanking (S3 sweep): routing through load() flashed the grade card
        // back to a skeleton on every push. The settled card stays until the answer lands.
        sendRead();
    }

    /** Sends the grades read. */
    public void load() {
        latestGrade = loadingGrade();
        gradeCount = 0;
        newestExam = null;
        onChange.run();
        sendRead();
    }

    /** The one read, shared by the blanking visit and the quiet push re-read (U-63). */
    private void sendRead() {
        pending = 1;
        dispatcher.send(Verb.MY_GRADES_GET, null)
                .whenComplete((response, failure) -> poster.run(() -> {
                    // Counted off here rather than inside settle, which returns early on the
                    // failure path; a leaked counter is a card that never refreshes again.
                    pending = 0;
                    settle(response, failure);
                }));
    }

    private void settle(Message response, Throwable failure) {
        if (!(TeacherDashboardSession.payloadOf(response, failure) instanceof MyGrades answer)) {
            latestGrade = DashboardCard.failed(DashboardCopy.LATEST_GRADE_KICKER,
                    DashboardCopy.LATEST_GRADE_TITLE, DashboardCopy.LATEST_GRADE_LINK,
                    Routes.MY_GRADES.id());
            onChange.run();
            return;
        }
        gradeCount = answer.grades().size();
        newestExam = newest(answer.grades()).map(StudentGradeRow::examName).orElse(null);
        latestGrade = newest(answer.grades())
                .map(row -> new DashboardCard(DashboardCopy.LATEST_GRADE_KICKER,
                        DashboardCopy.LATEST_GRADE_TITLE,
                        Integer.toString(row.effectiveScore()), row.examName(),
                        DashboardCopy.LATEST_GRADE_LINK, chipFor(row.effectiveScore()),
                        Routes.MY_GRADES.id(), DashboardCard.State.READY))
                .orElseGet(() -> new DashboardCard(DashboardCopy.LATEST_GRADE_KICKER,
                        DashboardCopy.LATEST_GRADE_TITLE, "0",
                        DashboardCopy.LATEST_GRADE_EMPTY, DashboardCopy.LATEST_GRADE_LINK, null,
                        Routes.MY_GRADES.id(), DashboardCard.State.EMPTY));
        onChange.run();
    }

    /** @return the most recently approved grade, or empty when there are none. */
    static Optional<StudentGradeRow> newest(List<StudentGradeRow> grades) {
        return grades.stream().max(Comparator.comparing(
                row -> row.approvedAt() == null ? Instant.EPOCH : row.approvedAt()));
    }

    /**
     * @return the pass or fail pill for a mark, from the frozen pass mark the
     *         server marks against ({@code ResultStatistics.PASS_MARK}) rather
     *         than from a second copy of the number in the client
     */
    static ChipSpec chipFor(int score) {
        return score >= ResultStatistics.PASS_MARK
                ? ChipSpec.of(MyGradesCopy.CHIP_PASSED, ChipTone.OK)
                : ChipSpec.of(MyGradesCopy.CHIP_BELOW, ChipTone.WARN);
    }

    private static DashboardCard loadingGrade() {
        return DashboardCard.loading(DashboardCopy.LATEST_GRADE_KICKER,
                DashboardCopy.LATEST_GRADE_TITLE, DashboardCopy.LATEST_GRADE_HINT,
                DashboardCopy.LATEST_GRADE_LINK, Routes.MY_GRADES.id());
    }
}
