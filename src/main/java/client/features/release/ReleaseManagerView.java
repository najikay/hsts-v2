package client.features.release;

import client.core.NavParams;
import client.core.Routes;
import client.ui.components.Buttons;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
import client.ui.components.StatusChip;
import client.ui.components.WarnConfirm;
import client.ui.screen.AbstractScreen;
import common.dto.release.ReleaseList;
import common.dto.release.ReleaseRow;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;

import java.time.ZoneId;

/**
 * The teacher's Release Manager (Presentation tier, E9.5/E9.6 — F5).
 *
 * <p>A renderer over {@link ReleaseManagerSession}. It owns no rules: which actions a row
 * offers comes from {@code ReleaseRow}'s own predicates, which are the wire's and therefore
 * the server's; what the sentences say comes from {@link ReleaseCopy}; whether a window is
 * legal comes from the request record both tiers share.
 *
 * <p>No refresh button anywhere (NFR-18). The first fetch subscribes, every change arrives as
 * a pushed row, and a one-second timeline re-renders the "opens in" and "left" lines between
 * pushes so the countdowns tick rather than freeze. The ageing is display only: the state
 * chip is the server's answer and is never recomputed here.
 *
 * <h2>The code is the point of this screen</h2>
 *
 * <p>After a release is created its code goes up in a panel, in the biggest type in the
 * product, spaced out character by character, with a copy button. That is not decoration:
 * S-17 says the code is delivered orally, so a teacher reads it off her screen to a room, and
 * every mis-heard character costs a student minutes. The panel stays until she dismisses it,
 * because somebody who looked away mid-sentence has to be able to look back.
 *
 * <h2>The two dangerous actions</h2>
 *
 * <p>Both go through {@code WarnConfirm}, and each says what it will actually do. Cancelling
 * is the milder one and its dialog says so ("nobody has sat it, so nothing is lost"); closing
 * early is the one that ends other people's exams, so its dialog counts them and states
 * F5.5's behaviour in the students' terms: anyone still working is handed in with what she
 * has saved, exactly as if her time had run out.
 */
public final class ReleaseManagerView extends AbstractScreen {

    /** How often the countdown lines are re-rendered between pushes. */
    private static final Duration TICK = Duration.seconds(1);

    private final BorderPane root = new BorderPane();
    private final VBox rows = new VBox(10);
    private final Label error = new Label();
    private final Button create = Buttons.primary(ReleaseCopy.CREATE_BUTTON);
    private final VBox reveal = new VBox(10);
    private final Label revealCode = new Label();
    private final Label copied = new Label(ReleaseCopy.CODE_COPIED);
    private final EmptyState empty =
            new EmptyState(Icons.RELEASE, ReleaseCopy.EMPTY_TITLE, ReleaseCopy.EMPTY_BODY);

    private ReleaseManagerSession session;
    private Timeline ticker;
    private ZoneId zone = ZoneId.systemDefault();

    @Override
    protected Parent build() {
        session = new ReleaseManagerSession(dispatcher(), eventBus());
        session.onUpdate(list -> render());

        create.setOnAction(e -> openCreateDialog());

        root.getStyleClass().add("release-manager");
        root.setTop(buildHeader());
        root.setCenter(buildBody());

        ticker = new Timeline(new KeyFrame(TICK, e -> renderRows()));
        ticker.setCycleCount(Animation.INDEFINITE);
        return root;
    }

    @Override
    public void onShow(NavParams params) {
        session.start();
        ticker.playFromStart();
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
        renderReveal();
        renderRows();
    }

    private void renderReveal() {
        session.lastCreated().ifPresentOrElse(row -> {
            revealCode.setText(spaced(row.code()));
            show(reveal, true);
        }, () -> show(reveal, false));
        show(copied, false);
    }

    private void renderRows() {
        ReleaseList list = session.releases();
        rows.getChildren().clear();
        if (list.isEmpty()) {
            rows.getChildren().add(empty);
            return;
        }
        for (ReleaseRow row : list.rows()) {
            rows.getChildren().add(rowNode(row));
        }
    }

    private VBox rowNode(ReleaseRow row) {
        Label name = new Label(row.examName());
        name.getStyleClass().addAll("strong", "release-name");

        Label course = new Label(row.courseName());
        course.getStyleClass().addAll("small", "muted");

        // LIVE pulses, by the design system's own rule: it is the one state a teacher scans
        // a list for, and the catalogue is what decides that rather than this screen.
        StatusChip chip = StatusChip.executionStatus(row.state().name());

        Label code = new Label(row.code());
        code.getStyleClass().addAll("mono", "release-code-inline");

        Label window = new Label(ReleaseCopy.window(row, zone));
        window.getStyleClass().addAll("small", "muted");

        Label status = new Label(ReleaseCopy.status(row, session.now(), zone));
        status.getStyleClass().addAll("small", "muted", "release-status-line");

        HBox top = new HBox(12, name, chip, Buttons.spacer(), code);
        top.setAlignment(Pos.CENTER_LEFT);

        HBox meta = new HBox(12, course, window, Buttons.spacer(), actions(row), status);
        meta.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(6, top, meta);
        card.getStyleClass().addAll("hsts-card", "compact", "release-row");
        return card;
    }

