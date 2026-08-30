package client.features.release;

import client.ui.anim.Animations;
import client.ui.anim.Motion;
import client.ui.components.Buttons;
import client.ui.components.FormField;
import client.ui.components.Icons;
import client.ui.components.ModalHost;
import client.ui.components.logic.ValidationState;
import common.dto.release.ReleasableVersion;
import common.dto.release.ReleaseCodeIssue;
import common.dto.release.ReleaseCreateRequest;
import common.dto.release.ReleaseOptions;
import common.dto.release.ReleaseWindow;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * "Which exam, when, and under what code?" (Presentation tier, E9.5 — F5.1, F5.2, F5.3).
 *
 * <p>{@code WarnConfirm}'s sibling, built the same way ({@code RejectDialog} is the other
 * one): a modal transparent stage over a scrim, inheriting the owner's stylesheets and dark
 * class. It cannot be a {@code WarnConfirm} because this is an input, not a confirmation.
 *
 * <h2>F5.1 is the picker, not a check</h2>
 *
 * <p>The list it offers is {@code RELEASE_OPTIONS_GET}'s answer, which the server built from
 * a query filtered on {@code APPROVED}. There is therefore no unapproved exam to pick, which
 * is what PRD §6 means by "impossible (not listed)". The two empty states are different
 * sentences because their next steps are different: nothing approved yet sends her to her
 * coordinator, nothing written yet sends her to the builder.
 *
 * <h2>F5.2 validates live, with the server's own rule</h2>
 *
 * <p>Every change re-runs {@link ReleaseCreateRequest#windowProblem}, the same method the
 * server refuses with, and the confirm button is disabled while it complains. Sharing the
 * method is what stops the client's idea of a legal window from drifting from the server's,
 * and the sentence under the fields is {@link ReleaseWindow}'s, so the inline hint and the
 * error that would come back are one string.
 *
 * <h2>The code field, and what the dice actually does (F5.3)</h2>
 *
 * <p>§4 says the teacher defines the code, so there is a field, and it validates as she types
 * against {@link ReleaseCreateRequest#codeProblem} — C-1's shape, the same method the server
 * refuses with. Leaving it blank is a legitimate answer and the prompt says so.
 *
 * <p>The dice beside it <b>clears the field</b> rather than filling it, and that is the honest
 * shape of the affordance. Filling it would mean either rolling a code on the client, which
 * cannot know whether it is already in use by a sitting students could still enter (§5 makes
 * that a server rule, answerable only inside the transaction that inserts), or asking the
 * server for a preview, which would either hand out a code somebody else takes a moment later
 * or hold a reservation nobody has asked us to build. Clearing promises exactly what happens:
 * the server picks a readable one, and she meets it on the reveal that follows a second later.
 * The button says "Generate for me" for the same reason.
 */
public final class CreateReleaseDialog {

    /** What a fresh dialog proposes: opening in an hour, closing an hour after that. */
    private static final int DEFAULT_LEAD_MINUTES = 60;

    /**
     * One answer: which version, the window, and the code she chose.
     *
     * @param examVersionId the approved version
     * @param openAt        when the window opens
     * @param closeAt       when it shuts
     * @param code          what she typed, or {@code null} when she left it to the server
     */
    public record Answer(long examVersionId, Instant openAt, Instant closeAt, String code) {
    }

    private CreateReleaseDialog() {
    }

    /**
     * Shows the dialog modally and blocks until she answers.
     *
     * @param owner   the window to dim and block; may be {@code null}
     * @param options the approved versions she may release
     * @param now     the server's clock reading, so the defaults are not a wrong laptop's idea
     *                of the time
     * @param zone    the viewer's time zone, which the pickers work in
     * @return what she chose, or empty when she dismissed it
     */
    /**
     * Builds the dialog's fields and their live validation, without showing anything.
     *
     * <p>Split out of {@link #show} because a modal {@code showAndWait} blocks the FX thread
     * by design and therefore cannot be driven by a test that is also on it. Everything that
     * decides anything is here — which fields exist, what the dice does, when Release is
     * enabled — so the interaction test drives the real node graph with the real listeners
     * attached, and {@link #show} is left with nothing but stage plumbing.
     *
     * @param options the approved versions she may release
     * @param now     the server's clock reading, so the defaults are not a wrong laptop's idea
     *                of the time
     * @param zone    the viewer's time zone, which the pickers work in
     * @return the form, ready to be put in a scene
     */
    public static Form form(ReleaseOptions options, Instant now, ZoneId zone) {
        return new Form(Objects.requireNonNull(options, "options"),
                Objects.requireNonNull(now, "now"),
                Objects.requireNonNull(zone, "zone"));
    }

    /**
     * The dialog's fields, their rules and their current answer.
     *
     * <p>Holds no stage and no scene: it can be built, poked and asserted on without anything
     * being shown, which is what makes the F5.3 rules testable through the toolkit rather than
     * only through the session beneath it.
     */
    public static final class Form {

        private final ComboBox<ReleasableVersion> versions = new ComboBox<>();
        private final TextField codeField = new TextField();
        private final FormField code;
        private final Button dice = Buttons.outline(ReleaseCopy.CODE_GENERATE);
        private final Button confirm = Buttons.primary(ReleaseCopy.CREATE_CONFIRM);
        private final Button dismiss = Buttons.secondary(ReleaseCopy.CREATE_DISMISS);
        private final Label complaint = new Label();
        private final Moment opens;
        private final Moment closes;
        private final VBox node;
        private final ZoneId zone;

        private Form(ReleaseOptions options, Instant now, ZoneId zone) {
            this.zone = zone;
            versions.getItems().setAll(options.versions());
            versions.setConverter(labels());
            versions.setMaxWidth(Double.MAX_VALUE);
            versions.setPromptText(ReleaseCopy.VERSION_LABEL);
            versions.getStyleClass().add("release-version-picker");
            if (options.versions().size() == 1) {
                versions.getSelectionModel().selectFirst();
            }

            codeField.setPromptText(ReleaseCopy.CODE_PROMPT);
            codeField.setPrefColumnCount(6);
            codeField.getStyleClass().add("release-code-field");
            code = new FormField(ReleaseCopy.CODE_LABEL, codeField).hint(ReleaseCopy.CODE_HINT);
            dice.getStyleClass().add("release-dice");

            LocalDateTime suggestedOpen = LocalDateTime.ofInstant(now, zone)
                    .plusMinutes(DEFAULT_LEAD_MINUTES).withSecond(0).withNano(0);
            opens = new Moment(ReleaseCopy.OPENS_LABEL, suggestedOpen);
            closes = new Moment(ReleaseCopy.CLOSES_LABEL,
                    suggestedOpen.plusMinutes(DEFAULT_LEAD_MINUTES));

            complaint.getStyleClass().addAll("small", "danger-text", "release-window-error");
            complaint.setWrapText(true);
            confirm.setDefaultButton(true);
            dismiss.setCancelButton(true);

            Runnable revalidate = () -> revalidate(now);
            dice.setOnAction(e -> {
                // Clears rather than fills: see the class note. The hint changes to say what
                // the server is about to do, so this is not a button that appears to do
                // nothing.
                codeField.clear();
                code.hint(ReleaseCopy.CODE_GENERATED);
                revalidate.run();
            });
            codeField.textProperty().addListener((obs, old, typed) -> revalidate.run());
            opens.onChange(revalidate);
            closes.onChange(revalidate);
            versions.valueProperty().addListener((obs, old, picked) -> revalidate.run());

            node = build(options, versions, code, dice, opens, closes, complaint,
                    dismiss, confirm);
            revalidate.run();
        }

        /** Re-runs both shared rules and moves the button and the messages to match. */
        private void revalidate(Instant now) {
            ReleasableVersion chosen = versions.getValue();
            ReleaseCreateRequest candidate = candidate(chosen);
            ReleaseWindow window = candidate.windowProblem(now, ReleaseCreateRequest.PAST_GRACE);
            ReleaseCodeIssue codeIssue = candidate.codeProblem();

            // Pristine while the box is empty: blank is a request, not a mistake, and a red
            // border on a field nobody has typed in is a scold.
            code.apply(candidate.hasCode()
                    ? ValidationState.from(codeIssue == null
                            ? Optional.empty() : Optional.of(codeIssue.sentence()))
                    : ValidationState.pristine());

            String sentence = window != null ? window.sentence()
                    : codeIssue != null ? codeIssue.sentence() : "";
            complaint.setText(sentence);
            complaint.setVisible(!sentence.isEmpty());
            complaint.setManaged(!sentence.isEmpty());
            confirm.setDisable(window != null || codeIssue != null || chosen == null);
        }

        private ReleaseCreateRequest candidate(ReleasableVersion chosen) {
            return new ReleaseCreateRequest(chosen == null ? 0 : chosen.examVersionId(),
                    opens.instant(zone), closes.instant(zone), codeField.getText());
        }

        /** @return the node graph, for a scene. */
        public VBox node() {
            return node;
        }

        /** @return the button that confirms; the caller wires it to its own stage. */
        public Button confirmButton() {
            return confirm;
        }

        /** @return the button that dismisses; the caller wires it to its own stage. */
        public Button dismissButton() {
            return dismiss;
        }

        /** @return what she has chosen, or empty when no exam is picked. */
        public Optional<Answer> answer() {
            ReleasableVersion chosen = versions.getValue();
            if (chosen == null) {
                return Optional.empty();
            }
            return Optional.of(new Answer(chosen.examVersionId(),
                    opens.instant(zone), closes.instant(zone),
                    ReleaseCreateRequest.normalizeCode(codeField.getText())));
        }
    }

    /**
     * Shows the dialog modally and blocks until she answers.
     *
     * <p>Stage plumbing only: every rule is in {@link Form}.
     *
     * @param owner   the window to dim and block; may be {@code null}
     * @param options the approved versions she may release
     * @param now     the server's clock reading
     * @param zone    the viewer's time zone, which the pickers work in
     * @return what she chose, or empty when she dismissed it
     */
    public static Optional<Answer> show(Window owner, ReleaseOptions options,
                                        Instant now, ZoneId zone) {
        Answer[] answer = {null};

        Stage stage = new Stage(StageStyle.TRANSPARENT);
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner != null) {
            stage.initOwner(owner);
        }

        Form form = form(options, now, zone);
        form.dismissButton().setOnAction(e -> stage.close());
        form.confirmButton().setOnAction(e -> {
            answer[0] = form.answer().orElse(null);
            stage.close();
        });

        StackPane scrim = ModalHost.mount(stage, owner, form.node());

        Animations.fadeIn(scrim, Motion.DIALOG_MS);
        Animations.scaleIn(form.node(), Motion.DIALOG_FROM_SCALE, Motion.DIALOG_MS);
        stage.showAndWait();
        return Optional.ofNullable(answer[0]);
    }

    // ===================== Layout ========================================

    @SuppressWarnings("checkstyle:ParameterNumber")
    private static VBox build(ReleaseOptions options, ComboBox<ReleasableVersion> versions,
                              FormField code, Button dice, Moment opens, Moment closes,
                              Label complaint, Button dismiss, Button confirm) {
        StackPane iconDisc = new StackPane(Icons.of(Icons.RELEASE, Icons.SIZE_LARGE, "dialog-icon"));
        iconDisc.getStyleClass().add("dialog-icon-disc");

        Label title = new Label(ReleaseCopy.CREATE_TITLE);
        title.getStyleClass().add("dialog-title");

        Label explanation = new Label(ReleaseCopy.VERSION_HINT);
        explanation.getStyleClass().add("dialog-explanation");
        explanation.setWrapText(true);

        VBox headingText = new VBox(6, title, explanation);
        HBox heading = new HBox(14, iconDisc, headingText);
        heading.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(headingText, Priority.ALWAYS);

        VBox body = new VBox(14, heading);
        if (options.isEmpty()) {
            // Two empty states, because the next step is different for each.
            Label empty = new Label(options.waitingOnApproval()
                    ? ReleaseCopy.NONE_APPROVED
                    : ReleaseCopy.NONE_WRITTEN);
            empty.setWrapText(true);
            empty.getStyleClass().addAll("muted", "release-picker-empty");
            body.getChildren().add(empty);
            confirm.setDisable(true);
        } else {
            HBox codeRow = new HBox(10, code, dice);
            codeRow.setAlignment(Pos.BOTTOM_LEFT);
            body.getChildren().addAll(new FormField(ReleaseCopy.VERSION_LABEL, versions),
                    codeRow, opens.node(), closes.node(), complaint);
        }
        body.getChildren().add(Buttons.row(dismiss, confirm));

        // 2026-08-30, live session, U-47: `wide` is what buys the Opens / Closes rows
        // their width. The number lives in the stylesheet beside `.hsts-dialog`, not
        // here, because an explicit max width set from code is a second opinion about
        // the same measurement, and the stylesheet's is the one that wins at runtime.
        body.getStyleClass().addAll("hsts-dialog", "release-create-dialog", "wide");
        return body;
    }

    /**
     * A date and a time, which is what a teacher actually thinks in.
     *
     * <h2>2026-08-30, live session, U-47: the row has to be allowed its width</h2>
     *
     * <p>The teacher could not read the clock: "too smooshed together, I can't see the
     * numbers clearly". Two things were doing that, and only the second is obvious.
     *
     * <p>The caption sat <b>beside</b> the controls on a 70px minimum, so a row that was
     * already the widest thing in a 520px card started 82px behind. The caption now sits
     * <b>above</b> its controls, which costs a line of height and gives the whole measure
     * back to the four fields that need it.
     *
     * <p>And an {@code HBox} that runs out of room does not clip, it <b>shrinks</b>: it
     * takes every child down towards its minimum width, and a {@link Spinner}'s computed
     * minimum is narrower than two digits plus its own arrows. A preferred width is only a
     * preference, so it lost that argument silently. The minimums below are the fix; the
     * preferred widths are kept beside them so the row reads the same when there is room
     * to spare.
     */
    private static final class Moment {

        /** Two digits, a caret column, and the padding a text field draws inside. */
        private static final double SPINNER_WIDTH = 80;

        /** Enough for a long localised date and the calendar button beside it. */
        private static final double DATE_WIDTH = 150;

        private final DatePicker date = new DatePicker();
        private final Spinner<Integer> hour = new Spinner<>(0, 23, 9);
        private final Spinner<Integer> minute = new Spinner<>(0, 59, 0, 5);
        private final VBox row;

        private Moment(String label, LocalDateTime initial) {
            date.setValue(initial.toLocalDate());
            hour.getValueFactory().setValue(initial.getHour());
            minute.getValueFactory().setValue(initial.getMinute());
            date.setPrefWidth(DATE_WIDTH);
            date.setMinWidth(DATE_WIDTH);
            date.getStyleClass().add("release-moment-date");
            for (Spinner<Integer> spinner : List.of(hour, minute)) {
                spinner.setPrefWidth(SPINNER_WIDTH);
                spinner.setMinWidth(SPINNER_WIDTH);
                spinner.setEditable(true);
                spinner.getStyleClass().add("release-moment-spinner");
            }

            Label at = new Label("at");
            at.getStyleClass().add("muted");
            Label colon = new Label(":");
            colon.getStyleClass().addAll("muted", "release-moment-colon");
            HBox fields = new HBox(10, date, at, hour, colon, minute);
            fields.setAlignment(Pos.CENTER_LEFT);
            fields.setMinWidth(Region.USE_PREF_SIZE);
            fields.getStyleClass().add("release-moment");

            Label caption = new Label(label);
            caption.getStyleClass().add("field-label");
            this.row = new VBox(6, caption, fields);
            this.row.getStyleClass().add("release-moment-row");
        }

        private void onChange(Runnable listener) {
            date.valueProperty().addListener((obs, old, value) -> listener.run());
            hour.valueProperty().addListener((obs, old, value) -> listener.run());
            minute.valueProperty().addListener((obs, old, value) -> listener.run());
        }

        private Instant instant(ZoneId zone) {
            LocalDate day = date.getValue();
            if (day == null) {
                return null;
            }
            return LocalDateTime.of(day, LocalTime.of(hour.getValue(), minute.getValue()))
                    .atZone(zone).toInstant();
        }

        private VBox node() {
            return row;
        }
    }

    private static StringConverter<ReleasableVersion> labels() {
        return new StringConverter<>() {
            @Override
            public String toString(ReleasableVersion version) {
                return version == null ? "" : version.label();
            }

            @Override
            public ReleasableVersion fromString(String text) {
                return null;
            }
        };
    }
}
