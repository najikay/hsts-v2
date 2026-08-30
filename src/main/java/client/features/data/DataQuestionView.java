package client.features.data;

import client.core.NavParams;
import client.features.bank.BankCopy;
import client.features.bank.QuestionDetailPane;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
import client.ui.screen.AbstractScreen;
import common.dto.bank.QuestionDetail;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.ByteArrayInputStream;

/**
 * One question of the school's bank, read-only (Presentation tier, E15.2 — F9.3, S-7, T-11.1,
 * U-44, the lead's ruling of 2026-08-30).
 *
 * <p>What the Data browser's Questions rows open. Reached from a row and from nowhere else, for
 * the reason {@code CheckedFormView} and {@code ExamPreviewView} are: it is a view of one
 * question, and a rail item that needed a question chosen first would be a dead end. The way back
 * is the shell's navbar Back, which {@code ShellBoot} aliases to the Data screen.
 *
 * <p>The question itself is drawn by {@link QuestionDetailPane}, which is the bank's own detail
 * rendering lifted out of {@code BankView} — same heading, same stem, same four options with the
 * correct one marked, same topic and difficulty, same illustration, same author line. The
 * principal <b>may</b> see the key: F9.3 gives her the bank to browse and {@code QuestionDetail}
 * has carried it to every staff reader since the lead's ruling of 2026-08-21, whose threat model
 * is students rather than staff.
 *
 * <h2>What is missing, and is missing by construction ⚑</h2>
 *
 * <p>There is no Edit, no Delete and no dialog, and the way that is guaranteed is that this file
 * adds nothing after the shared pane. The bank's screen appends its actions row at exactly the
 * point where this one appends the history panel; the shared renderer itself draws no control at
 * all, so there is no mode here that a later change could set the wrong way round (T-11.3).
 *
 * <p>Thin by the usual rule, and on the coverage exclusion list by name: every sentence is in
 * {@link DataDetailCopy} or {@link BankCopy} and every load decision is in
 * {@link DataQuestionSession}, all of which are measured.
 */
public final class DataQuestionView extends AbstractScreen {

    /** The nav parameter a Questions row carries: the five-digit display id. */
    public static final String PARAM_QUESTION = "displayId5";

    private final VBox root = new VBox(14);
    private final Label heading = new Label(DataDetailCopy.QUESTION_TITLE);
    private final Label subtitle = new Label(DataDetailCopy.QUESTION_SUBTITLE);
    private final Label readOnly = new Label(DataDetailCopy.READ_ONLY_NOTE);

    private final VBox detailBody = new VBox(12);
    private final VBox historyBody = new VBox(10);
    private final EmptyState unavailable = new EmptyState(Icons.BANK,
            DataDetailCopy.QUESTION_FAILED_TITLE, DataDetailCopy.QUESTION_FAILED_HINT);

    private DataQuestionSession session;

    @Override
    protected Parent build() {
        session = new DataQuestionSession(dispatcher(), onFxThread()).onChange(this::render);

        heading.getStyleClass().add("h1");
        subtitle.getStyleClass().addAll("small", "muted");
        subtitle.setWrapText(true);
        readOnly.getStyleClass().addAll("small", "muted", "data-read-only-note");
        readOnly.setWrapText(true);

        detailBody.getStyleClass().addAll("bank-detail-body", "data-question-detail");
        historyBody.getStyleClass().addAll("bank-history", "data-question-history");
        unavailable.getStyleClass().add("data-question-unavailable");

        VBox column = new VBox(16, detailBody, historyBody);
        column.getStyleClass().addAll("hsts-card", "bank-detail");
        column.setPadding(new Insets(18));

        ScrollPane scroller = new ScrollPane(column);
        scroller.setFitToWidth(true);
        scroller.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(scroller, Priority.ALWAYS);

        root.getStyleClass().addAll("hsts-page", "principal-data-question");
        root.setPadding(new Insets(24, 28, 24, 24));
        root.getChildren().addAll(new VBox(2, heading, subtitle, readOnly), unavailable, scroller);
        return root;
    }

    @Override
    public void onShow(NavParams params) {
        session.open(params.getString(PARAM_QUESTION, ""));
    }

    @Override
    public boolean listensToEvents() {
        // Nothing is pushed here. The bank's edit locks are a teacher's concern (F10.0) and
        // this screen has no control they could disable.
        return false;
    }

    // ===================== Rendering =====================================

    private void render() {
        QuestionDetail detail = session.detail().orElse(null);
        boolean failed = session.state().showsError();

        show(unavailable, failed);
        if (detail == null) {
            heading.setText(DataDetailCopy.QUESTION_TITLE);
            detailBody.getChildren().clear();
            historyBody.getChildren().clear();
            return;
        }
        heading.setText(DataDetailCopy.questionHeading(detail.displayId5()));
        detailBody.getChildren().setAll(QuestionDetailPane.readOnly(detail, imageNode(detail)));
        renderHistory();
    }

    /**
     * The version timeline, always open.
     *
     * <p>Unlike the bank's, which is behind a toggle: that screen is a browse where a teacher
     * clicking down a list would pay for a timeline she did not ask for, and this one is one
     * question opened deliberately by a reader whose entire purpose here is to read the data as
     * entered (F9.3).
     */
    private void renderHistory() {
        historyBody.getChildren().clear();

        Label title = new Label(DataDetailCopy.HISTORY_TITLE);
        title.getStyleClass().add("h3");
        historyBody.getChildren().add(title);

        if (session.historyError().isPresent()) {
            Label failure = new Label(session.historyError().get());
            failure.getStyleClass().addAll("small", "danger-text");
            failure.setWrapText(true);
            historyBody.getChildren().add(failure);
            return;
        }
        if (session.historyState().showsSkeleton()) {
            Label loading = new Label(DataDetailCopy.HISTORY_LOADING);
            loading.getStyleClass().addAll("small", "muted");
            historyBody.getChildren().add(loading);
            return;
        }
        historyBody.getChildren().addAll(QuestionDetailPane.history(session.historyEntries()));
    }

    /**
     * The illustration, or the honest sentence about why it is not there.
     *
     * <p>Three different absences and three different sentences, on {@code BankView}'s reasoning
     * exactly: the question has no picture, the picture is on its way, and the picture could not
     * be fetched. One "no image" for all three would tell a reader whose diagram failed to load
     * that the teacher never attached one.
     */
    private Node imageNode(QuestionDetail detail) {
        if (!detail.hasImage()) {
            Label none = new Label(BankCopy.NO_IMAGE);
            none.getStyleClass().addAll("small", "muted", "bank-no-image");
            return none;
        }
        switch (session.imageState()) {
            case READY -> {
                byte[] bytes = session.image();
                if (bytes != null && bytes.length > 0) {
                    ImageView view = new ImageView(new Image(new ByteArrayInputStream(bytes)));
                    view.setPreserveRatio(true);
                    view.setFitWidth(560);
                    view.getStyleClass().add("bank-image");
                    return view;
                }
                Label broken = new Label(BankCopy.IMAGE_FAILED);
                broken.getStyleClass().addAll("small", "danger-text");
                broken.setWrapText(true);
                return broken;
            }
            case ERROR -> {
                Label failed = new Label(BankCopy.IMAGE_FAILED);
                failed.getStyleClass().addAll("small", "danger-text", "bank-image-error");
                failed.setWrapText(true);
                return failed;
            }
            default -> {
                Label loading = new Label(BankCopy.IMAGE_LOADING);
                loading.getStyleClass().addAll("small", "muted", "bank-image-loading");
                return loading;
            }
        }
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
