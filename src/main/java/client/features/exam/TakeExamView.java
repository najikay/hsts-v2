package client.features.exam;

import client.core.NavParams;
import client.core.Routes;
import client.ui.components.CountdownTimer;
import client.ui.components.ReconnectBanner;
import client.ui.components.ToastStack;
import client.ui.components.WarnConfirm;
import client.ui.components.logic.CountdownLogic;
import client.ui.screen.AbstractScreen;
import common.dto.exam.AttemptForm;
import common.dto.exam.AttemptOutcome;
import common.dto.exam.TimerExtended;
import javafx.scene.Parent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Taking an exam, from the code screen to the takeover (Presentation tier, E10.9–E10.15).
 *
 * <p>One screen for the whole flow, because it is one continuous thing to the person doing
 * it and because the alternative — four routes — would make "the exam is unreachable once
 * it is over" (F6.4) a matter of guarding three navigations instead of one. Here the guard
 * is structural: the ending takeover covers the paper, its single button leaves, and
 * leaving stops the session and clears the model, so a re-entry starts at the code screen
 * and the server's own answer ("you have already handed this in") is what she sees.
 *
 * <p>All state lives in {@link ExamEntrySession} and {@link ExamAttemptSession}, both
 * FX-free and unit-tested; this class wires them to nodes and to the three designed
 * moments PRD §4 specifies:
 *
 * <ul>
 *   <li><b>Time Extended</b> (F7.1 ⚑): the countdown chip flashes green with a glow, a
 *       floating "+15:00" rises off it, and a toast names the teacher and the new end
 *       time. Never silent.</li>
 *   <li><b>Submit Confirm</b> (F6.9): a {@code WarnConfirm} carrying the answer-summary
 *       grid, with clickable chips that jump to the question, and the remaining time
 *       spelled out.</li>
 *   <li><b>Time Up</b> and <b>Submitted</b> (F6.4/F6.10): {@link ExamDoneView}, one
 *       component with two moods.</li>
 * </ul>
 *
 * <p>A dropped socket raises the reconnect banner and nothing else: the answers are on the
 * server, the clock is the server's, and {@link ExamAttemptSession#resume()} rebuilds the
 * screen when the connection returns (E10.15).
 */
public final class TakeExamView extends AbstractScreen {

    private final StackPane root = new StackPane();
    private final BorderPane content = new BorderPane();
    private final ReconnectBanner banner = new ReconnectBanner();
    private final ExamDoneView done = new ExamDoneView();
    private final AttemptModel model = new AttemptModel();

    private ExamEntrySession entry;
    private ExamAttemptSession attempt;
    private ExamEntryView entryView;
    private ExamFormView formView;

    @Override
    protected Parent build() {
        entry = new ExamEntrySession(dispatcher())
                .onChange(this::refreshEntry)
                .onStarted(this::enterForm);
        attempt = new ExamAttemptSession(dispatcher(), eventBus(), model, DelayedRunner.shared());
        attempt.onExtended(this::playExtension);
        attempt.onFinished(this::showEnding);
        attempt.onDisconnected(event -> banner.showDisconnected(event.serverLabel()));

        entryView = new ExamEntryView(entry);
        formView = new ExamFormView(model, new CountdownTimer(CountdownLogic.systemClock()));
        formView.onSelect(attempt::select);
        formView.onSubmit(this::confirmSubmit);

        model.onChange(this::refreshForm);
        done.onLeave(this::leave);

        banner.setOnRetry(() -> attempt.resume().thenRun(banner::hide));
        content.setCenter(entryView);

        root.getStyleClass().add("take-exam");
        root.getChildren().addAll(new VBox(banner, content), done);
        return root;
    }

