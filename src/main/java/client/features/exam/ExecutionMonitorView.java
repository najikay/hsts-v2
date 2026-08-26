package client.features.exam;

import client.core.NavParams;
import client.core.Routes;
import client.ui.components.BackLink;
import client.ui.components.Buttons;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
import client.ui.components.StatusChip;
import client.ui.components.WarnConfirm;
import client.ui.components.logic.CountdownLogic;
import client.ui.screen.AbstractScreen;
import common.dto.exam.ExecutionMonitor;
import common.dto.exam.MonitorRow;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * The teacher's live execution monitor (Presentation tier, E11.2/E11.3 — F7.2, F7.1).
 *
 * <p>A renderer over {@link ExecutionMonitorSession}. It shows the three derived counters,
 * a row per student with her status, progress, remaining time and integrity flag, and the
 * one action that changes anything: adding minutes.
 *
 * <p>No refresh button anywhere (NFR-18). The first fetch subscribes, every change arrives
 * as a pushed snapshot, and a one-second timeline ages the remaining-time column between
 * pushes so a row of countdowns ticks rather than freezing. The ageing is display only:
 * the numbers come from the server, computed against the same derived deadlines the
 * students' own countdowns use, so the teacher's screen cannot tell her something different
 * from what the student is looking at.
 *
 * <p>The integrity flag (C-4) is a chip on the row, worded as something to look at rather
 * than an accusation: the server cannot know intent, and the teacher is the one who decides
 * what it means.
 *
 * <h2>Arriving with no sitting ⚑ (U-1)</h2>
 *
 * <p>Every other way in carries one: a Releases row's Monitor button, a C-4 alert, an
 * "opens soon" deep link. The rail item U-1 enabled cannot, because a rail is a list of
 * places and the sitting is chosen on Releases. Asking for execution zero would have put the
 * server's refusal sentence under an empty title, so {@link #onShow} does not ask at all: it
 * renders {@link ExamCopy#MONITOR_NO_SITTING_TITLE} with a button to the screen where the
 * choice is made, and the header full of controls for a sitting that is not there stays off.
 */
public final class ExecutionMonitorView extends AbstractScreen {

    /** The nav parameter naming the sitting to watch; spelled by every caller that has one. */
    public static final String PARAM_EXECUTION = "executionId";

    /** How often the remaining-time column is re-rendered between pushes. */
    private static final Duration TICK = Duration.seconds(1);

    private final BorderPane root = new BorderPane();
    private final Label examName = new Label();
    private final Label meta = new Label();
    private final Label started = new Label("0");
    private final Label finished = new Label("0");
    private final Label timedOut = new Label("0");
    private final VBox rows = new VBox(8);
    private final Label error = new Label();
    private final Spinner<Integer> minutes = new Spinner<>(1, 480, 15, 5);
    private final Button extend = Buttons.primary("Add time");
    private final EmptyState empty = new EmptyState(Icons.MONITOR, "Nobody has started yet",
            "Students appear here as soon as they enter the code.");

    private ExecutionMonitorSession session;
    private Timeline ticker;
    private VBox header;
    private ScrollPane body;
    private EmptyState chooser;

    @Override
    protected Parent build() {
        session = new ExecutionMonitorSession(dispatcher(), eventBus());
        session.onUpdate(snapshot -> render());

        extend.setOnAction(e -> confirmExtend());
        minutes.setEditable(true);
        minutes.setPrefWidth(90);

        header = buildHeader();
        body = buildBody();
        chooser = new EmptyState(Icons.RELEASE, ExamCopy.MONITOR_NO_SITTING_TITLE,
                ExamCopy.MONITOR_NO_SITTING_HINT)
                .action(ExamCopy.MONITOR_NO_SITTING_ACTION,
                        () -> navigator().navigate(Routes.RELEASES.id()));

        root.getStyleClass().add("execution-monitor");
        root.setTop(header);
        root.setCenter(body);

        ticker = new Timeline(new KeyFrame(TICK, e -> renderRows()));
        ticker.setCycleCount(Animation.INDEFINITE);
        return root;
    }

    @Override
    public void onShow(NavParams params) {
        long executionId = params.getLong(PARAM_EXECUTION, 0);
        if (executionId <= 0) {
            showChooser();
            return;
        }
        root.setTop(header);
        root.setCenter(body);
        session.start(executionId);
        ticker.playFromStart();
    }

    /**
     * The paramless entry (U-1): offer the choice instead of making one up.
     *
     * <p>{@code session.stop()} rather than "do nothing", because the screen is built once and
     * navigated to many times: a teacher who watched a sitting, left, and came back from the
     * rail must not find the old sitting's rows still on screen under a title inviting her to
     * pick one.
     */
    private void showChooser() {
        ticker.stop();
        session.stop();
        rows.getChildren().clear();
        root.setTop(null);
        root.setCenter(chooser);
    }

    @Override
    public void onHide() {
        ticker.stop();
        session.stop();
    }

    @Override
    public boolean listensToEvents() {
        // The session subscribes itself; the screen has no @Subscribe method.
        return false;
    }

    // ===================== Rendering =====================================

    private void render() {
        error.setText(session.lastError());
        show(error, !session.lastError().isBlank());
        session.snapshot().ifPresent(snapshot -> {
            examName.setText(snapshot.examName());
            meta.setText(snapshot.courseCode() + " · code " + snapshot.code()
                    + " · " + ExamCopy.minutes(snapshot.durationMinutes())
                    + (snapshot.extraMinutes() > 0
                            ? " (including " + ExamCopy.minutes(snapshot.extraMinutes()) + " added)"
                            : "")
                    + " · closes " + ExamClock.localTime(snapshot.closesAt()));
            started.setText(Long.toString(snapshot.counts().started()));
            finished.setText(Long.toString(snapshot.counts().finished()));
            timedOut.setText(Long.toString(snapshot.counts().timedOut()));
            extend.setDisable(!snapshot.live());
            minutes.setDisable(!snapshot.live());
        });
        renderRows();
    }

    private void renderRows() {
        ExecutionMonitor snapshot = session.snapshot().orElse(null);
        if (snapshot == null) {
            return;
        }
        rows.getChildren().clear();
        if (snapshot.isEmpty()) {
            rows.getChildren().add(empty);
            return;
        }
        for (MonitorRow row : snapshot.rows()) {
            rows.getChildren().add(rowNode(row, snapshot));
        }
    }

    private HBox rowNode(MonitorRow row, ExecutionMonitor snapshot) {
        Label name = new Label(row.studentName());
        name.getStyleClass().add("strong");
        name.setMinWidth(180);

        StatusChip status = StatusChip.attemptStatus(row.state().name());

        Label progress = new Label(row.progressLabel());
        progress.getStyleClass().addAll("small", "muted");
        progress.setMinWidth(60);

        Label remaining = new Label(row.state().isLive()
                ? CountdownLogic.format(session.remainingFor(row))
                : (row.actualMinutes() == null ? "" : ExamCopy.minutes(row.actualMinutes())));
        remaining.getStyleClass().addAll("mono", "small");
        remaining.setMinWidth(70);

        HBox line = new HBox(12);
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().add("monitor-row");
        line.getChildren().addAll(name, status, progress);

        if (row.isFlagged()) {
            Label flag = new Label(row.integrity().label());
            flag.getStyleClass().addAll("hsts-chip", "warn", "integrity-flag");
            flag.setTooltip(new Tooltip("Opened another course's study bot at "
                    + ExamClock.localTime(row.integrity().at())
                    + ". The server cannot know why; this is for you to judge."));
            line.getChildren().add(flag);
        }
        if (row.hasAttentionEvents()) {
            line.getChildren().add(attentionChip(row));
        }

        line.getChildren().addAll(Buttons.spacer(), remaining);
        return line;
    }

    /**
     * The E11.7 attention signal (F7.1b).
     *
     * <p><b>Neutral, never danger.</b> The integrity flag beside it is amber because C-4 is a
     * rule the student was warned about and chose to cross; this is not a rule at all. A
     * window losing focus has a dozen benign causes — a notification, a screen reader, an
     * invigilator handing over a form — and the server cannot tell any of them from a browser.
     * Colouring it as an alarm would make the software accuse somebody it has no grounds to
     * accuse, which is exactly what F7.1b's "signal, not verdict" forbids.
     *
     * <p>The tooltip says the limit out loud, so a teacher acting on this knows what it is
     * worth: it is detected on the student's own machine.
     */
    private static Label attentionChip(MonitorRow row) {
        Label chip = new Label(row.attention().label());
        chip.getStyleClass().addAll("hsts-chip", "neutral", "attention-chip");
        chip.setTooltip(new Tooltip("Her exam window last lost focus at "
                + ExamClock.localTime(row.attention().lastAt())
                + ". Benign reasons are common and this is detected on her own machine, "
                + "so it is something to weigh, not a finding."));
        return chip;
    }

    // ===================== The one action ================================

    /**
     * Adds minutes to every student sitting this execution (F7.1, S-20).
     *
     * <p>Confirmed rather than immediate, because it is irreversible: there is no
     * "un-extend", and a mistyped 150 in a spinner would be discovered by a room full of
     * students. The dialog says exactly what will happen and to whom.
     */
    private void confirmExtend() {
        int amount = minutes.getValue();
        long live = session.snapshot().map(snapshot -> snapshot.counts().inProgress()).orElse(0L);
        boolean confirmed = WarnConfirm.show(
                root.getScene() == null ? null : root.getScene().getWindow(),
                WarnConfirm.spec("Add " + ExamCopy.minutes(amount) + " to this exam?")
                        .explanation("Everyone sitting it gets the extra time and is told immediately. "
                                + live + " student" + (live == 1 ? " is" : "s are")
                                + " working right now. This cannot be undone.")
                        .confirmText("Add time")
                        .cancelText("Cancel")
                        .warn());
        if (confirmed) {
            session.extend(amount);
        }
    }

    // ===================== Layout ========================================

    private VBox buildHeader() {
        examName.getStyleClass().add("h1");
        meta.getStyleClass().addAll("small", "muted");

        HBox controls = new HBox(8, new Label("Add"), minutes, new Label("minutes"), extend);
        controls.setAlignment(Pos.CENTER_LEFT);

        HBox top = new HBox(16,
                new VBox(2, BackLink.to(navigator(), Routes.RELEASES.id(), "Releases"),
                        examName, meta),
                Buttons.spacer(), controls);
        top.setAlignment(Pos.CENTER_LEFT);

        GridPane counters = new GridPane();
        counters.setHgap(16);
        counters.add(counter("Started", started), 0, 0);
        counters.add(counter("Handed in", finished), 1, 0);
        counters.add(counter("Timed out", timedOut), 2, 0);

        error.getStyleClass().addAll("small", "danger-text");
        error.setWrapText(true);
        show(error, false);

        VBox header = new VBox(14, top, counters, error);
        header.setPadding(new Insets(24, 28, 12, 28));
        return header;
    }

    private ScrollPane buildBody() {
        rows.setPadding(new Insets(4, 28, 24, 28));
        ScrollPane scroller = new ScrollPane(rows);
        scroller.setFitToWidth(true);
        scroller.getStyleClass().add("hsts-page");
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroller, Priority.ALWAYS);
        return scroller;
    }

    private static VBox counter(String label, Label value) {
        value.getStyleClass().add("stat-value");
        Label caption = new Label(label);
        caption.getStyleClass().add("stat-label");
        VBox card = new VBox(2, value, caption);
        card.getStyleClass().addAll("hsts-card", "compact", "monitor-counter");
        card.setMinWidth(120);
        return card;
    }

    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