    /**
     * The actions this release offers, decided by the row and not by this screen.
     *
     * <p>{@code canCancel} and {@code canCloseEarly} are on the wire enum, so the button that
     * appears and the guard the server applies are the same rule expressed once. A row that
     * is over offers nothing, which is the honest answer rather than a disabled button
     * nobody can explain.
     */
    private HBox actions(ReleaseRow row) {
        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_LEFT);
        if (row.hasParticipants() || row.isLive()) {
            Button monitor = Buttons.secondary(ReleaseCopy.MONITOR_ACTION);
            monitor.getStyleClass().add("release-monitor");
            monitor.setOnAction(e -> navigator().navigate(Routes.MONITOR.id(),
                    NavParams.of("executionId", row.executionId())));
            actions.getChildren().add(monitor);
        }
        if (row.canCancel()) {
            Button cancel = Buttons.warn(ReleaseCopy.CANCEL_ACTION);
            cancel.getStyleClass().add("release-cancel");
            cancel.setOnAction(e -> confirmCancel(row));
            actions.getChildren().add(cancel);
        }
        if (row.canCloseEarly()) {
            Button close = Buttons.danger(ReleaseCopy.CLOSE_ACTION);
            close.getStyleClass().add("release-close");
            close.setOnAction(e -> confirmClose(row));
            actions.getChildren().add(close);
        }
        return actions;
    }

    // ===================== The three actions =============================

    private void openCreateDialog() {
        CreateReleaseDialog.show(window(), session.options(), session.now(), zone)
                .ifPresent(answer -> session.create(answer.examVersionId(),
                        answer.openAt(), answer.closeAt(), answer.code()));
    }

    /**
     * Calls off a scheduled release (F5.5).
     *
     * <p>Confirmed rather than immediate because the code stops working and a room may
     * already have been told to expect it, but styled as a warning rather than a danger: no
     * work is lost, and the dialog says so instead of implying otherwise.
     */
    private void confirmCancel(ReleaseRow row) {
        boolean confirmed = WarnConfirm.show(window(),
                WarnConfirm.spec(ReleaseCopy.CANCEL_TITLE)
                        .explanation(ReleaseCopy.cancelExplanation(row))
                        .confirmText(ReleaseCopy.CANCEL_CONFIRM)
                        .cancelText(ReleaseCopy.KEEP)
                        .warn());
        if (confirmed) {
            session.cancel(row.executionId());
        }
    }

    /**
     * Ends a live release now (F5.5).
     *
     * <p>Danger styling and a dialog that counts the people it affects, because this one ends
     * other people's exams. The explanation states the behaviour rather than the mechanism:
     * anyone still working is handed in with what she has saved, exactly as if her time had
     * run out.
     */
    private void confirmClose(ReleaseRow row) {
        boolean confirmed = WarnConfirm.show(window(),
                WarnConfirm.spec(ReleaseCopy.closeTitle(row))
                        .explanation(ReleaseCopy.closeExplanation(row))
                        .confirmText(ReleaseCopy.CLOSE_CONFIRM)
                        .cancelText(ReleaseCopy.KEEP_RUNNING)
                        .danger());
        if (confirmed) {
            session.closeEarly(row.executionId());
        }
    }

    // ===================== Layout ========================================

    private VBox buildHeader() {
        Label title = new Label(ReleaseCopy.TITLE);
        title.getStyleClass().add("h1");
        Label subtitle = new Label(ReleaseCopy.SUBTITLE);
        subtitle.getStyleClass().addAll("small", "muted");

        HBox top = new HBox(16, new VBox(2, title, subtitle), Buttons.spacer(), create);
        top.setAlignment(Pos.CENTER_LEFT);

        error.getStyleClass().addAll("small", "danger-text");
        error.setWrapText(true);
        show(error, false);

        VBox header = new VBox(14, top, buildReveal(), error);
        header.setPadding(new Insets(24, 28, 12, 28));
        return header;
    }

    /**
     * The code, big (S-17).
     *
     * <p>The largest type in the product and letter-spaced, because a teacher reads it off
     * this panel to a room and the failure mode is a mis-heard character. Selectable and
     * copyable for the same reason: a code that can only be looked at cannot be pasted into
     * the message she sends the one student who is late.
     */
    private VBox buildReveal() {
        Label heading = new Label(ReleaseCopy.CODE_TITLE);
        heading.getStyleClass().add("h2");
        Label body = new Label(ReleaseCopy.CODE_BODY);
        body.getStyleClass().addAll("small", "muted");
        body.setWrapText(true);

        revealCode.getStyleClass().addAll("mono", "release-code-big");

        Button copy = Buttons.secondary(ReleaseCopy.CODE_COPY);
        copy.getStyleClass().add("release-copy");
        copy.setOnAction(e -> copyCode());

        Button done = Buttons.outline(ReleaseCopy.CODE_DONE);
        done.setOnAction(e -> session.clearCreated());

        copied.getStyleClass().addAll("small", "ok-text");
        show(copied, false);

        HBox controls = new HBox(10, copy, done, copied);
        controls.setAlignment(Pos.CENTER_LEFT);

        reveal.getChildren().setAll(heading, body, revealCode, controls);
        reveal.getStyleClass().addAll("hsts-card", "raised", "release-reveal");
        show(reveal, false);
        return reveal;
    }

    private void copyCode() {
        session.lastCreated().ifPresent(row -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(row.code());
            Clipboard.getSystemClipboard().setContent(content);
            show(copied, true);
        });
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

    private Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }

    /**
     * The code with a space between every character, so it reads at a distance.
     *
     * <p>Done here rather than in CSS on purpose: JavaFX has no {@code -fx-letter-spacing},
     * and a declaration the toolkit ignores is a promise nobody keeps. Real spaces are also
     * what a person reading aloud pauses on. The clipboard gets the <b>unspaced</b> code,
     * because that is what a student types.
     */
    private static String spaced(String code) {
        return code == null ? "" : String.join(" ", code.split(""));
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