    @Override
    public void onShow(NavParams params) {
        // Every entry starts at the code screen. The server decides what that code means,
        // including "you have already handed this one in" (F6.7), so there is no local
        // memory of finished exams to go stale.
        done.reset();
        attempt.stop();
        entry.reset();
        content.setCenter(entryView);
        // The dashboard card can hand over a code it has already validated. It is a
        // pre-fill and nothing more: the join still happens here and the identity step
        // still follows it.
        params.get(client.features.home.StudentHomeSession.CODE_PARAM, String.class)
                .ifPresent(entryView::prefillCode);
        entryView.focusCurrentField();
    }

    @Override
    public void onHide() {
        // Stops the countdown and unsubscribes from the bus. The attempt keeps running on
        // the server, which is the whole point: leaving the screen is not leaving the exam.
        formView.countdown().stop();
        attempt.stop();
    }

    @Override
    public boolean listensToEvents() {
        // The two sessions register themselves on the bus; the screen has no @Subscribe
        // method of its own, and registering it would only add a second unregister to
        // forget.
        return false;
    }

    // ===================== Flow ==========================================

    private void refreshEntry() {
        entryView.refresh();
    }

    private void refreshForm() {
        formView.refresh();
    }

    /** The identity screen succeeded: the paper takes over and the clock starts. */
    private void enterForm(AttemptForm form) {
        attempt.start(form.header().executionId(), form);
        content.setCenter(formView);
        formView.refresh();
        formView.startCountdown();
        model.outcome().ifPresent(done::show);
    }

    /**
     * The Submit Confirm moment (F6.9).
     *
     * <p>The grid's chips jump to their question and dismiss the dialog, which is what makes
     * the warning useful: a student told she has three blanks can go and fill them in
     * without hunting.
     */
    private void confirmSubmit() {
        if (!model.isLive()) {
            return;
        }
        AnswerGridView grid = new AnswerGridView();
        boolean[] jumped = {false};
        grid.show(model.chips(), index -> {
            jumped[0] = true;
            model.goTo(index);
            closeDialogOwning(grid);
        });

        boolean confirmed = WarnConfirm.show(root.getScene() == null ? null : root.getScene().getWindow(),
                WarnConfirm.spec(ExamCopy.SUBMIT_TITLE)
                        .explanation(ExamCopy.submitNote(model.unansweredCount())
                                + " " + ExamCopy.remainingNote(model.remaining()))
                        .detail(grid)
                        .confirmText(ExamCopy.SUBMIT_CONFIRM)
                        .cancelText(ExamCopy.SUBMIT_CANCEL)
                        .warn());
        if (confirmed && !jumped[0]) {
            attempt.submit();
        }
    }

    /** The Time Extended moment (F7.1 ⚑): chip flash, floating gain, and a naming toast. */
    private void playExtension(TimerExtended extension) {
        formView.refresh();
        formView.countdown().showExtension(extension.gained());
        ToastStack toasts = toasts();
        if (toasts != null) {
            toasts.success(ExamCopy.EXTENSION_TOAST_TITLE, ExamCopy.extensionToast(extension));
        }
    }

    /** The ending, either mood (F6.4/F6.10). */
    private void showEnding(AttemptOutcome outcome) {
        formView.countdown().stop();
        formView.refresh();
        done.show(outcome);
    }

    /** The single action on the takeover: leave, and do not come back to this paper. */
    private void leave() {
        done.reset();
        attempt.stop();
        entry.reset();
        content.setCenter(entryView);
        navigator().reset(Routes.HOME_STUDENT.id());
    }

    /**
     * Closes the confirm dialog from inside its own detail node.
     *
     * <p>{@code WarnConfirm} is deliberately a blocking boolean, so a chip that jumps to a
     * question has to close the window it is sitting in. Walking up from the node is the
     * only handle it has, and it is safe: the detail node is only ever mounted inside that
     * dialog.
     */
    private static void closeDialogOwning(javafx.scene.Node node) {
        if (node.getScene() != null && node.getScene().getWindow() != null) {
            node.getScene().getWindow().hide();
        }
    }
}
